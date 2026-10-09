package dev.pipelinek.assurance.providers.mutation

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PitestMutationProviderTest : AnnotationSpec() {

    @Test
    fun el_descriptor_declara_classification_runtime_y_capabilities_de_mutation() {
        val provider = PitestMutationProvider("<mutations></mutations>".toByteArray())
        provider.descriptor.classification.name shouldBe "Runtime"
        provider.descriptor.evidenceCapabilities.contains("mutation.killed") shouldBe true
    }

    @Test
    fun un_xml_valido_produce_items_por_mutante() {
        val xml = """
            <mutations>
              <mutation detected="true" status="KILLED" numberOfTestsRun="3">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <mutatedMethod>bar</mutatedMethod>
                <lineNumber>10</lineNumber>
              </mutation>
              <mutation detected="false" status="SURVIVED" numberOfTestsRun="3">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <mutatedMethod>baz</mutatedMethod>
                <lineNumber>20</lineNumber>
              </mutation>
            </mutations>
        """.trimIndent()
        val provider = PitestMutationProvider(xml.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.rawItems.size shouldBe 2
        val killed = produced.rawItems.find { it.payload["status"] == "KILLED" }
        (killed!!.payload["detected"] == "true") shouldBe true
        val survived = produced.rawItems.find { it.payload["status"] == "SURVIVED" }
        (survived!!.payload["detected"] == "false") shouldBe true
    }

    @Test
    fun un_xml_sin_mutations_es_failed() {
        val provider = PitestMutationProvider("<other></other>".toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }

    @Test
    fun el_provider_no_retorna_assertion_result() {
        val provider = PitestMutationProvider("<mutations></mutations>".toByteArray())
        val result: Any = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        (result::class.qualifiedName?.contains("AssertionResult") ?: false) shouldBe false
    }
}
