# Functional core — disciplina Haskell aplicada a Kotlin

## Principios

1. Datos inmutables por defecto.
2. ADTs sealed para todos los estados cerrados.
3. Efectos en bordes explícitos.
4. Errores como valores en el core.
5. No exceptions para expected domain outcomes.
6. No clock global; si una operación realmente necesita tiempo, recibe `EvaluationInstant` como dato.
7. No hidden mutable registries después de startup freeze.
8. Orden canónico para serialización/digests.

## Modelo de evaluación

```kotlin
sealed interface AssertionResult {
    data class Passed(val proof: ProofRef) : AssertionResult
    data class Failed(val counterexample: Counterexample) : AssertionResult
    data class Inconclusive(val gaps: NonEmptyList<EvidenceGap>) : AssertionResult
    data class Unsupported(val reason: UnsupportedReason) : AssertionResult
    data class Error(val failure: EvaluationFailure) : AssertionResult
}
```

## Lenses

Una lens nunca produce `Violation` directamente.

```kotlin
fun interface AssuranceLens<I, O> {
    fun project(input: I): ProjectionResult<O>
}
```

Una assertion consume la proyección:

```kotlin
fun interface AssuranceAssertion<A> {
    fun evaluate(input: A): AssertionResult
}
```

Composición:

```text
EvidenceSnapshot
  |> HexagonalLens.project
  |> NoForbiddenDependency.evaluate
```

## ValidatedNel

Cuando una suite pueda evaluar assertions independientes, se acumulan resultados sin short-circuit para producir un informe completo. Sólo el Step handler decide cómo transformar el report a `StepOutcome`.

## Pure registries

Providers/lenses/functions se registran al startup y después se congelan. El evaluador recibe un `FrozenAssuranceRuntime`, no un `MutableMap` global.

## No magical boolean

Nunca representar estados complejos con booleanos como:

```text
passed=true, executed=false
```

Cada estado tiene constructor propio.
