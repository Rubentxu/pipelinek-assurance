---
name: block-pending-external
description: Registro consolidado de bloqueadores externos al tramo de consolidación A-F. Source of truth para saber qué falta y por qué.
---

# Bloqueadores externos — tramo de consolidación A-F

**Estado:** `v0.10.0-rc1` tageado localmente sobre
`consolidation/A-F` (HEAD `e3a2875`, 32 commits sobre
v0.9.5-rc1, 590 tests verdes, cert 43/43 ejecutados
con 38 DEAD, 2 UNDER_KILLED, 3 INFRA_FAILED — 0
SURVIVED). Los items siguientes requieren
coordinación con productores upstream y **NO se
cierran en-repo**.

**B1 ya no está en esta lista.** Se cerró el 2026-10-10
contra el SDK `dev.rubentxu.pipeline.v2:*:0.48.0` stable
(instalado vía asdf, publicado en `~/.m2/repository/`). Ver
`odd/tasks/block-B-pipelinek-plugin.md`. El siguiente
tramo (Bloque G, ver
`block-G-pipelinek-real-integration.md`) re-trabaja
esta integración contra la SDK actual
(`a0f84e48`, ancla `0.48.0` + `0.51.0-rc1`).

## Anexo de cert 2026-10-11 (HEAD `e3a2875`)

El cert del tramo A–F ejecuta los **43** mutantes del
catálogo y produce un recibo JSON por mutante en
`build/mutant-receipts/`. Histograma:

| Veredicto | N | IDs |
|---|---|---|
| `DEAD` (killed ≥ 2) | **38** | ver `08-testing/MUTATION_CATALOG.md` |
| `UNDER_KILLED` | **2** | `M-10-CONTENT`, `M-CODEC01` |
| `INFRA_FAILED` | **3** | `M-D02`, `M-V01`, `M-V02` (hallazgos reales) |
| `SURVIVED` | **0** | — |

Los 5 mutantes con hallazgo **no se cuentan como
"certificados"**. Su análisis está en
`odd/tasks/block-A-integrity.md` § "Annexo: cert
post-cierre A". El bloque siguiente que los absorba
es G (codec frágil en la frontera null-safety /
sealed exhaustiveness; redundancia insuficiente en
test lens y codec).

## C1 — CogniCode v0.101.10 (BLOQUEADO)

**Quien provee:** upstream CogniCode (reproductor externo
de evidencia arquitectónica).

**Qué esperamos:**
- Release firmada con export
  `assurance-evidence/v1` consumible por nuestro codec.
- Compatibilidad byte-a-byte Rust ↔ Kotlin verificada
  sobre fixtures compartidos.
- Capabilities arquitectónicas implementadas
  (`architecture.dependency-graph`,
  `architecture.cycles`, etc.).

**Qué bloquea:**
- C5 (primer pipeline estático real, depende de C1).
- E4 (self-hosting: Assurance contra su propio repo con
  CogniCode real, no sólo `08-testing/self-model.graph`).
- Tag v0.11.0-rc1.

**Workaround en-repo:** `CogniCodeArtifactProvider` existe
y consume el shape `assurance-evidence/v1`. Los tests
unitarios usan fixtures locales (`fixtures/`) que NO son
válidos para certificación — sólo para desarrollo.

**Cierre:** cuando CogniCode 0.101.10 publique, ejecutar
`./gradlew :assurance-providers:test --tests *CogniCode*`
contra un export real, y verificar el digest canónico
de nuestro `EvidenceArtifactCodec` coincide con el del
export. Si pasa, tag v0.11.0-rc1.

## D1 — Chronos export real (BLOQUEADO)

**Quien provee:** upstream `Rubentxu/chronos`.

**Qué esperamos:**
- Contrato `assurance-runtime-evidence/v1` con session
  refs, window tokens durables, apertura/sellado,
  invocations, causal edges, correlaciones externas,
  gaps, export versionado.

**Qué bloquea:**
- D2 (`assurance.verify` Step body-owning real sobre SDK
  PipelineK).
- D4 (lens runtime conectada con evidencia real).
- Tag v0.12.0-rc1.

**Workaround en-repo:** `ChronosArtifactProvider` consume
el shape; los tests usan `fixtures/chronos-*.json`
sintéticos. `BodyContinuation` ya está ejercido contra el
SDK 0.48.0 real (B1 cerrado), así que el lado Kotlin de D2
ya no es el bloqueo: sólo falta el export de Chronos.

**Cierre:** cuando Chronos publique, ejecutar los
E2E de D2 con el export real, verificar
`EvidenceNormalizer` lo acepta, y validar
`BodyContinuation.runBodyOnce` con cancellation real.

