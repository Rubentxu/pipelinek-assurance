package dev.pipelinek.assurance.domain

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * Leyes epistémicas de M0.
 *
 * Cada test apunta a un mutante concreto del catálogo. Si alguno pasa sin que
 * su mutante muera, el test es decorativo.
 */
class EpistemicLawsTest : AnnotationSpec() {

    /** Las tres autoridades deterministas. ADR-004: solo estas son deterministas. */
    private val DETERMINISTIC_AUTHORITIES = EvidenceAuthority.entries.filter { it.isDeterministic }

    /** Todo lo que un Signal admite es exactamente su complemento. */
    private val EVIDENCE_AUTHORITIES_ALLOWED_IN_SIGNAL =
        setOf(EvidenceAuthority.HeuristicAnalyzer)

    @Test
    fun M_E01_partial_retains_its_gaps() {
        // La ley: un item `Partial` conserva su gap. No hay conversión que lo
        // promueva a `Complete`.
        val gap = EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.PartialProduced("0.4"))
        val partial = Completeness.Partial(listOf(gap))

        partial shouldBe Completeness.Partial(listOf(gap))
        partial.gaps shouldBe listOf(gap)
    }

    @Test
    fun M_E01_partial_requires_at_least_one_gap() {
        shouldThrow<IllegalArgumentException> {
            Completeness.Partial(emptyList())
        }
    }

    @Test
    fun M_E01_fact_cannot_declare_unknown_completeness() {
        // M-E01 ataca aqui: si un Fact aceptara `Unknown`, la evidencia
        // podria declararse completa por omision y `no evidence != PASS`
        // dejaria de valer.
        shouldThrow<IllegalArgumentException> {
            EvidenceFixtures.fact("synthetic/x/fact/1", completeness = Completeness.Unknown)
        }
        shouldThrow<IllegalArgumentException> {
            EvidenceFixtures.fact("synthetic/x/fact/2", completeness = Completeness.Unsupported("no provider"))
        }
    }

    @Test
    fun M_E01_fact_completeness_is_not_open_for_subclasses() {
        // Segunda via independiente al mismo mutante: construir el tipo
        // directamente, sin pasar por los fixtures. Si M-E01 revive, esto
        // tambien lo detecta aunque el fixture cambie.
        shouldThrow<IllegalArgumentException> {
            EvidenceItem.Fact(
                id = EvidenceId("synthetic/direct/fact/1"),
                subject = EvidenceSubject.Module("core"),
                authority = EvidenceAuthority.DeterministicAnalyzer,
                provenance = EvidenceFixtures.provenance("ModuleDependencies"),
                predicate = "depends-on",
                objectValue = "adapter",
                completeness = Completeness.Unknown,
            )
        }
        // `Observation` si admite `Unknown`/`Unsupported`: una observacion que
        // no pudo completarse es un estado legitimo, no una contradiccion.
        // Solo el Fact tiene la restriccion.
        EvidenceItem.Observation(
            id = EvidenceId("synthetic/direct/observation/1"),
            subject = EvidenceSubject.RuntimeSpan("span-1"),
            authority = EvidenceAuthority.RuntimeObserver,
            provenance = EvidenceFixtures.provenance("RuntimeInvocations"),
            observation = "adapter invoked domain",
            completeness = Completeness.Unsupported("provider dropped it"),
        ).completeness shouldBe Completeness.Unsupported("provider dropped it")
    }

    @Test
    fun M_E02_hypothesis_cannot_be_a_deterministic_fact() {
        // M-E02: una Hypothesis exige autoridad AgentHypothesis o HumanCurated.
        // Si aceptara DeterministicAnalyzer, una propuesta de agente podria
        // satisfacer una assertion que exige evidencia determinista (AAT-19).
        shouldThrow<IllegalArgumentException> {
            EvidenceItem.Hypothesis(
                id = EvidenceId("synthetic/y/hypothesis/1"),
                subject = EvidenceSubject.Module("core"),
                authority = EvidenceAuthority.DeterministicAnalyzer,
                provenance = EvidenceFixtures.provenance("AgentReview"),
                claim = "c",
                reasoning = "r",
                confidence = "low",
            )
        }
    }

    @Test
    fun M_E02_hypothesis_rejects_every_deterministic_authority() {
        // Segunda via al mismo mutante: barremos las tres autoridades
        // deterministas, no solo una. Un require parcial dejaria pasar una.
        for (authority in DETERMINISTIC_AUTHORITIES) {
            shouldThrow<IllegalArgumentException> {
                EvidenceItem.Hypothesis(
                    id = EvidenceId("synthetic/y/hypothesis/${authority.name}"),
                    subject = EvidenceSubject.Module("core"),
                    authority = authority,
                    provenance = EvidenceFixtures.provenance("AgentReview"),
                    claim = "c",
                    reasoning = "r",
                    confidence = "low",
                )
            }
        }
    }

    @Test
    fun M_E02_hypothesis_admits_exactly_the_non_deterministic_authorities() {
        // Control positivo: las dos autoridades validas siguen admitidas.
        for (authority in listOf(EvidenceAuthority.AgentHypothesis, EvidenceAuthority.HumanCurated)) {
            EvidenceItem.Hypothesis(
                id = EvidenceId("synthetic/y/hypothesis/ok-${authority.name}"),
                subject = EvidenceSubject.Module("core"),
                authority = authority,
                provenance = EvidenceFixtures.provenance("AgentReview"),
                claim = "c",
                reasoning = "r",
                confidence = "low",
            ).authority shouldBe authority
        }
    }

    @Test
    fun M_H01_signal_cannot_be_deterministic() {
        // M-H01: un Signal es heuristico por definicion.
        shouldThrow<IllegalArgumentException> {
            EvidenceItem.Signal(
                id = EvidenceId("synthetic/y/signal/1"),
                subject = EvidenceSubject.Module("core"),
                authority = EvidenceAuthority.DeterministicAnalyzer,
                provenance = EvidenceFixtures.provenance("StaticSmells"),
                signalKind = "k",
                score = "1",
                algorithmId = "a",
                algorithmVersion = "1",
            )
        }
    }

    @Test
    fun M_H01_signal_admits_only_heuristic_authority() {
        for (authority in EvidenceAuthority.entries - EVIDENCE_AUTHORITIES_ALLOWED_IN_SIGNAL) {
            shouldThrow<IllegalArgumentException> {
                EvidenceItem.Signal(
                    id = EvidenceId("synthetic/y/signal/${authority.name}"),
                    subject = EvidenceSubject.Module("core"),
                    authority = authority,
                    provenance = EvidenceFixtures.provenance("StaticSmells"),
                    signalKind = "k",
                    score = "1",
                    algorithmId = "a",
                    algorithmVersion = "1",
                )
            }
        }
    }

    @Test
    fun hypothesis_and_fact_are_distinct_types_without_promotion() {
        // La hipotesis conserva su tipo y su autoridad. No existe `toFact()`
        // ni constructor que la convierta: la ausencia es la ley.
        val h: EvidenceItem = EvidenceFixtures.hypothesis("synthetic/x/hypothesis/1")

        h.authority shouldBe EvidenceAuthority.AgentHypothesis
        (h is EvidenceItem.Hypothesis) shouldBe true

        // Un Fact sigue siendo un Fact, con su autoridad determinista.
        val f: EvidenceItem = EvidenceFixtures.fact("synthetic/x/depends-on/1")
        (f is EvidenceItem.Fact) shouldBe true
        f.authority shouldBe EvidenceAuthority.DeterministicAnalyzer
    }

    @Test
    fun AAT_19_only_three_authorities_are_deterministic() {
        EvidenceAuthority.entries.count { it.isDeterministic } shouldBe 3

        EvidenceAuthority.DeterministicAdapter.isDeterministic shouldBe true
        EvidenceAuthority.DeterministicAnalyzer.isDeterministic shouldBe true
        EvidenceAuthority.RuntimeObserver.isDeterministic shouldBe true

        EvidenceAuthority.HeuristicAnalyzer.isDeterministic shouldBe false
        EvidenceAuthority.HumanCurated.isDeterministic shouldBe false
        EvidenceAuthority.AgentHypothesis.isDeterministic shouldBe false
    }

    @Test
    fun AAT_09_no_authority_gates_by_default() {
        // ADR-004 y ROADMAP §5.4: ninguna autoridad basta sola para bloquear.
        for (authority in EvidenceAuthority.entries) {
            authority.mayGate shouldBe false
        }
    }

    @Test
    fun AAT_13_external_ids_are_namespaced_and_typed() {
        val correlation = EvidenceFixtures.correlation()
        correlation.from.namespace shouldBe dev.pipelinek.assurance.domain.evidence.ExternalNamespace.ChronosInvocationId
        correlation.to.namespace shouldBe dev.pipelinek.assurance.domain.evidence.ExternalNamespace.OTelSpanId
        correlation.from.value shouldBe "inv-1"
    }

    @Test
    fun authority_and_completeness_are_orthogonal() {
        // Un Signal completo sigue siendo heuristico: completitud no es
        // confianza.
        val signal = EvidenceFixtures.signal("synthetic/z/signal/1")
        signal.authority shouldBe EvidenceAuthority.HeuristicAnalyzer
        signal.completeness shouldBe Completeness.Complete

        // Y un Fact parcial sigue siendo determinista.
        val partialFact = EvidenceFixtures.fact(
            id = "synthetic/z/fact/1",
            completeness = Completeness.Partial(
                listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.PartialProduced("0.5"))),
            ),
        )
        partialFact.authority.isDeterministic shouldBe true
    }

    @Test
    fun manifest_cannot_declare_completeness_for_unproduced_capability() {
        // Un provider no puede declarar `Complete` sobre algo que no produjo.
        shouldThrow<IllegalArgumentException> {
            EvidenceFixtures.manifest(
                produced = listOf("ModuleDependencies"),
                completenessByCapability = mapOf(
                    "ModuleDependencies" to Completeness.Complete,
                    "SymbolGraph" to Completeness.Complete,
                ),
            )
        }
    }

    @Test
    fun snapshot_requires_at_least_one_source_manifest() {
        shouldThrow<IllegalArgumentException> {
            EvidenceFixtures.snapshot(listOf(EvidenceFixtures.fact("synthetic/q/fact/1")), sources = emptyList())
        }
    }

    @Test
    fun evidence_id_must_be_namespaced() {
        shouldThrow<IllegalArgumentException> { EvidenceId("sin-namespace") }
        EvidenceId("ns/subject/kind/1").value shouldBe "ns/subject/kind/1"
    }

    @Test
    fun digest_is_sha256_and_stable() {
        val a = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("abc")
        // Vector conocido de SHA-256 para "abc".
        a.hex shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        a shouldBe dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("abc")
        a shouldBe dev.pipelinek.assurance.domain.evidence.Digest.of("abc".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun digest_matches_published_sha256_vectors() {
        // Vectores oficiales NIST/FIPS 180-4. Un digest "de 64 hex chars" que
        // no coincida con estos no es SHA-256, por mucho que tenga la forma.
        val vectors = mapOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        )
        for ((input, expected) in vectors) {
            dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8(input).hex shouldBe expected
        }
    }

    /**
     * La matriz de completud de `EVIDENCE_MODEL.md` es la LEY, no un ejemplo.
     *
     * Los dos tests anteriores ya fijan la asimetría en casos concretos: un
     * `Fact` con `Unknown` revienta y una `Observation` con `Unsupported` se
     * acepta. Lo que no fijaban es que la matriz sea completa: si alguien
     * añade un quinto valor de `Completeness`, esos dos tests siguen en verde y
     * la tabla de la especificación queda desactualizada sin que nada lo note.
     *
     * Este test recorre TODOS los valores de `Completeness` y comprueba, para
     * cada uno, exactamente lo que la tabla dice que ocurre. Si la tabla y el
     * código discrepan, falla aquí en vez de fallar en producción.
     *
     * Por qué está escrito como recorrido y no como cuatro `shouldThrow`: un
     * caso concreto sigue teniendo la forma de "probamos lo que se nos ocurrió",
     * y esta matriz se escribió precisamente para que no dependa de la
     * Imagination de quien escribe el test.
     */
    @Test
    fun the_completeness_matrix_of_the_spec_is_the_one_the_code_enforces() {
        // La tabla de "La asimetría Fact / Observation en `Completeness`",
        // transcrita a código. Que sea una transcripción es el punto: si la
        // tabla de la especificación y estas dos líneas discrepan, el test
        // falla Y el sitio del fallo es el sitio del error.
        val matriz = mapOf(
            Completeness.Complete to true,
            Completeness.Partial(
                listOf(EvidenceGap("ns/sub/gap/1", EvidenceGap.GapReason.Lost)),
            ) to true,
            Completeness.Unknown to false,
            Completeness.Unsupported("sin provider") to false,
        )

        // El dominio tiene exactamente estos valores y ni uno más. Si alguien
        // añade un quinto, esta aserción falla: un valor nuevo entra por la
        // puerta de atrás sin que la tabla diga si es admisible.
        matriz.keys.map { it::class }.toSet() shouldBe
            Completeness::class.sealedSubclasses.map { it }.toSet()

        for ((completeness, admiteElFact) in matriz) {
            if (admiteElFact) {
                fact(completeness).completeness shouldBe completeness
            } else {
                shouldThrow<IllegalArgumentException> { fact(completeness) }
            }
            // `Observation` admite los cuatro, siempre. Si alguna vez no lo
            // hiciera, la asimetría documentada sería mentira.
            observation(completeness).completeness shouldBe completeness
        }
    }

    private fun fact(completeness: Completeness): EvidenceItem.Fact = EvidenceFixtures.fact(
        "synthetic/matrix/fact/1",
        completeness = completeness,
    )

    private fun observation(completeness: Completeness): EvidenceItem.Observation =
        EvidenceItem.Observation(
            id = EvidenceId("synthetic/matrix/observation/1"),
            subject = EvidenceSubject.RuntimeSpan("span-matrix"),
            authority = EvidenceAuthority.RuntimeObserver,
            provenance = EvidenceFixtures.provenance("RuntimeInvocations"),
            observation = "visto en esta ejecucion",
            completeness = completeness,
        )
}