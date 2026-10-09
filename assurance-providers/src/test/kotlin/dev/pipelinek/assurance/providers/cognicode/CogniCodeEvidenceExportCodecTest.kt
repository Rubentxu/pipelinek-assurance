/**
 * M2 — Tests del codec de `assurance-evidence/v1` consumido por CogniCode.
 *
 * Ref autoridad: `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` §Safety,
 * `03-specifications/PROVIDER_SPI.md`.
 *
 * Estos tests son ESPEJO de `EvidenceCodecRoundtripTest` del módulo M0:
 * no son exhaustivos (cubrimos el contrato del codec desde la frontera
 * externa, no las leyes de propiedad del dominio), pero sí son LOS tests
 * que rompen si alguien se salta una cota, una verificación de digest,
 * o un roundtrip.
 */
package dev.pipelinek.assurance.providers.cognicode

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * Batería de tests del codec `CogniCodeEvidenceExportCodec`.
 *
 * Patrón elegido: `AnnotationSpec` por consistencia con `M2ProviderSpiLawsTest`
 * y los demás fitness tests del repo. Cada test es independiente; los
 * `companion helpers` comparten un DTO bien formado sobre el que cada
 * test aplica su mutación.
 */
class CogniCodeEvidenceExportCodecTest : AnnotationSpec() {

    /** Construye un DTO válido, bien formado y con digest ya calculado. */
    private fun wellFormed(
        entities: List<EntityDto> = defaultEntities(),
        facts: List<FactDto> = defaultFacts(),
        relations: List<RelationDto> = defaultRelations(),
        signals: List<SignalDto> = defaultSignals(),
        completeness: Map<String, CapabilityCompletenessDto> = defaultCompleteness(),
        gaps: List<CapabilityGapDto> = emptyList(),
    ): CogniCodeEvidenceExportDto {
        val withoutDigest = CogniCodeEvidenceExportDto(
            apiVersion = CogniCodeEvidenceExportCodec.API_VERSION,
            kind = "EvidenceExport",
            producer = ProducerInfoDto(id = "cognicode", version = "0.1.0", schemaVersion = "1"),
            subject = SubjectRefDto(revision = "rev-001", kind = "Module"),
            manifest = ManifestSectionDto(
                requestedCapabilities = listOf(
                    "architecture.dependency-graph",
                    "architecture.entities",
                    "architecture.relations",
                    "signals.solid_audit",
                ),
                producedCapabilities = listOf(
                    "architecture.dependency-graph",
                    "architecture.entities",
                    "architecture.relations",
                    "signals.solid_audit",
                ),
                completenessByCapability = completeness,
                schemaVersion = CogniCodeEvidenceExportCodec.API_VERSION,
                digest = "0".repeat(64),
            ),
            entities = entities,
            facts = facts,
            relations = relations,
            signals = signals,
            sourceAnchors = listOf(
                SourceAnchorDto(file = "src/main/kotlin/Foo.kt", line = 42, column = 4, symbolRef = "Foo"),
            ),
            provenance = ProvenanceDto(
                producerId = "cognicode",
                producerVersion = "0.1.0",
                subjectRevision = "rev-001",
                capability = "architecture.entities",
            ),
            capabilityCompleteness = completeness,
            gaps = gaps,
            digest = "",
        )
        // Recalcula el digest con la convención del codec. Usa el helper
        // `internal digestOf` para no reimplementar la rutina de
        // verificación aquí — si la convención cambia, sólo cambia el codec.
        return withoutDigest.copy(digest = CogniCodeEvidenceExportCodec.digestOf(withoutDigest).hex)
    }

    // -----------------------------------------------------------------------
    // Roundtrip CBOR
    // -----------------------------------------------------------------------

    @Test
    fun cbor_roundtrip_preserves_all_sections() {
        val original = wellFormed()
        val bytes = CogniCodeEvidenceExportCodec.encodeToCbor(original)
        val decoded = CogniCodeEvidenceExportCodec.decodeFromCbor(bytes)

        decoded shouldBe original
    }

    // -----------------------------------------------------------------------
    // Roundtrip JSON
    // -----------------------------------------------------------------------

    @Test
    fun json_roundtrip_preserves_all_sections() {
        val original = wellFormed()
        val text = CogniCodeEvidenceExportCodec.encodeToJson(original)
        val decoded = CogniCodeEvidenceExportCodec.decodeFromJson(text)

        decoded shouldBe original
    }

    // -----------------------------------------------------------------------
    // Verificación de digest
    // -----------------------------------------------------------------------

