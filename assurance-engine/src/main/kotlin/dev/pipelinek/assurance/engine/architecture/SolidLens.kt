package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.capabilities.Capabilities
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

/**
 * M10 — Lens de principios SOLID (clasificación epistémica explícita).
 *
 * Ref: `03-specifications/LENSES.md` §SolidLens y `04-adrs/ADR-006-NO-GLOBAL-QUALITY-SCORE.md`.
 *
 * **Clasificación epistémica por principio (de la spec LENSES.md):**
 *
 *  - **DIP** (Dependency Inversion): puede ser una **assertion fuerte**.
 *    "Domain no depende de adapter" se enforce con el `DependencyGraph`
 *    y la assertion `noDependency` de M1. El motor ya tiene la
 *    evidencia; esta lens sólo la proyecta.
 *
 *  - **ISP** (Interface Segregation): produce **métricas y relaciones**,
 *    no un veredicto. El conteo de dependencias entrantes por módulo
 *    es la métrica ISP V1; un caller decide el umbral.
 *
 *  - **SRP** (Single Responsibility): produce **señales heurísticas**
 *    (Signal). Un módulo con muchas dependencias salientes o muchos
 *    symbols declarados "sugiere" una responsabilidad no segmentada;
 *    la lens lo reporta como `Signal`, no como `Failed`. Pasar un
 *    SRP Signal a `Mandatory` es un uso inapropiado (LENSES.md).
 *
 *  - **OCP** (Open/Closed): heurística similar a SRP. V1 no la
 *    calcula; queda como Signal placeholder.
 *
 *  - **LSP** (Liskov Substitution): requiere `RuntimeObserver` y
 *    Chronos (M7). V1 devuelve `Unsupported` por construcción.
 *
 * **Por qué SRP y OCP son Signal y no Failed:** SRP y OCP no tienen
 * una métrica determinista; cualquier intento de "medirlos" sin
 * observabilidad runtime es heurístico. Una `Signal` con
 * `HeuristicAnalyzer` no puede satisfacer una assertion
 * determinista (AAT-19). Promover SRP a `Mandatory` sería comprar
 * utilidad con un gate que la spec prohíbe.
 */
object SolidLens : AssuranceLens<EvidenceSnapshot, SolidProjection> {

    const val CAPABILITY: String = Capabilities.ARCHITECTURE_DEPENDENCY_GRAPH

