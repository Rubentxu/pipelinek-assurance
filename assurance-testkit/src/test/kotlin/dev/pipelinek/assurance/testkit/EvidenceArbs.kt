package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map

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
}