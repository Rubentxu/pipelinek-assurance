---
name: block-G-pipelinek-real-integration
description: Integración real de Assurance contra la distribución y el SDK actuales de PipelineK. NO añadir análisis nuevos. Demostrar que un suite real detiene un pipeline real.
---

# Bloque G — Integración real contra el SDK actual de PipelineK

**Goal:** Assurance se ejecuta dentro de una distribución
auténtica de PipelineK (instalada vía asdf) y `assurance.check`
detiene un pipeline real cuando un assertion `Mandatory` falla,
sin tocar el core de PipelineK, sin añadir tipos de análisis
nuevos y sin construir infraestructura especulativa.

**Release objetivo (tentativo):** `v0.13.0-rc1` si la integración
real cierra; si no, `v0.13.0-rc2` con la limitación documentada
honestamente y la UAT fallida adjunta.

**Precondición ya cumplida:** `v0.10.0-rc1` (consolidación
A–F, cerrado en-repo sobre `consolidation/A-F`). El auditor
externo lista R1 ("integridad del motor") como `v0.9.5-rc2`;
**ya está absorbido en `v0.10.0-rc1`**: la asociación de
resultados, los falsos PASS por evidencia ausente, la
identidad de assertions, los digests canónicos, el
normalizador y el harness de mutantes quedaron cerrados en
el tramo A. Reabrirlo sería duplicar trabajo.

**Este bloque NO es R1.** Este bloque ejecuta la **R2 ya
promovida a la realidad** (plugin ejecutable), la **R3**
(`assurance.verify` con body real) y la primera fila real
de **R5** (certificación contra distribución instalada).

## Autoridad

El productor de los contratos de integración es
`Rubentxu/pipeline-kotlin`, no la documentación histórica. Las
referencias canónicas son:

- `v2/pipeline-domain/.../step/StepRegistry.kt` — registro público
  de Steps.
- `v2/pipeline-domain/.../step/StepDefinitionContributor.kt` — SPI
  público de contribución.
- `v2/pipeline-domain/.../step/PluginManifest.kt` — manifest tipado
  del plugin.
- `v2/pipeline-application/.../PreLoadPluginAdmission.kt` —
  admisión previa de plugins (lo que el host acepta o rechaza
  ANTES de cargar la contribución).
- `v2/pipeline-application/.../PluginContributionVerifier.kt` —
  verificación de la contribución (lo que el host valida sobre
  la forma del Step después de cargada).
- `v2/pipeline-step-sdk/scm-git/` — referencia de un Step
  oficial de SDK (cómo se codifica un Step, cómo se enlaza con
  outputs tipados, cómo se descubren sus collaborators,
  cómo se publica identidad y procedencia).
- `examples/example-block-plugin/` — ejemplo canónico de un
  plugin block del propio repo. `RepeatBodyStepDefinition`
  es la **referencia directa** para `assurance.verify`.
- `examples/sdk-external-execution/` — ejemplo canónico de
  ejecución externa al SDK; reproduce el flujo
  `ServiceLoader` → admisión → verificación → invocación
  contra una distribución instalada.

**Versiones del SDK que importan:**

- `v0.48.0` — **baseline reproducible** de contrato e
  instalación. Ya está publicada. Es donde la matriz de
  compatibilidad ya tiene una fila.
- `v0.51.0-rc1` — matriz adelantada. **No** se asume
  estabilidad de sus APIs experimentales (inspección,
  control, recuperación, retención de output); sólo se
  certifica que el plugin sigue siendo admitido y
  ejecutable contra esa release candidate.

**NO** se usa como autoridad:

- La rama `s6-plugin-sdk` (170 commits por detrás de
  `main`, congelada, no representa el contrato actual).
- La documentación legacy en `docs/` de versiones
  anteriores.
- Suposiciones derivadas de cómo `MockSdkHost` se
  comporta — el Mock es un shim de tests, no un modelo
  de la distribución.

## Reglas de implementación (heredadas del usuario, refinadas
## por la auditoría del SDK en `a0f84e48`)

0. **Servicio del SDK vs Service Locator propio.** El
   plugin se descubre por `java.util.ServiceLoader`, que
   **es** la ruta pública del SDK y debe seguir usándose.
   Un *Service Locator* es un patrón distinto: un registro
   global o compartido donde los plugins van a buscar
   dependencias. **No** se introduce. Lo que sí se
   construye es un catálogo interno de Assurance
   (`FrozenAssuranceRuntime`) que **no** es un registro
   global y que el host no ve.
1. **No** crear un Step Registry alternativo ni un
   mecanismo nuevo de descubrimiento. Lo que existe en
   `StepRegistry` y `StepDefinitionContributor` es la
   única ruta para Steps.
