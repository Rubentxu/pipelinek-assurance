package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.ArtifactRef
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.EvaluationFailure
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.UnsupportedReason
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * M0 — Digest de report.
 *
 * Cierra el último hueco del kernel de determinismo: snapshot, suite y report
 * tienen los tres digest canónico. Los tres, para que un report sea
 * reproducible entre máquinas (Exit de M9), no sólo un snapshot.
 */
class ReportDigestTest : AnnotationSpec() {

    private val snapDigest = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("snapshot")
    private val suiteDigest = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("suite")

    private fun report(
        results: List<AssertionResult>,
        gaps: List<EvidenceGap> = emptyList(),
        artifacts: List<ArtifactRef> = emptyList(),
        engineVersion: String = "0.1.0",
        evaluationId: String = "eval-1",
    ) = AssuranceReport(
        evaluationId = AssuranceEvaluationId(evaluationId),
        snapshotDigest = snapDigest,
        suiteDigest = suiteDigest,
        engineVersion = engineVersion,
        results = results,
        gaps = gaps,
        artifacts = artifacts,
        correlations = emptyList(),
    )

    private fun passed(id: String) = AssertionResult.Passed(
        ProofRef("snap-001", listOf(EvidenceId("synthetic/a/depends-on/1")), AssertionId(id)),
    )

    @Test
    fun report_digest_is_stable_across_repeated_calls() {
        val r = report(listOf(passed("a"), passed("b")))

        CanonicalEncoder.digestReport(r) shouldBe CanonicalEncoder.digestReport(r)
    }

