/**
 * ci/assurance.pipeline.kts — Local CI para pipelinek-assurance.
 *
 * Ejecutado por PipelineK 0.48.0 (no GitHub Actions).
 * El usuario invoca con:
 *
 *   pipelinek run ci/assurance.pipeline.kts
 *
 * Stages:
 *   1. `Check`        — gradle clean check (compilación + tests + fitness)
 *   2. `Perf`         — budget de performance
 *   3. `Receipt`      — recibo de tests firmado por SHA
 *   4. `SBOM`         — CycloneDX SBOM del plugin
 *   5. `SecurityScan` — osv-scanner sobre lockfile + SBOM (skip si
 *                       la herramienta no esta instalada)
 *   6. `Sign`         — firma GPG de artefactos (modo permisivo)
 *   7. `Verify`       — verifica firmas en modo estricto
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
        stage("SecurityScan") {
            // Self-adapt: si osv-scanner no esta instalado, skip con
            // un warning claro. Antes el stage reventaba con
            // "command not found" y abortaba el run entero en hosts
            // donde la herramienta no estaba en PATH (era la
            // mayoria). Un pipeline que no se puede correr sin
            // instalar dependencias opcionales no es un pipeline
            // local; es un pipeline asumiendo infraestructura.
            sh(
                "if command -v osv-scanner >/dev/null 2>&1; then " +
                    "  osv-scanner" +
                    "    --lockfile=settings-gradle.lockfile" +
                    "    --sbom=pipelinek-assurance-plugin/build/reports/bom.json" +
                    "    --format=markdown" +
                    "    --output=osv-report.md" +
                    "    --fail-on=high; " +
                    "else " +
                    "  echo '[SecurityScan] osv-scanner no instalado; skip.' >&2; " +
                    "  echo '[SecurityScan] Para activarlo: brew install osv-scanner' >&2; " +
                    "fi",
            )
        }
        stage("Sign") {
            sh("./tools/sign-release.sh build/dist")
        }
        stage("Verify") {
            sh("./tools/verify-signatures.sh build/dist")
        }
    }
}
