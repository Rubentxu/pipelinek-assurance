package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.Digest

/**
 * M10 — `AssurancePack` versionado.
 *
 * Ref: ROADMAP §3 M10, "assurance-dsl (packs versionados)".
 *
 * Un pack es una colección NOMBRADA y VERSIONADA de suites y reglas
 * que un caller (CLI, agente, CI) puede cargar para decidir qué
 * assurance correr sin tener que conocer el detalle del IR.
 *
 * **Por qué existe:**
 *   - Permite a un humano decir "corre el pack de release de M10"
 *     sin tener que recordar la lista de suites obligatorias.
 *   - Permite versionar el contrato: si el pack cambia entre
 *     versiones, el `packVersion` lo declara y un run puede
 *     compararlos.
 *   - Es el input natural para `RequiredAssurancePlan.build(...)`:
 *     el pack aporta `availableSuites` y las reglas de inclusion;
 *     el plan los une con el diff para emitir la lista justificada.
 *
 * **Lo que NO hace:**
 *   - No es ejecutable: no corre las suites. Solo describe qué
 *     debería correr. La ejecución queda en `assurance.check` (M3)
 *     o `assurance.verify` (M7).
 *   - No incluye evidencia: el pack es plano de suites, no de
 *     snapshots. La evidencia llega por separado.
 *   - No tiene estado mutable: una vez creado, sus campos no
 *     cambian. La evolucion se hace publicando una nueva version.
 */
data class AssurancePack(
    val name: String,
    val packVersion: String,
    val description: String,
    val suites: List<SuiteRef>,
    val rules: List<Rule>,
) {
    init {
        require(name.isNotBlank()) { "AssurancePack.name no puede estar vacio" }
        require(packVersion.isNotBlank()) {
            "AssurancePack.packVersion no puede estar vacio"
        }
        require(suites.isNotEmpty()) {
            "AssurancePack '$name' sin suites: el pack no tiene contenido"
        }
        val ids = suites.map { it.suiteId.value }
        require(ids.size == ids.toSet().size) {
            "AssurancePack '$name' con suites duplicadas: $ids"
        }
    }

    /**
     * Referencia a una suite por `suiteId` y `suiteVersion`. El
     * `digest` es opcional y, si está presente, el caller puede
     * compararlo con el digest real de la suite para detectar
     * drift entre la suite declarada en el pack y la suite que
     * efectivamente va a correr.
     */
    data class SuiteRef(
        val suiteId: SuiteId,
        val suiteVersion: String,
        val digest: Digest? = null,
        val pathPrefix: String? = null,
    )

    /**
     * Regla de inclusion que el pack declara para una suite.
     * El orden de prioridad (de mayor a menor) es:
     * `Mandatory` > `Touched` > `NewFindings`.
     *
     * Si una suite tiene multiples reglas, la de mayor prioridad
     * gana. Esto se enforce en `RequiredAssurancePlan.build(...)`.
     */
    sealed interface Rule {
        /** La suite corre siempre, sin importar el diff. */
        data class Mandatory(val suiteId: SuiteId) : Rule
        /** La suite corre si algun path cambiado empieza con
         *  `prefix` (o por el `suiteId` si prefix es null). */
        data class Touched(val suiteId: SuiteId, val prefix: String? = null) : Rule
        /** La suite corre si tiene findings NEW en su `lens.kind`. */
        data class NewFindings(val suiteId: SuiteId) : Rule
    }
}
