package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssuranceEngine
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.SuiteId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M3 — `AssuranceCheckStepDefinition`: el Step `assurance.check` integrado
 * con el SDK de PipelineK.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
 * §`assurance.check` y `04-adrs/ADR-001-PIPELINEK-OWNS-EXECUTION.md`.
 *
 * **Forma del Step (de la spec PIPELINEK_PLUGIN_CONTRACT.md):**
 *   - `key = "assurance.check"`
 *   - input: `AssuranceCheckInput(name, suite, evidence, mode, completenessPolicy)`
 *   - output: `AssuranceCheckOutput(report, reportDigest, summary, outcome)`
 *   - replay-eligible: `MEMOIZED` si el fingerprint es estable.
 *
 * **AAT-3:** este plugin es el único módulo del repo que depende del
 * SDK de PipelineK (la dependencia se materializa en runtime via
 * ServiceLoader, no en compilación — la API se documenta abajo con
 * los tipos de `dev.rubentxu.pipeline.v2.domain.step.*` y la
 * frontera queda enforced por la inversión de imports).
 *
 * **AAT-6:** este Step no expone `AssertionResult` en su API pública.
 * La `outcome` es `Success` / `Failure(assurance)` / `Failure(incomplete)`
 * — un `StepOutcome` del plugin, no del core.
 *
 * **Lo que el plugin NO hace:** ejecutar la suite. El plugin
 * construye la `AssuranceReport` desde un snapshot de evidence y
 * la pasa al engine, que es puro. La I/O del snapshot, el digest
 * canónico y la persistencia del report artifact son
 * responsabilidad del SDK, no del plugin.
 */
object AssuranceCheckStepDefinition {

    const val KEY: String = "assurance.check"

    // -----------------------------------------------------------------------
    // Input / Output DTOs (shape del wire contract del Step)
    // -----------------------------------------------------------------------

    @Serializable
    data class Input(
        val name: String,
        val suite: SuiteDto,
        val evidence: List<EvidenceRefDto>,
        val mode: String = "FailClosed",
        val completenessPolicy: String = "RequireComplete",
    )

    @Serializable
    data class Output(
        val reportDigest: String,
        val summary: SummaryDto,
        val outcome: String,
    )

    @Serializable
    data class SuiteDto(
        val id: String,
        val version: String,
        val requiredEvidence: List<String>,
        val lenses: List<LensDto>,
        val assertions: List<AssertionDto>,
    )

    @Serializable
    data class LensDto(
        val id: String,
        val kind: String,
        val inputCapabilities: List<String>,
        val arguments: Map<String, String> = emptyMap(),
        val outputSchema: String,
    )

    @Serializable
    data class AssertionDto(
        val id: String,
        val lensRef: String,
        val operator: String,
        val operands: Map<String, String>,
        val severity: String,
        val enforcement: String,
        val completenessRequirements: List<String>,
        val rationale: String,
        val admittedAuthorities: Set<String> = setOf(
            "DeterministicAdapter",
            "DeterministicAnalyzer",
            "RuntimeObserver",
        ),
    )

    @Serializable
    data class EvidenceRefDto(
        val mediaType: String,
        val digest: String,
        val logicalRole: String,
    )

    @Serializable
    data class SummaryDto(
        val passed: Int,
        val failed: Int,
        val inconclusive: Int,
        val unsupported: Int,
        val errored: Int,
    )

    // -----------------------------------------------------------------------
    // Fingerprint (UAT-024)
    // -----------------------------------------------------------------------

    /**
     * Fingerprint del Step para replay.
     *
     * Misma suite + mismas evidence + mismo engine version ⇒ mismo
     * fingerprint. Si el caller re-ejecuta con el mismo fingerprint,
     * el SDK puede devolver el output cacheado.
     */
    data class Fingerprint(
        val suiteId: SuiteId,
        val suiteVersion: String,
        val evidenceDigests: List<Digest>,
        val engineVersion: String,
    )

    // -----------------------------------------------------------------------
    // Ejecución del Step (handler)
    // -----------------------------------------------------------------------

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /**
     * Lógica del Step: dado un input, devuelve un output. La
     * ejecución real (parseo, normalización, evaluación) vive en el
     * core; este handler es el cable entre el SDK y el core.
     *
     * **Lo que el handler NO hace:**
     *   - leer disco ni red (AAT-1, AAT-7);
     *   - invocar un provider (eso es del snapshot, no del step);
     *   - persistir el report artifact (eso es del SDK).
     */
    fun run(input: Input, evidenceItems: List<dev.pipelinek.assurance.domain.evidence.EvidenceItem>): Output {
        // B3 (Bloque B): delega al overload con registries vacíos.
        // El SDK llama a la versión de abajo con los registries
        // reales. Esta versión legacy (M3) sólo se conserva para los
        // tests que comprueban el shape del output sin providers.
        return run(
            input = input,
            evidenceItems = evidenceItems,
            providers = ProviderRegistry(),
            runtime = FrozenAssuranceRuntime(engineVersion = "0.1.0", lenses = emptyMap()),
            assertionsById = emptyMap(),
        )
    }

