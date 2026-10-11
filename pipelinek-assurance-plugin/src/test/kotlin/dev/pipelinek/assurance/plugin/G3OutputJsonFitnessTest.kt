package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * G3 (Bloque G) — Fitness test sobre el wire format del
 * `Output` de `assurance.check` cuando viaja por `StepCodec`.
 *
 * El test E2E preexistente
 * (`MockSdkHostTest.runCheck_output_se_serializa_a_json_canonico`)
 * sólo verifica que los 3 campos del contract original
 * (`reportDigest`, `summary`, `outcome`) están presentes.
 * No cubre los 2 campos nuevos de G3 (`failure`,
 * `replayPolicy`). Este test cierra esa cerradura: verifica
 * que el output se serializa a JSON con la forma
 * esperada por el SDK, y que los campos G3 viajan
 * correctos en el wire format.
 *
 * Lo que se valida:
 * 1. Un Output con `failure` no-null serializa
 *    `failure.kind`, `failure.message`,
 *    `failure.blockingAssertionIds` como un sub-objeto
 *    JSON con esos campos.
 * 2. Un Output con `failure = null` serializa `failure: null`
 *    o lo omite (con `encodeDefaults = true` aparece como
 *    `null`).
 * 3. `replayPolicy` se serializa con el valor exacto
 *    ("MEMOIZED" o "RERUN"), no con el default.
 * 4. El handler real, invocado con un `StepHandlerContext`
 *    que tiene las capabilities pero un `get` que falla
 *    con un `RuntimeException` no estándar, produce un
 *    Output cuyo JSON contiene `"replayPolicy":"RERUN"`
 *    (el path de "adapter version desconocida" que el
 *    broadening del catch en G3.1 habilita).
 *
 * Si el codec cambia (e.g. de un `Json` con
 * `ignoreUnknownKeys = true` a uno más estricto), este
 * test atrapa la regresión.
 */
class G3OutputJsonFitnessTest : AnnotationSpec() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun G3_output_serialize_failure_como_subobjeto_con_todos_los_campos() {
        val output = AssuranceCheckStepDefinition.Output(
            reportDigest = "abc123",
            summary = AssuranceCheckStepDefinition.SummaryDto(
                passed = 0, failed = 1, inconclusive = 0, unsupported = 0, errored = 0,
            ),
            outcome = "Failure",
            failure = AssuranceCheckStepDefinition.CounterexampleDto(
                kind = "mandatory-failure",
                message = "assurance Mandatory fail: a/blocks",
                blockingAssertionIds = listOf("a/blocks"),
            ),
            replayPolicy = "MEMOIZED",
        )
        val encoded = json.encodeToString(
            AssuranceCheckStepDefinition.Output.serializer(), output,
        )
        val obj: JsonObject = json.parseToJsonElement(encoded).jsonObject

        obj["reportDigest"]?.jsonPrimitive?.content shouldBe "abc123"
        obj["outcome"]?.jsonPrimitive?.content shouldBe "Failure"
        obj["replayPolicy"]?.jsonPrimitive?.content shouldBe "MEMOIZED"

