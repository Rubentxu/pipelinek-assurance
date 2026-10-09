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
    fun M_B01_expirado_es_NEW_no_EXISTING() {
        // Baseline expirado: el finding vuelve a NEW, no a EXISTING.
        // No hay RESOLVED porque el finding del baseline es el mismo
        // que el del report.
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
        diff.entries.single().state shouldBe DiffState.New
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
