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

### La asimetría Fact / Observation en `Completeness`

`Fact` y `Observation` admiten conjuntos DISTINTOS de valores de
`Completeness`, y no es un descuido:

| Item | `Complete` | `Partial` | `Unknown` | `Unsupported` |
|---|---|---|---|---|
| `Fact` | sí | sí | **no** | **no** |
| `Observation` | sí | sí | sí | sí |

La razón es epistemológica, no de estilo. Un `Fact` afirma que algo es
reproducible y cierto: si el producer no pudo observar lo que afirma, o si
declaró que el dato no soporta esa afirmación, el item es una afirmación sin
respaldo, y es exactamente el modo de fallo que este sistema existe para
detectar. Permitir `Fact(Unknown)` sería fabricar certeza a partir de una
ausencia de evidencia.

Un `Observation` sólo dice "esto se vio en esta ejecución". Que la ejecución
no cerrara, o que un provider la descartara, es información legítima sobre la
observación, no una contradicción. Por eso `Observation` sí los admite, y por
eso `Observation` puede ser la evidencia de que faltó evidencia.

Consecuencia práctica: un item de completitud `Unknown` o `Unsupported` **no
puede** presentarse como hecho. El motor tiene que degradarlo a `Inconclusive`,
no a `Passed`. Esto está verificado por los mutantes M-E01 (el invariante de
`Fact`) y por `EvidenceLawsTest`.

Nota de implementación: `Completeness` NO es una propiedad de la interfaz
`EvidenceItem`; cada variante la declara por su cuenta. El código que necesite
la completitud debe hacer pattern match sobre la variante, no leer una
propiedad común.

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

**Los ids NO tienen que ser únicos dentro de un snapshot.** El dominio no lo
exige y el digest canónico no puede dar por supuesto que lo sean: si dos items
comparten `EvidenceId`, el orden entre ellos se resuelve por su contenido
canónico, no por su posición de entrada. Ordenar sólo por `EvidenceId` parece
suficiente y no lo es, porque `sortedWith` es estable y el empate lo resuelve la
posición, que es justo lo que el digest prohíbe. Este defecto no lo
encontraron los tests de ejemplo (usaban ids únicos); lo encontró el property
testing, y está protegido por el mutante M-R02.

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
