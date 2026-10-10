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

## Estado (observado 2026-10-10, post-B4)

**B1 sigue BLOQUEADO** por externo (SDK v0.48.0-rc2).

**B2, B3, B4 cerrados (en-repo, sin bloqueador)**:
- B2: `AssuranceOrchestrator` con los 10 pasos y 9 tests.
- B3: `BuiltinLens` + `BuiltinAssertion` + `assurance.check` real
  end-to-end. `AssuranceCheckStepDefinition.run` overload que
  delega al orchestrator. 8 E2E tests.
- B4: contrato de report artifact (escritura completa, digest
  estable, replay reproducibilidad, rechazo de incompleto).
  7 tests en `B4ReportArtifactContractTest`.

## Sub-tareas

### B1 — Integración con SDK público (BLOQUEADO)

- [ ] Tomar `pipeline-kotlin` como productor de los contratos
      (referencias: `v2/pipeline-domain/.../step/StepDefinitionContributor.kt`,
      `examples/example-uppercase-plugin`, `examples/example-block-plugin`,
      `v2/pipeline-step-sdk/http/.../HttpStepDefinitionContributor.kt`).
- [ ] Convertir `AssurancePluginContributor` en una implementación
      real de `StepDefinitionContributor`.
- [ ] Implementar contratos, codecs, handlers, registro y
      metadata del plugin.
- [ ] Eliminar el uso de `List<Any>` como falsa integración
      tipada.
- [ ] `ServiceLoader` carga las clases; el host rechaza
      contribuciones malformadas.
- [ ] Consumir la versión publicada del SDK.

### B2 — Application Service (en-repo, sin bloqueador)

- [ ] Resolver suite.
- [ ] Leer referencias a artifacts.
- [ ] Validar esquema y digest.
- [ ] Seleccionar providers.
- [ ] Recolectar evidencia.
- [ ] Normalizar.
- [ ] Congelar registries de lenses/assertions.
- [ ] Evaluar.
- [ ] Codificar y publicar report.
- [ ] Devolver resultado tipado.

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
