# SPEC — Baseline, freezing y ratchets

## Objetivo

Generalizar Detekt baseline y ArchUnit freezing a cualquier assertion.

## Finding identity

```kotlin
data class KnownViolation(
    val stableId: FindingId,
    val assertion: AssertionId,
    val fingerprint: Digest,
    val firstSeenRevision: RevisionRef,
    val owner: String?,
    val rationale: String?,
    val expires: LocalDate?,
)
```

## Diff states

- NEW
- EXISTING
- RESOLVED
- REGRESSED
- CHANGED

## Ratchets

Ejemplos:

```kotlin
noNewViolations()
noNewCycles()
maxExistingCountMustNotIncrease()
criticalMutantsMustBeKilled()
complexityP95MustNotRegress()
```

Ratchet numérico sólo cuando la métrica tenga semántica y measurement protocol estable.

## Expiry

Baselines pueden tener excepciones con owner/rationale/expiry. Expired exception deja de suppress y se reporta claramente.
