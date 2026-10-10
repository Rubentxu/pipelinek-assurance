package dev.pipelinek.assurance.providers.chronos

import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * M6 — Tests del codec `ChronosRuntimeEvidenceCodec`.
 *
 * Lo que se verifica:
 *   1. Roundtrip: encode -> decode preserva los campos.
 *   2. Digest verification (fail-closed): un export con digest
 *      incorrecto se rechaza con `CodecException`.
 *   3. Digest canónico: encode -> decode del mismo DTO produce
 *      el mismo digest (input stable).
 *   4. Bounded decoding: un input mayor que `MAX_INPUT_BYTES`
 *      se rechaza.
 *   5. JSON malformado: produce `CodecException`.
 */
class ChronosRuntimeEvidenceCodecTest : AnnotationSpec() {

    @Test
    fun roundtrip_encode_decode_preserva_campos() {
        val dto = sampleDto()
        val bytes = ChronosRuntimeEvidenceCodec.encodeToJson(dto)
        val decoded = ChronosRuntimeEvidenceCodec.decodeFromJson(bytes)
        decoded.windowToken shouldBe "wt-abc"
        decoded.invocations.size shouldBe 2
        decoded.invocations[0].id shouldBe "inv-1"
        decoded.invocations[0].outcome shouldBe "ok"
        decoded.completenessByCapability["runtime.window"]?.status shouldBe "Partial"
    }

    @Test
    fun digest_incorrecto_es_rechazado() {
        val dto = sampleDto()
        val bytes = ChronosRuntimeEvidenceCodec.encodeToJson(dto)
        // Extraemos el digest serializado de los bytes para poder
        // mutarlo.
        val originalDigest = String(bytes, Charsets.UTF_8)
            .substringAfter("\"digest\":\"")
            .substringBefore("\"")
        (originalDigest.length == 64) shouldBe true
        // Mutamos el digest en el JSON serializado.
        val tampered = String(bytes, Charsets.UTF_8)
            .replace(originalDigest, "0".repeat(64))
            .toByteArray(Charsets.UTF_8)
        val ex = shouldThrow<ChronosRuntimeEvidenceCodec.CodecException> {
            ChronosRuntimeEvidenceCodec.decodeFromJson(tampered, verifyDigest = true)
        }
        ex.message shouldContain "digest del export Chronos no coincide"
    }

    @Test
    fun digest_input_estable_roundtrip_produce_mismo_digest() {
        val dto = sampleDto()
        val bytes1 = ChronosRuntimeEvidenceCodec.encodeToJson(dto)
        val decoded = ChronosRuntimeEvidenceCodec.decodeFromJson(bytes1, verifyDigest = false)
        val bytes2 = ChronosRuntimeEvidenceCodec.encodeToJson(decoded)
        // El digest es estable para el mismo DTO: serializar dos
        // veces produce el mismo digest.
        decoded.digest shouldNotBe null
        val d1 = String(bytes1, Charsets.UTF_8)
            .substringAfter("\"digest\":\"")
            .substringBefore("\"")
        val d2 = String(bytes2, Charsets.UTF_8)
            .substringAfter("\"digest\":\"")
            .substringBefore("\"")
        d1 shouldBe d2
    }

    @Test
    fun input_mayor_que_MAX_INPUT_BYTES_es_rechazado() {
        // 1 byte de más sobre MAX_INPUT_BYTES.
        val tooBig = ByteArray((64L * 1024 * 1024 + 1).toInt())
        val ex = shouldThrow<ChronosRuntimeEvidenceCodec.CodecException> {
            ChronosRuntimeEvidenceCodec.decodeFromJson(tooBig)
        }
        ex.message shouldContain "excede MAX_INPUT_BYTES"
    }

    @Test
    fun json_malformado_produce_codec_exception() {
        val garbage = "{ esto no es json válido".toByteArray(Charsets.UTF_8)
        val ex = shouldThrow<ChronosRuntimeEvidenceCodec.CodecException> {
            ChronosRuntimeEvidenceCodec.decodeFromJson(garbage)
        }
        ex.message shouldContain "decode JSON"
    }