## D3 — OTel collector real (BLOQUEADO)

**Quien provee:** entorno de CI con collector OTel real
en configuración reproducible.

**Qué esperamos:**
- Collector OTel estándar exportando OTLP.
- Traces con TraceId, SpanId, ParentSpanId,
  preservados a través del reporte.

**Qué bloquea:**
- D4 (lens `ConsistencyLens` y `ObservedArchitectureLens`
  con evidencia real).
- F3 (benchmarks de 100k trazas OTel, la parte que queda
  de F3).

**Workaround en-repo:** el codec y el `OtelArtifactProvider`
están implementados; los benchmarks F3 cubren 1k y 10k
modules sintéticos, suficiente para validar el coste del
engine sobre corpus comparable a 100k OTel spans.

**Cierre:** cuando un host con collector OTel esté
disponible, ejecutar los benchmarks de 100k y
promediar sobre 3 runs para obtener el baseline
publicable.

## F1 — Matriz de compatibilidad (PARCIAL)

**Quien provee:** el SDK real. Ya disponible en 0.48.0.

**Estado:** `06-uat/SDK_COMPATIBILITY_MATRIX.md` certifica
0.48.0 stable (8 dimensiones: ABI pública, registro del
plugin, typed outputs, BodyContinuation, artifact store,
replay, error y cancellation propagation).

**Qué falta:**
- 0.48.0-rc1 y 0.48.0-rc2 (marcadas `❌`: superadas por
  la stable, no certificables por separado).
- 0.49+ cuando exista upstream.

**Qué bloquea:**
- F6 (release candidate v1.0.0-rc1).
- Cierre formal del tramo.

**Workaround en-repo:** la matriz tiene una fila
certificada. El criterio de cierre mínimo se negociará con
el usuario: 1 versión estable es defendible para 1.0.0;
una fila por minor es lo que el plan original pedía.

## F6 — Release candidate v1.0.0-rc1 (BLOQUEADO por F1 + D1 + D3)

**Quien provee:** depende de F1, C1, D1 y D3.

**Qué esperamos:**
- Tag formal v1.0.0-rc1 con:
  - SBOM CycloneDX firmado
  - Recibo de tests y mutantes (ya tenemos scripts)
  - JAR publicado (ya tenemos `maven-publish` local)
  - CLI empaquetada (ya tenemos `build-cli-dist.sh`)
  - SHA256SUMS firmados (ya tenemos `sign-release.sh`
    + `verify-signatures.sh`)
  - Provenance del build
  - Instalación desde artefactos publicados verificada
- Suite completa en limpio.
- Mutaciones certificadas en aislamiento.

**Qué bloquea:**
- Promoción a stable v1.0.0.

**Workaround en-repo:** los scripts y configs están
listos (`build-cli-dist.sh`, `sign-release.sh`,
`verify-signatures.sh`, `collect-test-receipt.py`,
`certify_mutants.py`). F6 es el wiring de CI que los
compone.

## Resumen de impacto

```
                in-repo              externo
v0.10.0-rc1   A1-A5, B1-B4          — (cerrado)
v0.11.0-rc1   C2-C4                 C1
v0.12.0-rc1   D5                    D1, D2, D3
v0.13.0-rc1   E1-E3                 E4 (C1)
v1.0.0-rc1    F2-F5                 F1 (0.49+), F6
```

**Consecuencia:** con A, B, D5, E1–E3 y F2–F5 cerrados,
`v1.0.0-rc1` no tiene trabajo en-repo cerrable restante.
Los cuatro items de la columna derecha son la única vía.

## Decisiones pendientes del usuario

Estas no son bloqueadores técnicos: son acciones de
publicación que requieren autorización explícita.

- **Push de `v0.10.0-rc1`** a `origin`. El tag existe local
  sobre `dd9b486`; `origin` sólo tiene hasta `v0.9.5-rc1`.
- **Merge de `consolidation/A-F` a `main`**. La rama está
  16 commits por delante, 0 atrás.
- **Criterio de cierre de F1**: ¿1 fila (0.48.0) o una por
  minor (requiere 0.49+)?

## Trabajo defensivo disponible

Mientras los productores externos publican, el repo puede
seguir cerrando items defensivos (sin promoción de tag):

- Pulido de UX del CLI (`assure <comando>` con HATEOAS
  más rico)
- Más proveedores sintéticos (chronos-mock, otel-mock)
- Benchmarks reproducibles con fixtures
- Documentación operativa
- CI: wiring de los scripts existentes en GitHub Actions

Esos items no requieren bloqueador externo y pueden
avanzar con un commit atómico cada uno.
