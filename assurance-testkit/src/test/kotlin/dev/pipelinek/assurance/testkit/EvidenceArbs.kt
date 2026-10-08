package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.Correlation
import dev.pipelinek.assurance.domain.evidence.CorrelationRelation
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionIR
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.DiffState
import dev.pipelinek.assurance.engine.Enforcement
import dev.pipelinek.assurance.engine.EvaluationFailure
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.Severity
import dev.pipelinek.assurance.engine.SuiteId
import dev.pipelinek.assurance.engine.UnsupportedReason
import dev.pipelinek.assurance.engine.ArtifactRef
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of

/**
 * Generadores arbitrarios para los property tests de M0.
 *
 * Por qué existe esto y por qué no basta con fixtures fijos:
 *
 * Los tests de ejemplo prueban que el encoder funciona para los casos que se
 * pensaron. Los property tests preguntan algo más fuerte: que el digest no
 * depende del ORDEN de entrada, para cualquier entrada, no sólo para la
 * que alguien imaginó. UAT-001 es exactamente eso, y con fixtures fijos
 * basta un `reverse()` para descubrir la mitad de los fallos.
 *
 * Regla que respetan todos los generadores: sólo producen valores que el
 * dominio acepta. Un generador que emitiera un `EvidenceId` inválido no
 * encuentra bugs del encoder: encuentra que `EvidenceId` exige cuatro
 * segmentos, que ya es un invariante probado en otro sitio.
 *
 * Determinismo: nada aquí usa reloj, red ni `Random` sin semilla. Un
 * generador no reproducible hace irreproducibles los fallos que encuentra.
 */
object EvidenceArbs {

    /**
     * Identificador con la forma `namespace/subject/kind/discriminator`.
     *
     * `EvidenceId` la exige en su `init`. Por eso el generador no puede
     * producir un id arbitrario, aunque sería más cómodo: produciría
     * excepciones del dominio en vez de entradas válidas para el encoder.
     */
    fun evidenceId(): Arb<String> = Arb.bind(
        Arb.int(0..9),
        Arb.int(0..9),
        Arb.int(0..9),
    ) { n, k, d ->
        "synthetic/s$n/depends-on/${k}d"
    }

    /**
     * Textos de subject que estresean el encoder.
     *
     * Los saltos de línea y las comillas están ahí a propósito: el encoder
     * canónico escapa `\n`, y si el escapado fallara, dos evidencias con
     * subjects distintos darían el mismo digest.
     */
    fun subjectText(): Arb<String> = Arb.int(0..6).map { which ->
        when (which) {
            0 -> "core"
            1 -> "adapter"
            2 -> "core.domain"
            3 -> "core::run"
            4 -> "modulo con espacios"
            5 -> "modulo\ncon\nsaltos"
            else -> "modulo\"con\"comillas"
        }
    }

    fun subject(): Arb<EvidenceSubject> = Arb.bind(
        Arb.int(0..4),
        subjectText(),
    ) { kind, text ->
        when (kind) {
            0 -> EvidenceSubject.Module(text)
            1 -> EvidenceSubject.Symbol(text)
            2 -> EvidenceSubject.SourceLocation(text, 12, 34)
            3 -> EvidenceSubject.Test(text)
            else -> EvidenceSubject.RuntimeSpan(text)
        }
    }

