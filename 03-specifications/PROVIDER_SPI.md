# SPEC — Evidence Provider SPI

## Principio

Providers producen evidence. No conocen suites ni assertions y no deciden PASS/FAIL.

## Contrato conceptual

```kotlin
interface EvidenceProvider {
    val descriptor: EvidenceProviderDescriptor

    fun collect(request: EvidenceRequest): EvidenceCollectionResult
}
```

El core puro no llama este SPI directamente; un application service/adaptor reúne outputs y luego invoca `normalize`.

## Descriptor

- id/version;
- evidence capabilities;
- supported subject kinds;
- deterministic/runtime/heuristic classification;
- input formats;
- output schema version;
- cost hints informativos, nunca semántica.

## Providers V1/V2

### V1

- `CogniCodeArtifactProvider`
- `DetektSarifProvider`
- `GitProvider`

### V2

- `ChronosArtifactProvider`
- `OtelArtifactProvider`
- `JUnitXmlProvider`
- `MutationReportProvider`
- `CoverageProvider`

## Rule

Si un provider no puede producir una capability solicitada devuelve `Unsupported` o `Partial`, no una colección vacía indistinguible de “cero findings”.
