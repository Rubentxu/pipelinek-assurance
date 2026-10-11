---
name: block-G-work-units
description: Work units del Bloque G (integración real con el SDK actual). Cada unidad es un commit atómico, un test quirúrgico y un paso de UAT.
---

# Bloque G — Work units

Este documento convierte la iniciativa
`block-G-pipelinek-real-integration.md` en unidades
ejecutables. Cada unidad es un commit atómico
Conventional Commit, un test quirúrgico y un paso de
UAT verificable. La numeración es la del bloque G
(G0..G9).

## Reglas de cadencia

- **Una unidad por commit.** El mensaje es Conventional
  Commit y en español, con scope y motivo. Ejemplos:
  `chore(plugin): alinear con pipeline-sdk-bom` o
  `test(plugin): G1_jar_NO_contiene_pipeline_domain`.
- **Tests antes que código de producción**, salvo para
  G0 y G1 que son wiring y no admiten un test
  significativo sin lo que viene después.
- **Suite completa en cada gate**: `clean check` debe
  cerrar BUILD SUCCESSFUL con N tests, 0 failures,
  0 skipped, donde N es el publicado en
  `RELEASE_NOTES`.
- **Cada cert re-run actualiza `build/mutant-receipts/`**
  y **no** se considera verde si hay un solo `SURVIVED`
  en mutantes del scope tocado. `UNDER_KILLED` y
  `INFRA_FAILED` se documentan, no se cuentan como
  "certificados".
- **Push y merge bloqueados** hasta que G9 cierre. Las
  unidades G0..G8 se commitean en la rama de bloque y
  se pushean al final; **no** se mergea a `main` ni
  se taggea hasta que el gate del bloque esté verde.

## G0 — Pre-flight

**Objetivo:** confirmar la línea base de SDK y
distribución, fijar referencias reproducibles, abrir la
rama de bloque.

**Trabajo:**

1. Verificar `asdf list pipelinek` muestra
   `0.48.0` **y** `0.51.0-rc1` instalados.
2. Verificar que el ejemplo oficial
   `examples/sdk-external-execution` del repo
   `pipeline-kotlin` (anclado al SHA `a0f84e48`)
   compila y corre contra 0.48.0 desde cero (clean
   clone). Si falla, **STOP**: la integración con
   Assurance se basa en ese ejemplo.
3. Capturar:
   - SHA del commit del SDK (`a0f84e48`).
   - SHA del ejemplo `sdk-external-execution` en ese
     commit.
   - `~/.m2/repository/dev/rubentxu/pipeline/v2/`
     digests.
4. Crear rama `consolidation/G-integration-v2` desde
   `e3a2875` (HEAD de `consolidation/A-F`).
5. Crear `odd/tasks/block-G-evidence.md` con la
   tabla de anclas.

**Test:** ninguno (wiring). El gate de G0 es
**manual**: la UAT del ejemplo externo debe cerrar
verdes.

**Commit:**

```
chore(block-G): anclar SDK 0.48.0/0.51.0-rc1 y abrir rama de integración
```

## G1 — Wiring del plugin contra el BOM

**Objetivo:** alinear el plugin con el BOM público del
SDK, sin dependencias transitivas ni duplicación de
`pipeline-domain` (regla 5 + regla 11).

**Trabajo:**

1. Sustituir las dependencias explícitas en
   `pipelinek-assurance-plugin/build.gradle.kts` por
   alineación con BOM:
   `dev.rubentxu.pipeline.v2:pipeline-sdk-bom:0.48.0`
   (módulo `platform` o `enforcedPlatform`).
2. Pasar los cuatro artefactos del host a
   `compileOnly`: `pipeline-domain`,
   `pipeline-events`, `pipeline-output`,
   `pipeline-scripting-api`. (No son dependencia
   runtime: el host los provee.)
3. Excluir cualquier copia transitiva de
   `pipeline-domain` mediante
   `configurations.all { exclude(group = "dev.rubentxu.pipeline.v2", module = "pipeline-domain") }`
   en el plugin.
4. Publicar el JAR del plugin y verificar su contenido
   con `unzip -l`.

**Tests (quirúrgicos):**

- `G1_jar_NO_contiene_pipeline_domain`: el JAR no
  contiene clases del paquete
  `dev.rubentxu.pipeline.v2.domain.*`.
- `G1_jar_SI_contiene_assurance_check_y_verify`: el JAR
  sí contiene las dos contribuciones.
- `G1_classpath_compila_contra_BOM`: el JAR compila
  contra los cuatro `compileOnly` sin
  `ClassNotFoundException` ni `NoSuchMethodError` en
  tests.

**Commit:**

```
build(plugin): alinear con pipeline-sdk-bom y quitar copia de pipeline-domain
```

