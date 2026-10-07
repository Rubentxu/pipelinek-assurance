# Arquitectura de referencia

## 1. Vista global

```text
                        PIPELINEK
                           |
            +--------------+---------------+
            |              |               |
      assurance.check assurance.verify assurance.diff
            |              |               |
            +--------------+---------------+
                           |
                    AssuranceEngine
                           |
          +----------------+----------------+
          |                                 |
   EvidenceNormalizer                  Suite IR
          |                                 |
   EvidenceSnapshot                        |
          |                                 |
          +--------------+------------------+
                         |
                       Lenses
                         |
                    Projections
                         |
                    Assertions
                         |
                  AssertionResult
                         |
                  Counterexamples
                         |
                 AssuranceReport
```

## 2. Functional core

Funciones conceptuales puras:

```kotlin
normalize(inputs): Either<NormalizationError, EvidenceSnapshot>
project(snapshot, lens): ProjectionResult<A>
evaluate(projection, assertion): AssertionResult
evaluateSuite(snapshot, suite): AssuranceReport
diff(base, current, suite): AssuranceDiff
applyBaseline(report, baseline): RatchetedReport
```

No leen filesystem, no llaman red, no leen clock global.

## 3. Imperative shell

Adapters:

- Artifact readers;
- CogniCode Evidence exporter/reader;
- Chronos Evidence exporter/reader;
- Detekt/SARIF reader;
- OTel reader;
- Git reader;
- PipelineK Step handlers;
- CLI.

## 4. Evidencia y autoridad

```text
Fact
  reproducible assertion about snapshot

Observation
  something observed in one execution

Signal
  derived heuristic/ranking

Hypothesis
  human/agent proposition without authority
```

`EvidenceAuthority`:

- DeterministicAdapter
- DeterministicAnalyzer
- RuntimeObserver
- HumanCurated
- HeuristicAnalyzer
- AgentHypothesis

`Completeness`:

- Complete
- Partial(gaps)
- Unknown
- Unsupported

No se codifica confianza y completitud en un único score.

## 5. Identidades

Nunca colapsar:

```text
PipelineRunId
PipelineStepOpId
AssuranceEvaluationId
CogniCodeSnapshotId
CogniCodeEntityId
ChronosSessionId
ChronosInvocationId
OTelTraceId
OTelSpanId
GitRevision
```

Se relacionan mediante `CorrelationRef` con tipo explícito.

## 6. Persistencia

V1 no requiere servidor. Artifacts content-addressed:

```text
.assurance/
  objects/sha256/...
  snapshots/<id>.cbor
  reports/<id>.cbor
  baselines/<name>.json
```

En PipelineK los artifacts se pueden publicar/retener mediante las capacidades existentes. El plugin no crea un event store paralelo.

## 7. Determinismo

Para mismos:

- EvidenceSnapshot digest;
- SuiteIR digest;
- Engine version;
- baseline digest;

el report canonical digest debe ser idéntico.
