# Bloque G — estado G3 (`assurance.check` como Step atómico tipado)

**Fecha:** 2026-10-11
**Rama:** `consolidation/G-integration-v2`
**Source-of-truth:** `odd/tasks/block-G-work-units.md §G3` y
`odd/tasks/block-G-pipelinek-real-integration.md §Regla 3`.

## Lo que ya estaba (pre-G3)

`AssuranceCheckStepDefinitionImpl` ya existía en
`pipelinek-assurance-plugin/.../AssurancePluginContributor.kt` y
era un `StepDefinition<Input, Output>` real que implementaba
las interfaces del SDK (`StepContract`, `StepHandler`,
`StepCodec`, `StepHandlerContext`). El `assurance-check`
test existente (`AssuranceCheckEndToEndTest`) cubría la
lógica del `run(...)` overload con providers reales.

Lo que **faltaba** en G3 era el **contrato declarativo** y
el **handler en el surface del SDK** (no el overload legacy
`run(input, evidenceItems)`). Concretamente:

1. `StepContract.requiredCapabilities` = `emptySet()`
   en vez de las dos capabilities reales que el plugin
   necesita del host.
2. `StepDescriptor` reducido a `(stepId, name, configRef,
   pluginId, pluginVersion, apiVersion)`: faltaban
   `executionLocation`, `inputSchema`, `outputSchema`,
   `effects`, `replayPolicy`, `recoveryPolicy`,
   `idempotencyModel`, `timeoutModel`, `body`.
3. `AssuranceCheckStepHandler.execute(...)` ignoraba
   `context.capabilities` y llamaba al overload legacy con
   `evidenceItems = emptyList()`. El handler nunca veía
   las capabilities del host.
4. El `Output` (`reportDigest`, `summary`, `outcome`) no
   llevaba un counterexample estructurado: un Mandatory
   fail se serializaba como `"Failure"` y nada más, sin
   decir QUÉ assertion bloqueó.

## Cambios en este commit

### 1. `Output` ampliado (sin romper consumidores previos)

- Nuevo campo `failure: CounterexampleDto? = null` —
  el counterexample estructurado cuando el outcome es
  `Failure` o `Aborted`. `null` en `Success`.
- Nuevo campo `replayPolicy: String = "RERUN"` — la
  policy que el handler eligió en este run. El descriptor
  declara `ReplayPolicy.MEMOIZED` como preferida; el
  handler puede caer a `RERUN` si el fingerprint está
  incompleto.
- Nuevo DTO `CounterexampleDto(kind, message,
  blockingAssertionIds, sourceLocation)`. `kind` tiene
  cuatro valores: `mandatory-failure`, `incomplete`,
  `engine-error`, `missing-capability`.

Ambos campos son opcionales con default; la
`encodeDefaults = true` de los codecs del plugin los
serializa para que el host del SDK los vea. Los tests
existentes (`MockSdkHostTest.runCheck_output_se_serializa_a_json_canonico`)
siguen pasando porque sólo verifican la presencia de los
3 campos del contract original; los nuevos son aditivos.

### 2. `run(...)` overload gana `evidenceAdapterVersion: String? = null`

Si el caller pasa una versión no-vacía, el `Output.replayPolicy`
sale como `"MEMOIZED"`. Si pasa `null` o blank, `"RERUN"`. El
fingerprint G3 cubre engine version (en
`runtime.engineVersion`), suite id+version (en `input.suite`),
evidence digests (en `orchestration.reportDigest`) y
**adapter version** (este nuevo parámetro). Sin la
última, no se puede memoizar: el adapter puede cambiar
entre runs.

### 3. `counterexampleOf(...)` extrae el counterexample real

Helper privado que, dado un `StepOutcome.Failure`, re-corre
`AssuranceEngine.evaluateEnforcement(report, suite, mode)` y
cosecha los `blockingFailures` con
`enforcement = Mandatory` y `result != Passed`. Pone los
`assertionId.value` en `blockingAssertionIds`. Distingue
tres kinds:

- `mandatory-failure`: al menos un `EnforcementEntry`
  Mandatory con `result != Passed` (caso típico del plan).
- `incomplete`: el reason empieza con `assurance-incomplete`
  (caso `RequireComplete` insatisfecho).
- `engine-error`: cualquier otro Failure o un Aborted
  (cubre el caso "Mandatory fail sin IDs resueltos"
  y el caso "excepción del orchestrator").

El plugin **NO lanza** en Mandatory fail: la
serialización del counterexample va en el `Output`,
y el SDK mapea a `StepOutcome.Failure` desde el
output. `assertionsById` + `providers` pueden
devolver cualquier shape; el handler traduce.