## G2 — `FrozenAssuranceRuntime` y un solo orquestador

**Objetivo:** introducir el catálogo interno de
Assurance (regla 0 + regla 4) y unificar la ejecución
en `AssuranceOrchestrator` (regla 7).

**Trabajo:**

1. Crear `FrozenAssuranceRuntime` en
   `pipelinek-assurance-plugin/.../runtime/`. Contiene:
   - `evidenceProviders: List<EvidenceProvider>`.
   - `lenses: Map<LensId, Lens>`.
   - `assertions: Map<AssertionId, Assertion>`.
   - `operatorRegistry: Map<Op, Operator>`.
   - Constructor que congela las colecciones
     inmutablemente (`List.copyOf`,
     `Map.copyOf`); cualquier intento de mutar
     post-construcción lanza.
2. Refactorizar el handler de `assurance.check` para
   resolver contra `FrozenAssuranceRuntime`; eliminar
   cualquier path que construya lenses o assertions
   lazy.
3. Eliminar la duplicación de orquestación: si el CLI
   tenía su propio pipeline de evaluación, **se
   borra** y delega a `AssuranceOrchestrator`. Si los
   tests directos construían un orquestador paralelo,
   se borra.
4. El `PluginManifest` declara la dependencia de
   `FrozenAssuranceRuntime` mediante metadata de
   provenance.

**Tests (quirúrgicos):**

- `G2_un_solo_orquestador`: el CLI, los tests de
  aceptación y el plugin llaman al mismo método
  `AssuranceOrchestrator.orchestrate(OrchestrationRequest)`.
  Verificable inspeccionando bytecode (`javap -c`) o
  por fitness test que cuenta referencias.
- `G2_runtime_es_inmutable`: cualquier intento de
  añadir un provider/lens/assertion post-build lanza
  `UnsupportedOperationException`.
- `G2_cert_no_recompila_lenses_por_evaluacion`: el
  fingerprint de una evaluación no incluye el orden
  interno del catálogo (la congelación no es
  observabilidad).

**Commit:**

```
refactor(plugin): FrozenAssuranceRuntime + un solo AssuranceOrchestrator
```

## G3 — `assurance.check` como Step atómico tipado

**Objetivo:** implementar `assurance.check` como
`StepDefinition<AssuranceCheckInput, AssuranceCheckOutput>`
real (regla 3, primer sub-punto).

**Trabajo:**

1. Definir `AssuranceCheckInput` y
   `AssuranceCheckOutput` con `@Serializable` y
   codecs `StepCodec<I>` / `StepCodec<O>`.
2. `AssuranceCheckStepDefinition` con
   `StepContract` (key, descriptor con location,
   effects, capabilities, replayPolicy), `StepCodec`
   y `StepHandler` `suspend` que:
   - recibe `StepHandlerContext`;
   - invoca `AssuranceOrchestrator.orchestrate`;
   - aplica enforcement (Mandatory → Failure);
   - devuelve `TypedStepOutput` con `StepOutcome`.
3. La política `MEMOIZED` se activa sólo si el
   fingerprint cubre: versión del motor + suite +
   evidenceDigests + versiones de adapters. Si falta
   uno, se cae a `DEFAULT` y se documenta en el
   log del Step.
4. Fallo Mandatory → `StepOutcome.Failure` con el
   counterexample estructurado, **sin** lanzar
   excepción.

**Tests (quirúrgicos):**

- `G3_check_typed_step_definition_ok`: el input
  serializa y deserializa via `StepCodec`.
- `G3_check_mandatory_bloquea`: una assertion
  Mandatory fallando produce `StepOutcome.Failure` y
  el handler no lanza.
- `G3_check_memoized_solo_con_fingerprint_completo`:
  sin evidenceDigests o sin versión de adapter, cae
  a `DEFAULT`.
- `G3_check_capability_ausente_impide_ejecucion`:
  sin `ASSURANCE_PROVIDER` capability, el Step
  retorna `Aborted` y el orquestador nunca corre.

**Commit:**

```
feat(plugin): assurance.check como Step atómico tipado
```

## G4 — `assurance.verify` con `BodyContinuation`

**Objetivo:** implementar `assurance.verify` con
body real vía `BodyContinuation` (regla 3, segundo
sub-punto; regla 6). Patrón tomado de
`RepeatBodyStepDefinition` del ejemplo oficial.

**Trabajo:**

1. `AssuranceVerifyStepDefinition` declara:
   - `BodyExecutionOwner.HANDLER_CONTINUATION`,
     política secuencial,
     `BODY_CONTINUATION_CAPABILITY`.
