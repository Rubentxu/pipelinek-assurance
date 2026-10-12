# SDK Compatibility Matrix — pipelinek-assurance

Esta tabla certifica contra qué versiones del SDK de PipelineK
Assurance es compatible. Cada celda es el resultado de un run
real (o de un test equivalente si el run live está bloqueado
por un gap upstream — ver columna "Notas" y `docs/G6-FINDINGS.md`).

## Estado actual

| SDK version | Plugin compatible | Cert. ABI | Cert. typed outputs | Cert. BodyContinuation | Cert. artifact store | Cert. replay | Cert. live admission | Cert. live handler | Notas |
|-------------|-------------------|-----------|---------------------|-------------------------|----------------------|--------------|----------------------|---------------------|-------|
| 0.48.0      | ✅ sí              | ✅        | ✅                  | ✅ (typed DTO)          | ✅ (codec)            | ✅ (codec)    | ✅ (G6.1)             | ⚠️ (mock + SDK gap)  | Live admission verificada en `20fb3bf`; live handler **bloqueado** por gap upstream #1 (runtime capability pre-check sin SPI `RuntimeCapabilityContributor`). 203/0/0 verde. |
| 0.51.0-rc1  | ✅ sí              | ✅        | ✅                  | ✅ (typed DTO)          | ✅ (codec)            | ✅ (codec)    | ✅ (G6.1, con workaround) | ⚠️ (mock + SDK gap) | Workaround: JAR `http-0.51.0.jar` con `apiRange=[0.47.0, 0.49.0)` aislado en `/tmp/pk-051-min/lib/`. Live admission verificada; live handler bloqueado por gap #1 idéntico al 0.48.0. 203/0/0 verde. |

## Cómo se certifica

Cada celda `✅` representa un test verde o un run real:

- **ABI pública**: `F5_engine_NO_importa_paquete_plugin`,
  `F5_engine_NO_importa_paquete_providers` — el core del
  repo NO importa tipos del SDK; sólo el plugin lo hace.
- **Registro de plugin**: `serviceloader_del_sdk_descubre_el_contributor`
  — `ServiceLoader.load(StepDefinitionContributor::class.java)`
  encuentra `dev.pipelinek.assurance.plugin.AssurancePluginContributor`
  dado el classpath del test.
- **Typed outputs**: `B4ReportArtifactContractTest` (7 tests)
  — el `AssuranceCheckStepDefinition.Output` se codifica
  via `OutputCodec` (kotlinx.serialization) y sobrevive un
  roundtrip con digest estable.
- **BodyContinuation**: `assurance.verify` declarado con
  handler real (no placeholder); `BODY_CONTINUATION_CAPABILITY`
  en `requiredCapabilities` (G4).
- **Artifact store**: `B4ReportArtifactContractTest.report_encoded_twice_yields_identical_bytes`
  — el `ReportArtifactCodec.encodeToCbor` produce bytes
  deterministas.
- **Replay**: el `AssuranceReportCodec` rechaza reports con
  digest alterado y con bytes truncados. La replay con
  mismo input produce mismo digest
  (`B3_E3_digest_canonico_es_estable_entre_evaluacion_pura_y_codificacion`).
- **Live admission** (G6.1, commit `20fb3bf`): `pipelinek run`
  con el JAR instalado en `~/.asdf/installs/pipelinek/<ver>/lib/`
  admite el plugin, parsea el `META-INF/pipelinek/plugin-manifest.json`,
  descubre los steps vía ServiceLoader y llega al step dispatch.
- **Live handler** (G6.2, commits `72ade5f`..`dc03f00`):
  el handler **no se ejecuta** contra el SDK live por dos
  gaps upstream pinneados honestamente en
  `docs/G6-FINDINGS.md` (addenda v1 y v2). La lógica del
  handler está certificada por `MockSdkHost`:
  `AssuranceCheckEndToEndTest` + `G3AssuranceCheckStepTest`.

## Gaps upstream pinneados (no workarounds)

1. **Runtime capability pre-check sin SPI** (gap #1): el
   SDK 0.48.0 / 0.51.0-rc1 impone un pre-check de
   capabilities que aborta la ejecución del step con
   `missing required capabilities for step 'assurance.check':
   assurance.provider, assurance.assertions`. No existe la
   SPI `RuntimeCapabilityContributor` en el bytecode del
   SDK. El plugin declara las capabilities en el manifest
   (correcto) pero el host no las publica.
2. **Kts script classpath excluye el plugin** (gap #2): el
   compilador de `.kts` del SDK no incluye el JAR del plugin
   en su classpath. Cualquier `import
   dev.pipelinek.assurance.plugin.*` falla con `Unresolved
   reference`. El plugin se descubre a runtime vía
   ServiceLoader, pero el compiler no resuelve símbolos de él.

Ambos gaps están documentados en `docs/G6-FINDINGS.md` con
comandos de reproducción. No se aplican workarounds que
romperían AAT-3 ni `G3_V36`.

## Versiones futuras (PENDIENTE de release formal upstream)

| SDK version | Estado | Notas |
|-------------|--------|-------|
| 0.48.0-rc1  | ❌     | No certificado: la release rc1 estuvo en asdf pero no se ejecutó el suite contra ella. |
| 0.48.0-rc2  | ❌     | Instalado en asdf; no certificado formalmente porque SDK 0.48.0 (stable) es lo que el repo tiene en `~/.m2/repository/`. |
| 0.49.x      | ❌     | No publicado. |
| 0.50.x      | ❌     | No publicado. |

## Política de promoción

Una nueva versión del SDK se agrega a esta matriz sólo
después de:

1. Publicar la release firmada y reproducible (upstream).
2. Publicar en `~/.m2/repository/` o el repo Maven remoto
   (upstream).
3. Correr `pipelinek run ci/assurance.pipeline.kts` con la
   nueva versión y todos los stages en verde, **o**
   documentar honestamente qué celda queda en
   `⚠️ (mock + SDK gap)` y por qué.
4. Actualizar `pipelinek-assurance-plugin/build.gradle.kts`
   y `settings.gradle.kts` con la nueva coordenada.
5. Re-correr los mutantes del scope tocado (regla del
   Bloque G unidad G7).
6. Tag local con el SHA del run verde.

Si la celda queda `❌`, NO se promueve a `✅` sin un run
limpio. La matriz es la pieza de F1 que protege al repo
de declarar compatibilidad con un SDK que sólo pasó
compilación.

## Cert artifacts

- **Mutant receipts (Bloque G, G7)**: `build/mutant-receipts/*.json`
  (43 archivos, SHA `6084fcb`). Tally: 40 DEAD, 3 INFRA_FAILED,
  0 SURVIVED. Ver `odd/tasks/block-G-G6-status.md` §G7 para
  el detalle por mutante y `08-testing/MUTATION_CATALOG_CERTIFICATION_2026-10-10.md`
  para la tabla de veredictos.
- **Live run logs (G5, G6.1)**: `docs/G6-FINDINGS.md` §
  "Commands to reproduce" contiene los comandos exactos.
- **SDK_COMPATIBILITY_MATRIX SHA**: este documento se
  commitea con la matriz sincronizada al SHA certificado.
