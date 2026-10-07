# SPEC — Contrato del plugin externo con PipelineK

## 1. Dependencias permitidas

El plugin puede depender de APIs públicas del SDK/domain/scripting necesarias para:

- `StepDefinition`;
- `StepDefinitionContributor`;
- codecs;
- typed outputs/outcomes;
- `RegistryStepSpec` lowering mediante façade DSL;
- `RegistryBlockSpec`;
- `BODY_CONTINUATION_CAPABILITY`;
- Event contributor cuando el SDK esté certificado.

No depende de `pipeline-application`.

## 2. `assurance.check`

Tipo: atomic external registry Step.

Input canónico:

```kotlin
data class AssuranceCheckInput(
    val name: String,
    val suite: AssuranceSuiteRef,
    val evidence: List<EvidenceInputRef>,
    val mode: EnforcementMode,
    val completenessPolicy: CompletenessPolicy,
)
```

Output:

```kotlin
data class AssuranceCheckOutput(
    val report: ArtifactRef,
    val reportDigest: Digest,
    val summary: AssuranceSummary,
    override val outcome: StepOutcome,
) : TypedStepOutput
```

Replay candidate: `MEMOIZED` si fingerprint incluye todos los artifact digests + suite digest + engine version.

## 3. `assurance.verify`

Tipo: body-owning external registry Step.

Descriptor:

```text
StepBody.Declared
owner = HANDLER_CONTINUATION
policy = Sequential
```

Algoritmo:

1. validar input;
2. crear `AssuranceEvaluationId`;
3. solicitar al runtime provider un `EvidenceWindowToken` si procede;
4. invocar `BodyContinuation` exactamente una vez en V1;
5. preservar cancelación como structured control;
6. cerrar/seal la ventana de evidence;
7. recolectar evidence inputs;
8. evaluar suite;
9. producir report;
10. combinar body outcome + assurance outcome sin ocultar body failure.

### Matriz de resultado

| Body | Assurance | Step outcome |
|---|---|---|
| success | pass | success |
| success | mandatory fail | failure(assurance) |
| success | inconclusive + requireComplete | failure(assurance-incomplete) |
| failure | pass | original body failure |
| failure | fail | original body failure + report ref |
| cancelled | any | propagate cancellation |

## 4. `assurance.diff`

Input:

```kotlin
data class AssuranceDiffInput(
    val baseline: EvidenceSetRef,
    val current: EvidenceSetRef,
    val suite: AssuranceSuiteRef,
    val ratchets: List<RatchetSpec>,
)
```

Produce clasificaciones `NEW`, `EXISTING`, `RESOLVED`, `REGRESSED`, `CHANGED`.

## 5. DSL lowering

Las façades Kotlin del plugin sólo construyen datos y bajan a registry primitives. No resuelven runtime registries ni ejecutan I/O durante construcción de `.pipeline.kts`.

## 6. Eventos

Si Event Plugin SDK disponible:

- `assurance.started`
- `assurance.evidence.accepted`
- `assurance.evidence.gap`
- `assurance.assertion.failed`
- `assurance.assertion.inconclusive`
- `assurance.completed`

Payloads acotados; grafos y reports via ArtifactRef/digest.