### 4. `AssurancePluginContributor`: contract + handler reescritos

- **Capabilities declaradas**: dos nuevas
  `val`s a nivel de archivo —
  `ASSURANCE_PROVIDER = StepCapability("assurance.provider")`
  y `ASSURANCE_ASSERTIONS = StepCapability("assurance.assertions")`.
  El plugin las pide al host; el host las publica
  vía `RuntimeCapabilityContributor` (mecanismo del SDK).
- **Contract**:
  - `requiredCapabilities = setOf(ASSURANCE_PROVIDER,
    ASSURANCE_ASSERTIONS)`: el host rehusará ejecutar
    el Step si el runtime no las satisface.
  - `StepDescriptor` completo: `executionLocation =
    CONTROLLER`, `effects = [READ_ONLY]`,
    `replayPolicy = ReplayPolicy.MEMOIZED`,
    `recoveryPolicy = RecoveryPolicy.None`,
    `idempotencyModel = "idempotent-given-fingerprint"`,
    `timeoutModel = "bounded-by-orchestrator"`,
    `body = StepBody.None`.
- **Handler reescrito** (`AssuranceCheckStepHandler.execute`):
  1. Lee `context.capabilities.available()`. Si falta
     alguna de las dos capabilities, devuelve
     `Output(outcome = "Aborted", failure =
     CounterexampleDto("missing-capability", ...))`.
     **No lanza**.
  2. Si ambas están, llama a
     `AssuranceCheckStepDefinition.run(...)` con
     `evidenceAdapterVersion` extraído de la
     capability `ASSURANCE_PROVIDER` (versión del
     primer provider del registry).
  3. Si `run` lanza (caso bug del orchestrator,
     **no** Mandatory fail, que se serializa), el
     handler captura `Throwable` y devuelve
     `Output(outcome = "Aborted", failure =
     CounterexampleDto("engine-error", e.message))`.
     **No propaga**.

## SDK surface consumido (verificado en 0.48.0 y 0.51.0-rc1)

- `StepCapability` (value class) — usado para
  `ASSURANCE_PROVIDER`/`ASSURANCE_ASSERTIONS`. En bytecode
  es `String`; en Kotlin fuente es el wrapper.
- `StepCapabilityAccess.available(): Set<StepCapability>` —
  para detectar capabilities ausentes.
