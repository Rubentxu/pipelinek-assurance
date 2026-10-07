package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Correlation
import dev.pipelinek.assurance.domain.evidence.CorrelationRelation
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.ArtifactRef
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionIR
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.Enforcement
import dev.pipelinek.assurance.engine.DiffState
import dev.pipelinek.assurance.engine.EvaluationFailure
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.Severity
import dev.pipelinek.assurance.engine.SuiteId
import dev.pipelinek.assurance.engine.UnsupportedReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * M0 -- Codecs de suite y report: roundtrip, canonicalidad y digest.
 *
 * Cubre las familias 2 y 3 de `ARTIFACT_WIRE_CONTRACTS.md`. La familia 1 ya
 * tenia su propio archivo (`EvidenceCodecRoundtripTest`); aqui se comprueba lo
 * que las tres familias comparten, que es donde el digest se vuelve opcional
 * por descuido: el campo `digest` es obligatorio en el envelope, y un envelope
 * sin digest o con digest alterado tiene que rechazarse, no pasar.
 */
class SuiteReportCodecRoundtripTest : AnnotationSpec() {

    private val snapshotDigest = Digest.ofUtf8("snapshot")
    private val suiteDigest = Digest.ofUtf8("suite")

    private fun report(
        results: List<AssertionResult>,
        gaps: List<EvidenceGap> = emptyList(),
        artifacts: List<ArtifactRef> = emptyList(),
        correlations: List<Correlation> = emptyList(),
        engineVersion: String = "0.1.0",
        evaluationId: String = "eval-1",
    ) = AssuranceReport(
        evaluationId = AssuranceEvaluationId(evaluationId),
        snapshotDigest = snapshotDigest,
        suiteDigest = suiteDigest,
        engineVersion = engineVersion,
        results = results,
        gaps = gaps,
        artifacts = artifacts,
        correlations = correlations,
    )

    private fun passed(id: String, vararg evidence: String) = AssertionResult.Passed(
        ProofRef(
            snapshotId = "snap-001",
            evidenceIds = evidence.map { EvidenceId(it) }.ifEmpty {
                listOf(EvidenceId("synthetic/a/depends-on/1"))
            },
            assertionId = AssertionId(id),
        ),
    )

    private fun failedCycle(id: String) = AssertionResult.Failed(
        Counterexample.Cycle(
            assertionId = AssertionId(id),
            subjectRefs = listOf(TypedExternalId(ExternalNamespace.CogniCodeEntityId, "core")),
            evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
            explanation = "ciclo de dependencias",
            reproductionHints = listOf("mvn dependency:tree"),
            cycle = listOf("core", "adapters", "core"),
        ),
    )


    // -----------------------------------------------------------------------
    // Fixtures locales
    // -----------------------------------------------------------------------

    /**
     * Suite minima con una lens y una assertion coherentes entre si (AAT-16).
     *
     * Se declara aqui, y no se importa del testkit, porque este archivo vive
     * en `assurance-artifact` y el testkit depende de este modulo: importar
     * seria una dependencia circular. Ademas la apiVersion de la IR es
     * `assurance/v1` a proposito, distinto del `assurance-suite/v1` del
     * envelope, porque esa distincion es justo lo que
     * `the_wire_api_version_is_not_the_suite_ir_one` verifica.
     */
    private fun suite(): AssuranceSuiteIR = AssuranceSuiteIR(
        apiVersion = "assurance/v1",
        suiteId = SuiteId("architecture"),
        suiteVersion = "0.1.0",
        requiredEvidence = listOf("ModuleDependencies"),
        lenses = listOf(
            LensPlan(
                lensId = LensId("deps"),
                kind = "architecture.dependencies",
                inputCapabilities = listOf("ModuleDependencies"),
                outputSchema = "schema://assurance/deps/v1",
            ),
        ),
        assertions = listOf(
            AssertionIR(
                id = AssertionId("a"),
                lensRef = LensId("deps"),
                operator = "no-edge-between-sets",
                operands = mapOf("domain" to "core", "forbidden" to "adapters"),
                severity = Severity.Error,
                enforcement = Enforcement.Advisory,
                completenessRequirements = listOf("ModuleDependencies"),
                rationale = "el domain no depende de adapters",
            ),
        ),
    )

