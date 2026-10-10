/**
 * M8 — Codec JSON para un export OTel simplificado a nuestro modelo.
 *
 * Ref autoridad: `07-integrations/PIPELINEK_WORKSTREAM.md` §H5
 * (OTel correlation), `03-specifications/ARTIFACT_WIRE_CONTRACTS.md`.
 *
 * AAT-13: las IDs de OTel son tipos distintos de los de
 * PipelineK/Chronos/CogniCode. La estructura `resourceSpans[].scope
 * Spans[].spans[]` es la del OTLP JSON, no la del modelo de
 * evidencia; la conversion a `RawEvidenceItem` la hace el provider.
 *
 * Por qué un codec y no un parser ad-hoc: el OTLP JSON es una
 * gramática mantenida por OpenTelemetry. Un parser regex no
 * sobrevive al primer cambio de upstream; un codec con
 * `kotlinx.serialization` deja la gramática declarativa, fail-closed
 * ante campos malformados, y la mantiene sincronizada con la
 * especificación que el OTel collector emite.
 *
 * Bounded decoding: `MAX_INPUT_BYTES` se aplica antes de construir
 * el DTO. A diferencia de Chronos, OTel NO tiene un digest canónico
 * (el OTLP no lo declara), por lo que `decodeFromJson` no verifica
 * digest.
 */
package dev.pipelinek.assurance.providers.otel

import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OtelExportDto(
    val resourceSpans: List<ResourceSpansDto> = emptyList(),
) {
    /**
     * Devuelve la lista plana de spans (con su traceId y spanId
     * opcionales y parentSpanId opcional) recorriendo la jerarquía
     * `resourceSpans[].scopeSpans[].spans[]`.
     */
    fun spans(): List<OtelSpanDto> = resourceSpans.flatMap { rs ->
        rs.scopeSpans.flatMap { ss -> ss.spans }
    }
}

@Serializable
data class ResourceSpansDto(
    val scopeSpans: List<ScopeSpansDto> = emptyList(),
)

@Serializable
data class ScopeSpansDto(
    val spans: List<OtelSpanDto> = emptyList(),
)

@Serializable
data class OtelSpanDto(
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val name: String? = null,
)

object OtelTraceExportCodec {

    const val API_VERSION = "otel/trace/v1"

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    class CodecException(message: String) : RuntimeException(message)

    fun decodeFromJson(bytes: ByteArray): OtelExportDto {
        if (bytes.size > EvidenceArtifactCodec.MAX_INPUT_BYTES) {
            throw CodecException("input ${bytes.size} excede MAX_INPUT_BYTES=${EvidenceArtifactCodec.MAX_INPUT_BYTES}")
        }
        val text = bytes.toString(Charsets.UTF_8)
        return try {
            json.decodeFromString(OtelExportDto.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw CodecException("decode JSON: ${e.message}")
        }
    }

    fun encodeToJson(dto: OtelExportDto): ByteArray =
        json.encodeToString(OtelExportDto.serializer(), dto)
            .toByteArray(Charsets.UTF_8)
}
