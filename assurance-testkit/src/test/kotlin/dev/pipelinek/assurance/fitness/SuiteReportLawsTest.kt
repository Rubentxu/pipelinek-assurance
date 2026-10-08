package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.ReportArtifactCodec
import dev.pipelinek.assurance.artifact.SuiteArtifactCodec
import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.UnsupportedReason
import dev.pipelinek.assurance.testkit.EvidenceArbs
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.Gen
import io.kotest.property.checkAll
import kotlin.reflect.KClass

/**
 * M0 — Leyes de roundtrip de las familias suite y report.
 *
 * Ref: `06-uat/MILESTONE_GATES.md` Gate M0 ("property laws verdes") y
 * `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` familias 2 y 3.
 *
 * POR QUÉ ESTE ARCHIVO EXISTE Y POR QUÉ NO ESTÁ DENTRO DE `EvidenceLawsTest`
 *
 * `EvidenceLawsTest` ya tenía cuatro leyes de roundtrip y el ROADMAP decía que
 * "no existía property test de roundtrip". Eso era falso, y la forma en que
 * era falso importa: la afirmación hacía creer que faltaba la técnica, cuando
 * lo que faltaba era la cobertura. Evidence tenía ley; suite y report no.
 *
 * Las tres familias NO comparten codec. Evidence va por
 * `EvidenceArtifactCodec`, que expone CBOR y JSON porque el contrato de ESA
 * familia declara los dos medios. Suite y report sólo declaran CBOR (familias 2
 * y 3 de `ARTIFACT_WIRE_CONTRACTS.md`), así que aquí no hay ley JSON porque no
 * hay `encodeToJson` que probar. Leer "el contrato menciona JSON" como "todas
 * las familias tienen JSON" fue el error que casi cometemos al revisar el
 * documento por encima, y por eso está escrito en el KDoc: para que la próxima
 * persona no lo repita.
 *
 * `checkAll` con 300 iteraciones y semilla fija, como en `EvidenceLawsTest`.
 * La semilla fija no es decorado: sin ella un fallo encontrado hoy sería
 * irreproducible mañana, y un fallo irreproducible no se arregla, se ignora.
 */
class SuiteReportLawsTest : AnnotationSpec() {

    /**
     * Igual que en `EvidenceLawsTest`: `Gen` porque en kotest 6.2.4 la firma
     * pública de `checkAll` acepta `Gen<A>` y `Arb<A>` es un `Gen<A>`. El spec
     * es un `AnnotationSpec`, no un `PropertySpec`, así que `checkAll` es una
     * función suspendida.
     */
    private suspend fun <A> law(iterations: Int, arb: Arb<A>, body: suspend (A) -> Unit) {
        checkAll(iterations, arb as Gen<A>) { body(it) }
    }

