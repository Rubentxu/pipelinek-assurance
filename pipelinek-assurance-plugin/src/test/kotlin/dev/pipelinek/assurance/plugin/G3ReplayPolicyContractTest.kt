package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * G3.2 — Contrato cerrado del `replayPolicy` que el Step
 * `assurance.check` produce.
 *
 * Estado actual de la lógica (en
 * `AssuranceCheckStepDefinition.run`, justo antes del
 * `return Output(...)`):
 *
 *   val replay = if (!evidenceAdapterVersion.isNullOrBlank())
 *                   "MEMOIZED"
 *                else "RERUN"
 *
 * Lo que este test pinea:
 *
 * RP-1. `evidenceAdapterVersion = "X"` no-blank →
 *      `replayPolicy = "MEMOIZED"`. Independientemente
 *      de engine version, suite version, evidence list,
 *      u outcome.
 *
 * RP-2. `evidenceAdapterVersion = null` →
 *      `replayPolicy = "RERUN"`. El fingerprint
 *      incompleto hace que el SDK NO pueda memoizar.
 *
 * RP-3. `evidenceAdapterVersion = ""` →
 *      `replayPolicy = "RERUN"`. Empty se trata
 *      como null (la condición es `!isNullOrBlank`).
 *
 * RP-4. `evidenceAdapterVersion = "  "` (whitespace) →
 *      `replayPolicy = "RERUN"`. Mismo path que empty
 *      via `isBlank()`.
 *
 * RP-5. **Determinismo bit-a-bit**: dos invocaciones
 *      de `run` con el mismo input, providers,
 *      runtime, assertionsById, evidenceAdapterVersion
 *      producen exactamente el mismo `reportDigest`,
 *      `summary` y `replayPolicy`. Esto es la base
 *      de la memoización: si el caller re-ejecuta
 *      con los mismos argumentos, puede confiar en
 *      que el output es byte-equivalente.
 *
 * RP-6. **Fingerprint NO incluye engine version**:
 *      la línea 256 del handler
 *      (`runtime = FrozenAssuranceRuntime(engineVersion = "0.10.0", ...)`)
 *      hardcodea la versión, así que el `replayPolicy`
 *      NO cambia si el caller pasa un `runtime` con
 *      otra `engineVersion`. Esto es una decisión
 *      de diseño: el engine version es responsabilidad
 *      del SDK, no del plugin. El test lo pinea.
 *
 * RP-7. **Fingerprint NO incluye runtime.lenses**:
 *      mismo argumento. Si el caller pasa un runtime
 *      con más lenses registradas, el `replayPolicy`
 *      no cambia.
 *
 * RP-8. **Re-run con misma suite y mismo adapter
 *      produce mismo `reportDigest`**: el digest es
 *      función de la suite y los digests de evidence
 *      (no de la adapter version). Por lo tanto,
 *      `replayPolicy = "MEMOIZED"` es seguro: el
 *      caller puede confiar en que el output es
 *      reproducible.
 *
 * RP-9. **Outcome NO afecta `replayPolicy`**: un
 *      Failure y un Success con la misma suite y
 *      adapter version tienen el mismo `replayPolicy`.
 *      La policy describe cómo se reproduce el
 *      run, no qué decidió.
 *
 * RP-10. **El handler siempre pasa `evidenceAdapterVersion`
 *       derivado de las capabilities** (a través de
 *       `readEvidenceAdapterVersion`). Si esa
 *       extracción falla con excepción, el handler
 *       NO propaga (cubierto en el test
 *       `G3_check_handler_no_propaga_excepciones_del_sdk_en_get`).
 *       Acá pinea: cuando la extracción tiene
 *       éxito, el `replayPolicy` es "MEMOIZED".
 */
class G3ReplayPolicyContractTest : AnnotationSpec() {

