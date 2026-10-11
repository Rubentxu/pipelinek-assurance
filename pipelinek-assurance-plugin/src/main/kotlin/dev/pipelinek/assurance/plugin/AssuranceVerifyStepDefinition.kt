package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.pipelinek.assurance.engine.SuiteId
import kotlinx.serialization.Serializable

/**
 * G4 — `AssuranceVerifyStepDefinition`: el Step `assurance.verify`
 * integrado con el SDK de PipelineK (vía `BodyContinuation`).
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
 * §`assurance.verify`, `04-adrs/ADR-003-BODY-OWNING-RUNTIME-VERIFY.md`,
 * `07-integrations/PIPELINEK_WORKSTREAM.md`.
 *
 * **Forma del Step (de la spec):**
 *   - `key = "assurance.verify"`
 *   - `BodyExecutionOwner.HANDLER_CONTINUATION`, `Sequential`,
 *     `BODY_CONTINUATION_CAPABILITY`.
 *   - input: `VerifyInput(name, suite, evidence, mode, completenessPolicy)`
 *   - output: `VerifyOutput(reportDigest, summary, outcome, failure, bodyOutcome, replayPolicy)`
 *   - el `outcome` combina el body con assurance según la matriz
 *     documentada en el ADR-003.
 *
 * **G4 (Bloque G) — invariantes que enforce este step:**
 *   - V49: `BodyContinuation` se invoca **exactamente una vez**
 *     (PIPELINEK_PLUGIN_CONTRACT §3, paso 4).
 *   - V50: `BodyOutcome.Cancelled` se traduce a
 *     `CancellationException` propagada, NO a `StepOutcome.Failure`.
 *   - V51: El fallo original del body prevalece sobre el veredicto
 *     de assurance ("no ocultar el fallo del body").
 *   - V52: Replay no duplica efectos. Si el handler se invoca dos
 *     veces con el mismo contexto, la continuación se invoca una
 *     sola vez (la ventana de observación se cierra tras la primera
 *     ejecución).
 *
 * **AAT-6:** este Step no expone `AssertionResult` en su API
 * pública. La `outcome` es un string cerrado
 * (`"Success"` / `"Failure"` / `"Aborted"`) — un `StepOutcome`
 * del plugin, no del core.
 */
object AssuranceVerifyStepDefinition {

    const val KEY: String = "assurance.verify"

    // -----------------------------------------------------------------------
    // Input / Output DTOs (shape del wire contract del Step)
    // -----------------------------------------------------------------------

    /**
     * Input del Step `assurance.verify`.
     *
     * A diferencia de `assurance.check`, `assurance.verify` recibe
     * **un body que el Step debe ejecutar** vía `BodyContinuation`.
     * El body es el código bajo prueba; assurance es la red de
     * seguridad que verifica su comportamiento.
     */
    @Serializable
    data class Input(
        val name: String,
        val suite: SuiteDto,
        val evidence: List<EvidenceRefDto> = emptyList(),
        val mode: String = "FailClosed",
        val completenessPolicy: String = "RequireComplete",
    )

    /**
     * Output del Step `assurance.verify`.
     *
     * G4 (Bloque G) — `bodyOutcome` ("Success" | "Failure" |
     * "Cancelled") preserva el outcome original del body. `failure`
     * lleva el counterexample estructurado (body o assurance)
     * cuando el outcome es Failure o Aborted.
     *
     * `replayPolicy` es la policy que el handler eligió en este
     * run concreto: "MEMOIZED" si el fingerprint cubría
     * engine + suite + evidence digests + adapter version,
     * "RERUN" en cualquier otro caso.
     */
    @Serializable
    data class Output(
        val reportDigest: String,
        val summary: SummaryDto,
        val outcome: String,
        val failure: CounterexampleDto? = null,
        val bodyOutcome: String = "Success",
        val bodyFailureMessage: String? = null,
        val replayPolicy: String = "RERUN",
    )

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

    /**
     * Fingerprint del Step `assurance.verify` para replay.
     *
     * Misma estructura que `assurance.check` (engine + suite +
     * evidence + adapter version). El body NO entra en el
     * fingerprint porque el body es código bajo prueba, no
     * input del step.
     */
    data class Fingerprint(
        val suiteId: SuiteId,
        val suiteVersion: String,
        val evidenceDigests: List<Digest>,
        val engineVersion: String,
    )

    // -----------------------------------------------------------------------
    // Outcome del body (mirror del SDK `BodyOutcome` para que el
    // plugin tenga su propio shape, no acoplado al SDK)
    // -----------------------------------------------------------------------

    /**
     * Outcome del body ejecutado por el SDK vía `BodyContinuation`.
     *
     * - `Success`: el body terminó bien.
     * - `Failure`: el body lanzó una excepción o devolvió error.
     *   `message` puede ser null; en ese caso se usa el nombre
     *   de la clase del error.
     * - `Cancelled`: el body fue cancelado por un ancestro
     *   (control estructurado). El handler propaga la
     *   `CancellationException` sin traducirla a `Failure`.
     */
    sealed interface BodyOutcome {
        data object Success : BodyOutcome
        data class Failure(val error: Throwable, val message: String? = null) : BodyOutcome
        data class Cancelled(val reason: String) : BodyOutcome
    }

    // -----------------------------------------------------------------------
    // Ejecución del Step
    // -----------------------------------------------------------------------

