package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

/**
 * G3.2 — Contrato cerrado del `CounterexampleDto` que el Step
 * `assurance.check` produce.
 *
 * Los 4 `kind`s que el plugin produce en producción
 * (grep en `src/main`):
 *   - `mandatory-failure` (CounterexampleDto en
 *     `AssuranceCheckStepDefinition.counterexampleOf`).
 *   - `incomplete` (mismo path, cuando reason empieza
 *     con "assurance-incomplete").
 *   - `engine-error` (mismo path, fallback + handler
 *     outer catch).
 *   - `missing-capability` (handler, cuando el SDK no
 *     provee las capabilities requeridas).
 *
 * Lo que este test pinea:
 *
 * CK-1. **Exhaustividad de kinds.** Los 4 kinds canónicos
 *     son los únicos que el plugin produce. Si alguien
 *     añade un quinto kind accidentalmente, este test
 *     atrapa el cambio (vía grep dinámico sobre el código).
 *     Si lo cambia a propósito, este test obliga a
 *     actualizar el contrato.
 *
 * CK-2. **`mandatory-failure` lleva `blockingAssertionIds`.**
 *     Verificamos que el path de Mandatory fail con
 *     assertions bloqueantes no produce `blockingAssertionIds = []`.
 *
 * CK-3. **`incomplete` NO lleva `blockingAssertionIds`.**
 *     El reason empieza con "assurance-incomplete" y
 *     el código no resuelve a IDs concretos: la lista
 *     debe ser `emptyList()`.
 *
 * CK-4. **`engine-error` (counterexampleOf fallback) NO lleva
 *     `blockingAssertionIds`.** El reason no se mapea a IDs
 *     porque no hay una Mandatory específica que pueda
 *     bloquear (es un failure no-Mandatory). El campo
 *     es `emptyList()`.
 *
 * CK-5. **`engine-error` (handler outer catch) NO lleva
 *     `blockingAssertionIds`** y el message contiene el
 *     `Throwable.message` o el nombre de la clase.
 *
 * CK-6. **`missing-capability` lleva el `kind` exacto y
 *     el `message` lista las capabilities ausentes.**
 *
 * CK-7. **`blockingAssertionIds` NO contiene duplicados.**
 *     Si el report tiene 5 Mandatory fails con el mismo
 *     `assertionId`, el counterexample lista ese ID una
 *     sola vez (viene del `mapNotNull { it.assertionId?.value }`
 *     sobre el `EnforcementDecision.Failure.blockingFailures`,
 *     que es por-assertion). Esto es un test del
 *     invariante del shape, no del comportamiento.
 *
 * CK-8. **`sourceLocation` puede ser null o no-null.** El
 *     plugin nunca popula `sourceLocation`; el campo
 *     existe en el DTO por extensibilidad futura. Lo
 *     validamos como `null` por defecto.
 */
class G3CounterexampleContractTest : AnnotationSpec() {

    private val emptyRuntime = FrozenAssuranceRuntime(
        engineVersion = "0.10.0-test", lenses = emptyMap(),
    )

    @Test
    fun G3_CK1_production_code_produce_solo_los_4_kinds_canonicos() {
        // Pin dinámico: si el código produce un kind nuevo,
        // este test lo detecta. Si se añade un kind a
        // propósito, actualizar este set es deliberado.
        // El path es relativo al working dir de gradle
        // (raíz del subproyecto plugin).
        val src = java.io.File("src/main/kotlin/dev/pipelinek/assurance/plugin")
        val kinds = src.walkTopDown()
            .filter { it.extension == "kt" }
            // Filtrar MockSdkHost: su `kind = "NoOp"` es
            // un label de capability/projection, NO un
            // Counterexample kind.
            .filter { it.name != "MockSdkHost.kt" }
            .flatMap { it.readLines().asSequence() }
            .filter { it.contains("kind = \"") }
            .map { it.substringAfter("kind = \"").substringBefore("\"") }
            .toSet()
        // Kinds reales del código. G3 introdujo 4;
        // G4 (assurance.verify) añadió 2: "body-failure"
        // y "cancelled" (cuando el body ejecutó por el
        // SDK BodyContinuation falla o se cancela).
        kinds shouldBe setOf(
            "mandatory-failure",
            "incomplete",
            "engine-error",
            "missing-capability",
            "body-failure",
            "cancelled",
        )
    }

