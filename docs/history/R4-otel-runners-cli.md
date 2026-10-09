# Recibo R4 — Observabilidad, runners y CLI agent-first

**Bloque:** R4 — M8 + M9.
**Release propuesta:** `v0.5.0-beta.1`.
**Fecha:** 2026-10-10.
**SHA integrado:** `d2d1d15`.

## R4.1 — ObservabilityLens (OTel collector real) ✅

**Desbloqueado** con un test harness realista basado en `podman`
+ la imagen oficial `docker.io/otel/opentelemetry-collector:0.96.0`.

### Componentes del harness

- `/var/home/rubentxu/Proyectos/kotlin/PipelinekAssurance/pipelinek-assurance-blueprint/tools/otel-harness/collector-config.yaml`
  — OTLP HTTP receiver en `:4318`, processor batch + memory_limiter,
  exporter `file` a `/exports/otel-export.jsonl`.
- `/var/home/rubentxu/Proyectos/kotlin/PipelinekAssurance/pipelinek-assurance-blueprint/tools/otel-harness/emit-traces.py`
  — cliente Python que postea trazas OTLP/HTTP JSON v1. Tres modos:
  - `normal`: traza con 1 root + 2 children (traceId + parentSpanId).
  - `partial`: 1 span con `traceId=""` (propagación parcial).
  - `all`: ambos.
- `/var/home/rubentxu/Proyectos/kotlin/PipelinekAssurance/pipelinek-assurance-blueprint/tools/otel-harness/run-collector.sh`
  — `start` / `stop` / `status` del collector vía `podman run`.
  Volume bind a `tools/otel-harness/output/` para que el export
  sobreviva a `gradle clean check`.

### Test E2E (OtelHarnessTest, 5 tests, todos verdes)

- `el_collector_real_produce_export_legible_por_el_provider`:
  consume el output real del collector (no un JSON sintético).
- `la_traza_normal_emite_items_con_observability`: regex detecta
  traceId de 32 hex chars.
- `el_provider_no_retorna_assertion_result_con_collector_real`:
  AAT-6 verde con el JSON real.
- `el_provider_no_falla_con_formato_real_del_collector`: caso
  vacío → `Failed` (M-O01).
- `el_provider_reconoce_traceId_y_spanId_separados_aun_con_texto_real`:
  traceId y spanId como entidades distintas, no fundidas.

### Acceptance local

- UAT-017 (OTel missing span): cubierto por la lógica; el harness
  emite el caso `partial` que el provider debe reportar como gap.
- UAT-018 (OTel identity separation): AAT-13 verde por
  construcción; namespaces `OTelTraceId` / `OTelSpanId` distintos.

### Cómo se corre localmente

```bash
# 1. Arrancar el collector
./tools/otel-harness/run-collector.sh start

# 2. Emitir trazas
python3 tools/otel-harness/emit-traces.py --mode all

# 3. Verificar el output
ls -la tools/otel-harness/output/
cat tools/otel-harness/output/otel-export.jsonl | head -c 500

# 4. Correr los tests E2E
./gradlew :assurance-providers:test --tests "*OtelHarness*" --rerun-tasks
```

### Cómo se incorpora al CI

El setup se documenta en el recibo. El CI de GitHub Actions
puede arrancar el collector con la misma imagen antes del
`clean check`. El export queda en `tools/otel-harness/output/`
y se commitea con `--rerun-tasks` para que los golden bytes
sean reproducibles entre runs.

## R4.2 — JUnit Platform y Kotest ✅ (lógica)

- `MultiRunnerAssertions` mapea `AssertionResult` a
  `AssertionError` / `TestAbortedException` (Kotest) y a
  `AssuredTestEngine` (JUnit Platform).
- 5 veredictos: `Passed`, `Failed`, `Inconclusive`, `Unsupported`,
  `Error`. Cobertura por `MultiRunnerAssertionsTest`.

**Pendiente E2E:** la paridad de tres runners (Kotest / JUnit /
Pure) ejecutando la misma suite con el mismo digest no se ha
corrido fuera del proceso único de Gradle. Eso requiere un
host con los tres runners en PATH o un worktree por runner,
que está fuera de R4.1 (que solo resolvía el collector OTel).

## R4.3 — CLI agent-first ✅

- CLI `assure` con dispatch por prefijo más largo (no literal
  `"evidence path"`).
- Comandos: `report`, `explain`, `evidence path`.
- Fall-closed en fixtures ilegibles (`runCatching` reemplazado
  por `getOrElse` con envelope).
- Salida con relaciones HATEOAS para que un agente navegue sin
  parsear stdout.

## R4.4 — Recorrido del agente ✅ (simulado)

`CliDispatchTest` simula el recorrido: el agente recibe un
envelope, navega por las `actions`, y llega al contraejemplo
sin tocar `stdout` humano. La transcripción real (Claude /
Aider) está pendiente del binario distribuible de la CLI
(parte de R4.6 → v1.0.0).

## R4.5 — Paridad de ejecutores 🚫 (parcial)

Lógica cubierta por `MultiRunnerAssertionsTest`. E2E con
tres runners reales queda pendiente: necesito un host donde
los tres runners coexistan en PATH. En el host actual corre
Kotest/JUnit (vía Gradle). Pure runner = `assure report`,
ya verificado en R0.

## R4.6 — CLI instalable 🚫 (diferido a v1.0.0)

`install.sh` copia el JAR. El binario distribuible (tarball,
deb, rpm, container) con manpage, checksum firmado y notas
de compatibilidad es trabajo de release explícito; no es
cerrable en este ciclo.

## Estado del bloque

- R4.1 ✅ desbloqueado localmente con `podman`.
- R4.2 ✅ lógica.
- R4.3 ✅ CLI.
- R4.4 ✅ simulado en `CliDispatchTest`.
- R4.5 🚫 pendiente host con tres runners.
- R4.6 🚫 diferido a v1.0.0.

**El bloque R4 cierra la mitad con la release v0.5.0-beta.1.**
La otra mitad queda explícita como pendiente de host con
infraestructura completa de runners y binario distribuible.
