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
import dev.pipelinek.assurance.engine.architecture.ConnascenceFinding
import dev.pipelinek.assurance.engine.architecture.ConnascenceKind
import dev.pipelinek.assurance.engine.architecture.ConsistencyLens
import dev.pipelinek.assurance.engine.architecture.HexagonalArchitectureLens
import dev.pipelinek.assurance.engine.architecture.SeamLens
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M10 — Tests redundantes para los 4 mutantes nuevos del catálogo
 * (M-10-01..M-10-04). Cada test ataca una rama distinta del
 * mutante para evitar la redundancia de un solo test.
 */
class M10LensesTests : AnnotationSpec() {

    @Test
    fun M_10_01_connascence_finding_rechaza_strength_6() {
        // M-10-01: el init de ConnascenceFinding debe rechazar
        // strength fuera del rango 0..5. Un mutante que extienda
        // el rango a 0..6 deja pasar strength=6.
        shouldThrowAny {
            ConnascenceFinding(
                from = "a",
                to = "b",
                subject = "subj",
                kind = ConnascenceKind.Name,
                strength = 6,
            )
        }
    }

    @Test
    fun M_10_01_connascence_finding_acepta_strength_5() {
        // M-10-01 redundancia: el rango bueno acepta 5 sin
        // lanzar. Un mutante que cambie a 0..4 lanzaría aquí.
        ConnascenceFinding(
            from = "a",
            to = "b",
            subject = "subj",
            kind = ConnascenceKind.Name,
            strength = 5,
        )
    }

    @Test
    fun M_10_01_connascence_finding_strength_6_incluye_el_valor_en_el_mensaje() {
        // M-10-01 redundancia: el init reporta el valor concreto
        // que viola el rango. Un mutante que quite la rama
        // (cambie a 0..6) NO lanza con strength=6, así que el
        // assertAny nunca se ejecuta. Pero si lo cambiara a 0..4,
        // el assertAny sí lanzaría; lo que detecta la
        // redundancia es la combinación: el mensaje de error
        // DEBE incluir el valor que violó el rango, y ese valor
        // debe ser exactamente 6.
        val ex = io.kotest.assertions.throwables.shouldThrowAny {
            ConnascenceFinding(
                from = "a",
                to = "b",
                subject = "subj",
                kind = ConnascenceKind.Name,
                strength = 6,
            )
        }
        (ex.message?.contains("6") ?: false) shouldBe true
    }

    @Test
    fun M_10_03_consistency_lens_detecta_contradiccion() {
        // M-10-03: ConsistencyLens compara declared vs observed.
        // Si la lens tiene un filter false, no detecta
        // contradicciones. Construimos un grafo declarado y
        // edges runtime observados con un edge extra: ese edge
        // debe aparecer como contradicción.
        val snapshot = snapshotConDeclaredYObservedDiferentes()
        val projection = ConsistencyLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val consistency = projected.value as dev.pipelinek.assurance.engine.architecture.ConsistencyProjection
        (consistency.contradictions.isNotEmpty()) shouldBe true
    }

    @Test
    fun M_10_03_consistency_lens_reporta_el_edge_observado_que_falta() {
        // M-10-03 redundancia: la lista de contradicciones debe
        // incluir el edge observado que no está en el grafo
        // declarado. Un mutante que devuelva lista vacía (filter
        // false) deja esta lista vacía. La redundancia verifica
        // el CONTENIDO, no solo el tamaño.
        val snapshot = snapshotConDeclaredYObservedDiferentes()
        val projection = ConsistencyLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val consistency = projected.value as dev.pipelinek.assurance.engine.architecture.ConsistencyProjection
        val hasExtraEdge = consistency.contradictions.any { c ->
            c.toString().contains("adapters") && c.toString().contains("infra")
        }
        (hasExtraEdge) shouldBe true
    }