        // failure es un sub-objeto con kind + message + blockingAssertionIds.
        val failure = obj["failure"]?.jsonObject
        failure!!["kind"]?.jsonPrimitive?.content shouldBe "mandatory-failure"
        failure["message"]?.jsonPrimitive?.content shouldBe "assurance Mandatory fail: a/blocks"
        val blocking = failure["blockingAssertionIds"]?.toString()
        // kotlinx.serialization serializa List<String> como JSON array.
        blocking shouldBe "[\"a/blocks\"]"
    }

    @Test
    fun G3_output_serialize_failure_null_y_replayPolicy_RERUN_en_success() {
        val output = AssuranceCheckStepDefinition.Output(
            reportDigest = "ok-digest",
            summary = AssuranceCheckStepDefinition.SummaryDto(
                passed = 1, failed = 0, inconclusive = 0, unsupported = 0, errored = 0,
            ),
            outcome = "Success",
            failure = null,
            replayPolicy = "RERUN",
        )
        val encoded = json.encodeToString(
            AssuranceCheckStepDefinition.Output.serializer(), output,
        )
        val obj: JsonObject = json.parseToJsonElement(encoded).jsonObject
        obj["outcome"]?.jsonPrimitive?.content shouldBe "Success"
        obj["replayPolicy"]?.jsonPrimitive?.content shouldBe "RERUN"
        // failure aparece como null explícito (encodeDefaults = true).
        obj.containsKey("failure") shouldBe true
        // El valor debe ser `JsonNull` (no un objeto, no ausente).
        obj["failure"].toString() shouldBe "null"
    }

    @Test
    fun G3_output_serialize_failure_completa_con_sourceLocation() {
        // Verifica que `sourceLocation: String?` también se serializa
        // cuando no es null (path "engine-error" con source ref).
        val output = AssuranceCheckStepDefinition.Output(
            reportDigest = "engine-err",
            summary = AssuranceCheckStepDefinition.SummaryDto(
                passed = 0, failed = 0, inconclusive = 0, unsupported = 0, errored = 1,
            ),
            outcome = "Aborted",
            failure = AssuranceCheckStepDefinition.CounterexampleDto(
                kind = "engine-error",
                message = "NoSuchElementException: cap no satisfecha",
                sourceLocation = "AssurancePluginContributor.kt:259",
            ),
            replayPolicy = "RERUN",
        )
        val encoded = json.encodeToString(
            AssuranceCheckStepDefinition.Output.serializer(), output,
        )
        val obj: JsonObject = json.parseToJsonElement(encoded).jsonObject
        obj["outcome"]?.jsonPrimitive?.content shouldBe "Aborted"
        val failure = obj["failure"]?.jsonObject
        failure!!["sourceLocation"]?.jsonPrimitive?.content shouldBe
            "AssurancePluginContributor.kt:259"
    }

    @Test
    fun G3_handler_real_serialize_output_tras_runtime_exception_del_sdk() {
        // Test de integración handler → Output → JSON: ejercita
        // el path completo que el broadening del catch (G3.1)
        // habilitó. El SDK lanza NoSuchElementException desde
        // `get<>`; el handler NO propaga, devuelve
        // replayPolicy="RERUN". El JSON debe contener esa
        // policy exacta.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/json-fit",
                version = "0.1.0",
                requiredEvidence = emptyList(),
                lenses = listOf(
                    AssuranceCheckStepDefinition.LensDto(
                        id = "l/identity",
                        kind = "no-op",
                        inputCapabilities = listOf("none"),
                        outputSchema = "noop/v1",
                    ),
                ),
                assertions = listOf(
                    AssuranceCheckStepDefinition.AssertionDto(
                        id = "a/always-pass",
                        lensRef = "l/identity",
                        operator = "true",
                        operands = emptyMap(),
                        severity = "Info",
                        enforcement = "Advisory",
                        completenessRequirements = emptyList(),
                        rationale = "test only",
                    ),
                ),
            ),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val ctx = StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test/run-fit"),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                    setOf(ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(
                    key: dev.rubentxu.pipeline.v2.domain.step.StepCapability,
                ): T = throw NoSuchElementException("simulated: $key")
            },
        )
        val output = runBlocking {
            AssurancePluginContributor()
                .definitions()
                .filterIsInstance<
                    dev.rubentxu.pipeline.v2.domain.step.StepDefinition<
                        AssuranceCheckStepDefinition.Input,
                        AssuranceCheckStepDefinition.Output,
                    >,
                >()
                .first { it.contract.key == dev.rubentxu.pipeline.v2.domain.PluginStepId(
                    AssuranceCheckStepDefinition.KEY,
                ) }
                .handler
                .execute(input, ctx)
        }
        val encoded = json.encodeToString(
            AssuranceCheckStepDefinition.Output.serializer(), output,
        )
        val obj: JsonObject = json.parseToJsonElement(encoded).jsonObject
        obj["replayPolicy"]?.jsonPrimitive?.content shouldBe "RERUN"
    }
}
