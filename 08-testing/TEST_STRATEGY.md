# Estrategia de tests

## Pirámide específica

### T0 — Pure domain tests

ADTs, codecs, canonical ordering, digests.

### T1 — Property tests

- permutation invariance;
- encode/decode identity;
- lens composition laws;
- diff algebra;
- baseline idempotence.

### T2 — Mutation tests

Mutantes obligatorios por capability crítica:

- Inconclusive -> Passed;
- ignore evidence authority;
- drop edge in dependency graph;
- cycle detector skips node;
- body failure overwritten by assurance failure;
- cancellation translated to failure;
- baseline NEW treated as EXISTING.

### T3 — Provider contract tests

Golden external artifacts + malformed/partial/unknown versions.

### T4 — Plugin in-process

Step codecs/handlers/manifest/contributor.

### T5 — Installed distribution UAT

Real external JAR, real `.pipeline.kts`, no test-only classpath.

### T6 — Crash/replay

PipelineK restart/reuse where applicable.

### T7 — Cross-repo UAT

Real CogniCode/Chronos binaries/artifacts.

## No fragile timing gates

Performance budgets se fijan tras benchmark. Runtime causal tests usan barriers/window tokens, no `sleep 300ms`.
