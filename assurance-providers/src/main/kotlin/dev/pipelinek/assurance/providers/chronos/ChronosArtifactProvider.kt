package dev.pipelinek.assurance.providers.chronos

import dev.pipelinek.assurance.domain.capabilities.Capabilities
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
 * M6 — `ChronosArtifactProvider`: adapter del export `assurance-runtime-evidence/v1`.
 *
 * Ref autoridad: `07-integrations/CHRONOS_WORKSTREAM.md` (H1..H7) y
 * `03-specifications/PROVIDER_SPI.md`.
 *
 * **Estado actual:** Chronos no está disponible en este repositorio
 * (M6 depende de un artefacto externo). El adapter declara el
 * **contrato** del provider para que cuando Chronos exporte su
 * `assurance-runtime-evidence/v1`, este adapter lo consuma sin
 * cambios de signatura. La verificación end-to-end (UAT-015, UAT-016)
 * ocurre en M7 con un run real; en M6 sólo se certifica la forma.
 *
 * **Lo que el adapter exige del export** (de CHRONOS_WORKSTREAM.md):
 *   - `manifest`, `sessionRef`, `windowRef` (con `windowToken`),
 *   - `invocations[]` con `invocationChain` y `outcome`,
 *   - `causalEdges[]` con relaciones entre invocaciones,
 *   - `runtimeProperties[]` (e.g., `MethodCalled`, `EdgeObserved`),
 *   - `completenessByCapability` con gaps explícitos (H4).
 *
 * **WindowToken protocol:** el adapter exige `windowToken` no nulo
 * en cada export. Un export sin `windowToken` se rechaza con
 * `UnsupportedInputFormat`: la regla H2 ("no `now-30s`") se enforce
 * por signatura.
 *
 * **AAT-19:** la autoridad de los items runtime es siempre
 * `RuntimeObserver` (es la única que aplica a evidencia runtime).
 *
 * **AAT-6:** este provider no retorna `AssertionResult`; el core es
 * el que evalúa.
 */
class ChronosArtifactProvider(
    private val exportBytes: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor = EvidenceProviderDescriptor(
        id = "chronos",
        version = "0.1.0",
        evidenceCapabilities = listOf(
            Capabilities.RUNTIME_INVOCATION_CHAIN,
            Capabilities.RUNTIME_CAUSAL_SLICE,
            Capabilities.RUNTIME_WINDOW,
        ),
        subjectKinds = listOf("RuntimeSpan", "SourceLocation"),
        classification = ProviderClassification.Runtime,
        inputFormats = listOf("application/vnd.chronos.assurance-runtime-evidence+cbor;version=1"),
        outputSchemaVersion = ChronosRuntimeEvidenceCodec.API_VERSION,
    )

    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        // 1. Decode del export. Ruta principal: el codec con
        //    `kotlinx.serialization` (M-CHRONOS-REGEX-LEGACY
        //    documenta la ruta legacy).
        val export = try {
            ChronosRuntimeEvidenceCodec.decodeFromJson(exportBytes)
        } catch (e: ChronosRuntimeEvidenceCodec.CodecException) {
            return failed("decode: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return failed("decode: ${e.message}")
        }

        if (export.windowToken == null) {
            return failed(
                "Chronos export sin windowToken; la ventana debe delimitarse por token durable, no por timestamp",
            )
        }

        // 2. Mapeo a RawEvidenceItem. Los invocations son Observations
        //    con authority `RuntimeObserver`. Las causal edges son
        //    Observations con la misma authority. Los gaps de la
        //    capability se reportan como RawEvidenceGap.
        val items = export.invocations.map { inv ->
            RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "chronos/invocation/${inv.id}",
                subjectRef = inv.subjectRef ?: "chronos:invocation:${inv.id}",
                authority = "RuntimeObserver",
                payload = mapOf(
                    "outcome" to inv.outcome,
                    "window" to (export.windowToken ?: ""),
                    "durationMs" to inv.durationMs.toString(),
                ),
            )
        } + export.causalEdges.mapIndexed { i, edge ->
            RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "chronos/causal/${edge.id ?: "edge-$i"}",
                subjectRef = edge.from,
                authority = "RuntimeObserver",
                payload = mapOf(
                    "to" to edge.to,
                    "kind" to edge.kind,
                ),
            )
        }

        // 3. Gaps: capabilities declaradas con `Partial` y cero
        //    items. La condición `items.isEmpty()` es la del
        //    contrato original; si hay items pero la capability
        //    está Partial, no se emite gap aquí — el servicio de
        //    aplicación cruzará con `requestedCapabilities`.
        val gaps = export.completenessByCapability
            .filter { (_, c) -> c.status == "Partial" && items.isEmpty() }
            .map { (cap, _) ->
                RawEvidenceGap(
                    capability = cap,
                    reason = RawGapReason.PartialProduced("0/1"),
                    detail = "Chronos declara $cap como Partial sin items",
                )
            }

        return EvidenceCollectionResult.Produced(
            producerId = descriptor.id,
            producerVersion = descriptor.version,
            schemaVersion = export.schemaVersion,
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
                    capability = "runtime.window",
                    reason = RawGapReason.Unknown,
                    detail = reason,
                ),
            ),
        )

    /**
     * Decode legacy con regex sobre JSON textual. Conservado para
     * entradas que el producer antiguo aún emite y para que el
     * mutante M-CHRONOS-REGEX-LEGACY tenga un punto de mutación
     * explícito. El codec `ChronosRuntimeEvidenceCodec` es la
     * ruta principal.
     */
    internal fun decodeExportLegacy(bytes: ByteArray): ChronosExportDto {
        val text = bytes.toString(Charsets.UTF_8)
        val windowToken = Regex("\"windowToken\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.get(1)
        val invocations = Regex(
            "\"id\"\\s*:\\s*\"inv-([^\"]+)\"\\s*,\\s*\"outcome\"\\s*:\\s*\"([^\"]+)\"",
        ).findAll(text)
            .map { match ->
                ChronosInvocationDto(
                    id = "inv-${match.groupValues[1]}",
                    subjectRef = "span/${match.groupValues[1]}",
                    outcome = match.groupValues[2],
                    durationMs = 0L,
                )
            }
            .toList()
        val causalEdges = Regex("\"kind\"\\s*:\\s*\"causal\"")
            .findAll(text)
            .mapIndexed { i, _ ->
                ChronosCausalEdgeDto(
                    id = "edge-$i",
                    from = "span/from-$i",
                    to = "span/to-$i",
                    kind = "causal",
                )
            }
            .toList()
        val completeness = Regex(
            "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"status\"\\s*:\\s*\"([^\"]+)\"",
        ).findAll(text)
            .map { match ->
                match.groupValues[1] to ChronosCompletenessDto(status = match.groupValues[2])
            }
            .toList()
            .toMap()
        return ChronosExportDto(
            windowToken = windowToken,
            invocations = invocations,
            causalEdges = causalEdges,
            completenessByCapability = completeness,
        )
    }
}
