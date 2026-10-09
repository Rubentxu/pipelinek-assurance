package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M3 — Tests del contrato de `assurance.check`.
 *
 * Ref: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md` §Step outcome matrix.
 *
 * Lo que estos tests verifican es la LÓGICA del Step (cómo mapea
 * `AssuranceReport` → `StepOutcome` según `EnforcementMode` y
 * `CompletenessPolicy`). La integración con el SDK de PipelineK es
 * M3 segundo pase y se certifica con UAT-008 (instalado en distribución
 * real) y UAT-009 (Mandatory gate).
 */
class AssuranceCheckStepTest : AnnotationSpec() {

    @Test
    fun AAT_10_un_mandatory_fail_con_fail_closed_es_failure() {
        val report = reportWith(failed = 1, inconclusive = 0)
        val outcome = AssuranceCheckStep.outcomeOf(
            report,
            AssuranceCheckStep.EnforcementMode.FailClosed,
            AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        outcome.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Failure>()
        outcome.reason shouldBe "assurance: 1 assertion(s) failed (Mandatory)"
    }

    @Test
    fun un_mandatory_fail_con_report_only_es_success() {
        val report = reportWith(failed = 1, inconclusive = 0)
        val outcome = AssuranceCheckStep.outcomeOf(
            report,
            AssuranceCheckStep.EnforcementMode.ReportOnly,
            AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        outcome.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Success>()
    }

    @Test
    fun un_inconclusive_con_require_complete_es_failure_incomplete() {
        val report = reportWith(failed = 0, inconclusive = 1)
        val outcome = AssuranceCheckStep.outcomeOf(
            report,
            AssuranceCheckStep.EnforcementMode.FailClosed,
            AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        outcome.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Failure>()
        outcome.reason shouldBe "assurance-incomplete: 1 inconclusive assertion(s)"
    }

    @Test
    fun un_inconclusive_con_report_missing_es_success() {
        val report = reportWith(failed = 0, inconclusive = 1)
        val outcome = AssuranceCheckStep.outcomeOf(
            report,
            AssuranceCheckStep.EnforcementMode.FailClosed,
            AssuranceCheckStep.CompletenessPolicy.ReportMissing,
        )
        outcome.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Success>()
    }

    @Test
    fun todo_passed_es_success() {
        val report = reportWith(failed = 0, inconclusive = 0, passed = 2)
        val outcome = AssuranceCheckStep.outcomeOf(
            report,
            AssuranceCheckStep.EnforcementMode.FailClosed,
            AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        outcome.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Success>()
    }

    @Test
    fun el_step_key_es_assurance_check() {
        AssuranceCheckStep.STEP_KEY shouldBe "assurance.check"
    }

    @Test
    fun el_step_no_retorna_assertion_result() {
        val outcome: Any = AssuranceCheckStep.outcomeOf(
            reportWith(failed = 0, inconclusive = 0),
            AssuranceCheckStep.EnforcementMode.FailClosed,
            AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        (outcome is AssertionResult) shouldBe false
    }

    private fun reportWith(
        passed: Int = 0,
        failed: Int = 0,
        inconclusive: Int = 0,
    ): AssuranceReport {
        val results = buildList<AssertionResult> {
            repeat(passed) {
                add(
                    AssertionResult.Passed(
                        ProofRef(
                            "s",
                            listOf(EvidenceId("e/$it")),
                            dev.pipelinek.assurance.engine.AssertionId("a/$it"),
                        ),
                    ),
                )
            }
            repeat(failed) {
                add(
                    AssertionResult.Failed(
                        dev.pipelinek.assurance.engine.Counterexample.Cycle(
                            assertionId = dev.pipelinek.assurance.engine.AssertionId("a/$it"),
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
                            dev.pipelinek.assurance.domain.evidence.EvidenceGap(
                                capability = "test.capability",
                                reason = dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unknown,
                                detail = "test gap",
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
