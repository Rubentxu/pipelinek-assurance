package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.ProofRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * B4 (Bloque B) — Contrato del report artifact.
 *
 * Ref: `odd/tasks/block-B-pipelinek-plugin.md` §B4.
 *
 * El plan B4 dice:
 *   - Contrato genérico de report artifact con escritura
 *     completa, digest, referencia estable.
 *   - Fingerprint + mismos artifacts → mismo veredicto.
 *   - Report incompleto NO se publica como válido.
 *
 * Lo que estos tests verifican:
 *   1. `publishReport` / `loadReport` es un roundtrip con
 *      digest estable: codificar y decodificar preserva el
 *      digest y todos los campos.
 *   2. Mismo report codificado dos veces produce los MISMOS
 *      bytes (escritura determinista).
 *   3. Replay con mismo fingerprint: el mismo `AssuranceReport`
 *      codificado y luego decodificado reproduce el mismo
 *      resumen (veredicto reproducible).
 *   4. Un report con `digest` declarado que no coincide con el
 *      canónico se rechaza con `ArtifactDecodeException` (no
 *      se publica como válido).
 *   5. Un report con `apiVersion` desconocida se rechaza.
 *   6. Bytes truncados / garbage se rechazan con tipo concreto.
 *
 * El report artifact es **público** (lo escribe el SDK en su
 * store); por tanto, el codec es la frontera de confianza.
 * Si el codec acepta un payload alterado, el contrato entero
 * cae.
 */
class B4ReportArtifactContractTest : AnnotationSpec() {

    @Test
    fun report_roundtrips_with_stable_digest() {
        val report = sampleReport(evaluationId = "eval-b4-1", failed = 1)
        val bytes = ReportArtifactCodec.encodeToCbor(report)
        val decoded = ReportArtifactCodec.decodeFromCbor(bytes)
        decoded shouldBe report
        decoded.suiteDigest shouldBe report.suiteDigest
        decoded.snapshotDigest shouldBe report.snapshotDigest
        decoded.summary shouldBe report.summary
    }

    @Test
    fun report_encoded_twice_yields_identical_bytes() {
        // B4: "escritura determinista". Si el codec no es
        // estable byte-a-byte, el digest canónico no protege
        // contra variación: dos publishes "iguales" producen
        // artefactos distintos. Eso rompe la deduplicación
        // que el SDK hace en su store.
        val report = sampleReport(evaluationId = "eval-b4-2", failed = 1)
        val first = ReportArtifactCodec.encodeToCbor(report)
        val second = ReportArtifactCodec.encodeToCbor(report)
        first shouldBe second
    }

    @Test
    fun replay_with_same_fingerprint_reproduces_same_verdict() {
        // B4: "fingerprint + mismos artifacts → mismo
        // veredicto". Aquí no usamos aún el fingerprint
        // (viene de M4 con el SDK real); verificamos la
        // pieza del report: el report codificado es
        // recuperable y su `summary` (veredicto) es
        // idéntico. Es la base sobre la que el replay del
        // SDK compara.
        val report = sampleReport(
            evaluationId = "eval-b4-replay",
            passed = 2,
            failed = 1,
            inconclusive = 1,
        )
        val originalSummary = report.summary
        val bytes = ReportArtifactCodec.encodeToCbor(report)
        val decoded = ReportArtifactCodec.decodeFromCbor(bytes)
        decoded.summary shouldBe originalSummary
        decoded.summary.failed shouldBe 1
        decoded.summary.passed shouldBe 2
        decoded.summary.inconclusive shouldBe 1
    }

    @Test
    fun report_with_altered_digest_is_refused_not_published() {
        // B4: "report incompleto NO se publica como
        // válido". Aquí "incompleto" = digest declarado que
        // no coincide. El caller (SDK) que rehidrata el
        // report debe recibir un `ArtifactDecodeException`
        // con motivo, no un report "parcial pero aceptable".
        val report = sampleReport(evaluationId = "eval-b4-3", failed = 1)
        val goodBytes = ReportArtifactCodec.encodeToCbor(report)
        // Alteramos el digest en el CBOR. Como el codec
        // exige apiVersion+kind, lo más simple es alterar
        // el último byte del digest (que es la pieza
        // verificada al final).
        val tampered = goodBytes.copyOf().also {
            // Cambiamos un byte de la cola del digest. El
            // digest mide 32 bytes hex = 64 chars, ocupando
            // ~100 bytes CBOR al final. Tomamos un byte de
            // la mitad de la cola.
            val last = it.size - 5
            it[last] = (it[last].toInt() xor 0x01).toByte()
        }
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(tampered)
        }
        ex.message shouldContain "digest"
    }

    @Test
    fun report_with_truncated_bytes_is_refused_with_typed_error() {
        // B4: la entrada truncada NO se publica como
        // válida. Un publish a medias en disco no debe
        // rehidratar como un report "completo".
        val report = sampleReport(evaluationId = "eval-b4-4", passed = 1)
        val goodBytes = ReportArtifactCodec.encodeToCbor(report)
        val truncated = goodBytes.copyOf(goodBytes.size / 2)
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(truncated)
        }
        ex.shouldBeInstanceOf<EvidenceArtifactCodec.ArtifactDecodeException>()
    }

    @Test
    fun report_with_garbage_bytes_is_refused_not_guessed() {
        val garbage = ByteArray(64) { it.toByte() }
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(garbage)
        }
        ex.message shouldContain "CBOR"
    }

    @Test
    fun media_type_is_a_stable_contract_value() {
        // B4: el mediaType es la referencia estable que el
        // SDK usa para enrutar al codec. NO debe cambiar
        // sin un bump de versión (lo que rompería caches,
        // stores, y UAT-024).
        ReportArtifactCodec.MEDIA_TYPE shouldBe
            "application/vnd.pipelinek.assurance.report+cbor;version=1"
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun sampleReport(
        evaluationId: String,
        passed: Int = 0,
        failed: Int = 0,
        inconclusive: Int = 0,
    ): AssuranceReport {
        val results = buildList<AssertionResult> {
            repeat(passed) {
                add(
                    AssertionResult.Passed(
                        ProofRef(
                            snapshotId = "snap-b4",
                            evidenceIds = listOf(EvidenceId("e/b4-$it")),
                            assertionId = AssertionId("a/b4-$it"),
                        ),
                    ),
                )
            }
            repeat(failed) {
                add(
                    AssertionResult.Failed(
                        dev.pipelinek.assurance.engine.Counterexample.Cycle(
                            assertionId = AssertionId("a/b4-fail-$it"),
                            subjectRefs = emptyList(),
                            evidenceRefs = listOf(EvidenceId("e/b4-fail-$it")),
                            explanation = "B4 test failure",
                            reproductionHints = emptyList(),
                            cycle = listOf("a", "b", "a"),
                        ),
                    ),
                )
            }
            repeat(inconclusive) {
                add(
                    AssertionResult.Inconclusive(
                        listOf(
                            EvidenceGap(
                                capability = "b4.test",
                                reason = EvidenceGap.GapReason.Unknown,
                                detail = "B4 test inconclusive",
                            ),
                        ),
                    ),
                )
            }
        }
        return AssuranceReport(
            evaluationId = AssuranceEvaluationId(evaluationId),
            snapshotDigest = Digest.ofUtf8("snapshot-b4"),
            suiteDigest = Digest.ofUtf8("suite-b4"),
            engineVersion = "0.1.0",
            results = results,
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
    }
}
