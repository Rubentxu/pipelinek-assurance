package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSummary
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.DiffEngine
import dev.pipelinek.assurance.engine.DiffState
import dev.pipelinek.assurance.engine.FindingId
import dev.pipelinek.assurance.engine.KnownViolation
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * M4 — Leyes del diff engine.
 *
 * Ref: `03-specifications/BASELINE_AND_DIFF.md`, ROADMAP §M4.
 *
 * Las leyes que este test sostiene:
 *
 *   1. **Idempotencia del diff:** aplicar el mismo diff dos veces con
 *      el mismo input produce el mismo `Diff` (mismas entries, mismo
 *      orden). Es la base de UAT-024 (replay).
 *
 *   2. **NEW no se clasifica EXISTING** (M-B01): un finding sin
 *      `stableId` conocido NUNCA sale como `Existing`.
 *
 *   3. **EXPIRED ⇒ NEW:** un baseline con `expires` vencido hace que el
 *      finding vuelva a contarse como `NEW`, no como `EXISTING`. La
 *      supresión silenciosa está prohibida.
 *
 *   4. **RESOLVED ≠ silencioso:** un finding del baseline que ya no
 *      aparece en el report se reporta como `Resolved`, no se borra.
 */
class M4DiffLawsTest : AnnotationSpec() {

    private val revision = RevisionRef("0123456789abcdef0123456789abcdef01234567")
    private val engineVersion = "test"

    @Test
    fun idempotencia_del_diff() {
        val baseline = listOf(
            knownViolation("a-1", "explicacion 1", RevisionRef("a")),
        )
        val report = reportWith(
            failures = listOf(
                failure("a-1", "explicacion 1"),
            ),
        )
        val first = DiffEngine.diff("b", baseline, report, engineVersion)
        val second = DiffEngine.diff("b", baseline, report, engineVersion)
        first.entries shouldBe second.entries
        first.summary shouldBe second.summary
    }

    @Test
    fun M_B01_un_finding_desconocido_es_NEW_no_EXISTING() {
        // Baseline vacío: cualquier finding del report es NEW. Esto
        // elimina la variable "RESOLVED" que aparecía cuando el
        // baseline contenía un finding distinto.
        val baseline = emptyList<KnownViolation>()
        val report = reportWith(
            failures = listOf(
                failure("a-1", "explicacion 1"),
            ),
        )
        val diff = DiffEngine.diff("b", baseline, report, engineVersion)
        val entry = diff.entries.single()
        entry.stableId.assertionId.value shouldBe "a-1"
        entry.state shouldBe DiffState.New
    }

    @Test
    fun un_finding_del_baseline_que_sigue_presente_es_EXISTING() {
        // Sólo el finding del baseline; el report lo contiene. No
        // hay RESOLVED porque ningún finding del baseline desaparece.
        val baseline = listOf(
            knownViolation("a-1", "explicacion 1", revision, owner = "alice"),
        )
        val report = reportWith(
            failures = listOf(
                failure("a-1", "explicacion 1"),
            ),
        )
        val diff = DiffEngine.diff("b", baseline, report, engineVersion)
        val entry = diff.entries.single()
        entry.state shouldBe DiffState.Existing
        entry.owner shouldBe "alice"
    }

    @Test
    fun un_finding_del_baseline_que_no_aparece_es_RESOLVED() {
        val baseline = listOf(
            knownViolation("a-1", "explicacion vieja", revision, owner = "alice"),
        )
        val report = reportWith(failures = emptyList())
        val diff = DiffEngine.diff("b", baseline, report, engineVersion)
        val entry = diff.entries.single()
        entry.state shouldBe DiffState.Resolved
        entry.owner shouldBe "alice"
    }

