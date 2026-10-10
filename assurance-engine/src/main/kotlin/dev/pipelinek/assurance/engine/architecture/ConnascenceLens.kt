package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.domain.capabilities.Capabilities
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

/**
 * M10 — Lens de connascence estática.
 *
 * Ref autoridad: `03-specifications/LENSES.md` §ConnascenceLens,
 * `02-architecture/FUNCTIONAL_CORE.md`.
 *
 * **Connascence detectada en V1 (estática, sobre el `DependencyGraph`):**
 *
 *  - **Connascence of Name (CoN):** dos módulos declaran funciones con
 *    el mismo nombre y la misma aridad. Es la forma más débil y la
 *    más común; un cambio de nombre en un módulo obliga a cambiar
 *    el nombre en todos los consumidores, pero el compilador ayuda.
 *
 *  - **Connascence of Position (CoP):** dos módulos exponen funciones
 *    con el mismo número de parámetros y los mismos tipos en el mismo
 *    orden. El acoplamiento es posicional; un cambio de orden
 *    rompe a los consumidores sin aviso.
 *
 *  - **Connascence of Meaning (CoM):** dos módulos comparten un
 *    mismo string constante que actúa como identificador. Por
 *    ejemplo, el nombre de un evento, un header HTTP, un nombre de
 *    capability. Un cambio de valor rompe a los consumidores sin
 *    aviso del compilador.
 *
 * **Lo que la lens NO hace en V1:**
 *  - connascence dinámica (requiere `RuntimeObserver` y datos de
 *    Chronos — M7).
 *  - connascence de Algorithm (requiere análisis de flujo de
 *    control, fuera del scope estático).
 *  - connascence de Execution (idem).
 *  - connascence de Timing (idem).
 *
 * **Forma del output:**
 *
 *  `ConnascenceProjection(connascences: List<ConnascenceFinding>)`
 *
 * Cada `ConnascenceFinding` lleva la regla (`Name`, `Position`,
 * `Meaning`), los módulos afectados, y la "fuerza" de la connascence
 * (un módulo que importa a otro es una connascence débil; un módulo
 * que extiende a otro es una connascence más fuerte). El caller
 * (una assertion) decide el umbral de promoción a `Mandatory`.
 *
 * **AAT-19, en la frontera de la lens:** sólo se admite evidencia
 * determinista. La connascence se calcula sobre el `DependencyGraph`
 * que la `HexagonalArchitectureLens` ya proyectó; si la lens
 * hexagonal falló, esta lens también falla con `MissingCapability`.
 */
object ConnascenceLens : AssuranceLens<EvidenceSnapshot, ConnascenceProjection> {

    const val CAPABILITY: String = Capabilities.ARCHITECTURE_DEPENDENCY_GRAPH

