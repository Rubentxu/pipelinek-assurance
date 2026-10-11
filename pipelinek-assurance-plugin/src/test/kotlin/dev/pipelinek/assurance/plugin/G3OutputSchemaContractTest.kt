package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldBeSameSizeAs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * G3.2 — Cross-checks del `outputSchema` declarado
 * (`assurance.check/v1.output`) contra la forma real
 * del `Output` serializado.
 *
 * El `outputSchema` es un string que el descriptor
 * publica para que el SDK sepa qué esperar. Si el
 * Output tiene un campo que no está en el schema,
 * el caller que use el schema se rompe. Si el schema
 * promete un campo que el Output no tiene, el caller
 * recibe null. El test verifica que ambos lados
 * están sincronizados.
 *
 * Output del plugin (5 campos):
 *   - reportDigest: String
 *   - summary: SummaryDto
 *   - outcome: String
 *   - failure: CounterexampleDto?
 *   - replayPolicy: String
 *
 * Lo que el schema debería prometer (en términos
 * de "shape observable en JSON"):
 *   - "reportDigest": String
 *   - "summary": Object con passed/failed/inconclusive/
 *     unsupported/errored
 *   - "outcome": "Success" | "Failure" | "Aborted"
 *   - "failure": Object o null
 *   - "replayPolicy": "MEMOIZED" | "RERUN"
 *
 * El schema real es un string libre
 * (`assurance.check/v1.output`); el SDK no lo
 * valida. Pero la convención del repo es que ese
 * string es un "schema name" que el caller
 * mapea. Si cambia el nombre, los consumidores
 * existentes se rompen. Pinneamos:
 *
 * X-1. `outputSchema` es exactamente
 *      `assurance.check/v1.output`.
 * X-2. `inputSchema` es exactamente
 *      `assurance.check/v1.input`.
 * X-3. El JSON serializado de un `Output` "Success"
 *      tiene exactamente 5 keys top-level.
 * X-4. El JSON serializado de un `Output` "Failure"
 *      tiene 5 keys (failure no-null, replayPolicy "MEMOIZED").
 * X-5. Las 5 keys top-level son las esperadas.
 * X-6. La key `failure` puede ser null o un objeto
 *      con kind/message/blockingAssertionIds/sourceLocation.
 * X-7. La key `summary` tiene exactamente 5 sub-keys
 *      (passed, failed, inconclusive, unsupported, errored).
 *
 * Esto no se chequeaba en G3.1. El bug que evita:
 * que alguien añada un campo nuevo al Output y
 * olvide actualizar el schema string, o viceversa.
 */