2. Usar **el SPI público** de contribuciones, la **admisión
   previa** de plugins (`PreLoadPluginAdmission`), el
   **manifest tipado** (`PluginManifest`), las
   **registraciones con procedencia** (`StepRegistration`
   con metadatos de proveedor) y la **verificación de
   contribución** (`PluginContributionVerifier`). El
   manifest por sí solo no basta: sus declaraciones
   deben coincidir con lo que el JAR realmente aporta.
3. `assurance.check` se implementa como **Step atómico
   tipado** (`StepDefinition<I,O>` + `StepHandler`
   `suspend` con `TypedStepOutput` y `StepOutcome`).
   `assurance.verify` se implementa como **Step con
   `BodyContinuation`** (la continuación se obtiene vía
   `context.capabilities`, no se simula; la política es
   `BodyExecutionOwner.HANDLER_CONTINUATION`, secuencial,
   con `BODY_CONTINUATION_CAPABILITY`).
4. Los **registries de providers, lenses y assertions
   viven dentro de Assurance** como
   `FrozenAssuranceRuntime`, **congelados por
   evaluación**, y **no se acoplan al `StepRegistry` del
   host**. El host sólo ve dos Steps: `assurance.check` y
   `assurance.verify`.
5. Cero dependencias de `pipeline-application`,
   coordinador durable, journal, `ArtifactIndexCapability`
   ni clases internas del engine en el plugin. Los
   contratos del host llegan como `compileOnly`; el JAR
   del plugin **no** debe llevar una segunda copia de
   `pipeline-domain` (alinear versiones vía BOM
   `dev.rubentxu.pipeline.v2:pipeline-sdk-bom`).
6. **No** se duplica la semántica de retry, cancelación,
   identity o replay. PipelineK decide cuánto reintenta,
   cómo cancela, cómo identifica y cómo replaya.
   `BodyOutcome.Cancelled` se re-emite como
   `CancellationException` — **no** se reclasifica como
   `Failure` de Assurance. El fallo original del body
   **prevalece** sobre cualquier veredicto de
   evaluación posterior.
7. La ejecución de Assurance tiene **una sola ruta de
   orquestación** (`AssuranceOrchestrator.orchestrate`).
   No se introduce un segundo path "modo plugin" y un
   path "modo CLI" divergentes.
8. **Certificación antes de tipos nuevos.** Los invariantes
   de enforcement y evidencia se certifican antes de añadir
   un lens o un assertion nuevo. Esto evita falsos PASS
   donde la suite pasa porque la aserción no se evalúa.
9. **Digests verificables, procedencia distinta de
   identidad.** Tres digests separados:
   - `pluginOrigin`: identifica el código que generó
     el report (publisher + version + digest del JAR).
   - `evidenceDigest`: identifica los bytes de cada
     evidencia consumida.
   - `reportDigest`: identifica los bytes canónicos del
     report.
   Mezclarlos hace el report inexplicable.
10. **No se declara compatibilidad con una versión de
    PipelineK sin una UAT ejecutada contra esa versión
    instalada.** `SDK_COMPATIBILITY_MATRIX.md` se actualiza
    **después** del run, no antes. El plugin declara un
    `PipelineKApiRange` que **sólo** acepta las versiones
    certificadas; ampliar el rango no se hace por compilación
    exitosa, requiere admisión, runtime y UAT de ejecución
    ejecutados.
11. **Frontera de artefactos: usar `core.archiveArtifacts`
    + `ArtifactIndexCapability` del host para publicar el
    report.** Assurance **no** abre un puerto de escritura
    propio en el plugin. Si la integración demuestra que
    esa ruta no alcanza para un caso real, se documenta
    con la UAT fallida y se propone una evolución
    acotada, no se inventa la API en el plugin.

## Foco del bloque

El bloque NO abre los siguientes frentes (están explícitamente
fuera de alcance):

- Nuevos `Lens` o `Assertion` de análisis. El bloque usa los
  que ya existen (`BuiltinLens`, `BuiltinAssertion`) y los
  que ya están registrados como mutantes en
  `08-testing/MUTATION_CATALOG.md`.
- Nuevos providers de evidencia (CogniCode, Chronos, OTel).
  El bloque usa el provider `GraphProvider` local (ya
  implementado y certificado en B3).
- Self-hosting del plugin contra sí mismo. Eso es E4 y está
  bloqueado por C1 (externo). El bloque usa fixtures
  locales en `06-uat/fixtures/`.
- Publicación. F6 (v1.0.0-rc1) sigue bloqueado por C1, D1,
  D3 y F1 — eso no cambia con este bloque.

## Entregables (criterios de cierre)

El bloque se cierra **sólo** cuando se cumplen todos:

1. `pipelinek-assurance-plugin` se compila contra
   `dev.rubentxu.pipeline.v2:*:0.48.0` instalado vía asdf,
   usando **únicamente** los cuatro artefactos públicos
   listados arriba.
