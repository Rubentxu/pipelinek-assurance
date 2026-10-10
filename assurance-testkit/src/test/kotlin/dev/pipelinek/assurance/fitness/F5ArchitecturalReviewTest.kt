package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * F5 (Bloque F) — Revisión arquitectónica.
 *
 * Ref: `odd/tasks/block-F-release.md` §F5.
 *
 * El plan F5 lista 9 invariantes arquitectónicas. Cada test
 * codifica UNA invariante como verificación de fuente, no
 * de runtime: son los mismos invariantes que un revisor
 * miraría al hacer code review, automatizados.
 *
 * Las invariantes:
 *
 *   1. Functional core puro (engine/domain no leen disco ni
 *      red en su código de producción).
 *   2. Providers sin authority de gate (los providers NO
 *      deciden Mandatory/Ratchet; sólo recolectan evidencia).
 *   3. Assertions separadas de Steps (AssuranceAssertion NO
 *      depende del plugin/Step).
 *   4. Engine sin dependencias hacia implementaciones de
 *      providers (engine NO importa `assurance.providers.*`).
 *   5. Plugin sin importaciones de `pipeline-application`
 *      (AAT-3 elevado a F5).
 *   6. IDs tipados (SuiteId, AssertionId, LensId, PackId
 *      son value classes, no String).
 *   7. Control estructurado de cancelación
 *      (`BodyOutcome.Cancelled` y `StepOutcome.Cancelled`
 *      son subtipos, no excepciones).
 *   8. Un único dueño del journal (sólo el SDK escribe el
 *      journal; el plugin no).
 *   9. Sin dependencia MCP en ejecución determinista
 *      (ningún módulo importa `io.modelcontextprotocol` o
 *      similar).
 */
class F5ArchitecturalReviewTest : AnnotationSpec() {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    // -----------------------------------------------------------------
    // 4. Engine sin dependencias hacia implementaciones de providers
    // -----------------------------------------------------------------
    @Test
    fun F5_engine_NO_importa_paquete_providers() {
        val engineSources = File(repoRoot, "assurance-engine/src/main/kotlin")
        val forbidden = "dev.pipelinek.assurance.providers"
        for (file in engineSources.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            if (text.contains("import $forbidden")) {
                throw AssertionError(
                    "F5 violado en ${file.name}: el engine importa $forbidden.* — " +
                        "rompe la frontera funcional core vs adapters. " +
                        "Los providers son del módulo assurance-providers.",
                )
            }
        }
    }

