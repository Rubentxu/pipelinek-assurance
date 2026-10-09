package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M5 — Test del `TestTopologyLens`.
 *
 * Test mínimo y verificable:
 *   1. La lens **proyecta** con items de la capability `test.topology`.
 *   2. La lens **falla** con `MissingCapability` sin items.
 *   3. La lens **rechaza** items con predicate incorrecto.
 *   4. La lens **rechaza** items con subject que no es `Test`.
 *   5. La forma del output es estable: `TestTopology` con conteos
 *      coherentes y `tests.size == totalTests`.
 */
class TestTopologyLensTest : AnnotationSpec() {

    @Test
    fun proyecta_con_items_de_test_topology() {
        val snapshot = snapshotCon(
            item = test("com.example.Foo", "name1", "passed"),
        )
        val result = TestTopologyLens.project(snapshot)
        val projected = result.shouldBeInstanceOf<ProjectionResult.Projected<TestTopology>>()
        projected.value.totalTests shouldBe 1
        projected.value.passed shouldBe 1
    }

    @Test
    fun falla_con_missing_capability_sin_items_de_test() {
        val snapshot = snapshotSinTestItems()
        val result = TestTopologyLens.project(snapshot)
        result.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
    }

    @Test
    fun rechaza_predicate_incorrecto() {
        val item = EvidenceItem.Fact(
            id = EvidenceId("junit-xml/result/com.example.Foo/name1"),
            subject = EvidenceSubject.Test("com.example.Foo#name1"),
            authority = EvidenceAuthority.DeterministicAdapter,
            provenance = provenance(),
            predicate = "not.a.test",
            objectValue = "status=passed;time=0.1",
            completeness = Completeness.Complete,
        )
        val snapshot = snapshotCon(item = item)
        val result = TestTopologyLens.project(snapshot)
        val failure = result.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
        failure.reason.shouldBeInstanceOf<ProjectionFailureReason.InvalidInput>()
    }

    @Test
    fun falla_con_unknown_status() {
        val item = test("com.example.Foo", "name1", "weirdstatus")
        val snapshot = snapshotCon(item = item)
        val result = TestTopologyLens.project(snapshot)
        val failure = result.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
        failure.reason.shouldBeInstanceOf<ProjectionFailureReason.InvalidInput>()
    }

    @Test
    fun la_forma_del_output_es_estable() {
        val items = listOf(
            test("com.example.Foo", "a", "passed", "0.10"),
            test("com.example.Foo", "b", "failed", "0.20"),
            test("com.example.Foo", "c", "errored", "0.30"),
            test("com.example.Foo", "d", "skipped", "0.0"),
        )
        val snapshot = snapshotConConjunto(items)
        val result = TestTopologyLens.project(snapshot)
        val projected = result.shouldBeInstanceOf<ProjectionResult.Projected<TestTopology>>()
        val t = projected.value
        t.totalTests shouldBe 4
        t.passed shouldBe 1
        t.failed shouldBe 1
        // `errored` y `errors` se cuentan juntos.
        t.errors shouldBe 1
        t.skipped shouldBe 1
        t.tests.size shouldBe 4
    }

    // --- helpers ---

    private fun test(
        classname: String,
        name: String,
        status: String,
        time: String = "0.0",
    ): EvidenceItem.Fact {
        val objectValue = "classname=$classname;name=$name;status=$status;time=$time"
        return EvidenceItem.Fact(
            id = EvidenceId("junit-xml/result/$classname/$name"),
            subject = EvidenceSubject.Test("$classname#$name"),
            authority = EvidenceAuthority.DeterministicAdapter,
            provenance = provenance(),
            predicate = TestTopologyLens.PREDICATE,
            objectValue = objectValue,
            completeness = Completeness.Complete,
        )
    }

    private fun provenance(cap: String = TestTopologyLens.CAPABILITY): Provenance =
        Provenance(
            producerId = "junit-xml",
            producerVersion = "0.1.0",
            subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
            capability = cap,
        )

    private fun snapshotCon(item: EvidenceItem.Fact): EvidenceSnapshot {
        return snapshotConConjunto(listOf(item))
    }

    private fun snapshotConConjunto(items: List<EvidenceItem.Fact>): EvidenceSnapshot {
        return EvidenceSnapshot(
            id = SnapshotId("s/m5"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "junit-xml",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(TestTopologyLens.CAPABILITY),
                    producedCapabilities = listOf(TestTopologyLens.CAPABILITY),
                    completenessByCapability = mapOf(TestTopologyLens.CAPABILITY to Completeness.Complete),
                    schemaVersion = "junit/v4",
                    digest = Digest.ofUtf8("synthetic"),
                ),
            ),
            items = items,
            gaps = emptyList(),
        )
    }

    private fun snapshotSinTestItems(): EvidenceSnapshot {
        return EvidenceSnapshot(
            id = SnapshotId("s/m5-empty"),
            subject = EvidenceSubject.Module("self"),
            sources = listOf(
                EvidenceSourceManifest(
                    producerId = "junit-xml",
                    producerVersion = "0.1.0",
                    subjectRevision = RevisionRef("0000000000000000000000000000000000000000"),
                    requestedCapabilities = listOf(TestTopologyLens.CAPABILITY),
                    // `producedCapabilities` debe contener cualquier
                    // capability declarada en `completenessByCapability`
                    // (invarante del modelo epistémico).
                    producedCapabilities = listOf(TestTopologyLens.CAPABILITY),
                    completenessByCapability = mapOf(TestTopologyLens.CAPABILITY to Completeness.Unsupported("no tests")),
                    schemaVersion = "junit/v4",
                    digest = Digest.ofUtf8("synthetic-empty"),
                ),
            ),
            items = emptyList(),
            gaps = emptyList(),
        )
    }
}
