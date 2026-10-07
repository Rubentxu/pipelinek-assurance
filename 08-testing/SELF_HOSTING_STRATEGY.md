# Self-hosting — probarse a sí mismo mientras se construye

## Objetivo

Cada hito introduce una propiedad que el propio sistema usa inmediatamente sobre su repo o sus fixtures. Self-hosting no se reserva para el final.

## Fase S0 — Domain fitness

Antes de CogniCode integration, architecture tests ordinarios garantizan:

- domain no depende plugin/CLI/adapters;
- provider no depende assertions;
- no I/O in core.

## Fase S1 — Synthetic self-model

M1 crea manual/synthetic dependency evidence del propio layout y ejecuta HexagonalLens. Esto prueba el engine sin depender de CogniCode.

## Fase S2 — CogniCode self snapshot

M2 reemplaza synthetic evidence por export real de CogniCode. Se ejecutan ambas y deben producir proyecciones estructuralmente equivalentes para el subset compartido.

## Fase S3 — PipelineK self gate

M3 añade `.pipeline.kts` del nuevo repo:

```text
build -> tests -> cognicode export -> assurance.check
```

El plugin se instala como external JAR, no como project-internal shortcut.

## Fase S4 — Mutation proof

Cada release candidate crea mutantes controlados en temporary worktrees:

- domain imports adapter;
- cycle;
- heuristic promoted incorrectly;
- incomplete evidence treated as empty.

La suite debe detectarlos.

## Fase S5 — Runtime self verification

M7 envuelve integration tests propios con `assurance.verify` y Chronos fixture.

## Fase S6 — Release argument

M10/M11 release sólo si:

- static architecture;
- no-new critical smells;
- mutation strength;
- runtime invariants;
- observability assertions;
- plugin installed UAT.

## Regla anti-vacuidad

Una assertion no puede promocionarse a mandatory hasta existir al menos un fixture/mutante que la haga fallar por la razón esperada.
