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

## Estado (observado 2026-10-10, SHA `125304b`)

**Bloqueado por externo.** D1 requiere la release firmada de
Chronos con el contrato `assurance-runtime-evidence/v1`.

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

- [ ] Cancelación del ancestro, body fallido, fallo del
      observer, pérdida de evidencia, caída antes/después del
      sellado, interrupción al publicar.

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
