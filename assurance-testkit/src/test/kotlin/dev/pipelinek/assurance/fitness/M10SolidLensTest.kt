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
        // SRP/OCP V1: heurística basada en desviación estándar. En
        // grafos pequeños (2 módulos, 1 arista) no produce
        // outliers: el test verifica la FORMA (lista) y que la
        // salida es estable. Un grafo "fat" (un módulo con fan-out
        // anómalo) cazaría el mutante M-SOLID-SRP-EMPTY.
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
    fun M_SOLID_SRP_EMPTY_fat_module_produce_outlier() {
        // M-SOLID-SRP-EMPTY: un mutante que neutralice
        // findSrpSignals debe ser cazado por este test. Un módulo
        // con fan-out mucho mayor que la media dispara la señal
        // SRP. Diseño: `fat` apunta a 5 módulos; los demás no
        // apuntan a nadie. Outgoing: fat=5, todos los demás=0 →
        // fat es outlier.
        val snapshot = snapshotCon(
            modules = listOf(
                "fat @ Domain",
                "a @ Domain",
                "b @ Domain",
                "c @ Domain",
                "d @ Domain",
                "e @ Domain",
            ),
            edges = listOf(
                "fat -> a",
                "fat -> b",
                "fat -> c",
                "fat -> d",
                "fat -> e",
            ),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.srpSignals.any { it.module == "fat" } shouldBe true
    }

    @Test
    fun M_SOLID_SRP_EMPTY_fat_module_distinto_produce_outlier() {
        // M-SOLID-SRP-EMPTY redundancia: usamos un fixture con
        // un módulo outlier diferente para que el mutante
        // (devolver emptyList) falle por DOS paths: la lista
        // contiene "fat" en el primer test Y contiene "greedy"
        // en este.
        val snapshot = snapshotCon(
            modules = listOf(
                "greedy @ Domain",
                "p @ Domain",
                "q @ Domain",
                "r @ Domain",
                "s @ Domain",
                "t @ Domain",
            ),
            edges = listOf(
                "greedy -> p",
                "greedy -> q",
                "greedy -> r",
                "greedy -> s",
                "greedy -> t",
            ),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.srpSignals.any { it.module == "greedy" } shouldBe true
    }

    @Test
    fun M_SOLID_OCP_EMPTY_adapters_con_muchos_dependents_produce_outlier() {
        // M-SOLID-OCP-EMPTY: un mutante que neutralice
        // findOcpSignals debe ser cazado por este test. Una capa
        // Adapters con muchos dependents desde otras capas
        // externas dispara la señal OCP.
        val snapshot = snapshotCon(
            modules = listOf(
                "stable-core @ Domain",
                "adapter-foo @ Adapters",
                "infra1 @ Infrastructure",
                "infra2 @ Infrastructure",
                "infra3 @ Infrastructure",
                "infra4 @ Infrastructure",
            ),
            edges = listOf(
                "infra1 -> adapter-foo",
                "infra2 -> adapter-foo",
                "infra3 -> adapter-foo",
                "infra4 -> adapter-foo",
            ),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.ocpSignals.any { it.module == "adapter-foo" } shouldBe true
    }

    @Test
    fun M_SOLID_OCP_EMPTY_outlier_distinto_produce_outlier() {
        // M-SOLID-OCP-EMPTY redundancia: otro módulo outlier
        // con la misma forma de grafo.
        val snapshot = snapshotCon(
            modules = listOf(
                "stable-core @ Domain",
                "adapter-bar @ Adapters",
                "i1 @ Infrastructure",
                "i2 @ Infrastructure",
                "i3 @ Infrastructure",
                "i4 @ Infrastructure",
            ),
            edges = listOf(
                "i1 -> adapter-bar",
                "i2 -> adapter-bar",
                "i3 -> adapter-bar",
                "i4 -> adapter-bar",
            ),
        )
        val projection = SolidLens.project(snapshot)
        val projected = projection.shouldBeInstanceOf<ProjectionResult.Projected<*>>()
        val solid = projected.value as dev.pipelinek.assurance.engine.architecture.SolidProjection
        solid.ocpSignals.any { it.module == "adapter-bar" } shouldBe true
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
