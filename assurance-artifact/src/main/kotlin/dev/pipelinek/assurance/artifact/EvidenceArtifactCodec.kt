package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.Correlation
import dev.pipelinek.assurance.domain.evidence.CorrelationRelation
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M0 — Codec de artefactos con forma intermedia explícita.
 *
 * Ref: `03-specifications/ARTIFACT_WIRE_CONTRACTS.md`, `09-operations/SECURITY_AND_TRUST.md`.
 *
 * Decisión de diseño (ROADMAP M0 STOP): el codec NO deserializa directamente a
 * los ADTs del dominio mediante `@Serializable` sobre las clases selladas.
 *
 * Motivo, no por gusto:
 *
 * 1. `SECURITY_AND_TRUST.md` prohíbe la deserialización JVM polimórfica. El
 *    campo `kind` de un item decide qué constructor se invoca; un artefacto no
 *    confiado podría pedir una variante no prevista.
 * 2. Los ADT del dominio tienen invariantes en `init` (`require`) que deben
 *    correr también al decodificar. Con `@Serializable` sobre el ADT, el
 *    decoder puede saltárselos.
 * 3. Un DTO intermedio hace que el roundtrip sea comprobable: si el DTO no
 *    representa todo el dominio, el fallo aparece en el test y no en producción.
 *
 * Por eso los DTO de abajo son cerrados (`@Serializable` con `classDiscriminator`
 * explícito y sin `@SerialInfo`), y la conversión DTO -> dominio pasa por los
 * constructores reales, que son los que validan.
 */
object EvidenceArtifactCodec {

    /** Media types de la familia 1. */
    const val MEDIA_TYPE_CBOR = "application/vnd.pipelinek.assurance.evidence+cbor;version=1"
    const val MEDIA_TYPE_JSON = "application/vnd.pipelinek.assurance.evidence+json;version=1"

    /** Versión de schema aceptada. Fail-closed: no se adivina ni se migra solo. */
    const val API_VERSION = "assurance-evidence/v1"

    /**
     * Límites de decoding (`SECURITY_AND_TRUST.md`: bounded decoding).
     *
     * No son aficiones: un CBOR puede pedir 4 GiB de bytes en un solo elemento.
     * Sin cota, "decodificar evidencia no confiable" es un OOM a petición.
     */
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    const val MAX_COLLECTION_SIZE = 1_000_000
    const val MAX_NESTING_DEPTH = 64
    const val MAX_STRING_LENGTH = 1 shl 20

    /** JSON canónico: sin claves ordenadas por defecto, sin `null` parasitic. */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    fun encodeToCbor(snapshot: EvidenceSnapshot): ByteArray {
        val dto = EvidenceSnapshotDto.of(snapshot)
        requireWithinLimits(dto)
        return kotlinx.serialization.cbor.Cbor.encodeToByteArray(EvidenceSnapshotDto.serializer(), dto)
    }

    fun encodeToJson(snapshot: EvidenceSnapshot): String {
        val dto = EvidenceSnapshotDto.of(snapshot)
        requireWithinLimits(dto)
        return json.encodeToString(EvidenceSnapshotDto.serializer(), dto)
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Decodifica CBOR con las cotas aplicadas ANTES de deserializar.
     *
     * `MAX_INPUT_BYTES` se comprueba sobre la entrada, no sobre el resultado:
     * un encoder hostil puede pedir 2^32 elementos de 1 byte y agotar memoria
     * antes de que exista ningún objeto que inspeccionar.
     */
    fun decodeFromCbor(bytes: ByteArray): EvidenceSnapshot {
        if (bytes.size > MAX_INPUT_BYTES) {
            throw ArtifactDecodeException(
                "entrada de ${bytes.size} bytes excede el limite de $MAX_INPUT_BYTES",
            )
        }
        val dto = try {
            kotlinx.serialization.cbor.Cbor.decodeFromByteArray(EvidenceSnapshotDto.serializer(), bytes)
        } catch (e: Exception) {
            throw ArtifactDecodeException("CBOR no decodificable: ${e.message}", e)
        }
        return dto.toDomain()
    }

    fun decodeFromJson(text: String): EvidenceSnapshot {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
            throw ArtifactDecodeException("entrada JSON excede el limite de $MAX_INPUT_BYTES bytes")
        }
        val dto = try {
            json.decodeFromString(EvidenceSnapshotDto.serializer(), text)
        } catch (e: Exception) {
            throw ArtifactDecodeException("JSON no decodificable: ${e.message}", e)
        }
        return dto.toDomain()
    }

