package dev.pipelinek.assurance.providers.otel

import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * M8 — Tests del codec `OtelTraceExportCodec`.
 *
 * Lo que se verifica:
 *   1. Roundtrip: encode -> decode preserva la jerarquía
 *      `resourceSpans[].scopeSpans[].spans[]`.
 *   2. Jerarquía OTLP: una estructura con jerarquía completa
 *      se decodifica produciendo `spans()` plana.
 *   3. Bounded decoding: input mayor que `MAX_INPUT_BYTES` se
 *      rechaza.
 *   4. JSON malformado: produce `CodecException`.
 */
class OtelTraceExportCodecTest : AnnotationSpec() {

    @Test
    fun roundtrip_encode_decode_preserva_jerarquia() {
        val dto = OtelExportDto(
            resourceSpans = listOf(
                ResourceSpansDto(
                    scopeSpans = listOf(
                        ScopeSpansDto(
                            spans = listOf(
                                OtelSpanDto(traceId = "abc", spanId = "def", name = "op"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val bytes = OtelTraceExportCodec.encodeToJson(dto)
        val decoded = OtelTraceExportCodec.decodeFromJson(bytes)
        val spans = decoded.spans()
        spans.size shouldBe 1
        spans[0].traceId shouldBe "abc"
        spans[0].spanId shouldBe "def"
        spans[0].name shouldBe "op"
    }

    @Test
    fun jerarquia_anidada_seaplana_en_spans() {
        val dto = OtelExportDto(
            resourceSpans = listOf(
                ResourceSpansDto(
                    scopeSpans = listOf(
                        ScopeSpansDto(
                            spans = listOf(
                                OtelSpanDto(traceId = "t1", spanId = "s1"),
                                OtelSpanDto(traceId = "t1", spanId = "s2"),
                            ),
                        ),
                        ScopeSpansDto(
                            spans = listOf(
                                OtelSpanDto(traceId = "t2", spanId = "s3"),
                            ),
                        ),
                    ),
                ),
                ResourceSpansDto(
                    scopeSpans = listOf(
                        ScopeSpansDto(
                            spans = listOf(
                                OtelSpanDto(traceId = "t3", spanId = "s4"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val bytes = OtelTraceExportCodec.encodeToJson(dto)
        val decoded = OtelTraceExportCodec.decodeFromJson(bytes)
        val spans = decoded.spans()
        spans.size shouldBe 4
        spans.map { it.spanId }.toSet() shouldBe setOf("s1", "s2", "s3", "s4")
    }

    @Test
    fun input_mayor_que_MAX_INPUT_BYTES_es_rechazado() {
        val tooBig = ByteArray((64L * 1024 * 1024 + 1).toInt())
        val ex = shouldThrow<OtelTraceExportCodec.CodecException> {
            OtelTraceExportCodec.decodeFromJson(tooBig)
        }
        ex.message shouldContain "excede MAX_INPUT_BYTES"
    }

    @Test
    fun json_malformado_produce_codec_exception() {
        val garbage = "{ no es json otlp valido".toByteArray(Charsets.UTF_8)
        val ex = shouldThrow<OtelTraceExportCodec.CodecException> {
            OtelTraceExportCodec.decodeFromJson(garbage)
        }
        ex.message shouldContain "decode JSON"
    }

    @Test
    fun empty_resourceSpans_produce_spans_vacio() {
        // M-OTEL-REGEX-LEGACY redundancia: un export con
        // `resourceSpans: []` se decodifica sin error pero
        // `spans()` devuelve lista vacía. Esto es un caso
        // límite que un mutante que cambie el path "no
        // resourceSpans" no cazaría.
        val dto = OtelExportDto(resourceSpans = emptyList())
        val bytes = OtelTraceExportCodec.encodeToJson(dto)
        val decoded = OtelTraceExportCodec.decodeFromJson(bytes)
        decoded.spans() shouldBe emptyList()
    }

    @Test
    fun resourceSpans_excede_MAX_COLLECTION_SIZE_rechazada() {
        // AAT-1: bounded decoding. El check vive en
        // `decodeFromJson`. Construimos un JSON con
        // `MAX_COLLECTION_SIZE + 1` resourceSpans sin
        // asignar 1M de objects en memoria.
        val real = EvidenceArtifactCodec.MAX_COLLECTION_SIZE
        val oneRs = """{"scopeSpans":[{"spans":[{"traceId":"t"}]}]}"""
        val count = real + 1
        val rsList = (0 until count).joinToString(",") { oneRs }
        val json = """{"resourceSpans":[$rsList]}"""
        val ex = shouldThrow<IllegalArgumentException> {
            OtelTraceExportCodec.decodeFromJson(json.toByteArray(Charsets.UTF_8))
        }
        ex.message shouldContain "resourceSpans=" + count + " excede MAX_COLLECTION_SIZE"
    }

    @Test
    fun scopeSpans_excede_MAX_COLLECTION_SIZE_rechazada() {
        // M-OTEL-BOUNDED redundancia: el check de scopeSpans
        // es una segunda frontera de la jerarquía OTLP. Un
        // mutante que quite el check de resourceSpans pero
        // olvide el de scopeSpans deja pasar este test, no
        // el anterior.
        val real = EvidenceArtifactCodec.MAX_COLLECTION_SIZE
        val oneSs = """{"spans":[{"traceId":"t"}]}"""
        val count = real + 1
        val ssList = (0 until count).joinToString(",") { oneSs }
        val json = """{"resourceSpans":[{"scopeSpans":[$ssList]}]}"""
        val ex = shouldThrow<IllegalArgumentException> {
            OtelTraceExportCodec.decodeFromJson(json.toByteArray(Charsets.UTF_8))
        }
        ex.message shouldContain "scopeSpans=" + count + " excede MAX_COLLECTION_SIZE"
    }
}
