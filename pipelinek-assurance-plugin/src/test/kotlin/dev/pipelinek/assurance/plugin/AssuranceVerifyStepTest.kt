package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.ProofRef
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M7 — Tests de la matriz body × assurance de `assurance.verify`.
 *
 * La tabla (de PIPELINEK_PLUGIN_CONTRACT §3):
 *
 *   - body success, assurance pass:        Success
 *   - body success, Mandatory fail:        Failure(assurance)
 *   - body success, Inconcomplete:          Failure(incomplete)
 *   - body failure, cualquier assurance:    body failure se preserva
 *   - cancelled, cualquier assurance:       Cancelled (no Failure)
 *
 * La regla de "no ocultar el fallo del body" es la más importante:
 * un body failure con assurance pass debe seguir siendo body failure.
 * El test que verifica esto es UAT-013, el más crítico de M7.
 */
class AssuranceVerifyStepTest : AnnotationSpec() {

    @Test
    fun body_success_con_assurance_pass_es_success() {
        val outcome = AssuranceVerifyStep.combine(
            AssuranceVerifyStep.BodyOutcome.Success,
            reportWith(failed = 0, inconclusive = 0),
        )
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Success>()
    }

    @Test
    fun body_success_con_mandatory_fail_es_failure_de_assurance() {
        val outcome = AssuranceVerifyStep.combine(
            AssuranceVerifyStep.BodyOutcome.Success,
            reportWith(failed = 1, inconclusive = 0),
        )
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Failure>()
        (outcome as AssuranceVerifyStep.StepOutcome.Failure).reason shouldBe "assurance: 1 assertion(s) failed (Mandatory)"
    }

    @Test
    fun body_success_con_inconclusive_es_failure_incomplete() {
        val outcome = AssuranceVerifyStep.combine(
            AssuranceVerifyStep.BodyOutcome.Success,
            reportWith(failed = 0, inconclusive = 1),
        )
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Failure>()
        (outcome as AssuranceVerifyStep.StepOutcome.Failure).reason shouldBe "assurance-incomplete: 1 inconclusive assertion(s)"
    }

    @Test
    fun M_P01_body_failure_se_preserva_incluso_con_assurance_pass() {
        // M-P01: "assurance failure sobrescribe body failure". La
        // mutación sería devolver `Success` aquí. La ley exige
        // devolver `Failure` con la razón del body.
        val body = AssuranceVerifyStep.BodyOutcome.Failure(
            error = IllegalStateException("body crashed"),
            message = "body crashed during deploy",
        )
        val outcome = AssuranceVerifyStep.combine(body, reportWith(failed = 0, inconclusive = 0))
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Failure>()
        val reason = (outcome as AssuranceVerifyStep.StepOutcome.Failure).reason
        // La razón DEBE mencionar el body failure, no la assurance.
        (reason.contains("body failure") || reason.contains("body crashed")) shouldBe true
    }

    @Test
    fun M_P01_body_failure_preserva_razon_aun_con_assurance_fallando() {
        // M-P01 redundancia: con report con assurance fallando Y
        // body fallando, el body failure sigue siendo el outcome
        // primario. La razón DEBE mencionar el body, no sólo el
        // assurance. Un mutante que devuelva Success aquí
        // también es detectable.
        val body = AssuranceVerifyStep.BodyOutcome.Failure(
            error = IllegalStateException("body crashed"),
            message = "body crashed during deploy",
        )
        val outcome = AssuranceVerifyStep.combine(body, reportWith(failed = 3, inconclusive = 0))
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Failure>()
        val reason = (outcome as AssuranceVerifyStep.StepOutcome.Failure).reason
        // M-P01 redundancia: la razón incluye "body failure" Y
        // "assurance report", preservando ambos.
        (reason.contains("body failure")) shouldBe true
        (reason.contains("assurance report")) shouldBe true
    }

    @Test
    fun M_P02_cancelled_se_propag_a_como_cancelled_no_como_failure() {
        // M-P02: "cancellation capturada como Failure". La
        // cancelación NO es un fallo del código bajo prueba; es
        // control estructurado. Devolver `Failure` haría que un
        // pipeline reagendara la ejecución cuando debería
        // simplemente propagar la cancelación.
        val body = AssuranceVerifyStep.BodyOutcome.Cancelled("user pressed Ctrl-C")
        val outcome = AssuranceVerifyStep.combine(body, reportWith(failed = 1, inconclusive = 0))
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Cancelled>()
        (outcome as AssuranceVerifyStep.StepOutcome.Cancelled).reason shouldBe "user pressed Ctrl-C"
    }