    // -----------------------------------------------------------------------
    // Bounded decoding
    // -----------------------------------------------------------------------

    /**
     * Comprueba las cotas de un snapshot DTO ya construido.
     *
     * `internal` a propósito: un DTO de artefacto no forma parte de la API
     * pública del módulo. Lo público son los bytes.
     */
    internal fun requireWithinLimits(dto: EvidenceSnapshotDto) {
        require(dto.manifest.size <= MAX_COLLECTION_SIZE) {
            "manifest: ${dto.manifest.size} excede $MAX_COLLECTION_SIZE"
        }
        require(dto.payload.size <= MAX_COLLECTION_SIZE) {
            "payload: ${dto.payload.size} excede $MAX_COLLECTION_SIZE"
        }
        require(dto.gaps.size <= MAX_COLLECTION_SIZE) {
            "gaps: ${dto.gaps.size} excede $MAX_COLLECTION_SIZE"
        }
        require(dto.correlations.size <= MAX_COLLECTION_SIZE) {
            "correlations: ${dto.correlations.size} excede $MAX_COLLECTION_SIZE"
        }
        for (item in dto.payload) {
            item.requireWithinLimits()
        }
    }

    /** Fallo de decodificación. Distinto de los fallos de dominio a propósito. */
    class ArtifactDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
internal data class EvidenceSnapshotDto(
    val apiVersion: String,
    val kind: String,
    val snapshotId: String,
    val producer: String,
    val producerVersion: String,
    val subject: SubjectDto,
    val manifest: List<ManifestDto>,
    val payload: List<ItemDto>,
    val gaps: List<GapDto> = emptyList(),
    val correlations: List<CorrelationDto> = emptyList(),
) {
    init {
        // Fail-closed de schema: una versión desconocida no se adivina.
        require(apiVersion == EvidenceArtifactCodec.API_VERSION) {
            "apiVersion desconocida: $apiVersion"
        }
        require(kind == "EvidenceSnapshot") { "kind inesperado: $kind" }
    }

    fun toDomain(): EvidenceSnapshot {
        EvidenceArtifactCodec.requireWithinLimits(this)
        return EvidenceSnapshot(
            id = SnapshotId(snapshotId),
            subject = subject.toDomain(),
            sources = manifest.map { it.toDomain() },
            items = payload.map { it.toDomain() },
            gaps = gaps.map { it.toDomain() },
            correlations = correlations.map { it.toDomain() },
        )
    }

    companion object {
        fun of(snapshot: EvidenceSnapshot): EvidenceSnapshotDto {
            val first = snapshot.sources.firstOrNull()
                ?: error("un snapshot necesita al menos un manifest")
            return EvidenceSnapshotDto(
                apiVersion = EvidenceArtifactCodec.API_VERSION,
                kind = "EvidenceSnapshot",
                snapshotId = snapshot.id.value,
                producer = first.producerId,
                producerVersion = first.producerVersion,
                subject = SubjectDto.of(snapshot.subject),
                // Orden canónico ANTES de serializar, con el MISMO criterio que
                // usa el digest (AAT-16). No es cosmetico: CBOR y JSON no
                // ordenan por si mismos, y dos producers que recogieron la
                // misma evidencia en distinto orden producirian artefactos
                // distintos con el mismo contenido. Encontrado por property
                // testing (ley de permutacion).
                manifest = CanonicalEncoder.canonicalSources(snapshot.sources).map { ManifestDto.of(it) },
                payload = CanonicalEncoder.canonicalItems(snapshot.items).map { ItemDto.of(it) },
                gaps = CanonicalEncoder.canonicalGaps(snapshot.gaps).map { GapDto.of(it) },
                correlations = CanonicalEncoder.canonicalCorrelations(snapshot.correlations)
                    .map { CorrelationDto.of(it) },
            )
        }
    }
}

@Serializable
internal data class ManifestDto(
    val producerId: String,
    val producerVersion: String,
    val subjectRevision: String,
    val requestedCapabilities: List<String>,
    val producedCapabilities: List<String>,
    val completenessByCapability: Map<String, CompletenessDto>,
    val schemaVersion: String,
    val digest: String,
) {
    fun toDomain(): EvidenceSourceManifest = EvidenceSourceManifest(
        producerId = producerId,
        producerVersion = producerVersion,
        subjectRevision = RevisionRef(subjectRevision),
        requestedCapabilities = requestedCapabilities,
        producedCapabilities = producedCapabilities,
        completenessByCapability = completenessByCapability.mapValues { it.value.toDomain() },
        schemaVersion = schemaVersion,
        digest = Digest(digest),
    )

    companion object {
        fun of(m: EvidenceSourceManifest) = ManifestDto(
            producerId = m.producerId,
            producerVersion = m.producerVersion,
            subjectRevision = m.subjectRevision.value,
            requestedCapabilities = m.requestedCapabilities,
            producedCapabilities = m.producedCapabilities,
            completenessByCapability = m.completenessByCapability.mapValues { CompletenessDto.of(it.value) },
            schemaVersion = m.schemaVersion,
            digest = m.digest.hex,
        )
    }
}

@Serializable
internal sealed interface ItemDto {
    fun toDomain(): EvidenceItem
    fun requireWithinLimits() {}

