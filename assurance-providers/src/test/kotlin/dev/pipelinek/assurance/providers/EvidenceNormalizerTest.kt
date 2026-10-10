package dev.pipelinek.assurance.providers

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind
import dev.pipelinek.assurance.providers.EvidenceNormalizer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    // -----------------------------------------------------------------
    // A3 (Bloque A) — correcciones al normalizer
    // -----------------------------------------------------------------

    @Test
    fun A3_digest_es_del_contenido_no_de_cardinalidad() {
        // El bug A3: la versión M2-T8 calculaba el digest a partir
        // del tamaño de la lista (`items.size`). Eso hacía que un
        // snapshot con 5 items y otro con 5 items distintos
        // produjeran el mismo digest. El nuevo digest es SHA-256
        // sobre el contenido ordenado de los items y los gaps.
        val result1 = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "p/a/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "p", "object" to "o1"),
                ),
            ),
        )
        val result2 = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "p/a/2", // mismo cardinal, contenido distinto
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "p", "object" to "o2"),
                ),
            ),
        )
        val snap1 = EvidenceNormalizer.normalize(
            result = result1,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = RevisionRef("a".repeat(40)),
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/1"),
            requestedCapabilities = emptyList(),
        )
        val snap2 = EvidenceNormalizer.normalize(
            result = result2,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = RevisionRef("a".repeat(40)),
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/2"),
            requestedCapabilities = emptyList(),
        )
        snap1.sources[0].digest shouldNotBe snap2.sources[0].digest
    }

    @Test
    fun A3_digest_estable_ante_mismo_input() {
        // Mismo input (mismos items, mismo orden) → mismo digest.
        // El digest es función pura del contenido.
        val r = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "p/a/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "p", "object" to "o"),
                ),
            ),
        )
        val s1 = EvidenceNormalizer.normalize(
            result = r,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = RevisionRef("a".repeat(40)),
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/1"),
            requestedCapabilities = emptyList(),
        )
        val s2 = EvidenceNormalizer.normalize(
            result = r,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = RevisionRef("a".repeat(40)),
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/2"),
            requestedCapabilities = emptyList(),
        )
        s1.sources[0].digest shouldBe s2.sources[0].digest
    }

    @Test
    fun A3_provenance_propagacion_subjectRevision_real() {
        // El bug A3: la versión M2-T8 hardcodeaba
        // RevisionRef("0000...0") y descartaba el parámetro.
        val realRev = RevisionRef("deadbeef".repeat(5)) // 40 hex chars
        val result = produced(
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "p/a/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "p", "object" to "o"),
                ),
            ),
        )
        val snap = EvidenceNormalizer.normalize(
            result = result,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = realRev,
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/1"),
            requestedCapabilities = emptyList(),
        )
        snap.items[0].provenance.subjectRevision shouldBe realRev
        snap.sources[0].subjectRevision shouldBe realRev
    }

    @Test
    fun A3_capabilities_con_cero_resultados_se_marcan_Unknown() {
        // El bug A3: si una capability se pidió y el provider no
        // pudo observarla, no aparecía en producedCapabilities.
        // Ahora se marca Unknown explícitamente — no se esconde.
        val result = produced(rawItems = emptyList())
        val snap = EvidenceNormalizer.normalize(
            result = result,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = RevisionRef("a".repeat(40)),
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/1"),
            requestedCapabilities = listOf("runtime.window", "observability.trace"),
        )
        val capMap = snap.sources[0].completenessByCapability
        capMap["runtime.window"] shouldBe Completeness.Unknown
        capMap["observability.trace"] shouldBe Completeness.Unknown
    }

    @Test
    fun A3_provenance_propagacion_en_todos_los_tipos_de_item() {
        // Cada tipo de item lleva la revisión real en su Provenance.
        val rev = RevisionRef("cafebabe".repeat(5))
        val result = EvidenceCollectionResult.Produced(
            producerId = "p",
            producerVersion = "v1",
            schemaVersion = "assurance-evidence/v1",
            rawItems = listOf(
                RawEvidenceItem(
                    kind = RawItemKind.Fact,
                    id = "p/fact/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.DeterministicAdapter.name,
                    payload = mapOf("predicate" to "p", "object" to "o"),
                ),
                RawEvidenceItem(
                    kind = RawItemKind.Observation,
                    id = "p/obs/1",
                    subjectRef = "chronos:span/abc",
                    authority = EvidenceAuthority.RuntimeObserver.name,
                    payload = mapOf("observation" to "ok"),
                ),
                RawEvidenceItem(
                    kind = RawItemKind.Signal,
                    id = "p/sig/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.HeuristicAnalyzer.name,
                    payload = mapOf(
                        "signalKind" to "scc",
                        "score" to "0.5",
                        "algorithmId" to "tarjan",
                        "algorithmVersion" to "1.0",
                    ),
                ),
                RawEvidenceItem(
                    kind = RawItemKind.Hypothesis,
                    id = "p/hyp/1",
                    subjectRef = "module:core",
                    authority = EvidenceAuthority.AgentHypothesis.name,
                    payload = mapOf("claim" to "x", "reasoning" to "y"),
                ),
            ),
            declaredGaps = emptyList(),
        )
        val snap = EvidenceNormalizer.normalize(
            result = result,
            producerId = "p",
            producerVersion = "v1",
            subjectRevision = rev,
            subject = EvidenceSubject.Module("self"),
            snapshotId = SnapshotId("s/1"),
            requestedCapabilities = emptyList(),
        )
        for (item in snap.items) {
            item.provenance.subjectRevision shouldBe rev
        }
    }
}