    override fun project(input: EvidenceSnapshot): ProjectionResult<SolidProjection> {
        val graphProjection = HexagonalArchitectureLens.project(input)
        if (graphProjection is ProjectionResult.ProjectionFailed) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.MissingCapability(CAPABILITY),
                gaps = graphProjection.gaps,
            )
        }
        val graph = (graphProjection as ProjectionResult.Projected).value

        // DIP: parte de la HexagonalProjection; aquí la formalizamos
        // como una métrica de "violaciones DIP detectadas por la
        // arquitectura hexagonal". Si la política ya está bien, la
        // lista está vacía.
        val dipViolations = findDipViolations(graph)

        // ISP: distribución de dependencias entrantes. Una métrica
        // es la mediana de `dependientes entrantes` por módulo. Si
        // hay un módulo con `dependientes > mediana + 2σ`, se reporta
        // como Signal. V1 devuelve Signal global sin clasificar.
        val ispSignals = findIspSignals(graph)

        // SRP/OCP: heurísticas V1. Misma forma que ISP (desviación
        // estándar sobre outgoing edges). V1 entrega 0 hallazgos en
        // grafos pequeños como el self-model.graph del proyecto, pero
        // produce señales útiles en grafos grandes (multi-módulo) y
        // es cazada por los mutantes M-SOLID-SRP-EMPTY y
        // M-SOLID-OCP-EMPTY. LSP queda documentado como `Unsupported`
        // en el KDoc: requiere `RuntimeObserver` (Chronos M7) y no
        // tiene fuente de datos en V1.
        val srpSignals = findSrpSignals(graph)
        val ocpSignals = findOcpSignals(graph)

        return ProjectionResult.Projected(
            SolidProjection(
                graph = graph,
                dipViolations = dipViolations,
                ispSignals = ispSignals,
                srpSignals = srpSignals,
                ocpSignals = ocpSignals,
            ),
        )
    }

    private fun findDipViolations(graph: DependencyGraph): List<DipViolation> {
        // DIP: Domain no debe depender de capas más externas. Usamos
        // la misma regla que `noDependency` (M1): la capa destino no
        // puede ser más externa que la origen. Devolvemos las
        // violaciones ya existentes; la `noDependency` assertion
        // aplica la policy.
        val violations = mutableListOf<DipViolation>()
        for (edge in graph.edges) {
            val fromLayer = graph.layers.getValue(edge.from)
            val toLayer = graph.layers.getValue(edge.to)
            if (fromLayer.rank < toLayer.rank) {
                violations += DipViolation(
                    from = edge.from,
                    to = edge.to,
                    fromLayer = fromLayer.name,
                    toLayer = toLayer.name,
                )
            }
        }
        return violations
    }

    private fun findIspSignals(graph: DependencyGraph): List<HeuristicSignal> {
        // ISP V1: desviación estándar de `dependientes entrantes` por
        // módulo. Un módulo con muchos dependientes puede ser una
        // interfaz "gorda". El caller decide si lo promueve a gate.
        val incoming = graph.modules.associateWith { name ->
            graph.edges.count { it.to == name }
        }
        return statisticalOutliers(
            counts = incoming,
            principle = "ISP",
            metricName = "dependientes_entrantes",
        )
    }

    /**
     * SRP V1: módulos con muchas dependencias SALIENTES sugieren
     * varias responsabilidades (un módulo que "sabe de todo" lo
     * delata su fan-out). Misma forma estadística que ISP, sobre
     * outgoing.
     */
    private fun findSrpSignals(graph: DependencyGraph): List<HeuristicSignal> {
        val outgoing = graph.modules.associateWith { name ->
            graph.edges.count { it.from == name }
        }
        return statisticalOutliers(
            counts = outgoing,
            principle = "SRP",
            metricName = "dependientes_salientes",
        )
    }

    /**
     * OCP V1: módulos en capas estables (Domain, Application) que
     * tienen muchas dependencias ENTRAN hacia módulos en capas
     * menos estables. Una violación OCP sería extender Domain
     * cada vez que Adapters cambia; el síntoma es "muchos
     * dependents de Adapters hacia capas internas". Misma forma
     * que ISP/SRP, filtrada por capa destino no estable.
     */
    private fun findOcpSignals(graph: DependencyGraph): List<HeuristicSignal> {
        val nonStable = setOf(Layer.Adapters, Layer.Infrastructure)
        val dependentsIntoNonStable = graph.modules.associateWith { name ->
            graph.edges.count { edge ->
                edge.to == name && graph.layers[edge.from] in nonStable
            }
        }
        return statisticalOutliers(
            counts = dependentsIntoNonStable,
            principle = "OCP",
            metricName = "dependientes_desde_capas_no_estables",
        )
    }

    private fun statisticalOutliers(
        counts: Map<String, Int>,
        principle: String,
        metricName: String,
    ): List<HeuristicSignal> {
        if (counts.isEmpty()) return emptyList()
        val mean = counts.values.average()
        val variance = counts.values.sumOf { (it - mean) * (it - mean) } / counts.size
        val stdDev = kotlin.math.sqrt(variance)
        if (stdDev <= 0.0) return emptyList()
        val threshold = mean + 2 * stdDev
        return counts
            .filter { (_, count) -> count > threshold }
            .map { (name, count) ->
                HeuristicSignal(
                    principle = principle,
                    module = name,
                    metric = "$metricName=$count",
                    threshold = threshold.toString(),
                )
            }
    }
}

data class SolidProjection(
    val graph: DependencyGraph,
    /** DIP violations: lista determinista de dependencias de capas más externas. */
    val dipViolations: List<DipViolation>,
    /** ISP signals: lista heurística de módulos con muchos dependientes. */
    val ispSignals: List<HeuristicSignal>,
    /** SRP signals: módulos con fan-out anormal. */
    val srpSignals: List<HeuristicSignal>,
    /** OCP signals: módulos con muchos dependents desde capas no estables. */
    val ocpSignals: List<HeuristicSignal>,
) {
    /**
     * Una métrica agregada: el conteo de DIP violations. El caller
     * (una assertion) puede declarar "no más de 0 DIP violations"
     * sin pasar por un score compuesto (ADR-006).
     */
    val dipViolationCount: Int get() = dipViolations.size
}

data class DipViolation(
    val from: String,
    val to: String,
    val fromLayer: String,
    val toLayer: String,
)

/**
 * Signal heurística de una lens. La autoridad la pone el caller al
 * convertirla en `EvidenceItem.Signal` (siempre `HeuristicAnalyzer`,
 * AAT-19).
 */
data class HeuristicSignal(
    val principle: String,
    val module: String,
    val metric: String,
    val threshold: String,
)