    /**
     * Lógica del Step: dado un input y un outcome de body
     * (producido por el `BodyContinuation` del SDK), produce el
     * output combinando body + assurance.
     *
     * G4 (Bloque G) — la invariante V49 (body invoca 1 vez) NO
     * se enforce aquí; la enforce el handler en el SDK (es la
     * parte que tiene la referencia al continuation). Esta función
     * sólo combina resultados.
     *
     * El caller (el handler) ya invocó el body; aquí recibimos
     * el outcome. La combinación es determinista.
     */
    fun run(
        input: Input,
        bodyOutcome: BodyOutcome,
        evidenceItems: List<dev.pipelinek.assurance.domain.evidence.EvidenceItem>,
        providers: ProviderRegistry,
        runtime: FrozenAssuranceRuntime,
        assertionsById: Map<AssertionId, dev.pipelinek.assurance.engine.AssuranceAssertion<*>>,
        evidenceAdapterVersion: String? = null,
    ): Output {
        // 1. Si el body se canceló (V50), propagamos como
        //    "Aborted" con bodyOutcome = "Cancelled". El
        //    handler del SDK es responsable de rethrow la
        //    CancellationException, no este código.
        if (bodyOutcome is BodyOutcome.Cancelled) {
            return Output(
                reportDigest = Digest.ofUtf8("cancelled:${input.name}").hex,
                summary = SummaryDto(0, 0, 0, 0, 0),
                outcome = "Aborted",
                failure = CounterexampleDto(
                    kind = "cancelled",
                    message = "Body was cancelled: ${bodyOutcome.reason}",
                ),
                bodyOutcome = "Cancelled",
                bodyFailureMessage = bodyOutcome.reason,
                replayPolicy = "RERUN",
            )
        }

        // 2. Body falló (V51): el fallo prevalece. No
        //    ejecutamos assurance — el report sería irrelevante.
        if (bodyOutcome is BodyOutcome.Failure) {
            val message = bodyOutcome.message
                ?: bodyOutcome.error::class.simpleName
                ?: "body failure"
            return Output(
                reportDigest = Digest.ofUtf8("body-failed:$message").hex,
                summary = SummaryDto(0, 0, 0, 0, 0),
                outcome = "Failure",
                failure = CounterexampleDto(
                    kind = "body-failure",
                    message = message,
                    blockingAssertionIds = emptyList(),
                ),
                bodyOutcome = "Failure",
                bodyFailureMessage = message,
                replayPolicy = "RERUN",
            )
        }

        // 3. Body success. Ejecuta assurance y combina el
        //    veredicto de la suite con el body success.
        val checkOutput = AssuranceCheckStepDefinition.run(
            input = AssuranceCheckStepDefinition.Input(
                name = input.name,
                suite = input.suite.toCheckDto(),
                evidence = input.evidence.map { it.toCheckDto() },
                mode = input.mode,
                completenessPolicy = input.completenessPolicy,
            ),
            evidenceItems = evidenceItems,
            providers = providers,
            runtime = runtime,
            assertionsById = assertionsById,
            evidenceAdapterVersion = evidenceAdapterVersion,
        )

        return Output(
            reportDigest = checkOutput.reportDigest,
            summary = checkOutput.summary.toVerifyDto(),
            outcome = checkOutput.outcome,
            failure = checkOutput.failure?.toVerifyDto(),
            bodyOutcome = "Success",
            bodyFailureMessage = null,
            replayPolicy = checkOutput.replayPolicy,
        )
    }

    // -----------------------------------------------------------------------
    // Conversores DTO (verify ⇄ check)
    // -----------------------------------------------------------------------

    private fun SuiteDto.toCheckDto(): AssuranceCheckStepDefinition.SuiteDto =
        AssuranceCheckStepDefinition.SuiteDto(
            id = id,
            version = version,
            requiredEvidence = requiredEvidence,
            lenses = lenses.map {
                AssuranceCheckStepDefinition.LensDto(
                    id = it.id,
                    kind = it.kind,
                    inputCapabilities = it.inputCapabilities,
                    arguments = it.arguments,
                    outputSchema = it.outputSchema,
                )
            },
            assertions = assertions.map {
                AssuranceCheckStepDefinition.AssertionDto(
                    id = it.id,
                    lensRef = it.lensRef,
                    operator = it.operator,
                    operands = it.operands,
                    severity = it.severity,
                    enforcement = it.enforcement,
                    completenessRequirements = it.completenessRequirements,
                    rationale = it.rationale,
                    admittedAuthorities = it.admittedAuthorities,
                )
            },
        )

    private fun EvidenceRefDto.toCheckDto(): AssuranceCheckStepDefinition.EvidenceRefDto =
        AssuranceCheckStepDefinition.EvidenceRefDto(
            mediaType = mediaType,
            digest = digest,
            logicalRole = logicalRole,
        )

    private fun AssuranceCheckStepDefinition.SummaryDto.toVerifyDto(): SummaryDto =
        SummaryDto(passed, failed, inconclusive, unsupported, errored)

    private fun AssuranceCheckStepDefinition.CounterexampleDto.toVerifyDto(): CounterexampleDto =
        CounterexampleDto(
            kind = kind,
            message = message,
            blockingAssertionIds = blockingAssertionIds,
            sourceLocation = sourceLocation,
        )

    /**
     * Helper para tests: traduce el `BodyOutcome` del SDK
     * (paquete `dev.rubentxu.pipeline.v2.domain.step`) al
     * `BodyOutcome` del plugin.
     */
    fun toPluginBodyOutcome(
        sdkOutcome: dev.rubentxu.pipeline.v2.domain.step.BodyOutcome,
    ): BodyOutcome = when (sdkOutcome) {
        is dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Cancelled ->
            BodyOutcome.Cancelled(sdkOutcome.reason.name)
        is dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Completed -> when (val o = sdkOutcome.outcome) {
            is dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure ->
                BodyOutcome.Failure(
                    error = IllegalStateException(o.failure.message),
                    message = o.failure.message,
                )
            else -> BodyOutcome.Success
        }
    }
}
