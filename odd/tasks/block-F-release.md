---
name: block-F-release
description: Feature document for Bloque F — certificación de producción y primera candidata 1.0. Source of truth for v1.0.0-rc1.
---

# Bloque F — Certificación de producción y primera candidata 1.0

**Goal:** candidata de producción instalable, verificable,
auditable y reproducible sobre una distribución de PipelineK,
con trazabilidad desde el source commit hasta cada veredicto.

**Release objetivo:** `v1.0.0-rc1`

**Precondición:** A–E integrados, publicados y con sus
recibos remotos verificados.

## Estado (observado 2026-10-10, post-F4)

**F4 cerrado (en-repo, parcial)**: 9 tests en
`F4SecurityAuditTest` cubriendo las 8 garantías del plan:
- F4_01: no deserialización arbitraria.
- F4_03: límites de entrada y memoria.
- F4_04: protección frente a CBOR malformados.
- F4_05: integridad de manifests y digests (evidence,
  report, pack).
- F4_07: trust/provenance de los producers (la enum
  EvidenceAuthority NO admite authorities inventadas).
- F4_08: ausencia de falsas aprobaciones (Failed NO se
  confunde con Produced).

**F2, F3, F5 pendientes en-repo**:
- F2 (SBOM + publicación): parcialmente cubierto por A5
  (CycloneDX en CI, `osv-scanner` v2.6.0, SHA256SUMS
  pendientes de generar como artefacto descargable).
- F3 (perf): parcialmente cubierto por
  `tools/measure-performance.sh` (A5).
- F5 (revisión arquitectónica): parcialmente cubierto por
  `M3PluginModuleFitnessTest` (AAT-3) y `M4DiffLawsTest`
  (AAT-18).

**F1 y F6 BLOQUEADOS por SDK real** (B1).

## Sub-tareas

### F1 — Matriz de compatibilidad (BLOQUEADO por SDK real)

- [ ] Probar Assurance con versiones del SDK de PipelineK,
      comenzando por 0.48.0-rc2.
- [ ] Certificar por versión: ABI pública, registro de plugin,
      typed outputs, BodyContinuation, artifact store, replay,
      error y cancellation propagation.
- [ ] No declarar compatibilidad con versión que sólo
      superó compilación.

### F2 — Certificación de supply chain (en-repo)

- [ ] SBOM CycloneDX del árbol transitivo real.
- [ ] Publicar JAR/distribución del plugin.
- [ ] Publicar CLI empaquetada, SBOM, SHA256SUMS, firmas
      verificables, provenance del build.
- [ ] Matriz de compatibilidad.
- [ ] Recibo de tests y mutantes.
- [ ] Verificar firmas en modo estricto.

### F3 — Performance y robustez (en-repo + fixtures)

- [x] Benchmarks sobre corpus estático
      (`F3EngineBenchmarkTest` con 1k y 10k modules).
- [ ] 10k invocaciones Chronos — depende del export real
      de Chronos (D1, BLOQUEADO).
- [ ] 100k trazas OTel — depende del collector real (D3,
      BLOQUEADO).
- [x] Reports grandes: codificación de 500 failed en <2s
      (medido: 24ms).
- [x] Repeticiones: digest estable a través de 5 invocaciones
      consecutivas.
- [x] Presupuestos sobre medidas reproducibles y comparables
      (baselines: 1k=79ms, 10k=5s, 500-report=24ms).

### F4 — Auditoría final de seguridad (en-repo)

- [x] No deserialización arbitraria (F4_01: bytes no-CBOR
      y CBOR que no es del tipo esperado se rechazan con
      `ArtifactDecodeException`).
- [x] No ejecución de código desde evidence payload (el
      payload es `Map<String, String>`, no un blob
      ejecutable; el codec nunca interpreta su contenido).
- [x] Límites de entrada y memoria (F4_03: `MAX_INPUT_BYTES`
      se aplica antes de deserializar; `MAX_STRING_LENGTH`,
      `MAX_COLLECTION_SIZE`, `MAX_NESTING_DEPTH` se aplican
      sobre el DTO).
- [x] Protección frente a CBOR malformados (F4_04: bytes
      truncados y bytes garbage se rechazan con motivo
      concreto).
- [x] Integridad de manifests y digests (F4_05: digest
      alterado en evidence, report y pack se rechaza con
      `ArtifactDecodeException` mencionando `digest`).
- [x] Control de rutas dentro del workspace (delegado a
      el caller del plugin; el plugin NO abre paths
      arbitrarios — los providers son inyectados).
- [x] Trust/provenance de los producers (F4_07: la enum
      `EvidenceAuthority` NO admite authorities
      inventadas; AAT-19 enforce el resto en el normalizer).
- [x] Ausencia de falsas aprobaciones por errores de
      observación (F4_08: `Failed` NO se confunde con
      `Produced` a nivel de tipo; el normalizer lo
      rechaza como tal, no lo "asume vacío").

### F5 — Revisión arquitectónica (en-repo)

- [x] Functional core puro (engine no hace I/O: ningún
      `FileInputStream`, `URL`, `Socket` en `assurance-engine`).
- [x] Providers sin authority de gate (los providers sólo
      recolectan; el gate es del Step handler).
- [x] Assertions separadas de Steps (`AssuranceAssertion`
      en engine, no depende del plugin).
- [x] Engine sin dependencias hacia implementaciones de
      providers (engine NO importa `assurance.providers.*`).
- [x] Plugin sin importaciones de `pipeline-application`
      (AAT-3 elevado a F5).
- [x] IDs tipados (`SuiteId`, `AssertionId`, `LensId`,
      `FindingId` son value classes).
- [x] Control estructurado de cancelación
      (`BodyOutcome.Cancelled` y `StepOutcome.Cancelled`
      son subtipos).
- [x] Un único dueño del journal (plugin NO escribe
      `JournalWriter`; el SDK es el dueño).
- [x] Sin dependencia MCP en ejecución determinista
      (ningún módulo importa `io.modelcontextprotocol`
      o `io.grpc`).

9 tests en `F5ArchitecturalReviewTest`.

### F6 — Release candidate (BLOQUEADO por F1–F5)

- [ ] Congelar código y configuración.
- [ ] Suite completa en limpio.
- [ ] Certificar mutantes en aislamiento.
- [ ] Crear RC sólo si el gate está verde.
- [ ] Promoción posterior exige nueva certificación.

## Acceptance

- CI completo verde.
- Todos los UAT aplicables con evidencia.
- Todos los AAT verdes.
- Ningún P0/P1 abierto sin decisión explícita.
- Mutaciones certificadas sin informes obsoletos.
- Crash, replay y cancelación E2E.
- Matriz SDK real.
- SBOM y firmas verificables.
- Instalación desde artefactos publicados.
- RC verificada mediante descarga independiente.

## STOP

Gate sin ejecución real, incompatibilidad SDK, o artefacto
cuya procedencia no pueda reproducirse.

## Cierre (objetivo)

`v1.0.0-rc1` integrado en `origin/main`, publicado con todos
los artefactos y criterios pendientes documentados para
promoción estable.
