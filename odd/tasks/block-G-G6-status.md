---
name: block-G-G6-status
description: G6 (typed DSL extension + sample pipeline) y G6.2 honest outcome con dos gaps upstream pinneados. Estado de G7, G8, G9.
---

# Bloque G — estado G6 (typed DSL extension + sample pipeline)

## TL;DR

G6.1 (build-time manifest + live e2e admission) cerrado
en commit `20fb3bf`. G6.2 (typed DSL extension + 5
e2e tests + honest SDK gap documentation) cerrado en
commits `72ade5f`..`dc03f00`. G8 (compatibility matrix
post-run) cerrado en commit `5849fcb`. **G7 (re-cert
de mutantes) corriendo en background al cierre de este
documento.** G9 (release candidate v0.13.0-rc1) **bloqueado
por dos gaps upstream** que se describen abajo.

## G6.1 — Build-time manifest + live e2e admission ✅

Ver `docs/G6-FINDINGS.md` (178 líneas en su versión G6.1)
y commit `20fb3bf`.

- `PluginManifestBuilder.kt` genera el manifest a build time
  vía `JavaExec`, embebido en el JAR por `processResources`.
- `StepRegistration(definition, provider)` cableado con
  `StepProviderMetadata.create(...)` (constructor `internal`
  documentado).
- AAT-3 (`G3_V47`) allowlist extendido a 2 archivos
  (con justificación).
- Verificado e2e: `pipelinek run` admite el plugin y
  descubre los steps en SDK 0.48.0 y 0.51.0-rc1.

## G6.2 — Typed DSL extension + honest SDK gaps ✅ (en estructura)

Ver `docs/G6-FINDINGS.md` (405 líneas con addenda v1 y v2),
`CHANGELOG.md` (sección Bloque G) y commits
`72ade5f`..`dc03f00`.

- `AssuranceCheckDslExtension.kt` con dos overloads
  (`(scope, stepKey, encodedInput)` y `(scope, name, input)`).
- `G6E2ETest.kt` con 5 tests (V55–V59).
- **Gap honesto 1 — runtime capability pre-check**: el SDK
  aborta con `missing required capabilities` sin SPI
  `RuntimeCapabilityContributor`.
- **Gap honesto 2 — kts script classpath**: el compilador
  de `.kts` del SDK no incluye el JAR del plugin; cualquier
  `import dev.pipelinek.assurance.plugin.*` falla con
  `Unresolved reference`.
- 203/0/0 verde en ambos SDKs.

## G7 — Re-cert de mutantes contra el plugin ✅ (40/43 DEAD, 3 INFRA_FAILED)

**Resultado del cert (G7):**

| Veredicto | Count | Mutantes |
| --- | --- | --- |
| DEAD | 40 | (ver lista abajo) |
| INFRA_FAILED | 3 | M-D02, M-V01, M-V02 |
| UNDER_KILLED | 0 | (era 2: M-CODEC01, M-10-CONTENT; cerrados con tests redundantes) |
| MUTATION_NOT_APPLIED | 0 | (era 2: M-B01, M-NORM-01; cerrados con fix de patrón) |
| SURVIVED | 0 | (gate verde) |

**Mutantes INFRA_FAILED (3):** M-D02 (cambia `digest: String` →
`String?` y rompe compilación downstream), M-V01 (cambia
`EvidenceProvider` → `AssertionResult` rompiendo AAT-6), M-V02
(cambia `AssertionResult` a no-sealed rompiendo AAT-8). El
harness no puede distinguir entre "mutante mata un test" y
"mutante rompe la compilación" en estos casos; el veredicto
INFRA_FAILED se documenta en lugar de contarse como DEAD.

**Mutantes UNDER_KILLED cerrados con tests redundantes:**

- M-CODEC01 (`PackArtifactCodec.decodeFromCborPlan`):
  test nuevo `plan_con_digest_recalculado_tras_modificar_selecciones_se_rechaza`
  en `assurance-artifact` testkit; muta el contenido y
  recalcula el digest para un payload que un mutante
  permisivo aceptaría.
- M-10-CONTENT (`ConnascenceLens.findConnascenceOfName`):
  test nuevo `M_10_CONTENT_redundante_name_finding_reporta_strength_2_y_subject_no_vacio`
  en `assurance-testkit`; verifica la forma del finding
  (strength = 2, subject no vacío) en vez del solo conteo.

**Mutantes MUTATION_NOT_APPLIED cerrados con fix de patrón:**

- M-B01 (`BaselineAndDiff.kt`): patrón actualizado de
  `baselineById[id]` a `baselineByAssertionId[id.assertionId]`
  (el código se renombró en M4).
- M-NORM-01 (`EvidenceNormalizer.kt`): path actualizado de
  `assurance-engine/...` a `assurance-providers/...` (el
  normalizer se movió en M2).

**Harness fix introducido en G7:**

- El default del verdict era `DEAD` cuando el patrón de
  mutación no se encontraba; ahora es `MUTATION_NOT_APPLIED`.
  Antes, un patrón roto escribía un recibo con
  `verdict: DEAD, killed_count: 0`, que es un falso positivo
  del harness. Después del fix, el cert puede distinguir
  entre "el mutante fue aplicado y mató tests" (DEAD) y
  "el mutante no se aplicó porque el código se movió"
  (MUTATION_NOT_APPLIED).
- Docstring del cert actualizado de 4 a 5 veredictos.

