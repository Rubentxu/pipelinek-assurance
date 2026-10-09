package dev.pipelinek.assurance.plugin

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * M3 — Fitness AAT-3 actualizado.
 *
 * Ref: `06-uat/AAT_FITNESS.md` AAT-3 ("el plugin es el único módulo que
 * depende del SDK") y ROADMAP §3.
 *
 * El test original en `M0FitnessTest.AAT_03_plugin_module_does_not_exist_before_m3`
 * afirmaba: el módulo `pipelinek-assurance-plugin` no debe existir antes
 * de M3. Con la creación de este módulo, la ley se invierte:
 *
 *   - Antes de M3: el módulo NO existe.
 *   - A partir de M3: el módulo existe, y la ley se convierte en
 *     "el módulo NO contiene `implementation(project(":assure-cli"))` ni
 *     `implementation(project(":assurance-providers"))`".
 *
 * Este test es la versión M3 del AAT-3. Cuando la integración con el
 * SDK se complete, AAT-3 añadirá un check sobre la presencia de la
 * dependencia `dev.pipelinek:pipelinek-sdk:*` en este módulo.
 */
class M3PluginModuleFitnessTest : AnnotationSpec() {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun AAT_03_plugin_module_existe_en_M3() {
        File(repoRoot, "pipelinek-assurance-plugin").exists() shouldBe true
    }

    @Test
    fun AAT_03_plugin_no_depende_de_assure_cli() {
        val buildFile = File(repoRoot, "pipelinek-assurance-plugin/build.gradle.kts")
        buildFile.exists() shouldBe true
        val body = buildFile.readText()
        (":assure-cli" in body && "implementation(project" in body) shouldBe false
    }

    @Test
    fun AAT_03_plugin_no_depende_de_assurance_providers() {
        val buildFile = File(repoRoot, "pipelinek-assurance-plugin/build.gradle.kts")
        val body = buildFile.readText()
        (":assurance-providers" in body && "implementation(project" in body) shouldBe false
    }
}
