package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.EvaluationFailure
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.UnsupportedReason
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import java.io.File
import java.util.Base64

/**
 * M0 — Generador del corpus golden.
 *
 * Se invoca a mano (`./gradlew :assurance-testkit:generateGolden`) y NUNCA como
 * parte de `check`. Separar el generador del verificador es deliberado: si el
 * test escribiera el golden, un cambio de formato se sobrescribiría en cada
 * build y el fallo desaparecería antes de que nadie lo leyera.
 *
 * El corpus vive en una función compartida ([goldenDigests]) para que el
 * generador y [GoldenCorpusTest] no puedan divergir. Si divergieran, el test
 * compararía el golden contra un corpus distinto y siempre fallaría, lo cual es
 * molesto pero inocuo: el fallo sería ruido, nunca un falso verde.
 */
object GoldenCorpusGenerator {

    /** El corpus archivado, con las cuatro variantes de item y de completitud. */
    fun goldenCorpus() = EvidenceFixtures.snapshot(
        items = listOf(
            EvidenceFixtures.fact("synthetic/alpha/depends-on/1"),
            EvidenceFixtures.observation("synthetic/delta/invocation/1"),
            EvidenceFixtures.signal("synthetic/beta/smell/1"),
            EvidenceFixtures.hypothesis("synthetic/gamma/hypothesis/1"),
        ),
        gaps = listOf(EvidenceGap("SymbolGraph", EvidenceGap.GapReason.Lost, "expiro la cache")),
        correlations = listOf(EvidenceFixtures.correlation()),
        sources = listOf(
            EvidenceFixtures.manifest(
                produced = listOf("ModuleDependencies", "StaticSmells", "AgentReview", "RuntimeInvocations"),
            ),
        ),
    )

    /**
     * Los digests archivados.
     *
     * Se incluyen los tres formatos a propósito. El CBOR y el JSON son los
     * artefactos que se cruzan entre máquinas (M9, Exit); el canónico es la
     * forma interna de la que sale `snapshot.digest`. Si solo archiváramos el
     * canónico, un cambio en el CBOR no se detectaría hasta que alguien
     * comparase artefactos antiguos con nuevos.
     */
    fun goldenDigests(): Map<String, String> {
        val corpus = goldenCorpus()
        return linkedMapOf(
            "snapshot.digest" to CanonicalEncoder.digestSnapshot(corpus).hex,
            "snapshot.canonical.sha256" to Digest.ofUtf8(CanonicalEncoder.encodeSnapshot(corpus)).hex,
            "snapshot.json.sha256" to Digest.ofUtf8(EvidenceArtifactCodec.encodeToJson(corpus)).hex,
            "snapshot.cbor.sha256" to Digest.of(EvidenceArtifactCodec.encodeToCbor(corpus)).hex,
            "snapshot.cbor.base64" to Base64.getEncoder().encodeToString(EvidenceArtifactCodec.encodeToCbor(corpus)),
            "suite.digest" to CanonicalEncoder.digestSuite(EvidenceFixtures.suite()).hex,
            // El digest de report entra en el golden por la misma razón que el
            // resto: es un artefacto que se cruza entre máquinas (Exit de M9).
            // Los cinco veredictos, para que un cambio en la codificación de
            // cualquiera de ellos mueva el golden.
            "report.digest" to CanonicalEncoder.digestReport(goldenReport()).hex,
        )
    }

    /**
     * Un report con un resultado de cada tipo.
     *
     * No vale un report de un solo tipo: si el encoder dejara de codificar, por
     * ejemplo, los campos de un `Cycle`, y el golden sólo contuviera
     * `Passed`, el cambio pasaría inadvertido.
     */
    private fun goldenReport(): AssuranceReport = AssuranceReport(
        evaluationId = AssuranceEvaluationId("eval-golden"),
        snapshotDigest = CanonicalEncoder.digestSnapshot(goldenCorpus()),
        suiteDigest = CanonicalEncoder.digestSuite(EvidenceFixtures.suite()),
        engineVersion = "0.1.0",
        results = listOf(
            AssertionResult.Passed(
                ProofRef("snap-golden", listOf(EvidenceId("synthetic/golden/depends-on/1")), AssertionId("a-pass")),
            ),
            AssertionResult.Failed(
                Counterexample.DependencyPath(
                    assertionId = AssertionId("a-dep"),
                    subjectRefs = listOf(TypedExternalId(ExternalNamespace.PipelineStepOpId, "core:compile")),
                    evidenceRefs = listOf(EvidenceId("synthetic/golden/depends-on/1")),
                    explanation = "el domain depende de un adapter",
                    reproductionHints = listOf("gradle :core:dependencies"),
                    path = listOf("core", "adapter"),
                    fromLayer = "domain",
                    toLayer = "adapter",
                ),
            ),
            AssertionResult.Inconclusive(
                listOf(EvidenceGap("RuntimeInvocations", EvidenceGap.GapReason.Lost, "sin trace")),
            ),
            AssertionResult.Unsupported(UnsupportedReason.UnknownOperator("no-edge-between-sets")),
            AssertionResult.Error(EvaluationFailure("lens", "boom", "cause")),
        ),
        gaps = listOf(EvidenceGap("AgentReview", EvidenceGap.GapReason.Unknown, "sin revision")),
        artifacts = emptyList(),
        correlations = emptyList(),
    )

    fun targetFile(repoRoot: File): File =
        File(repoRoot, "assurance-testkit/src/test/resources/golden/m0-evidence-digests.txt")

    @JvmStatic
    fun main(args: Array<String>) {
        val repoRoot = File(args.firstOrNull() ?: ".").absoluteFile
        val target = targetFile(repoRoot)
        val digests = goldenDigests()

        target.parentFile.mkdirs()
        target.writeText(digests.entries.joinToString("\n") { "${it.key} ${it.value}" } + "\n")

        println("Golden escrito en ${target.absolutePath} (${digests.size} entradas)")
        digests.forEach { (k, v) -> println("  $k ${v.take(16)}...") }
    }
}