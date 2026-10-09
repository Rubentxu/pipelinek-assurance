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

### Recibo M2..M11 — cierre del primer ciclo
- 14 commits de M2..M11, todos verificados con `clean check` (342 tests, 0 failures, 42–54s).
- SHA final del ciclo: `7b14548` (HEAD en `main`).
- 5 lenses, 4 providers, 1 diff engine, 2 SDK Steps, 1 ServiceLoader contributor.
- Bloqueadores externos (no en este repo): export real de Chronos, export real de CogniCode, collector OTel en vivo, host PipelineK con SDK para `install.sh` end-to-end. El código del plugin está completo y consumible; lo que falta es el entorno donde correrlo.

[Unreleased]: # (cambios cerrados pero sin "release" formal todavía)
