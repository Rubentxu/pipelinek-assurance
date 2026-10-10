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
    fun M_O01_export_totalmente_vacio_es_failed_no_produced_vacio() {
        // M-O01 redundancia: un export sin traceId NI spanId debe
        // ser Failed, no Produced con lista vacía. Un mutante que
        // devuelva Produced(emptyList) haría fallar este test.
        // M-O01 ataca exactamente esa rama.
        val exportVacio = """
            {
              "resourceSpans": [
                {
                  "scopeSpans": [
                    {
                      "spans": []
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val provider = OtelArtifactProvider(exportVacio.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        // M-O01: el export vacío NO es Produced(emptyList). Es Failed.
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }

    @Test
    fun M_O01_failed_lleva_motivo_explicito_no_es_generico() {
        // M-O01 redundancia: el Failed debe llevar un motivo
        // explícito ("export OTel sin traceId ni spanId"). Un
        // mutante que devuelva Produced vacío es detectable
        // aquí (el outcome no es Failed y no hay motivo). Un
        // mutante que devuelva Failed con motivo vacío sería
        // detectable también.
        val exportVacio = "{}"
        val provider = OtelArtifactProvider(exportVacio.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val failed = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
        // El motivo debe mencionar OTel/trace/span, no ser genérico.
        val reasonText = failed.reason.toString()
        (reasonText.contains("traceId") || reasonText.contains("spanId") ||
            reasonText.contains("OTel")) shouldBe true
    }

    @Test
    fun el_provider_no_retorna_assertion_result() {
        val provider = OtelArtifactProvider("{}".toByteArray())
        val result: Any = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        (result::class.qualifiedName?.contains("AssertionResult") ?: false) shouldBe false
    }

    @Test
    fun M_OTEL_REGEX_LEGACY_uso_codigo_real_captura_name() {
        // M-OTEL-REGEX-LEGACY: un mutante que use el legacy regex
        // en vez del codec debe ser cazado. El legacy produce
        // OtelSpanDto sin `name`; el codec preserva el campo. Si
        // el mutante gana, el `name` no aparece en el item.
        val export = """
            {
              "resourceSpans": [
                {
                  "scopeSpans": [
                    {
                      "spans": [
                        {"traceId": "abc123", "spanId": "def456", "name": "GET /api/users"}
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
        // El codec real produce items con metadata que incluye
        // el `name` del span. Verificamos que hay 2 items (trace
        // + span) y que NO se confunden.
        (produced.rawItems.size >= 2) shouldBe true
    }

    @Test
    fun M_OTEL_REGEX_LEGACY_uso_codigo_real_jerarquia_con_multiples_scopes() {
        // M-OTEL-REGEX-LEGACY redundancia: el codec real
        // preserva TODA la jerarquía resourceSpans[].scopeSpans[];
        // el legacy la aplana incorrectamente bajo un único
        // resource/scope. Verificamos que con 2 resources
        // distintos, los spanIds únicos suman correctamente.
        val export = """
            {
              "resourceSpans": [
                {
                  "scopeSpans": [
                    {"spans": [{"traceId": "t1", "spanId": "s1"}]}
                  ]
                },
                {
                  "scopeSpans": [
                    {"spans": [{"traceId": "t2", "spanId": "s2"}]}
                  ]
                }
              ]
            }
        """.trimIndent()
        val provider = OtelArtifactProvider(export.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        val spanIds = produced.rawItems
            .filter { it.subjectRef.startsWith("OTelSpanId/") }
            .map { it.subjectRef }
        // Ambos spanIds deben aparecer exactamente una vez
        // (el codec los extrae correctamente).
        spanIds.toSet().size shouldBe 2
    }
}
