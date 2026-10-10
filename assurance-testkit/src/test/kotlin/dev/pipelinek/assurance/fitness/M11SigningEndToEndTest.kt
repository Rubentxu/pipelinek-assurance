package dev.pipelinek.assurance.fitness

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * M11.3 — Fitness E2E del flujo completo de signing.
 *
 * Lo que añade sobre `M11SigningFitnessTest` (que verifica el
 * script en modo "sin clave"):
 *
 * 1. Genera un keyring GPG de prueba en un directorio temporal.
 * 2. Crea artefactos dummy (tarball, sha256, sbom).
 * 3. Ejecuta `sign-release.sh` con la clave de prueba.
 * 4. Verifica que las firmas detached se crean.
 * 5. Verifica que las firmas verifican contra la clave pública.
 * 6. Limpia el keyring temporal.
 *
 * Por qué es un fitness y no un test de integración: la lógica
 * de signing vive en un script de shell, no en código Kotlin. El
 * test tiene que ejecutar el script, no reimplementar la lógica.
 * Si el script rompe su contrato, este test lo detecta.
 *
 * Si GPG no está disponible en el entorno, el test se salta con
 * SKIP en vez de fallar (es un fitness de supply chain, no del
 * core).
 */
class M11SigningEndToEndTest : AnnotationSpec() {

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

    private fun gpgAvailable(): Boolean {
        return try {
            val builder = ProcessBuilder("gpg", "--version").apply {
                redirectErrorStream(true)
            }
            val p = builder.start()
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    @Test
    fun sign_release_firma_y_verifica_round_trip() {
        if (!gpgAvailable()) {
            // gpg no está en el entorno: el fitness no aplica.
            // El test pasa y deja nota en consola.
            println("[skip] gpg not available; signing E2E skipped")
            return
        }

        val repoRoot = locateRepoRoot()
        val workDir = File(repoRoot, "build/test-sign-e2e").apply {
            deleteRecursively()
            mkdirs()
        }
        val gnupgHome = File(workDir, "gnupg").apply { mkdirs() }
        try {
            // 1. Generar keyring de prueba.
            val keyGenScript = """
                %no-protection
                Key-Type: RSA
                Key-Length: 2048
                Name-Real: Assurance Test
                Name-Email: assurance-test@example.invalid
                Expire-Date: 1
                %commit
            """.trimIndent()
            File(workDir, "keygen.batch").writeText(keyGenScript)

            val genProc = ProcessBuilder(
                "gpg",
                "--batch",
                "--homedir", gnupgHome.absolutePath,
                "--gen-key", File(workDir, "keygen.batch").absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val genOut = genProc.inputStream.bufferedReader().readText()
            genProc.waitFor()
            if (genProc.exitValue() != 0) {
                throw IllegalStateException("gpg keygen failed: $genOut")
            }

            // 2. Crear artefactos dummy.
            val tarball = File(workDir, "assure-cli-test.tar.gz").apply {
                writeBytes("dummy tarball content".toByteArray())
            }
            val sha256File = File(workDir, "assure-cli-test.tar.gz.sha256").apply {
                writeText("dummy  ${tarball.name}\n")
            }
            val bom = File(workDir, "bom.json").apply {
                writeText("""{"bomFormat":"CycloneDX","specVersion":"1.5","components":[]}""")
            }

            // 3. Ejecutar sign-release.sh.
            val signScript = File(repoRoot, "tools/sign-release.sh").absolutePath
            val signBuilder = ProcessBuilder(signScript, workDir.absolutePath).apply {
                environment()["GNUPGHOME"] = gnupgHome.absolutePath
                environment()["HOME"] = workDir.absolutePath
                redirectErrorStream(true)
            }
            val signProc = signBuilder.start()
            val signOut = signProc.inputStream.bufferedReader().readText()
            val signExit = signProc.waitFor()
            if (signExit != 0) {
                throw IllegalStateException("sign-release.sh failed (exit=$signExit): $signOut")
            }

            // 4. Verificar que las firmas existen.
            val tarSig = File(workDir, "${tarball.name}.asc")
            val shaSig = File(workDir, "${sha256File.name}.asc")
            val bomSig = File(workDir, "${bom.name}.asc")
            (tarSig.exists() && tarSig.length() > 0) shouldBe true
            (shaSig.exists() && shaSig.length() > 0) shouldBe true
            (bomSig.exists() && bomSig.length() > 0) shouldBe true

            // 5. Verificar que las firmas verifican contra la
            // clave pública.
            for (sig in listOf(tarSig, shaSig, bomSig)) {
                val verifyProc = ProcessBuilder(
                    "gpg",
                    "--homedir", gnupgHome.absolutePath,
                    "--verify", sig.absolutePath,
                )
                    .redirectErrorStream(true)
                    .start()
                val verifyOut = verifyProc.inputStream.bufferedReader().readText()
                val verifyExit = verifyProc.waitFor()
                if (verifyExit != 0) {
                    throw IllegalStateException(
                        "gpg --verify failed for ${sig.name}: $verifyOut",
                    )
                }
                // La salida de `gpg --verify` sobre detached sig
                // debe contener "Good signature" (inglés) o
                // "Firma correcta" (español), según el locale.
                val goodSig = verifyOut.contains("Good signature") ||
                    verifyOut.contains("Firma correcta")
                (goodSig) shouldBe true
            }
        } finally {
            // 6. Limpiar keyring temporal.
            workDir.deleteRecursively()
        }
    }
}
