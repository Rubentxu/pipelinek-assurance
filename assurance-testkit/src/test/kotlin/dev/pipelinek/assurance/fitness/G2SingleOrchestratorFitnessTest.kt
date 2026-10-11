package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * G2 (Bloque G) — fitness test del "un solo orquestador".
 *
 * Regla 7 del bloque G: el CLI, los tests de aceptación
 * y el plugin llaman al mismo método
 * `AssuranceOrchestrator.orchestrate(OrchestrationRequest)`.
 * Verificable inspeccionando el árbol de fuentes: no debe
 * haber un segundo orquestador que compita con
 * `dev.pipelinek.assurance.plugin.AssuranceOrchestrator`.
 *
 * Si pasan estos 2 tests, la regla 7 está cerrada.
 */
class G2SingleOrchestratorFitnessTest : AnnotationSpec() {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sourcesOf(module: String): List<File> {
        val root = File(repoRoot, "$module/src/main/kotlin")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private fun ktFilesIn(vararg modules: String): List<File> =
        modules.flatMap { sourcesOf(it) }

    @Test
    fun G2_un_solo_clase_AssuranceOrchestrator() {
        // Busca todas las definiciones de `object AssuranceOrchestrator`
        // (NO de tests, NO de `AssuranceOrchestratorTest`) en main, no test.
        // Debe haber exactamente 1 en `pipelinek-assurance-plugin/`.
        val mainSources = ktFilesIn(
            "assurance-domain",
            "assurance-engine",
            "assurance-artifact",
            "assurance-providers",
            "assure-cli",
            "pipelinek-assurance-plugin",
        )
        val matches = mutableListOf<String>()
        val re = Regex("""^(class|object)\s+AssuranceOrchestrator\b""")
        for (file in mainSources) {
            for (line in file.readLines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//")) continue
                if (re.containsMatchIn(trimmed)) {
                    matches += "${file.relativeTo(repoRoot)}: $trimmed"
                }
            }
        }
        matches.size shouldBe 1
        matches.first().contains("pipelinek-assurance-plugin/src/main/") shouldBe true
    }

    @Test
    fun G2_assure_cli_no_define_orquestador_propio() {
        // El CLI no debe tener su propio pipeline de evaluación
        // que compita con el orchestrator. Verificable
        // buscando llamadas a `AssuranceEngine.evaluateSuite`
        // desde `assure-cli/`.
        val cli = ktFilesIn("assure-cli")
        for (file in cli) {
            for (line in file.readLines()) {
                if (line.trimStart().startsWith("//")) continue
                if (line.contains("AssuranceEngine.evaluateSuite")) {
                    throw AssertionError(
                        "G2 violado en ${file.name}: " +
                            "assure-cli define su propio pipeline de evaluación; " +
                            "debe delegar a AssuranceOrchestrator.orchestrate(). " +
                            "Línea: $line"
                    )
                }
            }
        }
    }
}
