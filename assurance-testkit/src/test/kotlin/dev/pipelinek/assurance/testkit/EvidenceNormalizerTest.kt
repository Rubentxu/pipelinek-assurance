package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M2-T8 — Tests del `EvidenceNormalizer`.
 *
 * Lo que se verifica:
 *   1. Una `EvidenceCollectionResult.Produced` con Fact items
 *      se normaliza a `EvidenceItem.Fact` con authority correcta.
 *   2. **AAT-19**: una authority no reconocida aborta la
 *      normalización con `NormalizerException` (no coerción
 *      silenciosa).
 *   3. **AAT-13**: un rawItem sin `/` en el id aborta la
 *      normalización con `NormalizerException` (namespace
 *      faltante).
 *   4. La matriz authority/kind se enforce: un Signal con
 *      `DeterministicAdapter` aborta (M-H01).
 */
class EvidenceNormalizerTest : AnnotationSpec() {

    @Test
    fun normalize_Fact_con_authority_DeterministicAdapter_produce_Fact() {
        val result = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "test/fact/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf(
                        "predicate" to "imports",
                        "object" to "core.Bar",
                        "capability" to "architecture.dependency-graph",
                    ),
                ),
            ),
        )
        val snapshot = EvidenceNormalizer.normalize(
            result = result,
            producerId = "test",
            producerVersion = "0.1.0",
            subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/norm"),
            requestedCapabilities = listOf("architecture.dependency-graph"),
        )
        snapshot.items.size shouldBe 1
        val item = snapshot.items.first()
        item.shouldBeInstanceOf<dev.pipelinek.assurance.domain.evidence.EvidenceItem.Fact>()
        item.authority shouldBe EvidenceAuthority.DeterministicAdapter
        item.provenance.capability shouldBe "architecture.dependency-graph"
    }

    @Test
    fun normalize_authority_no_reconocida_aborta() {
        val result = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "test/fact/1",
                    subjectRef = "module:core",
                    authority = "UnknownAuthority",
                    payload = mapOf("predicate" to "imports", "object" to "x"),
                ),
            ),
        )
        val ex = shouldThrow<EvidenceNormalizer.NormalizerException> {
            EvidenceNormalizer.normalize(
                result = result,
                producerId = "test",
                producerVersion = "0.1.0",
                subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                subject = EvidenceSubject.Module("self"),
                snapshotId = SnapshotId("s/norm"),
                requestedCapabilities = emptyList(),
            )
        }
        ex.message shouldContain "authority no reconocida"
    }

    @Test
    fun normalize_id_sin_namespace_aborta() {
        // AAT-13: el id debe tener al menos un `/` para delimitar
        // el namespace del resto. Un id sin `/` es un producer
        // que mintió sobre su namespace.
        val result = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "fact-sin-namespace",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "imports", "object" to "x"),
                ),
            ),
        )
        val ex = shouldThrow<EvidenceNormalizer.NormalizerException> {
            EvidenceNormalizer.normalize(
                result = result,
                producerId = "test",
                producerVersion = "0.1.0",
                subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                subject = EvidenceSubject.Module("self"),
                snapshotId = SnapshotId("s/norm"),
                requestedCapabilities = emptyList(),
            )
        }
        ex.message shouldContain "rawItem sin namespace"
    }

    @Test
    fun M_NORM_01_mezcla_de_items_validos_e_invalidos_aborta() {
        // M-NORM-01 redundancia: si el normalizer acepta un item
        // con id sin namespace porque está en una lista con
        // otros items válidos, la enforce AAT-13 falla por
        // una segunda vía. Verificamos que un item "huérfano"
        // (id sin /) hace abortar la normalización aunque
        // haya otros items correctos en la misma lista.
        val result = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "test/fact/valido",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf(
                        "predicate" to "imports",
                        "object" to "core.Bar",
                        "capability" to "architecture.dependency-graph",
                    ),
                ),
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "id-huerfano-sin-slash",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "calls", "object" to "core.Foo"),
                ),
            ),
        )
        val ex = shouldThrow<EvidenceNormalizer.NormalizerException> {
            EvidenceNormalizer.normalize(
                result = result,
                producerId = "test",
                producerVersion = "0.1.0",
                subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                subject = EvidenceSubject.Module("self"),
                snapshotId = SnapshotId("s/norm"),
                requestedCapabilities = emptyList(),
            )
        }
        ex.message shouldContain "rawItem sin namespace"
    }

    @Test
    fun normalize_signal_con_authority_deterministic_aborta() {
        // M-H01: un Signal nunca puede tener authority
        // determinista. El normalizer refuse la combinación
        // para que un producer que mienta sea detectable.
        val result = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Signal,
                    id = "test/signal/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf(
                        "signalKind" to "scc-detector",
                        "score" to "0.5",
                        "algorithmId" to "tarjan",
                        "algorithmVersion" to "1.0",
                    ),
                ),
            ),
        )
        val ex = shouldThrow<IllegalArgumentException> {
            EvidenceNormalizer.normalize(
                result = result,
                producerId = "test",
                producerVersion = "0.1.0",
                subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                subject = EvidenceSubject.Module("self"),
                snapshotId = SnapshotId("s/norm"),
                requestedCapabilities = emptyList(),
            )
        }
        ex.message shouldContain "AAT-19"
    }

    private fun produced(rawItems: List<RawEvidenceItem>): EvidenceCollectionResult.Produced =
        EvidenceCollectionResult.Produced(
            producerId = "test",
            producerVersion = "0.1.0",
            schemaVersion = "assurance-evidence/v1",
            rawItems = rawItems,
            declaredGaps = emptyList(),
        )
}
