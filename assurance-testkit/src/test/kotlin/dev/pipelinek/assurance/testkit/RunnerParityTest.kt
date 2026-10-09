package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.ReportArtifactCodec
import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.ProofRef
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

class RunnerParityTest : AnnotationSpec() {

    @Test
    fun el_digest_es_estable_a_traves_de_invocaciones_repetidas() {
        val report = AssuranceReport(
            evaluationId = AssuranceEvaluationId("parity-1"),
            snapshotDigest = Digest.ofUtf8("s/runner-parity"),
            suiteDigest = Digest.ofUtf8("suite"),
            engineVersion = "0.1.0",
            results = listOf(
                AssertionResult.Passed(
                    proof = ProofRef(
                        snapshotId = "s/runner-parity",
                        evidenceIds = listOf(EvidenceId("parity/declared-graph/1")),
                        assertionId = AssertionId("parity.no-dep"),
                    ),
                ),
            ),
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
        val d1 = CanonicalEncoder.digestReport(report).hex
        val d2 = CanonicalEncoder.digestReport(report).hex
        d1 shouldBe d2
    }

    @Test
    fun el_codec_roundtrip_preserva_el_digest() {
        val report = AssuranceReport(
            evaluationId = AssuranceEvaluationId("parity-1"),
            snapshotDigest = Digest.ofUtf8("s/runner-parity"),
            suiteDigest = Digest.ofUtf8("suite"),
            engineVersion = "0.1.0",
            results = listOf(
                AssertionResult.Passed(
                    proof = ProofRef(
                        snapshotId = "s/runner-parity",
                        evidenceIds = listOf(EvidenceId("parity/declared-graph/1")),
                        assertionId = AssertionId("parity.no-dep"),
                    ),
                ),
            ),
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
        val direct = CanonicalEncoder.digestReport(report).hex
        val bytes = ReportArtifactCodec.encodeToCbor(report)
        val decoded = ReportArtifactCodec.decodeFromCbor(bytes)
        val after = CanonicalEncoder.digestReport(decoded).hex
        after shouldBe direct
    }
}
