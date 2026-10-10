---
name: block-B-pipelinek-plugin
description: Feature document for Bloque B — primer plugin PipelineK realmente ejecutable. Source of truth for v0.10.0-rc1.
---

# Bloque B — Primer plugin PipelineK realmente ejecutable

**Goal:** un usuario puede instalar el JAR de Assurance en una
distribución auténtica de PipelineK y ejecutar `assurance.check`
sin cambiar una línea del core.

**Release objetivo:** `v0.10.0-rc1`

**Precondición:** Bloque A cerrado (v0.9.5-rc1). SDK público de
PipelineK con versión verificable.

**Tag:** `v0.10.0-rc1` creado localmente sobre `consolidation/A-F`
(2026-10-10, 28 commits sobre v0.9.5-rc1). Push queda al usuario.

## Estado (observado 2026-10-10, post-B1)

**B1 cerrado (en-repo + SDK local)**: el plugin ahora
declara dependencias reales contra
`dev.rubentxu.pipeline.v2:{pipeline-domain,
pipeline-events, pipeline-output, pipeline-scripting-api}:0.48.0`.
La integración se resuelve vía mavenLocal() + flatDir sobre
`~/.asdf/installs/pipelinek/0.48.0/lib/`. SDK se publica
en `~/.m2/repository/` desde la instalación asdf. El
`./gradlew test` corre contra el SDK real.

**B2, B3, B4 cerrados (en-repo, sin bloqueador)**:
- B2: `AssuranceOrchestrator` con los 10 pasos y 9 tests.
- B3: `BuiltinLens` + `BuiltinAssertion` + `assurance.check` real
  end-to-end. `AssuranceCheckStepDefinition.run` overload que
  delega al orchestrator. 8 E2E tests.
- B4: contrato de report artifact (escritura completa, digest
  estable, replay reproducibilidad, rechazo de incompleto).
  7 tests en `B4ReportArtifactContractTest`.

## Sub-tareas

### B1 — Integración con SDK público (cerrado 2026-10-10)

- [x] Tomar `pipeline-kotlin` como productor de los contratos
      (referencias: `v2/pipeline-domain/.../step/StepDefinitionContributor.kt`,
      `examples/example-uppercase-plugin`, `examples/example-block-plugin`,
      `v2/pipeline-step-sdk/http/.../HttpStepDefinitionContributor.kt`).
      SDK 0.48.0 instalado en asdf, JARs en
      `~/.m2/repository/dev/rubentxu/pipeline/v2/`.
- [x] Convertir `AssurancePluginContributor` en una implementación
      real de `StepDefinitionContributor`.
      (SHA `c9ff955` + `f955782`.)
- [x] Implementar contratos, codecs, handlers, registro y
      metadata del plugin.
      `AssuranceCheckStepDefinitionImpl : StepDefinition<I,O>`;
      `AssuranceCheckStepHandler : StepHandler<I,O>` (suspend
      `execute`); `StepCodec<I>` con `EncodedStepValue`.
- [x] Eliminar el uso de `List<Any>` como falsa integración
      tipada. Ahora `definitions: Iterable<StepDefinition<*, *>>`.
- [x] `ServiceLoader` carga las clases; el host rechaza
      contribuciones malformadas.
      Test `serviceloader_del_sdk_descubre_el_contributor` verde
      sobre el SDK real 0.48.0.
- [x] Consumir la versión publicada del SDK.
      `pipelinek-assurance-plugin/build.gradle.kts` con
      `dev.rubentxu.pipeline.v2:pipeline-{domain,events,output,scripting-api}:0.48.0`
      vía mavenLocal() + flatDir.

### B2 — Application Service (en-repo, sin bloqueador)

- [x] Resolver suite (`AssuranceOrchestrator.orchestrate` paso 1,
      `validateSuite`).
- [x] Leer referencias a artifacts (paso 2-3, `validateEvidenceRefs`).
- [x] Validar esquema y digest (paso 3, `require` de 64 hex).
- [x] Seleccionar providers (paso 4, `providers.find`).
- [x] Recolectar evidencia (paso 5, `provider.collect`).
- [x] Normalizar (paso 6, `EvidenceNormalizer.normalize`).
- [x] Congelar registries de lenses/assertions (paso 7,
      `runtime.lenses`).
- [x] Evaluar (paso 8, `AssuranceEngine.evaluateSuite`).
- [x] Codificar y publicar report (paso 9, `report.suiteDigest`).
- [x] Devolver resultado tipado (paso 10, `OrchestrationResult`).

(SHA `aa7e5c0`, 9 tests en `AssuranceOrchestratorTest`.)

### B3 — `assurance.check` real (en-repo, sin bloqueador)

- [x] Sustituir el runtime vacío de
      `AssuranceCheckStepDefinition.run` (SHA `d497d41`).
- [x] Verificar que ejecuta las lenses y assertions registradas
      (8 E2E tests PASSED, ver
      `AssuranceCheckEndToEndTest`).
- [x] Ejemplo reproducible: grafo correcto (Success) +
      dependencia prohibida (Failure con counterexample + report).
- [x] Fachada Kotlin DSL que baje a Step primitives públicos
      (`AssuranceCheckStepDsl`, 8 tests en
      `AssuranceCheckStepDslTest`).
- [x] NO activar `assurance.verify` como capacidad anunciada.

### B4 — Artifact y replay (en-repo, sin bloqueador)

- [x] Contrato genérico de report artifact con escritura
      completa, digest, referencia estable (7 tests PASSED,
      `B4ReportArtifactContractTest`).
- [x] Fingerprint + mismos artifacts → mismo veredicto
      (roundtrip preserva `summary`).
- [x] Report incompleto NO se publica como válido
      (digest alterado, bytes truncados y garbage rechazados
      con `ArtifactDecodeException`).

## Acceptance

- Plugin compilado contra SDK público real.
- ServiceLoader real.
- Instalación sobre distribución PipelineK.
- `assurance.check` en pipeline real.
- Mandatory bloquea.
- `ReportOnly` no bloquea.
- Replay reproduce.
- Crash durante publicación no expone report parcial.
- Cero cambios específicos de Assurance en core de PipelineK.

**UAT:** 008, 009, 023, 024, 025.
**AAT:** 3, 10, 11, 12, 14.

## STOP

Plugin sólo funciona en MockSdkHost o requiere dispatcher
específico en core PipelineK.

## Cierre (objetivo)

- `v0.10.0-rc1` con JAR instalable, ejemplo ejecutable, reporte
  de compatibilidad y evidencia de run real.
- Sólo se promueve cuando B1 está verde.

**Estado 2026-10-10:** tag `v0.10.0-rc1` creado localmente sobre
`consolidation/A-F`. B1, B2, B3, B4 cerrados. Pendiente push del
tag y merge a `main` (decisión del usuario).
