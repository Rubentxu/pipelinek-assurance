package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A1 (Bloque A) — Tests de la función pura `evaluateEnforcement`.
 *
 * Lo que se verifica (per el exit criteria de A1):
 *   - Tests para `Unknown → Inconclusive` (M-NORM-01 ya cubre el
 *     gap; aquí verificamos que el per-assertion enforcement
 *     preserva la decisión).
 *   - `Unsupported Mandatory → gate failure`: AAT-20.
 *   - `Error → gate failure`: AAT-8 (subtipo Error nunca es Pass).
 *   - Pruebas separadas para Advisory, Mandatory, Ratchet.
 *   - ReportOnly degrada Mandatory fail a Success pero el report
 *     documenta qué falló.
 *   - Mutaciones que intenten eliminar los checks de enforcement.
 *
 * La función pura `evaluateEnforcement` recibe `report` (con
 * `results`) y `suite` (con `assertions` y su `enforcement` por
 * id). La asociación assertion ↔ política se preserva: un
 * `Failed` que es `Advisory` no bloquea; un `Failed` que es
 * `Mandatory` bloquea. La posición en la lista de resultados se
 * empareja con la posición en la lista de assertions del IR.
 */
class EnforcementDecisionTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // Helpers para construir fixtures mínimos
    // -----------------------------------------------------------------

    private fun passedResult(id: String): AssertionResult.Passed =
        AssertionResult.Passed(
            ProofRef(
                snapshotId = "test-snapshot",
                evidenceIds = listOf(EvidenceId("e/$id")),
                assertionId = AssertionId(id),
            ),
        )

    private fun failedResult(id: String): AssertionResult.Failed =
        AssertionResult.Failed(
            Counterexample.Cycle(
                assertionId = AssertionId(id),
                subjectRefs = emptyList(),
                evidenceRefs = listOf(EvidenceId("e/$id")),
                explanation = "test failure",
                reproductionHints = emptyList(),
                cycle = listOf("a/$id", "b/$id"),
            ),
        )

    private fun inconclusiveResult(id: String): AssertionResult.Inconclusive =
        AssertionResult.Inconclusive(
            listOf(
                dev.pipelinek.assurance.domain.evidence.EvidenceGap(
                    "runtime.window",
                    dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unknown,
                ),
            ),
        )

    private fun unsupportedResult(reason: UnsupportedReason): AssertionResult.Unsupported =
        AssertionResult.Unsupported(reason)

    private fun errorResult(id: String): AssertionResult.Error =
        AssertionResult.Error(
            EvaluationFailure(
                phase = "evaluate",
                detail = "boom in $id",
            ),
        )

    private fun assertion(
        id: String,
        enforcement: Enforcement = Enforcement.Advisory,
    ): AssertionIR = AssertionIR(
        id = AssertionId(id),
        lensRef = LensId("l/$id"),
        operator = "no-edge",
        operands = emptyMap(),
        severity = Severity.Error,
        enforcement = enforcement,
        completenessRequirements = emptyList(),
        rationale = "test",
    )

    private fun reportWith(vararg results: AssertionResult): AssuranceReport =
        AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-test"),
            snapshotDigest = Digest.ofUtf8("test-snapshot"),
            suiteDigest = Digest.ofUtf8("test-suite"),
            engineVersion = "test",
            results = results.toList(),
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )

    private fun suiteWith(vararg assertions: AssertionIR): AssuranceSuiteIR {
        // AAT-16: cada assertion debe referenciar una lens declarada.
        // Construimos una lens por assertion para que el join sea 1:1.
        val lenses = assertions.map { a ->
            LensPlan(
                lensId = a.lensRef,
                kind = "test",
                inputCapabilities = emptyList(),
                outputSchema = "schema/v1",
            )
        }
        return AssuranceSuiteIR(
            apiVersion = "assurance/v1",
            suiteId = SuiteId("test-suite"),
            suiteVersion = "v1",
            requiredEvidence = emptyList(),
            lenses = lenses,
            assertions = assertions.toList(),
        )
    }

    // -----------------------------------------------------------------
    // Advisory: nunca bloquea
    // -----------------------------------------------------------------

    @Test
    fun advisory_failed_en_fail_closed_no_bloquea() {
        val report = reportWith(failedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Advisory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Passed>()
    }

    @Test
    fun advisory_unsupported_en_fail_closed_no_bloquea() {
        // Un Advisory puede ser Unsupported o Error sin bloquear.
        val report = reportWith(
            unsupportedResult(UnsupportedReason.UnknownLensKind("nope")),
        )
        val suite = suiteWith(assertion("a1", Enforcement.Advisory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Passed>()
    }

    // -----------------------------------------------------------------
    // Mandatory: cualquier no-Passed bloquea
    // -----------------------------------------------------------------

    @Test
    fun mandatory_failed_en_fail_closed_es_failure() {
        val report = reportWith(failedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        val failure = decision.shouldBeInstanceOf<EnforcementDecision.Failure>()
        failure.mode shouldBe EnforcementMode.FailClosed
        failure.blockingFailures.size shouldBe 1
        failure.blockingFailures[0].assertionId?.value shouldBe "a1"
    }

    @Test
    fun mandatory_unsupported_en_fail_closed_es_failure_AAT20() {
        // AAT-20: la rama "no evidence" (Unsupported) sobre
        // Mandatory es Failure, nunca Passed.
        val report = reportWith(
            unsupportedResult(UnsupportedReason.UnknownOperator("missing-op")),
        )
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Failure>()
    }

    @Test
    fun mandatory_error_en_fail_closed_es_failure() {
        // AAT-8: Error nunca degrada a Success; en Mandatory es
        // gate failure.
        val report = reportWith(errorResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Failure>()
    }

    @Test
    fun mandatory_inconclusive_en_fail_closed_es_failure() {
        // Inconclusive en Mandatory es failure: la incompletitud
        // del snapshot no produce un PASS por la puerta de atrás.
        val report = reportWith(inconclusiveResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Failure>()
    }

    @Test
    fun mandatory_passed_en_fail_closed_es_passed() {
        val report = reportWith(passedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Passed>()
    }

    // -----------------------------------------------------------------
    // ReportOnly degrada Mandatory fail a Advisory
    // -----------------------------------------------------------------

    @Test
    fun mandatory_failed_en_report_only_es_advisory() {
        val report = reportWith(failedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.ReportOnly,
        )
        val advisory = decision.shouldBeInstanceOf<EnforcementDecision.Advisory>()
        advisory.blockingFailures.size shouldBe 1
        advisory.blockingFailures[0].enforcement shouldBe Enforcement.Mandatory
    }

    @Test
    fun mandatory_passed_en_report_only_es_passed() {
        val report = reportWith(passedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Mandatory))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.ReportOnly,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Passed>()
    }

    // -----------------------------------------------------------------
    // Mezcla: una Advisory + una Mandatory
    // -----------------------------------------------------------------

    @Test
    fun mezcla_advisory_failed_con_mandatory_passed_es_passed() {
        val report = reportWith(
            failedResult("a-adv"),  // Advisory, ignorada
            passedResult("a-mand"), // Mandatory, debe pasar
        )
        val suite = suiteWith(
            assertion("a-adv", Enforcement.Advisory),
            assertion("a-mand", Enforcement.Mandatory),
        )
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Passed>()
    }

    @Test
    fun mezcla_advisory_passed_con_mandatory_failed_es_failure() {
        val report = reportWith(
            passedResult("a-adv"),
            failedResult("a-mand"),
        )
        val suite = suiteWith(
            assertion("a-adv", Enforcement.Advisory),
            assertion("a-mand", Enforcement.Mandatory),
        )
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
        )
        val failure = decision.shouldBeInstanceOf<EnforcementDecision.Failure>()
        // Solo la Mandatory fail aparece en blockingFailures.
        failure.blockingFailures.size shouldBe 1
        failure.blockingFailures[0].assertionId?.value shouldBe "a-mand"
    }

    // -----------------------------------------------------------------
    // Ratchet: gate failure sólo si ratchetFailure=true
    // -----------------------------------------------------------------

    @Test
    fun ratchet_failed_sin_diff_es_passed() {
        val report = reportWith(failedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Ratchet))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
            ratchetFailure = false,
        )
        decision.shouldBeInstanceOf<EnforcementDecision.Passed>()
    }

    @Test
    fun ratchet_failed_con_diff_es_failure() {
        val report = reportWith(failedResult("a1"))
        val suite = suiteWith(assertion("a1", Enforcement.Ratchet))
        val decision = AssuranceEngine.evaluateEnforcement(
            report = report,
            suite = suite,
            mode = EnforcementMode.FailClosed,
            ratchetFailure = true,
        )
        val failure = decision.shouldBeInstanceOf<EnforcementDecision.Failure>()
        failure.blockingFailures[0].enforcement shouldBe Enforcement.Ratchet
    }
}
