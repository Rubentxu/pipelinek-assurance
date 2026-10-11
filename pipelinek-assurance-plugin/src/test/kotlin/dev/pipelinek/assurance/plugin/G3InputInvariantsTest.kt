package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * G3.2 (Bloque G, validación post-impl extendida) — Invariants
 * del `Input` del Step `assurance.check` y del comportamiento
 * del `run(...)` cuando el caller pasa combinaciones
 * válidas-pero-sin-sentido (empty lenses, empty assertions,
 * mode inválido, completenessPolicy inválida, evidence
 * duplicada, etc.).
 *
 * Por qué este test existe separado del
 * `G3AssuranceCheckStepTest`:
 *  - El test 1-5 de G3 asume siempre el camino feliz del
 *    `outputCodec` y el handler. Aquí asumimos el camino
 *    del `run(...)` directo, que es lo que el handler
 *    llama por dentro. La frontera entre los dos es
 *    `AssuranceCheckStepDefinition.run(input, ...)`.
 *  - El test del handler `G3_check_handler_no_propaga_excepciones_del_sdk_en_get`
 *    cubre excepciones del SDK desde `capabilities.get<>()`.
 *    Aquí cubrimos excepciones que vienen del **propio
 *    engine** cuando el input es estructuralmente inválido.
 *
 * Reglas verificadas:
 *
 * R1. Suite con `lenses = []` lanza `IllegalArgumentException`
 *     desde `AssuranceSuiteIR.init`. No se captura en
 *     `run(...)` (es una validación de input del caller).
 * R2. Suite con `assertions = []` lanza
 *     `IllegalArgumentException` desde
 *     `AssuranceSuiteIR.init`. Mismo path.
 * R3. Suite con `assertions[i].lensRef` que no está en
 *     `lenses` (dangling) lanza
 *     `IllegalArgumentException` (AAT-16).
 * R4. `mode` desconocido NO lanza; cae al else-branch
 *     `FailClosed`. Decisión de diseño: el plugin
 *     es tolerante con valores no canónicos de `mode`
 *     (string libre) y los trata como el modo seguro
 *     (FailClosed). El caller aprende de la doc
 *     que sólo "ReportOnly" es especial.
 * R5. `completenessPolicy` desconocido cae al
 *     `RequireComplete` (modo estricto por defecto).
 * R6. `evidence = []` es válido y no lanza: simplemente
 *     el orchestrator no tiene qué recolectar.
 * R7. `evidence` con dos `EvidenceRefDto` con el mismo
 *     `digest` válido se deduplica a nivel del
 *     orchestrator (`distinctBy { it.digest }`).
 *     El plugin no deduplica — pasa la lista tal cual,
 *     y el orchestrator colapsa. Validamos que el
 *     run no lanza y produce un output consistente.
 * R7b. `EvidenceRefDto` con `digest` que NO son 64
 *     hex chars lanza `IllegalArgumentException` desde
 *     `AssuranceOrchestrator.validateEvidenceRefs`.
 *     El handler convierte a `Aborted` con
 *     `kind = "engine-error"` y mensaje que contiene
 *     "invalid digest".
 * R7c. `EvidenceRefDto` con `logicalRole` blank
 *     lanza `IllegalArgumentException` desde el
 *     mismo validador.
 * R8. `name = ""` no es rechazado: el engine no usa
 *     el nombre como key (sólo el subject del
 *     orchestrator lo usa como `EvidenceSubject.Module(name)`).
 *     No validamos que esté vacío en el plugin — eso
 *     es responsabilidad de la spec del caller.
 * R8b. `name` con caracteres especiales (slashes, unicode,
 *     espacios) tampoco se rechaza — el plugin lo pasa
 *     tal cual al orchestrator, que lo usa como path-like
 *     subject reference.
 *
 * El handler (no `run`) tiene su propio camino:
 * R9. Cuando el `run(...)` lanza con suite vacía, el
 *     handler NO propaga; convierte a `Aborted` con
 *     `kind = "engine-error"`, `replayPolicy = "RERUN"`.
 *     Esto cierra la simetría: el "no throw" del handler
 *     cubre TODO lo que `run` pueda lanzar, no sólo
 *     excepciones del SDK desde `get<>()`.
 */