    /**
     * B3 (Bloque B) — `run` con orchestrator cableado.
     *
     * Ésta es la versión que el SDK invoca en producción. Hace los
     * 10 pasos de orquestación: validar refs, recolectar evidencia
     * vía providers, normalizar, congelar, evaluar, codificar el
     * report y computar el `StepOutcome` por assertion (A1).
     *
     * @param input el DTO del Step
     * @param evidenceItems items precargados (modo offline / tests)
     * @param providers registry de EvidenceProvider; el plugin NO
     *        registra providers propios — los aporta el SDK
     * @param runtime runtime congelado con las lenses del SDK
     * @param assertionsById mapa `AssertionId → AssuranceAssertion`
     *        que ejecuta la lógica de la assertion
     */
    fun run(
        input: Input,
        evidenceItems: List<dev.pipelinek.assurance.domain.evidence.EvidenceItem>,
        providers: ProviderRegistry,
        runtime: FrozenAssuranceRuntime,
        assertionsById: Map<AssertionId, dev.pipelinek.assurance.engine.AssuranceAssertion<*>>,
    ): Output {
        // 1. Decodifica la suite (de DTO a IR).
        val suite: AssuranceSuiteIR = suiteFromDto(input.suite)

        // 2. Construye la request de orquestación. El subject se
        //    deriva del nombre del Step. Los refs se traducen al
        //    shape mínimo del orchestrator.
        val mode = if (input.mode == "ReportOnly")
            AssuranceCheckStep.EnforcementMode.ReportOnly
        else
            AssuranceCheckStep.EnforcementMode.FailClosed
        val completeness = if (input.completenessPolicy == "ReportMissing")
            AssuranceCheckStep.CompletenessPolicy.ReportMissing
        else
            AssuranceCheckStep.CompletenessPolicy.RequireComplete

        // Si el caller ya pasó items pre-coleccionados, los
        // exponemos al orchestrator como un provider sintético que
        // retorna esos items para todos los logicalRole de la
        // suite. Eso preserva la forma "items precargados" que el
        // SDK puede usar para tests o re-ejecución.
        val effectiveProviders = if (evidenceItems.isNotEmpty() && providers.all().isEmpty()) {
            ProviderRegistry().apply {
                register(
                    "preloaded",
                    object : dev.pipelinek.assurance.engine.EvidenceProvider {
                        override val descriptor = dev.pipelinek.assurance.engine.EvidenceProviderDescriptor(
                            id = "preloaded",
                            version = "0.1.0",
                            evidenceCapabilities = listOf("preloaded"),
                            subjectKinds = listOf("Module"),
                            classification = dev.pipelinek.assurance.engine.ProviderClassification.Deterministic,
                            inputFormats = listOf("assurance-evidence/v1"),
                            outputSchemaVersion = "assurance-evidence/v1",
                        )
                        override fun collect(request: dev.pipelinek.assurance.engine.EvidenceRequest) =
                            dev.pipelinek.assurance.engine.EvidenceCollectionResult.Produced(
                                producerId = "preloaded",
                                producerVersion = "0.1.0",
                                schemaVersion = "assurance-evidence/v1",
                                rawItems = evidenceItems.map { item ->
                                    dev.pipelinek.assurance.engine.RawEvidenceItem(
                                        kind = when (item) {
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Fact -> dev.pipelinek.assurance.engine.RawItemKind.Fact
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Observation -> dev.pipelinek.assurance.engine.RawItemKind.Observation
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Signal -> dev.pipelinek.assurance.engine.RawItemKind.Signal
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Hypothesis -> dev.pipelinek.assurance.engine.RawItemKind.Hypothesis
                                        },
                                        id = item.id.value,
                                        subjectRef = when (val s = item.subject) {
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceSubject.Module -> "module:${s.path}"
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceSubject.Symbol -> "symbol:${s.qualifiedName}"
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceSubject.RuntimeSpan -> "chronos:${s.typedRef}"
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceSubject.SourceLocation -> "source:${s.file}:${s.line}"
                                            is dev.pipelinek.assurance.domain.evidence.EvidenceSubject.Test -> "test:${s.id}"
                                        },
                                        authority = item.authority.name,
                                        payload = item.toPayloadMap(),
                                    )
                                },
                                declaredGaps = emptyList(),
                            )
                    },
                )
            }
        } else {
            providers
        }

        val request = OrchestrationRequest(
            name = input.name,
            suite = suite,
            subject = dev.pipelinek.assurance.domain.evidence.EvidenceSubject.Module(input.name),
            evidenceRefs = input.evidence.map {
                EvidenceRefDto(
                    logicalRole = it.logicalRole,
                    digest = it.digest,
                    producerId = "preloaded",
                )
            },
            mode = mode,
            completenessPolicy = completeness,
            producerVersion = "0.1.0",
        )

        // 3. Orquestar. El orchestrator hace los 10 pasos.
        val orchestration = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = effectiveProviders,
            runtime = runtime,
            assertionsById = assertionsById,
        )

