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
import dev.pipelinek.assurance.engine.ProjectionResult
import dev.pipelinek.assurance.engine.architecture.HexagonalArchitectureLens
import dev.pipelinek.assurance.engine.architecture.SolidLens
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M10 — Tests de SolidLens.
 *
 * Verifica la separación epistémica de los principios SOLID:
 *   - DIP: violations deterministas (Lista clara).
 *   - ISP: signals heurísticas (módulo con muchos dependents).
 *   - SRP: placeholder V1 (vacío).
 *   - LSP: no implementado (requiere Chronos M7).
 */
class M10SolidLensTest : AnnotationSpec() {

    @Test
    fun dip_violations_son_deterministas() {
        // Un grafo con domain dependiendo de adapter rompe DIP.
        val snapshot = snapshotCon(
            modules = listOf("domain @ Domain", "adapter @ Adapters"),
            edges = listOf("domain -> adapter"),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.dipViolationCount shouldBe 1
        solid.dipViolations.single().from shouldBe "domain"
        solid.dipViolations.single().to shouldBe "adapter"
    }

    @Test
    fun dip_violations_domain_a_infrastructure_tambien_se_detecta() {
        // M-10-02 redundancia: la rama DIP debe detectar la
        // violación con cualquier capa de destino más externa,
        // no solo Adapters. Un mutante que limite la condición
        // a `toLayer == Adapters` dejaría pasar domain->infra.
        val snapshot = snapshotCon(
            modules = listOf("domain @ Domain", "infra @ Infrastructure"),
            edges = listOf("domain -> infra"),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.dipViolationCount shouldBe 1
        solid.dipViolations.single().from shouldBe "domain"
        solid.dipViolations.single().to shouldBe "infra"
    }

    @Test
    fun un_grafo_bien_ordenado_no_tiene_dip_violations() {
        // adapter -> domain, app -> adapter. Domain es la raíz, todas
        // las dependencias van hacia capas más internas.
        val snapshot = snapshotCon(
            modules = listOf(
                "domain @ Domain",
                "app @ Application",
                "adapter @ Adapters",
            ),
            edges = listOf(
                "app -> domain",
                "adapter -> app",
                "adapter -> domain",
            ),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.dipViolationCount shouldBe 0
    }

    @Test
    fun isp_signal_se_detecta_cuando_hay_outlier() {
        // Construimos un grafo con un módulo "gordo" que tiene muchos
        // dependents y los demás tienen 1. La media + 2σ debe ser
        // menor que el conteo de `gordo`, lo que dispara un signal.
        val mods = listOf("gordo @ Adapters", "a @ Adapters", "b @ Adapters", "c @ Adapters", "d @ Adapters", "e @ Adapters", "f @ Adapters", "g @ Adapters", "h @ Adapters", "i @ Adapters")
        val deps = listOf(
            "a -> gordo", "b -> gordo", "c -> gordo", "d -> gordo",
            "e -> gordo", "f -> gordo", "g -> gordo", "h -> gordo",
            "i -> gordo",
            // Cada módulo "a..i" depende de core (1 dependent cada uno).
            "a -> core", "b -> core", "c -> core", "d -> core",
            "e -> core", "f -> core", "g -> core", "h -> core",
            "i -> core",
        )
        val snapshot = snapshotCon(
            modules = mods + listOf("core @ Domain"),
            edges = deps,
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        // No es necesario que el módulo exacto sea `gordo`; basta con
        // que el conteo de signals sea > 0 cuando hay outlier claro.
        solid.ispSignals.isNotEmpty() shouldBe true
    }

    @Test
    fun srp_signals_es_placeholder_vacio() {
        // SRP y OCP son heurísticas; V1 no las calcula. Si alguien
        // añade algoritmos, este test se actualiza con un mutante
        // que documente el cambio.
        val snapshot = snapshotCon(
            modules = listOf("a @ Domain", "b @ Adapters"),
            edges = listOf("b -> a"),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.srpSignals shouldBe emptyList()
    }

    @Test
    fun sin_grafo_la_lens_falla_con_missing_capability() {
        val snapshot = snapshotSinGrafo()
        val projection = SolidLens.project(snapshot)
        projection.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
    }

    // --- helpers ---

    private fun snapshotCon(
        modules: List<String>,
        edges: List<String>,
    ): EvidenceSnapshot {
        val grafoTexto = buildString {
            appendLine("modules")
            modules.forEach { appendLine("  $it") }
            appendLine("edges")
            edges.forEach { appendLine("  $it") }
        }
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-solid"),
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

    private fun snapshotSinGrafo(): EvidenceSnapshot {
        return EvidenceSnapshot(
            id = SnapshotId("s/m10-empty"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "synthetic",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                    // `producedCapabilities` debe contener cualquier
                    // capability declarada en `completenessByCapability`.
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
}