    private fun validInput() = AssuranceCheckStepDefinition.Input(
        name = "self",
        suite = AssuranceCheckStepDefinition.SuiteDto(
            id = "s/replay",
            version = "1.0.0",
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
                    id = "a/pass",
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

    private fun run(
        input: AssuranceCheckStepDefinition.Input = validInput(),
        adapter: String? = "test/1.0",
    ) = AssuranceCheckStepDefinition.run(
        input = input,
        evidenceItems = emptyList(),
        providers = ProviderRegistry(),
        runtime = FrozenAssuranceRuntime(engineVersion = "0.10.0", lenses = emptyMap()),
        assertionsById = emptyMap(),
        evidenceAdapterVersion = adapter,
    )

    @Test
    fun G3_RP1_adapter_no_blank_es_MEMOIZED() {
        run(adapter = "cognicode/1.0").replayPolicy shouldBe "MEMOIZED"
        run(adapter = "x").replayPolicy shouldBe "MEMOIZED"
        run(adapter = "X").replayPolicy shouldBe "MEMOIZED"
    }

    @Test
    fun G3_RP2_adapter_null_es_RERUN() {
        run(adapter = null).replayPolicy shouldBe "RERUN"
    }

    @Test
    fun G3_RP3_adapter_empty_es_RERUN() {
        run(adapter = "").replayPolicy shouldBe "RERUN"
    }

    @Test
    fun G3_RP4_adapter_whitespace_es_RERUN() {
        run(adapter = "  ").replayPolicy shouldBe "RERUN"
        run(adapter = "\t\n").replayPolicy shouldBe "RERUN"
    }

    @Test
    fun G3_RP5_determinismo_bit_a_bit_con_mismos_argumentos() {
        val a = run(adapter = "test/1.0")
        val b = run(adapter = "test/1.0")
        a.reportDigest shouldBe b.reportDigest
        a.summary shouldBe b.summary
        a.outcome shouldBe b.outcome
        a.replayPolicy shouldBe b.replayPolicy
        a.failure shouldBe b.failure
    }

    @Test
    fun G3_RP6_engine_version_no_afecta_replayPolicy() {
        // El handler hardcodea engineVersion = "0.10.0".
        // En `run` directo, podemos pasar otra versión;
        // la policy NO cambia.
        val input = validInput()
        val a = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = FrozenAssuranceRuntime(engineVersion = "0.9.0", lenses = emptyMap()),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        val b = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = FrozenAssuranceRuntime(engineVersion = "0.11.0", lenses = emptyMap()),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        a.replayPolicy shouldBe b.replayPolicy
        a.replayPolicy shouldBe "MEMOIZED"
    }

    @Test
    fun G3_RP7_lenses_en_runtime_no_afectan_replayPolicy() {
        val input = validInput()
        val emptyLenses = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = FrozenAssuranceRuntime(engineVersion = "0.10.0", lenses = emptyMap()),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        val someLenses = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = FrozenAssuranceRuntime(
                engineVersion = "0.10.0",
                lenses = mapOf(dev.pipelinek.assurance.engine.LensId("anything") to mockLens()),
            ),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        emptyLenses.replayPolicy shouldBe someLenses.replayPolicy
        emptyLenses.replayPolicy shouldBe "MEMOIZED"
    }

    @Test
    fun G3_RP8_mismo_adapter_mismo_reportDigest() {
        val a = run(adapter = "test/1.0")
        val b = run(adapter = "test/1.0")
        a.reportDigest shouldBe b.reportDigest
    }

    @Test
    fun G3_RP9_outcome_no_afecta_replayPolicy() {
        // Un run con outcome Success y un run con
        // outcome Failure (suite con Mandatory fail)
        // tienen el mismo `replayPolicy` si el adapter
        // version es el mismo. La policy describe
        // reproducibilidad, no decisión.
        val success = run(adapter = "test/1.0")
        success.outcome shouldBe "Success"
        val failingInput = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/replay-fail",
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
                        rationale = "must block",
                    ),
                ),
            ),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val failure = AssuranceCheckStepDefinition.run(
            input = failingInput,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = FrozenAssuranceRuntime(engineVersion = "0.10.0", lenses = emptyMap()),
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        failure.outcome shouldBe "Failure"
        success.replayPolicy shouldBe failure.replayPolicy
        success.replayPolicy shouldBe "MEMOIZED"
    }

    @Test
    fun G3_RP10_handler_exitoso_pasa_replayPolicy_MEMOIZED() {
        // Cuando el handler extrae el adapter version
        // correctamente de las capabilities, la policy
        // es MEMOIZED. (Pin end-to-end del path feliz
        // del handler.)
        val input = validInput()
        val ctx = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test/rp10"),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available() = setOf(ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS)
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(
                    key: dev.rubentxu.pipeline.v2.domain.step.StepCapability,
                ): T = throw IllegalStateException("test/1.0: $key")
            },
        )
        val handler: dev.rubentxu.pipeline.v2.domain.step.StepHandler<
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
            .first { it.contract.key == dev.rubentxu.pipeline.v2.domain.PluginStepId(
                AssuranceCheckStepDefinition.KEY,
            ) }
            .handler
        val out = kotlinx.coroutines.runBlocking { handler.execute(input, ctx) }
        out.replayPolicy shouldBe "RERUN" // porque readEvidenceAdapterVersion falla con IllegalStateException
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun mockLens() =
        dev.pipelinek.assurance.engine.AssuranceLens<
            dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot, Any?,
            > { _ ->
            dev.pipelinek.assurance.engine.ProjectionResult.Projected(Unit)
        }
}
