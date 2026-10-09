# Recibo R2 — Baselines, ratchets y evidencia de tests

**Bloque:** R2 — M4 + M5.
**Release propuesta:** `v0.3.0-alpha.1`.
**Fecha:** 2026-10-10.
**SHA integrado:** `8b996c8`.

## Trabajo ejecutado

### R2.1 — Baseline semántica
- `DiffEngine` con `FindingId(assertionId, fingerprint: Digest)`,
  `KnownViolation`, `DiffEntry`, `DiffState`
  (`NEW`/`EXISTING`/`RESOLVED`/`REGRESSED`/`CHANGED`).
- Stable finding id: `(assertionId, fingerprint)`. No usa número
  de línea como única identidad.
- Exenciones con `owner`/`rationale`/`expiry`. Idempotente: aplicar
  el mismo baseline dos veces no cambia el resultado.
- M-B01 certificado con redundancia 2 (M4DiffLawsTest).

### R2.2 — `assurance.diff`
- Step externo `assurance.diff` consumido por el plugin vía
  `StepDefinitionContributor` (ServiceLoader, M3).
- Lógica del DiffEngine probada por `DiffEngineTest` (5 estados,
  idempotencia, expiración de excepciones).

### R2.3 — Adapters de calidad
- `DetektSarifProvider` sobre SARIF 2.1.0 (M5). Malformado →
  `ArtifactDecodeException`, no excepción opaca (UAT-026).
- `JUnitXmlProvider` con parser XXE-safe. Malformado → idem
  (UAT-027).
- **`JacocoCoverageProvider`** (R2.3 nuevo): parsea XML JaCoCo,
  produce `RawEvidenceItem` por línea con payload
  `{line, covered, ci, mi}`. 4 tests verdes.
- **`PitestMutationProvider`** (R2.3 nuevo): parsea XML Pitest,
  produce `RawEvidenceItem` por mutante con payload
  `{sourceFile, mutatedClass, lineNumber, status, detected}`.
  4 tests verdes.
- Source locations estables entre providers (UAT-028).

### R2.4 — `TestTopologyLens`
- `Capability=("test.topology"|"test.results")`,
  `Predicate="junit.testcase"`, deduplicación por
  `(classname, name)`. Status: passed, failed, errored/errors,
  skipped.

### R2.5 — Ratchets prácticos
- Deuda antigua conservada: `DiffState.Existing` con
  `owner`/`rationale`/`expiry` de la baseline.
- Regresión nueva bloqueada: `DiffState.New` sin baseline entry.
- Violación resuelta: `DiffState.Resolved` cuando estaba en
  baseline y ya no aparece.
- Excepción caducada: el día `expiry` ya pasó, vuelve a
  contarse como `New`.
- Mutante M-B01 muerto por 3 tests de M4DiffLawsTest (no por
  uno solo).
- Señal heurística separada: `M-H01_signal_cannot_be_deterministic`
  verifica que un `Signal` con authority `DeterministicAdapter`
  falle en la assertion que exige determinista.

## UAT cubiertos en este repo (no requieren host externo)

- **UAT-010 (Baseline freeze):** verificado por
  `DiffEngineTest.diff_clasifica_NEW_EXISTING_RESOLVED`.
- **UAT-011 (Baseline expiry):** verificado por
  `DiffEngineTest.expired_exception_es_NEW`.
- **UAT-019 (Mutation strength):** el catálogo ya tiene
  M-B01..M-10-04, todos certificados con redundancia ≥ 2.
- **UAT-026, UAT-027, UAT-028** (registrados en M5, cubiertos por
  los tests de los providers).

## UAT pendientes por bloqueador externo

- UAT-019 ejecución E2E con un mutante real generado por Pitest:
  requiere ejecutar Pitest en un host concreto (no aplicable
  aquí como "mutante vivo"). El catálogo cubre la propiedad con
  `tools/certify_mutants.py`.

## Acceptance

- UAT-010, UAT-011, UAT-019: lógica cubierta.
- UAT-026, UAT-027, UAT-028: tests verdes.
- AAT-18: verde (`suppression exige stable finding id`).
- AAT-19: verde (`Signal` no satisface assertion que exige
  determinista).
- M-B01: certificado con redundancia 2.
- Regresión de M-H01: cubierta por la matriz de authorities.

## Build

- `./gradlew --no-daemon clean check` → `BUILD SUCCESSFUL in 34s`.
- **360 tests, 0 failures, 0 skipped** (8 más que R0: 4 Jacoco +
  4 Pitest).
- Hash de `JacocoCoverageProvider` y `PitestMutationProvider` ya
  integrados.

## Riesgos y deuda

- Cobertura y mutation providers sintéticos: el `ci`/`mi` de JaCoCo
  y el `status` de Pitest se leen del wire format XML. La
  verificación end-to-end con un build real de JaCoCo y un run
  real de Pitest queda para M11 con host dedicado.
- UAT-019 con mutantes vivos: Pitest debería correr sobre el
  propio repo, no sobre fixtures. Pendiente del host con Java
  toolchain.
