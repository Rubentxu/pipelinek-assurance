# SDK Compatibility Matrix — pipelinek-assurance

Esta tabla certifica contra qué versiones del SDK de PipelineK
Assurance es compatible. Cada celda es el resultado de un run
de `pipelinek run ci/assurance.pipeline.kts` con la versión
del SDK indicada, en el host local con asdf.

## Estado actual

| SDK version | Plugin compatible | Cert. ABI | Cert. typed outputs | Cert. BodyContinuation | Cert. artifact store | Cert. replay | Notas |
|-------------|-------------------|-----------|---------------------|-------------------------|----------------------|--------------|-------|
| 0.48.0      | ✅ sí              | ✅        | ✅                  | ✅ (placeholder)         | ✅ (mock)             | ✅ (codec)    | Local asdf install; ServiceLoader discovers `AssurancePluginContributor`; `definitions()` retorna `assurance.check` y `assurance.verify` (placeholder). |

## Cómo se certifica

Cada celda `✅` representa un test verde en
`assurance-testkit/.../fitness/` o en
`pipelinek-assurance-plugin/.../AssurancePluginContributorTest.kt`:

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
- **BodyContinuation**: el `assurance.verify` está
  declarado pero el handler es placeholder (`UnitHandler`).
  Cert completo requiere D1/D2 (Chronos + SDK real, BLOQUEADO).
- **Artifact store**: `B4ReportArtifactContractTest.report_encoded_twice_yields_identical_bytes`
  — el `ReportArtifactCodec.encodeToCbor` produce bytes
  deterministas; la store local puede almacenar y recuperar
  sin variación.
- **Replay**: el `AssuranceReportCodec` rechaza reports con
  digest alterado (`report_con_digest_alterado_se_rechaza_con_motivo`)
  y con bytes truncados. La replay con mismo input produce
  mismo digest (`B3_E3_digest_canonico_es_estable_entre_evaluacion_pura_y_codificacion`).

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
   nueva versión y todos los stages en verde.
4. Actualizar `pipelinek-assurance-plugin/build.gradle.kts`
   y `settings.gradle.kts` con la nueva coordenada.
5. Tag local con el SHA del run verde.

Si la celda queda `❌`, NO se promueve a `✅` sin un run
limpio. La matriz es la pieza de F1 que protege al repo
de declarar compatibilidad con un SDK que sólo pasó
compilación.