    @Test
    fun result_order_does_not_change_the_digest() {
        val a = report(listOf(passed("a"), passed("b"), passed("c")))
        val b = report(listOf(passed("c"), passed("a"), passed("b")))

        CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)
    }

    @Test
    fun evaluation_id_does_not_change_the_digest() {
        // El `evaluationId` deriva del resto: meterlo duplicaria informacion.
        val a = report(listOf(passed("a")), evaluationId = "eval-1")
        val b = report(listOf(passed("a")), evaluationId = "eval-999")

        CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)
    }

    @Test
    fun engine_version_changes_the_digest() {
        // Si dos runners usan engines distintos, sus reports no deben ser
        // comparables. El digest tiene que reflejarlo.
        val a = report(listOf(passed("a")), engineVersion = "0.1.0")
        val b = report(listOf(passed("a")), engineVersion = "0.2.0")

        CanonicalEncoder.digestReport(a) shouldNotBe CanonicalEncoder.digestReport(b)
    }

    @Test
    fun input_digests_change_the_digest() {
        val base = report(listOf(passed("a")))
        val otherSnapshot = report(listOf(passed("a"))).copy(snapshotDigest = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("otro"))

        CanonicalEncoder.digestReport(base) shouldNotBe CanonicalEncoder.digestReport(otherSnapshot)
    }

    @Test
    fun changing_a_verdict_changes_the_digest() {
        // Pass -> Inconclusive es la diferencia que más importa: es como el
        // gate distingue "ok" de "no lo sé".
        val passing = report(listOf(passed("a")))
        val inconclusive = report(
            listOf(
                AssertionResult.Inconclusive(
                    listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
                ),
            ),
        )

        CanonicalEncoder.digestReport(passing) shouldNotBe CanonicalEncoder.digestReport(inconclusive)
    }

    @Test
    fun a_failed_and_a_passed_result_are_distinguishable() {
        val passing = report(listOf(passed("a")))
        val failing = report(
            listOf(
                AssertionResult.Failed(
                    Counterexample.DependencyPath(
                        assertionId = AssertionId("a"),
                        subjectRefs = emptyList(),
                        evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
                        explanation = "el domain depende de un adapter",
                        reproductionHints = listOf("gradle :core:dependencies"),
                        path = listOf("core", "adapter"),
                        fromLayer = "domain",
                        toLayer = "adapter",
                    ),
                ),
            ),
        )

        CanonicalEncoder.digestReport(passing) shouldNotBe CanonicalEncoder.digestReport(failing)
    }

    @Test
    fun every_result_kind_is_encodable_and_distinct() {
        val results = listOf(
            passed("a"),
            AssertionResult.Inconclusive(
                listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
            ),
            AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("no-edge-between-sets")),
            AssertionResult.Error(EvaluationFailure("project", "boom", "cause")),
        )
        val digests = results.map { CanonicalEncoder.digestReport(report(listOf(it))) }

        // Cuatro veredictos, cuatro digests: si dos coincidieran, el gate no
        // podría distinguirlos.
        digests.distinct().size shouldBe 4
    }

    @Test
    fun gap_order_does_not_change_the_report_digest() {
        val gaps = listOf(
            EvidenceGap("Zeta", EvidenceGap.GapReason.Unknown),
            EvidenceGap("Alpha", EvidenceGap.GapReason.Lost),
        )
        val a = report(listOf(passed("a")), gaps = gaps)
        val b = report(listOf(passed("a")), gaps = gaps.reversed())

        CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)
    }

    @Test
    fun artifact_order_does_not_change_the_report_digest() {
        val digestA = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("a")
        val digestB = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("b")
        val arts = listOf(
            ArtifactRef(digestA, "application/cbor", "deps"),
            ArtifactRef(digestB, "application/cbor", "symbols"),
        )
        val a = report(listOf(passed("a")), artifacts = arts)
        val b = report(listOf(passed("a")), artifacts = arts.reversed())

        CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)
    }

    @Test
    fun summary_counts_are_derived_and_not_double_counted_in_the_digest() {
        // El `summary` es una vista calculada del mismo contenido. Si el digest
        // lo incluyera, cambiar el conteo sin cambiar los resultados rompería la
        // reproducibilidad.
        val r = report(listOf(passed("a"), passed("b")))

        r.summary.passed shouldBe 2
        CanonicalEncoder.digestReport(r) shouldBe
            CanonicalEncoder.digestReport(r.copy(results = listOf(passed("a"), passed("b"))))
    }

    @Test
    fun report_digest_is_lowercase_hex_of_64_chars() {
        val d = CanonicalEncoder.digestReport(report(listOf(passed("a"))))

        d.hex.length shouldBe 64
        d.hex.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    @Test
    fun two_evaluations_of_the_same_evidence_agree() {
        // El caso que importa en produccion: el mismo snapshot y la misma
        // suite, evaluados dos veces, producen el mismo digest de report.
        val snapshot = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/a/depends-on/1")))
        val suite = EvidenceFixtures.suite()

        fun evaluate() = AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-${snapshot.id.value}-${suite.suiteId.value}"),
            snapshotDigest = CanonicalEncoder.digestSnapshot(snapshot),
            suiteDigest = CanonicalEncoder.digestSuite(suite),
            engineVersion = "0.1.0",
            results = listOf(passed("a")),
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )

        CanonicalEncoder.digestReport(evaluate()) shouldBe CanonicalEncoder.digestReport(evaluate())
    }

    @Test
    fun counterexample_variant_fields_enter_the_digest() {
        // Regresión: el encoder NO codificaba los campos propios de cada
        // variante de contraejemplo, sólo los comunes de la interfaz. Dos
        // DependencyPath con rutas distintas daban el mismo digest, así que
        // el artefacto firmado no distinguía un fallo real de otro.
        val base = Counterexample.DependencyPath(
            assertionId = AssertionId("a"),
            subjectRefs = emptyList(),
            evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
            explanation = "el domain depende de un adapter",
            reproductionHints = listOf("gradle :core:dependencies"),
            path = listOf("core", "adapter"),
            fromLayer = "domain",
            toLayer = "adapter",
        )

        fun failed(c: Counterexample) = report(listOf(AssertionResult.Failed(c)))

        CanonicalEncoder.digestReport(failed(base)) shouldBe CanonicalEncoder.digestReport(failed(base))

        // El path es el corazón del contraejemplo: si no entra al digest, el
        // artefacto no dice qué ruta se rompió.
        CanonicalEncoder.digestReport(failed(base)) shouldNotBe
            CanonicalEncoder.digestReport(failed(base.copy(path = listOf("core", "infra"))))
        CanonicalEncoder.digestReport(failed(base)) shouldNotBe
            CanonicalEncoder.digestReport(failed(base.copy(fromLayer = "adapter")))
        CanonicalEncoder.digestReport(failed(base)) shouldNotBe
            CanonicalEncoder.digestReport(failed(base.copy(toLayer = "application")))
    }

    @Test
    fun all_counterexample_variants_are_distinguishable_from_each_other() {
        // Seis variantes, cada una con campos propios. Si dos coincidieran, dos
        // informes semánticamente distintos serían indistinguibles.
        val aid = AssertionId("a")
        val evidence = listOf(EvidenceId("synthetic/a/depends-on/1"))
        fun variant(c: Counterexample) = CanonicalEncoder.digestReport(report(listOf(AssertionResult.Failed(c))))

        val digests = listOf(
            variant(
                Counterexample.DependencyPath(
                    assertionId = aid, subjectRefs = emptyList(), evidenceRefs = evidence,
                    explanation = "x", reproductionHints = listOf("h"),
                    path = listOf("a"), fromLayer = "l1", toLayer = "l2",
                ),
            ),
            variant(
                Counterexample.Cycle(
                    assertionId = aid, subjectRefs = emptyList(), evidenceRefs = evidence,
                    explanation = "x", reproductionHints = listOf("h"),
                    cycle = listOf("a"),
                ),
            ),
            variant(
                Counterexample.CausalSlice(
                    assertionId = aid, subjectRefs = emptyList(), evidenceRefs = evidence,
                    explanation = "x", reproductionHints = listOf("h"),
                    invocationChain = listOf("a"),
                ),
            ),
            variant(
                Counterexample.Mutation(
                    assertionId = aid, subjectRefs = emptyList(), evidenceRefs = evidence,
                    explanation = "x", reproductionHints = listOf("h"),
                    mutatedSymbol = "a", killedBy = null,
                ),
            ),
            variant(
                Counterexample.MissingTrace(
                    assertionId = aid, subjectRefs = emptyList(), evidenceRefs = evidence,
                    explanation = "x", reproductionHints = listOf("h"),
                    missingSpanFor = "a", expectedPropagation = "p",
                ),
            ),
            variant(
                Counterexample.BaselineRegression(
                    assertionId = aid, subjectRefs = emptyList(), evidenceRefs = evidence,
                    explanation = "x", reproductionHints = listOf("h"),
                    stableId = "a", state = dev.pipelinek.assurance.engine.DiffState.Regressed,
                ),
            ),
        )

        digests.distinct().size shouldBe 6
    }

    @Test
    fun killed_by_null_and_a_value_are_distinguishable() {
        // `killedBy = null` significa "el mutante sobrevivió"; un valor significa
        // "lo mató este test". Son veredictos opuestos y no pueden compartir digest.
        val base = Counterexample.Mutation(
            assertionId = AssertionId("a"),
            subjectRefs = emptyList(),
            evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
            explanation = "mutante",
            reproductionHints = listOf("h"),
            mutatedSymbol = "core::run",
            killedBy = null,
        )
        fun variant(c: Counterexample) = CanonicalEncoder.digestReport(report(listOf(AssertionResult.Failed(c))))

        variant(base) shouldNotBe variant(base.copy(killedBy = "M-R01Test"))
    }

    @Test
    fun unsupported_reasons_of_the_same_kind_are_distinguishable() {
        // Dos `Unsupported` con razones distintas deben diferir. Y el orden entre
        // ellos no puede depender del orden de entrada: antes la clave de orden
        // era la cadena vacía y el `sort` estable colaba el orden de entrada en
        // el digest.
        val a = AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("op-a"))
        val b = AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("op-b"))
        val c = AssertionResult.Unsupported(UnsupportedReason.UnknownLensKind("lens"))

        CanonicalEncoder.digestReport(report(listOf(a))) shouldNotBe CanonicalEncoder.digestReport(report(listOf(b)))
        CanonicalEncoder.digestReport(report(listOf(a))) shouldNotBe CanonicalEncoder.digestReport(report(listOf(c)))

        CanonicalEncoder.digestReport(report(listOf(a, b, c))) shouldBe
            CanonicalEncoder.digestReport(report(listOf(c, a, b)))
        CanonicalEncoder.digestReport(report(listOf(a, b, c))) shouldBe
            CanonicalEncoder.digestReport(report(listOf(b, c, a)))
    }

    @Test
    fun reproduction_hints_are_treated_as_a_set() {
        // El motor puede repetir un hint sin que signifique nada. Si el digest
        // cambiase, dos runs idénticos darían artefactos distintos.
        val base = Counterexample.Mutation(
            assertionId = AssertionId("a"),
            subjectRefs = emptyList(),
            evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
            explanation = "mutante vivo",
            reproductionHints = listOf("b", "a"),
            mutatedSymbol = "core::run",
            killedBy = null,
        )
        fun variant(c: Counterexample) = CanonicalEncoder.digestReport(report(listOf(AssertionResult.Failed(c))))

        variant(base.copy(reproductionHints = listOf("a", "b"))) shouldBe variant(base)
        variant(base.copy(reproductionHints = listOf("b", "a"))) shouldBe variant(base)
        variant(base.copy(reproductionHints = listOf("b", "a", "b"))) shouldBe variant(base)
    }

    @Test
    fun result_order_does_not_leak_into_the_digest_across_kinds() {
        // Combinación de todos los veredictos: el orden de entrada no puede
        // colarse por el `sort` estable cuando dos resultados comparten tipo.
        val all = listOf(
            passed("a"),
            AssertionResult.Failed(
                Counterexample.Cycle(
                    assertionId = AssertionId("b"),
                    subjectRefs = emptyList(),
                    evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
                    explanation = "ciclo",
                    reproductionHints = listOf("h"),
                    cycle = listOf("x", "y", "x"),
                ),
            ),
            AssertionResult.Inconclusive(
                listOf(EvidenceGap("RuntimeInvocations", EvidenceGap.GapReason.Lost, "sin trace")),
            ),
            AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("op")),
            AssertionResult.Error(EvaluationFailure("lens", "explotó")),
            AssertionResult.Unsupported(UnsupportedReason.UnknownLensKind("otra")),
        )

        CanonicalEncoder.digestReport(report(all)) shouldBe CanonicalEncoder.digestReport(report(all.reversed()))
    }
}
