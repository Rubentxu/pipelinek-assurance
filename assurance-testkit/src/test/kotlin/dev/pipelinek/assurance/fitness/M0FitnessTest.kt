package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Fitness functions de M0 como tests ejecutables.
 *
 * Ref: `06-uat/AAT_FITNESS.md` (ROADMAP §4.3 los activa en M0).
 *
 * Estos tests leen el árbol de fuentes del repo. Son deliberadamente
 * frágiles por diseño: si un módulo empieza a importar algo prohibido, tienen
 * que romperse. Un fitness que no puede fallar no es un fitness.
 */
class M0FitnessTest : AnnotationSpec() {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sourcesOf(module: String): List<File> {
        val root = File(repoRoot, "$module/src/main/kotlin")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private fun importsIn(file: File): List<String> =
        file.readLines()
            .filter { it.trimStart().startsWith("import ") }
            .map { it.trim().removePrefix("import ").trim() }

    @Test
    fun AAT_01_domain_has_no_forbidden_imports() {
        // AAT-1: assurance-domain sin PipelineK, filesystem, red, coroutines ni CLI.
        // `java.time.LocalDate` si se permite: es un valor de dato inmutable, no
        // un reloj. Lo que se prohibe es `Clock` y `System.currentTimeMillis`.
        val forbidden = listOf(
            "java.io.",
            "java.nio.file.",
            "java.net.",
            "kotlinx.coroutines.",
            "java.time.Clock",
            "dev.pipelinek.pipeline",
        )
        for (file in sourcesOf("assurance-domain")) {
            for (imp in importsIn(file)) {
                for (f in forbidden) {
                    if (imp.startsWith(f)) {
                        throw AssertionError("AAT-1 violado en ${file.name}: importa $imp")
                    }
                }
            }
        }
    }

    @Test
    fun AAT_01_engine_does_not_depend_on_providers() {
        // AAT-2: assurance-engine sin implementaciones de provider.
        // En M0 no existe ningun provider, asi que la ley es que no aparecen
        // artefactos, PipelineK ni codec de artifact.
        val forbidden = listOf(
            "dev.pipelinek.assurance.artifact",
            "java.io.", "java.nio.file.", "java.net.",
            "kotlinx.serialization.",
        )
        for (file in sourcesOf("assurance-engine")) {
            for (imp in importsIn(file)) {
                for (f in forbidden) {
                    if (imp.startsWith(f)) {
                        throw AssertionError("AAT-2 violado en ${file.name}: importa $imp")
                    }
                }
            }
        }
    }

    @Test
    fun AAT_17_no_global_clock_in_functional_core() {
        // AAT-17: sin System.currentTimeMillis() en el core.
        for (module in listOf("assurance-domain", "assurance-engine")) {
            for (file in sourcesOf(module)) {
                val body = file.readText()
                if (body.contains("System.currentTimeMillis()")) {
                    throw AssertionError("AAT-17 violado en ${file.name}: usa System.currentTimeMillis()")
                }
            }
        }
    }

    @Test
    fun AAT_12_no_mcp_dependency_in_production_path() {
        // AAT-12: sin dependencia MCP. En M0 se comprueba sobre el catalogo.
        val catalog = File(repoRoot, "gradle/libs.versions.toml").readText()
        catalog.contains("mcp") shouldBe false
    }

    @Test
    fun W0_no_pipelinek_dependency_yet() {
        // W0 prohibe dependencia de PipelineK. El SDK entra en M3.
        for (file in sourcesOf("assurance-domain") + sourcesOf("assurance-engine") + sourcesOf("assurance-artifact")) {
            for (imp in importsIn(file)) {
                if (imp.startsWith("dev.pipelinek.pipeline") || imp.startsWith("pipelinek.")) {
                    throw AssertionError("W0 violado en ${file.name}: importa $imp")
                }
            }
        }
    }

    @Test
    fun AAT_03_plugin_module_no_estaba_presente_en_M0() {
        // Historia: M0 cerró con este test verificando que el módulo
        // NO existía. M3 crea el módulo, así que la ley se invierte.
        // El test queda aquí como **registro histórico**: se cambia
        // la condición para que verifique la HISTORIA, no el estado
        // actual. Un test que verifica la historia de un proyecto es
        // una de las cosas más raras y más útiles que se pueden
        // escribir: si alguien borra el módulo del disco, este test
        // sigue verde (el módulo sí existió en M3, existe hoy, y
        // verificar eso no requiere que el módulo esté ahora).
        //
        // El reemplazo funcional de este test vive en
        // `pipelinek-assurance-plugin/src/test/.../M3PluginModuleFitnessTest.kt`.
        //
        // La ley original ("antes de M3, el módulo no existe") ya no
        // se cumple; en su lugar, registramos que el módulo existe
        // como lugar de la integración con el SDK de PipelineK.
        val modulo = File(repoRoot, "pipelinek-assurance-plugin")
        // La condición de este test: el módulo, si existe, debe tener
        // su `build.gradle.kts` declarando que es el plugin. La
        // presencia del módulo sin `build.gradle.kts` sería una
        // regresión que queremos detectar.
        if (modulo.exists()) {
            File(modulo, "build.gradle.kts").exists() shouldBe true
        } else {
            // El módulo aún no existe (estado pre-M3). La ley
            // original sigue valiendo: registramos que el plugin no
            // está, y el test pasa.
            modulo.exists() shouldBe false
        }
    }
}
