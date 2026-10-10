/**
 * C4 (Bloque C) — Motor de ratchets.
 *
 * Ref autoridad: `odd/tasks/block-C-static-real.md` §C4.
 *
 * Un ratchet es una **puerta** sobre el resultado del diff: define
 * qué transiciones se admiten y cuáles bloquean. Es el último
 * paso entre el diff y el gate del Step. El motor es puro: opera
 * sobre el `Diff` que ya calculó `DiffEngine` y devuelve una
 * `RatchetDecision` tipada.
 *
 * **Por qué este motor existe, separado del DiffEngine**: el
 * DiffEngine dice qué pasó (NEW, EXISTING, RESOLVED, REGRESSED,
 * CHANGED); el RatchetEngine dice qué está permitido en esta
 * corrida. La separación importa porque una misma transición
 * puede ser válida en un run y bloqueante en otro: depende de
 * la política del repo, no del estado del baseline. Mezclar
 * ambas cosas en un solo motor acoplaría la decisión de gate a
 * la clasificación, que es justo el error que el ROADMAP §3
 * prohíbe ("el core no decide el outcome del gate").
 *
 * **Políticas implementadas (C4)**:
 *   - `forbidNew`            bloquea entradas en estado `New`.
 *   - `forbidRegressed`      bloquea entradas en estado `Regressed`.
 *   - `forbidChanged`        bloquea entradas en estado `Changed`.
 *   - `noNewCycles`          bloquea cualquier `New` cuyo
 *                            counterexample sea `Cycle`.
 *   - `maxUnresolvedCount`   bloquea si el conteo total de
 *                            `New + Regressed + Changed` excede
 *                            el umbral.
 *   - `maxCyclesCount`       bloquea si el conteo de `New` con
 *                            counterexample `Cycle` excede el
 *                            umbral.
 *
 * **Excepciones**: `RatchetException` permite a un owner declarar
 * que ciertos `stableId` están suprimidos. La supresión respeta
 * `expires`: si la fecha de comparación está más allá del
 * `expires`, la excepción ya no aplica y el finding se evalúa
 * contra la política (no se silencia).
 *
 * **Determinismo**: la decisión del motor es función pura de
 * (`policy`, `diff`, `today`). No lee disco ni reloj. La fecha
 * se pasa como parámetro para que un run de CI sin reloj
 * (`today = null`) produzca siempre la misma decisión.
 */
package dev.pipelinek.assurance.engine

import java.time.LocalDate

/**
 * Política de ratchet.
 *
 * `exceptions` se evalúa ANTES de los flags: una entrada con
 * `stableId` declarado en `exceptions` (y con `expires` vigente
 * o sin `expires`) se excluye del conteo y de la evaluación.
 * La trazabilidad queda en el report del diff: el `owner`,
 * `rationale` y `expires` del baseline siguen ahí.
 */
data class RatchetPolicy(
    val forbidNew: Boolean = false,
    val forbidRegressed: Boolean = false,
    val forbidChanged: Boolean = false,
    val noNewCycles: Boolean = false,
    val maxUnresolvedCount: Int? = null,
    val maxCyclesCount: Int? = null,
    val exceptions: List<RatchetException> = emptyList(),
) {
    init {
        // Un umbral < 0 no tiene sentido. Rechazamos en
        // construcción para que la política inválida no llegue
        // a un run.
        require(maxUnresolvedCount == null || maxUnresolvedCount >= 0) {
            "maxUnresolvedCount no puede ser negativo: $maxUnresolvedCount"
        }
        require(maxCyclesCount == null || maxCyclesCount >= 0) {
            "maxCyclesCount no puede ser negativo: $maxCyclesCount"
        }
    }
}

/**
 * Excepción declarada por un owner sobre un conjunto de
 * `stableId`. Mientras la fecha de comparación esté dentro de
 * `expires` (o `expires` sea null), los `stableId` listados no
 * cuentan contra la política.
 *
 * La forma es paralela a `KnownViolation.expires`: ambos
 * honoring "excepción con fecha" porque el problema es el
 * mismo (sin fecha, una excepción se vuelve eterna y bloquea
 * un gate que el owner no puede reabrir).
 */
data class RatchetException(
    val stableIds: Set<FindingId>,
    val owner: String? = null,
    val rationale: String? = null,
    val expires: LocalDate? = null,
) {
    fun isActive(atDate: LocalDate?): Boolean =
        expires == null || (atDate != null && atDate <= expires)
}

/**
 * Razón por la que un ratchet bloquea.
 *
 * `Cycle` se separa de `New` aunque la entrada esté en estado
 * `New` para que un report pueda distinguir "encontré un ciclo
 * nuevo" de "encontré cualquier otra cosa nueva" — son dos
 * señales operativas distintas.
 */
