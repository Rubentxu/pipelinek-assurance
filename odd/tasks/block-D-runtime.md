---
name: block-D-runtime
description: Feature document for Bloque D — assurance runtime mediante Chronos, OTel y BodyContinuation. Source of truth for v0.12.0-rc1.
---

# Bloque D — Assurance runtime mediante Chronos, OTel y BodyContinuation

**Goal:** ejecutar un body de PipelineK y verificar
propiedades runtime con ventana de evidencia real,
preservando causalidad, cancelación y fallos.

**Release objetivo:** `v0.12.0-rc1`

**Precondición:** C cerrado. Contrato de BodyContinuation
certificado. Export Chronos real disponible.

## Estado (observado 2026-10-10, post-D5)

**D1, D2, D3 siguen BLOQUEADOS** por externo (Chronos, SDK real,
collector OTel real).

**D5 cerrado (en-repo, sin bloqueador)**: fallos, crash y
replay. 7 tests en `D5FailureModesTest`:
- Provider que lanza excepción → gap `Lost`, no aborta.
- Provider declara gap `Lost` → assertion cae a
  `Inconclusive`.
- `BodyContinuation` que lanza → `BodyOutcome.Failure`.
- `combine(body_failure, report_pass)` preserva el failure
  (no se oculta tras el pass de assurance).
- `Cancelled` no se confunde con `Failure` en el combine.
- Normalizer aborta cuando el producer miente →
  `OrchestrationResult.Failed` con motivo (no se publica
  report parcial).

## Sub-tareas

### D1 — Productor Chronos (BLOQUEADO)

- [ ] Coordinar implementación en `Rubentxu/chronos` del
      contrato `assurance-runtime-evidence/v1`.
- [ ] Session y window refs.
- [ ] Token durable de ventana.
- [ ] Apertura y sellado.
- [ ] Invocations, causal edges, correlaciones externas.
- [ ] Completitud, pérdidas y gaps.
- [ ] Export versionado y verificable.

### D2 — `assurance.verify` (BLOQUEADO por D1 + SDK real)

- [ ] Step body-owning real sobre SDK PipelineK.
- [ ] Secuencia: preparar ventana → ejecutar body → propagar
      cancelación → cerrar/sellar → exportar → evaluar → publicar.
- [ ] No capturar cancelaciones como fallos ordinarios.
- [ ] No iterar hijos fuera de BodyContinuation.

### D3 — Integración OTel (BLOQUEADO por collector real)

- [ ] Collector auténtico en entorno reproducible.
- [ ] Conservar TraceId, SpanId, ParentSpanId, etc.
- [ ] No colapsar identidades por igualdad de cadenas.

### D4 — Lenses runtime (en-repo, depende de D2/D3)

- [ ] Conectar `ObservedArchitectureLens` y `ConsistencyLens`
      con evidencia real.

### D5 — Fallos, crash y replay (en-repo)

- [x] Cancelación del ancestro (M_P02, ya en
      `AssuranceVerifyStepTest`).
- [x] Body fallido (M_P01, ya en `AssuranceVerifyStepTest`).
- [x] Fallo del observer: provider que lanza excepción →
      gap `Lost` (7 tests nuevos en `D5FailureModesTest`).
- [x] Pérdida de evidencia: provider declara gap `Lost` →
      assertion cae a `Inconclusive`.
- [x] Caída antes/después del sellado: handler que lanza →
      `BodyOutcome.Failure`; combine preserva el failure.
- [x] Interrupción al publicar: orchestrator devuelve
      `Failed` con `evaluationId` `eval-failed-*` (no se
      publica como artifact válido).

## Acceptance

- `assurance.verify { ... }` ejecuta body real en PipelineK.
- Se observa invocación real de Chronos.
- Collector OTel aporta traza correlacionada.
- Edge prohibido → counterexample causal.
- Captura incompleta → Inconclusive.
- Body fallido se preserva como outcome primario.
- Cancelación sigue siendo cancelación.
- Crash y replay verificables desde el journal y artifacts.

**UAT:** 012, 013, 014, 015, 016, 017, 018, 025, 033.
**AAT:** 5, 11, 13, 14, 19.

## STOP

Pruebas runtime basadas exclusivamente en envelopes
generados a mano o ventanas calculadas por timestamps.

## Cierre (objetivo)

`v0.12.0-rc1` con pipeline runtime demostrable, certificados
de causalidad, cancelación, crash y replay, y versiones
fijadas de Chronos y PipelineK.