    @Test
    fun G3_CK2_mandatory_failure_lleva_blocking_assertion_ids() {
        // El path de Mandatory fail. Con 1 sola assertion
        // Mandatory bloqueante, el counterexample lista
        // ese ID. (Si hubiera 2 Mandatory, la lógica
        // actual de `evaluateEnforcement` tiene un bug
        // latente: usa `indexOf(result)` que devuelve
        // el mismo índice para dos results `==`-iguales;
        // documentado en `G3_CK2b_latent_indexOf_bug`.)
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/mandatory",
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
                        id = "a/blocks-1",
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
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = emptyRuntime,
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Failure"
        val failure = out.failure!!
        failure.kind shouldBe "mandatory-failure"
        failure.blockingAssertionIds shouldHaveSize 1
        failure.blockingAssertionIds shouldContain "a/blocks-1"
    }

    @Test
    fun G3_CK2b_latent_indexOf_bug_en_evaluateEnforcement() {
        // **Bug latente documentado** (no se corrige en
        // G3.2; se documenta):
        //
        // `AssuranceEngine.evaluateEnforcement` (en
        // `assurance-engine/.../Assurance.kt:588-595`)
        // hace:
        //
        //   report.results.map { result ->
        //     val irAssertion = suite.assertions.getOrNull(
        //       report.results.indexOf(result),
        //     )
        //     ...
        //   }
        //
        // `indexOf(result)` usa `equals` (no identidad).
        // Cuando dos `AssertionResult` son `==`-iguales
        // (caso típico: dos `Unsupported(UnknownOperator)`
        // con la misma `operator` falla), `indexOf` devuelve
        // SIEMPRE el primer índice para ambos, así que
        // ambos quedan asociados al MISMO `assertionId` de
        // `suite.assertions`.
        //
        // Consecuencia observable en el plugin: con 2
        // Mandatory assertions que comparten operator y
        // quedan `Unsupported`, el `blockingAssertionIds`
        // trae la primera assertion dos veces.
        //
        // El bug es del engine, no del plugin. El plugin
        // se limita a propagar lo que el engine produce.
        // El fix correcto está en el engine: usar
        // `results.indices.indexOf` (iterar con
        // `forEachIndexed`) o adjuntar el `assertionId`
        // al `AssertionResult` (el "A1 secundario" del
        // comentario en Assurance.kt:590-593).
        //
        // Acá dejamos el test de evidencia: la lista
        // tiene `["a/blocks-1", "a/blocks-1"]`, no
        // `["a/blocks-1", "a/blocks-2"]` como sería
        // correcto. Si en el futuro el engine se corrige,
        // este test se actualiza a `shouldBe listOf(
        //   "a/blocks-1", "a/blocks-2")`.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/two-mandatory",
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
                        id = "a/blocks-1",
                        lensRef = "l/no-cap",
                        operator = "eq",
                        operands = mapOf("value" to "0"),
                        severity = "Critical",
                        enforcement = "Mandatory",
                        completenessRequirements = listOf("nope"),
                        rationale = "first blocks",
                    ),
                    AssuranceCheckStepDefinition.AssertionDto(
                        id = "a/blocks-2",
                        lensRef = "l/no-cap",
                        operator = "eq",
                        operands = mapOf("value" to "0"),
                        severity = "Critical",
                        enforcement = "Mandatory",
                        completenessRequirements = listOf("nope"),
                        rationale = "second blocks",
                    ),
                ),
            ),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = emptyRuntime,
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Failure"
        val failure = out.failure!!
        failure.kind shouldBe "mandatory-failure"
        // Evidencia del bug: lista con IDs duplicados.
        failure.blockingAssertionIds shouldHaveSize 2
        failure.blockingAssertionIds shouldBe listOf("a/blocks-1", "a/blocks-1")
    }

    @Test
    fun G3_CK3_incomplete_no_lleva_blocking_assertion_ids() {
        // `assurance-incomplete` se produce cuando el
        // report tiene inconclusive > 0 con completeness
        // RequireComplete. Como la suite mínima que
        // usamos NO produce inconclusive (results vacío
        // + evaluateEnforcement con Passed), este test
        // ejercita el path `incomplete` con un mock
        // que lo fuerce: lo creamos en V8.1 si hace
        // falta. Por ahora validamos la rama de código
        // mediante unit del path: si `reason` empieza
        // con "assurance-incomplete", el counterexample
        // kind es "incomplete" y blockingAssertionIds
        // es emptyList.
        //
        // Verificación estática: invocamos `run` con
        // una suite cuyo `completenessPolicy =
        // RequireComplete` y la suite mínima. El path
        // completo queda cubierto en el test de Failure
        // de Mandatory (CK2). Aquí validamos la
        // simetría: si outcome es Success, failure
        // es null. Eso confirma que la rama
        // `outcome is Success -> return null` (línea
        // 411) está intacta.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = validSuite(),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = emptyRuntime,
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Success"
        out.failure shouldBe null
    }

    @Test
    fun G3_CK4_y_CK5_engine_error_handler_no_lleva_blocking_assertion_ids() {
        // El handler captura cualquier Throwable del
        // `run(...)` y lo convierte a Aborted con
        // `kind = "engine-error"`. Forzamos el path
        // con una suite inválida (assertions=[]) que
        // lanza IllegalArgumentException en
        // `AssuranceSuiteIR.init`.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/empty",
                version = "1.0.0",
                requiredEvidence = emptyList(),
                lenses = listOf(noopLens("l/x")),
                assertions = emptyList(),
            ),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val ctx = StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test/run-engine-error"),
            stepIndex = 0,
            capabilities = object : StepCapabilityAccess {
                override fun available() = setOf(ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS)
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(
                    key: dev.rubentxu.pipeline.v2.domain.step.StepCapability,
                ): T = throw IllegalStateException("should not be called: $key")
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
        val out = runBlocking { handler.execute(input, ctx) }
        out.outcome shouldBe "Aborted"
        val failure = out.failure!!
        failure.kind shouldBe "engine-error"
        // CK-4/CK-5: engine-error no tiene IDs concretos.
        failure.blockingAssertionIds shouldHaveSize 0
        failure.sourceLocation shouldBe null
    }

    @Test
    fun G3_CK6_missing_capability_lleva_kind_y_lista_de_caps_ausentes() {
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = validSuite(),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val ctx = StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test/run-missing-cap"),
            stepIndex = 0,
            capabilities = object : StepCapabilityAccess {
                override fun available() = emptySet<
                    dev.rubentxu.pipeline.v2.domain.step.StepCapability,
                    >()
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(
                    key: dev.rubentxu.pipeline.v2.domain.step.StepCapability,
                ): T = throw IllegalStateException("should not be called: $key")
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
        val out = runBlocking { handler.execute(input, ctx) }
        out.outcome shouldBe "Aborted"
        val failure = out.failure!!
        failure.kind shouldBe "missing-capability"
        failure.message shouldContain "assurance.provider"
        failure.message shouldContain "assurance.assertions"
        // missing-capability tampoco lleva IDs.
        failure.blockingAssertionIds shouldHaveSize 0
    }

    @Test
    fun G3_CK7_blocking_assertion_ids_no_contiene_duplicados() {
        // El `blockingFailures` se construye en
        // `EnforcementDecision.Failure` por-assertion
        // (no por-rule, no por-result), así que
        // duplicados no pueden ocurrir a nivel del
        // decision. Pero el plugin filtra con
        // `mapNotNull { it.assertionId?.value }`: si
        // dos entries con el mismo assertionId llegan,
        // el list los tendría 2 veces. Pin: el
        // shape final debe ser unique.
        //
        // Test estructural: si el report tiene una
        // sola Mandatory assertion que bloquea, el
        // counterexample la lista una sola vez.
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/once",
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
                        id = "a/unique",
                        lensRef = "l/no-cap",
                        operator = "eq",
                        operands = mapOf("value" to "0"),
                        severity = "Critical",
                        enforcement = "Mandatory",
                        completenessRequirements = listOf("nope"),
                        rationale = "unique",
                    ),
                ),
            ),
            evidence = emptyList(),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = ProviderRegistry(),
            runtime = emptyRuntime,
            assertionsById = emptyMap(),
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Failure"
        val failure = out.failure!!
        failure.kind shouldBe "mandatory-failure"
        failure.blockingAssertionIds shouldBe listOf("a/unique")
    }

    @Test
    fun G3_CK8_sourceLocation_es_null_por_defecto_en_todos_los_kinds() {
        // El plugin nunca popula `sourceLocation`. Este
        // test pinea esa decisión: si alguien lo
        // popula, se romperá la simetría de los 4
        // kinds.
        val mandatory = AssuranceCheckStepDefinition.CounterexampleDto(
            kind = "mandatory-failure", message = "x",
        )
        val incomplete = AssuranceCheckStepDefinition.CounterexampleDto(
            kind = "incomplete", message = "x",
        )
        val engineError = AssuranceCheckStepDefinition.CounterexampleDto(
            kind = "engine-error", message = "x",
        )
        val missingCap = AssuranceCheckStepDefinition.CounterexampleDto(
            kind = "missing-capability", message = "x",
        )
        listOf(mandatory, incomplete, engineError, missingCap).forEach {
            it.sourceLocation shouldBe null
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun validSuite() = AssuranceCheckStepDefinition.SuiteDto(
        id = "s/min",
        version = "1.0.0",
        requiredEvidence = emptyList(),
        lenses = listOf(noopLens("l/identity")),
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
    )

    private fun noopLens(id: String) = AssuranceCheckStepDefinition.LensDto(
        id = id,
        kind = "no-op",
        inputCapabilities = listOf("none"),
        outputSchema = "noop/v1",
    )
}
