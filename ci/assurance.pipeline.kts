/**
 * ci/assurance.pipel.kts — Local CI para pipelinek-assurance.
 *
 * Ejecutado por PipelineK 0.48.0-rc2 (no GitHub Actions).
 * El usuario invoca con:
 *
 *   pipelinek run ci/assurance.pipel.kts
 *
 * Stages:
 *   1. `Check`     — gradle clean check (compilación + tests + fitness)
 *   2. `Perf`      — budget de performance
 *   3. `Receipt`   — recibo de tests firmado por SHA
 *   4. `SBOM`      — CycloneDX SBOM del plugin
 *   5. `Sign`      — firma GPG de artefactos (modo permisivo)
 *   6. `Verify`    — verifica firmas en modo estricto
 *
 * El stage final publica el resultado via exit 0/1.
 * Los stages corren en orden; cualquier fallo aborta.
 */
pipeline {
    stages {
        stage("Check") {
            sh("./gradlew --no-daemon clean check")
        }
        stage("Perf") {
            sh("./tools/measure-performance.sh --budget 90")
        }
        stage("Receipt") {
            sh("python3 tools/collect-test-receipt.py --skip-build")
        }
        stage("SBOM") {
            sh("./gradlew --no-daemon :pipelinek-assurance-plugin:cyclonedxBom")
        }
        stage("Sign") {
            sh("./tools/sign-release.sh build/dist")
        }
        stage("Verify") {
            sh("./tools/verify-signatures.sh build/dist")
        }
    }
}
