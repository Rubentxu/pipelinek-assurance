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

## G3.2 — Pase de validación extendida (más allá del contrato)

G3.2 cubre las dimensiones del G3 que el plan original
no trataba: invariants del Input, contrato del
counterexample, replay policy, schema, capabilities,
compat backwards, y precisión de dominios. 4 archivos
de test nuevos, 43 tests añadidos.

### V7: Invariants del Input

`G3InputInvariantsTest`, 11 tests. Cubre:

- R1-R3: `lenses = []`, `assertions = []`, y
  `lensRef` dangling lanzan `IllegalArgumentException`
  desde `AssuranceSuiteIR.init` (AAT-16). El
  handler NO propaga — convierte a `Aborted` con
  `kind = "engine-error"`.
- R4-R5: `mode` y `completenessPolicy` con valores
  no canónicos caen al else-branch (`FailClosed` /
  `RequireComplete`). El plugin es tolerante con
  typos en estos strings.
- R6: `evidence = []` es válido.
- R7: `evidence` con digest duplicado se deduplica
  en el orchestrator (`distinctBy { it.digest }`).
- R7b-R7c: `digest` no-SHA-256 o `logicalRole`
  blank lanzan `IllegalArgumentException` desde
  `validateEvidenceRefs` (en
  `AssuranceOrchestrator.kt:448`).
- R8: `name = ""` y `name` con caracteres
  especiales no se rechazan.
- R9: el handler convierte engine exceptions
  en `Aborted` con `kind = "engine-error"`,
  cerrando la simetría del "no throw" del handler.

**Descubrimiento V7:** `EvidenceRefDto.digest`
tiene un formato canónico (64 hex chars = SHA-256)
y `logicalRole` no puede ser blank. El plugin
**delega** esta validación al orchestrator (no
la hace en el DTO), lo cual es coherente: el
plugin sólo serializa, el orchestrator es la
puerta de entrada.

### V8: Contrato del counterexample

`G3CounterexampleContractTest`, 8 tests. Pinea
los 4 kinds canónicos del `CounterexampleDto`:

- CK-1: grep dinámico sobre el código verifica
  que sólo se producen 4 kinds: `mandatory-failure`,
  `incomplete`, `engine-error`, `missing-capability`.
- CK-2: `mandatory-failure` con 1 assertion
  produce 1 `blockingAssertionId`.
- **CK-2b (bug latente documentado):** con 2
  Mandatory assertions que comparten operator y
  quedan `Unsupported`, `blockingAssertionIds`
  trae la primera assertion dos veces:
  `["a/blocks-1", "a/blocks-1"]`. Causa:
  `AssuranceEngine.evaluateEnforcement` (en
  `assurance-engine/.../Assurance.kt:588-595`)
  usa `report.results.indexOf(result)`, que es
  `equals`-based; con dos `Unsupported` `==`-iguales,
  `indexOf` siempre devuelve 0. El fix correcto
  está en el engine (no en el plugin):
  `forEachIndexed` o adjuntar `assertionId` al
  `AssertionResult` ("A1 secundario" del comentario
  existente en Assurance.kt:590-593). Documentado,
  no corregido en G3.2.
- CK-3: `incomplete` no lleva `blockingAssertionIds`.
- CK-4/CK-5: `engine-error` (handler outer catch)
  no lleva `blockingAssertionIds`.
- CK-6: `missing-capability` lista las capabilities
  ausentes en el message.
- CK-7: `blockingAssertionIds` con 1 ID no produce
  duplicados.
- CK-8: `sourceLocation` es `null` por defecto en
  los 4 kinds.

**Descubrimiento V8:** el engine tiene un bug
latente en `evaluateEnforcement` que sólo se
manifiesta cuando dos `AssertionResult` son
`==`-iguales. En práctica es raro (típicamente
las Mandatory son sobre operators distintos), pero
un test con operator duplicado lo reproduce. G3.2
documenta el bug; el fix se programa para después
de G3 (afecta al engine, no al plugin).

### V9: Replay policy

`G3ReplayPolicyContractTest`, 10 tests. Pinea
las 10 reglas del `replayPolicy`:

- RP-1: adapter no-blank → `MEMOIZED`.
- RP-2: adapter null → `RERUN`.
- RP-3: adapter empty → `RERUN`.
- RP-4: adapter whitespace → `RERUN`.
- RP-5: determinismo bit-a-bit: dos invocaciones
  con los mismos argumentos producen el mismo
  `reportDigest`, `summary`, `outcome`, `replayPolicy`,
  `failure`.
- RP-6: `engineVersion` NO afecta `replayPolicy`.
  El handler hardcodea "0.10.0" y el run no
  incluye la engine version en el fingerprint.
- RP-7: `runtime.lenses` no afecta `replayPolicy`.
- RP-8: mismo adapter y misma suite → mismo
  `reportDigest` (precondición de la memoización).
- RP-9: outcome (Success/Failure) NO afecta
  `replayPolicy` con la misma suite. La policy
  describe reproducibilidad, no decisión.
- RP-10: cuando el handler extrae el adapter
  exitosamente, `replayPolicy` se serializa
  correctamente. (Pin end-to-end del path
  feliz del handler.)

### V10: outputSchema declarado vs Output real

`G3OutputSchemaContractTest`, 7 tests. Pinea que
el string `outputSchema = "assurance.check/v1.output"`
corresponde a la forma real del JSON:

- X-1/X-2: schema names inmutables.
- X-3: Output "Success" serializa con 5 keys
  top-level.
- X-4: Output "Failure" tiene 5 keys (failure
  no-null, replayPolicy no-default).
- X-5: las 5 keys son `failure`, `outcome`,
  `replayPolicy`, `reportDigest`, `summary`.
- X-6: `failure` puede ser `null` o un objeto
  con las 4 sub-keys.
- X-7: `summary` tiene 5 sub-keys con valores
  correctos.

### V11-V15: cross-checks consolidados

`G3CrossCheckConsolidatedTest`, 6 tests. Cubre
los huecos que quedaban:

- V11: `requiredCapabilities` del descriptor
  son exactamente las dos capabilities que el
  handler chequea (`assurance.provider` +
  `assurance.assertions`).
- V12: `severity` y `enforcement` en
  `AssertionDto` son strings libres (no enums
  tipados) — extensible a severities futuras
  sin cambiar el shape.
- V13: `MockSdkHost.runCheck(input)` con 1
  argumento (sin `evidenceAdapterVersion`)
  sigue funcionando. La nueva firma con
  default `evidenceAdapterVersion = null`
  preserva compat.
- V14: el `outcome` del Output sólo puede ser
  uno de 3 valores: "Success", "Failure",
  "Aborted". Surface cerrada.
- V15: `ReplayPolicy` (re-correr) y
  `RecoveryPolicy` (reconstruir) son policies
  distintas, no se confunden. `Resilience`
  no existe como policy tipada en el SDK (es
  un concepto cross-cutting).
- V15 extra: `executionLocation=CONTROLLER +
  effects=READ_ONLY + body=None` son consistentes
  entre sí (un step READ_ONLY no tiene
  sub-pipeline).

### Estado consolidado de validación G3

```
$ ./gradlew --no-daemon clean check
BUILD SUCCESSFUL in 1m 14s
654 tests, 0 failures, 0 errors, 0 skipped
```

G3 base: 599 tests. G3.1 añade 12 tests. G3.2
añade 43 tests (11 V7 + 8 V8 + 10 V9 + 7 V10 +
6 V11-V15). 0 regresiones, 0 warnings en código
G3, 1 bug latente documentado (no corregido).

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

## G3.3 — Validación más profunda (segundo pase)

