package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.architecture.ConnascenceKind
import dev.pipelinek.assurance.engine.architecture.ConnascenceLens
import dev.pipelinek.assurance.engine.architecture.HexagonalArchitectureLens
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M10 — Tests del `ConnascenceLens`.
 *
 * En V1 la lens es un esqueleto: la forma del output
 * (`ConnascenceProjection` con `findings` y `countByKind`) está
 * fijada, y los algoritmos de detección son placeholders. Estos
 * tests verifican:
 *
 *   1. La lens **proyecta** cuando hay un grafo en el snapshot
 *      (no falla con `MissingCapability`).
 *   2. La lens **falla** con `MissingCapability` cuando el snapshot
 *      no tiene evidence de la capability.
 *   3. La forma `countByKind` es estable (un Map<Kind, Int>),
 *      porque las assertions la consumen.
 *   4. La lens reusa la `HexagonalArchitectureLens` (no
 *      re-lee el snapshot): un snapshot válido para ambas lo es
 *      para esta.
 */
class M10ConnascenceLensTest : AnnotationSpec() {

    @Test
    fun la_lens_proyecta_cuando_hay_grafo() {
        val snapshot = snapshotConGrafo()
        val projection = ConnascenceLens.project(snapshot)
        projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
    }

    @Test
    fun la_lens_falla_con_missing_capability_sin_grafo() {
        val snapshot = snapshotVacio()
        val projection = ConnascenceLens.project(snapshot)
        projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.ProjectionFailed>()
    }

    @Test
    fun la_forma_count_by_kind_es_un_map_estable() {
        // La forma del output es parte del contrato. Una assertion
        // que itera `projection.countByKind[ConnascenceKind.Name]`
        // depende de que el Map exista y use las keys del enum.
        val snapshot = snapshotConGrafo()
        val projection = ConnascenceLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val connascence = projected.value as dev.pipelinek.assurance.engine.architecture.ConnascenceProjection
        // El grafo fixture tiene DOS módulos en la misma capa
        // (Domain) que no comparten dependencias; el test verifica
        // que la forma sea la correcta (Map<Kind, Int>) y que las
        // keys del enum son estables.
        (connascence.countByKind is Map<*, *>) shouldBe true
        ConnascenceKind.entries shouldBe setOf(
            ConnascenceKind.Name,
            ConnascenceKind.Position,
            ConnascenceKind.Meaning,
        )
    }

    @Test
    fun M_10_CONTENT_connascence_of_name_detecta_pares_en_misma_capa() {
        // M-10-CONTENT: un mutante que reemplace
        // `findConnascenceOfName` por `return emptyList()` debe
        // ser cazado por este test. El grafo fixture tiene dos
        // módulos en la capa Application que dependen ambos de
        // `domain-evidence` (Connascence of Name: comparten el
        // nombre de su dependencia aguas-abajo).
        val snapshot = snapshotConGrafoNoTrivial()
        val projection = ConnascenceLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val connascence = projected.value as dev.pipelinek.assurance.engine.architecture.ConnascenceProjection
        val nameFindings = connascence.findings.filter { it.kind == ConnascenceKind.Name }
        (nameFindings.isNotEmpty()) shouldBe true
    }

    @Test
    fun M_10_CONTENT_connascence_of_position_detecta_saltos_de_2_capas() {
        // M-10-CONTENT redundancia: un mutante que neutralice la
        // heurística de position debe ser cazado por este test.
        // El grafo fixture tiene un salto Domain → Infrastructure
        // (rank 0 → 3), que produce strength 3.
        val snapshot = snapshotConGrafoNoTrivial()
        val projection = ConnascenceLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val connascence = projected.value as dev.pipelinek.assurance.engine.architecture.ConnascenceProjection
        val positionFindings = connascence.findings.filter { it.kind == ConnascenceKind.Position }
        (positionFindings.isNotEmpty()) shouldBe true
    }

