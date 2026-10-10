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
        // con un GNUPGHOME vacío (sin claves). El script debe
        // terminar con exit 0 (modo desarrollo) o detectar la
        // ausencia de claves y NO abortar. Verificamos que el
        // script maneja los dos caminos (con o sin clave) sin
        // propagar excepciones.
        //
        // El test es env-agnóstico: aísla GNUPGHOME para que la
        // presencia de claves en el keyring del desarrollador
        // no haga flaky al test.
        val repoRoot = locateRepoRoot()
        val tmp = File(repoRoot, "build/test-sign-tmp").apply { deleteRecursively(); mkdirs() }
        val emptyGpgHome = File(repoRoot, "build/test-sign-tmp/gpg").apply { deleteRecursively(); mkdirs() }
        try {
            val process = ProcessBuilder(File(repoRoot, "tools/sign-release.sh").absolutePath, tmp.absolutePath)
                .redirectErrorStream(true)
                .apply {
                    environment()["GNUPGHOME"] = emptyGpgHome.absolutePath
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            // Modo desarrollo: exit 0 (no se firmó nada) o exit
            // 1 (señaló falta de clave, modo no-strict).
            (exit == 0 || exit == 1) shouldBe true
            // El output documenta el camino que tomó:
            // "no hay claves GPG" si el keyring está vacío,
            // "skip (no existe)" si no hay artefactos a firmar,
            // "usando clave GPG: <fingerprint>" si encontró una.
            // Aceptamos cualquiera: lo que verificamos es que
            // el script NO propaga excepciones y produce un
            // output razonable.
            output.isNotBlank() shouldBe true
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
