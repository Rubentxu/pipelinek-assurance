# SPEC — Observabilidad de assurance dentro de PipelineK

## Event Plane

Eventos describen hechos semánticos, no transportan grafos/reports grandes.

```text
AssuranceStarted
EvidenceAccepted
EvidenceGapDetected
AssertionFailed
AssertionInconclusive
AssuranceCompleted
```

## Output Plane

stdout/stderr de CogniCode, Chronos, Gradle, detekt, test runners continúan siendo bytes del Output Plane.

## Artifacts

Evidence snapshots, reports y large counterexamples se publican por ArtifactRef/content digest.

## CLI

Con el Observation subsystem:

```bash
pipelinek observe RUN --kind assurance.assertion.failed --format jsonl
```

El plugin no implementa follow/replay de runs por separado.

## Correlation

Los events incluyen:

- assurance evaluation id;
- suite/assertion id;
- report/counterexample ref;
- PipelineK op correlation ya aportada por Event Plane.
