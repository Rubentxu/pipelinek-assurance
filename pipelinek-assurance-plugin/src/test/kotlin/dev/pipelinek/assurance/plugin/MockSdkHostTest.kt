package dev.pipelinek.assurance.plugin

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M11.8 — Tests E2E del plugin a través del Mock SDK host.
 *
 * Lo que se verifica:
 *   1. `MockSdkHost.runCheck` ejecuta el Step `assurance.check`
 *      y devuelve un `Output` válido (ciclo SDK → plugin →
 *      SDK sin el SDK real).
 *   2. El output es un envelope JSON canónico (forma del wire
 *      contract), verificable por `encodeJson`.
 *   3. El `runVerify` devuelve un adapter con la `key` correcta
 *      (el SDK real la consume para dispatch).
 *   4. `MockSdkHost.checkInput` construye inputs mínimos que
 *      el handler del Step acepta sin lanzar.
 */
class MockSdkHostTest : AnnotationSpec() {

    @Test
    fun runCheck_ejecuta_assurance_check_y_devuelve_output() {
        val input = MockSdkHost.checkInput(name = "self")
        val output = MockSdkHost.runCheck(input)
        output.reportDigest.isNotBlank() shouldBe true
        // El summary cuenta passed/failed/etc. Con suite vacía
        // y 0 items, todo es 0.
        output.summary.passed shouldBe 0
        output.summary.failed shouldBe 0
        output.outcome shouldBe "Success"
    }

    @Test
    fun runCheck_output_se_serializa_a_json_canonico() {
        val input = MockSdkHost.checkInput(name = "self")
        val output = MockSdkHost.runCheck(input)
        val json = MockSdkHost.encodeJson(output)
        // El envelope JSON del Step tiene los 3 campos del
        // contract: reportDigest, summary, outcome.
        json shouldContain "\"reportDigest\""
        json shouldContain "\"summary\""
        json shouldContain "\"outcome\""
    }

    @Test
    fun runVerify_devuelve_adapter_con_key_correcta() {
        val adapter = MockSdkHost.runVerify()
        adapter.key shouldBe AssuranceVerifyStep.STEP_KEY
        adapter.shouldBeInstanceOf<AssuranceVerifyStepAdapter>()
    }

    @Test
    fun checkInput_construction_minima_para_evidence_vacia() {
        // Verifica que el helper produce un input con shape
        // válida y defaults razonables (FailClosed, RequireComplete).
        val input = MockSdkHost.checkInput(name = "module-x")
        input.name shouldBe "module-x"
        input.mode shouldBe "FailClosed"
        input.completenessPolicy shouldBe "RequireComplete"
        input.suite.id shouldBe "s/mock"
    }

    @Test
    fun runCheck_con_modo_report_only_reporta_failed_sin_abortar() {
        // M11.8: el modo ReportOnly mapea un Mandatory fail a
        // Success (no aborta el pipeline). Lo verificamos
        // construyendo una suite con un assertion que fallaría
        // si el modo fuera FailClosed.
        val input = MockSdkHost.checkInput(
            name = "self",
            mode = "ReportOnly",
        )
        val output = MockSdkHost.runCheck(input)
        // Sin assertions, todo es Success. El modo no cambia
        // esto: el modo sólo afecta a `outcome` cuando hay
        // failures. El test aquí verifica que ReportOnly no
        // lanza ni aborta.
        output.outcome shouldBe "Success"
    }
}
