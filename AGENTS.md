# AGENTS.md — pipelinek-assurance

Plugin externo de PipelineK para software assurance determinista.

Este archivo adapta `AGENTS.template.md` al repo real. La plantilla se queda como
documento de origen; este es el que manda para los agentes.

## Estado actual

Hitos cerrados: **M0..M11** (v0.8.0). Cierre post-audit: v0.9.0..v0.9.4.

Estado verificado (v0.9.4, SHA `df44ea5`): 495 tests verdes,
44/44 mutantes certificados con redundancia >= 2.

Plan de consolidación activo: **Bloques A → F** (ver
`ROADMAP.md` §3 al final y los `odd/tasks/block-*.md` que
vayan apareciendo). El orden A→B→C→D→E→F es estricto.

Mapa de hitos y gates: `ROADMAP.md` (autoridad única de secuenciación).

## Governance

- SDDK obligatorio. Pre-flight antes de tocar código.
- `ROADMAP.md` única autoridad de secuenciación.
- completed = acceptance verified. Nunca por intención.
- surgical tests durante implementación; full suite en integración/release.
- commits atómicos Conventional Commits.
- Un hito no arranca si el gate del anterior no está verde
  (`06-uat/MILESTONE_GATES.md`).

## Toolchain

Fijado en `.tool-versions` y `gradle/libs.versions.toml`:

```text
java   temurin-24.0.2+12   (launcher)
jvm    toolchain 21        (target de compilacion)
gradle 8.14.5
kotlin 2.4.10
```

El JDK 24 lanza Gradle; el target de compilación es 21, alineado con
`pipeline-kotlin` para que el SDK de PipelineK entre limpio en M3.

El repositorio usa asdf tanto para el binario de Gradle como para el JDK. Las
ejecuciones reales de este proyecto se hicieron con
`JAVA_HOME=$HOME/.asdf/installs/java/temurin-24.0.2+12 ./gradlew ...`. Invocar
siempre por `./gradlew` para no depender de la resolución de shims. Anotación
histórica: una versión anterior de este documento decía "mise para el JDK",
que no es lo que se usa; se corrige aquí para que la documentación no describa
un toolchain que nadie ejecuta.

## Modules

```text
assurance-domain      ADTs de evidencia. Sin I/O, sin PipelineK, sin CLI.
assurance-engine      Lens / Assertion / Report. Puro.
assurance-artifact    Codecs canonicos CBOR/JSON + digest determinista.
assurance-testkit     Fixtures y property tests. Unico modulo con kotest.
```

`pipelinek-assurance-plugin` no existe hasta M3. Sera el unico que dependa del
SDK de PipelineK (AAT-3). `assure-cli` aparece en M1.

No anadir modulos hasta que una frontera real lo exija.

## Architecture laws

1. Functional core has no I/O.
2. Assertions are not Steps.
3. Providers do not gate.
4. `no evidence != PASS`.
5. Heuristic != deterministic.
6. External IDs never collapse.
7. PipelineK owns body execution/recovery/journal.
8. Plugin never imports `pipeline-application`.
9. No MCP on the deterministic gate path.
10. Every mandatory assertion needs a known failing fixture/mutant before promotion.

Las leyes tienen mechanical tests. Los fitness functions de
`06-uat/AAT_FITNESS.md` se activan por hito:

| AAT | Hito | Que verifica |
|---|---|---|
| 1 | M0 | `assurance-domain` sin PipelineK, fs, red, coroutines ni CLI |
| 2 | M0 | `assurance-engine` sin implementaciones de provider |
| 6 | M0 | ningun `EvidenceProvider` retorna `AssertionResult` |
| 8 | M0 | `AssertionResult` exhaustivo, sin shortcut booleano |
| 9 | M0 | `Hypothesis` no construible como `Fact` por API publica |
| 16 | M0 | serializer de suite IR con orden canonico de map/set |
| 17 | M0 | sin `System.currentTimeMillis()` en el core |
| 20 | M0 | la rama `no evidence` no puede construir `Passed` |

## Testing

- property tests for algebra/digests;
- mutation tests for semantic laws;
- UAT catalog IDs referenced by milestone receipts;
- full installed external-plugin UAT before release.

Runner: Kotest sobre JUnit Platform. Tests deterministas: nunca `sleep`, nunca
comparar por orden de iteracion no canonico.

## Documentation

Historical/superseded docs -> `docs/history/`. Do not rewrite historical receipts
to claim newer semantics.

`PACKAGE_MANIFEST.md` lleva el digest SHA-256 de cada documento. Si tocas un
documento listado, actualiza su digest en el mismo commit o la integridad del
blueprint queda rota.
