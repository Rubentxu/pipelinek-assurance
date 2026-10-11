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

    /**
     * Output que el Step `assurance.check` entrega al SDK.
     *
     * B3 (Bloque B) — `reportDigest` + `summary` + `outcome` ("Success"
     * | "Failure" | "Aborted"). G3 (Bloque G) — `failure` lleva el
     * counterexample estructurado cuando el outcome es Failure o
     * Aborted (en Aborted, `failure` es opcional y puede ser null
     * si la causa es una excepción interna del motor de orchestrator
     * que no queremos enmascarar; en Failure por Mandatory, `failure`
     * SIEMPRE trae el counterexample de la assertion bloqueante).
     *
     * `replayPolicy` es la policy que el handler eligió en este run
     * concreto: "MEMOIZED" si el fingerprint cubría engine + suite +
     * evidence digests + adapter version, "RERUN" en cualquier otro
     * caso. La cadena está en el output (no en el `StepContract`)
     * porque la policy es decisión del handler, no del descriptor.
     */
    @Serializable
    data class Output(
        val reportDigest: String,
        val summary: SummaryDto,
        val outcome: String,
        val failure: CounterexampleDto? = null,
        val replayPolicy: String = "RERUN",
    )

    /**
     * Counterexample estructurado: una assertion Mandatory que falló
     * (o, en `Aborted`, la causa que abortó el Step). El shape es
     * deliberadamente plano: la spec del SDK
     * (`PIPELINEK_PLUGIN_CONTRACT.md §assurance.check`) sólo necesita
     * que el caller pueda renderizarlo en su log de falla y
     * persistirlo en el report artifact.
     */
    @Serializable
    data class CounterexampleDto(
        val kind: String,
        val message: String,
        val blockingAssertionIds: List<String> = emptyList(),
        val sourceLocation: String? = null,
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
     * G3 (Bloque G) — `run` ahora también computa la policy de
     * replay: si `evidenceAdapterVersion` está presente, devuelve
     * `replayPolicy = "MEMOIZED"`. Si no, cae a `"RERUN"`. El
     * `Output.failure` se popula con el counterexample estructurado
     * cuando el outcome es Failure (Mandatory) o Aborted (engine
     * sin coverage).
     *
     * @param input el DTO del Step
     * @param evidenceItems items precargados (modo offline / tests)
     * @param providers registry de EvidenceProvider; el plugin NO
     *        registra providers propios — los aporta el SDK
     * @param runtime runtime congelado con las lenses del SDK
     * @param assertionsById mapa `AssertionId → AssuranceAssertion`
     *        que ejecuta la lógica de la assertion
     * @param evidenceAdapterVersion versión del adapter de evidence
     *        que el SDK está proveyendo vía `ASSURANCE_PROVIDER`
     *        capability. Si es null/blank, el fingerprint queda
     *        incompleto y la policy cae a RERUN (no se puede
     *        memoizar sobre un adapter que puede cambiar).
     */
    fun run(
        input: Input,
        evidenceItems: List<dev.pipelinek.assurance.domain.evidence.EvidenceItem>,
        providers: ProviderRegistry,
        runtime: FrozenAssuranceRuntime,
        assertionsById: Map<AssertionId, dev.pipelinek.assurance.engine.AssuranceAssertion<*>>,
        evidenceAdapterVersion: String? = null,
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

        // 5. Counterexample estructurado (G3). El plugin NO lanza
        //    excepción cuando una Mandatory falla: serializa la
        //    causa en `failure` y deja que el SDK mapee a
        //    `StepOutcome.Failure` desde el output. En Aborted, el
        //    counterexample es opcional (puede ser null si la
        //    causa fue una excepción del orchestrator que no
        //    queremos enmascarar).
        //
        //    Para Failure: re-llamamos `evaluateEnforcement` con el
        //    mismo `report + suite + mode` que el outcome, y
        //    cosechamos los `blockingFailures` (los
        //    `EnforcementEntry` con `enforcement = Mandatory` y
        //    `result != Passed`). Eso preserva la asociación
        //    assertion ↔ política que A1 introdujo y que el
        //    plugin tiene que exportar al SDK.
        val failureDto = counterexampleOf(
            report = report,
            suite = suite,
            mode = mode,
            outcome = outcome,
        )

        // 6. Policy de replay (G3). El fingerprint es completo
        //    sólo si tenemos `evidenceAdapterVersion` (no
        //    null/blank). Sin él, no podemos memoizar: el adapter
        //    podría cambiar entre runs. La engine version ya
        //    está en `runtime.engineVersion`, la suite id+version
        //    en el input, y los evidence digests en la orquestación.
        val replay = if (!evidenceAdapterVersion.isNullOrBlank()) "MEMOIZED" else "RERUN"

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
            failure = failureDto,
            replayPolicy = replay,
        )
    }

    /**
     * Construye un `CounterexampleDto` a partir del report, el
     * suite, el mode y el `StepOutcome` (Failure | Aborted).
     *
     * Si el outcome es `Failure` y tenemos un `EnforcementDecision.Failure`
     * vía `evaluateEnforcement`, cosechamos los `blockingFailures` con
     * `enforcement = Mandatory` y `result != Passed` y los ponemos
     * en `blockingAssertionIds`. Si el outcome es `Failure` por
     * `assurance-incomplete` (RequireComplete), devolvemos un
     * counterexample de kind "incomplete". Cualquier otro Failure
     * (o un Aborted) cae a "engine-error".
     */
    private fun counterexampleOf(
        report: dev.pipelinek.assurance.engine.AssuranceReport,
        suite: dev.pipelinek.assurance.engine.AssuranceSuiteIR,
        mode: AssuranceCheckStep.EnforcementMode,
        outcome: AssuranceCheckStep.StepOutcome,
    ): CounterexampleDto? {
        if (outcome is AssuranceCheckStep.StepOutcome.Success) return null
        val reason = when (outcome) {
            is AssuranceCheckStep.StepOutcome.Failure -> outcome.reason
            is AssuranceCheckStep.StepOutcome.Aborted -> outcome.reason
        }
        // Caso "assurance-incomplete" tiene su propio kind.
        if (reason.startsWith("assurance-incomplete")) {
            return CounterexampleDto(kind = "incomplete", message = reason)
        }
        // Caso Mandatory fail: re-corre evaluateEnforcement y cosecha
        // los bloqueantes. No lo hacemos cuando el reason es
        // "assurance gate failure (mode=...)" sin IDs: ese es el
        // caso "Mandatory fail no resolvió a IDs concretos", que
        // devolvemos como engine-error para no fingir
        // counterexample que no tenemos.
        if (suite.assertions.isNotEmpty() && outcome is AssuranceCheckStep.StepOutcome.Failure) {
            val engineMode = when (mode) {
                AssuranceCheckStep.EnforcementMode.FailClosed ->
                    dev.pipelinek.assurance.engine.EnforcementMode.FailClosed
                AssuranceCheckStep.EnforcementMode.ReportOnly ->
                    dev.pipelinek.assurance.engine.EnforcementMode.ReportOnly
            }
            val decision = dev.pipelinek.assurance.engine.AssuranceEngine.evaluateEnforcement(
                report = report,
                suite = suite,
                mode = engineMode,
            )
            if (decision is dev.pipelinek.assurance.engine.EnforcementDecision.Failure) {
                val blocking = decision.blockingFailures
                    .filter { it.enforcement == dev.pipelinek.assurance.engine.Enforcement.Mandatory }
                    .mapNotNull { it.assertionId?.value }
                if (blocking.isNotEmpty()) {
                    return CounterexampleDto(
                        kind = "mandatory-failure",
                        message = reason,
                        blockingAssertionIds = blocking,
                    )
                }
            }
        }
        return CounterexampleDto(kind = "engine-error", message = reason)
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