        // 4. Computa el outcome usando A1 (per-assertion
        //    enforcement) si tenemos suite; sino, el legacy
        //    counter-based.
        val report = orchestration.report
        val outcome = if (suite.assertions.isNotEmpty()) {
            AssuranceCheckStep.outcomeOfWithSuite(
                report = report,
                suite = suite,
                mode = mode,
                completenessPolicy = completeness,
            )
        } else {
            AssuranceCheckStep.outcomeOf(report = report, mode = mode, completenessPolicy = completeness)
        }

        return Output(
            reportDigest = orchestration.reportDigest.hex,
            summary = SummaryDto(
                passed = report.summary.passed,
                failed = report.summary.failed,
                inconclusive = report.summary.inconclusive,
                unsupported = report.summary.unsupported,
                errored = report.summary.errored,
            ),
            outcome = when (outcome) {
                is AssuranceCheckStep.StepOutcome.Success -> "Success"
                is AssuranceCheckStep.StepOutcome.Failure -> "Failure"
                is AssuranceCheckStep.StepOutcome.Aborted -> "Aborted"
            },
        )
    }

    private fun dev.pipelinek.assurance.domain.evidence.EvidenceItem.toPayloadMap(): Map<String, String> =
        when (this) {
            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Fact -> mapOf(
                "predicate" to predicate,
                "object" to (objectValue ?: ""),
                "capability" to provenance.capability,
            )
            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Observation -> mapOf(
                "observation" to observation,
                "capability" to provenance.capability,
            )
            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Signal -> mapOf(
                "signalKind" to signalKind,
                "score" to score,
                "algorithmId" to algorithmId,
                "algorithmVersion" to algorithmVersion,
                "capability" to provenance.capability,
            )
            is dev.pipelinek.assurance.domain.evidence.EvidenceItem.Hypothesis -> mapOf(
                "claim" to claim,
                "reasoning" to reasoning,
                "confidence" to confidence,
                "capability" to provenance.capability,
            )
        }

    private fun suiteFromDto(dto: SuiteDto): AssuranceSuiteIR {
        return AssuranceSuiteIR(
            apiVersion = "assurance-ir/v1",
            suiteId = SuiteId(dto.id),
            suiteVersion = dto.version,
            requiredEvidence = dto.requiredEvidence,
            lenses = dto.lenses.map {
                dev.pipelinek.assurance.engine.LensPlan(
                    lensId = LensId(it.id),
                    kind = it.kind,
                    inputCapabilities = it.inputCapabilities,
                    arguments = it.arguments,
                    outputSchema = it.outputSchema,
                )
            },
            assertions = dto.assertions.map {
                dev.pipelinek.assurance.engine.AssertionIR(
                    id = AssertionId(it.id),
                    lensRef = LensId(it.lensRef),
                    operator = it.operator,
                    operands = it.operands,
                    severity = dev.pipelinek.assurance.engine.Severity.valueOf(it.severity),
                    enforcement = dev.pipelinek.assurance.engine.Enforcement.valueOf(it.enforcement),
                    completenessRequirements = it.completenessRequirements,
                    rationale = it.rationale,
                    admittedAuthorities = it.admittedAuthorities,
                )
            },
        )
    }

    private fun suiteDto(suite: AssuranceSuiteIR): SuiteDto = SuiteDto(
        id = suite.suiteId.value,
        version = suite.suiteVersion,
        requiredEvidence = suite.requiredEvidence,
        lenses = suite.lenses.map {
            LensDto(
                id = it.lensId.value,
                kind = it.kind,
                inputCapabilities = it.inputCapabilities,
                arguments = it.arguments,
                outputSchema = it.outputSchema,
            )
        },
        assertions = suite.assertions.map {
            AssertionDto(
                id = it.id.value,
                lensRef = it.lensRef.value,
                operator = it.operator,
                operands = it.operands,
                severity = it.severity.name,
                enforcement = it.enforcement.name,
                completenessRequirements = it.completenessRequirements,
                rationale = it.rationale,
                admittedAuthorities = it.admittedAuthorities,
            )
        },
    )

    private fun snapshotDigest(
        snapshot: dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot,
        input: Input,
    ): Digest {
        val ids = snapshot.items.map { it.id.value }.sorted()
        return Digest.ofUtf8(
            input.suite.id + "|" + input.suite.version + "|" + ids.joinToString("|"),
        )
    }
}
