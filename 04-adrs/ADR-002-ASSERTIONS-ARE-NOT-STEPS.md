# ADR-002 — Assertions and lenses are pure data, not PipelineK Steps

**Status:** ACCEPTED

## Decision

Una suite se compila a IR y se evalúa dentro de un Step handler. No se convierte cada assertion en Step.

## Why

Evita journal inflation, falsa semántica de efectos, overhead de cientos de operations y acoplamiento de DSL de calidad con DSL de orchestration.
