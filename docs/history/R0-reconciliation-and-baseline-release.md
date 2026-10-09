# Recibo R0 — Reconciliación y release de baseline

**Bloque:** R0 — Reconciliación, certificación del trabajo local y primera release.
**Release:** `v0.1.0-alpha.1` (propuesta).
**Fecha:** 2026-10-10.
**SDDK cycle id:** r0-baseline-2026-10-10.

## Identificadores

- **SHA inicial (HEAD local declarado):** `7aa2feb`
  *(en realidad `faa8085` — la meta llegó desfasada por un commit)*
- **SHA integrado en `origin/main`:** pendiente (este recibo precede al push)
- **Tag publicado:** `v0.1.0-alpha.1` (target)
- **URL de release:** pendiente (este recibo precede a `gh release create`)

## R0.1 — Reconciliación local/remoto

| Campo | Valor |
|---|---|
| `origin` URL | https://github.com/Rubentxu/pipelinek-assurance.git |
| `merge-base origin/main HEAD` | `0645599f76297f1cbf9089ed0d1ecf6fd97661c3` |
| Commits locales ahead | 32 (incluyendo el fix de manifest) |
| Commits remotos ahead | 0 |
| Tags locales | 0 |
| Tags remotos | 0 |
| Working tree | clean |
| Staged | 0 |
| Untracked | 0 |

`origin/main` es ancestro directo de HEAD. No hay divergencia; la
integración es un fast-forward sin pérdida de commits.

## R0.2 — Verificación de mutantes

Harness `tools/certify_mutants.py` con `cleanTest` previo:

| Mutante | total | killed | AVISO |
|---|---|---|---|
| M-A01 | 256 | 3 | no |
| M-A02 | 256 | 4 | no |
| M-A03 | 256 | 4 | no |
| M-H01 | 256 | 2 | no |
| M-B01 | 335 | 2 | no |
| M-P01 | 280 | 2 | no |
| M-P02 | 280 | 2 | no |
| M-C01 | 335 | 2 | no |
| M-O01 | 335 | 2 | no |
| M-10-01 | 335 | 3 | no |
| M-10-02 | 335 | 3 | no |
| M-10-03 | 335 | 3 | no |
| M-10-04 | 335 | 3 | no |

**13 mutantes certificados con redundancia ≥ 2. Sin AVISO.**

Verificación de `ChronosArtifactProvider.decodeExport`: regex
añadido durante el ciclo para parsear `completenessByCapability`
del JSON. Sin el fix, la lógica de gaps nunca se ejecutaba.
Detalle: `4a1b46f feat(providers): M-C01 decode de
completenessByCapability y test de gap`.

## R0.3 — Reconciliación de gates M0..M10

`./gradlew --no-daemon clean check`:

- BUILD SUCCESSFUL en 33-38s (tres corridas, mismo SHA)
- 352 tests, 0 failures, 0 skipped

Verificación de UAT/AAT con `assure` CLI end-to-end:

```text
$ ./gradlew :assure-cli:run --args="report 08-testing/self-model.graph"
{"kind":"AssertionPass","data":{"passed":"2","total":"2"}}

$ ./gradlew :assure-cli:run --args="report <self-model-roto>"
{"kind":"AssertionFailure","data":{"path":"domain -> testkit",
 "fromLayer":"Domain","toLayer":"Adapters"}}
```

Cubre UAT-003 (witness reproducible), UAT-021 (agent
discoverability) y UAT-022 (self-host). M3..M11 mantienen el
carácter `estructura` documentado en el ROADMAP por bloqueadores
externos reales (CogniCode/Chronos/OTel collector, host con SDK
de PipelineK).

## Drift de `PACKAGE_MANIFEST.md`

Detectado: 5 archivos blueprint con SHA desactualizado
(EVIDENCE_MODEL, MILESTONE_GATES, UAT_CATALOG, MUTATION_CATALOG,
ROADMAP). Regenerados en el commit `259fdb9`. El manifest
recupera su invariante de integridad.

## Riesgos y deuda pendiente

- Mutantes M-P03 y M-I01 del catálogo, sin certificado en el
  harness (M11 segundo pase).
- UAT end-to-end con host con SDK, export real de CogniCode,
  Chronos y OTel collector (bloqueadores externos).
- Performance budgets formales con umbral (sin fixtures de carga).
- Checksums firmados del SBOM, provenance (sin infraestructura de
  claves en el runner).

## Estado del gate R0

- [x] Árbol integrado limpio.
- [x] Suite completa sobre SHA definitivo.
- [x] Harness de mutación válido.
- [x] Estado de M0..M10 reconciliado.
- [x] Ninguna pérdida de commits locales o remotos.
- [ ] Tag remoto y release publicada (siguiente paso).
- [x] Receipt verificable (este archivo).
