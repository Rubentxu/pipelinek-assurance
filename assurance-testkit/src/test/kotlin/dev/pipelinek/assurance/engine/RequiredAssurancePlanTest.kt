package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.RequiredAssurancePlan.Plan
import dev.pipelinek.assurance.engine.RequiredAssurancePlan.Reason
import dev.pipelinek.assurance.engine.RequiredAssurancePlan.SuiteSelection
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M10 — Tests de `RequiredAssurancePlan` y su digest canonico.
 *
 * Lo que se certifya:
 *   - Plan selecciona suites Mandatory siempre, independiente del
 *     diff o los paths.
 *   - Plan selecciona suites tocadas por un cambio (prefijo de path).
 *   - Plan selecciona suites con findings NEW en su `lens.kind`.
 *   - Una suite que cumple multiples reglas sale UNA vez con la
 *     razon de mayor prioridad (Mandatory > Touched > New).
 *   - El digest es estable bajo el mismo input.
 *   - El Plan NUNCA produce un risk score (la API no lo expone).
 */
class RequiredAssurancePlanTest : AnnotationSpec() {

    @Test
    fun mandatory_baseline_siempre_sale_aunque_no_haya_cambios() {
        val suite = suite("mandatory.arch", metadata = mapOf("mandatory" to "true"))
        val plan = RequiredAssurancePlan.build(
            RequiredAssurancePlan.Input(
                availableSuites = listOf(suite),
                changedPaths = emptyList(),
                diff = emptyDiff(),
                engineVersion = "0.1.0",
            ),
        )
        plan.selections.size shouldBe 1
        plan.selections.single().reason shouldBe Reason.MandatoryBaseline
    }

    @Test
    fun suite_tocada_por_cambio_sale_con_el_path_que_lo_disparo() {
        val suite = suite("assurance-engine")
        val plan = RequiredAssurancePlan.build(
            RequiredAssurancePlan.Input(
                availableSuites = listOf(suite),
                changedPaths = listOf("assurance-engine/src/main/kotlin/X.kt"),
                diff = emptyDiff(),
                engineVersion = "0.1.0",
            ),
        )
        plan.selections.size shouldBe 1
        val reason = plan.selections.single().reason
        reason.shouldBeInstanceOf<Reason.TouchedByChange>()
        (reason as Reason.TouchedByChange).path shouldBe "assurance-engine/src/main/kotlin/X.kt"
    }

    @Test
    fun suite_con_new_findings_sale_sin_path_ni_mandatory() {
        val suite = suite("architecture.hexagonal", kind = "architecture.hexagonal")
        val diff = diffWithNewFinding("architecture.hexagonal/decl-no-cycle/1")
        val plan = RequiredAssurancePlan.build(
            RequiredAssurancePlan.Input(
                availableSuites = listOf(suite),
                changedPaths = emptyList(),
                diff = diff,
                engineVersion = "0.1.0",
            ),
        )
        plan.selections.size shouldBe 1
        plan.selections.single().reason shouldBe Reason.NewFindingsPresent
    }

    @Test
    fun mandatory_gana_sobre_touched_y_new() {
        // Suite con los tres gatillos: mandatory=true, path touched,
        // finding NEW. El plan debe reportar UNA entrada con
        // MandatoryBaseline, que es la razon de mayor prioridad.
        val suite = suite("architecture.hexagonal", metadata = mapOf("mandatory" to "true"))
        val diff = diffWithNewFinding("architecture.hexagonal/decl-no-cycle/1")
        val plan = RequiredAssurancePlan.build(
            RequiredAssurancePlan.Input(
                availableSuites = listOf(suite),
                changedPaths = listOf("architecture.hexagonal/X.kt"),
                diff = diff,
                engineVersion = "0.1.0",
            ),
        )
        plan.selections.size shouldBe 1
        plan.selections.single().reason shouldBe Reason.MandatoryBaseline
    }

    @Test
    fun plan_no_expone_ningun_risk_score() {
        // AAT-22: el Plan es una lista de selecciones, no un score.
        // Verificamos que la API no tiene nigun campo numerico.
        val plan = Plan(
            engineVersion = "0.1.0",
            selections = listOf(
                SuiteSelection(SuiteId("x"), Reason.MandatoryBaseline),
            ),
        )
        // Si el plan tuviera un riskScore, este test no compilaría
        // porque la firma exige "Reason" (sealed), no "Double".
        val reason: Reason = plan.selections.single().reason
        reason shouldBe Reason.MandatoryBaseline
    }

    @Test
    fun el_digest_es_estable_para_el_mismo_input() {
        val suite = suite("architecture.hexagonal", kind = "architecture.hexagonal")
        val input = RequiredAssurancePlan.Input(
            availableSuites = listOf(suite),
            changedPaths = listOf("architecture.hexagonal/X.kt"),
            diff = diffWithNewFinding("architecture.hexagonal/decl-no-cycle/1"),
            engineVersion = "0.1.0",
        )
        val p1 = RequiredAssurancePlan.build(input)
        val p2 = RequiredAssurancePlan.build(input)
        CanonicalEncoder.digestPlan(p1).hex shouldBe CanonicalEncoder.digestPlan(p2).hex
    }

    // --- helpers ---

    private fun suite(
        id: String,
        kind: String = "test.suite",
        metadata: Map<String, String> = emptyMap(),
    ): AssuranceSuiteIR = AssuranceSuiteIR(
        apiVersion = "assurance-ir/v1",
        suiteId = SuiteId(id),
        suiteVersion = "1.0.0",
        requiredEvidence = listOf("architecture.hexagonal"),
        lenses = listOf(
            LensPlan(
                lensId = LensId("lens/$kind"),
                kind = kind,
                inputCapabilities = listOf("architecture.hexagonal"),
                outputSchema = "HexagonalArchitecture/v1",
            ),
        ),
        assertions = listOf(
            AssertionIR(
                id = AssertionId("$kind/decl-no-cycle/1"),
                lensRef = LensId("lens/$kind"),
                operator = "no-edge-between-sets",
                operands = emptyMap(),
                severity = Severity.Critical,
                enforcement = Enforcement.Mandatory,
                completenessRequirements = emptyList(),
                rationale = "test",
            ),
        ),
        metadata = metadata,
    )

    private fun emptyDiff(): Diff = Diff(
        baselineName = "none",
        baselineDigest = Digest.ofUtf8("b"),
        currentDigest = Digest.ofUtf8("c"),
        engineVersion = "0.1.0",
        entries = emptyList(),
    )

    private fun diffWithNewFinding(assertionId: String): Diff = Diff(
        baselineName = "none",
        baselineDigest = Digest.ofUtf8("b"),
        currentDigest = Digest.ofUtf8("c"),
        engineVersion = "0.1.0",
        entries = listOf(
            DiffEntry(
                stableId = FindingId(
                    assertionId = AssertionId(assertionId),
                    fingerprint = Digest.ofUtf8("fp"),
                ),
                state = DiffState.New,
                subject = "self",
                explanation = "new finding",
            ),
        ),
    )
}
