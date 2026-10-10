package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.Diff
import dev.pipelinek.assurance.engine.DiffEntry
import dev.pipelinek.assurance.engine.DiffState
import dev.pipelinek.assurance.engine.FindingId
import dev.pipelinek.assurance.engine.RatchetDecision
import dev.pipelinek.assurance.engine.RatchetEngine
import dev.pipelinek.assurance.engine.RatchetException
import dev.pipelinek.assurance.engine.RatchetPolicy
import dev.pipelinek.assurance.engine.RatchetViolation
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * C4 (Bloque C) — Tests del motor de ratchets.
 *
 * Ref: `odd/tasks/block-C-static-real.md` §C4.
 *
 * El plan C4 dice:
 *   - `noNewViolations`, `noNewCycles`, conteo que no aumenta.
 *   - Políticas de mutation strength sobre evidencia válida.
 *   - Complejidad sólo con protocolo de medición estable.
 *   - Excepciones con owner, rationale, expiry.
 *
 * Estos tests verifican:
 *   1. `forbidNew` bloquea entradas `New`.
 *   2. `forbidRegressed` bloquea entradas `Regressed`.
 *   3. `forbidChanged` bloquea entradas `Changed`.
 *   4. `noNewCycles` bloquea hallazgos en estado `New` cuyo
 *      counterexample es `Cycle` (vía `isCycleByStableId`).
 *   5. `maxUnresolvedCount` bloquea cuando New+Regressed+Changed
 *      supera el umbral.
 *   6. `maxCyclesCount` bloquea cuando new cycles supera el
 *      umbral.
 *   7. Las excepciones con `expires` vigente exoneran al
 *      `stableId` listado.
 *   8. Las excepciones con `expires` vencido NO exoneran.
 *   9. Una política vacía permite cualquier diff.
 *  10. `Blocked` exige al menos una razón.
 */
class C4RatchetEngineTest : AnnotationSpec() {

    @Test
    fun forbidNew_bloquea_diffs_con_entradas_New() {
        val diff = diffWith(entries = listOf(entry(DiffState.New, "a-1", "f-1")))
        val policy = RatchetPolicy(forbidNew = true)
        val decision = RatchetEngine.evaluate(policy, diff)
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.single().kind shouldBe RatchetViolation.New
    }

    @Test
    fun forbidRegressed_bloquea_diffs_con_entradas_Regressed() {
        val diff = diffWith(entries = listOf(entry(DiffState.Regressed, "a-1", "f-1")))
        val policy = RatchetPolicy(forbidRegressed = true)
        val decision = RatchetEngine.evaluate(policy, diff)
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.single().kind shouldBe RatchetViolation.Regressed
    }

    @Test
    fun forbidChanged_bloquea_diffs_con_entradas_Changed() {
        val diff = diffWith(entries = listOf(entry(DiffState.Changed, "a-1", "f-1")))
        val policy = RatchetPolicy(forbidChanged = true)
        val decision = RatchetEngine.evaluate(policy, diff)
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.single().kind shouldBe RatchetViolation.Changed
    }

    @Test
    fun noNewCycles_bloquea_solo_New_que_son_ciclos() {
        // Tres entradas New: una con isCycle=true, dos sin.
        // La política noNewCycles debe bloquear SOLO la del
        // ciclo, no las otras dos.
        val cycleId = FindingId(AssertionId("a-cycle"), Digest.ofUtf8("c1"))
        val otherId = FindingId(AssertionId("a-other"), Digest.ofUtf8("o1"))
        val diff = diffWith(
            entries = listOf(
                entry(DiffState.New, "a-cycle", "c1"),
                entry(DiffState.New, "a-other", "o1"),
            ),
        )
        val policy = RatchetPolicy(noNewCycles = true)
        val decision = RatchetEngine.evaluate(
            policy = policy,
            diff = diff,
            isCycleByStableId = { it == cycleId },
        )
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        val cycleReason = blocked.reasons.single { it.kind == RatchetViolation.Cycle }
        cycleReason.findings.map { it.stableId.assertionId.value } shouldBe listOf("a-cycle")
        // El otro New no debe aparecer en la razón Cycle.
        cycleReason.findings.any { it.stableId == otherId } shouldBe false
    }

    @Test
    fun maxUnresolvedCount_bloquea_cuando_excede_umbral() {
        // 3 New; umbral 1.
        val diff = diffWith(
            entries = listOf(
                entry(DiffState.New, "a-1", "f-1"),
                entry(DiffState.New, "a-2", "f-2"),
                entry(DiffState.New, "a-3", "f-3"),
            ),
        )
        val policy = RatchetPolicy(maxUnresolvedCount = 1)
        val decision = RatchetEngine.evaluate(policy, diff)
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.any { it.kind == RatchetViolation.UnresolvedCount } shouldBe true
    }

