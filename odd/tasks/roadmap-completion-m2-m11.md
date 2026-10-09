---
name: roadmap-completion-m2-m11
description: Feature document tracking M2..M11 of ROADMAP.md. Source of truth for work-unit commits per milestone.
---

# Roadmap Completion: M2..M11

**Goal:** complete the 9 remaining milestones (M2 through M11) per `ROADMAP.md` authority.

## Current state (verified 2026-10-09)

- **W0** (bootstrap): closed
- **M0** (Compatibility fortress): closed — 174 tests, 8 AAT verdes, 15 mutantes certificados
- **M1** (Hexagonal vertical): closed — 236 tests verdes, CLI funcional, self-model en disco
- **M2..M11**: pending

## Constraints (from ROADMAP)

- Hitos son ciclos cerrados con precondición dura.
- No se crean módulos físicos nuevos sin frontera real.
- AAT, mutantes, UAT registrados en catálogo antes de implementarse.
- Self-hosting obligatorio en cada hito que cambie el IR.
- M5 y M6 deben registrar IDs de UAT que hoy no tienen.
- M11 cierra con un solo SHA y una suite completa verde.

## External dependencies (blockers)

| Hito | Repo externo | Estado |
|---|---|---|
| M2 | CogniCode (export `assurance-evidence/v1`) | **BLOQUEADO** sin acceso a CogniCode |
| M3 | PipelineK (SDK) | **BLOQUEADO** sin acceso al SDK de PipelineK |
| M6 | Chronos (export `assurance-runtime-evidence/v1`) | **BLOQUEADO** sin acceso a Chronos |
| M8 | OTel (refs tipadas) | **BLOQUEADO** sin OTel collector configurado |

**Estrategia:** donde el hito requiere export externo, se construye el adapter/consumidor con fixtures sintéticos del shape esperado, y se documenta que la verificación con export real queda pendiente del repo externo. Coherente con M1 (fixture sintético antes que evidencia real).

---

## M2 — CogniCode evidence integration

**Valor:** probar repos reales sin duplicar el analyzer.

**Estado:** pendiente. Precondición M1 ✅.

### Tasks

- [x] **M2-T1** Definir SPI `EvidenceProvider` en `assurance-engine`. Cumplir AAT-6 (no retorna `AssertionResult`). SHA: `ecd513e` (`feat(engine): M2 EvidenceProvider SPI con descriptor y collect`).
- [x] **M2-T1.5** Hacer AAT-6 estructural: el SPI no debe permitir por signatura un método que retorne `AssertionResult`. SHA: `2671cf0` (`test(fitness): AAT-6 estructural sobre EvidenceProvider SPI`).
- [x] **M2-T2** Definir shape del export `assurance-evidence/v1` (DTOs) en `assurance-providers`. Forma intermedia con bounded decoding. SHA: `023f666`.
- [x] **M2-T3** Codec `CogniCodeEvidenceExportCodec` (CBOR/JSON) del export. Verificar digest. Roundtrip con golden. SHA: `023f666`.
- [x] **M2-T4** `CogniCodeArtifactProvider` que consume el export y produce `EvidenceCollectionResult` con `RawEvidenceItem`/`RawEvidenceGap`. SHA: `023f666`.
- [x] **M2-T5** Provider sintético equivalente (`SyntheticEvidenceProvider`) en `assurance-testkit`. Differential proof con capability-based parity. SHA: `2bd529f`.
- [x] **M2-T6** Self-hosting S2: extractor in-test del propio repo produce un export, el `CogniCodeArtifactProvider` lo consume, la lens proyecta. SHA: ver siguiente commit.
- [ ] **M2-T7** Recibo en `ROADMAP.md` con SHA y conteo de tests (258 verde, +22 desde M1).
- [ ] **M2-T8** (Opcional M3) Normalizer genérico `EvidenceCollectionResult → EvidenceSnapshot`. Hoy se hace in-test; cuando haya un servicio de aplicación (plugin en M3), el normalizador vivirá allí.
- [ ] **M2-T9** (Difiere a M3) Integración con export real de CogniCode. El extractor in-test cubre la pipeline; el export real requiere WP-CG-002.
- [ ] **M2-T10** (Pendiente) Catálogo: registrar M2 en `08-testing/MUTATION_CATALOG.md` si hay mutantes nuevos. M-E01 y M-E02 se re-ejecutan en M3 sobre el path completo con provider.

### Módulos nuevos

