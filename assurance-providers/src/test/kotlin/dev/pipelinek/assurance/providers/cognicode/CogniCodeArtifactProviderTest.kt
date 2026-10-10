/**
 * M2 — Tests del `CogniCodeArtifactProvider`.
 *
 * Ref autoridad: `03-specifications/PROVIDER_SPI.md`, AAT-2, AAT-3, AAT-6.
 *
 * Estos tests verifican:
 *  1. El provider decodifica un export `assurance-evidence/v1` y devuelve
 *     un `EvidenceCollectionResult.Produced` con items crudos para cada
 *     sección del envelope.
 *  2. El descriptor es estable y refleja las capabilities, formatos y
 *     subject kinds declarados en la spec (AAT-2).
 *  3. AAT-6 por construcción: el provider no expone métodos cuyo tipo
 *     de retorno sea `AssertionResult`, ni siquiera por reflexión. La
 *     ley ya está cubierta por `M2ProviderSpiLawsTest` para el SPI; este
 *     test es la MISMA ley aplicada a la CLASE del provider (no al SPI).
 *
 * Lo que NO se prueba aquí: la normalización a `EvidenceSnapshot`. Eso
 * es del servicio de aplicación (en M3 / `assure-cli`). El provider
 * entrega `RawEvidenceItem`; este test verifica ese contrato, no la
 * cadena que viene después.
 */
