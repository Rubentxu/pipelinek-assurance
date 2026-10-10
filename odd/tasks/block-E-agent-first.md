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

## Estado (observado 2026-10-10, SHA `125304b`)

**Pendiente de A–D.** Sin A, B, C, D cerrados, los artifacts
que E debe navegar son ficticios.

## Sub-tareas

### E1 — CLI real (en-repo, depende de A–D)

- [ ] `assure capabilities`, `assure providers`,
      `assure snapshot inspect`, `assure suite inspect`,
      `assure report`, `assure findings`, `assure explain`,
      `assure evidence path`, `assure reproduce`, `assure diff`,
      `assure baseline inspect`, `assure next`.
- [ ] Cada recurso accionable devuelve enlaces/comandos
      válidos derivados del capability registry.
- [ ] `explain` muestra counterexample persistido.
- [ ] `evidence path` recorre referencias reales.

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