    @Test
    fun la_lens_reusa_hexagonal_lens_para_el_grafo() {
        // Si la hexagonal falla, la connascence también. Esta es la
        // garantía de la indirección: no hay un segundo parser.
        val snapshot = snapshotConSoloHeuristica()
        val hex = HexagonalArchitectureLens.project(snapshot)
        val conn = ConnascenceLens.project(snapshot)
        // Ambos deben ser ProjectionFailed.
        hex.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.ProjectionFailed>()
        conn.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.ProjectionFailed>()
    }

    // --- helpers ---

    private fun snapshotConGrafo(): EvidenceSnapshot {
        val grafoTexto = """
            modules
              domain-evidence @ Domain
              app-assurance @ Application
            edges
              app-assurance -> domain-evidence
        """.trimIndent()
        return EvidenceSnapshot(
            id = SnapshotId("s/m10"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "synthetic",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    producedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    completenessByCapability = mapOf(HexagonalArchitectureLens.CAPABILITY to Completeness.Complete),
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("synthetic"),
                ),
            ),
            items = listOf(
                EvidenceItem.Fact(
                    id = EvidenceId("synthetic/dependency-graph/1"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.DeterministicAdapter,
                    provenance = Provenance(
                        producerId = "synthetic",
                        producerVersion = "0.1.0",
                        subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                        capability = HexagonalArchitectureLens.CAPABILITY,
                    ),
                    predicate = HexagonalArchitectureLens.PREDICATE,
                    objectValue = grafoTexto,
                ),
            ),
            gaps = emptyList(),
        )
    }

    private fun snapshotConGrafoNoTrivial(): EvidenceSnapshot {
        // Grafo con:
        //  - 2 módulos en Application que dependen de domain-evidence
        //    (CoN entre ellos: comparten la misma dependencia aguas-abajo).
        //  - 1 arista Domain -> Infrastructure (CoP, salto de 3 rangos).
        //  - 2 módulos en capas distintas con prefijo "core"
        //    (CoM, connascence of meaning por nombre).
        val grafoTexto = """
            modules
              domain-evidence @ Domain
              core-domain @ Domain
              app-assurance @ Application
              core-app @ Application
              infra-foo @ Infrastructure
            edges
              app-assurance -> domain-evidence
              core-app -> domain-evidence
              domain-evidence -> infra-foo
        """.trimIndent()
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-nt"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "synthetic",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    producedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    completenessByCapability = mapOf(HexagonalArchitectureLens.CAPABILITY to Completeness.Complete),
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("synthetic-nt"),
                ),
            ),
            items = listOf(
                EvidenceItem.Fact(
                    id = EvidenceId("synthetic/dependency-graph/nt"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.DeterministicAdapter,
                    provenance = Provenance(
                        producerId = "synthetic",
                        producerVersion = "0.1.0",
                        subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                        capability = HexagonalArchitectureLens.CAPABILITY,
                    ),
                    predicate = HexagonalArchitectureLens.PREDICATE,
                    objectValue = grafoTexto,
                ),
            ),
            gaps = emptyList(),
        )
    }

    private fun snapshotVacio(): EvidenceSnapshot {
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-empty"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "synthetic",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    producedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    completenessByCapability = mapOf(HexagonalArchitectureLens.CAPABILITY to Completeness.Unsupported("no capability")),
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("synthetic-empty"),
                ),
            ),
            items = emptyList(),
            gaps = emptyList(),
        )
    }

    private fun snapshotConSoloHeuristica(): EvidenceSnapshot {
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-heur"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "synthetic",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    producedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    completenessByCapability = mapOf(HexagonalArchitectureLens.CAPABILITY to Completeness.Complete),
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("synthetic-heur"),
                ),
            ),
            items = listOf(
                EvidenceItem.Signal(
                    id = EvidenceId("synthetic/signal/1"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.HeuristicAnalyzer,
                    provenance = Provenance(
                        producerId = "synthetic",
                        producerVersion = "0.1.0",
                        subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                        capability = HexagonalArchitectureLens.CAPABILITY,
                    ),
                    signalKind = "scc-detector",
                    score = "0.5",
                    algorithmId = "tarjan",
                    algorithmVersion = "1.0",
                ),
            ),
            gaps = emptyList(),
        )
    }
}