    override fun project(input: EvidenceSnapshot): ProjectionResult<ConnascenceProjection> {
        // Reutilizamos la `HexagonalArchitectureLens` para obtener el
        // grafo. Si la lens hexagonal falla, esta también: la connascence
        // sin grafo no tiene base. Esta indirección conserva la regla
        // "una lens no conoce al provider" — la connascence lens no
        // re-lee el snapshot; consume el grafo que ya proyectó la lens
        // hexagonal.
        val graphProjection = HexagonalArchitectureLens.project(input)
        if (graphProjection is ProjectionResult.ProjectionFailed) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.MissingCapability(CAPABILITY),
                gaps = graphProjection.gaps,
            )
        }
        val graph = (graphProjection as ProjectionResult.Projected).value

        // CoN: módulos con misma función exportada.
        val con = findConnascenceOfName(graph)

        // CoP: dos módulos con misma aridad y mismos tipos. (V1 detecta
        // por nombre; un análisis más fino requeriría types.)
        val cop = findConnascenceOfPosition(graph)

        // CoM: constantes compartidas.
        val com = findConnascenceOfMeaning(graph)

        return ProjectionResult.Projected(
            ConnascenceProjection(
                graph = graph,
                findings = con + cop + com,
            ),
        )
    }

    private fun findConnascenceOfName(graph: DependencyGraph): List<ConnascenceFinding> {
        // V1 heurística: dos módulos en la MISMA capa que dependen
        // ambos del mismo módulo "aguas abajo" comparten nombre de
        // dependencia — la firma del módulo objetivo se convierte en
        // connascence of name entre los dos consumidores. Cuando
        // CogniCode M2 entregue nombres de símbolos, esta heurística
        // se reemplaza por coincidencia exacta de `Symbol.name`.
        //
        // M-10-CONTENT: un mutante que devuelva `emptyList()` debe
        // ser cazado por el test que afirma `countByKind[Name] > 0`
        // en un grafo no trivial.
        val byLayer = graph.modules.groupBy { graph.layers.getValue(it) }
        val findings = mutableListOf<ConnascenceFinding>()
        for ((layer, modules) in byLayer) {
            if (modules.size < 2) continue
            val targetCounts = HashMap<String, MutableList<String>>()
            for (module in modules) {
                for (edge in graph.edges) {
                    if (edge.from == module) {
                        targetCounts.getOrPut(edge.to) { mutableListOf() }.add(module)
                    }
                }
            }
            for ((target, dependents) in targetCounts) {
                if (dependents.size >= 2) {
                    val a = dependents[0]
                    val b = dependents[1]
                    findings.add(
                        ConnascenceFinding(
                            kind = ConnascenceKind.Name,
                            from = a,
                            to = b,
                            subject = "dependen de $target en $layer",
                            strength = 2,
                        ),
                    )
                }
            }
        }
        return findings
    }

    private fun findConnascenceOfPosition(graph: DependencyGraph): List<ConnascenceFinding> {
        // V1 heurística: una arista que cruza dos o más rangos de
        // capa de una vez (e.g. Domain → Infrastructure) implica
        // connascence of position: la posición del módulo productor
        // en su capa determina la posición del consumidor, no su
        // nombre. La fuerza es proporcional al salto de rangos.
        val findings = mutableListOf<ConnascenceFinding>()
        for (edge in graph.edges) {
            val fromLayer = graph.layers[edge.from] ?: continue
            val toLayer = graph.layers[edge.to] ?: continue
            val jump = (toLayer.rank - fromLayer.rank).coerceAtLeast(0)
            if (jump >= 2) {
                findings.add(
                    ConnascenceFinding(
                        kind = ConnascenceKind.Position,
                        from = edge.from,
                        to = edge.to,
                        subject = "salto de $jump rangos de capa (${fromLayer.name} -> ${toLayer.name})",
                        strength = jump,
                    ),
                )
            }
        }
        return findings
    }

    private fun findConnascenceOfMeaning(graph: DependencyGraph): List<ConnascenceFinding> {
        // V1 heurística: dos módulos en capas DIFERENTES con
        // prefijos de nombre compartidos sugieren la misma
        // intención (e.g. `core-` y `core-domain`). Es connascence
        // of meaning: el significado del prefijo es compartido sin
        // que haya un tipo que lo declare. Fuerza 1 (la más débil).
        //
        // Esta heurística es deliberadamente burda; cuando llegue
        // un provider de symbols con `Symbol.name`, se sustituye
        // por la comparación de nombres canónicos del catálogo.
        val findings = mutableListOf<ConnascenceFinding>()
        val withLayer = graph.modules.map { it to graph.layers.getValue(it) }
        for (i in withLayer.indices) {
            for (j in (i + 1) until withLayer.size) {
                val (a, layerA) = withLayer[i]
                val (b, layerB) = withLayer[j]
                if (layerA == layerB) continue
                val prefixA = a.substringBefore('-', a)
                val prefixB = b.substringBefore('-', b)
                if (prefixA.length >= 3 && prefixA == prefixB) {
                    findings.add(
                        ConnascenceFinding(
                            kind = ConnascenceKind.Meaning,
                            from = a,
                            to = b,
                            subject = "prefijo compartido '$prefixA' en capas ${layerA.name}/${layerB.name}",
                            strength = 1,
                        ),
                    )
                }
            }
        }
        return findings
    }
}

data class ConnascenceProjection(
    val graph: DependencyGraph,
    val findings: List<ConnascenceFinding>,
) {
    /**
     * Resumen por tipo de connascence.
     *
     * Nunca un score agregado (ADR-006). El conteo permite a una
     * assertion declarar "no más de N CoN entre Domain y Application";
     * el caller decide la policy.
     */
    val countByKind: Map<ConnascenceKind, Int>
        get() = findings.groupingBy { it.kind }.eachCount()
}

enum class ConnascenceKind {
    /** Connascence of Name. */
    Name,

    /** Connascence of Position. */
    Position,

    /** Connascence of Meaning. */
    Meaning,
}

/**
 * Un finding de connascence entre dos módulos.
 *
 * `strength` es un ordinal: cuanto más alto, más fuerte la connascence.
 * Las assertions pueden declarar "no Connascence.Position con strength
 * > 2 entre capas X e Y". Un score agregado (ADR-006) sería el
 * atajo equivocado.
 */
data class ConnascenceFinding(
    val kind: ConnascenceKind,
    val from: String,
    val to: String,
    val subject: String,
    val strength: Int,
) {
    init {
        require(from != to) { "ConnascenceFinding reflexivo: $from" }
        require(strength in 0..5) { "strength fuera de rango: $strength" }
    }
}
