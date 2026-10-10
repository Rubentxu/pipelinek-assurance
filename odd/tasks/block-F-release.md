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

## Estado (observado 2026-10-10, SHA `125304b`)

**Pendiente de A–E.**

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

- [ ] Benchmarks sobre corpus estático, 10k invocaciones
      Chronos, 100k trazas OTel, reports grandes, baselines
      extensas, repeticiones.
- [ ] Presupuestos sobre medidas reproducibles y comparables.

### F4 — Auditoría final de seguridad (en-repo)

- [ ] No deserialización arbitraria.
- [ ] No ejecución de código desde evidence payload.
- [ ] Límites de entrada y memoria.
- [ ] Protección frente a XML/JSON/CBOR malformados.
- [ ] Integridad de manifests y digests.
- [ ] Control de rutas dentro del workspace.
- [ ] Trust/provenance de los producers.
- [ ] Ausencia de falsas aprobaciones por errores de observación.

### F5 — Revisión arquitectónica (en-repo)

- [ ] Functional core puro.
- [ ] Providers sin authority de gate.
- [ ] Assertions separadas de Steps.
- [ ] Engine sin dependencias hacia implementaciones de providers.
- [ ] Plugin sin importaciones de `pipeline-application`.
- [ ] IDs tipados.
- [ ] Control estructurado de cancelación.
- [ ] Un único dueño del journal.
- [ ] Sin dependencia MCP en ejecución determinista.

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
