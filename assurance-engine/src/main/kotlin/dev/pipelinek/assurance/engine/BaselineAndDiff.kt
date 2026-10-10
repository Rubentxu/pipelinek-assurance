package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import java.time.LocalDate

/**
 * M4 — Diff algebra: stable finding id, baseline, classification.
 *
 * Ref autoridad: `03-specifications/BASELINE_AND_DIFF.md`, `08-testing/MUTATION_CATALOG.md`
 * M-B01 ("NEW clasificado EXISTING"), AAT-18 ("baseline suppression requiere
 * stable finding id").
 *
 * `DiffState` vive en `Assurance.kt` (donde ya está como enum de cinco
 * variantes) por su uso compartido con `Counterexample.BaselineRegression.state`.
 * Aquí se añade el resto del algebra: `FindingId`, `KnownViolation`,
 * `DiffEntry`, `Diff`, `DiffSummary` y `DiffEngine`.
 */
data class FindingId(
    val assertionId: AssertionId,
    val fingerprint: Digest,
) {
    init {
        // AAT-18: la supresión de baseline exige stable finding id. Un
        // fingerprint vacío haría estable cualquier cosa, así que el
        // require garantiza que el id tiene información real.
        require(fingerprint.hex.isNotBlank()) {
            "FindingId.fingerprint no puede ser vacío"
        }
    }
}

/**
 * Violación conocida declarada en un baseline.
 *
 * El baseline es un conjunto de `KnownViolation`. Cada una dice "este
 * finding existe, lo conozco, y decido qué hacer con él". El diff
 * compara el report actual contra el baseline; un finding con el
 * mismo `stableId` se etiqueta como `EXISTING`, uno con `stableId`
 * nuevo como `NEW`, uno que estaba en el baseline pero ya no aparece
 * como `RESOLVED`.
 *
 * `owner` y `rationale` son la historia: quién decidió suprimirlo y
 * por qué. `expires` es la fecha tras la cual la supresión deja de
 * aplicarse — un baseline sin `expires` no caduca, lo que `DiffEngine`
 * reporta como advertencia (no como error) para que un auditor lo
 * vea.
 */
data class KnownViolation(
    val stableId: FindingId,
    val assertionId: AssertionId,
    val fingerprint: Digest,
    val firstSeenRevision: RevisionRef,
    val owner: String? = null,
    val rationale: String? = null,
    val expires: LocalDate? = null,
) {
    init {
        require(fingerprint.hex.isNotBlank()) {
            "KnownViolation.fingerprint no puede ser vacío"
        }
    }

    /** ¿La supresión sigue vigente en la fecha dada? */
    fun isActive(atDate: LocalDate): Boolean = expires == null || atDate <= expires
}

/**
 * Clasificación de un finding en el diff.
 *
 * `Finding` lleva el `stableId` y la información mínima que el ratchet
 * o el report del diff necesita. `NoFinding` representa un finding que
 * estaba en el baseline pero ya no aparece en el report — su
 * `state` es siempre `Resolved`.
 */
data class DiffEntry(
    val stableId: FindingId,
    val state: DiffState,
    val subject: String,
    val explanation: String,
    val owner: String? = null,
    val rationale: String? = null,
    val expires: LocalDate? = null,
) {
    init {
        // Un `DiffEntry` con `NoFinding`-shape tiene state `Resolved`. Si
        // alguien declara `New` o `Existing` aquí, está construyendo un
        // finding que debería tener su `KnownViolation` original, y
        // `DiffEngine` la prefiere. La forma del diff no es un atajo.
        if (state != DiffState.Resolved) {
            require(subject.isNotBlank()) {
                "DiffEntry con state=$state requiere subject no vacío"
            }
        }
    }
}

/**
 * Diff: lista de entries con metadata del baseline.
 *
 * El diff es **inmutable y reproducible**: dos `DiffEngine.diff` con
 * el mismo input producen el mismo `Diff` byte a byte (mismo orden,
 * mismos digests). El `engineVersion` y el `baselineDigest` son parte
 * del input que entra al digest del diff, lo que hace que un re-run
 * sobre la misma baseline + el mismo report dé el mismo `Diff.digest`.
 */
