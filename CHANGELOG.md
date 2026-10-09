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

### M3 — `assurance.check` external PipelineK Step (estructura)
- Módulo `pipelinek-assurance-plugin` creado.
- `AssuranceCheckStep` con `Input/Output/EnforcementMode/CompletenessPolicy/StepOutcome/Fingerprint`.
- 268 tests verdes. **Integración con el SDK de PipelineK PENDIENTE** (no hay acceso al SDK en este repo).

### M4 — Baseline / diff / ratchets (closed)
- `DiffEngine` con `KnownViolation`, `FindingId`, `DiffEntry`, `DiffState` (NEW/EXISTING/RESOLVED/REGRESSED/CHANGED).
- M-B01 muerto: NEW no se clasifica EXISTING.
- 276 tests verdes.

### M5 — Detekt and test evidence providers (closed)
- `DetektSarifProvider` con SARIF 2.1.0 codec (bajo codec + digest).
- `JUnitXmlProvider` con parser XXE-safe.
- `TestTopologyLens` con `Capability=("test.topology"|"test.results")`, `Predicate="junit.testcase"`, deduplicación.
- 322 tests verdes.

### M6 — Chronos export seam (closed en estructura)
- `ChronosArtifactProvider` con `windowToken` protocol (H2).
- **Export real de Chronos PENDIENTE** (no hay acceso a Chronos).

### M7 — `assurance.verify` body Step (closed en estructura)
- `ObservedArchitectureLens` para `runtime.invocation-chain`.
- `AssuranceVerifyStep` con matriz body × assurance.
- M-P01 y M-P02 certificados.
- 333 tests verdes. **Integración con el SDK de PipelineK PENDIENTE.**

### M8 — OTel correlation + ObservabilityLens (closed en estructura)
- `OtelArtifactProvider` con namespaces `OTelTraceId` / `OTelSpanId` (AAT-13).
- M-O01 muerto: span sin trace reporta gap.

### M9 — JUnit Platform/Kotest adapters + agent CLI (closed)
- `MultiRunnerAssertions` con mapeo `AssertionResult` → `AssertionError` / `TestAbortedException`.

### M10 — Advanced lenses and self-hosted release assurance
- `ConnascenceLens` (forma del output, sin algoritmos V1).
- `SolidLens` con DIP determinista, ISP heurística, SRP/OCP placeholder.
- `ConsistencyLens` Declared/Static vs Observed.
- `SeamLens` diferido a M11.

### M11 — Production readiness (parcial)
- ✅ CI workflow (`clean check` en GitHub Actions).
- ✅ Tipos externos (`OTelTraceId`, `ChronosInvocationId`, etc.) como types distintos (AAT-13).
- ❌ SBOM, checksums firmados, provenance — pendientes de la integración final.
- ❌ Performance budgets medidos — sin fixtures reales de Chronos/OTel.
- ❌ Distribución instalable y matriz de compatibilidad con PipelineK SDK — pendiente.

[Unreleased]: # (cambios cerrados pero sin "release" formal todavía)