    @Test
    fun M_P02_cancelled_sin_report_se_propag_a_como_cancelled() {
        // M-P02 redundancia: la cancelación sin report (null)
        // también se propaga como Cancelled. Un mutante que
        // devuelva Failure aquí es detectable.
        val body = AssuranceVerifyStep.BodyOutcome.Cancelled("user pressed Ctrl-C")
        val outcome = AssuranceVerifyStep.combine(body, null)
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Cancelled>()
    }

    @Test
    fun M_P03_run_body_once_invoca_el_handler_exactamente_una_vez() {
        // M-P03: "handler itera children fuera de BodyContinuation".
        // V1 del contrato dice que el body se invoca una sola vez
        // por step. El processor es el ÚNICO que puede iterar
        // (sobre los hijos de la suite, por ejemplo), y recibe el
        // outcome, NO el handler. Un mutante que dentro del
        // processor vuelva a llamar a handler.run() ejecuta el
        // body N veces.
        val counter = intArrayOf(0)
        val handler = AssuranceVerifyStep.BodyContinuation {
            counter[0] += 1
            AssuranceVerifyStep.BodyOutcome.Success
        }
        // Processor que simula "iterar 5 children". El processor
        // tiene el handler en closure, lo cual es la trampa del
        // mutante. La forma correcta lo recibe pero NO lo invoca.
        val outcome = AssuranceVerifyStep.runBodyOnce(handler) { _ ->
            repeat(5) {
                // Un mutante que pusiera handler.run() aquí
                // ejecutaría el body 5 veces. La ley detecta
                // cualquier re-invocación.
                counter[0] // solo leemos; NO invocamos.
            }
        }
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.BodyOutcome.Success>()
        counter[0] shouldBe 1
    }

    @Test
    fun M_P03_run_body_once_processor_recibe_el_outcome_no_el_handler() {
        // M-P03 redundancia: el processor recibe el outcome del
        // body, no el handler. Una API alternativa donde el
        // processor recibiera el handler facilitaría el bug "iterar
        // children llamando al handler". Esta test confirma la forma
        // correcta: outcome es el dato, handler es la entrada.
        val counter = intArrayOf(0)
        val seen: MutableList<AssuranceVerifyStep.BodyOutcome> = mutableListOf()
        val handler = AssuranceVerifyStep.BodyContinuation {
            counter[0] += 1
            AssuranceVerifyStep.BodyOutcome.Failure(
                error = IllegalStateException("body"),
                message = "original",
            )
        }
        val outcome = AssuranceVerifyStep.runBodyOnce(handler) { body ->
            seen.add(body)
        }
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.BodyOutcome.Failure>()
        seen.size shouldBe 1
        // El outcome visto por el processor es el mismo devuelto
        // por runBodyOnce; no hay otro BodyOutcome "secreto" en
        // juego.
        (seen[0] === outcome) shouldBe true
        // M-P03 redundancia: con el mutante que añade handler.run()
        // antes del processor, el contador subiría a 2.
        counter[0] shouldBe 1
    }

    @Test
    fun body_success_sin_report_es_success() {
        // Sin report (e.g., el caller pasó null), el outcome es
        // Success — el body pasó, no hay evidencia que reporte.
        val outcome = AssuranceVerifyStep.combine(
            AssuranceVerifyStep.BodyOutcome.Success,
            null,
        )
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Success>()
    }

    @Test
    fun el_step_key_es_assurance_verify() {
        AssuranceVerifyStep.STEP_KEY shouldBe "assurance.verify"
    }

    // --- helpers ---

    private fun reportWith(failed: Int, inconclusive: Int): AssuranceReport {
        val results = buildList<AssertionResult> {
            repeat(failed) {
                add(
                    AssertionResult.Failed(
                        dev.pipelinek.assurance.engine.Counterexample.Cycle(
                            assertionId = AssertionId("a/$it"),
                            subjectRefs = emptyList(),
                            evidenceRefs = listOf(EvidenceId("e/$it")),
                            explanation = "test",
                            reproductionHints = emptyList(),
                            cycle = listOf("a", "b", "a"),
                        ),
                    ),
                )
            }
            repeat(inconclusive) {
                add(
                    AssertionResult.Inconclusive(
                        listOf(
                            EvidenceGap(
                                capability = "test.capability",
                                reason = EvidenceGap.GapReason.Unknown,
                                detail = "test",
                            ),
                        ),
                    ),
                )
            }
        }
        return AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-test"),
            snapshotDigest = Digest.ofUtf8("snapshot"),
            suiteDigest = Digest.ofUtf8("suite"),
            engineVersion = "test",
            results = results,
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
    }
}