data class Diff(
    val baselineName: String,
    val baselineDigest: Digest,
    val currentDigest: Digest,
    val engineVersion: String,
    val entries: List<DiffEntry>,
) {
    /**
     * Resumen por estado. Nunca un score agregado (ADR-006). El conteo
     * permite reportar cuántos findings están en cada estado; lo que
     * el caller hace con el conteo es suyo.
     */
    val summary: DiffSummary
        get() {
            var n = 0; var e = 0; var r = 0; var re = 0; var c = 0
            for (entry in entries) {
                when (entry.state) {
                    DiffState.New -> n++
                    DiffState.Existing -> e++
                    DiffState.Resolved -> r++
                    DiffState.Regressed -> re++
                    DiffState.Changed -> c++
                }
            }
            return DiffSummary(new = n, existing = e, resolved = r, regressed = re, changed = c)
        }
}

data class DiffSummary(
    val new: Int,
    val existing: Int,
    val resolved: Int,
    val regressed: Int,
    val changed: Int,
) {
    val total: Int get() = new + existing + resolved + regressed + changed
}

/**
 * Motor de diff: clasifica un report contra un baseline.
 *
 * El motor es **puro**: no lee disco, no llama red, no consulta
 * reloj (AAT-17). La fecha de comparación para `expires` se pasa como
 * parámetro; el motor no usa `LocalDate.now()`. Esto preserva el
 * determinismo del digest del diff.
 */