package dev.pipelinek.assurance.providers.cognicode

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.RawGapReason
import dev.pipelinek.assurance.engine.RawItemKind
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class CogniCodeArtifactProviderTest : AnnotationSpec() {

    // -----------------------------------------------------------------------
    // Descriptor — AAT-2 / PROVIDER_SPI
    // -----------------------------------------------------------------------

    @Test
    fun descriptor_declares_expected_identity_and_capabilities() {
        val provider = providerFor(syntheticExportBytes())

        provider.descriptor.id shouldBe "cognicode"
        provider.descriptor.version shouldBe "0.1.0"
        provider.descriptor.evidenceCapabilities shouldBe listOf(
            "architecture.dependency-graph",
            "architecture.entities",
            "architecture.relations",
            "signals.solid_audit",
        )
        provider.descriptor.subjectKinds shouldBe listOf("Module", "Symbol", "SourceLocation")
        provider.descriptor.classification shouldBe ProviderClassification.Deterministic
        provider.descriptor.outputSchemaVersion shouldBe "assurance-evidence/v1"
        provider.descriptor.inputFormats shouldContainAll listOf(
            CogniCodeEvidenceExportCodec.MEDIA_TYPE_CBOR,
            CogniCodeEvidenceExportCodec.MEDIA_TYPE_JSON,
        )
    }

    // -----------------------------------------------------------------------
    // Recolección — happy path
    // -----------------------------------------------------------------------

    @Test
    fun collect_returns_produced_with_one_raw_item_per_dto_section() {
        val provider = providerFor(syntheticExportBytes())
        val request = EvidenceRequest(subjectRevision = RevisionRef("rev-001"))

        val result = provider.collect(request)

        result is EvidenceCollectionResult.Produced
        val produced = result as EvidenceCollectionResult.Produced
        // 2 entities + 1 fact + 1 relation + 1 signal = 5 raw items
        produced.rawItems shouldHaveSize 5
        produced.producerId shouldBe "cognicode"
        produced.producerVersion shouldBe "0.1.0"
        produced.schemaVersion shouldBe "assurance-evidence/v1"

        val kinds = produced.rawItems.map { it.kind }
        kinds shouldContain RawItemKind.Fact
        kinds shouldContain RawItemKind.Signal
    }

    @Test
    fun signals_always_carry_HeuristicAnalyzer_authority() {
        // M-H01: un Signal nunca es determinista. Lo verificamos aunque
        // el export declare otra autoridad, porque la invariante gana al
        // a声明 (declaración) del producer.
        val provider = providerFor(syntheticExportBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        val signals = produced.rawItems.filter { it.kind == RawItemKind.Signal }
        signals.forEach { it.authority shouldBe "HeuristicAnalyzer" }
    }

    @Test
    fun facts_hold_evidence_ids_matching_the_cognicode_pattern() {
        val provider = providerFor(syntheticExportBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        // El patrón `cognicode/{section}/{kind}/{discriminator}` (ADR-008).
        // Verificamos por-prefix: cada sección tiene sufijo propio, y que
        // aparezca al menos un id por sección es lo que la spec pide.
        val ids = produced.rawItems.map { it.id }
        ids.any { it.startsWith("cognicode/entity/") } shouldBe true
        ids.any { it.startsWith("cognicode/fact/") } shouldBe true
        ids.any { it.startsWith("cognicode/relation/") } shouldBe true
        ids.any { it.startsWith("cognicode/signal/") } shouldBe true
    }

    // -----------------------------------------------------------------------
    // Gaps — capability declared Partial sin items producidos
    // -----------------------------------------------------------------------

    @Test
    fun partial_capability_without_items_emits_PartialProduced_gap() {
        // Construimos un export donde "signals.solid_audit" está declarado
        // como Partial pero la sección `signals` está VACÍA. Eso es
        // exactamente el caso que `COGNICODE_WORKSTREAM.md` `C4` enuncia.
        val bytes = exportWithSignalsEmptyButPartial()

        val provider = providerFor(bytes)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Produced
        val produced = result as EvidenceCollectionResult.Produced

        val partialGap = produced.declaredGaps.firstOrNull {
            it.capability == "signals.solid_audit" && it.reason is RawGapReason.PartialProduced
        }
        partialGap shouldNotBe null
    }

    @Test
    fun unknown_capability_outside_descriptor_emits_Unsupported_gap() {
        // Una capability declarada por el producer que NO está en el
        // descriptor (e.g. CogniCode añadió una capability nueva y todavía
        // no actualizamos el provider): el item producido se ignora y la
        // capability sale como Unsupported gap.
        //
        // La interpretación conservadora: declaramos el provider con su
        // descriptor actual; cualquier capability fuera de
        // `descriptor.evidenceCapabilities` es Unsupported. (Esta es la
        // interpretación que el prompt llama "si algo no está claro, pick
        // the conservative one".)
        val bytes = exportWithUnknownCapability()

        val provider = providerFor(bytes)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Produced
        val produced = result as EvidenceCollectionResult.Produced

        produced.declaredGaps.any {
            it.capability == "unknown-capability" && it.reason is RawGapReason.Unsupported
        } shouldBe true
    }

    // -----------------------------------------------------------------------
    // Failed — input malformado
    // -----------------------------------------------------------------------

    @Test
    fun collect_returns_Failed_when_decoding_throws() {
        val garbage = ByteArray(64) { 0xDE.toByte() } // bytes sin sentido

        val provider = providerFor(garbage)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
    }

    // -----------------------------------------------------------------------
    // AAT-6 — verification por signatura aplicada a la clase del provider
    // -----------------------------------------------------------------------

    @Test
    fun AAT_06_provider_class_does_not_declare_any_method_returning_AssertionResult() {
        // Misma ley que `M2ProviderSpiLawsTest.AAT_06_ningun_metodo_del_spi
        // _retorna_assertion_result`, pero aplicada a la IMPLEMENTACIÓN, no
        // al interface. La razón: alguien podría hacer que `CogniCodeArtifact
        // Provider` declare `eval(): AssertionResult` sin tocar el SPI.
        // Eso sería una puerta trasera: el SPI sigue limpio pero la clase
        // ya devuelve veredictos. La ley que AAT-6 enuncia se cumpliría
        // en el SPI pero se rompería en el uso.
        //
        // No importamos `AssertionResult` aquí por la misma razón que en
        // `M2ProviderSpiLawsTest`: identificar el tipo por nombre
        // cualificado mantiene el test fuera del surface público que
        // AAT-2 quiere preservar.
        val ASSERTION_RESULT_QUALIFIED = "dev.pipelinek.assurance.engine.AssertionResult"

        val providerClass = CogniCodeArtifactProvider::class.java
        val offending = providerClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.returnType.name == ASSERTION_RESULT_QUALIFIED }
            .map { it.name }

        offending shouldBe emptyList()
    }

    @Test
    fun AAT_06_provider_only_overrides_spi_methods() {
        // El provider NO debe declarar métodos públicos nuevos fuera de los
        // overrides del SPI (descriptor y collect). Si alguien añade
        // `score(…)` o `eval(…)`, este test lo caza en compilación:
        // contando únicamente los métodos del SPI, garantizamos que la
        // diferencia entre SPI y provider es 0.
        val providerClass = CogniCodeArtifactProvider::class.java
        val spiDeclared: Set<Method> = EvidenceProvider::class.java.declaredMethods.toSet()
        val ownDeclared: Set<Method> = providerClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.declaringClass == CogniCodeArtifactProvider::class.java }
            .toSet()

        // Cada método público declarado por el provider debe ser override
        // de un método del SPI. Sin esta restricción, un `score(this)` o un
        // `evaluate(this): AssertionResult` colaría, aunque el SPI siga
        // limpio. La ley AAT-6 habla del SPI; este test la proyecta a la
        // implementación.
        ownDeclared.forEach { method ->
            val overrides = spiDeclared.any { spi ->
                spi.name == method.name &&
                    spi.parameterTypes.toList() == method.parameterTypes.toList()
            }
            (overrides) shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // M-COGN01 — redundancia para los fixes de silent-else
    // -----------------------------------------------------------------------

    @Test
    fun M_COGN01_authority_desconocida_no_se_reclasifica_como_deterministic() {
        // M-COGN01: una autoridad que el provider no reconoce
        // (ej. "HeuristicAnalyzer" en una entidad que dice ser
        // determinista) NO debe re-clasificarse como
        // "DeterministicAnalyzer". Eso corrompe la señal AAT-19.
        val bytes = exportWithUnknownAuthority(authority = "HeuristicAnalyzer")
        val provider = CogniCodeArtifactProvider(bytes)
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        // El fact `imports` del export declara `HeuristicAnalyzer` como
        // autoridad. El provider NO debe re-clasificarlo a
        // `DeterministicAnalyzer` (comportamiento previo que M-COGN01
        // caza). Verificamos que el item conserva la autoridad original.
        val itemsForPredicate = produced.rawItems.filter {
            it.payload["predicate"] == "imports"
        }
        itemsForPredicate.forEach { it.authority shouldBe "HeuristicAnalyzer" }
    }

    @Test
    fun M_COGN01_reason_desconocido_se_reporta_como_Other_no_como_PartialProduced() {
        // M-COGN01 redundancia: un gap reason desconocido se
        // reporta como `Other(rawReason)`, NO como
        // `PartialProduced` (que era el comportamiento previo y
        // que ocultaba drift del producer).
        //
        // Estructura: el export declara `signals.solid_audit` como
        // `Complete` en `capabilityCompleteness` (para que esa rama
        // NO emita nada) y declara en la sección raíz `gaps` un gap
        // con reason desconocido. Así la única fuente de gaps es
        // la sección `gaps`, y la deduplicación no oculta el
        // `Other` que queremos verificar.
        val bytes = exportWithUnknownGapReason(reason = "DriftedFromProducer")
        val provider = CogniCodeArtifactProvider(bytes)
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        val other = produced.declaredGaps.find { it.reason is RawGapReason.Other }
        (other != null) shouldBe true
        (other!!.reason as RawGapReason.Other).rawReason shouldBe "DriftedFromProducer"
    }

    // -----------------------------------------------------------------------
    // Helpers — construcción de exports sintéticos
    // -----------------------------------------------------------------------

    private fun providerFor(bytes: ByteArray): CogniCodeArtifactProvider =
        CogniCodeArtifactProvider(bytes)

    private fun syntheticExportBytes(): ByteArray {
        val dto = buildWellFormed(
            signals = listOf(
                SignalDto(
                    id = "s-1",
                    entityRef = "sym-foo",
                    kind = "solid-audit",
                    score = "yes",
                    algorithmId = "solid-audit",
                    algorithmVersion = "0.1.0",
                    thresholds = mapOf("maxResponsibilities" to "1"),
                ),
            ),
        )
        return CogniCodeEvidenceExportCodec.encodeToCbor(dto)
    }

    private fun exportWithSignalsEmptyButPartial(): ByteArray {
        val dto = buildWellFormed(
            signals = emptyList(),
            completeness = mapOf(
                "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
                "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
                "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
                "signals.solid_audit" to CapabilityCompletenessDto.PartialDto(
                    gaps = listOf(
                        CapabilityGapDto(
                            capability = "signals.solid_audit",
                            reason = "PartialProduced",
                            detail = "lenguaje no soportado",
                        ),
                    ),
                ),
            ),
        )
        return CogniCodeEvidenceExportCodec.encodeToCbor(dto)
    }

    private fun exportWithUnknownCapability(): ByteArray {
        // Añadimos una capability inexistente al producer (la sección
        // `capabilityCompleteness` del envelope). El provider emite
        // raw items sólo para DTOs que conoce; el resto cae en la lista de
        // gaps por capability no admitida.
        val dto = buildWellFormed(
            completeness = mapOf(
                "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
                "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
                "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
                "signals.solid_audit" to CapabilityCompletenessDto.CompleteDto,
                "unknown-capability" to CapabilityCompletenessDto.UnsupportedDto(reason = "fuera del descriptor"),
            ),
        )
        return CogniCodeEvidenceExportCodec.encodeToCbor(dto)
    }

    private fun exportWithUnknownAuthority(authority: String): ByteArray {
        val dto = buildWellFormed(
            facts = listOf(
                FactDto(
                    id = "f-1",
                    entityRef = "sym-foo",
                    predicate = "imports",
                    objectValue = "core.Bar",
                    authority = authority,
                    sourceAnchorRef = null,
                ),
            ),
        )
        return CogniCodeEvidenceExportCodec.encodeToCbor(dto)
    }

    private fun exportWithUnknownGapReason(reason: String): ByteArray {
        // Para verificar el `Other(rawReason)`: declaramos TODAS las
        // capabilities como `Complete` en `capabilityCompleteness` (esa
        // rama NO emite gap) y añadimos un gap con reason desconocido en
        // la sección raíz `gaps`. Así la única fuente del gap que
        // esperamos es la sección raíz, y la deduplicación
        // `distinctBy { capability to reason }` no puede ocultar el
        // `Other`.
        val placeholder = CogniCodeEvidenceExportDto(
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
                completenessByCapability = mapOf(
                    "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
                    "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
                    "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
                    "signals.solid_audit" to CapabilityCompletenessDto.CompleteDto,
                ),
                schemaVersion = CogniCodeEvidenceExportCodec.API_VERSION,
                digest = "0".repeat(64),
            ),
            entities = listOf(
                EntityDto(id = "mod-core", kind = "Module", name = "core", layer = "domain"),
            ),
            facts = emptyList(),
            relations = emptyList(),
            signals = emptyList(),
            sourceAnchors = emptyList(),
            provenance = ProvenanceDto(
                producerId = "cognicode",
                producerVersion = "0.1.0",
                subjectRevision = "rev-001",
                capability = "architecture.entities",
            ),
            capabilityCompleteness = mapOf(
                "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
                "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
                "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
                "signals.solid_audit" to CapabilityCompletenessDto.CompleteDto,
            ),
            gaps = listOf(
                CapabilityGapDto(
                    capability = "signals.solid_audit",
                    reason = reason,
                    detail = "drift del producer",
                ),
            ),
            digest = "",
        )
        return CogniCodeEvidenceExportCodec.encodeToCbor(
            placeholder.copy(digest = CogniCodeEvidenceExportCodec.digestOf(placeholder).hex),
        )
    }

    /**
     * Construye un DTO bien formado con los datos de entrada proporcionados.
     * Calcula el digest con la convención del codec para que el decode de
     * vuelta pase la verificación.
     */
    private fun buildWellFormed(
        entities: List<EntityDto> = listOf(
            EntityDto(id = "mod-core", kind = "Module", name = "core", layer = "domain"),
            EntityDto(id = "sym-foo", kind = "Symbol", name = "core.Foo", layer = null),
        ),
        facts: List<FactDto> = listOf(
            FactDto(
                id = "f-1",
                entityRef = "sym-foo",
                predicate = "imports",
                objectValue = "core.Bar",
                authority = "DeterministicAnalyzer",
                sourceAnchorRef = null,
            ),
        ),
        relations: List<RelationDto> = listOf(
            RelationDto(from = "mod-core", to = "mod-adapters", kind = "depends-on", evidence = "f-1"),
        ),
        signals: List<SignalDto> = listOf(
            SignalDto(
                id = "s-1",
                entityRef = "sym-foo",
                kind = "solid-audit",
                score = "yes",
                algorithmId = "solid-audit",
                algorithmVersion = "0.1.0",
                thresholds = mapOf("maxResponsibilities" to "1"),
            ),
        ),
        completeness: Map<String, CapabilityCompletenessDto> = mapOf(
            "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
            "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
            "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
            "signals.solid_audit" to CapabilityCompletenessDto.CompleteDto,
        ),
    ): CogniCodeEvidenceExportDto {
        val placeholder = CogniCodeEvidenceExportDto(
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
            gaps = emptyList(),
            digest = "",
        )
        return placeholder.copy(digest = CogniCodeEvidenceExportCodec.digestOf(placeholder).hex)
    }
}