enum class RatchetViolation { New, Regressed, Changed, Cycle, UnresolvedCount, CyclesCount }

/**
 * Decisión del ratchet.
 *
 * `Blocked` lleva la lista de razones: cada razón apunta a las
 * entradas del diff que la provocaron. El Step handler usa
 * estas razones para producir el mensaje del gate.
 */
sealed interface RatchetDecision {
    data object Allowed : RatchetDecision
    data class Blocked(val reasons: List<BlockedReason>) : RatchetDecision {
        init {
            require(reasons.isNotEmpty()) { "Blocked debe llevar al menos una razón" }
        }
    }
}

data class BlockedReason(
    val kind: RatchetViolation,
    val findings: List<DiffEntry>,
    val detail: String,
) {
    init {
        require(findings.isNotEmpty()) { "BlockedReason debe llevar al menos un finding" }
    }
}

object RatchetEngine {

    /**
     * Aplica la política al diff y devuelve la decisión.
     *
     * @param policy política de ratchet
     * @param diff resultado del `DiffEngine.diff`
     * @param isCycleByStableId función que dice si un `stableId`
     *        del diff viene de un counterexample `Cycle`. La
     *        separación es deliberada: el `DiffEntry` no carga
     *        el counterexample (lo descartamos para que el diff
     *        sea ligero y reproducible), y el motor de ratchet
     *        pregunta al caller cuando necesita el dato. El
     *        caller lo construye una sola vez leyendo el report
     *        original.
     * @param today fecha de comparación para `expires`; null =
     *        "sin fecha", que en la práctica significa "todas
     *        las excepciones se consideran activas"
     */
    fun evaluate(
        policy: RatchetPolicy,
        diff: Diff,
        isCycleByStableId: (FindingId) -> Boolean = { false },
        today: LocalDate? = null,
    ): RatchetDecision {
        // 1. Excluimos del análisis las entradas que están
        //    dentro de una excepción vigente.
        val activeExceptions = policy.exceptions.filter { it.isActive(today) }
        val exemptedIds = activeExceptions
            .flatMap { it.stableIds }
            .toSet()
        val effective = diff.entries.filterNot { it.stableId in exemptedIds }

        val reasons = mutableListOf<BlockedReason>()

        if (policy.forbidNew) {
            val newFindings = effective.filter { it.state == DiffState.New }
            if (newFindings.isNotEmpty()) {
                reasons += BlockedReason(
                    kind = RatchetViolation.New,
                    findings = newFindings,
                    detail = "${newFindings.size} finding(s) nuevos contra el baseline",
                )
            }
        }
        if (policy.forbidRegressed) {
            val regressed = effective.filter { it.state == DiffState.Regressed }
            if (regressed.isNotEmpty()) {
                reasons += BlockedReason(
                    kind = RatchetViolation.Regressed,
                    findings = regressed,
                    detail = "${regressed.size} excepción(es) de baseline caducaron",
                )
            }
        }
        if (policy.forbidChanged) {
            val changed = effective.filter { it.state == DiffState.Changed }
            if (changed.isNotEmpty()) {
                reasons += BlockedReason(
                    kind = RatchetViolation.Changed,
                    findings = changed,
                    detail = "${changed.size} violation(es) cambiaron de fingerprint",
                )
            }
        }

        // Conteos: New + Regressed + Changed.
        val unresolved = effective.filter {
            it.state == DiffState.New || it.state == DiffState.Regressed || it.state == DiffState.Changed
        }
        if (policy.maxUnresolvedCount != null && unresolved.size > policy.maxUnresolvedCount) {
            reasons += BlockedReason(
                kind = RatchetViolation.UnresolvedCount,
                findings = unresolved,
                detail = "unresolved=${unresolved.size} excede maxUnresolvedCount=${policy.maxUnresolvedCount}",
            )
        }

        // Ciclos: el caller indica, vía `isCycleByStableId`,
        // si cada entrada viene de un counterexample Cycle.
        val newCycleEntries = effective.filter { entry ->
            entry.state == DiffState.New && isCycleByStableId(entry.stableId)
        }
        if (policy.noNewCycles && newCycleEntries.isNotEmpty()) {
            reasons += BlockedReason(
                kind = RatchetViolation.Cycle,
                findings = newCycleEntries,
                detail = "${newCycleEntries.size} cycle(s) nuevo(s) detectado(s)",
            )
        }
        if (policy.maxCyclesCount != null && newCycleEntries.size > policy.maxCyclesCount) {
            reasons += BlockedReason(
                kind = RatchetViolation.CyclesCount,
                findings = newCycleEntries,
                detail = "new cycles=${newCycleEntries.size} excede maxCyclesCount=${policy.maxCyclesCount}",
            )
        }

        return if (reasons.isEmpty()) RatchetDecision.Allowed
        else RatchetDecision.Blocked(reasons)
    }
}