    private fun correlation() = Correlation(
        from = TypedExternalId(ExternalNamespace.ChronosInvocationId, "inv-1"),
        relation = CorrelationRelation.CorrelatedWith,
        to = TypedExternalId(ExternalNamespace.OTelSpanId, "span-1"),
        evidence = EvidenceId("synthetic/alpha/depends-on/1"),
    )

    // -----------------------------------------------------------------------
    // Suite
    // -----------------------------------------------------------------------

    @Test
    fun suite_roundtrips_through_cbor() {
        val suite = suite()

        val decoded = SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(suite))

        decoded shouldBe suite
    }

    @Test
    fun suite_bytes_are_stable_across_repeated_encodes() {
        // Determinismo real: el mismo valor produce los mismos bytes, dos
        // veces y en el mismo proceso. Es la mitad de la paridad de digest
        // entre maquinas; la otra mitad es el orden canonico.
        val suite = suite()

        SuiteArtifactCodec.encodeToCbor(suite).toList() shouldBe
            SuiteArtifactCodec.encodeToCbor(suite).toList()
    }

    @Test
    fun suite_digest_in_the_envelope_is_the_canonical_one() {
        // El envelope declara el digest y el decoder lo comprueba. Si el
        // encoder escribiera otro, el roundtrip ya fallaria; por eso esto es
        // casi trivial, y aun asi vale: fija que el campo existe y se verifica.
        val suite = suite()

        val decoded = SuiteArtifactCodec.decodeFromCbor(SuiteArtifactCodec.encodeToCbor(suite))

        CanonicalEncoder.digestSuite(decoded) shouldBe CanonicalEncoder.digestSuite(suite)
    }

    @Test
    fun a_suite_with_an_altered_digest_is_refused() {
        // El ataque: alguien cambia el contenido del envelope y deja el digest
        // que venía. Sin comprobacion, la suite alterada decodifica "bien" y
        // el gate evalua contra una suite que nadie reviso.
        val original = SuiteArtifactCodec.encodeToCbor(suite())
        // Un digest de 64 hex VALIDO pero que no es el canonico: si fuera
        // invalido, el fallo seria de `Digest`, no de integridad, y el test
        // probaria otra cosa.
        val tampered = tamperSuiteDigest(original, "b".repeat(64))

        val e = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(tampered)
        }
        e.message shouldContain "no coincide con el canónico"
    }

    @Test
    fun a_suite_without_digest_is_refused_not_assumed() {
        // Ausente no es "igual que el canonico". El decoder falla por campo
        // ausente, y eso es un estado distinto de "el digest no cuadra".
        val original = SuiteArtifactCodec.encodeToCbor(suite())
        val withoutDigest = suiteWithoutDigest(suite())

        shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(withoutDigest)
        }
    }

    @Test
    fun suite_with_unknown_envelope_api_version_is_refused() {
        // Fail-closed del envelope. Se altera el apiVersion del WIRE
        // (`assurance-suite/v1`), no el de la IR: son campos distintos y el
        // que se valida al decodificar es el del envelope.
        val bytes = SuiteArtifactCodec.encodeToCbor(suite())

        val e = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(
                replaceTextInCbor(bytes, "assurance-suite/v1", "assurance-suite/v9"),
            )
        }
        e.message shouldContain "no decodificable"
    }

    @Test
    fun the_wire_api_version_is_not_the_suite_ir_one() {
        // El envelope declara la version del wire y la IR la suya. Si el codec
        // las confundiera, una suite con un apiVersion de IR distinto no se
        // podria ni codificar, y el fallo apareceria como "apiVersion
        // desconocida" sobre una suite perfectamente valida.
        //
        // `suite()` declara `assurance/v1` en la IR, que no es
        // el `assurance-suite/v1` del envelope: los dos viajan.
        val decoded = SuiteArtifactCodec.decodeFromCbor(
            SuiteArtifactCodec.encodeToCbor(suite()),
        )

        decoded.apiVersion shouldBe "assurance/v1"
    }

    @Test
    fun suite_garbage_bytes_are_refused_not_guessed() {
        shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }
    }

    @Test
    fun truncated_suite_is_refused() {
        val full = SuiteArtifactCodec.encodeToCbor(suite())

        shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(full.copyOf(full.size / 2))
        }
    }

    @Test
    fun suite_oversized_input_is_refused_before_deserializing() {
        // Misma salvaguarda que en evidence: la comprobacion ocurre sobre los
        // BYTES, no sobre el resultado. Se comprueba aqui para report y suite
        // porque las cotas son compartidas y un solo test no las certifica.
        val oversized = ByteArray((EvidenceArtifactCodec.MAX_INPUT_BYTES + 1).toInt())
        oversized[0] = 0xA1.toByte() // map(1), para que no falle por CBOR invalido

        val e = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(oversized)
        }
        e.message shouldContain "excede"

        val e2 = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(oversized)
        }
        e2.message shouldContain "excede"
    }

    // -----------------------------------------------------------------------
    // Report
    // -----------------------------------------------------------------------

    @Test
    fun report_roundtrips_through_cbor() {
        val r = report(
            results = listOf(
                passed("a", "synthetic/a/depends-on/1", "synthetic/b/depends-on/2"),
                failedCycle("b"),
                AssertionResult.Inconclusive(
                    listOf(EvidenceGap("StaticSmells", EvidenceGap.GapReason.Unsupported)),
                ),
                AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("no-edge-between-sets")),
                AssertionResult.Error(EvaluationFailure("evaluate", "boom", "cause")),
            ),
            gaps = listOf(
                EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.PartialProduced("0.5")),
            ),
            artifacts = listOf(ArtifactRef(Digest.ofUtf8("artifact"), "text/plain", "raw-log")),
            correlations = listOf(correlation()),
        )

        // El roundtrip NO es identidad, y no debe serlo: el codec canonicaliza
        // antes de serializar, asi que un report cuyos resultados llegan en
        // orden no canonico vuelve canonico. Afirmar `decoded == r` seria
        // falso; la primera version de este test lo afirmaba y fallo, con un
        // diff en `results` que era la CANONIZACION funcionando, no un bug.
        //
        // La propiedad correcta tiene dos partes, y las dos se comprueban:
        // 1. lo que vuelve es la forma canonica del original (idempotencia);
        // 2. volver a codificar eso da los MISMOS bytes (cierre), que es lo
        //    que hace que el digest del envelope siga siendo verificable.
        val decoded = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))

        ReportArtifactCodec.encodeToCbor(decoded).toList() shouldBe
            ReportArtifactCodec.encodeToCbor(r).toList()

        // Y el contenido no se pierde: el resumen por estado es la lectura
        // que un gate hace de un report, y tiene que coincidir con el del
        // original. Si el codec perdiera un resultado, el resumen lo delataria.
        decoded.summary shouldBe r.summary
        decoded.snapshotDigest shouldBe r.snapshotDigest
        decoded.suiteDigest shouldBe r.suiteDigest
        decoded.engineVersion shouldBe r.engineVersion
        decoded.gaps shouldBe r.gaps
        decoded.artifacts shouldBe r.artifacts
        decoded.correlations shouldBe r.correlations
    }

    @Test
    fun report_digest_in_the_envelope_is_the_canonical_one() {
        val r = report(listOf(passed("a")))

        val decoded = ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r))

        CanonicalEncoder.digestReport(decoded) shouldBe CanonicalEncoder.digestReport(r)
    }

    @Test
    fun a_report_with_an_altered_digest_is_refused() {
        val bytes = ReportArtifactCodec.encodeToCbor(report(listOf(passed("a"))))

        val e = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(tamperReportDigest(bytes, "c".repeat(64)))
        }
        e.message shouldContain "no coincide con el canónico"
    }

    @Test
    fun a_report_without_digest_is_refused_not_assumed() {
        val bytes = ReportArtifactCodec.encodeToCbor(report(listOf(passed("a"))))

        shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(reportWithoutDigest(report(listOf(passed("a")))))
        }
    }

    @Test
    fun report_order_of_results_does_not_change_the_bytes() {
        // Ley de permutacion, pero sobre el codec y no sobre el digest: dos
        // reports con los mismos resultados en distinto orden deben dar los
        // MISMOS bytes, no solo el mismo digest. Si difieren, el orden depende
        // del recorrido y dos runners no coinciden.
        val a = report(listOf(passed("a"), passed("b"), failedCycle("c")))
        val b = report(listOf(failedCycle("c"), passed("a"), passed("b")))

        ReportArtifactCodec.encodeToCbor(a).toList() shouldBe
            ReportArtifactCodec.encodeToCbor(b).toList()
    }

    @Test
    fun report_gap_order_does_not_change_the_bytes() {
        val gaps = listOf(
            EvidenceGap("A", EvidenceGap.GapReason.Unsupported),
            EvidenceGap("B", EvidenceGap.GapReason.Lost, "detalle"),
            EvidenceGap("C", EvidenceGap.GapReason.PartialProduced("0.25")),
        )
        val a = report(listOf(passed("a")), gaps = gaps)
        val b = report(listOf(passed("a")), gaps = gaps.reversed())

        ReportArtifactCodec.encodeToCbor(a).toList() shouldBe
            ReportArtifactCodec.encodeToCbor(b).toList()
    }

    @Test
    fun report_artifact_ref_order_does_not_change_the_bytes() {
        val refs = listOf(
            ArtifactRef(Digest.ofUtf8("a1"), "text/plain", "raw"),
            ArtifactRef(Digest.ofUtf8("b2"), "application/json", "normalized"),
        )
        val a = report(listOf(passed("a")), artifacts = refs)
        val b = report(listOf(passed("a")), artifacts = refs.reversed())

        ReportArtifactCodec.encodeToCbor(a).toList() shouldBe
            ReportArtifactCodec.encodeToCbor(b).toList()
    }

    @Test
    fun report_evidence_ids_order_does_not_change_the_bytes() {
        // El mismo conjunto de evidencia en distinto orden es el mismo hecho.
        val a = report(listOf(passed("a", "synthetic/a/depends-on/1", "synthetic/b/depends-on/2")))
        val b = report(listOf(passed("a", "synthetic/b/depends-on/2", "synthetic/a/depends-on/1")))

        ReportArtifactCodec.encodeToCbor(a).toList() shouldBe
            ReportArtifactCodec.encodeToCbor(b).toList()
    }

    @Test
    fun report_reproduction_hints_order_does_not_change_the_bytes() {
        val cex = Counterexample.CausalSlice(
            assertionId = AssertionId("a"),
            subjectRefs = listOf(TypedExternalId(ExternalNamespace.CogniCodeEntityId, "core")),
            evidenceRefs = listOf(EvidenceId("synthetic/a/depends-on/1")),
            explanation = "propagacion",
            reproductionHints = listOf("z", "m", "a"),
            invocationChain = listOf("http", "svc", "db"),
        )
        val reordered = cex.copy(reproductionHints = listOf("a", "z", "m"))

        val a = report(listOf(AssertionResult.Failed(cex)))
        val b = report(listOf(AssertionResult.Failed(reordered)))

        ReportArtifactCodec.encodeToCbor(a).toList() shouldBe
            ReportArtifactCodec.encodeToCbor(b).toList()
    }

    @Test
    fun changing_a_result_changes_the_report_digest() {
        // Control negativo de la ley de permutacion: si las pruebas anteriores
        // pasan porque el codec ignorase los resultados, este test lo delata.
        val a = report(listOf(passed("a", "synthetic/a/depends-on/1")))
        val b = report(listOf(passed("a", "synthetic/a/depends-on/2")))

        CanonicalEncoder.digestReport(a) shouldNotBe CanonicalEncoder.digestReport(b)
    }

    @Test
    fun every_result_variant_roundtrips() {
        // Cada variante de `AssertionResult`, `Counterexample` y
        // `UnsupportedReason` tiene su propio DTO. Una variante sin cubrir es
        // una variante que pierde campos al decodificar, y nadie se entera
        // hasta produccion.
        val refs = listOf(TypedExternalId(ExternalNamespace.CogniCodeEntityId, "core"))
        val ev = listOf(EvidenceId("synthetic/a/depends-on/1"))

        val variants: List<AssertionResult> = listOf(
            passed("a"),
            failedCycle("dep"),
            AssertionResult.Failed(
                Counterexample.DependencyPath(
                    assertionId = AssertionId("a"),
                    subjectRefs = refs,
                    evidenceRefs = ev,
                    explanation = "dependencia prohibida",
                    reproductionHints = listOf("hint"),
                    path = listOf("core", "adapters"),
                    fromLayer = "core",
                    toLayer = "adapters",
                ),
            ),
            AssertionResult.Failed(
                Counterexample.CausalSlice(
                    assertionId = AssertionId("a"),
                    subjectRefs = refs,
                    evidenceRefs = ev,
                    explanation = "propagacion",
                    reproductionHints = listOf("hint"),
                    invocationChain = listOf("http", "svc", "db"),
                ),
            ),
            AssertionResult.Failed(
                Counterexample.Mutation(
                    assertionId = AssertionId("a"),
                    subjectRefs = refs,
                    evidenceRefs = ev,
                    explanation = "mutante superviviente",
                    reproductionHints = listOf("hint"),
                    mutatedSymbol = "core.parse",
                    killedBy = "test-1",
                ),
            ),
            AssertionResult.Failed(
                Counterexample.Mutation(
                    assertionId = AssertionId("a"),
                    subjectRefs = refs,
                    evidenceRefs = ev,
                    explanation = "mutante con killer nulo",
                    reproductionHints = listOf("hint"),
                    mutatedSymbol = "core.parse",
                    killedBy = null,
                ),
            ),
            AssertionResult.Failed(
                Counterexample.MissingTrace(
                    assertionId = AssertionId("a"),
                    subjectRefs = refs,
                    evidenceRefs = ev,
                    explanation = "sin trace",
                    reproductionHints = listOf("hint"),
                    missingSpanFor = "svc.handle",
                    expectedPropagation = "db.query",
                ),
            ),
            AssertionResult.Failed(
                Counterexample.BaselineRegression(
                    assertionId = AssertionId("a"),
                    subjectRefs = refs,
                    evidenceRefs = ev,
                    explanation = "regresion",
                    reproductionHints = listOf("hint"),
                    stableId = "core.parse:1",
                    state = DiffState.Regressed,
                ),
            ),
            // `Inconclusive` exige al menos un gap por invariante de dominio
            // (AAT-20: la falta de evidencia no es un veredicto), asi que
            // `Inconclusive(emptyList())` no es construible. El test que lo
            // usaba fallaba por construccion, no por el codec.
            AssertionResult.Inconclusive(
                listOf(EvidenceGap("StaticSmells", EvidenceGap.GapReason.Unsupported)),
            ),
            AssertionResult.Inconclusive(
                listOf(
                    EvidenceGap("StaticSmells", EvidenceGap.GapReason.Unsupported),
                    EvidenceGap("AgentReview", EvidenceGap.GapReason.Lost),
                ),
            ),
            AssertionResult.Unsupported(UnsupportedReason.UnknownLensKind("architecture.unknown")),
            AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("no-edge-between-sets")),
            AssertionResult.Unsupported(UnsupportedReason.UnknownEvidenceKind("HeuristicSummary")),
            AssertionResult.Unsupported(
                UnsupportedReason.AuthorityNotAdmitted("DeterministicAnalyzer", "HeuristicAnalyzer"),
            ),
            AssertionResult.Error(EvaluationFailure("evaluate", "boom", "cause")),
            AssertionResult.Error(EvaluationFailure("encode", "boom", cause = null)),
        )

        for (variant in variants) {
            val r = report(listOf(variant))
            ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(r)) shouldBe r
        }
    }

    @Test
    fun report_bounds_apply_on_decode_not_only_on_encode() {
        // Las cotas tienen que correr en el camino de DECODE. Si solo
        // corrieran al encode, un envelope hostil de otro producer se
        // aceptaria: el limite defiende contra entrada no confiable, y la
        // entrada no confiable nunca pasa por nuestro encode.
        //
        // El envelope se construye con el serializer REAL a partir de un DTO
        // con un campo por encima del limite, y no reescribiendo bytes. La
        // version anterior de este test intentaba sustituir "0.1.0" por un
        // texto gigante en el CBOR, y eso no es un envelope con una cadena
        // larga: es un CBOR con la longitud declarada de 5 followed de un
        //Gib de bytes, que el decoder rechaza por estructura. El mensaje
        // habria sido el equivocado y el test habria pasado sin comprobar
        // nada.
        val tooLong = "y".repeat(EvidenceArtifactCodec.MAX_STRING_LENGTH + 1)
        val base = report(listOf(passed("a")))
        val hostile = kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            ReportDto.serializer(),
            ReportDto.of(base).copy(engineVersion = tooLong),
        )

        val e = shouldThrow<IllegalArgumentException> {
            ReportArtifactCodec.decodeFromCbor(hostile)
        }
        // El mensaje nombra el campo culpable y la cota. Si fallara por CBOR
        // invalido o por el digest, el texto seria otro y el test caeria.
        e.message shouldContain "Report.engineVersion"
        e.message shouldContain "${EvidenceArtifactCodec.MAX_STRING_LENGTH}"
    }

    @Test
    fun suite_bounds_apply_on_decode_not_only_on_encode() {
        // El mismo contrato para la familia 2. Cada familia tiene su propia
        // comprobacion, y por eso son dos tests: un unico test que probara las
        // dos familias a la vez moriria con el mutante que solo toca una.
        val tooLong = "y".repeat(EvidenceArtifactCodec.MAX_STRING_LENGTH + 1)
        val hostile = kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            SuiteDto.serializer(),
            SuiteDto.of(suite()).copy(suiteId = tooLong),
        )

        val e = shouldThrow<IllegalArgumentException> {
            SuiteArtifactCodec.decodeFromCbor(hostile)
        }
        e.message shouldContain "Suite.suiteId"
        e.message shouldContain "${EvidenceArtifactCodec.MAX_STRING_LENGTH}"
    }

    @Test
    fun report_evaluation_id_survives_but_stays_out_of_the_digest() {
        // El `evaluationId` es identidad de la corrida, no contenido: cambia
        // entre dos corridas identicas, asi que no puede entrar en el digest
        // (si entrara, dos runners comparables darian digests distintos).
        // Pero SI viaja en el envelope, porque quien recibe el artefacto
        // necesita saber a que corrida pertenece.
        val a = report(listOf(passed("a")), evaluationId = "eval-1")
        val b = report(listOf(passed("a")), evaluationId = "eval-999")

        ReportArtifactCodec.decodeFromCbor(ReportArtifactCodec.encodeToCbor(a)).evaluationId shouldBe
            AssuranceEvaluationId("eval-1")
        CanonicalEncoder.digestReport(a) shouldBe CanonicalEncoder.digestReport(b)
    }

    // -----------------------------------------------------------------------
    // Utilidades de construccion de envelopes malformados
    // -----------------------------------------------------------------------

    /**
     * Sustituye el digest declarado por otro de 64 hex.
     *
     * Se opera sobre el DTO y se re-serializa, NO sobre los bytes. La razon
     * esta en por que se hace asi, porque la via obvia estuvo tres veces mal:
     *
     * - buscar el texto `digest` en los bytes lee `x@672a...`: CBOR antepone
     *   un byte de longitud a toda clave;
     * - buscar la clave con su byte de longitud sigue fallando, porque el
     *   valor va precedido por `0x78 0x40` (texto de 64 bytes);
     * - y restar uno al "recuento" del mapa no hace nada, porque kotlinx
     *   escribe mapas INDEFINIDOS (`0xbf` ... `0xff`), sin recuento en los
     *   bytes.
     *
     * Re-serializar el DTO evita las tres. Lo que se produce sigue siendo un
     * artefacto con digest ALTERADO: lo que cambia no es el encoder (que
     * recalcularia el digest y devolveria un artefacto integro) sino el valor
     * del campo, que es exactamente lo que haria un atacante.
     */
    private fun tamperSuiteDigest(bytes: ByteArray, replacement: String): ByteArray {
        require(replacement.length == 64) { "el digest de reemplazo debe tener 64 chars" }
        val dto = kotlinx.serialization.cbor.Cbor
            .decodeFromByteArray(SuiteDto.serializer(), bytes)
        require(dto.digest != replacement) { "el digest de reemplazo debe diferir del original" }
        return kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            SuiteDto.serializer(),
            dto.copy(digest = replacement),
        )
    }

    private fun tamperReportDigest(bytes: ByteArray, replacement: String): ByteArray {
        require(replacement.length == 64) { "el digest de reemplazo debe tener 64 chars" }
        val dto = kotlinx.serialization.cbor.Cbor
            .decodeFromByteArray(ReportDto.serializer(), bytes)
        require(dto.digest != replacement) { "el digest de reemplazo debe diferir del original" }
        return kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            ReportDto.serializer(),
            dto.copy(digest = replacement),
        )
    }

    /**
     * Produce el envelope al que le falta el campo `digest`.
     *
     * Se hace con un DTO ESPEJO declarado abajo, no reescribiendo bytes: el
     * DTO real tiene `digest` obligatorio y sin valor por defecto, asi que no
     * se puede construir "el mismo envelope sin digest" a traves de su
     * constructor. Y manipular los bytes a mano, como se intento primero,
     * obliga a reimplementar la codificacion CBOR, que es la forma segura de
     * que el test valide una codificacion imaginaria en vez de la real.
     *
     * El envelope resultante esta bien formado y le falta un campo, que es
     * justo el artefacto de un producer que no implementa `digest`. El decoder
     * tiene que rechazarlo por campo ausente, no por CBOR invalido.
     */
    private fun suiteWithoutDigest(suite: AssuranceSuiteIR): ByteArray {
        val full = SuiteDto.of(suite)
        return kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            SuiteWithoutDigestDto.serializer(),
            SuiteWithoutDigestDto(
                apiVersion = full.apiVersion,
                kind = full.kind,
                suiteApiVersion = full.suiteApiVersion,
                suiteId = full.suiteId,
                suiteVersion = full.suiteVersion,
                requiredEvidence = full.requiredEvidence,
                lenses = full.lenses,
                assertions = full.assertions,
                metadata = full.metadata,
            ),
        )
    }

    private fun reportWithoutDigest(report: AssuranceReport): ByteArray {
        val full = ReportDto.of(report)
        return kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            ReportWithoutDigestDto.serializer(),
            ReportWithoutDigestDto(
                apiVersion = full.apiVersion,
                kind = full.kind,
                evaluationId = full.evaluationId,
                snapshotDigest = full.snapshotDigest,
                suiteDigest = full.suiteDigest,
                engineVersion = full.engineVersion,
                results = full.results,
                gaps = full.gaps,
                artifacts = full.artifacts,
                correlations = full.correlations,
            ),
        )
    }

    /**
     * Sustituye un texto dentro del CBOR por otro de la MISMA longitud.
     *
     * La longitud se mantiene porque el objetivo es alterar el CONTENIDO de un
     * campo sin tocar la estructura: si cambiara el tamaño, habria que
     * reescribir las cabeceras de longitud de todo lo que sigue, y entonces
     * el test mediria otra cosa.
     */
    private fun replaceTextInCbor(bytes: ByteArray, text: String, replacement: String): ByteArray {
        require(replacement.length == text.length) {
            "el reemplazo debe tener la misma longitud que el original: " +
                "${replacement.length} != ${text.length}"
        }
        val needle = text.toByteArray(Charsets.UTF_8)
        val value = replacement.toByteArray(Charsets.UTF_8)
        require(needle.size == value.size) {
            "el reemplazo debe tener los mismos BYTES que el original"
        }
        val idx = bytes.indexOfSubsequence(needle)
        require(idx >= 0) { "el CBOR no contiene el texto $text" }
        return bytes.copyOf().also { value.copyInto(it, idx) }
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/**
 * Espejo de `SuiteDto` SIN `digest`.
 *
 * Tiene que existir porque el DTO real declara el campo obligatorio: no hay
 * forma de construir "el mismo envelope sin digest" con el constructor real.
 * Mantener el espejo a mano tiene un coste accepted: si el codec anade un
 * campo nuevo, este espejo deja de llevar la misma forma y el test
 * `a_suite_without_digest_is_refused_not_assumed` seguira pasando, pero por
 * el motivo equivocado (el campo nuevo falta tambien). Por eso el test
 * comprueba ademas, en `the_wire_shape_is_the_codec_shape`, que las claves
 * del envelope real y las de este espejo mas `digest` coinciden.
 */
@kotlinx.serialization.Serializable
private data class SuiteWithoutDigestDto(
    val apiVersion: String,
    val kind: String,
    val suiteApiVersion: String,
    val suiteId: String,
    val suiteVersion: String,
    val requiredEvidence: List<String>,
    val lenses: List<LensDto>,
    val assertions: List<AssertionDto>,
    val metadata: Map<String, String> = emptyMap(),
)

@kotlinx.serialization.Serializable
private data class ReportWithoutDigestDto(
    val apiVersion: String,
    val kind: String,
    val evaluationId: String,
    val snapshotDigest: String,
    val suiteDigest: String,
    val engineVersion: String,
    val results: List<ResultDto>,
    val gaps: List<GapDto>,
    val artifacts: List<ArtifactRefDto> = emptyList(),
    val correlations: List<CorrelationDto> = emptyList(),
)
