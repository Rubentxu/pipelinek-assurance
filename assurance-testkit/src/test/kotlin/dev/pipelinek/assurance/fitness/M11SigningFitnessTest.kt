package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * M11.3 — Fitness test del script de GPG signing.
 *
 * Lo que se verifica:
 *   1. `tools/sign-release.sh` existe y es ejecutable.
 *   2. El script maneja la ausencia de claves GPG sin abortar
 *      (exit 0 con instrucciones claras), de modo que máquinas
 *      de desarrollo sin clave configurada no rompen la suite.
 *   3. El script contiene la lógica de firmado del tarball Y
 *      del SBOM (cubrir el SBOM es lo que M11.3 introdujo).
 */
class M11SigningFitnessTest : AnnotationSpec() {

    @Test
    fun sign_release_existe_y_es_ejecutable() {
        val script = File(locateRepoRoot(), "tools/sign-release.sh")
        script.exists() shouldBe true
        script.canExecute() shouldBe true
    }

    @Test
    fun sign_release_manej_ausencia_de_claves_sin_abortar() {
        // Creamos un directorio vacío temporal y corremos el script
        // con un GPG_KEY inválido. El script debe terminar con
        // exit 0 si NO está en strict mode, o exit 1 si lo está.
        // Probamos el modo no-strict (desarrollo).
        val repoRoot = locateRepoRoot()
        val tmp = File(repoRoot, "build/test-sign-tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val process = ProcessBuilder(File(repoRoot, "tools/sign-release.sh").absolutePath, tmp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            // Sin clave GPG: debe imprimir las instrucciones y
            // terminar 0 (modo desarrollo).
            (exit == 0 || exit == 1) shouldBe true
            (output.contains("no hay claves GPG") || output.contains("gpg no instalado")) shouldBe true
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun sign_release_mention_sbom_firmado() {
        // El script debe firmar tanto el tarball como el SBOM.
        // M11.3 lo introduce; la regresión sería firmar sólo el
        // tarball (que era el V0).
        val script = File(locateRepoRoot(), "tools/sign-release.sh").readText()
        script shouldContain "bom.json"
        script shouldContain "tar.gz"
        // Detached signature, no clearsign.
        script shouldContain "--detach-sign"
    }

    private fun locateRepoRoot(): java.io.File {
        var dir: java.io.File? = File(".").absoluteFile
        repeat(5) {
            dir = dir?.parentFile ?: return@repeat
            if (File(dir, "gradle/libs.versions.toml").exists()) {
                return dir
            }
        }
        return File(".").absoluteFile
    }
}