2. El JAR del plugin se instala en una distribución
   PipelineK real y `ServiceLoader.load(StepDefinitionContributor)`
   lo descubre. La admisión previa (`PreLoadPluginAdmission`)
   lo acepta. La verificación
   (`PluginContributionVerifier`) acepta las dos
   contribuciones.
3. Una pipeline de ejemplo (`ci/assurance.pipeline.kts`)
   ejecuta `assurance.check` con un suite de prueba que
   contiene un assertion `Mandatory` fallando. La pipeline
   se detiene con `StepOutcome.Failure` y un report
   íntegro. La ejecución se reproduce desde cero (clean
   clone, build, install, run).
4. El mismo ejemplo con un suite verde termina con
   `StepOutcome.Success`. El report es recuperable y
   verificable byte-a-byte.
5. `assurance.verify` se invoca como Step con
   `BodyContinuation` real, la continuación se ejecuta una
   vez, y la cancelación del host se propaga a la
   continuación. El report marca `BodyOutcome.Cancelled`
   si la cancelación llega a tiempo.
6. `SDK_COMPATIBILITY_MATRIX.md` se actualiza **después**
   del run real: la fila de 0.48.0 pasa de "✅ (mock)" a
   "✅ (real)" en cada celda que el run cubrió, y queda
   con "✅ (parcial)" donde el run no llegó.
7. Suite completa en limpio: `clean check` BUILD
   SUCCESSFUL, N tests, 0 fallos, 0 skipped, donde N es
   el número publicado en `RELEASE_NOTES`.
8. Cert de mutantes: 0 SURVIVED, los UNDER_KILLED
   documentados con su análisis (no se "cierran" como si
   fueran DEAD). Si hay SURVIVED, el bloque **no cierra**
   y se documenta la limitación honestamente.
9. La rama queda con **Conventional Commits atómicos**,
   integrada en `consolidation/A-F` (o rama de bloque) y
   pusheada. Tag y release candidate se crean **con
   recibo de tests, recibo de cert y matriz de
   compatibilidad actualizados en el mismo commit**.

## STOP conditions

El bloque se para y **no avanza** si:

- **`PreLoadPluginAdmission` rechaza el JAR.** Esto
  indicaría un manifest mal formado o un provenance
  inválido. No se reintenta con `-DskipVerifiers`; se
  corrige la causa raíz.
- **`PluginContributionVerifier` rechaza una
  contribución.** La forma del Step no cumple el contrato.
  No se cambia el verificador del host; se corrige el
  Step.
- Una pipeline real **no logra ejecutar** `assurance.check`
  end-to-end. No se documenta como "limitación del SDK"
  sin reproducir primero el ejemplo externo oficial
  (`examples/sdk-external-execution/`) y confirmar que
  tampoco funciona ahí.
- Aparecen SURVIVED en el cert que tocan la ruta de
  orquestación o el contrato de report. Si un SURVIVED
  toca `AssuranceOrchestrator` o `EvidenceArtifactCodec`,
  el bloque no se promueve.
- Hay que modificar `pipeline-application` o
  `pipeline-domain` para que el plugin funcione. Eso
  indicaría un l��mite del SDK, no del plugin.

## No-objetivos explícitos

- "Hacer que el plugin funcione con el SDK 0.47". El
  bloque certifica contra 0.48.0 (la versión instalada).
  0.47 es irrelevante; 0.49+ es trabajo futuro cuando
  exista upstream.
- "Añadir más capabilities al manifest". El manifest
  declara **dos** Steps. Añadir un tercero es un bloque
  nuevo.
- "Mejorar el report con UI". El report es JSON
  canonizado con digest. La UI es trabajo de F5 y vive
  en otro bloque.
- "Refactorizar `MockSdkHost` para que se parezca más al
  host real". `MockSdkHost` es un shim de tests; la
  fidelidad al host real se valida contra la
  distribución, no contra el Mock.

## Estructura del plan

El plan se detalla en
`odd/tasks/block-G-work-units.md`. Cada Work Unit es un
commit atómico, un test quirúrgico y un paso de UAT
verificable. La estructura general es:

- **G0** — pre-flight: confirmar SDK 0.48.0 **y**
  0.51.0-rc1 instalados vía asdf, fijar referencias
  (SHA del SDK y SHA del ejemplo
  `sdk-external-execution`), abrir rama de bloque
  (`consolidation/G-integration-v2`), anotar el SHA de
  `a0f84e48` como ancla.
- **G1** — eliminar dependencias de host internals en el
  plugin (regla 5). Reemplazar `pipeline-domain` no
  transitivo por alineación con BOM
  `pipeline-sdk-bom`; pasar los cuatro artefactos del
  host a `compileOnly`; verificar que el JAR publicado
  no incluye `pipeline-domain` (test
  `G1_jar_NO_contiene_pipeline_domain`).
