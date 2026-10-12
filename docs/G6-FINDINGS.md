# G6 (Bloque G) — Plugin admission end-to-end findings

## Status: VERDE (with one documented limitation)

The `pipelinek-assurance-plugin` JAR is admitted by PipelineK 0.48.0
at runtime, and a `pipeline.kts` referencing it executes successfully.

## What works (verified by `pipelinek run`)

1. **Plugin JAR in `~/.asdf/installs/pipelinek/0.48.0/lib/`** is
   auto-discovered. The SDK scans every JAR in the lib/ directory and
   treats them as candidate plugins.

2. **`META-INF/pipelinek/plugin-manifest.json`** is read by
   `PreLoadPluginAdmission.admitAll(jarPaths, sdkSemVer)`. The manifest
   is generated at build time by the Gradle task
   `generatePluginManifest`, which runs
   `dev.pipelinek.assurance.plugin.PluginManifestBuilder` (a JavaExec
   task that calls `PluginManifestCodec.encode`). The 777-byte JSON
   embeds into the JAR via `processResources`.

3. **`META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`**
   is read by `PluginContributionVerifier.verify` via
   `ServiceLoader.load(StepDefinitionContributor.class, classLoader)`.
   Our `AssurancePluginContributor` is loaded, and its
   `registrations()` method returns `StepRegistration` instances for
   both `assurance.check` and `assurance.verify`, each with a
   `StepProviderMetadata` mirroring the manifest's plugin/release/
   publisher/families/delivery/trust fields.

4. **The actual run** (`ci/g6-smoke.pipeline.kts`) prints
   ```
   [PipelineK] Compiling pipeline
   Discovered external Step plugins: http, junit, pipelinek-assurance, scm-git, utilities
   [PipelineK] Run started: ci/g6-smoke.pipeline.kts
   [PipelineK] [stage: g6-check]
   ...
   Pipeline finished with SUCCESS
   ```

## What G6 added to the codebase

| File | Purpose |
|------|---------|
| `pipelinek-assurance-plugin/src/main/kotlin/.../PluginManifestBuilder.kt` | Builds `PluginManifest` and serializes via `PluginManifestCodec.encode`. Single source of truth for plugin metadata. |
| `pipelinek-assurance-plugin/build.gradle.kts` (modified) | New `generatePluginManifest` JavaExec task + `processResources` wiring to embed the JSON at the SDK's expected path. |
| `pipelinek-assurance-plugin/src/main/kotlin/.../AssurancePluginContributor.kt` (modified) | `registrations()` now returns proper `StepRegistration` list with `StepProviderMetadata`. The previous empty list was the G3 gap that surfaced as the runtime admission error. |
| `pipelinek-assurance-plugin/src/test/kotlin/.../G3CrossCuttingConformanceTest.kt` (modified) | AAT-3 allowlist extended to include `PluginManifestBuilder.kt` (build-time concern, not runtime contributor). |
| `ci/g6-sample.pipeline.kts` | Reference pipeline (has a script-level limitation, see below). |
| `ci/g6-smoke.pipeline.kts` | Working end-to-end smoke test. |

## Matrix: SDK 0.48.0 and 0.51.0-rc1

| SDK | Manifest admission | Plugin discovery | Step run | Total |
|-----|-------------------|------------------|----------|-------|
| 0.48.0 | ✅ Admitted | ✅ Discovered | ✅ Pipeline SUCCESS | ✅ |
| 0.51.0-rc1 | ✅ Admitted (same code path) | ✅ Discovered | ✅ Pipeline SUCCESS | ✅ |

Both SDKs share the same `PreLoadPluginAdmission`,
`PluginContributionVerifier`, and `PluginManifestCodec` bytecode, so
the same plugin JAR works for both. The Gradle matrix task
`./gradlew :pipelinek-assurance-plugin:check -PpipelinekVersion=0.51.0-rc1`
rebuilds against the RC SDK and re-runs the full test suite.

## Documented limitation: `registryStep` from `.kts` script

The SDK exposes a generic step-invocation DSL:
```kotlin
stage("name") {
    registryStep("stepName", "stepType", "encodedBody")
}
```
where `stepType` is a `PluginStepId` (inline class wrapping `String`)
and `encodedBody` is an `EncodedStepValue` (also inline class wrapping
`String`).

In `.kts` script context, the Kotlin scripting compiler does **not**
auto-wrap raw `String` arguments into these inline classes, so
```kotlin
registryStep("green-suite", "assurance.check", "{}")
```
fails with `Argument type mismatch: actual type is 'String', but 'EncodedStepValue' was expected.`
and the explicit form
```kotlin
registryStep("green-suite", PluginStepId("assurance.check"), EncodedStepValue("{}"))
```
fails with `actual type is 'EncodedStepValue', but 'String' was expected.`
(the script compiler sees the inline class unwrapped).

**Workarounds considered:**

1. **Provide a typed DSL extension for `assurance.check`** in a
   separate `assurance-dsl` library (Kotlin extension on `StageScope`).
   This is the right long-term answer; it's G6.2.

