package dev.pipelinek.assurance.providers.chronos

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.RawGapReason
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * R3 — Tests E2E contra el envelope `assurance-runtime-evidence/v1`
 * producido por `tools/chronos-harness/emit-window.py`.
 *
 * **Setup**:
 *   python3 tools/chronos-harness/emit-window.py --mode all
 *
 * El script produce tres golden reproducibles en
 * `tools/chronos-harness/output/`:
 *   - `chronos-complete.json`: windowToken + 2 invocations.
 *   - `chronos-partial.json`:  windowToken + 0 invocations +
 *     completenessByCapability con Partial -> gap.
 *   - `chronos-invalid-no-token.json`: sin windowToken -> Failed.
 *
 * Estos golden son el **contrato** que el `ChronosArtifactProvider`
 * consume. Cuando Chronos publique su export real con este
 * shape, los golden siguen siendo el regression set.
 */
class ChronosHarnessTest : AnnotationSpec() {

    private fun goldenFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return File(dir, "tools/chronos-harness/output/$name.json")
    }

    @Test
    fun el_envelope_completo_produce_2_invocations_y_1_edge() {
        val file = goldenFile("chronos-complete")
        if (!file.exists()) {
            throw AssertionError(
                "Golden no generado. Ejecuta antes:\n" +
                    "  python3 tools/chronos-harness/emit-window.py --mode all",
            )
        }
        val provider = ChronosArtifactProvider(file.readBytes())
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.rawItems.size shouldBe 3
        produced.rawItems.count { it.authority == "RuntimeObserver" } shouldBe 3
    }

    @Test
    fun el_envelope_parcial_sin_invocations_emite_gap_observability_window() {
        // M-C01: la rama de gaps en completenessByCapability Partial
        // sin items debe emitir un gap con RawGapReason.PartialProduced.
        val file = goldenFile("chronos-partial")
        if (!file.exists()) {
            throw AssertionError("Golden no generado. Ejecuta emit-window.py --mode all")
        }
        val provider = ChronosArtifactProvider(file.readBytes())
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.declaredGaps.isNotEmpty() shouldBe true
        val gap = produced.declaredGaps.find { it.capability == "runtime.window" }
        (gap != null) shouldBe true
        (gap!!.reason is RawGapReason.PartialProduced) shouldBe true
    }

    @Test
    fun el_envelope_sin_windowToken_es_failed_no_produced() {
        // El provider enforce H2: la ventana debe delimitarse por
        // token durable, no por timestamp.
        val file = goldenFile("chronos-invalid-no-token")
        if (!file.exists()) {
            throw AssertionError("Golden no generado")
        }
        val provider = ChronosArtifactProvider(file.readBytes())
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }

    @Test
    fun el_provider_no_retorna_assertion_result_con_envelope_real() {
        val file = goldenFile("chronos-complete")
        if (!file.exists()) {
            throw AssertionError("Golden no generado")
        }
        val provider = ChronosArtifactProvider(file.readBytes())
        val result: Any = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        (result::class.qualifiedName?.contains("AssertionResult") ?: false) shouldBe false
    }
}
