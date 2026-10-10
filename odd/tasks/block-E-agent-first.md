---
name: block-E-agent-first
description: Feature document for Bloque E — experiencia agent-first, packs y self-hosting. Source of truth for v0.13.0-rc1.
---

# Bloque E — Experiencia agent-first, packs y self-hosting

**Goal:** una persona o un agente puede seleccionar suites,
ejecutar assurance y navegar desde un fallo hasta sus
evidencias, sin aprender de antemano la estructura interna.

**Release objetivo:** `v0.13.0-rc1`

**Precondición:** D cerrado.

## Estado (observado 2026-10-10, post-E1)

**E1 cerrado (en-repo, parcial)**: 3 nuevos comandos en el
CLI + 7 tests:
- `assure capabilities` lista los comandos del registry.
- `assure providers` documenta BuiltinLens + BuiltinAssertion.
- `assure next <veredicto>` sugiere la siguiente acción.

Los comandos restantes del E1 (`snapshot inspect`, `suite
inspect`, `findings`, `reproduce`, `diff`, `baseline
inspect`) requieren artefactos del SDK real o de producers
externos (B1, C1, D1) y quedan en-repo pero pendientes de
los productores.

**E2 (en-repo, ya en gran parte)**: `assurancePack` DSL y
`RequiredAssurancePlan` ya están implementados y probados
(AssuranceDslTest, RequiredAssurancePlanTest).

**E3 (en-repo, parcial)**: JUnit y Kotest se usan
paralelamente; la paridad de digests canónicos entre
runners es una verificación pendiente.

**E4 BLOQUEADO por C1** (CogniCode real para self-hosting).

## Sub-tareas

### E1 — CLI real (en-repo, depende de A–D)

- [x] `assure capabilities` (lista comandos del registry).
- [x] `assure providers` (documenta BuiltinLens +
      BuiltinAssertion).
- [x] `assure report`, `assure explain`, `assure evidence path`
      (M1 + M5; ya probados).
- [x] `assure next` (sugiere la siguiente acción).
- [ ] `assure snapshot inspect`, `assure suite inspect`,
      `assure findings`, `assure reproduce`, `assure diff`,
      `assure baseline inspect` — requieren artefactos del
      SDK real (B1) o producers externos (C1, D1). Quedan
      pendientes en-repo; se promoverán cuando los productores
      estén disponibles.
- [x] Cada recurso accionable devuelve enlaces/comandos
      válidos derivados del capability registry.
- [x] `explain` muestra counterexample persistido.
- [x] `evidence path` recorre referencias reales.

### E2 — Kotlin DSL y packs (en-repo)

- [ ] Fachadas idiomáticas para autoría de suites y packs.
- [ ] IR cerrado, serializable, versionado.
- [ ] Conectar `AssurancePack.Rule` directamente con
      `RequiredAssurancePlan`, eliminando deducciones frágiles.
- [ ] Selección por ownership, capabilities, cambios, findings.

### E3 — Runners (en-repo)

- [ ] JUnit Platform y Kotest con paridad de report.
- [ ] Comparar digests canónicos entre tres rutas:
      evaluación pura, JUnit, Kotest, Step PipelineK.

### E4 — Self-hosting real (BLOQUEADO por C1)

- [ ] Assurance contra su propio repo con CogniCode real.
- [ ] Caso negativo temporal (dependencia prohibida) que
      certifique el fallo, después restaurar.
- [ ] El self-hosting consume evidencia derivada del código
      actual, no solo `08-testing/self-model.graph`.

## Acceptance

- Un agente descubre comandos y encadena acciones sin
  conocimiento específico del modelo.
- Todas las acciones anunciadas son ejecutables.
- `explain` y `reproduce` usan artifacts reales.
- Paridad de veredictos y digests entre runners.
- Packs Mandatory y Touched seleccionan suites correctas.
- Un rename no desactiva suites por accidente.
- Self-hosting positivo y negativo en CI.
- Sin daemon ni transporte MCP para el gate.

**UAT:** 020, 021, 022, 023.
**AAT:** 1, 2, 7, 12, 15, 16, 20.

## STOP

CLI declara capacidades que no funcionan, o la selección de
suites puede omitir Mandatory por convención de nombres.

## Cierre (objetivo)

`v0.13.0-rc1` con distribución de CLI, ejemplos, packs, tests
de runner y self-hosting certificados.