- `assurance-providers` (M2): adapters de evidence providers. Depende de `assurance-domain`, `assurance-engine`, `assurance-artifact`. NO depende de PipelineK. Existe por la frontera "produce EvidenceSnapshot desde artefactos externos". Justificación contra "no anadir modulos hasta que una frontera real lo exija":
  - AAT-2 prohíbe implementaciones de provider en `assurance-engine`.
  - AAT-4 prohíbe imports de internals de CogniCode en `assurance-artifact`.
  - `assure-cli` es Infrastructure, no adapters.
  - M5 preve añadir más providers (Detekt SARIF, JUnit XML) al mismo módulo, evidencia de cohesión.
- `pipelinek-assurance-plugin` (M3): único módulo que puede depender del SDK de PipelineK. AAT-3 + AAT-10 + AAT-11.

### Acceptance criteria

- 245+ tests verdes, 0 failures, 0 skipped
- Differential proof: misma projection, mismo digest, desde synthetic y CogniCode-shape
- Self-hosting S2: `assure report` con snapshot real del repo
- AAT-6 deja de ser vacuo: existe al menos un `EvidenceProvider` en el repo y la ley verifica que NO retorna `AssertionResult`

---

## Tracking (resumen final)

| Hito | Estado | Tests (al cerrar) | SHA de cierre |
|---|---|---|---|
| W0  | closed | — | `74b89d0` (ya en historial) |
| M0  | closed | 174 → 169 | ya cerrado (historial) |
| M1  | closed | 236 | ya cerrado (historial) |
| M2  | closed | 258 | `023f666`, `2bd529f`, `ee536a6` |
| M3  | estructura | 268 | `8c88f16` (integración SDK pendiente) |
| M4  | closed | 276 | `53385b0` |
| M5  | closed | 322 | `9ae04fd` |
| M6  | estructura | 326 | `7cb0ee4` (Chronos real pendiente) |
| M7  | estructura | 333 | `43aa106` (integración SDK pendiente) |
| M8  | estructura | 337 | (commit al final de M11) |
| M9  | closed | 283 | `ce2866f` |
| M10 | parcial   | 337 | (Connascence/Solid/Consistency; Seam diferido) |
| M11 | parcial   | 337 | CI verde; SBOM, performance budgets, distribución pendiente |

## Bloqueadores externos (no cerrables sin acceso a los repos)

| Bloqueador | Hito | Plan |
|---|---|---|
| SDK de PipelineK (JAR consumible) | M3, M7 | Esperar publicación; mientras tanto, contratos de los Steps documentados. |
| Export real de CogniCode (`assurance-evidence/v1`) | M2 | El adapter existe; el export sintético cubre la pipeline. |
| Export real de Chronos (`assurance-runtime-evidence/v1`) | M6, M7 | El adapter existe; la verificación end-to-end con run real se difiere. |
| Collector OTel con datos | M8 | El adapter existe; las pruebas usan shapes sintéticos. |
| Runner de PipelineK instalado | M3, M7, M11 | UAT-008, UAT-009, UAT-024 y matriz de compatibilidad requieren una distribución real. |

## Lo que se entregó en este commit (M2..M11)

- 5 módulos: `assurance-domain`, `assurance-engine`, `assurance-artifact`, `assurance-testkit`, `assurance-providers`, `assure-cli`, `pipelinek-assurance-plugin`.
- SPI `EvidenceProvider` con `descriptor`, `collect`, `EvidenceRequest`, `EvidenceCollectionResult` (raw), `RawEvidenceItem`, `RawEvidenceGap`.
- 4 providers: CogniCode (export evidence v1), Detekt SARIF, JUnit XML, Chronos (runtime evidence v1), OTel.
- 5 lenses: `HexagonalArchitectureLens`, `ObservedArchitectureLens`, `ConnascenceLens`, `SolidLens`, `ConsistencyLens`, `TestTopologyLens`.
- `DiffEngine` con `FindingId`, `KnownViolation`, `DiffEntry`, `DiffState` (M4).
- `MultiRunnerAssertions` para Kotest + JUnit (M9).
- 2 Steps: `AssuranceCheckStep`, `AssuranceVerifyStep` con matrices de outcome (M3, M7).
- 22 fitness tests verificando AAT-1..AAT-20.
- 337 tests, 0 failures, 0 skipped.
- CI workflow (`clean check` en GitHub Actions).
- `CHANGELOG.md` con el historial de hitos.

## Lo que queda declarado como pendiente (no como deuda técnica oculta)

- Integración con SDK de PipelineK (M3, M7, parte de M11): sin acceso al SDK, los Steps tienen la forma del contrato pero no la integración real.
- Export real de CogniCode/Chronos/OTel: los adapters existen; el export real cubre la pipeline pero no la verificación end-to-end.
- Performance budgets medidos: sin fixtures reales, los budgets son placeholders.
- SBOM, checksums firmados, provenance: pendiente de la distribución final.
- SeamLens de M10: las demás lenses de M10 están hechas.
