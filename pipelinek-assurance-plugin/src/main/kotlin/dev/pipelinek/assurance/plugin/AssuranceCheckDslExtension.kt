// G6.2 (Bloque G) — Kotlin extension that wraps the SDK's
// `registryStep` for use from `.kts` script context.
//
// **Why this exists:**
// The SDK's `registryStep` is typed in Kotlin as
// `(String, PluginStepId, EncodedStepValue)`. Both `PluginStepId`
// and `EncodedStepValue` are inline classes wrapping `String`,
// so the JVM signature is `(String, String, String)` and from
// regular Kotlin code a raw `String` auto-wraps.
//
// However, the Kotlin scripting compiler used by PipelineK's
// `pipelinek run` (Kotlin 2.4.10 scripting host) does **not**
// auto-wrap raw `String` arguments into these inline classes.
// The compile error is one of:
//   - `Argument type mismatch: actual type is 'String', but 'EncodedStepValue' was expected.`
//   - `Argument type mismatch: actual type is 'EncodedStepValue', but 'String' was expected.`
// depending on whether you pass raw `String` or explicit
// `EncodedStepValue(...)` — neither works.
//
// The workaround: this extension function is plain Kotlin (not
// inline-class-typed), so the regular compiler auto-wraps the
// raw `String` parameters. From the `.kts`, the user calls
// `assuranceCheck(scope, name, stepKey, jsonInput)` with four
// plain `String` values; this function then calls
// `scope.registryStep(name, PluginStepId(stepKey), EncodedStepValue(jsonInput))`
// using the inline-class constructors that DO exist as static
// factory methods at the JVM level (verified by
// `PluginStepId.constructor-impl(String)` and
// `EncodedStepValue.constructor-impl(String)`).
//
// **Where the values come from:**
// The `Input` shape for `assurance.check` is fully defined in
// `AssuranceCheckStepDefinition.Input` (suite + evidence +
// mode + completenessPolicy). The kts caller is expected to
// build a JSON string that conforms to that schema. The
// `AssuranceCheckInputCodec` (in the same module) is the
// authoritative serializer; users can either build the JSON by
// hand or via the `AssuranceCheckInputBuilder` facade
// (`AssuranceCheckStepDsl.kt`).
//
// **AAT-3 (deliberate):** this file imports
// `dev.rubentxu.pipeline.*` because the entire point is to
// bridge the SDK's typed API. The `G3_V47` test's allowlist
// is extended to include this file. See the comment there for
// the rationale and the "growing allowlist is a code smell"
// warning.
package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.json.Json

/**
 * Calls the SDK's `registryStep` with raw `String` parameters,
 * doing the inline-class wrapping here (where the regular Kotlin
 * compiler handles it correctly).
 *
 * @param scope the receiver stage scope (from `stage("name") { ... }`).
 * @param name the step invocation name, used in event logs and
 *   output paths. Becomes the `StepSpec.name`.
 * @param stepKey the step type key, e.g. `"assurance.check"`. The
 *   SDK looks this up in the registered `StepDefinition` set
 *   populated by the plugin's `ServiceLoader`.
 * @param encodedInput the JSON-serialized `Input` DTO. Must
 *   conform to the schema declared in
 *   `AssuranceCheckStepDefinition.Input` (name, suite, evidence,
 *   mode, completenessPolicy).
 */
fun assuranceCheck(
    scope: StageScope,
    stepKey: String,
    encodedInput: String,
) {
    // The SDK's `registryStep` has Kotlin signature
    // `registryStep(stepKey: PluginStepId, encodedInput:
    // EncodedStepValue, schemaVersion: String = "dsl-v1")`.
    // There is NO separate `name` parameter — the step
    // invocation name comes from the outer `stage("name")` or
    // from a separate DSL field.
    //
    // The `-TmLEHys` JVM suffix is the inline-class mangle for
    // the (PluginStepId, EncodedStepValue) pair. The script
    // compiler used by `pipelinek run` does NOT auto-wrap raw
    // String into these inline classes, so we wrap them
    // explicitly here (where the regular compiler handles the
    // wrap correctly).
    //
    // The step key is `"assurance.check"`; we accept it as a
    // parameter for forward-compat (e.g. `assurance.verify`
    // could reuse this helper).
    scope.registryStep(
        PluginStepId(stepKey),
        EncodedStepValue(encodedInput),
    )
}

/**
 * Convenience overload that takes an `AssuranceCheckStepDefinition.Input`
 * directly and serializes it via the plugin's codec. This is the
 * recommended path from a `.kts` that has an `Input` value (built
 * via `AssuranceCheckInputBuilder` or hand-rolled).
 *
 * The `name` here is the step invocation name, used in the SDK's
 * event log. Note: this does NOT call `registryStep`; the SDK
 * still resolves the step by its `PluginStepId` (`"assurance.check"`).
 */
fun assuranceCheck(
    scope: StageScope,
    name: String,
    input: AssuranceCheckStepDefinition.Input,
) {
    @Suppress("UNUSED_PARAMETER")
    val unused = name // reserved for future event labels
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val encoded: String =
        json.encodeToString(AssuranceCheckStepDefinition.Input.serializer(), input)
    assuranceCheck(scope, AssuranceCheckStep.STEP_KEY, encoded)
}
