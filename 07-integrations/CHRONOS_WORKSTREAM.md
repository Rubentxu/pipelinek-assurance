# Workstream Chronos — adaptación para pipelinek-assurance

## Objetivo

Exponer runtime evidence causal y reproducible para que `assurance.verify` pueda afirmar propiedades sobre una ventana de ejecución delimitada.

## Principios ya alineados

El roadmap de Chronos ya insiste en:

- ExecutionLog por sesión como evidencia autoritativa;
- EventSeq/cursor/replay/sellado;
- No Silent Lies;
- identidad estática, invocación dinámica y trace/span externos separados;
- M6 OTLP correlation;
- M8 counterexamples;
- M9 happens-before.

## H0 — Characterization

Congelar:

- session creation/seal;
- read cursor semantics;
- gap/loss behavior;
- invocation identity;
- causal edges;
- current external trace correlation shape.

## H1 — `assurance-runtime-evidence/v1`

Export externo versionado:

```text
manifest
session ref
window ref
invocations
causal edges
runtime properties
violations/observations
external trace correlations
completeness/loss/gaps
artifacts
```

Chronos violations siguen siendo observations/signals según su semántica; assurance decide gate.

## H2 — Deterministic window token

Necesitamos un protocolo para `assurance.verify`:

```text
prepare -> WindowToken
execute body
seal(WindowToken) -> SealedWindowRef
export(SealedWindowRef)
```

No delimitar por `now - 30s` ni timestamp heurístico.

El token debe poder correlacionarse con PipelineK op metadata sin fusionar IDs.

## H3 — CLI

```bash
chronos assurance window open ...
chronos assurance window seal <token>
chronos assurance export <window> -o runtime.cbor
```

La forma exacta puede reducirse si Chronos expone un único command transaccional, pero el contrato conceptual debe permanecer.

## H4 — Completeness and loss

Export incluye:

- loss counters;
- unsupported event classes;
- privilege gaps;
- backend limitations;
- session sealed state.

Cualquier gap relevante para assertion -> Inconclusive.

## H5 — OTel correlation

M6 Chronos puede exportar typed refs:

```text
ChronosInvocationId -> OTelSpanId
SessionId -> TraceId(s)
```

pero assurance conserva todos los tipos.

## H6 — Counterexample bridge

Cuando Chronos M8 produzca counterexamples/shrinking, exportarlos como referenced artifact/causal slice compatible con `Counterexample` de assurance.

No duplicar shrinker en assurance.

## H7 — Self-validation

Chronos usa assurance para:

- validar architecture boundaries;
- verificar No Silent Lies contract con negative fixtures;
- probar OTel correlation coverage sobre su propio integration fixture.

## Cambios que NO hacer

- no importar PipelineK SDK en chronos-core;
- no usar MCP como seam;
- no convertir EventSeq en wall-clock time;
- no responder “no observations” cuando el backend fue incapaz de observar.
