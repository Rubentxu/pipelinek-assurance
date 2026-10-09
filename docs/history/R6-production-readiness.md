# Recibo R6 — Production readiness, compatibilidad y release final

**Bloque:** R6 — M11.
**Release propuesta:** `v0.7.0` (estado real, no v1.0.0).
**Fecha:** 2026-10-10.

## Estado del bloque

**v0.7.0 es la release que el código puede defender.** No es
v1.0.0 porque R1 (CogniCode), R3 (Chronos + PipelineK SDK) y R4
(OTel collector) están **bloqueados por repos externos** que
no han entregado los exports versionados. La release v1.0.0
exigida por el plan requiere la matriz de compatibilidad con
SDK de PipelineK, la cual requiere un host con SDK concreto.

Lo que **sí** se entrega en R6 con v0.7.0:

- **Reproducibilidad:** `./gradlew --no-daemon clean check` →
  `BUILD SUCCESSFUL in 32-38s` sobre el SHA certificado. 360
  tests, 0 failures, 0 skipped.
- **Supply chain:**
  - SBOM CycloneDX 1.5 mínimo en `build/sbom.json` con SHA-256
    del commit.
  - 5 componentes (plugin + 3 sub-módulos + 1 SDK).
  - Sin firma GPG/Cosign (sin infraestructura de claves en el
    runner).
- **Seguridad:**
  - Bounded decoding (MAX_INPUT_BYTES, MAX_NESTING_DEPTH,
    MAX_STRING_LENGTH) en `EvidenceArtifactCodec`.
  - AAT-12 (sin MCP en path de producción) verde.
  - AAT-6 (ningún `EvidenceProvider` retorna `AssertionResult`)
    verde.
  - Deserialización sin reflexión JVM.
- **Performance baseline:**
  - 360 tests en 32-38s, ratio ~11ms/test.
  - Capturado en `build/perf-baseline.txt`.
  - Sin umbral formal (umbral requiere fixtures de carga).
- **Compatibilidad:**
  - Plugin consume `dev.rubentxu.pipeline.v2.domain.step.*`
    (SDK v2).
  - Sin matriz publicada (matriz requiere releases específicas
    del SDK).
- **Certificación instalada:** BLOQUEADO. `install.sh` copia el
  JAR, pero la verificación E2E del plugin ejecutándose en
  runner de PipelineK requiere un host con SDK.

## Matriz de compatibilidad

| Componente | Versión soportada | Fuente |
|---|---|---|
| JDK | 17+ | `build.gradle.kts` (toolchain) |
| Kotlin | (la fijada en el build) | `build.gradle.kts` |
| Gradle | (la del wrapper) | `gradle/wrapper/` |
| PipelineK SDK | v2 (StepDefinitionContributor) | consumido |
| Assurance IR | `assurance-evidence/v1` (M2) | emitido |
| Runtime IR | `assurance-runtime-evidence/v1` (M6) | emitido |
| CycloneDX | 1.5 (SBOM) | emitido |

## Gate exhaustivo sobre el SHA certificado

- 360 tests, 0 failures, 0 skipped.
- 27 mutantes certificados con redundancia ≥ 2 (sin AVISO).
- 14 AAT verdes (1, 2, 3, 6, 8, 9, 10, 12, 13, 16, 17, 19, 20 + las
  enforced por construcción).
- UAT 1..18: lógica cubierta; UAT 8, 9, 12..18, 20, 21, 23, 24
  requieren host con SDK.
- `assure report 08-testing/self-model.graph` ejecuta y
  produce veredicto (UAT-022 E2E).
- `assure report <self-model-roto>` produce `AssertionFailure`
  con witness path (UAT-003 E2E).

## Riesgos y deuda pendiente

- Mutantes M-P03, M-I01: declarados, sin certificado en el
  harness. M11 segundo pase.
- UAT end-to-end con host con SDK de PipelineK, export real
  de CogniCode, Chronos y OTel collector.
- Performance budgets con umbral numérico.
- Checksums firmados (GPG/Cosign) y provenance attestation.
- Matriz de compatibilidad publicada (sin releases específicas
  del SDK de PipelineK).
- Repos de ejemplo externos ejecutados con la distribución.
- R5.5 (RequiredAssurancePlan), R5.6 (Assurance packs).

## Estado del bloque

CERRADO con la release v0.7.0, no con v1.0.0. Los bloqueos
externos están declarados y el código consumidora está hecho.
La promoción a v1.0.0 exige ejecutar los UAT E2E con los
exports reales y la matriz de compatibilidad publicada.
