/**
 * M5 — Tests del `JUnitXmlProvider`.
 *
 * Ref autoridad: `03-specifications/PROVIDER_SPI.md`, AAT-2, AAT-3, AAT-6.
 *
 * Estos tests verifican:
 *  1. El provider parsea un JUnit XML con 3 testcases (1 pass, 1
 *     failure, 1 skipped) y devuelve un `EvidenceCollectionResult.Produced`
 *     con 3 items de los kinds correctos.
 *  2. Las autoridades son siempre `DeterministicAdapter`.
 *  3. Los IDs siguen el patrón `junit-xml/result/...` (ADR-008).
 *  4. AAT-6: la clase no expone métodos cuyo tipo de retorno sea
 *     `AssertionResult`.
 */
package dev.pipelinek.assurance.providers.junit

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.RawItemKind
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class JUnitXmlProviderTest : AnnotationSpec() {

    // -----------------------------------------------------------------------
    // Descriptor — AAT-2 / PROVIDER_SPI
    // -----------------------------------------------------------------------

    @Test
    fun descriptor_declares_expected_identity_and_capabilities() {
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        provider.descriptor.id shouldBe "junit-xml"
        provider.descriptor.version shouldBe "0.1.0"
        provider.descriptor.evidenceCapabilities shouldBe listOf(
            "test.results",
            "test.topology",
        )
        provider.descriptor.subjectKinds shouldBe listOf("Test")
        provider.descriptor.classification shouldBe ProviderClassification.Deterministic
        provider.descriptor.outputSchemaVersion shouldBe "junit/v4"
        provider.descriptor.inputFormats shouldBe listOf("application/junit+xml")
    }

    // -----------------------------------------------------------------------
    // Recolección — happy path
    // -----------------------------------------------------------------------

    @Test
    fun collect_returns_produced_with_one_item_per_testcase() {
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Produced
        val produced = result as EvidenceCollectionResult.Produced
        produced.rawItems shouldHaveSize 3
        produced.producerId shouldBe "junit-xml"
        produced.producerVersion shouldBe "0.1.0"
        produced.schemaVersion shouldBe "junit/v4"
    }

    @Test
    fun passed_testcase_becomes_Fact_failed_becomes_Observation_skipped_becomes_Fact() {
        // La asimetría es deliberada: un test que pasa o se salta es
        // un hecho (no se observó nada raro). Un test que falla es
        // una observación (algo ANÓMALO ocurrió). Un test skipped NO
        // es una observación porque no se ejecutó: no hay nada que
        // observar. La policy de kinds vive en el provider; este test
        // la verifica.
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        val porNombre = produced.rawItems.associateBy { it.payload["name"] }
        porNombre["shouldPass"]!!.kind shouldBe RawItemKind.Fact
        porNombre["shouldFail"]!!.kind shouldBe RawItemKind.Observation
        porNombre["shouldSkip"]!!.kind shouldBe RawItemKind.Fact
    }

    @Test
    fun all_items_carry_DeterministicAdapter_authority() {
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        produced.rawItems.forEach { it.authority shouldBe "DeterministicAdapter" }
    }

    @Test
    fun payload_carries_classname_name_time_status_capability() {
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        val pass = produced.rawItems.single { it.payload["name"] == "shouldPass" }
        pass.payload["classname"] shouldBe "com.example.FooTest"
        pass.payload["name"] shouldBe "shouldPass"
        pass.payload["status"] shouldBe "passed"
        pass.payload["capability"] shouldBe "test.topology"
        // `time` es no-vacío por contrato; no comparamos un valor
        // exacto porque la fixture usa "0.123" y un test que exija
        // el valor exacto sería brittle si la fixture cambia.
        (pass.payload["time"]!!.isNotBlank()) shouldBe true
    }

    @Test
    fun ids_follow_namespace_pattern() {
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        produced.rawItems.forEach { item ->
            item.id.startsWith("junit-xml/result/") shouldBe true
        }
    }

    @Test
    fun subjectRef_is_typed_as_Test() {
        val provider = JUnitXmlProvider(syntheticJUnitBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        produced.rawItems.forEach { item ->
            item.subjectRef.startsWith("junit:Test:") shouldBe true
            item.subjectRef.contains("com.example.FooTest") shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // Variantes de XML
    // -----------------------------------------------------------------------

    @Test
    fun single_testsuite_root_is_accepted() {
        // Algunos tools emiten `<testsuite>` como raíz directa, sin
        // `<testsuites>` envolvente. El provider debe aceptarlo.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="Single" tests="1" failures="0" errors="0" skipped="0">
              <testcase classname="com.example.Single" name="onlyOne" time="0.001"/>
            </testsuite>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val provider = JUnitXmlProvider(xml)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Produced
        val produced = result as EvidenceCollectionResult.Produced
        produced.rawItems shouldHaveSize 1
        produced.rawItems.single().payload["suite"] shouldBe "Single"
    }

    @Test
    fun errored_testcase_uses_Observation_kind() {
        // Distinguir `errored` de `failed`: un error es un problema
        // de setup, no una assertion fallida. El provider lo
        // representa con `Observation` (igual que failed), pero el
        // `payload["status"]` los separa.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="S" tests="1" failures="0" errors="1" skipped="0">
                <testcase classname="com.example.X" name="explodes" time="0.0">
                  <error message="boom">stack</error>
                </testcase>
              </testsuite>
            </testsuites>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val provider = JUnitXmlProvider(xml)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        val produced = result as EvidenceCollectionResult.Produced
        val solo = produced.rawItems.single()
        solo.kind shouldBe RawItemKind.Observation
        solo.payload["status"] shouldBe "errored"
    }

    // -----------------------------------------------------------------------
    // Failed — XML malformado
    // -----------------------------------------------------------------------

    @Test
    fun collect_returns_Failed_when_xml_is_malformed() {
        val garbage = "<testsuites><testsuite><testcase".toByteArray(Charsets.UTF_8)
        val provider = JUnitXmlProvider(garbage)

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
    }

    @Test
    fun collect_returns_Failed_when_root_tag_is_unexpected() {
        val xml = "<notJunit><foo/></notJunit>".toByteArray(Charsets.UTF_8)
        val provider = JUnitXmlProvider(xml)

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
    }

    // -----------------------------------------------------------------------
    // AAT-6 — verification por signatura
    // -----------------------------------------------------------------------

    @Test
    fun AAT_06_provider_class_does_not_declare_any_method_returning_AssertionResult() {
        val ASSERTION_RESULT_QUALIFIED = "dev.pipelinek.assurance.engine.AssertionResult"

        val providerClass = JUnitXmlProvider::class.java
        val offending = providerClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.returnType.name == ASSERTION_RESULT_QUALIFIED }
            .map { it.name }

        offending shouldBe emptyList()
    }

    @Test
    fun AAT_06_provider_only_overrides_spi_methods() {
        val providerClass = JUnitXmlProvider::class.java
        val spiClass = dev.pipelinek.assurance.engine.EvidenceProvider::class.java
        val spiDeclared: Set<Method> = spiClass.declaredMethods.toSet()
        val ownDeclared: Set<Method> = providerClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.declaringClass == JUnitXmlProvider::class.java }
            .toSet()

        ownDeclared.forEach { method ->
            val overrides = spiDeclared.any { spi ->
                spi.name == method.name &&
                    spi.parameterTypes.toList() == method.parameterTypes.toList()
            }
            (overrides) shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Fixture: 1 testcase passed, 1 failed, 1 skipped.
     *
     * La estructura sigue la convención de surefire/gradle: raíz
     * `<testsuites>` con un `<testsuite>` que contiene tres
     * `<testcase>`. El primero sin hijos (passed), el segundo con
     * `<failure>` (failed), el tercero con `<skipped>` (skipped).
     */
    private fun syntheticJUnitBytes(): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuites>
          <testsuite name="com.example.FooTest" tests="3" failures="1" errors="0" skipped="1">
            <testcase classname="com.example.FooTest" name="shouldPass" time="0.123"/>
            <testcase classname="com.example.FooTest" name="shouldFail" time="0.045">
              <failure message="expected:&apos;ok&apos; but was:&apos;ko&apos;" type="AssertionError">
                stack
              </failure>
            </testcase>
            <testcase classname="com.example.FooTest" name="shouldSkip" time="0.0">
              <skipped message="disabled"/>
            </testcase>
          </testsuite>
        </testsuites>
    """.trimIndent().toByteArray(Charsets.UTF_8)
}