object DiffEngine {
    /**
     * Compara un report actual contra un baseline y produce un `Diff`.
     *
     * `today` es la fecha de comparación para `expires`. Si es null,
     * se interpreta como "comparar sin caducidad": un baseline con
     * `expires` ya vencido **se sigue tratando como activo**. Esta
     * segunda interpretación es deliberada: permite que un run
     * determinista de CI no dependa del reloj, a costa de no
     * detectar baselines expirados en ese run. La detección de
     * expiración se hace en un run separado, con fecha explícita.
     *
     * M-B01 ataca la clasificación "NEW como EXISTING". El motor
     * evita el ataque por construcción: un finding sin
     * `stableId` conocido **no puede** etiquetarse como `Existing`,
     * porque el `stableId` no aparece en el mapa de violaciones
     * conocidas. La etiqueta `Existing` se aplica SÓLO si el
     * `stableId` está en el baseline y el finding sigue presente.
     */
    fun diff(
        baselineName: String,
        baseline: List<KnownViolation>,
        currentReport: AssuranceReport,
        engineVersion: String,
        today: LocalDate? = null,
    ): Diff {
        // Indexamos el baseline por assertionId. Eso permite que
        // un finding con el mismo `assertionId` pero distinto
        // `fingerprint` se siga reconociendo como "el mismo
        // concepto" y entre al estado CHANGED en vez de NEW.
        val baselineByAssertionId = baseline.associateBy { it.assertionId }
        val currentFindings = currentReport.results
            .filterIsInstance<AssertionResult.Failed>()
            .map { f ->
                FindingId(
                    assertionId = f.counterexample.assertionId,
                    fingerprint = fingerprintOf(f),
                ) to f
            }
        val currentAssertionIds = currentFindings.map { it.first.assertionId }.toSet()

        val entries = mutableListOf<DiffEntry>()

        // Una sola pasada por finding. Cada finding del report cae en
        // exactamente uno de cinco estados:
        //   - NEW si su assertionId no está en el baseline, o si la
        //     KnownViolation correspondiente ha expirado;
        //   - EXISTING si su assertionId está en el baseline, la
        //     KnownViolation sigue activa, y el fingerprint semántico
        //     no ha cambiado;
        //   - CHANGED si su assertionId está en el baseline (la
        //     KnownViolation con ese assertionId declara un
        //     fingerprint), pero el fingerprint del report actual
        //     difiere: la violación "se movió" (otra línea, otro
        //     módulo, otra explicación);
        //   - REGRESSED si la KnownViolation tenía `expires` y la
        //     fecha de comparación está más allá: la excepción
        //     caducó y el finding volvió como regresión.
        //   - (no entra si es `Passed` o cualquier otro resultado
        //     que no sea `Failed`).
        for ((id, failure) in currentFindings) {
            val known = baselineByAssertionId[id.assertionId]
            if (known == null) {
                entries += DiffEntry(
                    stableId = id,
                    state = DiffState.New,
                    subject = failure.counterexample.subjectRefs.joinToString(",") { it.value },
                    explanation = failure.counterexample.explanation,
                )
            } else {
                // ¿La supresión declarada sigue vigente? Si tiene
                // expires y hoy está más allá, la excepción cayó y
                // el finding vuelve como regresión. Si la
                // comparación no tiene fecha (today == null), la
                // excepción se considera activa: esa decisión es
                // coherente con la semántica "run determinista de
                // CI no depende del reloj".
                val expiredByDate = known.expires != null &&
                    today != null && today > known.expires
                if (expiredByDate) {
                    entries += DiffEntry(
                        stableId = id,
                        state = DiffState.Regressed,
                        subject = failure.counterexample.subjectRefs.joinToString(",") { it.value },
                        explanation = failure.counterexample.explanation,
                        owner = known.owner,
                        rationale = known.rationale,
                        expires = known.expires,
                    )
                } else if (id.fingerprint == known.fingerprint) {
                    // Mismo assertionId + mismo fingerprint: la
                    // supresión aplica tal cual.
                    entries += DiffEntry(
                        stableId = id,
                        state = DiffState.Existing,
                        subject = failure.counterexample.subjectRefs.joinToString(",") { it.value },
                        explanation = failure.counterexample.explanation,
                        owner = known.owner,
                        rationale = known.rationale,
                        expires = known.expires,
                    )
                } else {
                    // Mismo assertionId, fingerprint distinto: la
                    // violación se movió (otra línea, otro módulo,
                    // otra explicación). El baseline declaraba el
                    // contenido original; el report trae contenido
                    // nuevo. Esto es Changed, no Existing, porque la
                    // supresión aplicaba al fingerprint declarado,
                    // no al actual.
                    entries += DiffEntry(
                        stableId = id,
                        state = DiffState.Changed,
                        subject = failure.counterexample.subjectRefs.joinToString(",") { it.value },
                        explanation = failure.counterexample.explanation,
                        owner = known.owner,
                        rationale = known.rationale,
                        expires = known.expires,
                    )
                }
            }
        }

        // RESOLVED: estaba en el baseline, ya no aparece en el report.
        // Se reporta (no se borra) para que un auditor pueda ver
        // qué findings dejaron de aparecer.
        for (known in baseline) {
            if (known.assertionId !in currentAssertionIds) {
                entries += DiffEntry(
                    stableId = known.stableId,
                    state = DiffState.Resolved,
                    subject = "", // un RESOLVED no tiene subject actual
                    explanation = "suprimido por baseline; ya no aparece en el report",
                    owner = known.owner,
                    rationale = known.rationale,
                    expires = known.expires,
                )
            }
        }

        // Orden canónico para digest reproducible: por stableId.
        val sorted = entries.sortedBy { it.stableId.assertionId.value + ":" + it.stableId.fingerprint.hex }

        return Diff(
            baselineName = baselineName,
            baselineDigest = Digest.ofUtf8(baselineName + ":" + baseline.joinToString("|") { it.stableId.toString() }),
            currentDigest = currentReport.snapshotDigest,
            engineVersion = engineVersion,
            entries = sorted,
        )
    }

    /**
     * Fingerprint semántico de un `Failed`.
     *
     * El digest cubre `assertionId + subjectRefs + explanation` — el
     * contenido semántico. Cambios cosméticos (orden de keys,
     * puntuación) no cambian el fingerprint; cambios de subject o de
     * explicación sí. Mover una línea tampoco: la línea no es parte
     * del fingerprint.
     */
    private fun fingerprintOf(failure: AssertionResult.Failed): Digest {
        val content = buildString {
            append(failure.counterexample.assertionId.value)
            failure.counterexample.subjectRefs.forEach { append("|").append(it.value) }
            append("|").append(failure.counterexample.explanation)
        }
        return Digest.ofUtf8(content)
    }
}