2. `StepHandler` `suspend` sigue el orden fijo:
   1. Validar suite y abrir ventana de observación.
   2. Invocar la continuación **una sola vez** vía
      `context.capabilities.bodyContinuation()`.
   3. Preservar el `BodyOutcome` original (Success,
      Failure, Cancelled) sin reclasificar.
   4. Cerrar ventana de observación.
   5. Recoger y evaluar evidencia (si la
      continuación lo produjo).
   6. Publicar report combinando el resultado del
      body y el veredicto de Assurance, **sin
      ocultar el fallo original**.
3. `BodyOutcome.Cancelled` se traduce a
   `CancellationException` para que el host lo
   propague; **no** se convierte en
   `StepOutcome.Failure`.
4. Replay y recovery respetan "una sola vez": la
   continuación no se vuelve a invocar en un
   replay/recovery.

**Tests (quirúrgicos):**

- `G4_verify_cuerpo_ejecuta_una_vez`: dos invocaciones
  del handler en la misma sesión no duplican el body.
- `G4_verify_cancelacion_no_es_failure`: el body
  cancelado se re-emite como `CancellationException`;
  el report marca `BodyOutcome.Cancelled`, no
  `StepOutcome.Failure`.
- `G4_verify_fallo_original_prevalece`: un body que
  lanzó `IllegalStateException` mantiene ese fallo
  aunque Assurance pase.
- `G4_verify_replay_no_duplica_efectos`: la ventana
  de observación se cierra tras la primera
  ejecución; un replay no la reabre.

**Commit:**

```
feat(plugin): assurance.verify con BodyContinuation real (HANDLER_CONTINUATION)
```

## G5 — Admisión y verificación del plugin

**Objetivo:** el JAR pasa `PreLoadPluginAdmission` y
las dos contribuciones pasan
`PluginContributionVerifier` (regla 2).

**Trabajo:**

1. Generar `PluginManifest` desde una declaración
   tipada: `api = pipeline-sdk-bom:0.48.0`,
   `publisher`, `version`, `contributions =
   [assurance.check, assurance.verify]`.
2. `StepRegistration` para cada Step expone
   publisher, version y capabilities.
3. Verificar contra el **host real instalado** vía
   asdf (no contra `MockSdkHost`):
   - `pipelinek plugin admit <jar>` retorna 0.
   - `pipelinek plugin verify-contributions <jar>`
     lista las dos contribuciones como `verified`.
4. Repetir 3 contra 0.51.0-rc1.

**Tests (quirúrgicos):**

- `G5_admision_pasa_contra_0_48`: el JAR es admitido
  por `PreLoadPluginAdmission` 0.48.0.
- `G5_verificacion_pasa_contra_0_48`: las dos
  contribuciones están en la lista de verificadas.
- `G5_admision_pasa_contra_0_51_rc1`: igual para
  0.51.0-rc1.
- `G5_manifest_inconsistente_es_rechazado`: un JAR
  con manifest mintiendo sobre sus capabilities es
  rechazado por `PluginContributionVerifier`.

**Commit:**

```
feat(plugin): manifest tipado + admisión/verificación reales contra 0.48.0 y 0.51.0-rc1
```

## G6 — Pipeline de ejemplo ejecutable

**Objetivo:** ejecutar `assurance.check` dentro de
una pipeline real de PipelineK (asdf) con dos casos:
suite verde y suite con Mandatory fallando (regla
"el primer objetivo es demostrar...").

**Trabajo:**

1. Crear `ci/assurance.pipeline.kts` que:
   - declara dependencias del plugin via
     `--plugin-jar`;
   - ejecuta `assurance.check` con un suite que
     contiene un assertion `Mandatory` sobre un grafo
     de evidencia válido.
2. Caso A: suite verde → la pipeline termina con
   `StepOutcome.Success`, el report se archiva vía
   `core.archiveArtifacts` y es consultable vía
   `ArtifactIndexCapability`.
3. Caso B: el mismo pipeline con un assertion
   `Mandatory` fallando → la pipeline termina con
   `StepOutcome.Failure`, el report es íntegro y
   consultable.
4. Documentar el procedimiento en
   `06-uat/examples/assurance-pipeline/README.md`.

**Tests (quirúrgicos):**

- `G6_pipeline_check_pasa_con_suite_verde`: el
  pipeline corre, exit=0, report indexable.
- `G6_pipeline_check_bloquea_con_mandatory_fallando`:
  el pipeline corre, exit≠0, report indexable con
  counterexample.
- `G6_report_digest_estable`: el digest del report
  es estable byte-a-byte en dos runs con los mismos
  inputs.
- `G6_report_recuperable_via_indice`: el report se
  recupera por digest vía `ArtifactIndexCapability`.

**Commit:**

```
test(uat): pipeline real de PipelineK con assurance.check verde y bloqueante
```

## G7 — Cert de mutantes sobre la nueva ruta

**Objetivo:** re-correr los mutantes del catálogo
que tocan `assurance.check` y `assurance.verify`,
sin falsos PASS (regla 8).

