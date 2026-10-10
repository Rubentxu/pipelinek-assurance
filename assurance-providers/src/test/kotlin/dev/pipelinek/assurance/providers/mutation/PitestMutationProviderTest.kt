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

    // --- C2 (Bloque C) — colisiones, identidad, entradas ---

    @Test
    fun C2_dos_mutaciones_misma_linea_producen_ids_distintos() {
        // C2: "Colisiones de IDs por línea/clase/fichero
        // resueltas". PIT produce múltiples mutaciones
        // por línea (distintos operadores). El id debe
        // distinguir el operador para que el normalizer
        // (AAT-13) no rechace el segundo por id
        // duplicado.
        val xml = """
            <mutations>
              <mutation detected="true" status="KILLED" numberOfTestsRun="3">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <lineNumber>10</lineNumber>
                <mutator>org.pitest.mutationtest.engine.gregor.mutators.ReturnValsMutator</mutator>
              </mutation>
              <mutation detected="false" status="SURVIVED" numberOfTestsRun="3">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <lineNumber>10</lineNumber>
                <mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>
              </mutation>
            </mutations>
        """.trimIndent()
        val provider = PitestMutationProvider(xml.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.rawItems.size shouldBe 2
        val ids = produced.rawItems.map { it.id }
        ids.distinct().size shouldBe ids.size
    }

    @Test
    fun C2_identidad_preservada_mismo_mutator_mismo_id() {
        // C2: "Preservar identidad de mutantes". El id
        // incluye el mutator, así que dos invocaciones
        // del provider con el mismo XML producen el
        // mismo conjunto de ids (no se randomiza).
        val xml = """
            <mutations>
              <mutation detected="true" status="KILLED">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <lineNumber>10</lineNumber>
                <mutator>INCREMENTS</mutator>
              </mutation>
            </mutations>
        """.trimIndent()
        val a = PitestMutationProvider(xml.toByteArray()).collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val b = PitestMutationProvider(xml.toByteArray()).collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val aItems = a.shouldBeInstanceOf<EvidenceCollectionResult.Produced>().rawItems
        val bItems = b.shouldBeInstanceOf<EvidenceCollectionResult.Produced>().rawItems
        aItems.map { it.id } shouldBe bItems.map { it.id }
    }

    @Test
    fun C2_xml_parcial_con_algunos_elementos_faltantes_no_aborta() {
        // C2: "Verificar entradas vacías, inválidas,
        // enormes, parciales". Una mutación sin
        // <mutator> se acepta como `unknown` (no
        // abortamos): la frontera del provider es
        // permissive con campos opcionales. El
        // normalizer rechazará después si la
        // authority/namespace no encaja, pero el
        // provider no aborta mid-parse.
        val xml = """
            <mutations>
              <mutation detected="true" status="KILLED">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <lineNumber>10</lineNumber>
              </mutation>
            </mutations>
        """.trimIndent()
        val provider = PitestMutationProvider(xml.toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        produced.rawItems.single().payload["mutator"] shouldBe "unknown"
    }

    @Test
    fun C2_xml_invalido_es_Failed_con_motivo() {
        // C2: entrada inválida produce Failed, NO
        // excepción que escape. La frontera del
        // provider es cerrada: XML sin <mutation>
        // → Failed con motivo legible.
        val provider = PitestMutationProvider("esto no es xml".toByteArray())
        val outcome = provider.collect(EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")))
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }
}
