package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json

/**
 * G3 (Bloque G) — Tests del contrato del Step `assurance.check`
 * tipado. Cierran las 4 sub-reglas de la regla 3 del bloque G:
 *
 * 1. `G3_check_typed_step_definition_ok`: el input/output pasa
 *    por `StepCodec` sin pérdida. El contract declara las
 *    codecs y se puede invocar con un input serializado.
 * 2. `G3_check_mandatory_bloquea`: una assertion Mandatory
 *    fallando produce `Output.outcome = "Failure"` con un
 *    `failure` estructurado; el handler NO lanza excepción.
 * 3. `G3_check_memoized_solo_con_fingerprint_completo`: el
 *    `Output.replayPolicy` es "MEMOIZED" sólo si el handler
 *    recibe `evidenceAdapterVersion` (o equivalente vía
 *    capability). Sin él, cae a "RERUN".
 * 4. `G3_check_capability_ausente_impide_ejecucion`: si el
 *    host no provee la capability `ASSURANCE_PROVIDER`, el
 *    Step retorna `outcome = "Aborted"` y el orchestrator
 *    nunca corre.
 *
 * Estos tests usan un `FakeCapabilityAccess` para inyectar
 * capabilities arbitrarias en el handler sin necesidad de un
 * SDK host completo. El handler real (`AssuranceCheckStepHandler`)
 * es la pieza bajo prueba; el `AssuranceCheckStepDefinition.run`
 * se prueba por separado.
 */
