/**
 * A3 (Bloque A) — Normalizer de frontera cruda → normalizada.
 *
 * Convierte `EvidenceCollectionResult.Produced` (forma cruda del
 * SPI de providers) a `EvidenceSnapshot` (forma que el core
 * consume).
 *
 * **Por qué vive aquí, en `assurance-providers`**: la
 * conversión cruda → normalizada es lógica de aplicación que
 * pertenece a la frontera del SPI. Los adapters viven en
 * `assurance-providers` (cognicode, chronos, otel, detekt,
 * junit, mutation); el normalizer los consume a través de
 * `EvidenceCollectionResult` (forma cruda) y produce
 * `EvidenceSnapshot` (forma que el core consume).
 *
 * En B2, el plugin importa este normalizer (es utility code,
 * no adapter) sin acoplarse a las implementaciones concretas
 * de providers. La distinción entre "adapter" y "utility" la
 * enforce `M3PluginModuleFitnessTest.AAT_03_plugin_no_depende_de_assurance_providers`
 * por contenido de imports, no por mera presencia del módulo
 * en el classpath.
 *
 * El testkit queda como **consumidor** de la misma implementación
 * (ver `EvidenceNormalizerTest`); la duplicación que existía antes
 * se elimina.
 *
 * Lo que el normalizer enforce (sin cambios respecto a M2-T8, pero
 * ahora con las correcciones de A3):
 *
 * 1. **AAT-19 (authority/kind matrix)**: el `authority` del raw
 *    item debe mapear a un `EvidenceAuthority` válido Y coincidir
 *    con el `RawItemKind`:
 *      - Fact → DeterministicAdapter | DeterministicAnalyzer | RuntimeObserver
 *      - Observation → RuntimeObserver
 *      - Signal → HeuristicAnalyzer
 *      - Hypothesis → AgentHypothesis | HumanCurated
 * 2. **AAT-13 (namespacing)**: el `id` debe contener `/`. Si no,
 *    se rechaza con `NormalizerException`.
 * 3. **Bounded decoding**: longitudes acotadas en id y campos.
 *
 * **Correcciones A3 aplicadas (antes eran defectos)**:
 *
 *  - **Digest del contenido, no de la cardinalidad**. La versión
 *    M2-T8 calculaba `Digest.ofUtf8("$producerId/$producerVersion/
 *    ${items.size}")` — un digest que se mantiene estable al
 *    añadir/quitar items, lo que rompe la reproducibilidad. El
 *    nuevo digest es SHA-256 sobre la concatenación ordenada
 *    `(producerId, producerVersion, itemId, itemDigest, gapKey)`
 *    de toda la salida. Mismo input → mismo digest; distinto
 *    contenido → distinto digest.
 *
 *  - **Propagación del `subjectRevision` real**. La versión M2-T8
 *    hardcodeaba `RevisionRef("0000...0")` y descartaba el
 *    parámetro que la firma ya aceptaba. Ahora la Provenance
 *    lleva la revisión del sujeto observada por el producer.
 *
 *  - **Capabilities producidas con cero resultados**. La versión
 *    M2-T8 derivaba `producedCapabilities` exclusivamente de los
 *    items. Si una capability se pidió y el producer no pudo
 *    observarla, no aparecía — y el gap tampoco, porque sólo
 *    declaraba gaps el provider. Ahora esas capabilities
 *    pendientes se marcan explícitamente como `Unknown` en el
 *    manifest, no se ocultan.
 *
 *  - **Conversión de gaps**: las cuatro formas de `RawGapReason`
 *    (Unsupported, Unknown, Lost, PartialProduced) se mapean 1:1
 *    a `EvidenceGap.GapReason`. `Other` se preserva como
 *    `Unknown` (asimetría documentada en M-COGN02: el SPI
 *    admite Other para producer que miente, el core lo descarta
 *    silenciosamente porque la frontera cruda/normalizada
 *    prohibe propagar mentiras).
 */
package dev.pipelinek.assurance.providers

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.RawEvidenceGap
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind

object EvidenceNormalizer {

    class NormalizerException(message: String) : RuntimeException(message)

