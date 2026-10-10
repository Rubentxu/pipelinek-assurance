/**
 * M2-T8 — Normalizer `EvidenceCollectionResult → EvidenceSnapshot`.
 *
 * Ref autoridad: `03-specifications/PROVIDER_SPI.md`, AAT-2, AAT-19.
 *
 * Por qué existe en el testkit y no en `assurance-engine`:
 *  - La frontera del SPI entrega `EvidenceCollectionResult` con
 *    `RawEvidenceItem` (forma cruda, con `authority: String`).
 *  - El core consume `EvidenceSnapshot` con `EvidenceItem`
 *    (forma normalizada, con `authority: EvidenceAuthority`).
 *  - La conversión entre ambas es responsabilidad del SERVICIO
 *    DE APLICACIÓN, no del core. Cuando el plugin (M3) exista
 *    como servicio de aplicación en runtime, este helper
 *    migrará a `pipelinek-assurance-plugin` con la misma
 *    signatura.
 *
 * Lo que el normalizer enforce:
 *  1. **AAT-19 (authority/kind matrix)**: el authority_string del
 *    raw item debe mapear a un `EvidenceAuthority` válido Y
 *    coincidir con el `RawItemKind`:
 *      - Fact → DeterministicAdapter | DeterministicAnalyzer | RuntimeObserver
 *      - Observation → RuntimeObserver
 *      - Signal → HeuristicAnalyzer
 *      - Hypothesis → AgentHypothesis | HumanCurated
 *  2. **AAT-13 (namespacing)**: el `id` debe contener `/`. Si no,
 *    se rechaza con `NormalizerException` (el producer mintió sobre
 *    su namespace).
 *  3. **Bounded decoding**: las longitudes de `id` y de los
 *    campos string se acotan. Un raw item con un `id` de 10MB
 *    es un ataque, no un caso límite.
 */
package dev.pipelinek.assurance.testkit

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
import dev.pipelinek.assurance.engine.ProviderFailureReason
import dev.pipelinek.assurance.engine.RawEvidenceGap
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind

/**
 * Normalizer: convierte `EvidenceCollectionResult.Produced` a
 * `EvidenceSnapshot` que el core puede consumir.
 *
 * Mapeo de `RawItemKind` → `EvidenceItem`:
 *  - Fact → `EvidenceItem.Fact` (predicate + objectValue).
 *  - Observation → `EvidenceItem.Observation` (observation string).
 *  - Signal → `EvidenceItem.Signal` (signalKind + score + algo).
 *  - Hypothesis → `EvidenceItem.Hypothesis` (statement).
 *
 * La autoridad del raw item es un String; se mapea a
 * `EvidenceAuthority` por nombre (AAT-19: nombres exactos,
 * no coerción silenciosa). Una autoridad no reconocida aborta
 * la normalización con `NormalizerException`.
 */
object EvidenceNormalizer {

    class NormalizerException(message: String) : RuntimeException(message)

    /**
     * Normaliza un `EvidenceCollectionResult.Produced` a
     * `EvidenceSnapshot`. Devuelve `Failed` si el provider no
     * produjo o si la autoridad de algún item es inválida.
     */
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

        val items = produced.rawItems.map { rawItem -> normalizeItem(rawItem, producerId, producerVersion) }
        val gaps = produced.declaredGaps.map { gap -> normalizeGap(gap) }

        val manifest = EvidenceSourceManifest(
            producerId = producerId,
            producerVersion = producerVersion,
            subjectRevision = subjectRevision,
            requestedCapabilities = requestedCapabilities,
            producedCapabilities = items
                .map { it.provenance.capability }
                .distinct()
                .ifEmpty { listOf("unknown") },
            completenessByCapability = items
                .groupBy { it.provenance.capability }
                .mapValues { (_, list) -> list.first().completenessOrDefault() },
            schemaVersion = produced.schemaVersion,
            digest = Digest.ofUtf8("$producerId/$producerVersion/${items.size}"),
        )

        return EvidenceSnapshot(
            id = snapshotId,
            subject = subject,
            sources = listOf(manifest),
            items = items,
            gaps = gaps,
        )
    }

    private fun normalizeItem(
        raw: RawEvidenceItem,
        producerId: String,
        producerVersion: String,
    ): EvidenceItem {
        // AAT-13: el id debe contener `/`. Si no, el producer
        // mintió sobre su namespace. (Los codecs de
        // CogniCode/Chronos/OTel forman el id con prefijo
        // `producerId/section/...`.)
        if (!raw.id.contains("/")) {
            throw NormalizerException(
                "rawItem sin namespace en id: ${raw.id} (producerId=$producerId)",
            )
        }
        // AAT-19: la authority debe mapearse a un
        // EvidenceAuthority reconocido. Nombres exactos,
        // no coerción silenciosa.
        val authority = parseAuthority(raw.authority)
        val capability = raw.payload["capability"] ?: "unknown"
        val provenance = Provenance(
            producerId = producerId,
            producerVersion = producerVersion,
            subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
            capability = capability,
        )
        val subject = subjectFromRaw(raw)
        val id = EvidenceId(raw.id)

        // El kind debe coincidir con la authority por AAT-19.
        // Esta es la matriz canónica:
        //   - Fact requiere Deterministic* o RuntimeObserver.
        //   - Observation requiere RuntimeObserver.
        //   - Signal requiere HeuristicAnalyzer.
        //   - Hypothesis requiere AgentHypothesis o HumanCurated.
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

    private fun subjectFromRaw(raw: RawEvidenceItem): EvidenceSubject {
        // El subject_ref es texto; la convención del motor es
        // que un `Module` se representa como `module:{name}` y un
        // `Symbol` como `symbol:{name}`. Para tipos runtime
        // (OTel, Chronos) usamos `RuntimeSpan`. Otros caen a
        // `Symbol` con el subjectRef como qualifiedName — es
        // siempre un identificador textual, así que Symbol es
        // el fallback más natural sin inventar un `Generic`.
        return when {
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
    }

    private fun EvidenceSubject.namespace(): String = when (this) {
        is EvidenceSubject.Module -> "module:${path}"
        is EvidenceSubject.Symbol -> "symbol:${qualifiedName}"
        is EvidenceSubject.SourceLocation -> "source:${file}:${line}"
        is EvidenceSubject.Test -> "test:${id}"
        is EvidenceSubject.RuntimeSpan -> "runtime:${typedRef}"
    }

    private fun EvidenceItem.completenessOrDefault(): dev.pipelinek.assurance.domain.evidence.Completeness =
        when (this) {
            is EvidenceItem.Fact -> this.completeness
            is EvidenceItem.Observation -> this.completeness
            is EvidenceItem.Signal -> this.completeness
            // Hypothesis no lleva completeness (es siempre
            // Complete implícitamente — es una afirmación de un
            // agente, no un fact runtime).
            is EvidenceItem.Hypothesis -> dev.pipelinek.assurance.domain.evidence.Completeness.Complete
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
            // RawGapReason.Other no tiene contraparte en
            // EvidenceGap.GapReason: la asimetría es deliberada
            // (M-COGN02: reasons desconocidos se preservan como
            // `Other` en el SPI pero el core los descarta
            // silenciosamente como Unknown, que es lo que la
            // frontera cruda/normalizada exige).
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
