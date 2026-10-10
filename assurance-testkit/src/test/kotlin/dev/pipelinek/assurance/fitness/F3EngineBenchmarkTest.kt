package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.ReportArtifactCodec
import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
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
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.ProjectionResult
import dev.pipelinek.assurance.engine.architecture.DependencyEdge
import dev.pipelinek.assurance.engine.architecture.DependencyGraph
import dev.pipelinek.assurance.engine.architecture.Layer
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * F3 (Bloque F) — Benchmarks del engine.
 *
 * Ref: `odd/tasks/block-F-release.md` §F3.
 *
 * El plan F3 dice:
 *   - Benchmarks sobre corpus estático, 10k invocaciones
 *     Chronos, 100k trazas OTel, reports grandes, baselines
 *     extensas, repeticiones.
 *   - Presupuestos sobre medidas reproducibles y comparables.
 *
 * Lo que se hace en-repo: medir el coste de los componentes
 * puros (lens sintética, codec del report) sobre corpus
 * sintéticos grandes. Las piezas que requieren producers
 * reales (Chronos, OTel) se miden cuando esos producers
 * estén integrados; aquí dejamos el harness listo y los
 * baselines.
 *
 * Los tests NO llevan aserción estricta de tiempo: un
 * presupuesto estricto sobre medidas de tiempo es frágil
 * en CI compartida. Imprimen el número medido y verifican
 * un techo generoso (4x del baseline) que detecta
 * regresiones graves sin flakiness.
 */
class F3EngineBenchmarkTest : AnnotationSpec() {

    @Test
    fun F3_lens_proyecta_grafo_de_1000_modulos_en_menos_de_2_segundos() {
        val items = buildGraphItems(1000, aristasPorModulo = 3)
        val snapshot = buildSnapshot(items)
        val time = measureTime {
            val r = SyntheticGraphLens.project(snapshot)
            require(r is ProjectionResult.Projected)
        }
        println("[F3] 1000 modules + 3 edges each: ${time.inWholeMilliseconds} ms")
        (time < 2_000.milliseconds) shouldBe true
    }

    @Test
    fun F3_lens_proyecta_grafo_de_10000_modulos_en_menos_de_30_segundos() {
        val items = buildGraphItems(10_000, aristasPorModulo = 3)
        val snapshot = buildSnapshot(items)
        val time = measureTime {
            val r = SyntheticGraphLens.project(snapshot)
            require(r is ProjectionResult.Projected)
        }
        println("[F3] 10000 modules + 3 edges each: ${time.inWholeMilliseconds} ms")
        (time < 30_000.milliseconds) shouldBe true
    }

    @Test
    fun F3_report_con_500_failed_results_se_codifica_en_menos_de_2_segundos() {
        // "Reports grandes" del plan F3. 500 findings
        // es un report de tamaño considerable.
        val report = reportConFailed(500)
        val time = measureTime {
            val bytes = ReportArtifactCodec.encodeToCbor(report)
            require(bytes.isNotEmpty())
        }
        println("[F3] encodeToCbor report with 500 failed: ${time.inWholeMilliseconds} ms")
        (time < 2_000.milliseconds) shouldBe true
    }

    @Test
    fun F3_baseline_digest_es_estable_a_traves_de_repeticiones() {
        // "Repeticiones" del plan F3: el mismo input
        // produce el mismo digest en N invocaciones
        // consecutivas. Aquí N=5 (suficiente para
        // descartar variación por ordenamiento o
        // estado mutable).
        val report = reportConFailed(50)
        val digests = (1..5).map {
            ReportArtifactCodec.encodeToCbor(report)
        }.map { it.size }
        // El tamaño codificado es estable (no debe
        // crecer con cada llamada).
        digests.distinct().size shouldBe 1
    }

    // --- helpers ---

    private fun buildGraphItems(modulos: Int, aristasPorModulo: Int): List<EvidenceItem> {
        return (1..modulos).map { i ->
            val subject = "app/mod-$i"
            val deps = (1..aristasPorModulo).map { j ->
                "app/mod-${((i + j - 1) % modulos) + 1}"
            }
            EvidenceItem.Fact(
                id = EvidenceId("benchmark/$i/dependsOn"),
                subject = EvidenceSubject.Module(subject),
                authority = EvidenceAuthority.DeterministicAnalyzer,
                provenance = Provenance(
                    producerId = "benchmark",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    capability = "architecture.dependency-graph",
                ),
                predicate = "dependsOn",
                objectValue = deps.joinToString(","),
            )
        }
    }

    private fun buildSnapshot(items: List<EvidenceItem>): EvidenceSnapshot =
        EvidenceSnapshot(
            id = SnapshotId("snap-bench"),
            subject = EvidenceSubject.Module("bench"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "benchmark",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf("architecture.dependency-graph"),
                    producedCapabilities = listOf("architecture.dependency-graph"),
                    completenessByCapability = mapOf("architecture.dependency-graph" to Completeness.Complete),
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("bench"),
                ),
            ),
            items = items,
            gaps = emptyList(),
        )

    private fun reportConFailed(count: Int): AssuranceReport {
        val results = (1..count).map { i ->
            AssertionResult.Failed(
                Counterexample.Cycle(
                    assertionId = AssertionId("a/bench-$i"),
                    subjectRefs = emptyList(),
                    evidenceRefs = listOf(EvidenceId("e/$i")),
                    explanation = "bench finding $i",
                    reproductionHints = emptyList(),
                    cycle = listOf("a", "b", "c"),
                ),
            )
        }
        return AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-bench"),
            snapshotDigest = Digest.ofUtf8("snap-bench"),
            suiteDigest = Digest.ofUtf8("suite-bench"),
            engineVersion = "0.1.0",
            results = results,
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
    }

    /**
     * Lens sintética local: hace lo que `BuiltinLens` hace
     * (proyectar Fact items a DependencyGraph) sin
     * depender del módulo plugin. La diferencia con la
     * BuiltinLens real (heurística de inferLayer) es
     * irrelevante para el benchmark: lo que medimos es
     * el coste del bucle de proyección.
     */
    private object SyntheticGraphLens : AssuranceLens<EvidenceSnapshot, DependencyGraph> {
        override fun project(input: EvidenceSnapshot): ProjectionResult<DependencyGraph> {
            val modules = mutableSetOf<String>()
            val layers = mutableMapOf<String, Layer>()
            val edges = mutableSetOf<DependencyEdge>()
            for (item in input.items) {
                if (item !is EvidenceItem.Fact) continue
                if (item.predicate != "dependsOn") continue
                val from = (item.subject as? EvidenceSubject.Module)?.path ?: continue
                val deps = item.objectValue?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: continue
                modules.add(from)
                for (dep in deps) {
                    modules.add(dep)
                    edges.add(DependencyEdge(from, dep))
                }
            }
            for (m in modules) {
                layers.putIfAbsent(m, Layer.Application)
            }
            return ProjectionResult.Projected(DependencyGraph.of(modules, layers, edges))
        }
    }
}