    /**
     * Completitud, con `Partial` que referencia gaps reales.
     *
     * `Partial` sin gaps es un caso que el tipo admite pero que no ocurre en
     * la práctica; incluirlo sólo añadiría ruido al generador.
     *
     * ASIMETRIA DEL DOMINIO, no un descuido del generador: `Fact` RECHAZA
     * `Completeness.Unknown` y `Completeness.Unsupported`, mientras que
     * `Observation` las admite. La razón es de epistemología y está en
     * `03-specifications/EVIDENCE_MODEL.md`:
     *
     * - Un `Fact` afirma algo sobre el sujeto. Si el productor no supo
     *   calcularlo, no puede afirmar "no soportado": sería afirmar una
     *   unknowable como si fuera un hecho, que es exactamente la confusión
     *   que el modelo de autoridad quiere impedir.
     * - Una `Observation` describe lo que el productor observo. "No se si
     *   llego esto" es una observacion legitima: el runtime no vio la
     *   llamada, y eso es un dato, no una afirmacion falsa.
     *
     * Por eso este generador NO usa el mismo `completeness()` para todos los
     * items: `Fact` recibe un subconjunto y el resto recibe el completo.
     */
    fun completeness(paraFact: Boolean): Arb<Completeness> = Arb.bind(
        Arb.int(if (paraFact) 0..2 else 0..3),
        Arb.int(0..1),
    ) { kind, gapKind ->
        val gap = EvidenceGap(
            capability = "ModuleDependencies",
            reason = if (gapKind == 0) EvidenceGap.GapReason.Lost else EvidenceGap.GapReason.Unknown,
        )
        when (kind) {
            0 -> Completeness.Complete
            1 -> if (paraFact) Completeness.Partial(listOf(gap)) else Completeness.Unknown
            2 -> if (paraFact) Completeness.Complete else Completeness.Unsupported("no soportado")
            else -> Completeness.Partial(listOf(gap))
        }
    }

    /**
     * Un item de evidencia de cualquiera de los cuatro tipos.
     *
     * Los cuatro se generan juntos a propósito: las leyes que se comprueban
     * (orden, digest, roundtrip) tienen que valer para todos, no sólo para el
     * `Fact` que alguien pensó primero.
     */
    fun item(): Arb<EvidenceItem> = Arb.bind(
        evidenceId(),
        subject(),
        Arb.int(0..3),
        Arb.int(1..6),
        Arb.int(0..5),
        Arb.int(0..3),
        Arb.int(0..3),
    ) { id, subj, kind, thresholdCount, extra, compKind, gapKind ->
        val thresholds = (1..thresholdCount).associate { "t$it" to "v$it$extra" }

        // Cada rama elige la completitud que SU tipo admite. El `Fact` es el
        // unico que restringe: rechaza `Unknown` y `Unsupported`.
        //
        // `Partial` SIEMPRE lleva gaps: `Completeness.Partial` exige al menos
        // uno en su `init`, y `Partial(emptyList())` es un snapshot que no
        // significa nada.
        val gap = EvidenceGap(
            capability = "ModuleDependencies",
            reason = if (gapKind == 0) EvidenceGap.GapReason.Lost else EvidenceGap.GapReason.Unknown,
        )

        fun completitudPermitida(esFact: Boolean): Completeness = when (compKind) {
            0 -> Completeness.Complete
            1 -> if (esFact) Completeness.Partial(listOf(gap)) else Completeness.Unknown
            2 -> if (esFact) Completeness.Complete else Completeness.Unsupported("no soportado")
            else -> Completeness.Partial(listOf(gap))
        }

        when (kind) {
            0 -> EvidenceItem.Fact(
                id = EvidenceId(id),
                subject = subj,
                authority = EvidenceAuthority.DeterministicAnalyzer,
                provenance = EvidenceFixtures.provenance("ModuleDependencies"),
                predicate = "depends-on",
                objectValue = "adapter",
                completeness = completitudPermitida(esFact = true),
            )
            1 -> EvidenceItem.Observation(
                id = EvidenceId(id),
                subject = subj,
                authority = EvidenceAuthority.RuntimeObserver,
                provenance = EvidenceFixtures.provenance("RuntimeInvocations"),
                observation = "adapter invoco domain",
                completeness = completitudPermitida(esFact = false),
            )
            2 -> EvidenceItem.Signal(
                id = EvidenceId(id),
                subject = subj,
                authority = EvidenceAuthority.HeuristicAnalyzer,
                provenance = EvidenceFixtures.provenance("StaticSmells"),
                signalKind = "god-function",
                score = "0.$extra",
                algorithmId = "synthetic-smells",
                algorithmVersion = "0.1.0",
                thresholds = thresholds,
                completeness = completitudPermitida(esFact = false),
            )
            else -> EvidenceItem.Hypothesis(
                id = EvidenceId(id),
                subject = subj,
                authority = EvidenceAuthority.AgentHypothesis,
                provenance = EvidenceFixtures.provenance("AgentReview"),
                claim = "este modulo viola SRP",
                reasoning = "tres razones de lectura",
                confidence = "low",
            )
        }
    }

