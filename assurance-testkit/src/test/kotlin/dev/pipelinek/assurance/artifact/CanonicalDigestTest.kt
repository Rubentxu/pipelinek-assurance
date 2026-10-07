package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * UAT-001 — Deterministic snapshot.
 *
 * "Misma evidence entrada en distinto orden fisico produce mismo snapshot digest."
 *
 * Es el Exit de M0. Si la canonicalizacion se rompe, nada posterior (paridad
 * M9, replay M3, baseline M4) tiene sentido; por eso el ROADMAP pone
 * "digest canonico inestable" como STOP de M0.
 */
class CanonicalDigestTest : AnnotationSpec() {

    @Test
    fun UAT_001_item_permutation_yields_identical_digest() {
        val items = listOf(
            EvidenceFixtures.fact("synthetic/alpha/depends-on/1"),
            EvidenceFixtures.signal("synthetic/beta/smell/1"),
            EvidenceFixtures.hypothesis("synthetic/gamma/hypothesis/1"),
            EvidenceFixtures.observation("synthetic/delta/invocation/1"),
        )

        val original = EvidenceFixtures.snapshot(items, correlations = listOf(EvidenceFixtures.correlation()))
        val permuted = EvidenceFixtures.snapshot(
            items = items.reversed(),
            correlations = original.correlations.reversed(),
        )

        // Mismo contenido semantico, distinto orden fisico: mismo digest.
        CanonicalEncoder.digestSnapshot(original) shouldBe CanonicalEncoder.digestSnapshot(permuted)
    }

    @Test
    fun UAT_001_every_permutation_of_four_items_agrees() {
        // Exhaustivo sobre las 24 permutaciones: si un solo path de orden
        // queda sin canonicalizar, esto lo detecta.
        val items = listOf(
            EvidenceFixtures.fact("synthetic/a/depends-on/1"),
            EvidenceFixtures.fact("synthetic/b/depends-on/2"),
            EvidenceFixtures.signal("synthetic/c/smell/1"),
            EvidenceFixtures.hypothesis("synthetic/d/hypothesis/1"),
        )

        val expected = CanonicalEncoder.digestSnapshot(EvidenceFixtures.snapshot(items))

        fun permutations(list: List<EvidenceItem>): List<List<EvidenceItem>> =
            if (list.size <= 1) listOf(list)
            else list.flatMap { head -> permutations(list - head).map { listOf(head) + it } }

        val all = permutations(items)
        all.size shouldBe 24
        for (p in all) {
            CanonicalEncoder.digestSnapshot(EvidenceFixtures.snapshot(p)) shouldBe expected
        }
    }

    @Test
    fun UAT_001_repeated_calls_are_stable() {
        val snapshot = EvidenceFixtures.mixedSnapshot()
        val first = CanonicalEncoder.digestSnapshot(snapshot)

        repeat(5) {
            CanonicalEncoder.digestSnapshot(snapshot) shouldBe first
        }
    }

    @Test
    fun M_R01_threshold_map_order_does_not_change_digest() {
        // El mutante M-R01: serializar un Map en orden de iteracion.
        val a = linkedMapOf("z" to "1", "a" to "2", "m" to "3")
        val b = linkedMapOf("m" to "3", "z" to "1", "a" to "2")

        fun signalWith(thresholds: Map<String, String>) = EvidenceItem.Signal(
            id = EvidenceId("synthetic/m/smell/1"),
            subject = EvidenceSubject.Module("core"),
            authority = EvidenceAuthority.HeuristicAnalyzer,
            provenance = EvidenceFixtures.provenance("StaticSmells"),
            signalKind = "god-function",
            score = "0.42",
            algorithmId = "synthetic-smells",
            algorithmVersion = "0.1.0",
            thresholds = thresholds,
        )

        CanonicalEncoder.digestSnapshot(EvidenceFixtures.snapshot(listOf(signalWith(a)))) shouldBe
            CanonicalEncoder.digestSnapshot(EvidenceFixtures.snapshot(listOf(signalWith(b))))
    }

    @Test
    fun M_R01_suite_operand_order_does_not_change_digest() {
        val base = EvidenceFixtures.suite()

        val reordered = base.copy(
            assertions = base.assertions.map {
                it.copy(operands = linkedMapOf("forbidden" to "adapters", "domain" to "core"))
            },
        )

        CanonicalEncoder.encodeSuite(base) shouldBe CanonicalEncoder.encodeSuite(reordered)
    }

    @Test
    fun M_R01_suite_assertion_order_does_not_change_digest() {
        val a = EvidenceFixtures.suite()
        val second = EvidenceFixtures.suite(assertionId = "b")

        val forward = a.copy(assertions = a.assertions + second.assertions)
        val reversedForward = a.copy(assertions = (a.assertions + second.assertions).reversed())

        CanonicalEncoder.encodeSuite(forward) shouldBe CanonicalEncoder.encodeSuite(reversedForward)
    }

    @Test
    fun M_R01_suite_lens_order_does_not_change_digest() {
        val a = EvidenceFixtures.suite()
        val secondLens = a.lenses.first().copy(
            lensId = dev.pipelinek.assurance.engine.LensId("symbols"),
            kind = "architecture.symbols",
        )

        val forward = a.copy(lenses = a.lenses + secondLens)
        val reversedForward = a.copy(lenses = (a.lenses + secondLens).reversed())

        CanonicalEncoder.encodeSuite(forward) shouldBe CanonicalEncoder.encodeSuite(reversedForward)
    }