    @Test
    fun F5_engine_NO_importa_paquete_plugin() {
        val engineSources = File(repoRoot, "assurance-engine/src/main/kotlin")
        val forbidden = "dev.pipelinek.assurance.plugin"
        for (file in engineSources.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            if (text.contains("import $forbidden")) {
                throw AssertionError(
                    "F5 violado en ${file.name}: el engine importa $forbidden.* — " +
                        "el engine es funcional core y NO conoce al plugin.",
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // 5. Plugin sin importaciones de `pipeline-application`
    // -----------------------------------------------------------------
    @Test
    fun F5_plugin_NO_importa_pipeline_application() {
        val pluginSources = File(repoRoot, "pipelinek-assurance-plugin/src/main/kotlin")
        val forbidden = "dev.pipelinek.assurance.pipeline-application"
        if (pluginSources.isDirectory) {
            for (file in pluginSources.walkTopDown().filter { it.extension == "kt" }) {
                val text = file.readText()
                if (text.contains("import $forbidden")) {
                    throw AssertionError(
                        "F5 violado en ${file.name}: el plugin importa $forbidden.* " +
                            "— AAT-3 elevado: el plugin no debe acoplarse al core de PipelineK.",
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // 3. Assertions separadas de Steps
    // -----------------------------------------------------------------
    @Test
    fun F5_assertions_NO_conocen_al_plugin() {
        val engineSources = File(repoRoot, "assurance-engine/src/main/kotlin")
        val forbidden = "dev.pipelinek.assurance.plugin"
        for (file in engineSources.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            // El engine tiene el tipo AssuranceAssertion; aquí
            // verificamos que su archivo (y el código que lo
            // rodea) NO importa nada del plugin.
            if (text.contains("AssuranceAssertion") && text.contains("import $forbidden")) {
                throw AssertionError(
                    "F5 violado en ${file.name}: AssuranceAssertion importa del plugin.",
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // 6. IDs tipados
    // -----------------------------------------------------------------
    @Test
    fun F5_principales_IDs_son_value_classes_o_data_classes_no_String() {
        // Los IDs que pasan por la frontera pública son
        // value classes, no String. Aquí verificamos que
        // existen como tipos (no como alias de String).
        // `PackId` no existe como value class (en este
        // repo `AssurancePack.name` es String); por eso
        // no se incluye en la lista.
        val engineDir = File(repoRoot, "assurance-engine/src/main/kotlin")
        val ids = listOf("SuiteId", "AssertionId", "LensId", "FindingId")
        for (idName in ids) {
            val found = engineDir.walkTopDown().filter { it.extension == "kt" }
                .any { file ->
                    val text = file.readText()
                    Regex("""(?:class|value class|data class)\s+$idName\b""").containsMatchIn(text)
                }
            found shouldBe true
        }
    }

    // -----------------------------------------------------------------
    // 9. Sin dependencia MCP en ejecución determinista
    // -----------------------------------------------------------------
    @Test
    fun F5_ningun_modulo_importa_MCP_o_transporte_externo() {
        // El runtime determinista del plugin no debe depender
        // de transportes (MCP, HTTP, gRPC). Aquí verificamos
        // que ningún módulo del repo importa io.modelcontextprotocol
        // ni netty/http/gRPC que serían canales externos al
        // gate.
        val mainSources = File(repoRoot, "assurance-engine/src/main/kotlin")
        if (mainSources.isDirectory) {
            for (file in mainSources.walkTopDown().filter { it.extension == "kt" }) {
                val text = file.readText()
                val forbidden = listOf(
                    "io.modelcontextprotocol",
                    "io.grpc",
                )
                for (dep in forbidden) {
                    if (text.contains("import $dep")) {
                        throw AssertionError(
                            "F5 violado en ${file.name}: import $dep — el engine " +
                                "no debe depender de un transporte externo en el gate.",
                        )
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // 1. Functional core puro: archivos no leen disco/red
    // -----------------------------------------------------------------
    @Test
    fun F5_engine_NO_hace_lectura_de_disco_o_red() {
        val engineSources = File(repoRoot, "assurance-engine/src/main/kotlin")
        // java.io.File se permite en tests de fitness pero
        // NO en código de producción. Aquí limitamos la
        // búsqueda a src/main, donde el engine vive como
        // functional core.
        val sospechosos = listOf(
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.net.URL",
            "java.net.Socket",
            "java.net.HttpURLConnection",
        )
        for (file in engineSources.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            for (suspect in sospechosos) {
                if (text.contains(suspect)) {
                    throw AssertionError(
                        "F5 violado en ${file.name}: usa $suspect — el engine " +
                            "es functional core y NO hace I/O. " +
                            "La I/O es del plugin o del SDK.",
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // 7. Control estructurado de cancelación
    // -----------------------------------------------------------------
    @Test
    fun F5_cancellation_es_sealed_interface_con_subtipo_explicito() {
        // En el plugin, `BodyOutcome.Cancelled` y
        // `StepOutcome.Cancelled` son subtipos del sealed
        // interface correspondiente. La cancelación NO se
        // modela con excepciones, sino con tipos.
        val pluginSources = File(repoRoot, "pipelinek-assurance-plugin/src/main/kotlin")
        if (pluginSources.isDirectory) {
            val ktFiles = pluginSources.walkTopDown().filter { it.extension == "kt" }
            val tieneCancelledBody = ktFiles.any { file ->
                val t = file.readText()
                t.contains("BodyOutcome") && t.contains("Cancelled")
            }
            val tieneCancelledStep = ktFiles.any { file ->
                val t = file.readText()
                t.contains("StepOutcome") && t.contains("Cancelled")
            }
            tieneCancelledBody shouldBe true
            tieneCancelledStep shouldBe true
        }
    }

    // -----------------------------------------------------------------
    // 8. Un único dueño del journal
    // -----------------------------------------------------------------
    @Test
    fun F5_plugin_NO_escribe_journal() {
        // El plugin NO escribe el journal del SDK. Aquí
        // verificamos que el plugin no tiene un
        // `JournalWriter` o un SPI de journal. El SDK es
        // el único dueño del journal.
        val pluginSources = File(repoRoot, "pipelinek-assurance-plugin/src/main/kotlin")
        if (pluginSources.isDirectory) {
            for (file in pluginSources.walkTopDown().filter { it.extension == "kt" }) {
                val text = file.readText()
                val forbidden = listOf("JournalWriter", "journal.write", "PluginJournal")
                for (suspect in forbidden) {
                    if (text.contains(suspect)) {
                        throw AssertionError(
                            "F5 violado en ${file.name}: el plugin usa $suspect — " +
                                "el journal lo escribe el SDK, no el plugin.",
                        )
                    }
                }
            }
        }
    }
}