- **G2** — introducir `FrozenAssuranceRuntime` como
  catálogo interno de Assurance (regla 4 + regla 0);
  eliminar el segundo path de orquestación (regla 7) y
  unificar en `AssuranceOrchestrator`. El test
  `G2_un_solo_orquestador` demuestra que el CLI, los
  tests y el plugin llaman al mismo `orchestrate()`.
- **G3** — `assurance.check` como Step atómico tipado
  contra la distribución real (regla 3, primer
  sub-punto). `StepDefinition<AssuranceCheckInput,
  AssuranceCheckOutput>` con `StepCodec` real, `StepHandler
  suspend`, `StepDescriptor` con `location`, `effects`,
  `capabilities`, `replayPolicy`. La política `MEMOIZED`
  sólo se activa si el fingerprint cubre motor + suite +
  evidences + versiones de adapters.
- **G4** — `assurance.verify` con `BodyContinuation`
  real (regla 3, segundo sub-punto; regla 6). Reflejar
  el patrón de `RepeatBodyStepDefinition`:
  `BodyExecutionOwner.HANDLER_CONTINUATION`, política
  secuencial, `BODY_CONTINUATION_CAPABILITY`. Orden
  fijo: validar suite y abrir ventana → invocar
  continuación una vez → preservar resultado del body
  → cerrar ventana → recoger y evaluar evidencia →
  publicar report combinando sin ocultar el fallo
  original. `BodyOutcome.Cancelled` se re-emite como
  `CancellationException`.
- **G5** — admisión y verificación del plugin
  (regla 2). `PluginManifest` generado desde una
  declaración tipada; el JAR pasa
  `PreLoadPluginAdmission`; las dos contribuciones
  pasan `PluginContributionVerifier`; las
  `StepRegistration` exponen publisher, version y
  capabilities. Test: `G5_admision_y_verificacion_pasan`
  contra 0.48.0 **y** 0.51.0-rc1.
- **G6** — pipeline de ejemplo ejecutable end-to-end con
  un suite que detenga el pipeline. Crea
  `ci/assurance.pipeline.kts` invocando el plugin
  instalado en una distribución asdf. Mide éxito
  (suite verde) y bloqueo (suite con Mandatory
  fallando). Publica el report vía
  `core.archiveArtifacts`.
- **G7** — cert de mutantes sobre la nueva ruta
  (regla 8; sin falsos PASS). Re-ejecuta los mutantes
  del catálogo que tocan `assurance.check` y
  `assurance.verify`; documenta `UNDER_KILLED` con su
  análisis; **SURVIVED bloquea el cierre** del bloque.
- **G8** — `SDK_COMPATIBILITY_MATRIX.md` actualizado
  post-run (regla 10). 0.48.0: cada celda del run
  real pasa de "✅ (mock)" a "✅ (real)"; celdas no
  cubiertas pasan a "✅ (parcial)". 0.51.0-rc1: nueva
  fila con la matriz de admisión/ejecución, sin asumir
  estabilidad de APIs experimentales.
- **G9** — release candidate `v0.13.0-rc1` con todos
  los recibos: tests, cert, matriz, JAR reproducible,
  SBOM, firma, SHA256SUMS, provenance. `PipelineKApiRange`
  declarado con las versiones certificadas.

## Decisiones pendientes del usuario (previas al inicio)

1. **¿Se ejecuta este bloque ahora**, o se espera a que
   C1, D1, D3 estén cerrados? El bloque no depende de
   esos items (usa fixtures locales), pero
   `v1.0.0-rc1` sí. El bloque se promueve a
   `v0.13.0-rc1` sin esperar a C1/D1/D3.
2. **Rama**: `consolidation/G-integration-v2` (sigue la
   nomenclatura de la consolidación A–F).
3. **SDK target**: certificar contra **0.48.0 y
   0.51.0-rc1**. 0.48.0 es el baseline reproducible;
   0.51.0-rc1 amplía la matriz sin asumir estabilidad de
   sus APIs experimentales. No se certifica contra
   `main` de `pipeline-kotlin` para generar un artefacto
   supuestamente compatible; `main` sólo se usa para
   detectar cambios futuros de contrato.
4. **¿Se mantiene `MockSdkHost`?** Sí, pero su rol
   queda explícitamente reducido: shim de tests, **no**
   modelo de integración. La fidelidad al host real se
   valida contra la distribución asdf.
5. **¿Se abre un puerto de escritura de artefactos en el
   plugin?** No por defecto. La primera integración
   usa `core.archiveArtifacts` del host. Si la UAT
   demuestra que no alcanza, se documenta con el caso
   concreto y se propone evolución.

## Estado

**Estado al crear el documento:** iniciativa propuesta,
sin commits. La precondición (v0.10.0-rc1) está en-repo
pendiente de push por decisión del usuario.