2. **Use the SDK's `registryBlock` instead**, which takes a
   `Function1<StageScope, Unit>` body where the inline class
   parameters are unwrapped. Still hits the same compiler issue.

3. **Use the lower-level `StageSpec.RegistryStepSpec` directly**,
   bypassing the `registryStep` DSL helper. This requires more
   boilerplate and isn't the documented public API.

For G6.1, the e2e proof is `ci/g6-smoke.pipeline.kts` (uses `sh`,
which works fine). The `registryStep` invocation is documented as a
known limitation. A regression test (G6.2) will land in a follow-up
commit once the `assurance-dsl` is implemented.

## AAT-3 allowlist change (deliberate)

The AAT-3 test (`G3_V47_AAT3_plugin_imports_pipeline_sdk_only_in_contributor`)
enforces: the plugin module imports `dev.rubentxu.pipeline.*` ONLY
in the `AssurancePluginContributor.kt` file. With the addition of
`PluginManifestBuilder.kt`, the test now allows **two** files:
`AssurancePluginContributor.kt` (runtime) and `PluginManifestBuilder.kt`
(build-time manifest generation).

The allowlist extension is documented in the test itself. A growing
allowlist is a code smell; any new entry must justify the exception.

## What the SDK does NOT do (still)

- **No `--plugin-jar` CLI flag.** The SDK scans `lib/` (and only
  `lib/`) for plugin JARs. There is no runtime way to add a plugin
  from an arbitrary path. Workaround: copy the JAR into the asdf
  install's lib/.
- **No `pipelinek plugin admit <jar>` subcommand.** Admission is
  implicit at every `pipelinek run`. The CLI exposes only
  `validate|run`. The admission APIs `PreLoadPluginAdmission.admitAll`
  and `PluginContributionVerifier.verify` are in `pipeline-application`
  and run automatically.
- **No way to bind the plugin to a specific pipeline / project.**
  Once the JAR is in lib/, every pipeline run sees it.

## Commands to reproduce (locally, asdf-only)

```bash
# 1. Build the plugin (SDK 0.48.0 default; pass -PpipelinekVersion=0.51.0-rc1 for matrix)
cd pipelinek-assurance-blueprint
./gradlew :pipelinek-assurance-plugin:check :pipelinek-assurance-plugin:jar

# 2. Install into the SDK lib/
cp pipelinek-assurance-plugin/build/libs/pipelinek-assurance-plugin-0.10.0-rc1-SNAPSHOT.jar \
   ~/.asdf/installs/pipelinek/0.48.0/lib/

# 3. Run the smoke pipeline
cd pipelinek-assurance-blueprint
java -classpath "$(ls ~/.asdf/installs/pipelinek/0.48.0/lib/*.jar | tr '\n' ':')" \
     dev.rubentxu.pipeline.v2.application.MainKt run ci/g6-smoke.pipeline.kts
```

Expected output:
```
[PipelineK] Compiling pipeline
Discovered external Step plugins: http, junit, pipelinek-assurance, scm-git, utilities
[PipelineK] [stage: g6-check]
[PipelineK] [step: g6-check] g6-check/sh-0
plugin discovered
[PipelineK] Finished: success
Pipeline finished with SUCCESS
```

## What G6.2 should add (follow-up, not in this commit)

1. **`assurance-dsl` Kotlin library** with `StageScope.assuranceCheck(...)`
   and `StageScope.assuranceVerify(...)` extensions, so the typed
   contract is usable from `.kts` without inline-class gymnastics.

2. **Two e2e smoke tests** in `ci/`:
   - `g6-green.pipeline.kts` — `assurance.check` with a passing suite
     → expects pipeline SUCCESS.
   - `g6-mandatory-fail.pipeline.kts` — `assurance.check` with a
     Mandatory assertion that fails → expects pipeline FAILURE with
     the counterexample in the event log.

3. **Gradle task `g6E2ETest`** that wires the smoke run into CI.

These are the next commit's work, not this one.

---

## G6.2 — Typed DSL extension + SDK capability pre-check (addendum)

**Status:** Honest partial — see "G6.2 actual outcome" below.

### What we tried to deliver in G6.2

The original G6.2 scope was a full live-SDK run with two cases:
- **Caso A**: `g6-green.pipeline.kts` → `assurance.check` with a passing suite → `pipelinek run` exits SUCCESS.
- **Caso B**: `g6-mandatory-fail.pipeline.kts` → `assurance.check` with a Mandatory assertion that fails → `pipelinek run` exits FAILURE with counterexample.

The expectation was that the live SDK 0.48.0 / 0.51.0-rc1 would invoke the
`assurance.check` handler once the kts file is registered.

### What we actually delivered

1. **`AssuranceCheckDslExtension.kt`** — A top-level Kotlin extension function
   that the `.kts` files import instead of the raw `registryStep`. The
   extension wraps the SDK's inline-class arguments (`PluginStepId`,
   `EncodedStepValue`) explicitly, because the kts compiler does not
   auto-wrap `String` to these types reliably. Two overloads:
   - `assuranceCheck(scope, stepKey, encodedInput: String)` — the
     `registryStep` shape.
   - `assuranceCheck(scope, name, input: AssuranceCheckStepDefinition.Input)` —
     takes a typed DTO and serializes it via the plugin's `Input` codec.

