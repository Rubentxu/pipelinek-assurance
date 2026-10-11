# AGENTS.md — pipelinek-assurance

Plugin externo de PipelineK para software assurance determinista.

Este archivo adapta `AGENTS.template.md` al repo real. La plantilla se queda como
documento de origen; este es el que manda para los agentes.

## Estado actual

Hitos cerrados: **M0..M11** (v0.8.0). Cierre post-audit: v0.9.0..v0.9.4.
Tramo de consolidación A-F cerrado en-repo y tageado.

Estado verificado (HEAD `e3a2875`): 590 tests verdes,
**`v0.10.0-rc1` tagged** (cierre Bloque B + Bloques A, C,
D, E, F in-repo) sobre `consolidation/A-F` (32 commits
sobre v0.9.5-rc1): A1-A5, **B1-B4** (con SDK PipelineK
0.48.0 stable local), C2-C4, D5, E1-E3, F1 (matrix SDK
0.48.0), F2-F5. **Cert 43/43 ejecutados** sobre
`e3a2875`: 38 DEAD (killed≥2), 2 UNDER_KILLED
(M-10-CONTENT, M-CODEC01), 3 INFRA_FAILED (M-D02, M-V01,
M-V02), 0 SURVIVED. Los 5 hallazgos están analizados
en `odd/tasks/block-A-integrity.md` § "Annexo: cert
post-cierre A".

HEAD actual: `consolidation/G-integration-v2` (rama de
Bloque G, basada en `consolidation/A-F@b1f3f7e`).
G0 (pre-flight, `0e1dad9`): 0.51.0-rc1 instalado vía
asdf, admisión de classpath OK, regression 0.48.0 OK.
G1 (matriz de versiones, `fa69f26`): `pipelinekVersion`
como propiedad Gradle, `SDK_COMPATIBILITY_MATRIX.md`
abierto, 3 codec warnings silenced.
G2 (FrozenAssuranceRuntime, `806282b`): `freeze()`
materializa la inmutabilidad; 3 contract tests + 2
fitness tests cierran la regla 0 + regla 4 + regla 7.
G3 (assurance.check tipado, `bd13470`): contract
con `requiredCapabilities=ASSURANCE_PROVIDER,ASSURANCE_ASSERTIONS`,
descriptor con `ReplayPolicy.MEMOIZED` + `Effect.READ_ONLY`
+ `ExecutionLocation.CONTROLLER`, handler que aborta
sin lanzar cuando las capabilities faltan o cuando el
orchestrator lanza, `Output.failure` estructurado con
counterexample, `Output.replayPolicy` derivado del
fingerprint (engine + suite + adapter version).
G3.1 (validación post-impl, `cc701fb`): 12 tests
añadidos — 1 edge case del handler (no propaga
`NoSuchElementException` del SDK), 4 fitness tests
del wire format JSON (counterexample + replayPolicy),
7 contract fields (CONTROLLER, READ_ONLY, MEMOIZED,
None, StepBody.None, capabilities, no-deprecation).
3 warnings del compilador corregidos (parameter
name `key` en `StepCapabilityAccess.get(...)`).
G3.2 (validación extendida, `1975d95`): 43 tests
en 5 archivos — invariants del Input (V7),
contrato del counterexample (V8), replay policy
(V9), outputSchema vs JSON real (V10),
cross-checks consolidados (V11–V15: caps, severities
strings, MockSdkHost compat, surface de outcome,
precisión de dominios). 1 bug latente en engine
documentado (`evaluateEnforcement` usa
`indexOf(result)` que es equals-based; produce
`blockingAssertionIds` duplicados cuando dos
`AssertionResult` son `==`-iguales). El fix es
engine-side, no plugin-side; queda para bloque
post-G3.
G3.3 (validación más profunda, `36cbaf2`): 10 tests
en 2 archivos — idempotencia del handler (V16,
100 invocaciones), thread-safety del handler (V17,
20 invocaciones concurrentes con suite.id
distintos), surface del API público (V19, contributor
publica 2 steps + id estable), visibilidad
(V22, contributor public / MockSdkHost internal
verificado por inspección textual), back-compat
del overload `run(input, evidenceItems)` (V23),
digest determinism (V24, mismo input ⇒ mismo hex,
diferente suite.id ⇒ diferente digest). V18/V20/V21
no testeables (codecs private, evidenceItems
hardcoded, handler private); documentados como
cobertura en B3 o pendiente para G4.
G3.4 (property + negative paths, `af6c430`): 13 tests
en 2 archivos — property-based (V25, 50 inputs
aleatorios con seed=42), snapshot del JSON output
(V26, 5 top-level keys pineadas), negative paths
del orchestrator (V27/V28, IAE esperado en
inputs inválidos consistente con R1/R2), engine
contract (V29, sólo Advisory + FailClosed ⇒
Success), stress (V31, 100 assertions en < 5s),
schema cross-version (V32, tipos pineados).
**1 hallazgo real documentado**: el digestOf del
orchestrator (AssuranceCheckStepDefinition.kt:371)
ES order-dependent — dos suites con las mismas
assertions en distinto orden producen digests
distintos. Fix engine-side, programado para
bloque post-G3. V18/V20/V21/V30 no testeables
(codecs private, evidenceItems hardcoded, handler
private, plugin no parsea JSON).
G3.5 (invariants + boundaries, `8d3ed71`): 19 tests
en 1 archivo — codec round-trip (V33, Input +
Output sobreviven encode→decode), step descriptor
contract surface (V34, todos los campos no-vacíos
+ capabilities ASSURANCE_PROVIDER/ASSERTIONS
presentes), replay policy boundary (V35, 4 casos
de evidenceAdapterVersion), summary invariants
(V36, conteos >= 0 + pineo del happy path),
failure invariants (V37, failure-null en Success,
counterexample estructurado en Failure/Aborted),
reportDigest format (V38, 64-hex + determinism
100-invocations + Digest.ofUtf8 format), AAT-3
frontier (V39, el core no importa
`dev.rubentxu.pipeline.*` por inspección de
archivos), regression guard (V40, 1 test de cada
pase anterior).
**1 bug del propio test descubierto y arreglado
vía jstack**: `parseSummary$intAt` tenía
`while (cond) end` — el `end` era un expression
statement que no incrementaba, causando loop
infinito cuando el primer char post-`:` era
dígito. Fix: `end++` en el cuerpo. Tests ahora
pasan en 7.5s.
Total acumulado: 696/0/0 en clean check (38s).
Detalle en `odd/tasks/block-G-G3-status.md` § G3.1,
§ G3.2, § G3.3, § G3.4 y § G3.5.

Plan de consolidación activo: **Bloques A → F** (ver
`ROADMAP.md` §3 al final y los `odd/tasks/block-*.md` que
vayan apareciendo). El orden A→B→C→D→E→F es estricto.

Pendiente por externo (no se cierra en-repo): C1 CogniCode
v0.101.10, D1 Chronos export, D3 OTel collector, F6 RC
v1.0.0-rc1. Detalle de cada uno: `odd/tasks/block-pending-external.md`.

Push de `v0.10.0-rc1` y merge de `consolidation/A-F` a
`main` quedan como decisión del usuario.

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