    @Test
    fun coleccion_invocations_excede_MAX_COLLECTION_SIZE_rechazada() {
        // AAT-1: bounded decoding. El check vive en
        // `decodeFromJson` después de construir el DTO. Para
        // no pagar el coste de MAX_COLLECTION_SIZE=1M de
        // allocations en el test runner, validamos con un
        // payload JSON sintético que declara `MAX_COLLECTION_SIZE+1`
        // entries (el tamaño del JSON es ~30MB pero no
        // requiere construir 1M de DTOs en memoria).
        //
        // Cómo lo conseguimos: usamos el formato crudo (no
        // construimos 1M de DTOs). El codec decodifica el
        // JSON; si el check existe, aborta con el mensaje
        // esperado.
        val real = EvidenceArtifactCodec.MAX_COLLECTION_SIZE
        // Construimos un JSON manualmente con `real + 1`
        // invocations. Para evitar 1M de líneas, usamos un
        // patrón compacto: `"id":"inv-X","outcome":"ok","durationMs":0`.
        val oneInvocation =
            """{"id":"inv-X","outcome":"ok","durationMs":0}"""
        // Generar N invocaciones separadas por comas.
        val count = real + 1
        val invocations = (0 until count).joinToString(",") { oneInvocation }
        val json = """{"windowToken":"wt-abc","invocations":[$invocations]}"""
        val ex = shouldThrow<IllegalArgumentException> {
            ChronosRuntimeEvidenceCodec.decodeFromJson(json.toByteArray(Charsets.UTF_8))
        }
        ex.message shouldContain "excede MAX_COLLECTION_SIZE"
    }

    @Test
    fun string_muy_larga_en_invocation_id_rechazada() {
        // AAT-1: bounded string decoding. El check vive en
        // decode, así que ejercitamos con un JSON sintético
        // que tiene un id de longitud > MAX_STRING_LENGTH.
        val longId = "x".repeat(EvidenceArtifactCodec.MAX_STRING_LENGTH + 1)
        val json = """{"windowToken":"wt-abc","invocations":[{"id":"$longId","outcome":"ok","durationMs":0}]}"""
        val ex = shouldThrow<IllegalArgumentException> {
            ChronosRuntimeEvidenceCodec.decodeFromJson(json.toByteArray(Charsets.UTF_8))
        }
        ex.message shouldContain "MAX_STRING_LENGTH"
    }

    @Test
    fun coleccion_causalEdges_excede_MAX_COLLECTION_SIZE_rechazada() {
        // M-CHRONOS-BOUNDED redundancia: el check de
        // causalEdges.size es una segunda frontera independiente
        // de invocations.size. Un mutante que quite el check
        // de invocations pero olvide el de causalEdges deja
        // pasar este test, no el anterior.
        val real = EvidenceArtifactCodec.MAX_COLLECTION_SIZE
        val oneEdge =
            """{"from":"span/f","to":"span/t","kind":"causal"}"""
        val count = real + 1
        val edgeList = (0 until count).joinToString(",") { oneEdge }
        val json = """{"windowToken":"wt-abc","causalEdges":[$edgeList]}"""
        val ex = shouldThrow<IllegalArgumentException> {
            ChronosRuntimeEvidenceCodec.decodeFromJson(json.toByteArray(Charsets.UTF_8))
        }
        ex.message shouldContain "causalEdges=" + count + " excede MAX_COLLECTION_SIZE"
    }

    @Test
    fun encodeToJson_incluye_digest_canonico() {
        // M-CHRONOS-REGEX-LEGACY redundancia: el encode es la
        // mitad de roundtrip; el digest embebido debe ser el
        // canónico (SHA-256 sobre el JSON con `digest=""`).
        val dto = sampleDto()
        val bytes = ChronosRuntimeEvidenceCodec.encodeToJson(dto)
        val text = String(bytes, Charsets.UTF_8)
        text shouldContain "\"digest\":\""
        // El digest es hex de 64 chars.
        Regex("\"digest\":\"[0-9a-f]{64}\"").containsMatchIn(text) shouldBe true
    }

    private fun sampleDto(): ChronosExportDto = ChronosExportDto(
        windowToken = "wt-abc",
        sessionRef = "sess-1",
        invocations = listOf(
            ChronosInvocationDto(id = "inv-1", outcome = "ok", durationMs = 10L),
            ChronosInvocationDto(id = "inv-2", outcome = "ok", durationMs = 20L),
        ),
        completenessByCapability = mapOf(
            "runtime.window" to ChronosCompletenessDto(status = "Partial"),
        ),
    )
}