class G3OutputSchemaContractTest : AnnotationSpec() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun contract() = AssurancePluginContributor()
        .definitions()
        .filterIsInstance<
            dev.rubentxu.pipeline.v2.domain.step.StepDefinition<
                AssuranceCheckStepDefinition.Input,
                AssuranceCheckStepDefinition.Output,
                >,
            >()
        .first { it.contract.key == PluginStepId(AssuranceCheckStepDefinition.KEY) }
        .contract

    @Test
    fun G3_X1_outputSchema_es_assurance_check_v1_output() {
        contract().descriptor.outputSchema shouldBe "assurance.check/v1.output"
    }

    @Test
    fun G3_X2_inputSchema_es_assurance_check_v1_input() {
        contract().descriptor.inputSchema shouldBe "assurance.check/v1.input"
    }

    @Test
    fun G3_X3_output_Success_serializa_con_5_keys_top_level() {
        val out = AssuranceCheckStepDefinition.Output(
            reportDigest = "abc",
            summary = AssuranceCheckStepDefinition.SummaryDto(1, 0, 0, 0, 0),
            outcome = "Success",
            failure = null,
            replayPolicy = "RERUN",
        )
        val obj: JsonObject = json.parseToJsonElement(
            json.encodeToString(AssuranceCheckStepDefinition.Output.serializer(), out),
        ).jsonObject
        obj.size shouldBe 5
    }

    @Test
    fun G3_X4_output_Failure_serializa_con_5_keys_incluyendo_failure_no_null() {
        val out = AssuranceCheckStepDefinition.Output(
            reportDigest = "abc",
            summary = AssuranceCheckStepDefinition.SummaryDto(0, 1, 0, 0, 0),
            outcome = "Failure",
            failure = AssuranceCheckStepDefinition.CounterexampleDto(
                kind = "mandatory-failure",
                message = "m",
                blockingAssertionIds = listOf("a/x"),
            ),
            replayPolicy = "MEMOIZED",
        )
        val obj: JsonObject = json.parseToJsonElement(
            json.encodeToString(AssuranceCheckStepDefinition.Output.serializer(), out),
        ).jsonObject
        obj.size shouldBe 5
        // failure no es null.
        obj["failure"].toString() shouldNotBe "null"
    }

    @Test
    fun G3_X5_las_5_keys_son_las_esperadas() {
        val out = AssuranceCheckStepDefinition.Output(
            reportDigest = "abc",
            summary = AssuranceCheckStepDefinition.SummaryDto(1, 0, 0, 0, 0),
            outcome = "Success",
            failure = null,
            replayPolicy = "RERUN",
        )
        val obj: JsonObject = json.parseToJsonElement(
            json.encodeToString(AssuranceCheckStepDefinition.Output.serializer(), out),
        ).jsonObject
        obj.keys.sorted() shouldBe listOf(
            "failure", "outcome", "replayPolicy", "reportDigest", "summary",
        ).sorted()
    }

    @Test
    fun G3_X6_failure_es_objeto_o_null() {
        val withFailure = AssuranceCheckStepDefinition.Output(
            reportDigest = "x", summary = AssuranceCheckStepDefinition.SummaryDto(0, 0, 0, 0, 0),
            outcome = "Failure",
            failure = AssuranceCheckStepDefinition.CounterexampleDto(
                kind = "engine-error", message = "y",
            ),
            replayPolicy = "RERUN",
        )
        val withNull = withFailure.copy(failure = null)
        listOf(withFailure, withNull).forEach { out ->
            val obj: JsonObject = json.parseToJsonElement(
                json.encodeToString(
                    AssuranceCheckStepDefinition.Output.serializer(), out,
                ),
            ).jsonObject
            val failureStr = obj["failure"].toString()
            if (out.failure == null) {
                failureStr shouldBe "null"
            } else {
                failureStr.startsWith("{") shouldBe true
                failureStr.contains("\"kind\"") shouldBe true
                failureStr.contains("\"message\"") shouldBe true
                failureStr.contains("\"blockingAssertionIds\"") shouldBe true
                failureStr.contains("\"sourceLocation\"") shouldBe true
            }
        }
    }

    @Test
    fun G3_X7_summary_tiene_5_subkeys() {
        val out = AssuranceCheckStepDefinition.Output(
            reportDigest = "x",
            summary = AssuranceCheckStepDefinition.SummaryDto(
                passed = 1, failed = 2, inconclusive = 3, unsupported = 4, errored = 5,
            ),
            outcome = "Success",
            failure = null,
            replayPolicy = "RERUN",
        )
        val obj: JsonObject = json.parseToJsonElement(
            json.encodeToString(AssuranceCheckStepDefinition.Output.serializer(), out),
        ).jsonObject
        val summary = obj["summary"]!!.jsonObject
        summary.size shouldBe 5
        summary.keys.sorted() shouldBe listOf(
            "errored", "failed", "inconclusive", "passed", "unsupported",
        ).sorted()
        // Verifica que los valores son los correctos.
        summary["passed"]?.jsonPrimitive?.content shouldBe "1"
        summary["failed"]?.jsonPrimitive?.content shouldBe "2"
        summary["inconclusive"]?.jsonPrimitive?.content shouldBe "3"
        summary["unsupported"]?.jsonPrimitive?.content shouldBe "4"
        summary["errored"]?.jsonPrimitive?.content shouldBe "5"
    }
}