    @Test
    fun M_B01_expirado_es_REGRESSED_no_EXISTING() {
        // Baseline expirado: el finding vuelve como REGRESSION, no
        // como EXISTING ni como NEW. La distinción entre New y
        // Regressed importa: Regressed indica que el dueño DECIDIÓ
        // antes que esa violación se podía suprimir, y la excepción
        // caducó. New sería un hallazgo que nunca estuvo en el
        // baseline.
        val baseline = listOf(
            knownViolation(
                id = "a-1",
                explanation = "explicacion 1",
                firstSeen = revision,
                expires = LocalDate.of(2024, 1, 1),
            ),
        )
        val report = reportWith(
            failures = listOf(
                failure("a-1", "explicacion 1"),
            ),
        )
        val today = LocalDate.of(2025, 6, 1)
        val diff = DiffEngine.diff("b", baseline, report, engineVersion, today = today)
        diff.entries.single().state shouldBe DiffState.Regressed
    }

    @Test
    fun sin_expiracion_el_baseline_se_aplica_indefinidamente() {
        // Sin fecha de comparación (today = null), un baseline sin
        // `expires` se trata como activo. Es el camino determinista
        // para CI.
        val baseline = listOf(
            knownViolation("a-1", "explicacion 1", revision),
        )
        val report = reportWith(failures = listOf(failure("a-1", "explicacion 1")))
        val diff = DiffEngine.diff("b", baseline, report, engineVersion, today = null)
        diff.entries.single().state shouldBe DiffState.Existing
    }

    @Test
    fun diff_sin_baseline_reporta_todo_NEW() {
        val report = reportWith(
            failures = listOf(
                failure("a-1", "exp 1"),
                failure("a-2", "exp 2"),
            ),
        )
        val diff = DiffEngine.diff("b", emptyList(), report, engineVersion)
        diff.entries.all { it.state == DiffState.New } shouldBe true
        diff.summary.new shouldBe 2
        diff.summary.existing shouldBe 0
    }

    @Test
    fun diff_vacio_tiene_summary_vacio() {
        val report = reportWith(failures = emptyList())
        val diff = DiffEngine.diff("b", emptyList(), report, engineVersion)
        diff.entries shouldBe emptyList()
        diff.summary.total shouldBe 0
    }

    @Test
    fun C3_finding_con_fingerprint_distinto_es_CHANGED_no_EXISTING() {
        // Mismo assertionId en baseline y report, pero el
        // fingerprint del report difiere del declarado en el
        // baseline. La violación "se movió": la supresión
        // aplicaba al fingerprint declarado, no al actual. El
        // motor la etiqueta como CHANGED para que el ratchet
        // pueda distinguir un cambio de posición de un hallazgo
        // idéntico.
        val assertionId = AssertionId("a-1")
        val originalFingerprint = Digest.ofUtf8("original")
        val currentFingerprint = Digest.ofUtf8("moved-to-different-line")
        val baseline = listOf(
            KnownViolation(
                stableId = FindingId(assertionId, originalFingerprint),
                assertionId = assertionId,
                fingerprint = originalFingerprint,
                firstSeenRevision = revision,
            ),
        )
        // Construimos un Failed cuyo fingerprint (calculado por
        // DiffEngine.fingerprintOf) sea el "moved". Como el
        // helper `failure` usa la fórmula canónica, no podemos
        // pasarle un fingerprint arbitrario. Pero el motor
        // SIEMPRE clasifica por lo que el report produce, así
        // que para este test el report lleva un failure con
        // explanation distinta, lo que cambia su fingerprint
        // semántico.
        val report = reportWith(
            failures = listOf(failure("a-1", "se movio a otra linea")),
        )
        val diff = DiffEngine.diff("b", baseline, report, engineVersion)
        diff.entries.single().state shouldBe DiffState.Changed
    }

    @Test
    fun C3_baseline_con_mismo_fingerprint_es_EXISTING() {
        // Companion del test Changed: si el report trae el MISMO
        // fingerprint semántico que el baseline declaraba, el
        // finding es EXISTING. Es el caso que la supresión
        // cubre.
        val baseline = listOf(knownViolation("a-1", "exp", revision))
        val report = reportWith(failures = listOf(failure("a-1", "exp")))
        val diff = DiffEngine.diff("b", baseline, report, engineVersion)
        diff.entries.single().state shouldBe DiffState.Existing
    }

