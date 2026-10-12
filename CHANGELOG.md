# CHANGELOG

Historial de hitos del proyecto, con foco en lo que cambió
funcionalmente. El detalle fino (SHA, conteos, decisiones) vive
en los commits y en `ROADMAP.md`.

El formato sigue [Keep a Changelog](https://keepachangelog.com/),
adaptado al hecho de que los hitos del ROADMAP **son** las unidades
de cambio: el documento es un mapa del proyecto, no un release log.

## [Unreleased]

### M0 — Compatibility fortress (closed)
- ADTs de Evidence/Authority/Completeness con invariantes en `init`.
- Codec CBOR/JSON con bounded decoding (digest SHA-256 real).
- 8 AAT verdes (AAT-1, AAT-2, AAT-6, AAT-8, AAT-9, AAT-16, AAT-17, AAT-20).
- 15 mutantes certificados como muertos.

### M1 — First useful static assurance vertical (closed)
- `HexagonalArchitectureLens` y assertions `noDependency` / `acyclic`.
- CLI `assure report | explain | evidence path`.
- Self-model sintético en `08-testing/self-model.graph`.
- 236 tests verdes, 0 failures.

### M2 — CogniCode evidence integration (closed)
- SPI `EvidenceProvider` en `assurance-engine` con `descriptor` y `collect`.
- `CogniCodeArtifactProvider` que consume `assurance-evidence/v1`.
- `SyntheticEvidenceProvider` para differential proof.
- 258 tests verdes, +22 desde M1.

### M3 — `assurance.check` external PipelineK Step (closed)
- Módulo `pipelinek-assurance-plugin` con `AssuranceCheckStep` real contra `dev.rubentxu.pipeline.v2.domain.step.*`.
- `AssurancePluginContributor` declara `assurance.check` y `assurance.verify` via `StepDefinitionContributor` (ServiceLoader en `META-INF/services/...`).
- 268 tests verdes. Integración con el SDK cerrada (SDK consumido en `:pipelinek-assurance-plugin`).

### M4 — Baseline / diff / ratchets (closed)
- `DiffEngine` con `KnownViolation`, `FindingId`, `DiffEntry`, `DiffState` (NEW/EXISTING/RESOLVED/REGRESSED/CHANGED).
- M-B01 muerto: NEW no se clasifica EXISTING.
- 276 tests verdes.

### M5 — Detekt and test evidence providers (closed)
- `DetektSarifProvider` con SARIF 2.1.0 codec (bajo codec + digest).
- `JUnitXmlProvider` con parser XXE-safe.
- `TestTopologyLens` con `Capability=("test.topology"|"test.results")`, `Predicate="junit.testcase"`, deduplicación.
- 322 tests verdes.

### M6 — Chronos export seam (estructura)
- `ChronosArtifactProvider` con `windowToken` protocol (H2) y sealed `EvidenceCollectionResult` (M2) consumido.
- **Export real de Chronos PENDIENTE** (no hay acceso a Chronos en este repo).

### M7 — `assurance.verify` body Step (closed)
- `ObservedArchitectureLens` para `runtime.invocation-chain`.
- `AssuranceVerifyStep` con matriz body × assurance.
- `AssuranceVerifyStepAdapter` integrado con el SDK de PipelineK.
- M-P01 y M-P02 certificados.
- 333 tests verdes.

### M8 — OTel correlation + ObservabilityLens (closed en estructura)
- `OtelArtifactProvider` con namespaces `OTelTraceId` / `OTelSpanId` (AAT-13).
- M-O01 muerto: span sin trace reporta gap.

### M9 — JUnit Platform/Kotest adapters + agent CLI (closed)
- `MultiRunnerAssertions` con mapeo `AssertionResult` → `AssertionError` / `TestAbortedException`.

### M10 — Advanced lenses and self-hosted release assurance (closed)
- `ConnascenceLens` (forma del output, sin algoritmos V1).
- `SolidLens` con DIP determinista, ISP heurística, SRP/OCP placeholder.
- `ConsistencyLens` Declared/Static vs Observed.
- `SeamLens` (seam = adapter/infra con dependiente interno; authority heurística).

### M11 — Production readiness (closed en estructura)
- ✅ CI workflow (`clean check` en GitHub Actions).
- ✅ Tipos externos (`OTelTraceId`, `ChronosInvocationId`, etc.) como types distintos (AAT-13).
- ✅ Scripts: `install.sh`, `tools/generate-sbom.sh`, `tools/measure-performance.sh`.
- ✅ Baseline: 342 tests en 42s (`build/perf-baseline.txt`).
- ✅ SBOM CycloneDX 1.5 mínimo desde `build.gradle.kts` del plugin (`build/sbom.json`).
- ❌ Checksums firmados, provenance — pendientes de la integración final.
- ❌ Distribución instalable y matriz de compatibilidad con PipelineK SDK — pendiente (requiere host con SDK).

### Bloque G — End-to-end plugin admission + sample pipeline (closed en estructura)

#### G1..G5 (closed)
- G1–G5 cierran el contrato del plugin: el `StepDefinitionContributor` se admite, las capabilities y el replay policy son correctos, el `MockSdkHost` ejercita la lógica del handler con contrato verificable.
- 729 tests verdes, 0 failures acumulado al cierre de G5.

#### G6.1 — Build-time manifest generation (closed)
- `PluginManifestBuilder.kt` genera `META-INF/pipelinek/plugin-manifest.json` a build time vía `JavaExec`, embebido en el JAR por `processResources`.
- `StepRegistration(definition, provider)` cableado con `StepProviderMetadata.create(...)` (constructor `internal` documentado).
- Verificado e2e: `pipelinek run` admite el plugin, descubre los steps en SDK 0.48.0 y 0.51.0-rc1.
- AAT-3 (`G3_V47`) allowlist extendido a dos archivos: `AssurancePluginContributor.kt` + `PluginManifestBuilder.kt` (con comentario que justifica la exención).
- `docs/G6-FINDINGS.md` (178 líneas) documenta el hallazgo + comandos para reproducir + la limitación del script-context PipelineK compiler.

#### G6.2 — Typed DSL extension + honest SDK gap (closed en estructura)
- `AssuranceCheckDslExtension.kt` expone `assuranceCheck(scope, stepKey, encodedInput)` y la sobrecarga `assuranceCheck(scope, name, input: Input)` que envuelve explícitamente las inline classes del SDK (`PluginStepId`, `EncodedStepValue`) que el script-context compiler no auto-wrapea.
- `G6E2ETest.kt` (V55–V59) cubre el contrato estructural de la extensión: compile-time reference, JSON round-trip, payload shape, inline-class wrapping, manifest embedded.
- **Gap honesto**: el runtime del SDK 0.48.0 y 0.51.0-rc1 impone un pre-check de capabilities que aborta la ejecución del step con `missing required capabilities for step 'assurance.check': assurance.provider, assurance.assertions` — el handler **nunca corre**. No existe la SPI `RuntimeCapabilityContributor` en el bytecode del SDK. El plugin declara las capabilities en el manifest (correcto) pero el host no las publica. **Esto es un gap upstream, no un workaround nuestro.**
- No se suaviza `requiredCapabilities` (rompe `G3_V36`); no se inyecta un `RuntimeCapabilityContributor` reflectivo (rompe AAT-3).
- La corrección de la lógica del handler queda cubierta por `AssuranceCheckEndToEndTest` + `G3AssuranceCheckStepTest` vía `MockSdkHost` (que sí inyecta las capabilities).
- 203/0/0 verde en ambos SDKs.

### Recibo M2..M11 — cierre del primer ciclo
- 14 commits de M2..M11, todos verificados con `clean check` (342 tests, 0 failures, 42–54s).
- SHA final del ciclo: `7b14548` (HEAD en `main`).
- 5 lenses, 4 providers, 1 diff engine, 2 SDK Steps, 1 ServiceLoader contributor.
- Bloqueadores externos (no en este repo): export real de Chronos, export real de CogniCode, collector OTel en vivo, host PipelineK con SDK para `install.sh` end-to-end. El código del plugin está completo y consumible; lo que falta es el entorno donde correrlo.

[Unreleased]: # (cambios cerrados pero sin "release" formal todavía)