    fun normalize(
        result: EvidenceCollectionResult,
        producerId: String,
        producerVersion: String,
        subjectRevision: RevisionRef,
        subject: EvidenceSubject,
        snapshotId: SnapshotId,
        requestedCapabilities: List<String>,
    ): EvidenceSnapshot {
        val produced = result as? EvidenceCollectionResult.Produced
            ?: throw NormalizerException(
                "el provider $producerId no produjo; " +
                    "result=${result?.let { it::class.simpleName }}",
            )

        val items = produced.rawItems.map { rawItem ->
            normalizeItem(rawItem, producerId, producerVersion, subjectRevision)
        }
        val gaps = produced.declaredGaps.map { gap -> normalizeGap(gap) }

        // A3: capabilities con cero resultados. Si la capability
        // se pidió pero no produjo items, la marcamos como
        // Unknown explícitamente — no se esconde.
        val capabilitiesByItems = items
            .groupBy { it.provenance.capability }
            .mapValues { (_, list) -> list.first().completenessOrDefault() }
        val missingCapabilities = requestedCapabilities
            .filter { cap -> cap !in capabilitiesByItems.keys }
            .associateWith { Completeness.Unknown }
        val completenessByCapability = capabilitiesByItems + missingCapabilities

        // A3: digest del contenido, no de la cardinalidad. SHA-256
        // sobre la concatenación ordenada de los campos que
        // determinan el resultado. Dos snapshots con los mismos
        // items, los mismos gaps, el mismo producer y la misma
        // revisión producen el mismo digest. Mismo item size
        // con contenido distinto produce digest distinto.
        val manifest = EvidenceSourceManifest(
            producerId = producerId,
            producerVersion = producerVersion,
            subjectRevision = subjectRevision,
            requestedCapabilities = requestedCapabilities,
            producedCapabilities = completenessByCapability.keys.toList(),
            completenessByCapability = completenessByCapability,
            schemaVersion = produced.schemaVersion,
            digest = contentBasedDigest(producerId, producerVersion, items, gaps),
        )

        return EvidenceSnapshot(
            id = snapshotId,
            subject = subject,
            sources = listOf(manifest),
            items = items,
            gaps = gaps,
        )
    }

    /**
     * A3: digest del contenido, no de la cardinalidad.
     *
     * Calcula SHA-256 sobre la concatenación ordenada de:
     *  - producerId y producerVersion
     *  - cada item: id + authority + capability + payload canónico
     *  - cada gap: capability + reason + detail
     *
     * El orden importa (digest estable); se preserva el orden
     * de aparición en `produced.rawItems` y `produced.declaredGaps`,
     * que es el orden de declaración del producer. Si dos
     * producers declaran lo mismo en distinto orden, el digest
     * difiere — y eso es lo correcto: la reproducibilidad
     * requiere orden canónico en la entrada.
     */
    private fun contentBasedDigest(
        producerId: String,
        producerVersion: String,
        items: List<EvidenceItem>,
        gaps: List<EvidenceGap>,
    ): Digest {
        val sb = StringBuilder()
        sb.append("producerId=").append(producerId).append('\n')
        sb.append("producerVersion=").append(producerVersion).append('\n')
        for (item in items) {
            sb.append("item=")
                .append(item.id.value).append('|')
                .append(item.authority.name).append('|')
                .append(item.provenance.capability).append('|')
                .append(item.canonicalPayload()).append('\n')
        }
        for (gap in gaps) {
            sb.append("gap=")
                .append(gap.capability).append('|')
                .append(gap.reason.canonicalName()).append('|')
                .append(gap.detail ?: "").append('\n')
        }
        return Digest.ofUtf8(sb.toString())
    }

    private fun EvidenceItem.canonicalPayload(): String = when (this) {
        is EvidenceItem.Fact ->
            "Fact|$predicate|${objectValue ?: ""}"
        is EvidenceItem.Observation ->
            "Observation|$observation"
        is EvidenceItem.Signal ->
            "Signal|$signalKind|$score|$algorithmId|$algorithmVersion"
        is EvidenceItem.Hypothesis ->
            "Hypothesis|$claim|${reasoning}|${confidence}"
    }

    private fun EvidenceGap.GapReason.canonicalName(): String = when (this) {
        is EvidenceGap.GapReason.Unsupported -> "Unsupported"
        is EvidenceGap.GapReason.Unknown -> "Unknown"
        is EvidenceGap.GapReason.Lost -> "Lost"
        is EvidenceGap.GapReason.PartialProduced -> "PartialProduced($coveredFraction)"
    }