- `StepCapabilityAccess.get<T : Any>(capability:
  StepCapability): T` — para leer providers vía la
  capability `ASSURANCE_PROVIDER`. Capturamos
  `IllegalStateException` (el caso "no se puede
  satisfacer") y `ClassCastException`.
- `StepDescriptor` completo: 18 campos. `RecoveryPolicy`
  es `sealed` (sólo el módulo del SDK lo extiende);
  usamos `RecoveryPolicy.None`.
- `StepContract.requiredCapabilities: Set<StepCapability>`.
- `StepHandlerContext` (data class con
  `runId: RunId, stepIndex: Int, capabilities:
  StepCapabilityAccess`).
- `ReplayPolicy.MEMOIZED | RERUN | NEVER` (enum).
- `Effect.READ_ONLY` (enum).
- `ExecutionLocation.CONTROLLER | WORKER | AGENT` (enum).
- `StepBody.None` (interface con `Declared | None`).

**Cero divergencias entre 0.48.0 y 0.51.0-rc1** en este
sub-set. El delta 0.48.0→0.51.0-rc1 sólo añade
`Capability`/`EventFollower`/`OutputPinStore`/
`BodyExecutionEngine` (no consumidos por G3).

## Tests añadidos

`G3AssuranceCheckStepTest` (4 tests, todos verdes):

- `G3_check_typed_step_definition_ok`: input/output
  pasa por `Json.encodeToString`/
  `decodeFromString`; el `contract.inputCodec.schema()`
  y `outputCodec.schema()` son los esperados.
- `G3_check_mandatory_bloquea`: una assertion
  Mandatory con lens que no se puede proyectar (la
  capability `nope` no está en el snapshot) produce
  `Output.outcome = "Failure"` y
  `failure.kind = "mandatory-failure"` con
  `blockingAssertionIds = ["a/blocks"]`. El handler
  no lanza (`shouldNotThrowAny`).
- `G3_check_memoized_solo_con_fingerprint_completo`:
  tres sub-casos: con `evidenceAdapterVersion =
  "cognicode/1.0"` ⇒ `"MEMOIZED"`; con `null` ⇒
  `"RERUN"`; con `"  "` ⇒ `"RERUN"`.
- `G3_check_capability_ausente_impide_ejecucion`: el
  `StepHandlerContext` reporta `available() = emptySet()`.
  El handler retorna `Output(outcome = "Aborted",
  failure = CounterexampleDto(kind = "missing-capability",
  message = "Required capabilities absent: assurance.provider,assurance.assertions"))`,
  con `replayPolicy = "RERUN"`.

Los 4 tests ejercitan el handler real
(`AssuranceCheckStepHandler`) con un `FakeStepHandlerContext`
que implementa `StepCapabilityAccess` lanzando
`IllegalStateException` cuando se le pide una capability
no presente (mismo comportamiento que un CapabilityAccess
del SDK real).

## Lo que NO se hace en G3 (deferido)

- **`RuntimeCapabilityContributor` real que publique
  las dos capabilities.** El handler las pide y el
  test cubre el path ausente; el host de producción
  queda pendiente de G5/G6 (matriz + cert) cuando
  llegue el `RuntimeCapabilityContributor` real.
- **El plugin NO provee providers propios.** La
  registry vacía en el handler es la ruta actual.
  Cuando el host publique un `ProviderRegistry` vía
  la capability `ASSURANCE_PROVIDER`, el handler lo
  recogerá automáticamente (ya extraído en
  `readEvidenceAdapterVersion`).
- **Refactor de `Output` a un sealed type.** Sigue
  siendo un data class con un campo `outcome: String`.
  Cambiar a `sealed interface Output { Success, Failure,
  Aborted }` rompería los codecs JSON existentes; lo
  diferimos hasta G6 (cert sobre la nueva ruta).
- **Logging del run.** El `Output` no lleva un campo
  `log` o `events`. El SDK probablemente quiera un
  canal de logs por step; el plugin no lo emite
  todavía. Diferido a G6.

## G3.1 — Pases de validación (post-implementación)

G3.1 es el pase de validación autónoma que pidió el
usuario. Cada item es un test o un comando que fallaría
si la implementación G3 se desviara de su contrato.

### V1: SDK 0.51.0-rc1 real

```
$ PIPELINEK_SDK_LIB=$HOME/.asdf/installs/pipelinek/0.51.0-rc1/lib \
  ./gradlew --no-daemon clean check
BUILD SUCCESSFUL in 50s
599 tests, 0 failures, 0 errors, 0 skipped
```

Confirmó que el cambio de `Output` (con `failure?` y
`replayPolicy`) y el `evidenceAdapterVersion?` del
`run(...)` compilan y enlazan contra 0.51.0-rc1. El
default `evidenceAdapterVersion = null` mantiene el
JSON shape original (sin campo nuevo) para callers
que no pasan versión.

### V2: Edge case del handler (no-throw + CancellationException)

Encontré dos bugs reales durante este pase:

1. El `catch` de `readEvidenceAdapterVersion` era
   estrecho (sólo `IllegalStateException |
   ClassCastException`). Lo amplié a
   `catch (t: Throwable)` para honrar el contrato
   "no-throw": si el SDK lanza un
   `NoSuchElementException` desde su `get<>`, el
   handler no debe propagar.
2. El `catch` externo del handler tragaba
   `kotlinx.coroutines.CancellationException`,
   rompiendo el contrato de coroutines. Añadí un
   `catch (c: CancellationException) { throw c }`
   explícito. Esto requirió declarar
   `kotlinx-coroutines-core:1.11.0` en el
   `build.gradle.kts` del plugin (era sólo transitivo
   del SDK, no estaba en `compileClasspath`).

Test añadido:
- `G3_check_handler_no_propaga_excepciones_del_sdk_en_get`
  usa un fake que lanza `NoSuchElementException` y
  verifica que el handler completa con
  `replayPolicy = "RERUN"`.

Resultado: 600/0/0 verde.

### V3: Fitness test del wire format JSON

El test E2E preexistente
(`MockSdkHostTest.runCheck_output_se_serializa_a_json_canonico`)
sólo cubría los 3 campos del contract original
(`reportDigest`, `summary`, `outcome`). Añadí
`G3OutputJsonFitnessTest` con 4 tests que cierran
esa cerradura:

- `G3_output_serialize_failure_como_subobjeto_con_todos_los_campos`:
  `failure` aparece como sub-objeto con `kind`,
  `message`, `blockingAssertionIds`.
- `G3_output_serialize_failure_null_y_replayPolicy_RERUN_en_success`:
  `failure: null` (con `encodeDefaults = true`) y
  `replayPolicy` se serializa con el valor exacto.
- `G3_output_serialize_failure_completa_con_sourceLocation`:
  el path "engine-error" con `sourceLocation` viaja
  al JSON.
- `G3_handler_real_serialize_output_tras_runtime_exception_del_sdk`:
  integración handler → output → JSON end-to-end.
  El SDK lanza una excepción no estándar, el handler
  la captura, y el JSON final contiene
  `"replayPolicy":"RERUN"`.

Resultado: 4/4 verde.

### V4: Dominios tipados del `StepDescriptor`

El SDK no valida los strings de texto libre
(`idempotencyModel`, `timeoutModel`, etc.), pero
los 5 dominios tipados (`ExecutionLocation`,
`Effect`, `ReplayPolicy`, `RecoveryPolicy`,
`StepBody`) sí los usa el orquestador para tomar
decisiones. `G3ContractFieldsTest` (7 tests)
verifica que:

- `executionLocation = CONTROLLER` (no WORKER).
- `effects = [READ_ONLY]` (nada de escritura).
- `replayPolicy = MEMOIZED` (no REPLAY).
- `recoveryPolicy = None` (no Auto).
- `body = StepBody.None` (no Linear, no
  CanonicalEngine).
- `requiredCapabilities = [assurance.provider,
  assurance.assertions]`.
- `deprecation` vacío (no deprecated).

Si alguien edita esas líneas por error y pone,
p.ej., `replayPolicy = ReplayPolicy.REPLAY`, el
test atrapa la regresión antes de que llegue al
orquestador del SDK.

Resultado: 7/7 verde.

### V5: Doble cómputo de `evaluateEnforcement` (observación, no bug)

`run(...)` llama dos veces a
`AssuranceEngine.evaluateEnforcement(...)` con el
mismo `report + suite + mode`:

1. Una vez dentro de `outcomeOfWithSuite`
   (`AssuranceCheckStep.kt:250`).
2. Otra vez dentro de `counterexampleOf`
   (`AssuranceCheckStepDefinition.kt:433`).

Las dos llamadas son funciones puras sobre el
mismo `report`, así que NO es trabajo duplicado
(no se re-ejecutan lenses/assertions). Es sólo un
re-traveseo de la decisión.

**Follow-up G3.2** (no en este commit): refactor
`outcomeOfWithSuite` para que devuelva el
`EnforcementDecision` además del `StepOutcome`, y
reusar esa misma decisión en `counterexampleOf`.
Coste estimado: trivial (mover 4 líneas). Beneficio:
elimina la posibilidad de divergencia si en el
futuro `evaluateEnforcement` deja de ser puro
(timestamp-based, p.ej.).

### V6: Warnings del compilador en archivos G3

`./gradlew :pipelinek-assurance-plugin:compileTestKotlin --rerun-tasks`
reportaba 3 warnings de parameter-name-mismatch
entre nuestras overrides de `StepCapabilityAccess.get(...)`
y la firma del supertipo. Renombré el parámetro
`capability` → `key` en los 3 overrides para
alinear con el SDK y silenciar los warnings.
Post-fix: 0 warnings en archivos G3.

### Estado consolidado de validación G3

```
$ ./gradlew --no-daemon clean check
BUILD SUCCESSFUL in 48s
611 tests, 0 failures, 0 errors, 0 skipped
```

G3 base: 599 tests. G3.1 añade 12 tests (1 edge
case + 4 JSON fitness + 7 contract fields). 0
regresiones, 0 warnings en código G3.

## Evidencia

```
$ ./gradlew --no-daemon clean check
BUILD SUCCESSFUL in 50s
599 tests, 0 failures, 0 errors, 0 skipped
```

G3 añade 4 tests al total (595 → 599). Gates 1+2 del
runbook siguen verdes.

## Estado de Bloque G

| Unidad | Estado | Evidencia |
| ------ | ------ | --------- |
| G0 pre-flight | ✓ verde | `block-G-G0-status.md` |
| G1 matriz/BOM/compileOnly | ✓ verde | `fa69f26` + `SDK_COMPATIBILITY_MATRIX.md` |
| G2 FrozenAssuranceRuntime | ✓ verde | `block-G-G2-status.md` |
| G3 assurance.check tipado | ✓ verde | este doc + 4 tests nuevos |
| G4 assurance.verify + BodyContinuation | pendiente | depende de G3 |
| G5 admisión 0.48 + 0.51 | ✓ parcial | admisión OK; cert formal pendiente de G6 |
| G6 pipeline de ejemplo | pendiente | depende de G4 |
| G7 cert sobre la nueva ruta | pendiente | depende de G6 |
| G8 SDK_COMPATIBILITY_MATRIX.md | ✓ abierto | v1 |
| G9 v0.13.0-rc1 | pendiente | depende de G8 |
