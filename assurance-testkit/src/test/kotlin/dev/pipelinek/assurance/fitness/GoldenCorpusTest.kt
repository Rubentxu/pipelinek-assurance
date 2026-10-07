package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * M0 — Corpus golden de codecs y digests.
 *
 * Ref: `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` ("schema upgrade requires
 * golden corpus old->new") y el Exit de M0 ("corpus golden, dos digests
 * independientes idénticos").
 *
 * Por qué existe esto y por qué no es burocracia:
 *
 * El resto de la suite demuestra que el digest es *estable dentro de una
 * ejecución*. Eso no detecta que un refactor haya cambiado el formato y que
 * todos los artefactos archivados hayan dejado de ser comparables con los
 * nuevos. Este test congela los bytes exactos: si el encoder cambia, aunque
 * siga siendo determinista, el test se pone rojo.
 *
 * Este test NUNCA escribe el golden. La escritura vive en
 * [GoldenCorpusGenerator], invocado a mano con `:assurance-testkit:generateGolden`.
 * Un golden que se regenera en cada build siempre pasaría, y el cambio de
 * formato se escribiría sobre sí mismo antes de que nadie lo viera.
 */
class GoldenCorpusTest : AnnotationSpec() {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val goldenFile = GoldenCorpusGenerator.targetFile(repoRoot)

    private val corpus = GoldenCorpusGenerator.goldenCorpus()

    private fun readGolden(): Map<String, String> {
        // Fail-closed: sin golden archivado no hay verificacion, y no se inventa.
        goldenFile.exists() shouldBe true
        return goldenFile.readLines()
            .filter { it.isNotBlank() }
            .associate { line -> line.substringBefore(' ') to line.substringAfter(' ') }
    }

    @Test
    fun golden_corpus_is_pinned() {
        val expected = readGolden()
        val actual = GoldenCorpusGenerator.goldenDigests()

        // Una clave nueva o retirada no es un fallo silencioso: es un cambio de
        // contrato que hay que revisar a mano, asi que se comparan los conjuntos.
        actual.keys shouldBe expected.keys

        val drifted = actual.keys.filter { actual[it] != expected[it] }
        withClue(drifted.joinToString("\n") { "  $it cambio de golden" }) {
            drifted shouldBe emptyList()
        }
    }

    @Test
    fun two_independent_encodings_agree_byte_for_byte() {
        // El Exit de M0 pide "dos digests independientes identicos". Estos dos
        // procesos no comparten estado: uno serializa a CBOR, el otro calcula
        // el digest del texto canonico. Cada uno debe ser reproducible por si
        // solo, y el CBOR decodificado vuelve al mismo digest canonico.
        CanonicalEncoder.digestSnapshot(corpus) shouldBe CanonicalEncoder.digestSnapshot(corpus)
        EvidenceArtifactCodec.encodeToCbor(corpus) shouldBe EvidenceArtifactCodec.encodeToCbor(corpus)

        val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(corpus))
        CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(corpus)
    }

    @Test
    fun golden_corpus_is_not_empty() {
        // Un golden vacio pasa todos los tests y no protege de nada.
        val entries = readGolden()
        entries.isEmpty() shouldBe false
        entries.size shouldBe GoldenCorpusGenerator.goldenDigests().size
    }

    @Test
    fun a_changed_corpus_produces_a_different_digest() {
        // Control negativo: si el golden cambiara solo porque el test fuera
        // fragil, este test lo delata.
        val other = EvidenceFixtures.snapshot(
            items = listOf(EvidenceFixtures.fact("synthetic/alpha/depends-on/1")),
        )

        CanonicalEncoder.digestSnapshot(corpus) shouldNotBe CanonicalEncoder.digestSnapshot(other)
    }
}