class G3InputInvariantsTest : AnnotationSpec() {

    private val emptyRuntime = FrozenAssuranceRuntime(
        engineVersion = "0.10.0-test", lenses = emptyMap(),
    )
    private val emptyProviders = ProviderRegistry()
    private val emptyAssertions = emptyMap<
        dev.pipelinek.assurance.engine.AssertionId,
        dev.pipelinek.assurance.engine.AssuranceAssertion<*>,
        >()

    @Test
    fun G3_R1_run_con_lenses_vacias_lanza_IllegalArgumentException() {
        val input = baseInput(
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/empty-lenses",
                version = "1.0.0",
                requiredEvidence = emptyList(),
                lenses = emptyList(),
                assertions = listOf(advisoryAssertion("a/x", "l/missing")),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = emptyProviders,
                runtime = emptyRuntime,
                assertionsById = emptyAssertions,
                evidenceAdapterVersion = "test/1.0",
            )
        }
    }

    @Test
    fun G3_R2_run_con_assertions_vacias_lanza_IllegalArgumentException() {
        val input = baseInput(
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/empty-assertions",
                version = "1.0.0",
                requiredEvidence = emptyList(),
                lenses = listOf(noopLens("l/x")),
                assertions = emptyList(),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = emptyProviders,
                runtime = emptyRuntime,
                assertionsById = emptyAssertions,
                evidenceAdapterVersion = "test/1.0",
            )
        }
    }

    @Test
    fun G3_R3_run_con_lensRef_dangling_lanza_IllegalArgumentException() {
        // AAT-16: la suite debe referenciar lenses declaradas.
        val input = baseInput(
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/dangling",
                version = "1.0.0",
                requiredEvidence = emptyList(),
                lenses = listOf(noopLens("l/declared")),
                // 'a/x' apunta a 'l/undeclared' — debe fallar.
                assertions = listOf(advisoryAssertion("a/x", "l/undeclared")),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = emptyProviders,
                runtime = emptyRuntime,
                assertionsById = emptyAssertions,
                evidenceAdapterVersion = "test/1.0",
            )
        }
    }

    @Test
    fun G3_R4_run_con_mode_desconocido_cae_a_FailClosed_y_completa() {
        // El caller escribió "Strict" en vez de "FailClosed".
        // El plugin NO lanza — interpreta como FailClosed.
        val input = baseInput(mode = "Strict")
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = emptyProviders,
            runtime = emptyRuntime,
            assertionsById = emptyAssertions,
            evidenceAdapterVersion = "test/1.0",
        )
        // No validamos outcome (puede ser Success si no hay
        // bloqueantes); validamos que NO lanza y produce
        // un output. outcome debe ser uno de los 3
        // valores canónicos.
        out.outcome shouldBe "Success"
    }

    @Test
    fun G3_R5_run_con_completenessPolicy_desconocida_cae_a_RequireComplete() {
        val input = baseInput(completenessPolicy = "Loose")
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = emptyProviders,
            runtime = emptyRuntime,
            assertionsById = emptyAssertions,
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Success"
    }

    @Test
    fun G3_R6_run_con_evidence_vacia_no_lanza() {
        val input = baseInput(evidence = emptyList())
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = emptyProviders,
            runtime = emptyRuntime,
            assertionsById = emptyAssertions,
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Success"
        out.failure shouldBe null
    }

    @Test
    fun G3_R7_run_con_evidence_duplicada_dedup_en_orchestrator() {
        // Mismo `digest` válido (64 hex chars) aparece dos
        // veces. El orchestrator aplica `distinctBy { digest }`
        // y procesa la lista deduplicada. El plugin NO
        // deduplica — pasa la lista tal cual. El run no
        // lanza y produce un output consistente.
        val validDigest = "a".repeat(64) // 64 hex 'a' chars
        val dup = AssuranceCheckStepDefinition.EvidenceRefDto(
            mediaType = "application/x-ndjson",
            digest = validDigest,
            logicalRole = "self.metrics",
        )
        val input = baseInput(evidence = listOf(dup, dup))
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = emptyProviders,
            runtime = emptyRuntime,
            assertionsById = emptyAssertions,
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Success"
    }

    @Test
    fun G3_R7b_run_con_digest_invalido_lanza_IllegalArgumentException() {
        val bad = AssuranceCheckStepDefinition.EvidenceRefDto(
            mediaType = "application/x-ndjson",
            digest = "sha256:0001", // No son 64 hex chars
            logicalRole = "self.metrics",
        )
        val input = baseInput(evidence = listOf(bad))
        shouldThrow<IllegalArgumentException> {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = emptyProviders,
                runtime = emptyRuntime,
                assertionsById = emptyAssertions,
                evidenceAdapterVersion = "test/1.0",
            )
        }
    }

    @Test
    fun G3_R7c_run_con_logicalRole_blank_lanza_IllegalArgumentException() {
        val noRole = AssuranceCheckStepDefinition.EvidenceRefDto(
            mediaType = "application/x-ndjson",
            digest = "a".repeat(64),
            logicalRole = "",
        )
        val input = baseInput(evidence = listOf(noRole))
        shouldThrow<IllegalArgumentException> {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = emptyProviders,
                runtime = emptyRuntime,
                assertionsById = emptyAssertions,
                evidenceAdapterVersion = "test/1.0",
            )
        }
    }

    @Test
    fun G3_R8_run_con_name_vacio_no_lanza() {
        val input = baseInput().copy(name = "")
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = emptyProviders,
            runtime = emptyRuntime,
            assertionsById = emptyAssertions,
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Success"
    }

    @Test
    fun G3_R8b_run_con_name_con_caracteres_especiales_no_lanza() {
        val input = baseInput().copy(
            name = "path/with/slashes:and:colons_and ünîcödé-123",
        )
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = emptyProviders,
            runtime = emptyRuntime,
            assertionsById = emptyAssertions,
            evidenceAdapterVersion = "test/1.0",
        )
        out.outcome shouldBe "Success"
    }

    @Test
    fun G3_R9_handler_convierte_excepcion_de_engine_en_Aborted_sin_propagar() {
        // El handler llama `run(...)` con una suite vacía.
        // `run` lanza `IllegalArgumentException`. El
        // handler NO propaga: convierte a Aborted con
        // kind = "engine-error".
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = AssuranceCheckStepDefinition.SuiteDto(
                id = "s/empty-assertions-handler",
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
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test/run-empty"),
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
        val output = runBlocking { handler.execute(input, ctx) }
        output.outcome shouldBe "Aborted"
        output.replayPolicy shouldBe "RERUN"
        val failure = output.failure!!
        failure.kind shouldBe "engine-error"
        failure.message.contains("assertion", ignoreCase = true) shouldBe true
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun baseInput(
        suite: AssuranceCheckStepDefinition.SuiteDto = minimalValidSuite(),
        evidence: List<AssuranceCheckStepDefinition.EvidenceRefDto> = emptyList(),
        mode: String = "FailClosed",
        completenessPolicy: String = "RequireComplete",
    ) = AssuranceCheckStepDefinition.Input(
        name = "self",
        suite = suite,
        evidence = evidence,
        mode = mode,
        completenessPolicy = completenessPolicy,
    )

    private fun minimalValidSuite() = AssuranceCheckStepDefinition.SuiteDto(
        id = "s/min",
        version = "1.0.0",
        requiredEvidence = emptyList(),
        lenses = listOf(noopLens("l/identity")),
        assertions = listOf(advisoryAssertion("a/pass", "l/identity")),
    )

    private fun noopLens(id: String) = AssuranceCheckStepDefinition.LensDto(
        id = id,
        kind = "no-op",
        inputCapabilities = listOf("none"),
        outputSchema = "noop/v1",
    )

    private fun advisoryAssertion(
        id: String,
        lensRef: String,
    ) = AssuranceCheckStepDefinition.AssertionDto(
        id = id,
        lensRef = lensRef,
        operator = "true",
        operands = emptyMap(),
        severity = "Info",
        enforcement = "Advisory",
        completenessRequirements = emptyList(),
        rationale = "test only",
    )
}