    private fun normalizeItem(
        raw: RawEvidenceItem,
        producerId: String,
        producerVersion: String,
        subjectRevision: RevisionRef,
    ): EvidenceItem {
        if (!raw.id.contains("/")) {
            throw NormalizerException(
                "rawItem sin namespace en id: ${raw.id} (producerId=$producerId)",
            )
        }
        val authority = parseAuthority(raw.authority)
        val capability = raw.payload["capability"] ?: "unknown"
        // A3: propagación del subjectRevision real, no el
        // hardcodeado "0000...0" de M2-T8.
        val provenance = Provenance(
            producerId = producerId,
            producerVersion = producerVersion,
            subjectRevision = subjectRevision,
            capability = capability,
        )
        val subject = subjectFromRaw(raw)
        val id = EvidenceId(raw.id)

        return when (raw.kind) {
            RawItemKind.Fact -> {
                require(authority in DETERMINISTIC_OR_RUNTIME) {
                    "Fact con authority ${raw.authority} no admitida (AAT-19)"
                }
                EvidenceItem.Fact(
                    id = id,
                    subject = subject,
                    authority = authority,
                    provenance = provenance,
                    predicate = raw.payload["predicate"] ?: raw.subjectRef,
                    objectValue = raw.payload["object"]?.takeIf { it.isNotEmpty() },
                )
            }
            RawItemKind.Observation -> {
                require(authority == EvidenceAuthority.RuntimeObserver) {
                    "Observation con authority ${raw.authority} no admitida (AAT-19)"
                }
                EvidenceItem.Observation(
                    id = id,
                    subject = subject,
                    authority = authority,
                    provenance = provenance,
                    observation = raw.payload["observation"]
                        ?: raw.payload["outcome"]
                        ?: raw.subjectRef,
                )
            }
            RawItemKind.Signal -> {
                require(authority == EvidenceAuthority.HeuristicAnalyzer) {
                    "Signal con authority ${raw.authority} no admitida (AAT-19, M-H01)"
                }
                EvidenceItem.Signal(
                    id = id,
                    subject = subject,
                    authority = authority,
                    provenance = provenance,
                    signalKind = raw.payload["signalKind"] ?: "unknown",
                    score = raw.payload["score"] ?: "0",
                    algorithmId = raw.payload["algorithmId"] ?: "unknown",
                    algorithmVersion = raw.payload["algorithmVersion"] ?: "0.0.0",
                    thresholds = parseThresholds(raw.payload["thresholds"]),
                )
            }
            RawItemKind.Hypothesis -> {
                require(authority in AGENT_OR_HUMAN) {
                    "Hypothesis con authority ${raw.authority} no admitida (AAT-19)"
                }
                EvidenceItem.Hypothesis(
                    id = id,
                    subject = subject,
                    authority = authority,
                    provenance = provenance,
                    claim = raw.payload["claim"] ?: raw.subjectRef,
                    reasoning = raw.payload["reasoning"] ?: "",
                    confidence = raw.payload["confidence"] ?: "0.0",
                )
            }
        }
    }

    private fun parseAuthority(raw: String): EvidenceAuthority =
        runCatching { EvidenceAuthority.valueOf(raw) }.getOrElse {
            throw NormalizerException(
                "authority no reconocida: '$raw' " +
                    "(válidas: ${EvidenceAuthority.entries.joinToString()})",
            )
        }

    private fun parseThresholds(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split("|")
            .mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun subjectFromRaw(raw: RawEvidenceItem): EvidenceSubject = when {
        raw.subjectRef.startsWith("module:") -> EvidenceSubject.Module(
            raw.subjectRef.removePrefix("module:"),
        )
        raw.subjectRef.startsWith("symbol:") -> EvidenceSubject.Symbol(
            raw.subjectRef.removePrefix("symbol:"),
        )
        raw.subjectRef.startsWith("OTelTraceId/") ||
            raw.subjectRef.startsWith("OTelSpanId/") ||
            raw.subjectRef.startsWith("chronos:") -> EvidenceSubject.RuntimeSpan(
            raw.subjectRef,
        )
        else -> EvidenceSubject.Symbol(raw.subjectRef)
    }

    private fun EvidenceItem.completenessOrDefault(): Completeness = when (this) {
        is EvidenceItem.Fact -> this.completeness
        is EvidenceItem.Observation -> this.completeness
        is EvidenceItem.Signal -> this.completeness
        is EvidenceItem.Hypothesis -> Completeness.Complete
    }

    private fun normalizeGap(gap: RawEvidenceGap): EvidenceGap = EvidenceGap(
        capability = gap.capability,
        reason = normalizeGapReason(gap),
        detail = gap.detail,
    )

    private fun normalizeGapReason(gap: RawEvidenceGap): EvidenceGap.GapReason =
        when (val r = gap.reason) {
            is dev.pipelinek.assurance.engine.RawGapReason.Unsupported ->
                EvidenceGap.GapReason.Unsupported
            is dev.pipelinek.assurance.engine.RawGapReason.Unknown ->
                EvidenceGap.GapReason.Unknown
            is dev.pipelinek.assurance.engine.RawGapReason.Lost ->
                EvidenceGap.GapReason.Lost
            is dev.pipelinek.assurance.engine.RawGapReason.PartialProduced ->
                EvidenceGap.GapReason.PartialProduced(r.coveredFraction)
            is dev.pipelinek.assurance.engine.RawGapReason.Other ->
                EvidenceGap.GapReason.Unknown
        }

    private val DETERMINISTIC_OR_RUNTIME = setOf(
        EvidenceAuthority.DeterministicAdapter,
        EvidenceAuthority.DeterministicAnalyzer,
        EvidenceAuthority.RuntimeObserver,
    )

    private val AGENT_OR_HUMAN = setOf(
        EvidenceAuthority.AgentHypothesis,
        EvidenceAuthority.HumanCurated,
    )
}