**Comando reproducible:**

```bash
cd pipelinek-assurance-blueprint
rm -f build/mutant-receipts/*.json
for m in $(grep -oE '^    "M-[A-Z0-9-]+":' tools/certify_mutants.py | \
           sed -E 's/^    "(.+)":/\1/' | tr -d ' '); do
  python3 tools/certify_mutants.py "$m"
done
```

**Tiempo:** ~30 min para 43 mutantes en SDK 0.48.0 sobre el
worktree actual.

## G8 — `SDK_COMPATIBILITY_MATRIX.md` post-run ✅

Ver commit `5849fcb` y `06-uat/SDK_COMPATIBILITY_MATRIX.md`
(113 líneas).

- Tabla reescrita con 2 filas reales: 0.48.0 y 0.51.0-rc1.
- Nuevas columnas `Cert. live admission` y `Cert. live handler`
  para hacer visible el gap del SDK.
- Sección "Gaps upstream pinneados" con enlaces a
  `docs/G6-FINDINGS.md`.
- Política de promoción actualizada: la nueva fila
  requiere re-cert de mutantes (regla G7).

## G9 — Release candidate `v0.13.0-rc1` ⛔ (bloqueado por upstream)

**Trabajo de G9 (per `block-G-work-units.md`):**

1. `clean check` BUILD SUCCESSFUL — ✅ (707/0/0 verde).
2. Cert de mutantes 0 SURVIVED — ✅ (G7 cerrado: 40 DEAD, 3 INFRA_FAILED documentados, 0 SURVIVED).
3. SBOM CycloneDX firmado, SHA256SUMS firmados,
   provenance del build — ⛔ no ejecutado (parte de M11,
   pendiente).
4. JAR reproducible — ⛔ no certificado (parte de M11,
   pendiente).
5. CLI empaquetado (`build-cli-dist.sh`) — ⛔ no
   certificado (parte de M11, pendiente).
6. `PipelineKApiRange` declarado `0.48.0 || 0.51.0-rc1` —
   ✅ (ya en `plugin-manifest.json` y validado por
   `G6_V59`).
7. Tag `v0.13.0-rc1` creado — ⛔ no creado.
8. Push y merge a `main` — user-gated.

**Bloqueador principal (no se resuelve en este repo):**

> El handler de `assurance.check` y `assurance.verify` no
> se ejecuta contra el SDK live porque el runtime del
> SDK 0.48.0 / 0.51.0-rc1 impone un pre-check de capabilities
> sin SPI `RuntimeCapabilityContributor`. Esto NO es un
> bug del plugin: el plugin declara las capabilities en
> el manifest (correcto), pero el host no las publica
> en el runtime. El plugin depende de una SPI upstream
> que no existe.

**Estado honesto de G9:**

G9 está **bloqueado por upstream**, no por trabajo
pendiente en este repo. Los criterios 1, 2 y 6 del gate
están cumplidos o en vía de cumplimiento. Los criterios
3, 4, 5 son del Bloque M11 (release readiness, previo
al Bloque G). El criterio 7 (tag) está diferido
explícitamente hasta que la SDK arregle la SPI.

**Decisión recomendada:**

- No tag `v0.13.0-rc1` hasta que el SDK arregle gap #1
  Y la SPI sea pública.
- Mantener `consolidation/G-integration-v2` con los
  6 commits G6 + G8 + G7 verdedictos verdes como el
  último estado bueno antes del blocker.
- Cuando el SDK arregle gap #1, retomar G7 (re-cert
  contra nueva SDK), G8 (actualizar matriz), G9
  (tag + merge).

## Total verde

| SDK | Test count | Failures | Skipped |
|-----|-----------|----------|---------|
| 0.48.0      | 707 (52 artifact + 341 testkit + 111 providers + 203 plugin) | 0 | 0 |
| 0.51.0-rc1  | 203 (plugin only) | 0 | 0 |

`./gradlew clean check` cierra en BUILD SUCCESSFUL en ~45s.
El sub-proyecto `pipelinek-assurance-plugin` corre contra
ambos SDKs (cambia la coordenada con `-PpipelinekVersion=...`
y `PIPELINEK_SDK_LIB=...`).

## Commits atómicos del Bloque G (sobre `consolidation/A-F`)

```
G6.1 + admisión:
  20fb3bf  G6 (Bloque G) — Generate plugin-manifest at build time
           wire StepRegistration, end-to-end admit
  3b7e51d  docs(G5): admission + cross-check contra SDK real
  c4b5659  G5 admission+cross-check contra SDK real 0.48.0

G6.2 typed DSL extension + tests:
  72ade5f  G6.2: typed DSL extension + sample kts invoking assurance.check
  18cd936  G6.2: 5 e2e tests + AAT-3 allowlist comment justifying the extension
  90c9bb3  G6.2: addendum to G6-FINDINGS documenting the SDK capability gap
  d6febe8  G6.2: CHANGELOG entry documenting the honest SDK gap

G6.2 addendum v2 (kts script classpath gap):
  ca0ff3c  G6.2: addendum v2 — kts script classpath is also an SDK gap
  dc03f00  G6.2: CHANGELOG notes the second SDK gap

G8 compatibility matrix:
  5849fcb  G8: SDK_COMPATIBILITY_MATRIX post-run with 0.48.0 and 0.51.0-rc1
```

(28 commits en total, los anteriores son del Bloque F.)
