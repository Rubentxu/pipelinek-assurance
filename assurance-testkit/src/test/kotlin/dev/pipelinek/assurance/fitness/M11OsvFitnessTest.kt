package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * M11.6 — Fitness test del CI security scan con `osv-scanner`.
 *
 * Lo que se verifica:
 *   1. El workflow de CI incluye un job `security-scan` con
 *      `osv-scanner-action`.
 *   2. El scan se aplica al `settings-gradle.lockfile` (la
 *      fuente de verdad de versiones en uso).
 *   3. El scan se aplica también al SBOM CycloneDX
 *      (defense in depth: detecta vulnerabilidades en deps
 *      transitivas que el lockfile no captura explícitamente).
 *   4. El scan falla en severidad >= HIGH (`--fail-on=high`).
 */
class M11OsvFitnessTest : AnnotationSpec() {

    @Test
    fun workflow_incluye_security_scan_job() {
        val workflow = readWorkflow()
        workflow shouldContain "security-scan:"
        workflow shouldContain "osv-scanner-action"
    }

    @Test
    fun security_scan_usa_settings_gradle_lockfile() {
        val workflow = readWorkflow()
        // El lockfile del version catalog es el que Gradle
        // mantiene con `./gradlew --write-locks`. Es la fuente
        // de verdad de qué versiones se usan realmente.
        workflow shouldContain "settings-gradle.lockfile"
    }

    @Test
    fun security_scan_incluye_sbom_cyclonedx() {
        val workflow = readWorkflow()
        // Defense in depth: el SBOM captura deps transitivas
        // que el lockfile no nombra explícitamente. osv-scanner
        // los cruza contra la base de datos OSV.
        workflow shouldContain "bom.json"
    }

    @Test
    fun security_scan_aborta_en_severidad_alta() {
        val workflow = readWorkflow()
        // Política de M11.6: HIGH y CRITICAL bloquean la PR.
        // MEDIUM y LOW se reportan pero no bloquean.
        workflow shouldContain "--fail-on=high"
    }

    private fun readWorkflow(): String {
        val repoRoot = locateRepoRoot()
        val candidates = listOf(
            File(repoRoot, ".github/workflows/ci.yml"),
            File(repoRoot, ".github/workflows/security-scan.yml"),
        )
        return candidates.first { it.exists() }.readText()
    }

    private fun locateRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            dir = dir?.parentFile ?: return@repeat
            if (File(dir, "gradle/libs.versions.toml").exists()) {
                return dir
            }
        }
        return File(".").absoluteFile
    }
}
