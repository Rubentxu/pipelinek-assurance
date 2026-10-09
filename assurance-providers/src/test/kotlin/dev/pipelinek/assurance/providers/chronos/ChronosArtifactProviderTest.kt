package dev.pipelinek.assurance.providers.chronos

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M6 — Tests de `ChronosArtifactProvider`.
 *
 * Lo que se verifica en V1 (sin Chronos real):
 *   1. El descriptor declara `ProviderClassification.Runtime` y la
 *      capability `runtime.window`.
 *   2. Un export SIN `windowToken` se rechaza con `Failed` (H2:
 *      "no `now-30s`"; la ventana debe delimitarse por token).
 *   3. Un export CON `windowToken` produce un `Produced` con
 *      `RuntimeObserver` authority.
 *   4. AAT-6: el provider no expone `AssertionResult` en su API.
 */
class ChronosArtifactProviderTest : AnnotationSpec() {

    @Test
    fun el_descriptor_declara_classification_runtime_y_window() {
        val provider = ChronosArtifactProvider("{}".toByteArray())
        provider.descriptor.classification.name shouldBe "Runtime"
        provider.descriptor.evidenceCapabilities.contains("runtime.window") shouldBe true
    }

    @Test
    fun un_export_sin_window_token_es_rechazado() {
        // H2: la ventana debe delimitarse por token durable, no por
        // timestamp heurístico. El provider enforce esto por
        // signatura: si falta `windowToken`, devuelve `Failed`.
        val sinToken = """
            {
              "sessionRef": "sess-1",
              "invocations": []
            }
        """.trimIndent()
        val provider = ChronosArtifactProvider(sinToken.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }

    @Test
    fun un_export_con_window_token_produce_items_runtime_observer() {
        val conToken = """
            {
              "windowToken": "wt-abc",
              "sessionRef": "sess-1",
              "invocations": [
                {"id": "inv-1", "outcome": "ok", "durationMs": 10},
                {"id": "inv-2", "outcome": "ok", "durationMs": 20}
              ]
            }
        """.trimIndent()
        val provider = ChronosArtifactProvider(conToken.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.rawItems.size shouldBe 2
        produced.rawItems.all { it.authority == "RuntimeObserver" } shouldBe true
    }

    @Test
    fun el_provider_no_retorna_assertion_result() {
        // AAT-6: el provider no expone `AssertionResult` ni decide el
        // veredicto. Lo que retorna es `EvidenceCollectionResult`,
        // cuyo `Failed` se construye con `ProviderFailureReason`,
        // no con veredicto de assertion.
        val provider = ChronosArtifactProvider("{}".toByteArray())
        val result: Any = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val typeName = result::class.qualifiedName.orEmpty()
        (typeName.contains("AssertionResult")) shouldBe false
    }

    @Test
    fun M_C01_un_gap_parcial_sin_items_produce_declared_gap() {
        // M-C01: un gap real de Chronos (Partial sin items) no puede
        // ignorarse. El provider DEBE emitirlo como `declaredGaps`
        // con `RawGapReason.PartialProduced`. Si lo filtra, M-C01
        // sobrevive y el gate de runtime incomplete queda sin
        // cazar.
        val conGapParcial = """
            {
              "windowToken": "wt-abc",
              "sessionRef": "sess-1",
              "invocations": [],
              "completenessByCapability": {
                "runtime.window": {"status": "Partial"}
              }
            }
        """.trimIndent()
        val provider = ChronosArtifactProvider(conGapParcial.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        (produced.declaredGaps.isNotEmpty()) shouldBe true
        // M-C01: el gap de la capability `runtime.window` debe estar
        // con `RawGapReason.PartialProduced`.
        val match = produced.declaredGaps.find { it.capability == "runtime.window" }
        if (match == null) {
            // Debug: qué hay en la lista
            throw AssertionError("gaps encontrados: ${produced.declaredGaps.map { it.capability }}")
        }
        (match.reason is dev.pipelinek.assurance.engine.RawGapReason.PartialProduced) shouldBe true
    }
}