**Trabajo:**

1. Identificar mutantes del catálogo que tocan
   código del plugin (no del core): por inspección
   del `mutator_name` en cada receipt, agrupar por
   scope.
2. Re-correr esos mutantes. Esperar resultados:
   - `DEAD` con `killed ≥ 2`: el test de redundancia
     pasó.
   - `UNDER_KILLED`: un solo test killed; documentar
     qué test falta añadir.
   - `INFRA_FAILED`: el build falla con un error
     diagnosticable (null-safety, exhaustiveness);
     documentar el hallazgo.
   - `SURVIVED`: bloquea el cierre del bloque.
3. Actualizar `08-testing/MUTATION_CATALOG.md` con
   la fila `Resultado G7: ...` por mutante.

**Tests (quirúrgicos):** los mismos del cert
(`/tmp/cert-all.sh`).

**Commit:**

```
test(cert): re-correr mutantes del plugin contra HEAD de G
```

## G8 — `SDK_COMPATIBILITY_MATRIX.md` post-run

**Objetivo:** actualizar la matriz con los runs
reales (regla 10), sin anticipar compatibilidades.

**Trabajo:**

1. Para 0.48.0: cada celda de los runs G5 y G6
   pasa de "✅ (mock)" a "✅ (real)"; celdas no
   cubiertas pasan a "✅ (parcial)" con la nota
   específica.
2. Para 0.51.0-rc1: nueva fila con admisión y
   ejecución verificadas; las celdas de APIs
   experimentales quedan con "✅ (parcial)" y la
   nota "asume APIs no estables".
3. Anexar el SHA de los artefactos del host
   consumidos y los digests de los reports
   obtenidos.
4. **No** ampliar `PipelineKApiRange` más allá de
   las dos versiones certificadas.

**Tests:** ninguno (es una doc).

**Commit:**

```
docs(uat): SDK_COMPATIBILITY_MATRIX con filas reales 0.48.0 y 0.51.0-rc1
```

## G9 — Release candidate `v0.13.0-rc1`

**Objetivo:** promover a release candidate con
todos los recibos.

**Trabajo:**

1. `clean check` BUILD SUCCESSFUL, N tests, 0
   failures, 0 skipped; N publicado en
   `RELEASE_NOTES_v0.13.0-rc1.md`.
2. Cert de mutantes: 0 SURVIVED, los `UNDER_KILLED`
   e `INFRA_FAILED` documentados.
3. SBOM CycloneDX firmado, SHA256SUMS firmados,
   provenance del build.
4. JAR reproducible (mismo input → mismo JAR, vía
   `gradle properties`).
5. CLI empaquetado (`build-cli-dist.sh`).
6. `PipelineKApiRange` declarado
   `0.48.0 || 0.51.0-rc1`.
7. Tag `v0.13.0-rc1` creado **en el commit que
   tiene la matriz, los recibos y los docs
   sincronizados**, no antes.
8. Push y merge a `main` autorizados por el usuario.

**Tests:** la suite completa del bloque.

**Commit:**

```
chore(release): v0.13.0-rc1 con recibos + matriz firmada
```

## Riesgos conocidos antes de empezar

- **Catálogo de mutantes no toca el plugin.** El
  catálogo actual tiene 43 entradas, la mayoría
  apuntan a `assurance-domain` y `assurance-engine`.
  Sólo una fracción toca el código del plugin. Si
  esa fracción es ≤ 3, G7 es trivial y no vale
  como gate del bloque. Hay que mirar.
- **0.51.0-rc1 puede traer cambios incompatibles.**
  Si `PluginContributionVerifier` rechaza
  contribuciones que 0.48.0 aceptaba, el bloque no
  puede declarar compatibilidad con 0.51.0-rc1.
- **`examples/sdk-external-execution` puede no
  correr contra 0.48.0 instalado.** Si el ejemplo
  oficial falla, no es problema de Assurance, pero
  **STOP** igualmente: no se construye integración
  sobre una base rota.
- **Cobertura de capabilities:** el catálogo actual
  no tiene un mutante que ataque la
  `BODY_CONTINUATION_CAPABILITY` ausente. Si no se
  añade uno, G4 tiene un test débil. Hay que
  decidir si añadirlo o documentar la limitación.

## Decisiones heredadas del bloque

Ver `block-G-pipelinek-real-integration.md` §
"Decisiones pendientes del usuario". Por defecto:

- Se ejecuta ahora (no espera a C1/D1/D3).
- Rama: `consolidation/G-integration-v2`.
- SDK target: 0.48.0 + 0.51.0-rc1.
- `MockSdkHost` se mantiene como shim de tests.
- Sin puerto de escritura propio en el plugin;
  `core.archiveArtifacts` del host.
