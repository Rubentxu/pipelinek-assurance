package dev.pipelinek.assurance.providers.otel

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M8 — Tests de `OtelArtifactProvider`.
 *
 * Lo que se verifica en V1 (sin OTel collector real):
 *   1. El descriptor clasifica como `Runtime` y declara la
 *      capability `observability.trace`.
 *   2. Un export con `traceId` + `spanId` produce items con
 *      authority `RuntimeObserver` y namespaces distintos
 *      (`OTelTraceId` para trace, `OTelSpanId` para span).
 *   3. Un export sin `traceId` pero con `spanId` reporta un gap
 *      de propagación parcial.
 *   4. AAT-13: trace y span no se funden en el mismo `subjectRef`.
 */
class OtelArtifactProviderTest : AnnotationSpec() {

    @Test
    fun el_descriptor_declara_classification_runtime_y_trace() {
        val provider = OtelArtifactProvider("{}".toByteArray())
        provider.descriptor.classification.name shouldBe "Runtime"
        provider.descriptor.evidenceCapabilities.contains("observability.trace") shouldBe true
    }

    @Test
    fun trace_y_span_producen_items_con_namespaces_distintos() {
        val export = """
            {
              "resourceSpans": [
                {
                  "scopeSpans": [
                    {
                      "spans": [
                        {"traceId": "abc123", "spanId": "def456"}
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val provider = OtelArtifactProvider(export.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        // 1 item para el trace, 1 para el span, ambos con
        // subjectRef distintos.
        produced.rawItems.size shouldBe 2
        val subjectRefs = produced.rawItems.map { it.subjectRef }.toSet()
        subjectRefs.contains("OTelTraceId/abc123") shouldBe true
        subjectRefs.contains("OTelSpanId/def456") shouldBe true
        (subjectRefs.size == 2) shouldBe true // no se funden
    }

    @Test
    fun M_O01_span_sin_trace_genera_gap_de_propagacion() {
        // M-O01: "OTel missing span como success". La mutación
        // sería devolver Success cuando hay spans sin trace. La
        // ley exige reportar el gap (AAT-21: "telemetría
        // incompleta da Inconclusive, nunca Passed").
        val export = """
            {
              "resourceSpans": [
                {
                  "scopeSpans": [
                    {
                      "spans": [
                        {"spanId": "def456"}
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val provider = OtelArtifactProvider(export.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.declaredGaps.isNotEmpty() shouldBe true
        // El gap nombra la capability y la razón.
        val gap = produced.declaredGaps.first()
        gap.capability shouldBe "observability.trace"
    }

    @Test
    fun el_provider_no_retorna_assertion_result() {
        val provider = OtelArtifactProvider("{}".toByteArray())
        val result: Any = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        (result::class.qualifiedName?.contains("AssertionResult") ?: false) shouldBe false
    }
}
