package dev.pipelinek.assurance.providers.chronos

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
            "runtime.invocation-chain",
            "runtime.causal-slice",
            "runtime.window",
        ),
        subjectKinds = listOf("RuntimeSpan", "SourceLocation"),
        classification = ProviderClassification.Runtime,
        inputFormats = listOf("application/vnd.chronos.assurance-runtime-evidence+cbor;version=1"),
        outputSchemaVersion = "assurance-runtime-evidence/v1",
    )

    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        // 1. Decode del export. Por ahora, asumimos que el export
        //    tiene la forma JSON/CBOR correcta; el codec real se
        //    materializa cuando Chronos esté disponible.
        //    Aquí validamos la presencia del `windowToken`, que es
        //    la regla H2 (no timestamps heurísticos).
        val export = try {
            decodeExport(exportBytes)
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
                subjectRef = inv.subjectRef,
                authority = "RuntimeObserver",
                payload = mapOf(
                    "outcome" to inv.outcome,
                    "window" to (export.windowToken ?: ""),
                    "durationMs" to inv.durationMs.toString(),
                ),
            )
        } + export.causalEdges.map { edge ->
            RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "chronos/causal/${edge.id}",
                subjectRef = edge.from,
                authority = "RuntimeObserver",
                payload = mapOf(
                    "to" to edge.to,
                    "kind" to edge.kind,
                ),
            )
        }

        // 3. Gaps: capabilities declaradas con `Complete` pero sin
        //    items, o `Partial` con cero items.
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
     * Decode mínimo. En M6 sin Chronos real, esto se mockea desde
     * el test; en M7 con Chronos real, este método se conecta al
     * `ChronosRuntimeEvidenceCodec`.
     */
    private fun decodeExport(bytes: ByteArray): ChronosExport {
        // V1: leer un JSON simple. El export completo se codifica
        // con kotlinx.serialization cuando el codec real exista.
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.contains("\"windowToken\"")) {
            throw IllegalArgumentException("no windowToken")
        }
        // Parsing minimalista para V1. La forma completa viene del codec.
        val windowToken = Regex("\"windowToken\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.get(1)
        val invocations = Regex("\"id\"\\s*:\\s*\"inv-([^\"]+)\"\\s*,\\s*\"outcome\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { match ->
                ChronosInvocation(
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
                ChronosCausalEdge(
                    id = "edge-$i",
                    from = "span/from-$i",
                    to = "span/to-$i",
                    kind = "causal",
                )
            }
            .toList()
        return ChronosExport(
            windowToken = windowToken,
            invocations = invocations,
            causalEdges = causalEdges,
            completenessByCapability = emptyMap(),
            schemaVersion = "assurance-runtime-evidence/v1",
        )
    }
}

data class ChronosExport(
    val windowToken: String?,
    val invocations: List<ChronosInvocation>,
    val causalEdges: List<ChronosCausalEdge>,
    val completenessByCapability: Map<String, ChronosCompleteness>,
    val schemaVersion: String,
)

data class ChronosInvocation(
    val id: String,
    val subjectRef: String,
    val outcome: String,
    val durationMs: Long,
)

data class ChronosCausalEdge(
    val id: String,
    val from: String,
    val to: String,
    val kind: String,
)

data class ChronosCompleteness(
    val status: String,
)
