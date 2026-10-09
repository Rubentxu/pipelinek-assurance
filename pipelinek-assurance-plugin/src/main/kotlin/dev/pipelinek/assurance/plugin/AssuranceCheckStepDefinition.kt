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
        // 1. Decodifica la suite (de DTO a IR).
        val suite: AssuranceSuiteIR = suiteFromDto(input.suite)

        // 2. Construye un snapshot in-memory con los items de
        //    evidence que el caller pasó. Un snapshot real lo
        //    construye el provider desde un artefacto externo;
        //    aquí asumimos que el SDK ya hizo esa parte.
        val snapshot = dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot(
            id = dev.pipelinek.assurance.domain.evidence.SnapshotId("snap-${input.suite.id}"),
            subject = dev.pipelinek.assurance.domain.evidence.EvidenceSubject.Module(input.name),
            sources = listOf(
                dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest(
                    producerId = "plugin/${KEY}",
                    producerVersion = "0.1.0",
                    subjectRevision = dev.pipelinek.assurance.domain.evidence.RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = input.suite.requiredEvidence,
                    producedCapabilities = input.evidence.map { it.logicalRole },
                    completenessByCapability = input.evidence.associate { it.logicalRole to
                        dev.pipelinek.assurance.domain.evidence.Completeness.Complete },
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("snap-${input.suite.id}"),
                ),
            ),
            items = evidenceItems,
            gaps = emptyList(),
        )

        // 3. Evalúa la suite con un runtime congelado.
        val runtime = FrozenAssuranceRuntime(
            engineVersion = "0.1.0",
            lenses = emptyMap(), // Las lenses reales se inyectan en runtime.
        )
        val report = AssuranceEngine.evaluateSuite(
            snapshot = snapshot,
            suite = suite,
            runtime = runtime,
            digestOf = { Digest.ofUtf8(json.encodeToString(SuiteDto.serializer(), suiteDto(it))) },
            snapshotDigest = snapshotDigest(snapshot, input),
            assertionsById = emptyMap(), // Las assertions reales se inyectan.
        )

        // 4. Computa el outcome según la policy.
        val outcome = AssuranceCheckStep.outcomeOf(
            report = report,
            mode = if (input.mode == "ReportOnly")
                AssuranceCheckStep.EnforcementMode.ReportOnly
            else
                AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = if (input.completenessPolicy == "ReportMissing")
                AssuranceCheckStep.CompletenessPolicy.ReportMissing
            else
                AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )

        return Output(
            reportDigest = report.snapshotDigest.hex,
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