    @Test
    fun maxUnresolvedCount_acepta_en_el_umbral() {
        // 1 New; umbral 1: NO excede.
        val diff = diffWith(entries = listOf(entry(DiffState.New, "a-1", "f-1")))
        val policy = RatchetPolicy(maxUnresolvedCount = 1)
        RatchetEngine.evaluate(policy, diff) shouldBe RatchetDecision.Allowed
    }

    @Test
    fun maxCyclesCount_bloquea_cuando_excede_umbral() {
        val cycle1 = FindingId(AssertionId("c1"), Digest.ofUtf8("cf-1"))
        val cycle2 = FindingId(AssertionId("c2"), Digest.ofUtf8("cf-2"))
        val diff = diffWith(
            entries = listOf(
                entry(DiffState.New, "c1", "cf-1"),
                entry(DiffState.New, "c2", "cf-2"),
            ),
        )
        val policy = RatchetPolicy(maxCyclesCount = 1)
        val decision = RatchetEngine.evaluate(
            policy = policy,
            diff = diff,
            isCycleByStableId = { it == cycle1 || it == cycle2 },
        )
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.any { it.kind == RatchetViolation.CyclesCount } shouldBe true
    }

    @Test
    fun excepcion_vigente_exonera_al_stableId() {
        // forbidNew + 1 New con excepción vigente para ese
        // stableId: la política debe permitir.
        val id = FindingId(AssertionId("a-1"), Digest.ofUtf8("f-1"))
        val diff = diffWith(entries = listOf(entry(DiffState.New, "a-1", "f-1")))
        val policy = RatchetPolicy(
            forbidNew = true,
            exceptions = listOf(
                RatchetException(
                    stableIds = setOf(id),
                    owner = "platform",
                    rationale = "tracked in JIRA-42",
                    expires = LocalDate.of(2030, 1, 1),
                ),
            ),
        )
        RatchetEngine.evaluate(
            policy = policy,
            diff = diff,
            today = LocalDate.of(2026, 6, 1),
        ) shouldBe RatchetDecision.Allowed
    }

    @Test
    fun excepcion_caducada_NO_exonera() {
        val id = FindingId(AssertionId("a-1"), Digest.ofUtf8("f-1"))
        val diff = diffWith(entries = listOf(entry(DiffState.New, "a-1", "f-1")))
        val policy = RatchetPolicy(
            forbidNew = true,
            exceptions = listOf(
                RatchetException(
                    stableIds = setOf(id),
                    owner = "platform",
                    rationale = "tracked in JIRA-42",
                    expires = LocalDate.of(2024, 1, 1),
                ),
            ),
        )
        val decision = RatchetEngine.evaluate(
            policy = policy,
            diff = diff,
            today = LocalDate.of(2026, 6, 1),
        )
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.single().kind shouldBe RatchetViolation.New
    }

    @Test
    fun politica_vacia_permite_cualquier_diff() {
        val diff = diffWith(
            entries = listOf(
                entry(DiffState.New, "a-1", "f-1"),
                entry(DiffState.Regressed, "a-2", "f-2"),
                entry(DiffState.Changed, "a-3", "f-3"),
            ),
        )
        RatchetEngine.evaluate(RatchetPolicy(), diff) shouldBe RatchetDecision.Allowed
    }

    @Test
    fun Blocked_exige_al_menos_una_razon() {
        // Si por composición una razón queda vacía, el
        // constructor exige al menos una entrada. Este test
        // verifica el invariante: si el motor genera
        // Blocked, tiene razones.
        val diff = diffWith(entries = listOf(entry(DiffState.New, "a-1", "f-1")))
        val policy = RatchetPolicy(forbidNew = true)
        val decision = RatchetEngine.evaluate(policy, diff)
        val blocked = decision.shouldBeInstanceOf<RatchetDecision.Blocked>()
        blocked.reasons.isNotEmpty() shouldBe true
    }

    @Test
    fun umbrales_negativos_se_rechazan_en_construccion() {
        val ex = runCatching {
            RatchetPolicy(maxUnresolvedCount = -1)
        }.exceptionOrNull()
        ex.shouldBeInstanceOf<IllegalArgumentException>()
    }

    // --- helpers ---

    private fun entry(
        state: DiffState,
        assertionIdValue: String,
        fingerprintHex: String,
    ): DiffEntry = DiffEntry(
        stableId = FindingId(AssertionId(assertionIdValue), Digest.ofUtf8(fingerprintHex)),
        state = state,
        subject = "module:foo",
        explanation = "explicacion",
    )

    private fun diffWith(entries: List<DiffEntry>): Diff = Diff(
        baselineName = "b",
        baselineDigest = Digest.ofUtf8("baseline"),
        currentDigest = Digest.ofUtf8("current"),
        engineVersion = "test",
        entries = entries,
    )
}