class G3AssuranceCheckStepTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // Test 1: input/output via StepCodec
    // -----------------------------------------------------------------

    @Test
    fun G3_check_typed_step_definition_ok() {
        // El contract expone codecs; la codec input y output
        // están en AssurancePluginContributor. Como son
        // `private class`, las ejercitamos indirectamente vía
        // `Json.encodeToString` y `Json.decodeFromString` con
        // los serializers de los DTOs públicos.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self-check",
            suite = minimalSuiteDto(),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(
            AssuranceCheckStepDefinition.Input.serializer(), input,
        )
        encoded shouldStartWith "{"
        encoded shouldContain "\"name\":\"self-check\""

        val decoded = json.decodeFromString(
            AssuranceCheckStepDefinition.Input.serializer(), encoded,
        )
        decoded.name shouldBe "self-check"
        decoded.mode shouldBe "FailClosed"

        // El contract de Step `assurance.check` es recuperable
        // desde el contributor y declara los codecs correctos.
        val contract = stepContract()
        contract.inputCodec.schema() shouldBe "assurance.check/v1.input"
        contract.outputCodec.schema() shouldBe "assurance.check/v1.output"
    }

    // -----------------------------------------------------------------
    // Test 2: Mandatory fail produce Failure, no throw
    // -----------------------------------------------------------------

    @Test
    fun G3_check_mandatory_bloquea() {
        // Suite con una assertion Mandatory que NO se puede
        // satisfacer (lens "missing-capability" no presente).
        // El handler corre con un fake `StepHandlerContext` que
        // provee las capabilities requeridas, así que llega
        // hasta el orchestrator. El Mandatory fail debe
        // serializarse en `Output.outcome = "Failure"` con
        // un `failure` estructurado que NO lanza excepción.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/mandatory-fail",
                version = "1.0.0",
                requiredEvidence = listOf("nope"),
                lenses = listOf(
                    AssuranceCheckStepDefinition.LensDto(
                        id = "l/no-cap",
                        kind = "no-op",
                        inputCapabilities = listOf("nope"),
                        outputSchema = "noop/v1",
                    ),
                ),
                assertions = listOf(
                    AssuranceCheckStepDefinition.AssertionDto(
                        id = "a/blocks",
                        lensRef = "l/no-cap",
                        operator = "eq",
                        operands = mapOf("value" to "0"),
                        severity = "Critical",
                        enforcement = "Mandatory",
                        completenessRequirements = listOf("nope"),
                        rationale = "must block when evidence is missing",
                    ),
                ),
            ),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )

        val output = shouldNotThrowAny {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = ProviderRegistry(),
                runtime = dev.pipelinek.assurance.engine.FrozenAssuranceRuntime(
                    engineVersion = "0.10.0-test",
                    lenses = emptyMap(),
                ),
                assertionsById = emptyMap(),
                evidenceAdapterVersion = "test/1.0",
            )
        }

        // La forma del output: outcome es Failure y failure
        // trae el counterexample estructurado. NO es Aborted
        // (la Mandatory fail se serializa como Failure, no se
        // aborta por excepción). El counterexample lista la
        // assertion bloqueante `a/blocks` como
        // `blockingAssertionIds`.
        output.outcome shouldBe "Failure"
        val failure = output.failure!!
        failure.kind shouldBe "mandatory-failure"
        failure.message.isNotEmpty() shouldBe true
        failure.blockingAssertionIds shouldContain "a/blocks"
        // Y NO lanzó: `shouldNotThrowAny` ya garantizó eso.
    }

    // -----------------------------------------------------------------
    // Test 3: MEMOIZED sólo con fingerprint completo
    // -----------------------------------------------------------------

    @Test
    fun G3_check_memoized_solo_con_fingerprint_completo() {
        // Caso "con adapter version" — replayPolicy = MEMOIZED.
        val input = minimalInput("s/memo")
        val withAdapter = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = dev.pipelinek.assurance.engine.FrozenAssuranceRuntime(
                engineVersion = "0.10.0", lenses = emptyMap(),
            ),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "cognicode/1.0",
        )
        withAdapter.replayPolicy shouldBe "MEMOIZED"

        // Caso "sin adapter version" — replayPolicy = RERUN.
        val withoutAdapter = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = dev.pipelinek.assurance.engine.FrozenAssuranceRuntime(
                engineVersion = "0.10.0", lenses = emptyMap(),
            ),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = null,
        )
        withoutAdapter.replayPolicy shouldBe "RERUN"

        // Caso "adapter version blank" — replayPolicy = RERUN.
        val blankAdapter = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = dev.pipelinek.assurance.engine.FrozenAssuranceRuntime(
                engineVersion = "0.10.0", lenses = emptyMap(),
            ),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "  ",
        )
        blankAdapter.replayPolicy shouldBe "RERUN"
    }

    // -----------------------------------------------------------------
    // Test 4: capability ausente → Aborted, no run
    // -----------------------------------------------------------------

    @Test
    fun G3_check_capability_ausente_impide_ejecucion() {
        val input = minimalInput("s/no-cap")
        // Fake context SIN las capabilities del plugin. El
        // handler detecta la ausencia y aborta sin lanzar.
        val ctx = fakeContext(available = emptySet())
        val output = kotlinx.coroutines.runBlocking {
            stepHandler().execute(input, ctx)
        }
        output.outcome shouldBe "Aborted"
        val failure = output.failure!!
        failure.kind shouldBe "missing-capability"
        failure.message shouldContain ASSURANCE_PROVIDER.key
        failure.message shouldContain ASSURANCE_ASSERTIONS.key
        // replayPolicy = RERUN: no hubo run, no se puede memoizar.
        output.replayPolicy shouldBe "RERUN"
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun minimalInput(suiteId: String): AssuranceCheckStepDefinition.Input =
        AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = minimalSuiteDto(suiteId),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )

    /**
     * Suite mínima válida: `AssuranceSuiteIR` exige al menos una
     * lens y al menos una assertion (AAT-16). Para el test de
     * MEMOIZED no nos importa el outcome, sólo la policy. La
     * assertion es `Advisory` (no `Mandatory`) para que el
     * outcome sea Success y el fingerprint cubra todos los
     * inputs (sin Mandatory fail).
     */
    private fun minimalSuiteDto(id: String = "s/minimal"): AssuranceCheckStepDefinition.SuiteDto =
        AssuranceCheckStepDefinition.SuiteDto(
            id = id,
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
        )

    private fun stepContract(): dev.rubentxu.pipeline.v2.domain.step.StepContract<
        AssuranceCheckStepDefinition.Input,
        AssuranceCheckStepDefinition.Output,
    > = AssurancePluginContributor()
        .definitions()
        .filterIsInstance<
            dev.rubentxu.pipeline.v2.domain.step.StepDefinition<
                AssuranceCheckStepDefinition.Input,
                AssuranceCheckStepDefinition.Output,
            >,
        >()
        .first { it.contract.key == dev.rubentxu.pipeline.v2.domain.PluginStepId(AssuranceCheckStepDefinition.KEY) }
        .contract

    private fun stepHandler(): dev.rubentxu.pipeline.v2.domain.step.StepHandler<
        AssuranceCheckStepDefinition.Input,
        AssuranceCheckStepDefinition.Output,
    > = AssurancePluginContributor()
        .definitions()
        .filterIsInstance<
            dev.rubentxu.pipeline.v2.domain.step.StepDefinition<
                AssuranceCheckStepDefinition.Input,
                AssuranceCheckStepDefinition.Output,
            >,
        >()
        .first { it.contract.key == dev.rubentxu.pipeline.v2.domain.PluginStepId(AssuranceCheckStepDefinition.KEY) }
        .handler

    private fun fakeContext(
        available: Set<StepCapability>,
    ): StepHandlerContext = StepHandlerContext(
        runId = dev.rubentxu.pipeline.v2.domain.RunId("test/run-1"),
        stepIndex = 0,
        capabilities = object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = available
            // El override es `<T : Any> T get(...)`: no-null T.
            // Cuando el caller pide un `T` que no podemos satisfacer,
            // lanzamos `IllegalStateException` (mismo comportamiento
            // que un CapabilityAccess del SDK real). El handler
            // captura esa excepción y la trata como capability ausente.
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(capability: StepCapability): T {
                throw IllegalStateException("capability $capability not present")
            }
        },
    )
}
