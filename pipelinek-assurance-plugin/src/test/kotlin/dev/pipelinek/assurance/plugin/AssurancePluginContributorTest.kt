package dev.pipelinek.assurance.plugin

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.ServiceLoader

/**
 * M3/M7 — Tests del `AssurancePluginContributor` y su descubrimiento
 * via ServiceLoader.
 *
 * **AAT-3:** este plugin es el único módulo del repo que depende
 * del SDK. El descubrimiento es via ServiceLoader; el archivo
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
 * declara el contributor.
 *
 * **AAT-10:** el plugin no contiene `when (stepKey == "assurance...")`
 * ni otros atajos. La verificación se hace por grep sobre el código
 * del plugin.
 *
 * Lo que estos tests verifican:
 *   1. El archivo `META-INF/services/...` existe y nombra la clase
 *      correcta.
 *   2. La clase `AssurancePluginContributor` existe y se puede
 *      instanciar.
 *   3. La forma del `definitions` (lista) es estable.
 *   4. El plugin no itera `StepNode` ni importa `pipeline-application`.
 */
class AssurancePluginContributorTest : AnnotationSpec() {

    private val moduleRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun el_meta_inf_services_declara_el_contributor() {
        val serviceFile = File(
            moduleRoot,
            "pipelinek-assurance-plugin/src/main/resources/META-INF/services/" +
                "dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor",
        )
        serviceFile.exists() shouldBe true
        val body = serviceFile.readText().trim()
        body shouldBe "dev.pipelinek.assurance.plugin.AssurancePluginContributor"
    }

    @Test
    fun el_contributor_se_instancia_y_declara_definitions() {
        val contributor = AssurancePluginContributor()
        contributor.id shouldBe "pipelinek-assurance"
        contributor.definitions().count() shouldBe 2
    }

    @Test
    fun el_contributor_declara_los_dos_steps_conocidos() {
        val contributor = AssurancePluginContributor()
        val keys = contributor.definitions().map { d ->
            d.contract.key.value
        }
        keys shouldBe listOf("assurance.check", "assurance.verify")
    }

    @Test
    fun el_plugin_no_importa_pipeline_application() {
        // AAT-11: `assurance.verify` no itera StepNode ni importa el
        // coordinator de aplicación. La frontera es observable en
        // el código: ningún archivo del plugin importa el package
        // `dev.rubentxu.pipeline.v2.application.*`.
        val pluginRoot = File(moduleRoot, "pipelinek-assurance-plugin/src/main/kotlin")
        val files = pluginRoot.walkTopDown().filter { it.extension == "kt" }.toList()
        for (file in files) {
            val body = file.readText()
            (body.contains("dev.rubentxu.pipeline.v2.application")) shouldBe false
        }
    }

    @Test
    fun el_plugin_no_tiene_when_sobre_stepkey() {
        // AAT-10: el plugin no tiene `when (stepKey == "assurance...")`
        // ni dispatcher basado en string. Verificamos por grep,
        // excluyendo comentarios y KDoc (que pueden mencionar el patrón
        // para documentar que NO se usa).
        val pluginRoot = File(moduleRoot, "pipelinek-assurance-plugin/src/main/kotlin")
        val files = pluginRoot.walkTopDown().filter { it.extension == "kt" }.toList()
        for (file in files) {
            // Filtrar comentarios: líneas que empiezan con `*`, `//`,
            // o están dentro de bloques `/* ... */` o `/** ... */`.
            val body = file.readText()
                // Eliminar KDoc y comentarios de línea antes de buscar.
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
                .replace(Regex("""//[^\n]*"""), "")
            (body.contains("when (stepKey") || body.contains("when(stepKey")) shouldBe false
        }
    }

    @Test
    fun serviceloader_del_sdk_descubre_el_contributor() {
        // B1: el host del SDK descubre el plugin via
        // `ServiceLoader.load(StepDefinitionContributor::class.java)`.
        // Verificamos que, dado el classpath actual del test
        // (que incluye el SDK 0.48.0 y el plugin), el loader
        // encuentra al menos un contributor cuya clase sea
        // `AssurancePluginContributor`.
        val contributorClass = Class.forName(
            "dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor",
        )
        @Suppress("UNCHECKED_CAST")
        val loader = java.util.ServiceLoader.load(contributorClass as Class<Any>)
        val found = loader.toList()
        val ours = found.firstOrNull { it.javaClass.name == "dev.pipelinek.assurance.plugin.AssurancePluginContributor" }
        (ours != null) shouldBe true
    }
}