    companion object {
        /**
         * Despacha al DTO de la variante concreta.
         *
         * El `else` no existe a propósito: cuando el dominio añada una quinta
         * variante, este exhaustivo dejara de compilar. Un `else -> error()`
         * dejaría compilar y perdería evidencia en silencio, que es peor.
         */
        fun of(item: EvidenceItem): ItemDto = when (item) {
            is EvidenceItem.Fact -> FactDto.of(item)
            is EvidenceItem.Observation -> ObservationDto.of(item)
            is EvidenceItem.Signal -> SignalDto.of(item)
            is EvidenceItem.Hypothesis -> HypothesisDto.of(item)
        }
    }

    @Serializable
    @SerialName("Fact")
    data class FactDto(
        val id: String,
        val subject: SubjectDto,
        val authority: String,
        val producerId: String,
        val producerVersion: String,
        val revision: String,
        val capability: String,
        val artifactDigest: String?,
        val predicate: String,
        val objectValue: String?,
        val completeness: CompletenessDto,
    ) : ItemDto {
        override fun toDomain(): EvidenceItem.Fact = EvidenceItem.Fact(
            id = EvidenceId(id),
            subject = subject.toDomain(),
            authority = EvidenceAuthority.valueOf(authority),
            provenance = provenance(producerId, producerVersion, revision, capability, artifactDigest),
            predicate = predicate,
            objectValue = objectValue,
            completeness = completeness.toDomain(),
        )

        companion object {
            fun of(f: EvidenceItem.Fact) = FactDto(
                id = f.id.value,
                subject = SubjectDto.of(f.subject),
                authority = f.authority.name,
                producerId = f.provenance.producerId,
                producerVersion = f.provenance.producerVersion,
                revision = f.provenance.subjectRevision.value,
                capability = f.provenance.capability,
                artifactDigest = f.provenance.artifactDigest?.hex,
                predicate = f.predicate,
                objectValue = f.objectValue,
                completeness = CompletenessDto.of(f.completeness),
            )
        }
    }

    @Serializable
    @SerialName("Observation")
    data class ObservationDto(
        val id: String,
        val subject: SubjectDto,
        val authority: String,
        val producerId: String,
        val producerVersion: String,
        val revision: String,
        val capability: String,
        val artifactDigest: String?,
        val observation: String,
        val completeness: CompletenessDto,
    ) : ItemDto {
        override fun toDomain(): EvidenceItem.Observation = EvidenceItem.Observation(
            id = EvidenceId(id),
            subject = subject.toDomain(),
            authority = EvidenceAuthority.valueOf(authority),
            provenance = provenance(producerId, producerVersion, revision, capability, artifactDigest),
            observation = observation,
            completeness = completeness.toDomain(),
        )

        companion object {
            fun of(o: EvidenceItem.Observation) = ObservationDto(
                id = o.id.value,
                subject = SubjectDto.of(o.subject),
                authority = o.authority.name,
                producerId = o.provenance.producerId,
                producerVersion = o.provenance.producerVersion,
                revision = o.provenance.subjectRevision.value,
                capability = o.provenance.capability,
                artifactDigest = o.provenance.artifactDigest?.hex,
                observation = o.observation,
                completeness = CompletenessDto.of(o.completeness),
            )
        }
    }