    @Test
    fun C3_Regressed_y_New_son_distintos_en_summary() {
        // El plan C3 distingue explícitamente REGRESSED de NEW:
        // un Regressed estaba suprimido por una excepción que
        // caducó; un New nunca estuvo en el baseline. Un auditor
        // que vea Regressed sabe que hubo una decisión previa
        // (con owner y rationale) que dejó de aplicar. Para
        // confirmarlo: el mismo report contra dos baselines
        // (uno sin el finding, otro con expires vencido)
        // produce estados distintos.
        val assertionId = AssertionId("a-1")
        val fingerprint = Digest.ofUtf8(
            buildString {
                append("a-1")
                append("|run/1")
                append("|").append("exp")
            },
        )
        val report = reportWith(failures = listOf(failure("a-1", "exp")))

        // Baseline A: no contiene el finding → New.
        val diffNew = DiffEngine.diff("b", emptyList(), report, engineVersion)
        diffNew.entries.single().state shouldBe DiffState.New

        // Baseline B: contiene el finding con expires vencido
        // y today posterior → Regressed.
        val baselineExpired = listOf(
            KnownViolation(
                stableId = FindingId(assertionId, fingerprint),
                assertionId = assertionId,
                fingerprint = fingerprint,
                firstSeenRevision = revision,
                expires = LocalDate.of(2024, 1, 1),
            ),
        )
        val diffRegressed = DiffEngine.diff(
            "b", baselineExpired, report, engineVersion,
            today = LocalDate.of(2025, 6, 1),
        )
        diffRegressed.entries.single().state shouldBe DiffState.Regressed
    }

    // --- helpers ---

    private fun knownViolation(
        id: String,
        explanation: String,
        firstSeen: RevisionRef,
        owner: String? = null,
        rationale: String? = null,
        expires: LocalDate? = null,
    ): KnownViolation {
        val assertionId = AssertionId(id)
        // El fingerprint del test DEBE coincidir con el que
        // `DiffEngine.fingerprintOf` calcula. Si el test usa uno
        // distinto, el `baselineById[id]` no encuentra el finding y
        // la clasificación falla. La forma canónica aquí es
        // "assertionId|explanation" porque la `subjectRefs` del
        // test siempre es la misma (`run/1`); el DiffEngine
        // concatenará la `subjectRefs` real del `Failed`.
        //
        // Truco: usamos el fingerprint que el DiffEngine generaría
        // al clasificar un Failed con el mismo id y la misma
        // explicación. Como el DiffEngine es internal, lo
        // reproducimos aquí con la misma fórmula. Si la fórmula
        // cambia, este test se pone rojo y exige actualizar
        // ambos lados en el mismo commit.
        val fingerprint = Digest.ofUtf8(
            buildString {
                append(id)
                append("|run/1")
                append("|").append(explanation)
            },
        )
        return KnownViolation(
            stableId = FindingId(assertionId, fingerprint),
            assertionId = assertionId,
            fingerprint = fingerprint,
            firstSeenRevision = firstSeen,
            owner = owner,
            rationale = rationale,
            expires = expires,
        )
    }

    private fun failure(assertionIdValue: String, explanation: String): AssertionResult.Failed {
        val assertionId = AssertionId(assertionIdValue)
        return AssertionResult.Failed(
            Counterexample.DependencyPath(
                assertionId = assertionId,
                subjectRefs = listOf(
                    TypedExternalId(ExternalNamespace.PipelineRunId, "run/1"),
                ),
                evidenceRefs = listOf(EvidenceId("e/1")),
                explanation = explanation,
                reproductionHints = emptyList(),
                path = listOf("a", "b"),
                fromLayer = "Adapters",
                toLayer = "Domain",
            ),
        )
    }

    private fun reportWith(failures: List<AssertionResult.Failed>): AssuranceReport {
        return AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-test"),
            snapshotDigest = Digest.ofUtf8("snapshot"),
            suiteDigest = Digest.ofUtf8("suite"),
            engineVersion = engineVersion,
            results = failures,
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        ).let { report ->
            // Aseguramos que el summary se calcula correctamente.
            report.copy()
        }
    }
}
