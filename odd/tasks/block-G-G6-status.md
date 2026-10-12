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

## G7 — Re-cert de mutantes contra el plugin 🟡 (en curso)

**Comando:**

```bash
bash /tmp/cert-all-bg.sh
```

(43 mutantes, secuencial, ~25–40 min. Output:
`/tmp/cert-all-output.log`.)

**Mutantes que tocan código del plugin** (3):

- `M-P01`: `AssuranceVerifyStep.kt` — body failure preservation
- `M-P02`: `AssuranceVerifyStep.kt` — cancellation propagation
- `M-P03`: `AssuranceVerifyStep.kt` — handler invocation shape

**Mutantes que NO tocan código del plugin** (40):
domain, artifact, codec, engine, providers, CLI, etc.

**Por qué re-certificar TODOS, no solo los 3 del plugin:**

El Bloque G añadió tests al módulo `pipelinek-assurance-plugin`
(5 specifically G6.2 — V55–V59 — más el resto cubre
el wiring de G5/G6.1), más cambios en
`AssuranceCheckStep`, `AssuranceVerifyStep`,
`MockSdkHost`, `PluginManifestBuilder` y
`AssurancePluginContributor`. Un mutante que NO toca el plugin
puede haber sido matado antes por un test que ahora
también cubre código del plugin (o viceversa). El
gate "43/43 DEAD" del cierre de Bloque A `c3df2a7`
NO certifica el código nuevo del Bloque G. La re-cert
post-Bloque G es la regla de la unidad G7.

**Criterio de cierre G7:**

- 43/43 `DEAD` (killed >= 2): G7 cerrado verde.
- Cualquier `SURVIVED` o `UNDER_KILLED`: STOP del
  bloque; el mutante debe documentarse en
  `MUTATION_CATALOG.md` con plan correctivo antes de
  G8 / G9.

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

1. `clean check` BUILD SUCCESSFUL — ✅ (203/0/0 verde).
2. Cert de mutantes 0 SURVIVED — 🟡 (en curso, G7).
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
| 0.48.0      | 203 | 0 | 0 |
| 0.51.0-rc1  | 203 | 0 | 0 |

`./gradlew :pipelinek-assurance-plugin:clean :pipelinek-assurance-plugin:check`
cierra en BUILD SUCCESSFUL en ~12s en ambos SDKs.

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