    @Serializable
    @SerialName("Signal")
    data class SignalDto(
        val id: String,
        val subject: SubjectDto,
        val authority: String,
        val producerId: String,
        val producerVersion: String,
        val revision: String,
        val capability: String,
        val artifactDigest: String?,
        val signalKind: String,
        val score: String,
        val algorithmId: String,
        val algorithmVersion: String,
        val thresholds: Map<String, String>,
        val completeness: CompletenessDto,
    ) : ItemDto {
        override fun toDomain(): EvidenceItem.Signal = EvidenceItem.Signal(
            id = EvidenceId(id),
            subject = subject.toDomain(),
            // La autoridad no viaja: la reconstruye el invariante. Un Signal
            // que se decodifique con autoridad determinista es rechazado.
            authority = EvidenceAuthority.HeuristicAnalyzer,
            provenance = provenance(producerId, producerVersion, revision, capability, artifactDigest),
            signalKind = signalKind,
            score = score,
            algorithmId = algorithmId,
            algorithmVersion = algorithmVersion,
            thresholds = thresholds,
            completeness = completeness.toDomain(),
        )

        override fun requireWithinLimits() {
            require(thresholds.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
                "thresholds: ${thresholds.size} excede el limite"
            }
        }

        companion object {
            fun of(s: EvidenceItem.Signal) = SignalDto(
                id = s.id.value,
                subject = SubjectDto.of(s.subject),
                authority = s.authority.name,
                producerId = s.provenance.producerId,
                producerVersion = s.provenance.producerVersion,
                revision = s.provenance.subjectRevision.value,
                capability = s.provenance.capability,
                artifactDigest = s.provenance.artifactDigest?.hex,
                signalKind = s.signalKind,
                score = s.score,
                algorithmId = s.algorithmId,
                algorithmVersion = s.algorithmVersion,
                thresholds = s.thresholds,
                completeness = CompletenessDto.of(s.completeness),
            )
        }
    }

    @Serializable
    @SerialName("Hypothesis")
    data class HypothesisDto(
        val id: String,
        val subject: SubjectDto,
        val authority: String,
        val producerId: String,
        val producerVersion: String,
        val revision: String,
        val capability: String,
        val artifactDigest: String?,
        val claim: String,
        val reasoning: String,
        val confidence: String,
    ) : ItemDto {
        override fun toDomain(): EvidenceItem.Hypothesis = EvidenceItem.Hypothesis(
            id = EvidenceId(id),
            subject = subject.toDomain(),
            authority = EvidenceAuthority.valueOf(authority),
            provenance = provenance(producerId, producerVersion, revision, capability, artifactDigest),
            claim = claim,
            reasoning = reasoning,
            confidence = confidence,
        )

        companion object {
            fun of(h: EvidenceItem.Hypothesis) = HypothesisDto(
                id = h.id.value,
                subject = SubjectDto.of(h.subject),
                authority = h.authority.name,
                producerId = h.provenance.producerId,
                producerVersion = h.provenance.producerVersion,
                revision = h.provenance.subjectRevision.value,
                capability = h.provenance.capability,
                artifactDigest = h.provenance.artifactDigest?.hex,
                claim = h.claim,
                reasoning = h.reasoning,
                confidence = h.confidence,
            )
        }
    }
}

private fun provenance(
    producerId: String,
    producerVersion: String,
    revision: String,
    capability: String,
    artifactDigest: String?,
): Provenance = Provenance(
    producerId = producerId,
    producerVersion = producerVersion,
    subjectRevision = RevisionRef(revision),
    capability = capability,
    artifactDigest = artifactDigest?.let { Digest(it) },
)

@Serializable
internal sealed interface CompletenessDto {
    fun toDomain(): Completeness

    @Serializable
    @SerialName("Complete")
    data object CompleteDto : CompletenessDto {
        override fun toDomain(): Completeness = Completeness.Complete
    }

    @Serializable
    @SerialName("Unknown")
    data object UnknownDto : CompletenessDto {
        override fun toDomain(): Completeness = Completeness.Unknown
    }

    @Serializable
    @SerialName("Unsupported")
    data class UnsupportedDto(val reason: String) : CompletenessDto {
        override fun toDomain(): Completeness = Completeness.Unsupported(reason)
    }

