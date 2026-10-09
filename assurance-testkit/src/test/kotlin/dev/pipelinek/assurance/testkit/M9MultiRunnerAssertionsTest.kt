package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions as JUnit

/**
 * M9 — Tests de paridad JUnit ↔ Kotest para `MultiRunnerAssertions`.
 *
 * La regla de M9 (UAT-020): el `AssertionResult` debe traducirse igual
 * desde el runner puro, JUnit Jupiter y Kotest. El test compara el
 * outcome observable: si `Passed` no lanza, `Failed` lanza
 * `AssertionError`, `Inconclusive` lanza `TestAbortedException`, etc.
 *
 * El contrato de paridad se verifica también con la suite digest, pero
 * eso es un test de M0 (M9 no introduce digest nuevo, sólo la fachada
 * de reporte).
 */
class M9MultiRunnerAssertionsTest : AnnotationSpec() {

    @Test
    fun un_passed_no_lanza_en_junit() {
        val result = passedResult()
        // Si lanza, el test falla; si no lanza, pasa. No necesitamos
        // verificar nada más: "no lanzó" es el contrato.
        MultiRunnerAssertions.assertInJunit(result)
    }

    @Test
    fun un_failed_lanza_assertion_error_en_junit() {
        val result = failedResult()
        val ex = shouldThrow<AssertionError> {
            MultiRunnerAssertions.assertInJunit(result)
        }
        ex.message?.contains("a/1") shouldBe true
    }

    @Test
    fun un_inconclusive_lanza_test_aborted_en_junit() {
        val result = inconclusiveResult()
        val ex = shouldThrow<org.opentest4j.TestAbortedException> {
            MultiRunnerAssertions.assertInJunit(result)
        }
        ex.message?.contains("inconclusive") shouldBe true
    }

    @Test
    fun un_unsupported_lanza_test_aborted_en_junit() {
        val result = unsupportedResult()
        val ex = shouldThrow<org.opentest4j.TestAbortedException> {
            MultiRunnerAssertions.assertInJunit(result)
        }
        ex.message?.contains("unsupported") shouldBe true
    }

    @Test
    fun un_error_lanza_assertion_error_en_junit() {
        val result = errorResult()
        val ex = shouldThrow<AssertionError> {
            MultiRunnerAssertions.assertInJunit(result)
        }
        ex.message?.contains("phase-test") shouldBe true
    }

    @Test
    fun un_report_completo_pasa_si_todas_las_assertions_pasan() {
        val report = AssuranceReport(
            evaluationId = AssuranceEvaluationId("e/1"),
            snapshotDigest = Digest.ofUtf8("s"),
            suiteDigest = Digest.ofUtf8("u"),
            engineVersion = "test",
            results = listOf(passedResult(), passedResult()),
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
        MultiRunnerAssertions.assertReportInJunit(report)
    }

    @Test
    fun un_report_con_un_failed_falla() {
        val report = AssuranceReport(
            evaluationId = AssuranceEvaluationId("e/1"),
            snapshotDigest = Digest.ofUtf8("s"),
            suiteDigest = Digest.ofUtf8("u"),
            engineVersion = "test",
            results = listOf(passedResult(), failedResult()),
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
        shouldThrow<AssertionError> {
            MultiRunnerAssertions.assertReportInJunit(report)
        }
    }

    // --- helpers ---

    private fun passedResult(): AssertionResult.Passed = AssertionResult.Passed(
        dev.pipelinek.assurance.engine.ProofRef(
            "s/1",
            listOf(EvidenceId("e/1")),
            AssertionId("a/1"),
        ),
    )

    private fun failedResult(): AssertionResult.Failed = AssertionResult.Failed(
        Counterexample.DependencyPath(
            assertionId = AssertionId("a/1"),
            subjectRefs = listOf(TypedExternalId(ExternalNamespace.PipelineRunId, "r/1")),
            evidenceRefs = listOf(EvidenceId("e/1")),
            explanation = "explicacion",
            reproductionHints = emptyList(),
            path = listOf("a", "b"),
            fromLayer = "Adapters",
            toLayer = "Domain",
        ),
    )

    private fun inconclusiveResult(): AssertionResult.Inconclusive = AssertionResult.Inconclusive(
        listOf(
            EvidenceGap(
                capability = "test.capability",
                reason = EvidenceGap.GapReason.Unknown,
                detail = "test gap",
            ),
        ),
    )

    private fun unsupportedResult(): AssertionResult.Unsupported = AssertionResult.Unsupported(
        dev.pipelinek.assurance.engine.UnsupportedReason.UnknownOperator("op-test"),
    )

    private fun errorResult(): AssertionResult.Error = AssertionResult.Error(
        dev.pipelinek.assurance.engine.EvaluationFailure(
            phase = "phase-test",
            detail = "detail-test",
        ),
    )
}
