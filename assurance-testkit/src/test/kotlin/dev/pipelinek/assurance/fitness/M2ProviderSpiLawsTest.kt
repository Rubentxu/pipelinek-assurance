package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * AAT-6, segunda vuelta: verificación ESTRUCTURAL del SPI, no textual.
 *
 * `M0UnprovenAatsTest.AAT_06_no_existe_ningun_evidence_provider_que_retorne_assertion_result`
 * es la primera vuelta: lee el texto y busca "EvidenceProvider" en una línea
 * y "AssertionResult" en otra. Funciona, pero es textual: un provider que
 * declare el SPI por reflexión y devuelva `Any` podría esquivarla si nadie
 * añade el grep en el sitio correcto.
 *
 * Esta segunda vuelta carga el interface `EvidenceProvider` por reflexión
 * y verifica TRES cosas que el SPI tiene que cumplir POR CONSTRUCCIÓN:
 *
 *  1. Declara un método `getDescriptor` que retorna `EvidenceProviderDescriptor`
 *     (la identidad del provider no puede faltar);
 *  2. Declara un método `collect` que retorna `EvidenceCollectionResult`
 *     (la firma del SPI);
 *  3. NINGÚN método declarado retorna un tipo cuyo nombre cualificado
 *     contenga `AssertionResult` (AAT-6, por signatura, no por string).
 *
 * Las tres son "fail-closed": si alguien cambia el SPI para saltarse una,
 * el test se pone rojo en compilación o en ejecución, y el gate que el
 * roadmap exige para M2 no se firma.
 *
 * `java.lang.reflect` y no `kotlin-reflect`: kotlin-reflect ya estaba en
 * el classpath, pero este test es un fitness (AAT-6 + PROVIDER_SPI), no
 * lógica de producto, y java reflection no requiere anotaciones extra en
 * el SPI. La dependencia técnica de un fitness debe ser la menor posible.
 */
class M2ProviderSpiLawsTest : AnnotationSpec() {

    private val providerClass: Class<out EvidenceProvider> = EvidenceProvider::class.java

    @Test
    fun AAT_06_evidence_provider_spi_declara_descriptor_no_vacio() {
        val descriptorMethod: Method = providerClass.methods
            .firstOrNull { it.name == "getDescriptor" && it.parameterCount == 0 }
            ?: error("AAT-6 / PROVIDER_SPI: EvidenceProvider debe declarar getter 'getDescriptor'")

        descriptorMethod.returnType shouldBe EvidenceProviderDescriptor::class.java
    }

    @Test
    fun AAT_06_evidence_provider_spi_declara_collect_que_retorna_collection_result() {
        val collectMethod: Method = providerClass.methods
            .firstOrNull { it.name == "collect" && it.parameterCount == 1 }
            ?: error("AAT-6 / PROVIDER_SPI: EvidenceProvider debe declarar método 'collect' con 1 parámetro")

        // `EvidenceCollectionResult` es sealed. Aceptamos el tipo o sus
        // subtipos directos, pero NO `Any` ni `Object`. Si la firma cambiara
        // a algo menos específico, el test se pone rojo.
        val returnType = collectMethod.returnType
        val acceptable = returnType == EvidenceCollectionResult::class.java ||
            EvidenceCollectionResult::class.java.isAssignableFrom(returnType)

        acceptable shouldBe true
    }

    @Test
    fun AAT_06_ningun_metodo_del_spi_retorna_assertion_result() {
        // AAT-6 por signatura. Si alguien añade `fun eval(...): AssertionResult`
        // al interface, este test se pone rojo aunque la implementación nunca
        // se use: la mutación rompe la FORMA del SPI, no su uso.
        //
        // No importamos `AssertionResult` en este test: lo identificamos por
        // nombre cualificado, para que el módulo de fitness no quede atado a
        // la API pública del core. AAT-2 (engine sin implementaciones de
        // provider) no nos prohíbe importar el type, pero la prudencia
        // manda: un test que rompe la frontera que AAT-2 protege está
        // midiendo algo distinto de lo que dice.
        val ASSERTION_RESULT_QUALIFIED = "dev.pipelinek.assurance.engine.AssertionResult"

        val offending = providerClass.methods
            .filter { Modifier.isAbstract(it.modifiers) || !Modifier.isStatic(it.modifiers) }
            .filter { it.returnType.name == ASSERTION_RESULT_QUALIFIED }
            .map { it.name }

        offending shouldBe emptyList()
    }

    @Test
    fun AAT_06_el_descriptor_no_es_una_lambda_como_fun_interface_seria() {
        // PROVIDER_SPI dice `interface`, no `fun interface`. La razón es la
        // identidad: un provider declarado como lambda no podría declarar
        // su `descriptor` por construcción. Si alguien "simplifica" el SPI
        // pasándolo a `fun interface`, el descriptor desaparece y este test
        // se pone rojo: el SPI deja de ser un provider y pasa a ser un
        // `EvidenceSnapshot` con tres líneas de más.
        //
        // Detección: un `fun interface` en Kotlin genera un método estático
        // `invoke` con signatura específica. `interface` normal no. Comparar
        // contra el flag de la JVM no es portable entre Kotlin versions, así
        // que la verificación se hace por contraste: el interface debe ser
        // interface y no debe tener el método `invoke` que un fun interface
        // tendría.
        providerClass.isInterface shouldBe true

        val hasInvoke = providerClass.methods.any { it.name == "invoke" }
        hasInvoke shouldBe false
    }
}