    @Test
    fun digest_mismatch_fails_closed_with_explanatory_message() {
        val original = wellFormed()
        // Tampering: el digest declarado difiere del recalculado.
        // `copy(digest = ...)` no re-ejecuta init para digests arbitrarios
        // porque el campo no se valida en init (lo valida el codec).
        val tampered = original.copy(digest = "0".repeat(64))

        val bytes = CogniCodeEvidenceExportCodec.encodeToCbor(tampered)

        val ex = shouldThrow<CogniCodeEvidenceExportCodec.CodecException> {
            CogniCodeEvidenceExportCodec.decodeFromCbor(bytes)
        }
        ex.message shouldStartWith "digest declarado"
        ex.message shouldContain "no coincide con el"
    }

    // -----------------------------------------------------------------------
    // Bounded decoding — string oversized
    // -----------------------------------------------------------------------

    @Test
    fun oversized_string_is_rejected() {
        // Construimos un DTO directamente con un string > MAX_STRING_LENGTH
        // pasando por `copy` (que no ejecuta init para el campo en cuestión,
        // porque ningún init valida longitudes — esa es la capa codec).
        val huge = "x".repeat(CogniCodeEvidenceExportCodec.MAX_STRING_LENGTH + 1)
        val oversized = wellFormed().copy(
            entities = listOf(EntityDto(id = "id", kind = huge, name = "Foo", layer = null)),
        )

        // La codificación SÍ admite el DTO (lo construimos por `copy`,
        // saltándonos los bounds), pero el decode rechaza por requireWithinLength.
        val bytes = CogniCodeEvidenceExportCodec.encodeToCbor(oversized)

        val ex = shouldThrow<CogniCodeEvidenceExportCodec.CodecException> {
            CogniCodeEvidenceExportCodec.decodeFromCbor(bytes)
        }
        ex.message shouldContain "excede MAX_STRING_LENGTH"
    }

    // -----------------------------------------------------------------------
    // Bounded decoding — profundidad excesiva
    // -----------------------------------------------------------------------

    @Test
    fun excessive_nesting_is_rejected() {
        // No podemos producir un DTO sobre-anidado porque su shape está
        // fijado, así que escribimos JSON con anidamiento > MAX_NESTING_DEPTH.
        // El decoder pasa por JsonElement ANTES de construir el DTO, así
        // que esta validación se ejecuta incluso cuando el JSON no
        // corresponde al esquema (es defensa estructural, no defensa de
        // esquema). El formato CBOR tiene el mismo límite pero se testea
        // por la misma vía JsonElement del decoder.
        val depth = CogniCodeEvidenceExportCodec.MAX_NESTING_DEPTH + 1
        val nested = buildString {
            repeat(depth) { append("{\"a\":") }
            append("null")
            repeat(depth) { append("}") }
        }

        val ex = shouldThrow<CogniCodeEvidenceExportCodec.CodecException> {
            CogniCodeEvidenceExportCodec.decodeFromJson(nested)
        }
        ex.message shouldContain "MAX_NESTING_DEPTH"
    }

    // -----------------------------------------------------------------------
    // Helpers para construir DTOs válidos por defecto
    // -----------------------------------------------------------------------

    private fun defaultEntities(): List<EntityDto> = listOf(
        EntityDto(id = "mod-core", kind = "Module", name = "core", layer = "domain"),
        EntityDto(id = "sym-foo", kind = "Symbol", name = "core.Foo", layer = null),
    )

    private fun defaultFacts(): List<FactDto> = listOf(
        FactDto(
            id = "f-1",
            entityRef = "sym-foo",
            predicate = "imports",
            objectValue = "core.Bar",
            authority = "DeterministicAnalyzer",
            sourceAnchorRef = null,
        ),
    )

    private fun defaultRelations(): List<RelationDto> = listOf(
        RelationDto(from = "mod-core", to = "mod-adapters", kind = "depends-on", evidence = "f-1"),
    )

    private fun defaultSignals(): List<SignalDto> = listOf(
        SignalDto(
            id = "s-1",
            entityRef = "sym-foo",
            kind = "solid-audit",
            score = "yes",
            algorithmId = "solid-audit",
            algorithmVersion = "0.1.0",
            thresholds = mapOf("maxResponsibilities" to "1"),
        ),
    )

    private fun defaultCompleteness(): Map<String, CapabilityCompletenessDto> = mapOf(
        "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
        "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
        "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
        "signals.solid_audit" to CapabilityCompletenessDto.CompleteDto,
    )
}
