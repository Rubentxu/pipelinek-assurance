package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * UAT-002 — Missing evidence is not success.
 *
 * "Eliminar ModuleDependencies produce Inconclusive/FAIL segun completeness
 * policy; jamas PASS."
 *
 * Es la ley mas importante del producto: `no evidence != PASS`
 * (`02-architecture/FUNCTIONAL_CORE.md`, ADR-004, D7). Si esto se rompe, todo
 * el argumento de "determinista y reproducible" es humo.
 */
class NoEvidenceIsNotPassTest : AnnotationSpec() {

    /** Snapshot sin la capability requerida, con su gap declarado. */
    private fun emptySnapshot(): EvidenceSnapshot = EvidenceFixtures.snapshot(
        items = emptyList(),
        sources = listOf(
            EvidenceFixtures.manifest(
                producerId = "cognicode",
                produced = listOf("ModuleDependencies"),
                completenessByCapability = mapOf(
                    "ModuleDependencies" to Completeness.Partial(
                        listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
                    ),
                ),
            ),
        ),
        gaps = listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
    )

    /** Lens que exige `ModuleDependencies`. */
    private val needsModuleDependencies = AssuranceLens<EvidenceSnapshot, List<EvidenceItem>> { input ->
        val facts = input.items.filter { it.provenance.capability == "ModuleDependencies" }
        if (facts.isEmpty()) {
            ProjectionResult.ProjectionFailed(
                ProjectionFailureReason.MissingCapability("ModuleDependencies"),
                listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
            )
        } else {
            ProjectionResult.Projected(facts)
        }
    }

    /** Assertion que siempre "pasa" si llega a ejecutarse. */
    private val alwaysPasses = AssuranceAssertion<List<EvidenceItem>> { facts ->
        AssertionResult.Passed(
            ProofRef("snap-001", facts.map { it.id }, AssertionId("always-passes")),
        )
    }

    @Test
    fun UAT_002_projection_failure_never_yields_passed() {
        val snapshot = emptySnapshot()

        val result = AssuranceEngine.evaluate(
            AssuranceEngine.project(snapshot, needsModuleDependencies),
            alwaysPasses,
        )

        // Una assertion que SIEMPRE pasa no puede pasar sin evidencia.
        result shouldBe AssertionResult.Inconclusive(
            listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
        )
        (result is AssertionResult.Passed) shouldBe false
    }

    @Test
    fun UAT_002_with_evidence_the_same_assertion_passes() {
        // Control positivo: el mismo par (lens, assertion) SI pasa cuando la
        // evidencia existe. Sin esto, el test anterior podria estar verde por
        // un fallo de wiring y no por la ley.
        val snapshot = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/a/depends-on/1")))

        val result = AssuranceEngine.evaluate(
            AssuranceEngine.project(snapshot, needsModuleDependencies),
            alwaysPasses,
        )

        result shouldBe AssertionResult.Passed(
            ProofRef("snap-001", listOf(dev.pipelinek.assurance.domain.evidence.EvidenceId("synthetic/a/depends-on/1")), AssertionId("always-passes")),
        )
    }

    @Test
    fun AAT_20_proof_ref_cannot_be_constructed_empty() {
        // Defensa en profundidad: aunque alguien salte la lens, un `Passed` sin
        // evidencia no se puede construir.
        shouldThrow<IllegalArgumentException> {
            ProofRef("snap-001", emptyList(), AssertionId("a"))
        }
    }

    @Test
    fun AAT_20_proof_ref_from_returns_null_without_evidence() {
        val snapshot = emptySnapshot()
        val proof = ProofRef.from(snapshot, AssertionId("a")) { true }

        proof shouldBe null
    }

    @Test
    fun AAT_20_proof_ref_from_succeeds_with_evidence() {
        val snapshot = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/a/fact/1")))
        val proof = ProofRef.from(snapshot, AssertionId("a")) { true }

        proof!!.evidenceIds.size shouldBe 1
        proof.snapshotId shouldBe "snap-001"
    }

    @Test
    fun inconclusive_requires_at_least_one_gap() {
        shouldThrow<IllegalArgumentException> {
            AssertionResult.Inconclusive(emptyList())
        }
    }

    @Test
    fun empty_snapshot_is_partial_not_complete() {
        val snapshot = emptySnapshot()

        snapshot.overallCompleteness shouldBe Completeness.Partial(
            listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
        )
    }

    @Test
    fun missing_required_evidence_becomes_a_gap_in_the_report() {
        // AAT-20 a nivel suite: una capability requerida que no aparece en los
        // items se traduce a gap, nunca se ignora en silencio.
        val snapshot = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/a/depends-on/1")))
        val suite = EvidenceFixtures.suite(requiredEvidence = listOf("ModuleDependencies", "SymbolGraph"))

        val report = AssuranceEngine.evaluateSuite(
            snapshot = snapshot,
            suite = suite,
            runtime = EvidenceFixtures.frozenRuntime(),
            digestOf = { dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("suite") },
            snapshotDigest = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("snap"),
            assertionsById = mapOf(AssertionId("a") to alwaysPasses),
        )

        report.gaps.map { it.capability } shouldBe listOf("SymbolGraph")
        report.summary.total shouldBe report.results.size
    }
}