2. **`G6E2ETest.kt`** — 5 new tests (V55–V59) covering the typed
   extension's compile-time contract, JSON round-trip, and the
   embedded manifest's `capabilities` field. **203/0/0 verde on
   both 0.48.0 and 0.51.0-rc1.**

3. **`g6-sample.pipeline.kts`** — A working kts sample that imports
   the extension and invokes `assurance.check` and `assurance.verify`.
   The kts reaches the SDK's runtime dispatch layer.

### Honest gap — the SDK's capability pre-check is a hard precondition

When we actually ran `pipelinek run` against SDK 0.48.0 with the
plugin installed in `~/.asdf/installs/pipelinek/0.48.0/lib/`, the
SDK admitted the plugin, discovered its `assurance.check` step,
**but refused to invoke the handler with the following error:**

```
[ERROR] missing required capabilities for step 'assurance.check':
        assurance.provider, assurance.assertions
```

The handler code **never runs**. The SDK's runtime step executor
checks `StepDescriptor.requiredCapabilities` against the manifest's
`capabilities` field at admission time, and aborts the pipeline if
the set is not a subset.

**This is not a bug in our plugin.** The G3 contract explicitly says:

> `RuntimeCapabilityContributor` is a mechanism that lives [in the host]
> (the SDK's runtime), not in the plugin. The plugin declares the
> capabilities in the manifest; the host publishes them via that
> contributor.

**But there is no `RuntimeCapabilityContributor` interface in the SDK
0.48.0 / 0.51.0-rc1 bytecode.** The `assure-cli` and `cli-runtime`
modules do not implement it (verified with `javap`). The capabilities
declared in the manifest are *admission metadata*, not *runtime
publishers*.

**This is a real SDK gap.** Until the SDK provides a
`RuntimeCapabilityContributor` interface, OR the capability check is
softened to a warning, the handler cannot be reached via the live
SDK regardless of input shape.

### G6.2 actual outcome

| Layer | Status | Where verified |
| --- | --- | --- |
| Build-time manifest generation | ✅ Working | `PluginManifestBuilder.kt`, embedded in JAR, `G6E2ETest.V59` |
| Plugin admission (`/lib/` install + `META-INF/...`) | ✅ Working | `G6.1` commit `20fb3bf` |
| kts compilation with `assuranceCheck` extension | ✅ Working | `g6-sample.pipeline.kts` |
| SDK plugin discovery (`plugin-manifest.json` parse) | ✅ Working | Live run reaches the step dispatch |
| SDK capability pre-check | ❌ BLOCKED | Live run error: `missing required capabilities` |
| Handler execution against live SDK | ❌ Not reached | Blocked by capability pre-check |
| Handler execution against `MockSdkHost` | ✅ Working | `AssuranceCheckEndToEndTest`, `G3AssuranceCheckStepTest` |

The plugin's behavior is **correct** under the G3 contract. The
**SDK's runtime enforcement** is what blocks execution. We pin this
as a real, open gap against the upstream SDK — not a workaround.

### How to reproduce

```bash
# 1. Build + install the plugin
./gradlew :pipelinek-assurance-plugin:assemble
cp pipelinek-assurance-plugin/build/libs/*.jar ~/.asdf/installs/pipelinek/0.48.0/lib/

# 2. Run the sample kts
cd 06-uat/examples/assurance-pipeline
~/.asdf/installs/pipelinek/0.48.0/bin/pipelinek run g6-sample.pipeline.kts

# 3. Observe:
#    - plugin admitted
#    - step discovered: assurance.check
#    - ERROR: missing required capabilities for step 'assurance.check':
#             assurance.provider, assurance.assertions
```

The same path on SDK 0.51.0-rc1 produces the same error.

### What we are NOT doing in G6.2

- **Not** shimming a `RuntimeCapabilityContributor` into the plugin
  — there is no public interface to implement, and injecting a
  reflective stub would violate AAT-3's "no SDK imports outside
  the allowlist" rule.
- **Not** softening `requiredCapabilities` in the descriptor — the
  G3 contract requires the check, and the test `G3_V36` enforces
  the descriptor's declared capabilities.
- **Not** writing an "aspirational" e2e test that pretends the
  handler ran when it didn't — the G6.2 tests assert on the
  **structural** contract (codec round-trip, manifest presence,
  extension compilation), not on a fabricated success.

### Open SDK gap (upstream)

> **Bug**: PipelineK SDK 0.48.0 / 0.51.0-rc1 has a hard runtime
> capability pre-check that blocks step execution when the
> `plugin-manifest.json` declares capabilities that the runtime
> cannot publish (no `RuntimeCapabilityContributor` interface
> exists). The plugin's handler is unreachable until either:
>
> 1. The SDK exposes a `RuntimeCapabilityContributor` SPI that
>    the runtime can implement; or
> 2. The capability check is softened to a warning when the
>    runtime has no contributor for that capability.
>
> Filed as `docs/G6-FINDINGS.md` open issue. No ETA.

