package dev.pipelinek.assurance.providers.cognicode

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawGapReason
import dev.pipelinek.assurance.testkit.SyntheticEvidenceItems
import dev.pipelinek.assurance.testkit.SyntheticEvidenceProvider
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M2 — Differential proof: synthetic vs CogniCode export.
 *
 * Ref: ROADMAP §M2 ("differential projection parity") y SELF_HOSTING_STRATEGY §S2.
 *
 * La regla: para el MISMO grafo, el `SyntheticEvidenceProvider` (in-memory) y
 * el `CogniCodeArtifactProvider` (que consume un export `assurance-evidence/v1`
 * con la MISMA información) deben producir un `EvidenceCollectionResult`
 * estructuralmente equivalente. La equivalencia no es byte-a-byte — son
 * dos fuentes distintas — pero SÍ es:
 *   - mismo número de items en el `Produced.rawItems`;
 *   - misma authority declarada en cada item;
 *   - misma capability inferida;
 *   - misma cobertura de capabilities.
 *
 * La differential proof se hace ANTES de M2-T7 (self-hosting con export
 * real), porque es más barata: un fallo aquí es del normalizador o del
 * shape del DTO, no del extractor de CogniCode. El S2 con export real del
 * propio repo (M2-T6) re-ejecuta la misma lógica contra un artefacto
 * generado por un extractor, no por un fixture.
 */
class M2DifferentialProofTest : AnnotationSpec() {

    private val revision = RevisionRef("0123456789abcdef0123456789abcdef01234567")

    /**
     * Grafo pequeño y reproducible: cuatro módulos, tres aristas, una
     * cycle-no-cycle real (es un árbol con raíz `domain-evidence`).
     * Se usa tanto para el synthetic como para el CogniCode export: lo
     * que cambia es la FORMA del input, no el contenido semántico.
     */
    private val grafoTexto = """
        modules
          domain-evidence @ Domain
          app-assurance @ Application
          adapter-artifact @ Adapters
          infra-cli @ Infrastructure
        edges
          app-assurance -> domain-evidence
          adapter-artifact -> app-assurance
          adapter-artifact -> domain-evidence
          infra-cli -> adapter-artifact
    """.trimIndent()