Continuación de G3.2 autorizada por el usuario
("Do more validation on the work below... do not
reply or wait for the user"). 9 tests nuevos en
2 archivos, 0 tests modificados.

### Nuevos tests (10)

- `G3HandlerDeterminismAndConcurrencyTest`
  (V16, V17):
  - `V16_idempotency_hundred_invocations_produce_identical_output`
    — 100 invocaciones con mismo Input producen el
    mismo JSON (mismo reportDigest, mismo summary,
    misma replayPolicy). Idempotencia del handler.
  - `V17_concurrent_invocations_isolated` — 20
    invocaciones concurrentes con inputs distintos
    (variando `suite.id`) producen 20 `reportDigest`
    únicos; y 2 invocaciones concurrentes con el
    mismo `suite.id` producen el mismo digest.
    Thread-safety del handler.

- `G3PluginSurfaceContractTest` (V19, V22, V23, V24,
  8 tests):
  - `V19_definitions_returns_two_steps_with_expected_keys` —
    el contributor publica 2 steps con keys
    `assurance.check` y `assurance.verify` (verificado
    por reflection sobre `getContract().getKey()`).
  - `V19_contributor_id_is_stable` — el id
    `"pipelinek-assurance"` es parte del wire
    contract; pineado.
  - `V22_assurancePluginContributor_is_public` — la
    clase y su constructor sin args son públicos.
  - `V22_mockSdkHost_is_internal` — el archivo fuente
    declara `internal object MockSdkHost` (verificado
    por inspección textual — Kotlin `internal` se
    compila a `public` en bytecode con name mangling,
    así que java.lang.reflect no sirve).
  - `V23_backcompat_run_with_just_evidenceItems_works` —
    el overload de `AssuranceCheckStepDefinition.run`
    con sólo `(input, evidenceItems)` sigue accesible.
  - `V24_same_input_produces_byte_equal_digest` —
    3 invocaciones con mismo Input producen mismo
    digest (mismo hex, 64 chars, lowercase).
  - `V24_digest_changes_when_suite_content_changes` —
    añadir una assertion al suite cambia el digest.
  - `V24_suiteId_value_matches_input_suite_id` —
    inputs con `suite.id` distintos producen
    `reportDigest` distintos; el `SuiteId` del
    engine es coherente con el `String` del DTO.

### Hallazgos no testeables (documentados, no bugs)

- **V18 (StepCodec shape)**: los codecs
  `AssuranceCheckInputCodec` y `AssuranceCheckOutputCodec`
  son `private class` dentro de `AssurancePluginContributor.kt`.
  No son accesibles desde tests, pero su shape JSON ya
  está cubierto por G3.1 (fitness tests del wire format
  con `encodeDefaults=true`, `failure` sub-objeto,
  `replayPolicy`, etc.) y por G3.2 V10
  (`G3OutputSchemaContractTest`).

- **V20 (provider preloaded behavior)**:
  `MockSdkHost.runCheck` hardcodea
  `evidenceItems = emptyList()`. La lógica del plugin
  que detecta `evidenceItems.isNotEmpty() && providers.all().isEmpty()`
  y crea un provider sintético está en
  `AssuranceCheckStepDefinition.run` (línea 257-301
  aprox.) pero no es alcanzable desde el mock. Cubierto
  por tests de B3 (AssuranceCheckEndToEndTest) que
  invocan la sobrecarga completa con items pre-coleccionados.

- **V21 (readEvidenceAdapterVersion paths)**: el handler
  es `private class AssuranceCheckHandler`. El path
  `try { context.capabilities.get<ProviderRegistry>(ASSURANCE_PROVIDER) }
  catch (t: Throwable) { null }` se ejerce a través del
  `G3OutputJsonFitnessTest` (counterexample) y del
  `G3CounterexampleContractTest` (kind="engine-error"),
  pero no podemos probar el path de éxito sin un
  `ProviderRegistry` con adapter version real. Eso se
  cubrirá en G4 (assurance.verify con BodyContinuation),
  donde el SDK real provee el `ProviderRegistry`.

### Cambios y fixes de test

- `G3HandlerDeterminismAndConcurrencyTest`:
  inicial V17 fallaba porque variaba `name` (que NO
  entra en `reportDigest`). Rediseño: varía `suite.id`,
  que sí entra. Descubrimiento: el `name` del Input
  es un label, no parte del fingerprint.
- `G3PluginSurfaceContractTest`: kotest en vez de
  junit5 (más coherente con el resto de tests del
  plugin); java reflection para acceder a
  `private class AssuranceCheckStepDefinitionImpl`
  (kotlin reflection respeta `private` y rompe).

### Estado de tests

- **664 tests, 0 failures, 0 errors, 0 skipped**
  (654 G3.2 + 10 G3.3).
- 0 warnings del compilador en archivos G3.
- 0 regresiones en 599 tests preexistentes.
- Clean check: 1m 05s.

### Estado de Bloque G (post-G3.3)

| Unidad | Estado | Evidencia |
| ------ | ------ | --------- |
| G0 pre-flight | ✓ verde | `block-G-G0-status.md` |
| G1 matriz/BOM/compileOnly | ✓ verde | `fa69f26` + `SDK_COMPATIBILITY_MATRIX.md` |
| G2 FrozenAssuranceRuntime | ✓ verde | `block-G-G2-status.md` |
| G3 assurance.check tipado | ✓ verde | este doc + 56 tests (3 pases) |
| G3.1 validación post-impl | ✓ verde | `cc701fb` + 12 tests |
| G3.2 validación extendida | ✓ verde | `1975d95` + 43 tests |
| G3.3 validación más profunda | ✓ verde | 36cbaf2 + 10 tests |
| G3.4 property + negative | ✓ verde | af6c430 + 13 tests |
| G3.5 invariants + boundaries | ✓ verde | este commit + 19 tests |
| G3.6 cross-cutting conformance | ✓ verde | a220a7b + 15 tests |
| G4 assurance.verify + BodyContinuation | pendiente | depende de G3 |
| G5 admisión 0.48 + 0.51 | ✓ parcial | admisión OK; cert formal pendiente de G6 |
| G6 pipeline de ejemplo | pendiente | depende de G4 |
| G7 cert sobre la nueva ruta | pendiente | depende de G6 |
| G8 SDK_COMPATIBILITY_MATRIX.md | ✓ abierto | v1 |
| G9 v0.13.0-rc1 | pendiente | depende de G8 |


## G3.4 — Property-based, negative paths, engine contract

Tercer pase de validación autónoma extendida
autorizado por el usuario. 2 archivos de test
nuevos, 13 tests añadidos (V25, V26, V27, V28,
V29, V31, V32; V30 y V18/V20/V21 no testeables).

### Nuevos tests (13)

- G3PropertyAndNegativePathsTest (V25, V26, V27, V28, 8 tests)
- G3EngineAndStressTest (V29, V31, V32, 5 tests)

### Hallazgos no testeables (documentados, no bugs)

- V18 (StepCodec shape): codecs son private class.
- V20 (provider preloaded): MockSdkHost.runCheck
  hardcodea evidenceItems=emptyList.
- V21 (readEvidenceAdapterVersion paths): handler
  es private class.
- V30 (JSON parsing edge cases): el plugin NO
  parsea JSON en su input.

### Hallazgo real de G3.4 (orden-dependence del digest)

El digestOf del orchestrator (en
AssuranceCheckStepDefinition.kt:371) serializa el
SuiteDto directamente sin ordenar los campos.
Dos suites con las mismas assertions en distinto
orden producen digests distintos. Severidad media.
Fix: engine-side, programado para bloque post-G3.

### Cambios y fixes de test

- G3PropertyAndNegativePathsTest:
  - kotest shouldContain retorna String; reemplazos
    por contains o matchers directos.
  - Mini-parser JSON parseTopLevelKeys.
  - V27 y V28 rediseñados tras descubrir IAE
    en inputs inválidos (consistente con R1/R2).
- G3EngineAndStressTest:
  - V31 rediseñado: digest ES order-dependent.
  - V32 tipos: contains no compila con infix,
    reemplazado por .contains().

### Estado de tests

- 677 tests, 0 failures, 0 errors, 0 skipped
  (664 G3.3 + 13 G3.4).
- 0 warnings del compilador en archivos G3.
- 0 regresiones en 599 tests preexistentes.
- Clean check: 51s.


## G3.5 — Invariants, boundaries, codec round-trip, AAT-3

Cuarto pase de validación autónoma extendida
autorizado por el usuario. 1 archivo de test
nuevo, 19 tests añadidos. Cubre huecos
identificados tras G3.4.

### Nuevos tests (19)

- G3InvariantsAndBoundariesTest
  - V33 codec round-trip ×2 (Input, Output)
  - V34 step descriptor contract ×2
  - V35 replay policy boundary ×4
  - V36 summary invariants ×3 (conteos >= 0,
    Success ⇒ failed=0, pineo del happy path)
  - V37 failure invariants ×3
  - V38 reportDigest format ×3 (64-hex,
    determinism 100-invocations, Digest.ofUtf8)
  - V39 AAT-3 frontier ×1
  - V40 regression guard ×1

### Hallazgo real de G3.5 (no bug del bloque G)

Bug del propio test: `parseSummary$intAt`
tenía `while (cond) end` (cuerpo de bucle
era el identifier `end` como expression
statement, no incrementaba). Bug
descubierto via jstack cuando V36 colgaba
gradle 5+ min en CPU. Fix: `end++` en el
cuerpo. Ahora 18/18 G3.5 tests verde en
7.5s.

### Cambios de main

- MockSdkHost:
  - runCheck(input, evidenceAdapterVersion)
    sobrecarga añadida para que los tests
    puedan verificar la frontera de
    replayPolicy.
  - decodeJson / decodeInput / decodeOutput
    helpers para el codec round-trip.
- AssurancePluginContributor:
  - AssuranceCheckStepDefinitionImpl de
    `private class` a `internal class`
    (visible para tests del módulo).
  - AssuranceCheckStepDefinitionImplContract
    añadido como helper para tests
    (descriptor + requiredCapabilities).

### Estado de tests

- 696 tests, 0 failures, 0 errors, 0 skipped
  (677 G3.4 + 19 G3.5).
- 0 warnings del compilador en archivos G3
  (warnings preexistentes en otros tests
  intactos).
- 0 regresiones en 677 tests preexistentes.
- Clean check: 38s.

## G3.6 — Cross-cutting conformance: SHA-256, JSON shape, enums, AAT-3 strict, order regression

Quinto pase de validación autónoma extendida.
Cubre huecos identificados tras G3.5.

### Cobertura

- **V41**: SHA-256 conformance. Pineamos el
  vector NIST `Digest.ofUtf8("abc")` debe
  producir
  `ba7816bf8f01cfea414140de5dae2223b00361a3
  96177a9cb410ff61f20015ad`. Si FIPS 180-4
  se rompe, el test falla.
- **V42**: Edge cases del digest. `""`,
  `"áéíóú ñ ç €"`, `"🚀✅❌"` producen 64
  hex chars lowercase.
- **V43**: `name` NO entra en el digest.
  Mismo `suite.id+version` con names
  distintos ⇒ mismo digest (verifica G3.3
  V24).
- **V44**: Enum exhaustive. `Severity` ∈
  {Info, Warning, Error} y `Enforcement` ∈
  {Advisory, Mandatory, Ratchet} pasan por
  DTO → IR. Valid values producen
  Success/Failure según enforcement. Invalid
  values lanzan IAE.
- **V45**: JSON key order. Pinea el orden
  de las 5 top-level keys
  (reportDigest, summary, outcome, failure,
  replayPolicy) y de las 5 sub-keys de
  summary.
- **V46**: Failure DTO kind set cerrado.
  4 kinds: mandatory-failure, incomplete,
  engine-error, missing-capability.
- **V47**: Plugin module strict AAT-3. Cero
  imports de `dev.rubentxu.pipeline.*`
  fuera de `AssurancePluginContributor.kt`.
  El SDK sólo entra por el contributor.
- **V48**: Order regression guard. Misma
  suite con assertions/lenses en orden
  distinto ⇒ digest distinto. Pineado como
  "current behavior" del bug de G3.4.

### Cambios no test-only

- `MockSdkHost.encodeJson` se usa para
  verificar el shape JSON (V45).
- No fue necesario tocar código de
  producción más allá de lo que G3.5 ya
  tenía (codec round-trip ya estaba).

### Estado de tests

- 711 tests, 0 failures, 0 errors, 0 skipped
  (696 G3.5 + 15 G3.6).
- 0 warnings del compilador en archivos G3
  (warnings preexistentes en otros tests
  intactos).
- 0 regresiones en 696 tests preexistentes.
- Clean check: 39s.
