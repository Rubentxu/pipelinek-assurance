package dev.pipelinek.assurance.domain.capabilities

/**
 * Registro centralizado de capability IDs.
 *
 * **Por qué existe:** AAT-13 prohíbe que dos namespaces distintos
 * compartan la misma cadena sin tipo. Los capabilities son otro
 * namespace: la cadena `"architecture.dependency-graph"` aparecía
 * como `const val CAPABILITY` en `HexagonalArchitectureLens`,
 * `ConnascenceLens`, `SolidLens` y como constante local en
 * `CogniCodeArtifactProvider`. Un refactor que cambiase el string en
 * uno de los cuatro sitios rompía silenciosamente los otros tres:
 * los consumidores no lo notaban hasta que un export real fallaba.
 *
 * Esta clase convierte el string en un símbolo del dominio. Las
 * lenses y los providers lo referencian. Una refactorización en el
 * value class (vía M-CAP-DRIFT) falla en todos los tests que dependen
 * del valor canónico, lo que es exactamente el comportamiento que
 * AAT-13 quiere en el dominio.
 *
 * **Cómo se versiona:** un cambio de valor es un cambio de
 * `wire contract` (familia 1+ del ARTIFACT_WIRE_CONTRACTS.md). No se
 * hace sin bump de major.
 */
object Capabilities {

    /** Grafo de dependencias entre módulos / símbolos. */
    const val ARCHITECTURE_DEPENDENCY_GRAPH: String = "architecture.dependency-graph"

    /** Entidades exportadas por un analizador estático (CogniCode). */
    const val ARCHITECTURE_ENTITIES: String = "architecture.entities"

    /** Relaciones declaradas por un analizador estático. */
    const val ARCHITECTURE_RELATIONS: String = "architecture.relations"

    /** Cadenas de invocaciones runtime (Chronos, observability). */
    const val RUNTIME_INVOCATION_CHAIN: String = "runtime.invocation-chain"

    /** Slice causal runtime (Chronos). */
    const val RUNTIME_CAUSAL_SLICE: String = "runtime.causal-slice"

    /** Ventana de observación (token durable). */
    const val RUNTIME_WINDOW: String = "runtime.window"

    /** Resultados de tests ejecutados. */
    const val TEST_RESULTS: String = "test.results"

    /** Topología de tests (relación symbol → test). */
    const val TEST_TOPOLOGY: String = "test.topology"

    /** Señales heurísticas (Detekt, sonar, etc.). */
    const val SIGNALS_SOLID_AUDIT: String = "signals.solid_audit"

    /** Señales heurísticas de Detekt (reglas SARIF). */
    const val SIGNALS_DETEKT: String = "signals.detekt"
}