    // -------------------------------------------------------------------------
    // Suite — CBOR
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_suite_cbor_roundtrip_returns_the_canonical_form() {
        // NO es `decode(encode(x)) == x`: esa aserción sería falsa.
        //
        // El codec canoniza las colecciones al decodificar: una suite con las
        // lenses en orden inverso vuelve en orden canónico, y con duplicados en
        // `requiredEvidence` vuelve sin ellos. El roundtrip devuelve la FORMA
        // CANÓNICA de `x`, no `x`.
        //
        // La diferencia importa por una razón concreta: escribir `== x` da una
        // ley que falla y hay que "arreglar" debilitándola hasta que pasa, y el
        // resultado es un test que no dice nada. Escribir la ley verdadera desde
        // el principio evita las dos cosas.
        law(iterations = 300, arb = EvidenceArbs.richSuite()) { s ->
            SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(s)) shouldBe
                CanonicalEncoder.canonicalizeSuite(s)
        }
    }

    @Test
    suspend fun LAW_suite_canonical_form_is_a_fixed_point() {
        // La forma canónica es un punto fijo del roundtrip: canonizar dos
        // veces no cambia nada. Sin esta ley, un canonicalizador que ordenara
        // por una clave y dejara otra colección sin ordenar seguiría dando
        // verde en el roundtrip, y sólo se rompería al segundo viaje.
        law(iterations = 300, arb = EvidenceArbs.richSuite()) { s ->
            val canonica = CanonicalEncoder.canonicalizeSuite(s)
            CanonicalEncoder.canonicalizeSuite(canonica) shouldBe canonica
            SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(canonica)) shouldBe canonica
        }
    }

    @Test
    suspend fun LAW_suite_cbor_roundtrip_preserves_the_digest() {
        law(iterations = 300, arb = EvidenceArbs.richSuite()) { s ->
            val decoded = SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(s))
            CanonicalEncoder.digestSuite(decoded) shouldBe CanonicalEncoder.digestSuite(s)
        }
    }

    @Test
    suspend fun LAW_suite_permutation_yields_the_same_cbor_bytes() {
        // Segunda ley para M-A03, y es la que importa: el mutante quita el
        // `sortedBy` de `canonicalizeSuite`, y la ley de digest NO lo caza,
        // porque `digestSuite` ordena por su cuenta. El digest era correcto y
        // el artefacto no, que es el mismo modo de fallo que `correlations` y
        // que ya ha aparecido dos veces en este repo.
        //
        // Aquí se comparan BYTES, no valores ni digests. Con un valor, la
        // diferencia se perdería en la canonicalización posterior; con un
        // digest, la diferencia es invisible por construcción.
        law(iterations = 300, arb = EvidenceArbs.suitePermutationPair()) { (a, b) ->
            val ba = SuiteArtifactCodec.encodeToCbor(a)
            val bb = SuiteArtifactCodec.encodeToCbor(b)
            ba.contentEquals(bb) shouldBe true
        }
    }

    @Test
    suspend fun LAW_suite_digest_is_invariant_under_permutation() {
        // La suite es un conjunto de lenses y assertions, no una secuencia: dos
        // suites con las mismas lenses en distinto orden son la MISMA suite y
        // por eso mismo el mismo digest. Si esto falla hay dos bugs distintos
        // con el mismo síntoma: o el digest depende del orden (no es canónico),
        // o la suite está ordenada por significado (y entonces `reversed()`
        // no produce un artefacto equivalente). El síntoma no distingue; el
        // diagnóstico sí obliga a mirar las dos causas.
        law(iterations = 300, arb = EvidenceArbs.suitePermutationPair()) { (a, b) ->
            CanonicalEncoder.digestSuite(a) shouldBe CanonicalEncoder.digestSuite(b)
        }
    }

    @Test
    suspend fun LAW_suite_double_roundtrip_is_a_fixed_point() {
        law(iterations = 300, arb = EvidenceArbs.richSuite()) { s ->
            val once = SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(s))
            val twice = SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(once))
            twice shouldBe once
            SuiteArtifactCodec.encodeToCbor(twice) shouldBe SuiteArtifactCodec.encodeToCbor(once)
        }
    }

    @Test
    suspend fun LAW_report_correlation_permutation_yields_the_same_artifact() {
        // M-R04. Ésta es la ley que hace que el defecto no vuelva solo.
        //
        // El fallo real era: `ReportArtifactCodec` canonicalizaba `results`,
        // `gaps` y `artifacts`, pero NO `correlations`, mientras que
        // `digestReport` sí las ordenaba con `canonicalCorrelations`. Dos
        // informes con las mismas correlaciones en distinto orden producían
        // artefactos byte a byte DISTINTOS con el MISMO digest.
        //
        // Eso es el peor caso posible, y explica por qué no lo cazó la ley de
        // invariancia del digest: el digest era correcto. Todo lo que un
        // checksum puede comprobar, estaba bien. Lo que estaba mal era la
        // REPRESENTACIÓN, que el digest no cubre por definición.
        //
        // Por eso esta ley compara BYTES y no digests. Y por eso usa la
        // pareja permutada del GENERADOR y no una permutación fija: un
        // ejemplo hardcodeado pasa por el motivo equivocado en cuanto el
        // codificador cambia su representación, que es justo lo que pasó
        // aquí.
        law(iterations = 300, arb = EvidenceArbs.reportPermutationPair()) { (a, b) ->
            // El digest: correcto, y por eso no habría detectado nada.
            CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)

            // Los BYTES: esto es lo que estaba roto. Digest igual, artefacto
            // distinto. El mismo digest para dos artefactos distintos es una
            // colisión que se infringe a sí mismo, y ningún checksum la
            // delata por definición.
            ReportArtifactCodec.encodeToCbor(a) shouldBe
                ReportArtifactCodec.encodeToCbor(b)
        }
    }

    // -------------------------------------------------------------------------
    // Report — CBOR
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_report_cbor_roundtrip_returns_the_canonical_form() {
        // Misma razón que en la suite: el codec canoniza `results`, `gaps`,
        // `artifacts` y `correlations` al decodificar. El roundtrip devuelve la
        // forma canónica del informe, no el informe tal como llegó.
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r)) shouldBe
                CanonicalEncoder.canonicalizeReport(r)
        }
    }

    @Test
    suspend fun LAW_report_canonical_form_is_a_fixed_point() {
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val canonico = CanonicalEncoder.canonicalizeReport(r)
            CanonicalEncoder.canonicalizeReport(canonico) shouldBe canonico
            ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(canonico)) shouldBe canonico
        }
    }

    @Test
    suspend fun LAW_report_cbor_roundtrip_preserves_the_digest() {
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val decoded = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
            CanonicalEncoder.digestReport(decoded) shouldBe CanonicalEncoder.digestReport(r)
        }
    }

    @Test
    suspend fun LAW_report_digest_is_invariant_under_permutation() {
        law(iterations = 300, arb = EvidenceArbs.reportPermutationPair()) { (a, b) ->
            CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)
        }
    }

    @Test
    suspend fun LAW_report_double_roundtrip_is_a_fixed_point() {
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val once = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
            val twice = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(once))
            twice shouldBe once
            ReportArtifactCodec.encodeToCbor(twice) shouldBe ReportArtifactCodec.encodeToCbor(once)
        }
    }

    // -------------------------------------------------------------------------
    // Leyes que van más allá del roundtrip
    //
    // "Decode(encode(x)) == x" es la ley fuerte, pero no es la única que
    // importa. Estas leyes atacan lo que el roundtrip NO puede detectar por sí
    // solo: subtipos que se funden entre sí conservando clase, conteo y digest.
    // Un decoder así tiene un roundtrip "perfecto" sobre cualquier aserción
    // gruesa, y produce informes que dicen algo distinto de lo que midieron.
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_counterexample_variant_survives_the_roundtrip() {
        // Cada subtipo de `Counterexample` tiene su propio DTO. Si el decoder
        // los funde todos a `Cycle`, conserva el conteo de resultados, la clase
        // `Failed` y el digest: el roundtrip parece intacto y el diagnóstico
        // es falso. Comparar subtipos es lo que lo delata.
        //
        // Se comparan como CONJUNTO, no como lista: el codec reordena
        // `results` al canonicalizar, así que comparar en orden daría falsos
        // positivos que taparían el defecto que se quiere cazar. El conjunto
        // pierde el orden, y el orden es justo lo que esta ley no mide: lo
        // miden la ley de forma canónica y la de invariancia bajo permutación.
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val antes = r.results
                .filterIsInstance<AssertionResult.Failed>()
                .map { it.counterexample::class }
                .toSet()
            val despues = ReportArtifactCodec
                .decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
                .results
                .filterIsInstance<AssertionResult.Failed>()
                .map { it.counterexample::class }
                .toSet()
            despues shouldBe antes
        }
    }

    @Test
    suspend fun LAW_unsupported_reason_survives_the_roundtrip_verbatim() {
        // Un `UnsupportedReason` degradado a `UnknownOperator` sigue siendo
        // `Unsupported`: el decode no falla y una aserción que sólo mirase el
        // veredicto pasaría. Por eso esta ley compara el subtipo de la razón.
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val antes = r.results
                .filterIsInstance<AssertionResult.Unsupported>()
                .map { it.reason::class }
                .toSet()
            val despues = ReportArtifactCodec
                .decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
                .results
                .filterIsInstance<AssertionResult.Unsupported>()
                .map { it.reason::class }
                .toSet()
            despues shouldBe antes
        }
    }

    @Test
    suspend fun LAW_gap_reason_survives_the_roundtrip_verbatim() {
        // Misma razón que arriba, en la colección `gaps`: `Lost` y `Unknown`
        // significan cosas opuestas para el veredicto (`no lo sé` frente a
        // `se perdió`), así que fundenlos cambia el significado del informe sin
        // cambiar ni el tamaño ni el digest.
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val antes = r.gaps.map { it.reason::class }.toSet()
            val despues = ReportArtifactCodec
                .decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
                .gaps
                .map { it.reason::class }
                .toSet()
            despues shouldBe antes
        }
    }

    @Test
    suspend fun LAW_report_never_invents_results() {
        // El informe no puede ganar resultados al viajar: un artefacto con dos
        // resultados que se decodifica con tres ha fabricado evidencia. El
        // conteo es la aserción más fuerte y la más barata; la igualdad elemento
        // a elemento ya la cubre la ley de identidad.
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val decoded = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
            decoded.results.size shouldBe r.results.size
            decoded.gaps.size shouldBe r.gaps.size
            decoded.artifacts.size shouldBe r.artifacts.size
            decoded.correlations.size shouldBe r.correlations.size
        }
    }

    @Test
    suspend fun LAW_evaluation_identity_survives_the_roundtrip() {
        // `AssuranceEvaluationId` es la identidad de la evaluación (ADR-008).
        // Un decoder que lo regenera produce dos informes del mismo estudio con
        // ids distintos, y la deduplicación por evaluación deja de funcionar.
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val decoded = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))
            decoded.evaluationId shouldBe AssuranceEvaluationId(r.evaluationId.value)
            decoded.engineVersion shouldBe r.engineVersion
        }
    }

    /**
     * Dos informes que sólo difieren en el snapshot que consumieron NO pueden
     * compartir digest.
     *
     * Ésta es la ley con consecuencias: si la cumpliera el mutante, el informe
     * de un snapshot limpio y el de un snapshot con evidencia eliminada serían
     * el mismo artefacto, y la auditoría no distinguiría "todo bien" de "no
     * miramos lo que pasó".
     */
    @Test
    suspend fun LAW_snapshot_digest_distinguishes_reports() {
        law(iterations = 300, arb = EvidenceArbs.report()) { r ->
            val otro = r.copy(snapshotDigest = Digest.ofUtf8("otro-${r.evaluationId.value}"))
            CanonicalEncoder.digestReport(otro) shouldNotBe CanonicalEncoder.digestReport(r)
        }
    }

    // -------------------------------------------------------------------------
    // Leyes sobre el GENERADOR
    //
    // Sin estas, "todas las leyes verdes" no significa "todo está probado".
    //
    // El fallo que atacan es concreto: alguien añade un subtipo nuevo a
    // `Counterexample`, cablea su DTO, y las leyes siguen en verde porque el
    // generador nunca produce ese subtipo. El property test pasa, el gate pasa,
    // y el subtipo nuevo no se ha probado. Verde por no haber mirado, que es
    // la forma más cara de estar verde.
    //
    // Por eso la cobertura se comprueba sobre el CORPUS ACUMULADO y no sobre
    // una muestra: 300 informes con 0..4 resultados tienen ~1500 resultados, y
    // una sola muestra casi nunca contiene los seis subtipos a la vez.
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_generator_covers_every_result_variant() {
        val vistas = mutableSetOf<KClass<out AssertionResult>>()
        checkAll(iterations = 300, EvidenceArbs.report() as Gen<AssuranceReport>) { r ->
            r.results.forEach { vistas += it::class }
        }
        vistas shouldBe AssertionResult::class.sealedSubclasses.toSet()
    }

    @Test
    suspend fun LAW_generator_covers_every_counterexample_variant() {
        val vistas = mutableSetOf<KClass<out Counterexample>>()
        checkAll(iterations = 300, EvidenceArbs.report() as Gen<AssuranceReport>) { r ->
            r.results
                .filterIsInstance<AssertionResult.Failed>()
                .forEach { vistas += it.counterexample::class }
        }
        vistas shouldBe Counterexample::class.sealedSubclasses.toSet()
    }

    @Test
    suspend fun LAW_generator_covers_every_unsupported_reason() {
        val vistas = mutableSetOf<KClass<out UnsupportedReason>>()
        checkAll(iterations = 300, EvidenceArbs.report() as Gen<AssuranceReport>) { r ->
            r.results
                .filterIsInstance<AssertionResult.Unsupported>()
                .forEach { vistas += it.reason::class }
        }
        vistas shouldBe UnsupportedReason::class.sealedSubclasses.toSet()
    }

    @Test
    suspend fun LAW_generator_covers_every_gap_reason() {
        val vistas = mutableSetOf<KClass<out EvidenceGap.GapReason>>()
        checkAll(iterations = 300, EvidenceArbs.report() as Gen<AssuranceReport>) { r ->
            r.gaps.forEach { vistas += it.reason::class }
        }
        vistas shouldBe EvidenceGap.GapReason::class.sealedSubclasses.toSet()
    }

    /**
     * El generador tiene que producir reports con cero resultados.
     *
     * Una arista que no aparece en ninguna muestra es una arista sin probar, y
     * en un informe "cero resultados" es la arista más peligrosa: es la forma
     * que se confunde con "no había nada que evaluar", que es justo el estado
     * que el diseño dice que NUNCA es `PASS`.
     */
    @Test
    suspend fun LAW_generator_produces_empty_reports() {
        var vistos = 0
        checkAll(iterations = 300, EvidenceArbs.report() as Gen<AssuranceReport>) { r ->
            if (r.results.isEmpty()) vistos++
        }
        vistos shouldBeGreaterThan 0
    }
}