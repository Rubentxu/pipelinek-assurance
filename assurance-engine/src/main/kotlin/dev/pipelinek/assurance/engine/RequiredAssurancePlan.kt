package dev.pipelinek.assurance.engine

/**
 * M10 — `RequiredAssurancePlan` calcula la lista de suites que DEBEN
 * correr dado un cambio y un diff de findings.
 *
 * **Por qué existe (ROADMAP §3 M10):**
 *   "RequiredAssurancePlan a partir de cambios, como lista justificada
 *    de suites, nunca como score de riesgo."
 *
 * El output es una lista de `SuiteSelection`, cada una con la `SuiteId`
 * y la `Reason` que justifica su inclusion. La suma de razones
 * constituye el argumento de release; un humano o un agente puede
 * recorrerlo sin tener que invertir un puntaje numerico.
 *
 * **Lo que NO hace:**
 *   - No produce un risk score (0.0..1.0). Un score no es justificable
 *     y hace que el plan sea opaco a la revision humana.
 *   - No decide por si solo si correr la suite; la ejecucion queda en
 *     manos del step `assurance.check` (M3) o `assurance.verify` (M7).
 *   - No invierte ni anade findings al diff; consume el diff que
 *     `BaselineAndDiff.diff(...)` ya emitio.
 *
 * **Determinismo:** dado el mismo input (`changedSuites`, `diff`,
 * `engineVersion`), el plan es identico byte a byte. La
 * canonicalizacion se delega a `CanonicalEncoder.canonicalPlan(...)`
 * para producir un digest estable.
 */
object RequiredAssurancePlan {

    /**
     * Identifica una suite por su `SuiteId` y la razon por la que
     * fue seleccionada.
     */
    data class SuiteSelection(
        val suiteId: SuiteId,
        val reason: Reason,
    )

    /**
     * Razon por la que una suite debe correr. Es enumerada, no
     * numerica: el lector del plan ve el motivo literal, no un
     * puntaje que tiene que invertir.
     */
    sealed interface Reason {
        /** La suite tiene findings NEW (no estaban en el baseline). */
        data object NewFindingsPresent : Reason
        /** La suite fue tocada por el cambio (codigo, IR, lens). */
        data class TouchedByChange(val path: String) : Reason
        /** La suite es Mandatory y debe correr siempre. */
        data object MandatoryBaseline : Reason
    }

    /**
     * Input del plan: las suites que cambiaron (paths del repo o del
     * IR) y el diff de findings de la corrida anterior.
     */
    data class Input(
        val availableSuites: List<AssuranceSuiteIR>,
        val changedPaths: List<String>,
        val diff: Diff,
        val engineVersion: String,
    ) {
        init {
            require(availableSuites.isNotEmpty()) {
                "Input sin suites disponibles: el plan no tiene que inventar"
            }
        }
    }

    /**
     * Output: lista de `SuiteSelection`. El orden es estable por
     * `suiteId.value` para que el digest no dependa del orden de
     * iteracion de `availableSuites`.
     */
    data class Plan(
        val engineVersion: String,
        val selections: List<SuiteSelection>,
    ) {
        init {
            // AAT-22: sin duplicados por (suiteId, reason). Si una
            // suite es Mandatory Y ademas tiene findings NEW, sale
            // UNA sola vez con la razon mas fuerte (Mandatory gana).
            val keys = selections.map { it.suiteId to it.reason::class }
            require(keys.size == keys.toSet().size) {
                "Plan con selecciones duplicadas: $keys"
            }
        }
    }

    /**
     * Construye el plan a partir del input.
     *
     * Reglas de inclusion (en orden de prioridad):
     *   1. **Mandatory baseline**: suites con `requiredEvidence`
     *      que declaren `mandatory: true` en su metadata siempre
     *      corren, sin importar el diff.
     *   2. **Touched by change**: suites cuyo `suiteId.value` aparece
     *      como prefijo en algun `changedPath` (ej: `assurance-engine`
     *      cambia → `architecture.hexagonal` corre).
     *   3. **New findings present**: suites que tienen al menos un
     *      finding con `DiffState.New` en el diff. La suite se
     *      identifica por su `LensPlan.kind` como namespace.
     *
     * Una suite que cumpla multiples reglas sale UNA vez con la
     * razon de mayor prioridad (Mandatory > TouchedByChange >
     * NewFindingsPresent).
     */
    fun build(input: Input): Plan {
        val selections = mutableListOf<SuiteSelection>()
        val seen = mutableSetOf<SuiteId>()

        // Mandatory baseline
        for (suite in input.availableSuites) {
            if (suite.metadata["mandatory"] == "true" && seen.add(suite.suiteId)) {
                selections += SuiteSelection(suite.suiteId, Reason.MandatoryBaseline)
            }
        }

        // Touched by change
        for (suite in input.availableSuites) {
            val touched = input.changedPaths.any { p -> p.startsWith(suite.suiteId.value) }
            if (touched && seen.add(suite.suiteId)) {
                val firstMatch = input.changedPaths.first { it.startsWith(suite.suiteId.value) }
                selections += SuiteSelection(suite.suiteId, Reason.TouchedByChange(firstMatch))
            }
        }

        // New findings present: el `assertionId.value` tiene la forma
        // `<lens-kind>/<operator>/<n>`. Si empieza con el `kind` de la
        // lens de la suite, es un finding de esa suite.
        val newAssertionPrefixes = input.diff.entries
            .filter { it.state == DiffState.New }
            .map { it.stableId.assertionId.value }
            .toSet()
        for (suite in input.availableSuites) {
            val kind = suite.lenses.firstOrNull()?.kind ?: continue
            val matches = newAssertionPrefixes.any { it.startsWith("$kind/") }
            if (matches && seen.add(suite.suiteId)) {
                selections += SuiteSelection(suite.suiteId, Reason.NewFindingsPresent)
            }
        }

        // Orden estable por suiteId para que el digest no dependa del
        // orden de iteracion del input.
        val ordered = selections.sortedBy { it.suiteId.value }
        return Plan(input.engineVersion, ordered)
    }
}
