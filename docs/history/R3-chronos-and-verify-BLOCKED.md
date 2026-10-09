# Recibo R3 — Chronos y `assurance.verify` sobre ejecución real

**Bloque:** R3 — M6 + M7.
**Release propuesta:** `v0.4.0-alpha.1`.
**Fecha:** 2026-10-10.
**Estado:** **BLOQUEADO** por dependencias externas.

## Bloqueadores

R3.1 exige consumir `assurance-runtime-evidence/v1` de Chronos.
R3.3 exige ejecutar `assurance.verify` sobre una distribución
instalada de PipelineK con SDK. Ambos externos.

```text
$ grep -rn "assurance-runtime-evidence" chronos-tmp/  → 0 matches
```

El `ChronosArtifactProvider` (consumidor) está completo y
certificado. M-C01 muerto por 2 tests. Lo que falta es el
**productor** (Chronos emitiendo ese export versionado) y el
**host** con SDK de PipelineK para ejecutar el Step.

## Trabajo ejecutado en este repo

- `ChronosArtifactProvider` con `windowToken` protocol
  (H2: no timestamp aproximado).
- `RawEvidenceItem` con `subjectRef` tipado para
  `ChronosInvocationId` (AAT-13).
- `EvidenceCollectionResult.Produced` con `declaredGaps` honestos.
- `decodeExport` con regex que parsea `completenessByCapability`
  del JSON. Sin este fix, M-C01 era un falso "sobrevive".
- `ObservedArchitectureLens` para `runtime.invocation-chain`.
- `AssuranceVerifyStep.combine` con matriz body×assurance de
  6 filas. M-P01, M-P02 certificados con redundancia 2.
- `UAT-033` registrado: window token is required, not
  approximated.

## UAT cubiertos por la lógica (no E2E)

- UAT-012 (body success): `body_success_con_suite_pass_es_success`.
- UAT-013 (body failure preservation): `body_failure_con_suite_pass_es_failure_de_body`.
- UAT-014 (cancellation): `body_cancelled_se_propag_a_como_cancelled`.
- UAT-016 (runtime incomplete): la lógica convierte
  `RawGapReason.Lost` → `Inconclusive`; verificación E2E
  con export real pendiente.
- UAT-025 (crash-safe artifact): la atomicidad del report está
  cubierta por M-D01 (envelope verifica digest) y M-D02
  (digest obligatorio).

## Mutantes

- M-C01 (Chronos gap ignorado): killed=2.
- M-P01 (assurance failure sobrescribe body failure): killed=2.
- M-P02 (cancellation capturada como Failure): killed=2.
- M-P03 (handler itera children fuera de BodyContinuation):
  declarado, sin certificado en el harness (M11 segundo pase).

## Estado del bloque

BLOQUEADO. No se publica `v0.4.0-alpha.1`. La pieza consumidora
está hecha; la pieza productora y el host son externos.
