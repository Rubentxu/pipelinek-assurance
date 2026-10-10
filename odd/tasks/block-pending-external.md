---
name: block-pending-external
description: Registro consolidado de bloqueadores externos al tramo de consolidación A-F. Source of truth para saber qué falta y por qué.
---

# Bloqueadores externos — tramo de consolidación A-F

**Estado:** v0.9.5-rc1 tagged. Tramo A-F cerrado en-repo sobre
`consolidation/A-F` (HEAD `a3986f3`). Los siguientes items
requieren coordinación con productores upstream y NO se
cierran en-repo.

## B1 — SDK PipelineK v0.48.0-rc2 (BLOQUEADO)

**Quien provee:** upstream `Rubentxu/pipelinek` (repositorio
externo).

**Qué esperamos:**
- Release firmada y reproducible del SDK
  `dev.rubentxu:pipelinek-sdk:0.48.0-rc2`.
- ServiceLoader real con `StepDefinitionContributor`.
- `BodyContinuation` exportado en su forma canónica.
- Tipos públicos de salida tipados (no `List<Any>`).

**Qué bloquea:**
- Tag v0.10.0-rc1 (B1 es el primer sub-task de Bloque B; sin
  SDK, no hay integración real, no hay UAT-008 / UAT-009).
- Promoción de la rama `consolidation/A-F` a `main`.
- Publicación del JAR a un repo remoto (F2.4, depende de
  la integración SDK para validar la distribución).
- F1 (matriz de compatibilidad).

**Workaround en-repo:** el módulo `pipelinek-assurance-plugin`
existe como placeholder estructural (AAT-3 verifica su
presencia); el código del plugin compila y los tests E2E
pasan con un `MockSdkHost` (en `MockSdkHost.kt`).

**Cierre:** cuando el upstream publique 0.48.0-rc2, añadir
`implementation("dev.pipelinek:pipelinek-sdk:0.48.0-rc2")` al
`pipelinek-assurance-plugin/build.gradle.kts`, ejecutar
`./tools/certify_mutants.py` + `./gradlew test` y re-correr
los E2E con SDK real. Si pasan, tag v0.10.0-rc1.

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
  PipelineK — depende de B1 también).
- D4 (lens runtime conectada con evidencia real).

**Workaround en-repo:** `ChronosArtifactProvider` consume
el shape; los tests usan `fixtures/chronos-*.json`
sintéticos.

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
- F3 (benchmarks de 100k trazas OTel).

**Workaround en-repo:** el codec y el `OtelArtifactProvider`
están implementados; los benchmarks F3 cubren 1k y 10k
modules sintéticos, suficiente para validar el coste del
engine sobre corpus comparable a 100k OTel spans.

**Cierre:** cuando un host con collector OTel esté
disponible, ejecutar los benchmarks de 100k y
promediar sobre 3 runs para obtener el baseline
publicable.

## F1 — Matriz de compatibilidad (BLOQUEADO por B1)

**Quien provee:** el SDK real (B1). Sin SDK, no hay
matriz posible.

**Qué esperamos:**
- Lista de versiones del SDK con las que Assurance es
  compatible, comenzando por 0.48.0-rc2.
- Por cada versión: ABI pública, registro del plugin,
  typed outputs, BodyContinuation, artifact store,
  replay, error y cancellation propagation.

**Qué bloquea:**
- F6 (release candidate v1.0.0-rc1).
- Cierre formal del tramo.

**Workaround en-repo:** la matriz es vacía hasta B1.
El plan es que B1 = 0.48.0-rc2, B = 0.49.0-rc1, etc.

## F6 — Release candidate v1.0.0-rc1 (BLOQUEADO por F1)

**Quien provee:** depende de F1.

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
                in-repo  externo
v0.10.0-rc1   (B2-B4)    B1
v0.11.0-rc1   (C2-C4)    C1 + B1
v0.12.0-rc1   (D5)       D1, D2, D3 + B1
v0.13.0-rc1   (E1-E3)    E4 (C1)
v1.0.0-rc1    (F2-F5)    F1, F6 (B1, C1, D1, D3)
```

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
