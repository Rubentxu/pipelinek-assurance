package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

/**
 * M10 — `ConsistencyLens`: detecta contradicciones entre arquitectura
 * declarada, estática y observada.
 *
 * Ref autoridad: `03-specifications/LENSES.md` §ConsistencyLens.
 *
 * **Tres fuentes de verdad sobre la arquitectura:**
 *   - `DeclaredArchitecture`: lo que el humano o el build script
 *     dice. Hoy vive en `08-testing/self-model.graph` (declarado
 *     a mano, M1).
 *   - `StructuralArchitecture`: lo que el código realmente tiene
 *     (edges entre módulos). Lo lee la `HexagonalArchitectureLens`
 *     desde un producer como CogniCode.
 *   - `ObservedArchitecture`: lo que se observa en runtime
 *     (Chronos). Lo lee la `ObservedArchitectureLens`.
 *
 * Las tres deben coincidir. Si difieren, hay una mentira en
 * alguna parte: o el código dice una cosa y la documentación otra,
 * o la documentación dice una cosa y la ejecución otra, o el código
 * dice una cosa y la ejecución otra.
 *
 * **Lo que la lens produce V1:** una `ConsistencyProjection` con la
 * lista de contradicciones. Cada contradicción lleva los dos lados
 * (declared vs structural, structural vs observed) y el módulo
 * afectado. Las assertions consumen la lista.
 *
 * **Lo que la lens NO hace V1:** no dice qué fuente es la "correcta".
 * Una contradicción entre `Declared` y `Structural` puede ser:
 *   - documentación desactualizada → actualizar la declaración.
 *   - código que importa algo no declarado → declarar el módulo o
 *     romper la dependencia.
 * El caller decide.
 */
object ConsistencyLens : AssuranceLens<EvidenceSnapshot, ConsistencyProjection> {

    override fun project(input: EvidenceSnapshot): ProjectionResult<ConsistencyProjection> {
        // DeclaredArchitecture: el self-model.graph o el equivalente
        // que el producer emita. Hoy, declarado a mano, así que
        // simplemente leemos los Fact de la capability
        // `architecture.dependency-graph` (de donde sale también el
        // Structural) y lo usamos como proxy. La distinción
        // Declared/Static requiere un producer adicional (M2/M5);
        // V1 los trata como iguales y la lens se reduce a
        // Declared/Static vs Observed.
        val declared = HexagonalArchitectureLens.project(input)
        if (declared is ProjectionResult.ProjectionFailed) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.MissingCapability("architecture.dependency-graph"),
                gaps = declared.gaps,
            )
        }
        val declaredGraph = (declared as ProjectionResult.Projected).value

        val observed = ObservedArchitectureLens.project(input)
        val observedGraph: DependencyGraph? = when (observed) {
            is ProjectionResult.Projected -> observed.value
            is ProjectionResult.ProjectionFailed -> null
        }

        // Si no hay observed, la lens no tiene nada que comparar;
        // devuelve un projection sin contradicciones.
        if (observedGraph == null) {
            return ProjectionResult.Projected(
                ConsistencyProjection(
                    declared = declaredGraph,
                    observed = null,
                    contradictions = emptyList(),
                ),
            )
        }

        // Comparar: cualquier edge en observed que NO esté en
        // declared es una contradicción (el código observado
        // importa algo que el self-model dice que no importa).
        val declaredEdges = declaredGraph.edges.toSet()
        val contradictions = observedGraph.edges
            .filter { it !in declaredEdges }
            .map { edge ->
                Contradiction(
                    from = edge.from,
                    to = edge.to,
                    declared = false,
                    observed = true,
                    kind = "observed-but-not-declared",
                )
            }
        // Cualquier edge en declared que NO esté en observed NO es
        // contradicción (puede ser código que no se ejecutó en el
        // window de la observación). V1 sólo reporta el primer
        // sentido.

        return ProjectionResult.Projected(
            ConsistencyProjection(
                declared = declaredGraph,
                observed = observedGraph,
                contradictions = contradictions,
            ),
        )
    }
}

data class ConsistencyProjection(
    val declared: DependencyGraph,
    val observed: DependencyGraph?,
    val contradictions: List<Contradiction>,
) {
    val contradictionCount: Int get() = contradictions.size
}

data class Contradiction(
    val from: String,
    val to: String,
    val declared: Boolean,
    val observed: Boolean,
    val kind: String,
)