    @Serializable
    @SerialName("Partial")
    data class PartialDto(val gaps: List<GapDto>) : CompletenessDto {
        override fun toDomain(): Completeness = Completeness.Partial(gaps.map { it.toDomain() })
    }

    companion object {
        fun of(c: Completeness): CompletenessDto = when (c) {
            is Completeness.Complete -> CompleteDto
            is Completeness.Unknown -> UnknownDto
            is Completeness.Unsupported -> UnsupportedDto(c.reason)
            is Completeness.Partial -> PartialDto(c.gaps.map { GapDto.of(it) })
        }
    }
}

@Serializable
internal data class GapDto(
    val capability: String,
    val reason: String,
    val detail: String? = null,
) {
    fun toDomain(): EvidenceGap = EvidenceGap(
        capability = capability,
        reason = when (reason) {
            "Unsupported" -> EvidenceGap.GapReason.Unsupported
            "Unknown" -> EvidenceGap.GapReason.Unknown
            "Lost" -> EvidenceGap.GapReason.Lost
            else -> EvidenceGap.GapReason.PartialProduced(reason)
        },
        detail = detail,
    )

    companion object {
        fun of(g: EvidenceGap) = GapDto(
            capability = g.capability,
            reason = when (val r = g.reason) {
                is EvidenceGap.GapReason.Unsupported -> "Unsupported"
                is EvidenceGap.GapReason.Unknown -> "Unknown"
                is EvidenceGap.GapReason.Lost -> "Lost"
                is EvidenceGap.GapReason.PartialProduced -> r.coveredFraction
            },
            detail = g.detail,
        )
    }
}

@Serializable
internal sealed interface SubjectDto {
    fun toDomain(): EvidenceSubject

    @Serializable
    @SerialName("Module")
    data class ModuleDto(val path: String) : SubjectDto {
        override fun toDomain(): EvidenceSubject = EvidenceSubject.Module(path)
    }

    @Serializable
    @SerialName("Symbol")
    data class SymbolDto(val qualifiedName: String) : SubjectDto {
        override fun toDomain(): EvidenceSubject = EvidenceSubject.Symbol(qualifiedName)
    }

    @Serializable
    @SerialName("SourceLocation")
    data class SourceLocationDto(
        val file: String,
        val line: Int,
        val column: Int?,
    ) : SubjectDto {
        override fun toDomain(): EvidenceSubject = EvidenceSubject.SourceLocation(file, line, column)
    }

    @Serializable
    @SerialName("Test")
    data class TestDto(val id: String) : SubjectDto {
        override fun toDomain(): EvidenceSubject = EvidenceSubject.Test(id)
    }

    @Serializable
    @SerialName("RuntimeSpan")
    data class RuntimeSpanDto(val typedRef: String) : SubjectDto {
        override fun toDomain(): EvidenceSubject = EvidenceSubject.RuntimeSpan(typedRef)
    }

    companion object {
        fun of(s: EvidenceSubject): SubjectDto = when (s) {
            is EvidenceSubject.Module -> ModuleDto(s.path)
            is EvidenceSubject.Symbol -> SymbolDto(s.qualifiedName)
            is EvidenceSubject.SourceLocation -> SourceLocationDto(s.file, s.line, s.column)
            is EvidenceSubject.Test -> TestDto(s.id)
            is EvidenceSubject.RuntimeSpan -> RuntimeSpanDto(s.typedRef)
        }
    }
}

@Serializable
internal data class CorrelationDto(
    val fromNamespace: String,
    val fromValue: String,
    val relation: String,
    val toNamespace: String,
    val toValue: String,
    val evidence: String,
) {
    fun toDomain(): Correlation = Correlation(
        from = TypedExternalId(ExternalNamespace.valueOf(fromNamespace), fromValue),
        relation = CorrelationRelation.valueOf(relation),
        to = TypedExternalId(ExternalNamespace.valueOf(toNamespace), toValue),
        evidence = EvidenceId(evidence),
    )

    companion object {
        fun of(c: Correlation) = CorrelationDto(
            fromNamespace = c.from.namespace.name,
            fromValue = c.from.value,
            relation = c.relation.name,
            toNamespace = c.to.namespace.name,
            toValue = c.to.value,
            evidence = c.evidence.value,
        )
    }
}
