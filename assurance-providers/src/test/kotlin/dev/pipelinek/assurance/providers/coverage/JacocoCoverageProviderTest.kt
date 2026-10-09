package dev.pipelinek.assurance.providers.coverage

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class JacocoCoverageProviderTest : AnnotationSpec() {

    @Test
    fun el_descriptor_declara_classification_runtime_y_capabilities_de_cobertura() {
        val provider = JacocoCoverageProvider("<report></report>".toByteArray())
        provider.descriptor.classification.name shouldBe "Runtime"
        provider.descriptor.evidenceCapabilities.contains("coverage.line") shouldBe true
    }

    @Test
    fun un_xml_valido_produce_items_por_linea() {
        val xml = """
            <report>
              <package name="com/example">
                <class name="Foo">
                  <method name="bar">
                    <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
                    <line nr="11" mi="2" ci="0" mb="0" cb="0"/>
                  </method>
                </class>
              </package>
            </report>
        """.trimIndent()
        val provider = JacocoCoverageProvider(xml.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.rawItems.size shouldBe 2
        val line10 = produced.rawItems.find { it.payload["line"] == "10" }
        (line10 != null) shouldBe true
        (line10!!.payload["covered"] == "true") shouldBe true
        val line11 = produced.rawItems.find { it.payload["line"] == "11" }
        (line11!!.payload["covered"] == "false") shouldBe true
    }

    @Test
    fun un_xml_sin_report_es_failed() {
        val provider = JacocoCoverageProvider("<other></other>".toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }

    @Test
    fun el_provider_no_retorna_assertion_result() {
        val provider = JacocoCoverageProvider("<report></report>".toByteArray())
        val result: Any = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        (result::class.qualifiedName?.contains("AssertionResult") ?: false) shouldBe false
    }
}