    @Test
    fun M_10_04_seam_lens_no_clasifica_modulos_internos_como_seam() {
        // M-10-04: un módulo de capa Domain/Application NO es
        // seam. Un mutante que quite la condición `layer !in
        // externalLayers` clasificaría TODOS los módulos como
        // seam. Construimos un grafo con un módulo Domain
        // dependiente; ese módulo NO debe aparecer como seam.
        val snapshot = snapshotSoloDomainYApplication()
        val projection = SeamLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val seam = projected.value as dev.pipelinek.assurance.engine.architecture.SeamProjection
        // Sin módulos Adapters/Infrastructure, no hay seams.
        seam.seamCount shouldBe 0
    }

    @Test
    fun M_10_04_seam_lens_solo_reporta_adapters_e_infra_como_seam() {
        // M-10-04 redundancia: con un grafo donde Domain tiene
        // un internal dependent (Application), Domain NO es seam
        // (es interno). Un mutante que quite el filtro de capa
        // externa clasificaría Domain como seam porque su
        // dependent (Application) es interno. La diferencia
        // observable es 0 vs 1 seams.
        val grafo = """
            modules
              app @ Application
              domain @ Domain
            edges
              app -> domain
        """.trimIndent()
        val snapshot = EvidenceSnapshot(
            id = SnapshotId("s/m10-seam-redundancia"),
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
                    id = EvidenceId("synthetic/dependency-graph/2"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.DeterministicAdapter,
                    provenance = Provenance(
                        producerId = "synthetic",
                        producerVersion = "0.1.0",
                        subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                        capability = HexagonalArchitectureLens.CAPABILITY,
                    ),
                    predicate = HexagonalArchitectureLens.PREDICATE,
                    objectValue = grafo,
                ),
            ),
            gaps = emptyList(),
            correlations = emptyList(),
        )
        val projection = SeamLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<dev.pipelinek.assurance.engine.ProjectionResult.Projected<*>>()
        val seam = projected.value as dev.pipelinek.assurance.engine.architecture.SeamProjection
        // Domain es interno; con el filtro original, seamCount == 0.
        // Sin el filtro (mutante), Domain se clasificaría como seam
        // y seamCount sería 1.
        seam.seamCount shouldBe 0
    }

    // --- helpers ---

    private fun snapshotConDeclaredYObservedDiferentes(): EvidenceSnapshot {
        val declared = """
            modules
              app @ Application
              adapters @ Adapters
            edges
              adapters -> app
        """.trimIndent()
        val observed = """
            modules
              app @ Application
              adapters @ Adapters
              infra @ Infrastructure
            edges
              adapters -> app
              adapters -> infra
        """.trimIndent()
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-consistency"),
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
                    id = EvidenceId("synthetic/declared-graph/1"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.DeterministicAdapter,
                    provenance = Provenance(
                        producerId = "synthetic",
                        producerVersion = "0.1.0",
                        subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                        capability = HexagonalArchitectureLens.CAPABILITY,
                    ),
                    predicate = HexagonalArchitectureLens.PREDICATE,
                    objectValue = declared,
                ),
                EvidenceItem.Observation(
                    id = EvidenceId("synthetic/observed-edge/1"),
                    subject = EvidenceSubject.RuntimeSpan("span/1"),
                    authority = EvidenceAuthority.RuntimeObserver,
                    provenance = Provenance(
                        producerId = "synthetic",
                        producerVersion = "0.1.0",
                        subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                        capability = dev.pipelinek.assurance.engine.architecture.ObservedArchitectureLens.CAPABILITY,
                    ),
                    observation = "edge=adapters->infra",
                ),
            ),
            gaps = emptyList(),
            correlations = emptyList(),
        )
    }

    private fun snapshotSoloDomainYApplication(): EvidenceSnapshot {
        val grafo = """
            modules
              domain @ Domain
              app @ Application
            edges
              app -> domain
        """.trimIndent()
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-seam"),
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
                    objectValue = grafo,
                ),
            ),
            gaps = emptyList(),
            correlations = emptyList(),
        )
    }
}
