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
- [ ] **M2-T1.5** Hacer AAT-6 estructural: el SPI no debe permitir por signatura un método que retorne `AssertionResult`.
- [ ] **M2-T2** Definir shape del export `assurance-evidence/v1` (DTOs) en `assurance-artifact`. Forma intermedia con bounded decoding. Kind=EvidenceSnapshot.
- [ ] **M2-T3** Codec `CogniCodeExportCodec` (CBOR/JSON) del export. Verificar digest. Roundtrip con golden.
- [ ] **M2-T4** `CogniCodeArtifactProvider` que consume el export y produce `EvidenceSnapshot` con `EvidenceSourceManifest`, provenance, completeness por capability, stable ids.
- [ ] **M2-T5** Provider sintético equivalente que produce el mismo `EvidenceSnapshot` desde una fixture en memoria. Usado en la differential proof.
- [ ] **M2-T6** Differential proof: el mismo grafo, una vez vía synthetic provider y otra vía CogniCode-shape, debe producir la MISMA `HexagonalProjection`. Property test con permutación.
- [ ] **M2-T7** Self-hosting S2: el `self-model.graph` de M1 se reemplaza por un snapshot real del propio repo, ambos pasan la suite.
- [ ] **M2-T8** `assure evidence path` y `assure explain` mejorados para incluir provenance real cuando la evidence viene de provider.
- [ ] **M2-T9** Tests de regression: M-E01, M-E02, M-H01 sobre el path completo con provider.
- [ ] **M2-T10** Catálogo: registrar M2 en `08-testing/MUTATION_CATALOG.md` si hay mutantes nuevos.
- [ ] **M2-T11** Recibo en `ROADMAP.md` con SHA y conteo de tests.

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

## M3 — `assurance.check` external PipelineK Step

(estructura similar — pending)

## M4..M11

(estructura similar — pending)