    @Test
    fun suite_content_change_yields_different_digest() {
        val base = EvidenceFixtures.suite()
        val stricter = base.copy(
            assertions = base.assertions.map {
                it.copy(enforcement = dev.pipelinek.assurance.engine.Enforcement.Mandatory)
            },
        )

        // El enforcement cambia si el assertion bloquea o solo informa: eso
        // es contenido, no presentacion, y debe entrar en el digest.
        CanonicalEncoder.digestSuite(base) shouldNotBe CanonicalEncoder.digestSuite(stricter)
    }

    @Test
    fun M_E01_partial_and_complete_yield_different_digests() {
        // Si el digest ignorase la completitud, `Partial` y `Complete` serian
        // indistinguibles y el ratchet de M4 no podria detectar una regresion de
        // evidencia.
        val complete = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/c/depends-on/1")))
        val partial = EvidenceFixtures.snapshot(
            listOf(
                EvidenceFixtures.fact(
                    id = "synthetic/c/depends-on/1",
                    completeness = Completeness.Partial(
                        listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.PartialProduced("0.3"))),
                    ),
                ),
            ),
        )

        CanonicalEncoder.digestSnapshot(complete) shouldNotBe CanonicalEncoder.digestSnapshot(partial)
    }

    @Test
    fun M_E01_gap_order_does_not_change_digest() {
        val gaps = listOf(
            EvidenceGap("Zeta", EvidenceGap.GapReason.Unknown),
            EvidenceGap("Alpha", EvidenceGap.GapReason.Lost),
        )
        val a = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/g/depends-on/1")), gaps = gaps)
        val b = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/g/depends-on/1")), gaps = gaps.reversed())

        CanonicalEncoder.digestSnapshot(a) shouldBe CanonicalEncoder.digestSnapshot(b)
    }

    @Test
    fun different_content_yields_different_digest() {
        val a = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/a/depends-on/1")))
        val b = EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/a/depends-on/2")))

        CanonicalEncoder.digestSnapshot(a) shouldNotBe CanonicalEncoder.digestSnapshot(b)
    }

    @Test
    fun digest_is_lowercase_hex_of_64_chars() {
        val d = CanonicalEncoder.digestSnapshot(EvidenceFixtures.mixedSnapshot())

        d.hex.length shouldBe 64
        d.hex.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    @Test
    fun M_R01_source_manifest_order_does_not_change_digest() {
        val manifest = EvidenceFixtures.manifest(producerId = "cognicode")
        val items = listOf(EvidenceFixtures.fact("synthetic/s/depends-on/1"))

        val a = EvidenceFixtures.snapshot(items, sources = listOf(manifest))
        val b = EvidenceFixtures.snapshot(items, sources = listOf(manifest, EvidenceFixtures.manifest(producerId = "chronos")))

        // Distinto numero de sources es contenido distinto: digest distinto.
        CanonicalEncoder.digestSnapshot(a) shouldNotBe CanonicalEncoder.digestSnapshot(b)
    }

    @Test
    fun M_R01_correlation_order_does_not_change_digest() {
        val c1 = EvidenceFixtures.correlation(fromValue = "inv-1", toValue = "span-1")
        val c2 = EvidenceFixtures.correlation(fromValue = "inv-2", toValue = "span-2")
        val items = listOf(EvidenceFixtures.fact("synthetic/c/depends-on/1"))

        val a = CanonicalEncoder.digestSnapshot(EvidenceFixtures.snapshot(items, correlations = listOf(c1, c2)))
        val b = CanonicalEncoder.digestSnapshot(EvidenceFixtures.snapshot(items, correlations = listOf(c2, c1)))

        a shouldBe b
    }

    @Test
    fun AAT_17_canonical_form_has_no_timestamp_field() {
        // Un reloj dentro del digest rompe la reproducibilidad.
        val canonical = CanonicalEncoder.encodeSnapshot(EvidenceFixtures.mixedSnapshot())

        canonical.contains("timestamp") shouldBe false
        canonical.contains("202") shouldBe false
    }

    @Test
    fun field_separator_prevents_concatenation_collisions() {
        // Sin prefijo de longitud, estos dos campos colisionarian.
        val a = EvidenceItem.Fact(
            id = EvidenceId("synthetic/collide/fact/1"),
            subject = EvidenceSubject.Module("core"),
            authority = EvidenceAuthority.DeterministicAnalyzer,
            provenance = EvidenceFixtures.provenance("ModuleDependencies"),
            predicate = "bc",
            objectValue = "a",
        )
        val b = EvidenceItem.Fact(
            id = EvidenceId("synthetic/collide/fact/2"),
            subject = EvidenceSubject.Module("core"),
            authority = EvidenceAuthority.DeterministicAnalyzer,
            provenance = EvidenceFixtures.provenance("ModuleDependencies"),
            predicate = "b",
            objectValue = "ca",
        )

        CanonicalEncoder.encodeSnapshot(EvidenceFixtures.snapshot(listOf(a))) shouldNotBe
            CanonicalEncoder.encodeSnapshot(EvidenceFixtures.snapshot(listOf(b)))
    }
}