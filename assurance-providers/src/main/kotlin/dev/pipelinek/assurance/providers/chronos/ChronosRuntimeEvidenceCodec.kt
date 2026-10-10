/**
 * M6 — Codec binario y JSON para el export Chronos `assurance-runtime-evidence/v1`.
 *
 * Ref autoridad: `07-integrations/CHRONOS_WORKSTREAM.md` H1..H7 y
 * `03-specifications/ARTIFACT_WIRE_CONTRACTS.md`.
 *
 * Por qué un codec con `kotlinx.serialization` y no un parser ad-hoc:
 * el export Chronos es la frontera entre un sistema externo y nuestro
 * modelo de dominio. Su gramática (windowToken, sessionRef, invocations,
 * causalEdges, completenessByCapability) está versionada en el workstream
 * H1, y un parser ad-hoc pierde esa gramática en el momento en que el
 * producer añade un campo (que es lo que pasó con Chronos v0.4 → v0.5).
 *
 * Bounded decoding (AAT-1): se aplica `MAX_INPUT_BYTES` antes de
 * construir el DTO. La frontera nunca debe aceptar un export que
 * pueda DoS-ear al motor.
 *
 * Digest verification (fail-closed, opcional): si el campo `digest`
 * está presente en el envelope, se calcula el SHA-256 sobre la
 * serialización canónica y se compara. La verificación puede
 * desactivarse con `verifyDigest = false` para tests.
 *
 * Relación con `ChronosArtifactProvider.decodeExportLegacy`:
 * el provider mantiene una ruta legacy (regex sobre JSON textual)
 * para entradas que el producer antiguo aún emite. La ruta principal
 * es este codec. La rama legacy está cubierta por el mutante
 * M-CHRONOS-REGEX-LEGACY.
 */
package dev.pipelinek.assurance.providers.chronos

import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import dev.pipelinek.assurance.domain.evidence.Digest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChronosExportDto(
    val windowToken: String? = null,
    val sessionRef: String? = null,
    val windowRef: String? = null,
    val schemaVersion: String = "assurance-runtime-evidence/v1",
    val invocations: List<ChronosInvocationDto> = emptyList(),
    val causalEdges: List<ChronosCausalEdgeDto> = emptyList(),
    val completenessByCapability: Map<String, ChronosCompletenessDto> = emptyMap(),
    val digest: String? = null,
)

@Serializable
data class ChronosInvocationDto(
    val id: String,
    val subjectRef: String? = null,
    val outcome: String,
    val durationMs: Long = 0L,
)

@Serializable
data class ChronosCausalEdgeDto(
    val id: String? = null,
    val from: String,
    val to: String,
    val kind: String,
)

@Serializable
data class ChronosCompletenessDto(
    val status: String,
)

object ChronosRuntimeEvidenceCodec {

    const val API_VERSION = "assurance-runtime-evidence/v1"

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    class CodecException(message: String) : RuntimeException(message)

    /**
     * Decodifica un export `assurance-runtime-evidence/v1` desde
     * JSON. Aplica `MAX_INPUT_BYTES` antes de construir el DTO.
     * Si el campo `digest` está presente y `verifyDigest = true`,
     * se calcula el SHA-256 sobre la serialización canónica
     * (con `digest=""`) y se compara.
     */
    fun decodeFromJson(bytes: ByteArray, verifyDigest: Boolean = true): ChronosExportDto {
        if (bytes.size > EvidenceArtifactCodec.MAX_INPUT_BYTES) {
            throw CodecException("input ${bytes.size} excede MAX_INPUT_BYTES=${EvidenceArtifactCodec.MAX_INPUT_BYTES}")
        }
        val text = bytes.toString(Charsets.UTF_8)
        val dto = try {
            json.decodeFromString(ChronosExportDto.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw CodecException("decode JSON: ${e.message}")
        }
        if (verifyDigest && !dto.digest.isNullOrEmpty()) {
            val expected = dto.digest
            val placeholder = dto.copy(digest = "")
            val payload = json.encodeToString(ChronosExportDto.serializer(), placeholder)
            val actual = Digest.ofUtf8(payload).hex
            if (!expected.equals(actual, ignoreCase = true)) {
                throw CodecException("digest del export Chronos no coincide: esperado=$expected, actual=$actual")
            }
        }
        return dto
    }

    fun encodeToJson(dto: ChronosExportDto): ByteArray {
        val withDigest = dto.copy(digest = digestOf(dto).hex)
        return json.encodeToString(ChronosExportDto.serializer(), withDigest)
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Calcula el digest canónico: SHA-256 del JSON con
     * `digest=""`. La canonicalización es la que `kotlinx.serialization`
     * aplica por defecto (orden de declaración de campos). Si en el
     * futuro se quiere orden canónico, se sustituye por un `Json`
     * configurado con `classDiscriminator` y serialización explícita.
     */
    fun digestOf(dto: ChronosExportDto): Digest {
        val placeholder = dto.copy(digest = "")
        val payload = json.encodeToString(ChronosExportDto.serializer(), placeholder)
        return Digest.ofUtf8(payload)
    }
}
