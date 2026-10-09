package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.SuiteId
import dev.pipelinek.assurance.plugin.AssuranceCheckStep.EvidenceInputRef

/**
 * M7 — Step `assurance.verify`.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
 * §`assurance.verify`, `04-adrs/ADR-003-BODY-OWNING-RUNTIME-VERIFY.md`,
 * `07-integrations/PIPELINEK_WORKSTREAM.md`.
 *
 * **Estado actual:** la integración con el SDK de PipelineK está
 * bloqueada (M7 depende de un artefacto externo). Este archivo
 * define el CONTRATO del Step como tipos puros, análogo a
 * `AssuranceCheckStep` (M3). La integración se cablea cuando el SDK
 * esté disponible; UAT-012..UAT-015 (matriz body × assurance) y
 * UAT-025 (crash-safe artifact) se certifican con un run real.
 *
 * **Lo que el step hace (de la spec):**
 *   1. Validar inputs.
 *   2. Crear `AssuranceEvaluationId`.
 *   3. Solicitar `WindowToken` (Chronos) si aplica.
 *   4. Invocar `BodyContinuation` **exactamente una vez** (V1).
 *   5. Preservar cancelación como control estructurado (no se
 *      traduce a `Failure`).
 *   6. Sellar la ventana de evidencia.
 *   7. Recoger evidencia.
 *   8. Evaluar la suite.
 *   9. Producir el report artifact.
 *  10. Combinar body + assurance **sin ocultar el fallo del body**.
 *
 * **Outcome matrix (PIPELINEK_PLUGIN_CONTRACT §3):**
 *   - body success, assurance pass: Success
 *   - body success, Mandatory fail: Failure(assurance)
 *   - body success, Inconclusive + RequireComplete: Failure(incomplete)
 *   - body failure, cualquier assurance: original body failure
 *   - cancelled, cualquier assurance: cancellation se propaga
 */
object AssuranceVerifyStep {

    const val STEP_KEY: String = "assurance.verify"

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    data class Input(
        val suite: AssuranceSuiteIR,
        val bodyHandler: BodyContinuation,
        val evidence: List<EvidenceInputRef>,
        val mode: AssuranceCheckStep.EnforcementMode = AssuranceCheckStep.EnforcementMode.FailClosed,
        val completenessPolicy: AssuranceCheckStep.CompletenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
    )

    /**
     * Continuation que ejecuta el body del Step. Vive como tipo del
     * Step; la integración con el SDK cablea la firma real cuando
     * el `BodyContinuation` del SDK esté disponible.
     */
    fun interface BodyContinuation {
        fun run(): BodyOutcome
    }

    /**
     * Resultado del body: success, failure (preservando el error) o
     * cancellation (que NO se traduce a failure).
     */
    sealed interface BodyOutcome {
        data object Success : BodyOutcome
        data class Failure(val error: Throwable, val message: String? = null) : BodyOutcome
        data class Cancelled(val reason: String) : BodyOutcome
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    /**
     * Output del Step `assurance.verify`. Combina el outcome del body
     * con el outcome de la suite, **sin ocultar el fallo del body**.
     *
     * La tabla de combinación está implementada literalmente en
     * `combine` y certificada por los tests.
     */
    data class Output(
        val body: BodyOutcome,
        val report: AssuranceCheckStep.ReportArtifactRef?,
        val reportDigest: Digest?,
        val summary: AssuranceCheckStep.AssuranceSummary?,
        val outcome: StepOutcome,
    )

    sealed interface StepOutcome {
        data object Success : StepOutcome
        data class Failure(val reason: String) : StepOutcome
        data class Aborted(val reason: String) : StepOutcome
        data class Cancelled(val reason: String) : StepOutcome
    }

    // -----------------------------------------------------------------------
    // Combinación body × assurance
    // -----------------------------------------------------------------------

    /**
     * Combina el outcome del body con el de la suite. La regla es
     * "no ocultar el fallo del body": un body failure siempre es
     * failure, no se etiqueta como success aunque assurance pase.
     *
     * La cancelación es un caso especial: se propaga como
     * `Cancelled`, no como `Failure`, porque PipelineK usa la
     * cancelación como control estructurado (no es un error del
     * código bajo prueba).
     */
    fun combine(body: BodyOutcome, report: AssuranceReport?): StepOutcome {
        return when (body) {
            is BodyOutcome.Cancelled -> StepOutcome.Cancelled(body.reason)
            is BodyOutcome.Failure -> {
                // El body falló. El fallo se preserva tal cual. El
                // report de assurance se adjunta si existe, pero NO
                // cambia el outcome del body.
                val reason = buildString {
                    append("body failure: ").append(body.message ?: body.error::class.simpleName.orEmpty())
                    if (report != null) {
                        append("; assurance report: ${report.summary.failed} failed, ${report.summary.inconclusive} inconclusive")
                    }
                }
                StepOutcome.Failure(reason)
            }
            is BodyOutcome.Success -> {
                // Body ok: el outcome depende de la suite.
                if (report == null) {
                    StepOutcome.Success
                } else {
                    val failed = report.summary.failed
                    val inconclusive = report.summary.inconclusive
                    when {
                        failed > 0 -> StepOutcome.Failure("assurance: $failed assertion(s) failed (Mandatory)")
                        inconclusive > 0 -> StepOutcome.Failure("assurance-incomplete: $inconclusive inconclusive assertion(s)")
                        else -> StepOutcome.Success
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // V1: BodyContinuation se invoca exactamente una vez.
    // -----------------------------------------------------------------------

    /**
     * Helper que enforce la invariante V1 del step `assurance.verify`:
     * la `BodyContinuation` del SDK se invoca **exactamente una vez**
     * (PIPELINEK_PLUGIN_CONTRACT §3, paso 4).
     *
     * El handler se ejecuta UNA vez; el processor recibe el outcome y
     * puede iterar los hijos de la suite u otros elementos sin volver
     * a tocar el body. Una refactorización que itere los children y
     * vuelva a llamar `handler.run()` (M-P03) ejecuta el body N veces
     * y dispara side-effects por cada elemento. La función hace esa
     * distinción visible: `handler` para el body, `processor` para
     * los hijos.
     */
    fun runBodyOnce(
        handler: BodyContinuation,
        processor: (BodyOutcome) -> Unit = {},
    ): BodyOutcome {
        var wasInvoked = false
        val outcome: BodyOutcome = try {
            if (wasInvoked) {
                BodyOutcome.Cancelled("body continuation invoked more than once (V1 violated)")
            } else {
                wasInvoked = true
                handler.run()
            }
        } catch (t: Throwable) {
            BodyOutcome.Failure(t)
        }
        processor(outcome)
        return outcome
    }
}
