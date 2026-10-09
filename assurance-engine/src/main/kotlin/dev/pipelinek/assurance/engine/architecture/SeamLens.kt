package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

/**
 * M10 — `SeamLens`: lens que detecta seams en la arquitectura.
 *
 * Ref autoridad: `03-specifications/LENSES.md` §SeamLens.
 *
 * **Qué es un seam:** un módulo que es punto de entrada/salida de un
 * subsistema, donde el cambio es más probable y donde los tests
 * son más valiosos. Ejemplos:
 *   - adapters que traducen entre formatos;
 *   - el módulo CLI (interface humano);
 *   - el módulo plugin (interface SDK).
 *
 * **Cómo se detecta en V1:** heurística simple. Un módulo se
 * considera seam si:
 *   - es de la capa más externa (Adapters o Infrastructure), Y
 *   - tiene al menos un módulo dependiente en capas más internas
 *     (Application o Domain).
 *
 * V1 no hace análisis de "testability" ni "churn"; eso requiere
 * observabilidad runtime (M7+) o un histórico de cambios. La
 * versión V1 es **Signal**, no `Fact` determinista: la noción de
 * seam es heurística.
 *
 * **AAT-19:** la lens admite autoridad heurística para señales
 * y authority determinista para facts. Aquí la lens produce
 * `SeamSignal`s que el caller convierte en `EvidenceItem.Signal`
 * con `HeuristicAnalyzer`.
 */
object SeamLens : AssuranceLens<EvidenceSnapshot, SeamProjection> {

    override fun project(input: EvidenceSnapshot): ProjectionResult<SeamProjection> {
        val graphProjection = HexagonalArchitectureLens.project(input)
        if (graphProjection is ProjectionResult.ProjectionFailed) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.MissingCapability("architecture.dependency-graph"),
                gaps = graphProjection.gaps,
            )
        }
        val graph = (graphProjection as ProjectionResult.Projected).value

        // V1: seam = módulo de Adapters/Infrastructure con un
        // dependiente en Application o Domain.
        val externalLayers = setOf(Layer.Adapters, Layer.Infrastructure)
        val internalLayers = setOf(Layer.Domain, Layer.Application)
        val seams = mutableListOf<SeamSignal>()
        for (module in graph.modules) {
            val layer = graph.layers.getValue(module)
            if (layer !in externalLayers) continue
            val hasInternalDependent = graph.edges.any { edge ->
                edge.to == module && graph.layers.getValue(edge.from) in internalLayers
            }
            if (hasInternalDependent) {
                val dependents = graph.edges.filter { it.to == module }.map { it.from }
                seams += SeamSignal(
                    module = module,
                    layer = layer.name,
                    dependents = dependents,
                    signal = "seam: ${dependents.size} internal dependent(s)",
                )
            }
        }
        return ProjectionResult.Projected(
            SeamProjection(
                graph = graph,
                seams = seams,
            ),
        )
    }
}

data class SeamProjection(
    val graph: DependencyGraph,
    val seams: List<SeamSignal>,
) {
    val seamCount: Int get() = seams.size
}

/**
 * Señal heurística de un seam. El caller la convierte en
 * `EvidenceItem.Signal` con authority `HeuristicAnalyzer`.
 */
data class SeamSignal(
    val module: String,
    val layer: String,
    val dependents: List<String>,
    val signal: String,
)