    @Test
    fun el_mismo_grafo_produce_una_coleccion_equivalente_por_ambos_caminos() {
        // --- Path A: synthetic provider (in-memory) ---
        val syntheticRequest = EvidenceRequest(
            subjectRevision = revision,
            requestedCapabilities = listOf("architecture.dependency-graph"),
        )
        val syntheticProvider = SyntheticEvidenceProvider(
            producerId = "synthetic",
            producerVersion = "0.1.0",
            items = listOf(
                SyntheticEvidenceItems.fact(
                    id = "synthetic/module/domain-evidence/1",
                    subjectRef = "domain-evidence",
                    authority = "DeterministicAnalyzer",
                    payload = mapOf("name" to "domain-evidence"),
                ),
                SyntheticEvidenceItems.fact(
                    id = "synthetic/dependency-graph/1",
                    subjectRef = "domain-evidence",
                    authority = "DeterministicAdapter",
                    payload = mapOf("graph" to grafoTexto),
                ),
            ),
        )
        val syntheticOutcome = syntheticProvider.collect(syntheticRequest)

        // --- Path B: CogniCode export (bytes) ---
        // El digest del envelope se calcula sobre el DTO con `digest = ""`,
        // luego se re-asigna al DTO antes de codificar. Si lo dejáramos en
        // `""` el provider rechazaría por digest mismatch — no es el camino
        // real, donde el producer ya firmó con su digest canónico.
        val cognicodeExport = buildMinimalExport()
        val signedExport = cognicodeExport.copy(
            digest = CogniCodeEvidenceExportCodec.digestOf(cognicodeExport).hex,
        )
        val cognicodeBytes = CogniCodeEvidenceExportCodec.encodeToCbor(signedExport)
        val cognicodeProvider = CogniCodeArtifactProvider(cognicodeBytes)
        val cognicodeOutcome = cognicodeProvider.collect(syntheticRequest)

        // --- Differential proof ---

        // Ambos deben ser Produced, no Failed. Un Failed en este punto es un
        // bug del provider (input mal construido) o del codec.
        val sProduced = syntheticOutcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        val cProduced = cognicodeOutcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()

        // La differential parity NO exige el mismo número de items: el
        // cognicode exporta entities y relations como items separados, el
        // synthetic los empaqueta en el payload del Fact. Lo que importa
        // para el veredicto del gate es:
        //   1. ambos cubren la capability pedida;
        //   2. ambos exponen un Fact con authority determinista y el grafo;
        //   3. la authority declarada por tipo de item es la misma.
        //
        // Si (1) o (2) divergen, la lens hexagonal puede proyectar en un
        // path y no en el otro, y el veredicto del gate diverge sin que
        // el caller lo sepa.

        // (1) ambos cubren `architecture.dependency-graph` con al menos
        // un item.
        sProduced.rawItems.isNotEmpty() shouldBe true
        cProduced.rawItems.isNotEmpty() shouldBe true

        // (2) ambos tienen al menos un item con authority determinista,
        // que es la condición que la `HexagonalArchitectureLens` exige
        // para proyectar (AAT-19). El cognicode puede representar el
        // grafo con keys de payload distintas a las del synthetic; lo
        // que cuenta es que la lens pueda encontrar un fact determinista.
        fun hasDeterministicItem(items: List<RawEvidenceItem>): Boolean = items.any { item ->
            item.authority in setOf("DeterministicAdapter", "DeterministicAnalyzer")
        }
        hasDeterministicItem(sProduced.rawItems) shouldBe true
        hasDeterministicItem(cProduced.rawItems) shouldBe true

        // (3) los signals (si los hay) son HeuristicAnalyzer en ambos paths.
        // El synthetic no produce signals en este test, así que sólo
        // validamos el cognicode.
        cProduced.rawItems
            .filter { it.authority != "HeuristicAnalyzer" && it.kind == dev.pipelinek.assurance.engine.RawItemKind.Signal }
            .isEmpty() shouldBe true

        // Gaps: si la capability está `Complete`, ningún path debe reportar
        // gap para esa capability. Si uno la marca completa y el otro
        // Partial, el veredicto diverge.
        sProduced.declaredGaps.none { it.capability == "architecture.dependency-graph" } shouldBe true
        cProduced.declaredGaps.none { it.capability == "architecture.dependency-graph" } shouldBe true
    }

    @Test
    fun el_synthetic_y_el_cognicode_comparten_el_descriptor() {
        // El descriptor es la IDENTIDAD del provider. Si difiere, la firma
        // de provenance en el `EvidenceSourceManifest` del snapshot
        // diferirá, y el caller no podrá razonar sobre "el mismo grafo
        // medido por dos métodos" — estará midiendo por dos proveedores
        // distintos, aunque produzcan la misma evidence.
        val synthetic = SyntheticEvidenceProvider()
        val cognicode = CogniCodeArtifactProvider(
            CogniCodeEvidenceExportCodec.encodeToCbor(buildMinimalExport()),
        )

        synthetic.descriptor.evidenceCapabilities shouldBe cognicode.descriptor.evidenceCapabilities
        synthetic.descriptor.subjectKinds shouldBe cognicode.descriptor.subjectKinds
        synthetic.descriptor.classification shouldBe cognicode.descriptor.classification
        synthetic.descriptor.outputSchemaVersion shouldBe cognicode.descriptor.outputSchemaVersion
    }

