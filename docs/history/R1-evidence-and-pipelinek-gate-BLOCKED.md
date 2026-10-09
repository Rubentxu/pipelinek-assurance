# Recibo R1 — Evidence estática real y primer gate PipelineK

**Bloque:** R1 — CogniCode + `assurance.check` real.
**Release propuesta:** `v0.2.0-alpha.1`.
**Fecha:** 2026-10-10.
**Estado:** **BLOQUEADO** por dependencia externa.

## Bloqueador

R1.1 exige consumir `assurance-evidence/v1` de CogniCode. El comando
y el schema **no existen** en el repo `Rubentxu/CogniCode` (clonado
y verificado el 2026-10-10). El workstream
`07-integrations/COGNICODE_WORKSTREAM.md` describe el export como
**trabajo a hacer** en WP-CG-002 (caracterización + exporter), no
como algo ya entregado.

```text
$ grep -rn "assurance-evidence" CogniCode-tmp/  → 0 matches
$ grep -rn "export assurance"   CogniCode-tmp/  → 0 matches
```

## Política aplicada

`# Política de decisiones y trabajo paralelo` del plan:

> Si un bloque depende de una release externa que no existe:
> entregar primero la dependencia en su repositorio, verificar
> su release, actualizar el consumidor con identidad exacta,
> continuar el bloque. No sustituir silenciosamente un contrato
> de producción por mocks y declarar completada la integración.

CogniCode no tiene el export, así que assurance no puede
"sustituirlo por mock y declarar R1 cerrado". El cierre exige
una release de CogniCode con `assurance-evidence/v1`, lo cual
queda fuera del alcance de este repositorio.

## Trabajo ejecutado en este repo (no suficiente para cerrar R1)

- `CogniCodeArtifactProvider` consume un shape `assurance-evidence/v1`
  con facts, source locations, completeness, stable ids, provenance.
  AAT-4 verde (no internals de CogniCode).
- Codec CBOR/JSON con bounded decoding y digest SHA-256.
- `SyntheticEvidenceProvider` para differential proof sobre el
  subset común.
- `M2DifferentialProofTest` certifica paridad entre provider
  sintético y CogniCode.
- Self-hosting S2 con `SelfHostingS2Test`: extractor in-test
  del propio repo, codifica el export, lo pasa por el provider,
  la lens proyecta, veredicto Passed.

Todo esto es **estructura consumidora**. El export real de
CogniCode es la pieza que falta.

## Acceptance bloqueado

UAT-006, UAT-007, UAT-008, UAT-009, UAT-022, UAT-023, UAT-024
requieren artefactos reales (export de CogniCode, distribución
de PipelineK con SDK). La lógica de las assertions y del plugin
está; la ejecución end-to-end no.

## Identificadores

- **SHA inicial:** `d6af4290faf51753d4f4ab91634bd3c1c6e30284`
- **Tag v0.1.0-alpha.1:** publicado el 2026-10-09T22:18:16Z,
  URL https://github.com/Rubentxu/pipelinek-assurance/releases/tag/v0.1.0-alpha.1
- **Tag v0.2.0-alpha.1:** **NO publicado** (gate no satisfecho).

## Siguiente paso

Coordinar con el repo de CogniCode para que WP-CG-002 produzca
el export. Una vez publicado y consumido, retomar R1 con el
mismo plan.
