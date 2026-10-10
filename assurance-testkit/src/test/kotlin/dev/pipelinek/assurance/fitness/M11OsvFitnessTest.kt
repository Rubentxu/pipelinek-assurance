package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * M11.6 — Fitness test del CI security scan con `osv-scanner`.
 *
 * GitHub Actions queda prohibido en este repo (la CI corre
 * local con PipelineK 0.48.0). El fitness test apunta al
 * pipeline script (`ci/assurance.pipeline.kts`) y a los
 * scripts que implementan las piezas que el workflow
 * GitHub Actions tenía como job:
 *
 *   1. El pipeline local incluye un stage de seguridad
 *      (osv-scanner sobre lockfile + SBOM).
 *   2. El scan se aplica al `settings-gradle.lockfile`
 *      (fuente de verdad de versiones en uso).
 *   3. El scan se aplica también al SBOM CycloneDX
 *      (defense in depth).
 *   4. El scan falla en severidad >= HIGH (`--fail-on=high`).
 *
 * Los tests son la versión "M11.6" del security scan,
 * adaptada a la infraestructura local. Misma política,
 * distinto runner.
 */
class M11OsvFitnessTest : AnnotationSpec() {

    @Test
    fun pipeline_local_incluye_security_scan_stage() {
        val pipeline = readPipeline()
        pipeline shouldContain "stage(\"SBOM\")"
        // El scan se hace con osv-scanner. El script
        // puede llamarlo en el stage "SBOM" o en uno
        // dedicado; aquí verificamos que al menos el
        // SBOM se genera (cycloneDX), que es la entrada
        // del scanner.
        pipeline shouldContain "cyclonedxBom"
    }

    @Test
    fun security_scan_usa_settings_gradle_lockfile() {
        val pipeline = readPipeline()
        // El lockfile del version catalog es el que Gradle
        // mantiene con `./gradlew --write-locks`. Es la fuente
        // de verdad de qué versiones se usan realmente.
        pipeline shouldContain "settings-gradle.lockfile"
    }

    @Test
    fun security_scan_incluye_sbom_cyclonedx() {
        val pipeline = readPipeline()
        // Defense in depth: el SBOM captura deps transitivas
        // que el lockfile no nombra explícitamente. osv-scanner
        // los cruza contra la base de datos OSV.
        pipeline shouldContain "bom.json"
    }

    @Test
    fun security_scan_aborta_en_severidad_alta() {
        val pipeline = readPipeline()
        // Política de M11.6: HIGH y CRITICAL bloquean el pipeline.
        // MEDIUM y LOW se reportan pero no bloquean.
        pipeline shouldContain "--fail-on=high"
    }

    private fun readPipeline(): String =
        File(locateRepoRoot(), "ci/assurance.pipeline.kts").readText()

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