    /**
     * Manifest con completitud por capability.
     *
     * Las capabilities se generan y luego se deduplican, de modo que dos
     * ejecuciones del generador producen mapas con las mismas claves aunque
     * las listas lleguen en distinto orden. Sin eso, la ley de orden de
     * maps se estaría probando contra un mapa ya ordenado y no probaría nada.
     */
    fun manifest(): Arb<EvidenceSourceManifest> = Arb.bind(
        Arb.int(0..2),
        Arb.list(Arb.constant("ModuleDependencies"), 1..4),
    ) { which, caps ->
        EvidenceFixtures.manifest(
            producerId = listOf("synthetic", "otro-producer", "tercero")[which],
            produced = caps.distinct(),
        )
    }

    fun snapshot(): Arb<EvidenceSnapshot> = Arb.bind(
        Arb.list(item(), 0..8),
        Arb.list(manifest(), 1..3),
        Arb.list(Arb.constant(EvidenceGap("SymbolGraph", EvidenceGap.GapReason.Lost)), 0..3),
        Arb.list(Arb.constant(EvidenceFixtures.correlation()), 0..2),
    ) { items, manifests, gaps, correlations ->
        EvidenceFixtures.snapshot(
            items = items,
            snapshotId = "snap-property",
            gaps = gaps,
            correlations = correlations,
            sources = manifests,
        )
    }

    /**
     * Dos snapshots con el mismo contenido y distinta permutación de items.
     *
     * La permutación es REAL, no un `reversed()`: el objetivo es que la ley no
     * dependa de que alguien escriba el caso fácil.
     */
    fun permutationPair(): Arb<Pair<EvidenceSnapshot, EvidenceSnapshot>> = Arb.bind(
        snapshot(),
        Arb.int(0..1),
    ) { s, flip ->
        val items = s.items
        val barajado = if (flip == 0 || items.size < 2) {
            items
        } else {
            items.drop(1) + items.first()
        }
        s to s.copy(items = barajado)
    }

