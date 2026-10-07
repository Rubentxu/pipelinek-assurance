# SPEC — Assertions, counterexamples y reports

## Assertion result

```kotlin
sealed interface AssertionResult {
  Passed(proof)
  Failed(counterexample)
  Inconclusive(gaps)
  Unsupported(reason)
  Error(failure)
}
```

## Counterexample

Debe ser estructurado, no un mensaje plano.

Ejemplos:

- `DependencyPathCounterexample`
- `CycleCounterexample`
- `CausalSliceCounterexample`
- `MutationCounterexample`
- `MissingTraceCounterexample`
- `BaselineRegressionCounterexample`

Todo contraejemplo incluye:

- assertion id;
- subject refs;
- evidence refs;
- source locations si existen;
- reproduction hints;
- deterministic explanation fields.

## Report

`AssuranceReport` contiene:

- snapshot/suite digests;
- engine version;
- assertion results;
- evidence gaps;
- summary counts por estado;
- body outcome ref para verify;
- artifacts;
- correlations.

## No global score

Puede generarse dashboard agregado, pero no forma parte del gate canónico.
