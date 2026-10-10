package dev.pipelinek.assurance.testkit

import java.io.File

/**
 * Helpers para tests de SBOM (M11.2).
 *
 * Resuelve la ruta al `bom.json` generado por la tarea
 * `cyclonedxBom` para los módulos que producen distribución.
 * El path se busca desde la raíz del repo (donde Gradle invoca
 * los tests), con fallback a `..` cuando se ejecuta desde un
 * sub-módulo.
 */
object SbomFixture {

    fun pluginSbom(): File = locate("pipelinek-assurance-plugin/build/reports/bom.json")

    fun cliSbom(): File = locate("assure-cli/build/reports/bom.json")

    private fun locate(relative: String): File {
        // Buscar el repo root: subimos hasta encontrar `gradle/libs.versions.toml`
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            dir = dir?.parentFile ?: return@repeat
            if (File(dir, "gradle/libs.versions.toml").exists()) {
                return File(dir, relative)
            }
        }
        return File(relative)
    }
}
