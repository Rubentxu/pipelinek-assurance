package dev.pipelinek.assurance.providers.otel

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.RawEvidenceGap
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawGapReason
import dev.pipelinek.assurance.engine.RawItemKind

/**
 * M8 — `OtelArtifactProvider`: adapter de OpenTelemetry.
 *
 * Ref autoridad: `07-integrations/PIPELINEK_WORKSTREAM.md` §H5
 * (OTel correlation), `03-specifications/PROVIDER_SPI.md`,
 * `03-specifications/OBSERVABILITY_AND_EVENTS.md`.
 *
 * **Estado actual:** OTel no está disponible como collector real
 * (M8 depende de un collector externo). El adapter declara el
 * **contrato** del provider para cuando el export OTel
 * (`application/vnd.otel.trace+cbor;version=1`) esté disponible.
 *
 * **AAT-13:** las IDs de OTel son tipos distintos de los de
 * PipelineK/Chronos/CogniCode. El adapter emite items con
 * `subjectRef` que codifica el namespace (`OTelTraceId` o
 * `OTelSpanId`), y **nunca funde IDs entre namespaces** (la mutación
 * inversa es exactamente lo que el spec prohíbe).
 *
 * **AAT-19:** un `Signal` con authority `HeuristicAnalyzer` se
 * emite cuando la propagación de trace es parcial. Una
 * `Observation` con `RuntimeObserver` se emite cuando el span está
 * completo.
 *
 * **AAT-6:** este provider no retorna `AssertionResult`.
 */
class OtelArtifactProvider(
    private val exportBytes: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor = EvidenceProviderDescriptor(
        id = "otel",
        version = "0.1.0",
        evidenceCapabilities = listOf(
            "observability.trace",
            "observability.span",
        ),
        subjectKinds = listOf("RuntimeSpan"),
        classification = ProviderClassification.Runtime,
        inputFormats = listOf("application/vnd.otel.trace+json;version=1"),
        outputSchemaVersion = "otel/trace/v1",
    )

    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        val text = runCatching { exportBytes.toString(Charsets.UTF_8) }
            .getOrElse {
                return failed("decode: ${it.message}")
            }
        // V1: parsing minimalista. La forma completa del export OTel
        // (con `resourceSpans`, `scopeSpans`, `spans[].traceId`,
        // `spans[].spanId`, `spans[].parentSpanId`) se materializa
        // cuando el codec real exista.
        val traceIds = Regex("\"traceId\"\\s*:\\s*\"([0-9a-f]+)\"")
            .findAll(text).map { it.groupValues[1] }.toList()
        val spanIds = Regex("\"spanId\"\\s*:\\s*\"([0-9a-f]+)\"")
            .findAll(text).map { it.groupValues[1] }.toList()

        if (traceIds.isEmpty() && spanIds.isEmpty()) {
            return failed("export OTel sin traceId ni spanId reconocibles")
        }

        val items = mutableListOf<RawEvidenceItem>()
        // Cada traceId es un span "padre" potencial. Lo emitimos
        // como Observation con namespace OTelTraceId (AAT-13).
        traceIds.forEach { traceId ->
            items += RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "otel/trace/$traceId",
                subjectRef = "OTelTraceId/$traceId",
                authority = "RuntimeObserver",
                payload = mapOf("traceId" to traceId),
            )
        }
        // Cada spanId se emite como Observation con namespace
        // OTelSpanId. La presencia simultánea de traceId y
        // spanId en el mismo export es la correlación mínima;
        // un export sin traceId significa que el collector no
        // pudo correlacionar.
        spanIds.forEach { spanId ->
            items += RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "otel/span/$spanId",
                subjectRef = "OTelSpanId/$spanId",
                authority = "RuntimeObserver",
                payload = mapOf("spanId" to spanId),
            )
        }

        // Si hay spanIds sin traceIds, la propagación está
        // incompleta. Lo reportamos como gap (AAT-21 partial).
        val gaps = if (spanIds.isNotEmpty() && traceIds.isEmpty()) {
            listOf(
                RawEvidenceGap(
                    capability = "observability.trace",
                    reason = RawGapReason.PartialProduced("0/${spanIds.size}"),
                    detail = "spans sin traceId: la propagación de trace no es completa",
                ),
            )
        } else emptyList()

        return EvidenceCollectionResult.Produced(
            producerId = descriptor.id,
            producerVersion = descriptor.version,
            schemaVersion = "otel/trace/v1",
            rawItems = items,
            declaredGaps = gaps,
        )
    }

    private fun failed(reason: String): EvidenceCollectionResult.Failed =
        EvidenceCollectionResult.Failed(
            producerId = descriptor.id,
            reason = dev.pipelinek.assurance.engine.ProviderFailureReason.CollectionError(reason),
            gaps = listOf(
                RawEvidenceGap(
                    capability = "observability.trace",
                    reason = RawGapReason.Unknown,
                    detail = reason,
                ),
            ),
        )
}