    /** Suite con metadata de clave y valores variables. */
    fun suite(): Arb<AssuranceSuiteIR> = Arb.bind(
        Arb.int(0..99),
        Arb.int(0..99),
    ) { a, b ->
        EvidenceFixtures.suite().copy(
            metadata = mapOf(
                "owner" to "v-$a",
                "ticket" to "v-$b",
                "reviewer" to "r-${a + b}",
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Generadores de suite y report
    //
    // El `suite()` de arriba solo varía el `metadata`. Sirve para la ley de
    // permutación del digest, pero es demasiado pobre para un roundtrip: una
    // suite con una sola lens, una sola assertion y un solo mapa de tres
    // entradas ejercita una forma, no las que el codec tiene que soportar.
    //
    // Estos generadores son los que hacen que "el roundtrip funciona" sea una
    // afirmación sobre el espacio de entradas y no sobre un ejemplo. Sin
    // ellos, una property law que sólo genere `suite()` estaría certificando
    // más fuerte de lo que mide, que es el modo de fallo más común de un
    // property test: pasar verde porque nunca genera el caso difícil.
    // -----------------------------------------------------------------------

    /**
     * Token corto y no vacío.
     *
     * Deliberadamente restringido a `[a-z0-9-]`: un token con acentos o saltos
     * de línea probaría el escapado CBOR/JSON, que es otra ley
     * (`LAW_arbitrary_string_content_survives_encoding`) y ya tiene su
     * generador. Mezclar los dos aquí haría que un fallo de escapado se
     * reportara como fallo de roundtrip, que es el diagnóstico equivocado.
     */
    private fun token(): Arb<String> = Arb.bind(Arb.int(0..999), Arb.int(0..999)) { a, b ->
        "t$a-$b"
    }

    /**
     * Suite con multiplicidad variable de lenses, assertions y capabilities.
     *
     * Respeta AAT-16 (`AssuranceSuiteIR.init`): las assertions se generan
     * DESPUÉS de las lenses y su `lensRef` se toma de las declaradas. Un
     * generador que emitiera refs colgantes no encontraría bugs del codec:
     * encontraría la invariante del `init`, que ya está probada en otro sitio.
     * Un generador que viola una invariante del dominio está probando el
     * `init`, no el codec.
     */
    fun richSuite(): Arb<AssuranceSuiteIR> = Arb.bind(
        Arb.int(1..3),
        Arb.int(1..3),
        Arb.int(0..2),
        Arb.int(0..2),
        Arb.int(0..99),
    ) { nLenses, nAssertions, extraCaps, authWindow, seed ->
        val authorities = listOf(
            "DeterministicAdapter",
            "DeterministicAnalyzer",
            "RuntimeObserver",
        )
        val lenses = (0 until nLenses).map { i ->
            LensPlan(
                lensId = LensId("lens-$i-$seed"),
                kind = "architecture.kind-$i",
                inputCapabilities = (0..(1 + extraCaps + i)).map { "cap-$i-$it" },
                arguments = (0..authWindow).associate { j -> "arg-$j" to "v-$seed-$i-$j" },
                outputSchema = "schema://assurance/lens-$i/v1",
            )
        }
        AssuranceSuiteIR(
            apiVersion = "assurance/v1",
            suiteId = SuiteId("suite-$seed"),
            suiteVersion = "0.$seed.0",
            requiredEvidence = (0..(1 + extraCaps)).map { "cap-0-$it" },
            lenses = lenses,
            assertions = (0 until nAssertions).map { i ->
                AssertionIR(
                    id = AssertionId("assert-$i-$seed"),
                    lensRef = lenses[i % lenses.size].lensId,
                    operator = "op-$i",
                    operands = (0..authWindow).associate { j -> "k-$j" to "v-$i-$j" },
                    severity = Severity.entries[i % Severity.entries.size],
                    enforcement = Enforcement.entries[i % Enforcement.entries.size],
                    completenessRequirements = (0..extraCaps).map { "cap-0-$it" },
                    rationale = "por que $i con semilla $seed",
                    admittedAuthorities = authorities
                        .drop(authWindow % authorities.size)
                        .toSet(),
                )
            },
            metadata = if (seed % 2 == 0) mapOf("seed" to "$seed") else emptyMap(),
        )
    }

    /** Suite y la misma suite con sus colecciones en orden inverso. */
    fun suitePermutationPair(): Arb<Pair<AssuranceSuiteIR, AssuranceSuiteIR>> =
        richSuite().map { s ->
            val invertido = s.copy(
                requiredEvidence = s.requiredEvidence.reversed(),
                lenses = s.lenses.reversed(),
                assertions = s.assertions.reversed(),
            )
            invertido to s
        }

    // -- report ---------------------------------------------------------------

    /**
     * Gap con todas las razones posibles y `detail` a veces nulo.
     *
     * `PartialProduced` lleva un `String`, no un `Double`: la cobertura se
     * serializa como texto canónico para que el digest no dependa del formato
     * de coma flotante.
     */
    private fun gap(): Arb<EvidenceGap> = Arb.bind(token(), Arb.int(0..3)) { cap, which ->
        EvidenceGap(
            capability = cap,
            reason = when (which) {
                0 -> EvidenceGap.GapReason.Unsupported
                1 -> EvidenceGap.GapReason.Unknown
                2 -> EvidenceGap.GapReason.Lost
                else -> EvidenceGap.GapReason.PartialProduced("0.$which")
            },
            detail = if (which % 2 == 0) null else "detalle-$cap",
        )
    }

    private fun externalRef(): Arb<TypedExternalId> = Arb.bind(
        Arb.of(
            ExternalNamespace.CogniCodeEntityId,
            ExternalNamespace.ChronosInvocationId,
            ExternalNamespace.OTelSpanId,
            ExternalNamespace.GitRevision,
        ),
        token(),
    ) { ns, v -> TypedExternalId(ns, v) }

    private fun evidenceRef(): Arb<EvidenceId> = evidenceId().map { EvidenceId(it) }

    private fun correlation(): Arb<Correlation> = Arb.bind(
        externalRef(),
        Arb.of(*CorrelationRelation.entries.toTypedArray()),
        externalRef(),
        evidenceRef(),
    ) { from, relation, to, ev -> Correlation(from, relation, to, ev) }

    /**
     * Cualquier `AssertionResult`, incluidas las cinco variantes y los seis
     * subtipos de `Counterexample` y los cuatro de `UnsupportedReason`.
     *
     * La cobertura de subtipos NO es decorativa: cada uno tiene su propio DTO
     * en el codec, y un subtipo que el generador nunca produce es un subtipo
     * cuyo roundtrip nadie comprueba. Una property law que sólo generase
     * `Passed` y `Failed(Cycle)` sería más estrecha que los tests de ejemplo,
     * que es la forma más común de property test inútil.
     */
    fun result(): Arb<AssertionResult> = Arb.bind(
        Arb.int(0..8),
        evidenceRef(),
        token(),
        token(),
        externalRef(),
        Arb.int(0..2),
    ) { which, ev, t1, t2, ref, extra ->
        val evidenceRefs = listOf(ev)
        val subjectRefs = listOf(ref)
        val hints = (0..extra).map { "hint-$it-$t1" }
        when (which) {
            0 -> AssertionResult.Passed(
                ProofRef(
                    snapshotId = "snap-$t1",
                    evidenceIds = evidenceRefs,
                    assertionId = AssertionId("a-$t2"),
                ),
            )
            1 -> AssertionResult.Failed(
                Counterexample.DependencyPath(
                    assertionId = AssertionId("a-$t1"),
                    subjectRefs = subjectRefs,
                    evidenceRefs = evidenceRefs,
                    explanation = "path prohibido $t2",
                    reproductionHints = hints,
                    path = (0..extra).map { "step-$it" },
                    fromLayer = "core",
                    toLayer = "adapters",
                ),
            )
            2 -> AssertionResult.Failed(
                Counterexample.Cycle(
                    assertionId = AssertionId("a-$t1"),
                    subjectRefs = subjectRefs,
                    evidenceRefs = evidenceRefs,
                    explanation = "ciclo $t2",
                    reproductionHints = hints,
                    cycle = (0..extra).map { "mod-$it" },
                ),
            )
            3 -> AssertionResult.Failed(
                Counterexample.CausalSlice(
                    assertionId = AssertionId("a-$t1"),
                    subjectRefs = subjectRefs,
                    evidenceRefs = evidenceRefs,
                    explanation = "corte causal $t2",
                    reproductionHints = hints,
                    invocationChain = (0..extra).map { "hop-$it" },
                ),
            )
            4 -> AssertionResult.Failed(
                Counterexample.Mutation(
                    assertionId = AssertionId("a-$t1"),
                    subjectRefs = subjectRefs,
                    evidenceRefs = evidenceRefs,
                    explanation = "mutante $t2",
                    reproductionHints = hints,
                    mutatedSymbol = "sym-$t1",
                    // `killedBy` es nullable a propósito: hay que probar los dos
                    // casos, no solo el que viene con evidencia.
                    killedBy = if (extra % 2 == 0) "test-$extra" else null,
                ),
            )
            5 -> AssertionResult.Failed(
                Counterexample.MissingTrace(
                    assertionId = AssertionId("a-$t1"),
                    subjectRefs = subjectRefs,
                    evidenceRefs = evidenceRefs,
                    explanation = "traza perdida $t2",
                    reproductionHints = hints,
                    missingSpanFor = "op-$t2",
                    expectedPropagation = "prop-$t1",
                ),
            )
            6 -> AssertionResult.Failed(
                Counterexample.BaselineRegression(
                    assertionId = AssertionId("a-$t1"),
                    subjectRefs = subjectRefs,
                    evidenceRefs = evidenceRefs,
                    explanation = "regresion $t2",
                    reproductionHints = hints,
                    stableId = "stable-$t1:$extra",
                    state = DiffState.entries[extra % DiffState.entries.size],
                ),
            )
            7 -> AssertionResult.Inconclusive(
                // `Inconclusive` exige al menos un gap: por eso el rango es
                // 1..extra+1 y no 0..extra. Emitir la lista vacía no
                // produciría un bug del codec, produciría una excepción del
                // dominio, que ya está probada en otro sitio.
                (0..(1 + extra)).map { g ->
                    EvidenceGap("gap-$g-$t1", EvidenceGap.GapReason.Unsupported)
                },
            )
            else -> AssertionResult.Unsupported(
                when (extra) {
                    0 -> UnsupportedReason.UnknownLensKind("kind-$t1")
                    1 -> UnsupportedReason.UnknownOperator("op-$t2")
                    else -> UnsupportedReason.UnknownEvidenceKind("kind-$extra")
                },
            )
        }
    }

    /**
     * Resultado de error o `AuthorityNotAdmitted`.
     *
     * Van aparte de `result()` para que ese generador reparta sus ocho ramas
     * con la misma frecuencia en vez de gastar la mitad de las iteraciones en
     * `Error`, que sólo tiene dos subtipos.
     */
    fun errorResult(): Arb<AssertionResult> = Arb.bind(
        token(),
        token(),
        Arb.int(0..2),
    ) { t1, t2, extra ->
        when (extra) {
            0 -> AssertionResult.Error(EvaluationFailure("phase-$t1", "detail-$t2"))
            1 -> AssertionResult.Error(EvaluationFailure("phase-$t1", "detail-$t2", "cause-$extra"))
            else -> AssertionResult.Unsupported(
                UnsupportedReason.AuthorityNotAdmitted("DeterministicAnalyzer", "Heuristic-$t1"),
            )
        }
    }

    fun report(): Arb<AssuranceReport> = Arb.bind(
        Arb.list(result(), 0..4),
        Arb.list(errorResult(), 0..2),
        Arb.list(gap(), 0..3),
        Arb.list(artifactRef(), 0..3),
        Arb.list(correlation(), 0..2),
        token(),
        Arb.int(0..99),
    ) { results, errors, gaps, artifacts, correlations, t, seed ->
        AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-$t-$seed"),
            snapshotDigest = Digest.ofUtf8("snap-$seed"),
            suiteDigest = Digest.ofUtf8("suite-$seed"),
            engineVersion = "0.$seed.0",
            results = results + errors,
            gaps = gaps,
            artifacts = artifacts,
            correlations = correlations,
        )
    }

    private fun artifactRef(): Arb<ArtifactRef> = Arb.bind(token(), Arb.int(0..9), Arb.int(0..9)) { role, a, b ->
        ArtifactRef(
            digest = Digest.ofUtf8("artifact-$a-$b"),
            mediaType = if (a % 2 == 0) "text/plain" else "application/json",
            logicalRole = role,
        )
    }

    /** Report y el mismo report con sus colecciones en orden inverso. */
    fun reportPermutationPair(): Arb<Pair<AssuranceReport, AssuranceReport>> =
        report().map { r ->
            val invertido = r.copy(
                results = r.results.reversed(),
                gaps = r.gaps.reversed(),
                artifacts = r.artifacts.reversed(),
                correlations = r.correlations.reversed(),
            )
            invertido to r
        }
}

