# ADR-001 — PipelineK owns execution; assurance owns verification semantics

**Status:** ACCEPTED

## Context

El producto sólo tiene sentido como plugin si usa realmente las fortalezas de PipelineK sin duplicarlas.

## Decision

PipelineK conserva orchestration/lifecycle/journal/replay/capabilities/body execution/events. Assurance no crea scheduler, DAG executor ni recovery protocol.

## Consequences

- `assurance.verify` usa `BodyContinuation`.
- assertions no ejecutan Steps.
- provider acquisition ocurre como Steps/adapters explícitos.
- core de PipelineK permanece unaware de assurance.
