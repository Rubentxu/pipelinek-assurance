# SPEC — Assurance IR

## Propósito

La DSL Kotlin es una façade de autoría. La autoridad reproducible es un IR cerrado, serializable y versionado.

## Suite

```text
AssuranceSuiteIR
  apiVersion
  suiteId
  suiteVersion
  requiredEvidence[]
  lenses[]
  assertions[]
  metadata
```

## Lens plan

```text
LensPlan
  lensId
  kind
  inputCapabilities[]
  arguments
  outputSchema
```

V1 kinds previstos:

- `architecture.hexagonal`
- `architecture.dependencies`
- `architecture.cycles`

Otros se añaden por registry/versioning, no por un `when` central disperso.

## Assertion IR

```text
AssertionIR
  id
  lensRef
  operator
  operands
  severity
  enforcement
  completenessRequirements
  rationale
```

Operators iniciales:

- no-edge-between-sets
- acyclic
- path-must-not-exist
- count-not-increase
- no-new-finding

No introducir lenguaje de query general antes de al menos dos families que necesiten una álgebra compartida más rica.

## Deterministic encoding

- field order canónico;
- set ordenado por canonical key;
- floats evitados en identities/digests siempre que sea posible;
- timestamps informativos fuera del digest semántico salvo que sean parte explícita de la assertion;
- engine/schema versions incluidos.

## Extensibilidad

La compatibilidad se gobierna por `apiVersion` + namespaced `kind`. Unknown kind -> `Unsupported`, nunca ignore.
