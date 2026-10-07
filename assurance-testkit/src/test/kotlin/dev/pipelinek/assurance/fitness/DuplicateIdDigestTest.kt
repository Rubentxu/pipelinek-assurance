package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * M0 — Regresión: ids duplicados rompían la invariancia de permutación.
 *
 * Historia: los tests de ejemplo pasaban porque usaban ids únicos, y con ids
 * únicos todo funcionaba. El property testing encontró el fallo: dos items con
 * el MISMO `EvidenceId` en distinto orden de entrada producían digests
 * distintos, porque `sortedBy` es estable y el empate lo resolvía la posición.
 *
 * El dominio admite ids duplicados (`EvidenceSnapshot` no los exige únicos),
 * así que el encoder tiene que resolver el empate de forma determinista.
 *
 * Si algún día este test vuelve a fallar, el orden total se ha roto.
 */
class DuplicateIdDigestTest : AnnotationSpec() {

    private fun fact(id: String, predicate: String) = EvidenceItem.Fact(
        id = EvidenceId(id),
        subject = EvidenceSubject.Module("core"),
        authority = EvidenceAuthority.DeterministicAnalyzer,
        provenance = Provenance(
            producerId = "synthetic",
            producerVersion = "0.1.0",
            subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
            capability = "ModuleDependencies",
        ),
        predicate = predicate,
        objectValue = "adapter",
        completeness = Completeness.Complete,
    )

    @Test
    fun duplicate_ids_do_not_break_permutation_invariance() {
        val id = "synthetic/dup/depends-on/1"
        val a = fact(id, "depends-on")
        val b = fact(id, "imports")

        val uno = EvidenceFixtures.snapshot(items = listOf(a, b))
        val otro = EvidenceFixtures.snapshot(items = listOf(b, a))

        // Con ids ÚNICOS la ley se cumple: control negativo que confirma que
        // la causa era la duplicidad y no el ordenamiento en sí.
        val unicoA = fact("synthetic/uni/depends-on/1", "depends-on")
        val unicoB = fact("synthetic/uni/imports/2", "imports")
        val conUnicos = EvidenceFixtures.snapshot(items = listOf(unicoA, unicoB))
        CanonicalEncoder.digestSnapshot(conUnicos) shouldBe
            CanonicalEncoder.digestSnapshot(conUnicos.copy(items = listOf(unicoB, unicoA)))

        // Y con ids DUPLICADOS también, que es el caso que fallaba.
        CanonicalEncoder.digestSnapshot(uno) shouldBe CanonicalEncoder.digestSnapshot(otro)

        // El CBOR tampoco puede filtrar el orden de entrada.
        EvidenceArtifactCodec.encodeToCbor(uno) shouldBe EvidenceArtifactCodec.encodeToCbor(otro)
        EvidenceArtifactCodec.encodeToJson(uno) shouldBe EvidenceArtifactCodec.encodeToJson(otro)
    }
}