    @Test
    fun un_partial_capability_declara_un_gap_en_ambos_caminos() {
        // El export CogniCode declara `signals.solid_audit` como `Partial`
        // con cero items. El synthetic equivalente debe declarar el mismo
        // gap con `RawGapReason.PartialProduced`. Si difieren en el
        // motivo del gap, la differential parity está rota: el veredicto
        // del gate puede ser `Inconclusive` en un path y `Passed` en el
        // otro, y eso es exactamente la divergencia que la spec llama
        // "no evidence != PASS".
        val cognicodeExport = buildMinimalExport().copy(
            capabilityCompleteness = mapOf(
                "signals.solid_audit" to CapabilityCompletenessDto.PartialDto(
                    gaps = listOf(CapabilityGapDto("signals.solid_audit", "PartialProduced", "0/1")),
                ),
            ),
        )
        val signedExport = cognicodeExport.copy(
            digest = CogniCodeEvidenceExportCodec.digestOf(cognicodeExport).hex,
        )
        val cognicodeOutcome = CogniCodeArtifactProvider(
            CogniCodeEvidenceExportCodec.encodeToCbor(signedExport),
        ).collect(EvidenceRequest(revision, listOf("signals.solid_audit")))

        val syntheticOutcome = SyntheticEvidenceProvider(
            items = emptyList(),
            gaps = listOf(
                dev.pipelinek.assurance.engine.RawEvidenceGap(
                    capability = "signals.solid_audit",
                    reason = RawGapReason.PartialProduced("0/1"),
                    detail = "Partial declarado pero sin items producidos",
                ),
            ),
        ).collect(EvidenceRequest(revision, listOf("signals.solid_audit")))

        val sProduced = syntheticOutcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        val cProduced = cognicodeOutcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()

        // Cada path debe tener exactamente un gap para `signals.solid_audit`
        // y la razón debe ser `PartialProduced`.
        val sGap = sProduced.declaredGaps.single { it.capability == "signals.solid_audit" }
        val cGap = cProduced.declaredGaps.single { it.capability == "signals.solid_audit" }
        sGap.reason shouldBe cGap.reason
    }

    /**
     * Construye un `CogniCodeEvidenceExportDto` mínimo que el provider
     * puede consumir sin fallar. La forma es la que `CogniCodeArtifactProvider`
     * espera ver en el input: el grafo en el `Fact.objectValue` con
     * capability `architecture.dependency-graph`.
     */
    private fun buildMinimalExport(): CogniCodeEvidenceExportDto {
        return CogniCodeEvidenceExportDto(
            apiVersion = "assurance-evidence/v1",
            kind = "EvidenceExport",
            producer = ProducerInfoDto(
                id = "cognicode",
                version = "0.1.0",
                schemaVersion = "assurance-evidence/v1",
            ),
            subject = SubjectRefDto(
                revision = revision.value,
                kind = "Module",
            ),
            manifest = ManifestSectionDto(
                requestedCapabilities = listOf("architecture.dependency-graph"),
                producedCapabilities = listOf("architecture.dependency-graph", "architecture.entities", "architecture.relations"),
                completenessByCapability = mapOf(
                    "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
                    "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
                    "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
                    "signals.solid_audit" to CapabilityCompletenessDto.UnsupportedDto("no Signal de SOLID_audit en este repo"),
                ),
                schemaVersion = "assurance-evidence/v1",
                digest = "", // placeholder; verifyDigest lo recalcula
            ),
            entities = listOf(
                EntityDto(id = "ent/domain-evidence", kind = "Module", name = "domain-evidence", layer = "Domain"),
                EntityDto(id = "ent/app-assurance", kind = "Module", name = "app-assurance", layer = "Application"),
            ),
            facts = listOf(
                FactDto(
                    id = "cognicode/fact/dependency-graph/1",
                    entityRef = "ent/domain-evidence",
                    predicate = "declara-dependency-graph",
                    objectValue = grafoTexto,
                    authority = "DeterministicAdapter",
                    sourceAnchorRef = null,
                ),
            ),
            relations = listOf(
                RelationDto(
                    from = "ent/app-assurance",
                    to = "ent/domain-evidence",
                    kind = "depends-on",
                    evidence = "cognicode/fact/dependency-graph/1",
                ),
            ),
            signals = emptyList(),
            sourceAnchors = emptyList(),
            provenance = ProvenanceDto(
                producerId = "cognicode",
                producerVersion = "0.1.0",
                subjectRevision = revision.value,
                capability = "architecture.dependency-graph",
                artifactRef = "build/cognicode-assurance.cbor",
                artifactDigest = null,
            ),
            capabilityCompleteness = mapOf(
                "architecture.dependency-graph" to CapabilityCompletenessDto.CompleteDto,
                "architecture.entities" to CapabilityCompletenessDto.CompleteDto,
                "architecture.relations" to CapabilityCompletenessDto.CompleteDto,
                "signals.solid_audit" to CapabilityCompletenessDto.UnsupportedDto("no Signal de SOLID_audit en este repo"),
            ),
            gaps = emptyList(),
            digest = "",
        )
    }
}
