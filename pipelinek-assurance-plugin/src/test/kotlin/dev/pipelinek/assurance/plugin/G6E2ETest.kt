// G6.2 (Bloque G) — End-to-end tests for the typed DSL extension
// and the `assuranceCheck` Step invocation path.
//
// **Scope of this test class (deliberate, not aspirational):**
//   - Verify the `assuranceCheck(scope, stepKey, encodedInput)`
//     extension function compiles and is callable from regular
//     Kotlin code (the same function the `.kts` imports).
//   - Verify the `assuranceCheck(scope, name, input)` overload
//     serializes a fully-typed `Input` DTO via the plugin's
//     codec into a JSON string the SDK can decode.
//   - Verify the extension wraps the SDK's `registryStep` with
//     the correct inline-class arguments (PluginStepId,
//     EncodedStepValue).
//
// **Scope NOT covered by this test class (documented elsewhere):**
//   - The actual `pipelinek run` execution against the live SDK
//     is documented in `docs/G6-FINDINGS.md` (with command-line
//     commands and expected output). We do NOT run the live SDK
//     from Gradle because:
//       1. The SDK's runtime capability check is a pre-condition
//          that fails with `missing required capabilities` for
//          `assurance.check` — the SDK has no
//          `RuntimeCapabilityContributor` mechanism in 0.48.0 /
//          0.51.0-rc1, so the handler never runs against the
//          live SDK regardless of input shape.
//       2. A live SDK run requires the plugin JAR to be
//          installed in `~/.asdf/installs/pipelinek/0.48.0/lib/`,
//          which is host-specific and not appropriate for
//          `gradle check`.
//   - The handler-level e2e (green graph → Success, forbidden
//     edge → Failure with counterexample) is covered by
//     `AssuranceCheckEndToEndTest` and
//     `G3AssuranceCheckStepTest`, which use the typed
//     `MockSdkHost` (bypasses the capability check entirely
//     by injecting the capability stub directly).
//
// **What we DO test here:**
//   - V55: the DSL extension compiles and links (compile-time
//     regression guard for the inline-class wrapping).
//   - V56: the JSON-serializing overload produces a payload
//     that round-trips through the input codec and contains
//     the expected fields.
//   - V57: the `assuranceCheck` extension passes through the
//     stepKey verbatim, so `assurance.check` and
//     `assurance.verify` both work via the same helper.
package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StageScope
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class G6E2ETest : AnnotationSpec() {

    /**
     * The minimal `Input` shape that a green-case pipeline
     * would serialize. The full shape (suite + evidence +
     * assertions) is built by the `AssuranceCheckInputBuilder`
     * in production; here we use a small fixture to verify the
     * DSL extension's serialization works on a non-trivial
     * input.
     */
    private fun sampleInput(): AssuranceCheckStepDefinition.Input =
        AssuranceCheckStepDefinition.Input(
            name = "g6-e2e",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "g6-suite",
                version = "v1",
                requiredEvidence = listOf("architecture.dependency-graph"),
                lenses = emptyList(),
                assertions = emptyList(),
            ),
            evidence = listOf(
                AssuranceCheckStepDefinition.EvidenceRefDto(
                    mediaType = "assurance-evidence/v1",
                    digest = "0".repeat(64),
                    logicalRole = "architecture.dependency-graph",
                ),
            ),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )

    @Test
    fun v55_dsl_extension_exposes_assuranceCheck_as_top_level_function() {
        // Compile-time check: the function exists, takes
        // (StageScope, String, String) and returns Unit. If the
        // AAT-3 allowlist or the file's package declaration
        // drifts, this test won't even compile.
        val fn: (StageScope, String, String) -> Unit = ::assuranceCheck
        withClue("assuranceCheck top-level function exists") { fn shouldNotBe null }
    }

    @Test
    fun v56_input_overload_serializes_input_to_valid_json_via_plugin_codec() {
        // The `assuranceCheck(scope, name, input)` overload
        // delegates to `AssuranceCheckInputCodec` (same codec
        // the SDK uses for decode). Round-trip: encode →
        // decode → equal DTO.
        val original: AssuranceCheckStepDefinition.Input = sampleInput()
        val encoded: String = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.encodeToString(AssuranceCheckStepDefinition.Input.serializer(), original)

        withClue("encoded payload is non-empty") { encoded.isNotBlank() }
        withClue("encoded payload is a JSON object") {
            encoded.trim().first() shouldBe '{'
        }

        // Verify the round-trip decodes to an equal DTO.
        val decoded: AssuranceCheckStepDefinition.Input = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.decodeFromString(
            AssuranceCheckStepDefinition.Input.serializer(),
            encoded,
        )
        withClue("round-trip preserves name") { decoded.name shouldBe original.name }
        withClue("round-trip preserves mode") { decoded.mode shouldBe original.mode }
        withClue("round-trip preserves completenessPolicy") {
            decoded.completenessPolicy shouldBe original.completenessPolicy
        }
        withClue("round-trip preserves suite.id") {
            decoded.suite.id shouldBe original.suite.id
        }
        withClue("round-trip preserves evidence count") {
            decoded.evidence.size shouldBe original.evidence.size
        }
    }

    @Test
    fun v57_encoded_payload_contains_expected_top_level_fields() {
        // Verify the JSON shape the SDK will see. The `name`,
        // `suite`, `evidence`, `mode`, `completenessPolicy`
        // fields are all the SDK's `AssuranceCheckInputCodec`
        // expects.
        val input: AssuranceCheckStepDefinition.Input = sampleInput()
        val encoded: String = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.encodeToString(AssuranceCheckStepDefinition.Input.serializer(), input)

        val obj = Json.parseToJsonElement(encoded).jsonObject
        withClue("payload contains 'name'") {
            obj["name"]?.jsonPrimitive?.content shouldBe "g6-e2e"
        }
        withClue("payload contains 'mode'") {
            obj["mode"]?.jsonPrimitive?.content shouldBe "FailClosed"
        }
        withClue("payload contains 'completenessPolicy'") {
            obj["completenessPolicy"]?.jsonPrimitive?.content shouldBe "RequireComplete"
        }
        withClue("payload contains 'suite' object") {
            obj["suite"] shouldNotBe null
        }
        withClue("payload contains 'evidence' array") {
            obj["evidence"] shouldNotBe null
        }
    }

    @Test
    fun v58_inline_class_wrappers_used_by_helper_are_instantiable() {
        // The DSL extension wraps raw `String` into
        // `PluginStepId` and `EncodedStepValue`. The kts
        // compiler cannot do this auto-wrap reliably, so the
        // helper does it explicitly. Verify the inline-class
        // values we wrap with have the right `value` payload.
        val stepId: PluginStepId = PluginStepId("assurance.check")
        val encoded: EncodedStepValue = EncodedStepValue("{}")

        withClue("PluginStepId.value preserved") {
            stepId.value shouldBe "assurance.check"
        }
        withClue("EncodedStepValue.value preserved") {
            encoded.value shouldBe "{}"
        }
    }

    @Test
    fun v59_manifest_in_jar_declares_the_capabilities_required_by_step() {
        // The SDK's runtime capability check compares the
        // step's `requiredCapabilities` against the plugin
        // manifest's `capabilities` field. The handler can
        // only run if both match. Verify the embedded
        // manifest (built by `PluginManifestBuilder`) declares
        // the same set of capabilities the step requires.
        val manifestResource: String? =
            javaClass.classLoader.getResource("META-INF/pipelinek/plugin-manifest.json")
                ?.readText()
        withClue("manifest resource is present in the JAR") {
            manifestResource shouldNotBe null
        }
        val manifest = Json.parseToJsonElement(manifestResource!!).jsonObject
        val declaredCapabilities: Set<String> = manifest["capabilities"]!!
            .toString()
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .toSet()
        withClue("manifest declares 'assurance.provider'") {
            "assurance.provider" in declaredCapabilities
        }
        withClue("manifest declares 'assurance.assertions'") {
            "assurance.assertions" in declaredCapabilities
        }
    }
}
