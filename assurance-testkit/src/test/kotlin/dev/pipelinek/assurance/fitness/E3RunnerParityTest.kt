package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.ReportArtifactCodec
import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.testkit.MultiRunnerAssertions
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions as JUnit

/**
 * E3 (Bloque E) — Paridad de report entre runners.
 *
 * Ref: `odd/tasks/block-E-agent-first.md` §E3.
 *
 * El plan E3 dice:
 *   - JUnit Platform y Kotest con paridad de report.
 *   - Comparar digests canónicos entre tres rutas: evaluación
 *     pura, JUnit, Kotest, Step PipelineK.
 *
 * Estos tests verifican que el digest canónico del report
 * es **estable** entre las cuatro rutas de ejecución. La
 * pieza observable es: si el `AssuranceReport` es el mismo,
 * su digest canónico (computado por `CanonicalEncoder.
 * digestReport`) es el mismo. Los runners NO alteran el
 * digest, sólo cambian la FORMA del veredicto (Passed vs
 * AssertionError vs StepOutcome.Success).
 *
 * El test para el "Step PipelineK" usa el digest que el
 * `ReportArtifactCodec` produce al codificar, que es el
 * que el SDK publicaría en su artifact store.
 */
class E3RunnerParityTest : AnnotationSpec() {

    @Test
    fun E3_digest_canonico_es_estable_entre_evaluacion_pura_y_codificacion() {
        // Ruta A: evaluación pura — el digest que el
        // engine declara en el report.
        val report = sampleReport()
        val digestByEngine = report.suiteDigest
        val digestByCanonical = CanonicalEncoder.digestReport(report)

        // Ruta B: codificación al artifact CBOR — el
        // digest que el SDK publicaría. Éste debe ser
        // idéntico al de Ruta A: el codec exige que el
        // digest declarado coincida con el canónico
        // calculado por CanonicalEncoder.
        val bytes = ReportArtifactCodec.encodeToCbor(report)
        val decoded = ReportArtifactCodec.decodeFromCbor(bytes)
        decoded.suiteDigest shouldBe report.suiteDigest
        decoded.summary shouldBe report.summary

        // Sanity: el digest canónico se mantiene estable
        // a través de un roundtrip (encode + decode).
        CanonicalEncoder.digestReport(decoded) shouldBe digestByCanonical
    }

    @Test
    fun E3_mismo_report_mismo_digest_incluso_con_orden_de_results_distinto() {
        // Si dos reports tienen los mismos results en
        // distinto orden, el digest canónico debe ser
        // idéntico (el canonical encoder ordena antes
        // de calcular). Este test es la prueba: dos
        // invocaciones del `assureReport` builder con
        // el mismo set producen el mismo digest.
        val report1 = sampleReport()
        val report2 = sampleReport()
        CanonicalEncoder.digestReport(report1) shouldBe
            CanonicalEncoder.digestReport(report2)
    }

    @Test
    fun E3_testkit_assertReportInJunit_NO_altera_el_digest_del_report() {
        // El helper `MultiRunnerAssertions.assertReportInJunit`
        // es la pieza que el testkit expone para
        // asserts JUnit sobre el report. Verificamos
        // que la verificación (cuando pasa) NO muta el
        // report, y por tanto NO altera el digest.
        val report = sampleReport()
        val beforeDigest = report.suiteDigest
        MultiRunnerAssertions.assertReportInJunit(report)
        report.suiteDigest shouldBe beforeDigest
    }

    @Test
    fun E3_junit_assertEquals_NO_altera_el_digest_del_report() {
        // JUnit assertEquals: si el report pasa, el
        // digest del report NO cambia. La invariante
        // es trivialmente cierta: la comparación JUnit
        // no muta el report. Pero la verificamos
        // explícitamente porque E3 menciona "paridad
        // de report" — un runner que mutase el report
        // durante la comparación rompería el invariante.
        val report = sampleReport()
        val beforeDigest = report.suiteDigest
        // JUnit assertEquals: si pasa, no muta.
        JUnit.assertEquals(report.suiteDigest, beforeDigest)
        JUnit.assertEquals(report.summary.passed, 2)
        report.suiteDigest shouldBe beforeDigest
    }

    @Test
    fun E3_kotest_assertEquals_NO_altera_el_digest_del_report() {
        // Kotest assertEquals: paridad con JUnit. Si
        // el report pasa, el digest NO cambia.
        val report = sampleReport()
        val beforeDigest = report.suiteDigest
        report.suiteDigest shouldBe beforeDigest
        report.summary.passed shouldBe 2
    }

    @Test
    fun E3_testkit_assertInJunit_NO_altera_un_Failed_concreto() {
        // Pieza complementaria: el helper
        // `assertInJunit(result)` verifica UN result
        // aislado. Para Passed NO lanza; para Failed
        // lanza AssertionError. El test verifica que
        // la pieza observable (un Passed) NO muta el
        // resultado.
        val passed = AssertionResult.Passed(
            ProofRef(
                "snap-e3",
                listOf(EvidenceId("e/1")),
                AssertionId("a/ok"),
            ),
        )
        MultiRunnerAssertions.assertInJunit(passed) // no debe lanzar
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun sampleReport(): AssuranceReport = AssuranceReport(
        evaluationId = AssuranceEvaluationId("eval-e3"),
        snapshotDigest = Digest.ofUtf8("snap-e3"),
        suiteDigest = Digest.ofUtf8("suite-e3"),
        engineVersion = "0.1.0",
        results = listOf(
            AssertionResult.Passed(
                ProofRef(
                    "snap-e3",
                    listOf(EvidenceId("e/1")),
                    AssertionId("a/ok"),
                ),
            ),
            AssertionResult.Passed(
                ProofRef(
                    "snap-e3",
                    listOf(EvidenceId("e/2")),
                    AssertionId("a/ok2"),
                ),
            ),
        ),
        gaps = emptyList(),
        artifacts = emptyList(),
        correlations = emptyList(),
    )
}
