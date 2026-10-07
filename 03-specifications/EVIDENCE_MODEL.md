# SPEC — Evidence model

## Snapshot

```kotlin
data class EvidenceSnapshot(
    val id: SnapshotId,
    val subject: SubjectRef,
    val sources: NonEmptyList<EvidenceSourceManifest>,
    val items: PersistentList<EvidenceItem>,
    val gaps: PersistentList<EvidenceGap>,
    val correlations: PersistentList<Correlation>,
)
```

## EvidenceItem

```kotlin
sealed interface EvidenceItem {
    val id: EvidenceId
    val subject: EvidenceSubject
    val authority: EvidenceAuthority
    val provenance: Provenance

    data class Fact(...): EvidenceItem
    data class Observation(...): EvidenceItem
    data class Signal(...): EvidenceItem
    data class Hypothesis(...): EvidenceItem
}
```

## Authority

- `DeterministicAdapter`
- `DeterministicAnalyzer`
- `RuntimeObserver`
- `HeuristicAnalyzer`
- `HumanCurated`
- `AgentHypothesis`

## Completeness

`EvidenceSourceManifest` declara:

- producer id/version;
- subject revision/session;
- capabilities requested;
- capabilities actually produced;
- completeness por capability;
- known gaps;
- schema version;
- digest.

## Stable identity

Encontrar el mismo smell/finding tras mover una línea no debe crear identidad nueva si el provider dispone de identidad semántica estable. El canonical Evidence ID combina provider namespace + semantic subject identity + evidence kind + stable discriminator.

## Correlation

```kotlin
Correlation(
  from = TypedExternalId(...),
  relation = CORRELATED_WITH,
  to = TypedExternalId(...),
  evidence = EvidenceId,
)
```

No usar Strings sin namespace/tipo.

## Content addressing

Payload voluminoso puede vivir como ArtifactRef. El evidence item guarda digest + media type + logical role.
