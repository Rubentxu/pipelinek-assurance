# Recibo R4 — Observabilidad, runners y CLI agent-first

**Bloque:** R4 — M8 + M9.
**Release propuesta:** `v0.5.0-beta.1`.
**Fecha:** 2026-10-10.
**Estado:** **BLOQUEADO** parcialmente.

## Bloqueador

R4.1 exige consumir evidencia OTel con un collector en vivo
produciendo `runtime.invocation-chain` y `observability.trace`.
El repo público no incluye el collector. La lógica consumidora
está hecha y certificada (M-O01 killed=2).

R4.5 (paridad de ejecutores) y R4.6 (release instalable) están
hechos localmente: `MultiRunnerAssertions` mapea los 5 veredictos
por Kotest y JUnit; el CLI `assure` ejecuta `report`,
`explain`, `evidence path` con respuesta HATEOAS.

## Trabajo ejecutado

- `OtelArtifactProvider` con `OTelTraceId` / `OTelSpanId` (AAT-13).
- Spans con trace → ok; spans sin trace → `RawGapReason.Unknown`.
- Correlación textual con otro ID no produce colisión semántica
  (UAT-018 cubierta por la forma canónica del codec).
- M-O01 certificado con redundancia 2.
- `MultiRunnerAssertions` para Kotest + JUnit Platform. 5
  veredictos mapeados: `Passed`, `Failed`, `Inconclusive`,
  `Unsupported`, `Error`.
- Paridad de digest: el mismo `AssuranceReport` codificado por
  el pipeline directa y por el adapter JUnit produce el mismo
  digest.
- CLI `assure` con dispatch por prefijo más largo (no literal
  `"evidence path"`), fall-closed en fixtures ilegibles.

## UAT cubiertos

- UAT-017 (OTel missing span): la lógica emite gap. E2E con
  collector pendiente.
- UAT-018 (OTel identity separation): AAT-13 verde por
  construcción; value classes con tipos distintos.
- UAT-020 (JUnit parity): cubierta por `MultiRunnerAssertionsTest`.
- UAT-021 (Agent discoverability): cubierta por el flujo
  `report → evidence path / explain` ejercitado en
  `CliDispatchTest` y en los recibos de R0.

## Bloqueador restante

- UAT-017 con collector OTel en vivo (BLOQUEADO).
- UAT-020 con tres runners (Kotest/JUnit/Pure) reportando el
  mismo digest E2E: lógica cubierta; runners reales pendientes.

## Estado del bloque

BLOQUEADO para los UAT que requieren collector y runners reales.
La lógica consumidora y el CLI están publicados en R0. La
matriz de paridad de ejecutores queda como M11 segundo pase
ejecutable.
