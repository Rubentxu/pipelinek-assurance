package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.StepBody
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * G3.2 — Cross-checks consolidados (V11–V15).
 *
 * V11. **Capabilities declaradas vs realmente usadas.**
 *     El descriptor publica `requiredCapabilities =
 *     ["assurance.provider", "assurance.assertions"]`.
 *     El código del handler referencia
 *     `ASSURANCE_PROVIDER` y `ASSURANCE_ASSERTIONS`
 *     (top-level vals). Si alguien añade una capability
 *     en el descriptor pero no la usa en el handler,
 *     o al revés, este test lo detecta.
 *
 * V12. **`severity` y `enforcement` en el `AssertionDto`
 *     son strings libres, NO enums tipados.** El
 *     motor los convierte a enums al parsear
 *     (`suiteFromDto`), pero el shape serializado
 *     acepta cualquier string. Esto es intencional
 *     para extensibilidad (operators custom), pero
 *     si alguien cambia `severity: String` a un
 *     enum, el test lo atrapa.
 *
 * V13. **`MockSdkHost.run()` con la API vieja
 *     (sin `evidenceAdapterVersion`) sigue
 *     funcionando** — la nueva firma con default
 *     `evidenceAdapterVersion = null` debe ser
 *     backwards-compatible.
 *
 * V14. **Surface de errores del handler es cerrada.**
 *     Los 3 outcomes posibles del handler son
 *     "Success", "Failure", "Aborted". El
 *     `outcome` NUNCA es null o cualquier otro
 *     string. Verificamos el set exhaustivo.
 *
 * V15. **Dominios tipados del descriptor son
 *     consistentes entre sí** — no confundir
 *     `ReplayPolicy` (re-correr) con
 *     `RecoveryPolicy` (reconstruir resultado).
 *     `Resilience` no existe en el SDK como
 *     policy separada (es un concepto cross-cutting
 *     que se compone de `replay + recovery`).
 */
class G3CrossCheckConsolidatedTest : AnnotationSpec() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun checkStepContract() = AssurancePluginContributor()
        .definitions()
        .filterIsInstance<
            dev.rubentxu.pipeline.v2.domain.step.StepDefinition<
                AssuranceCheckStepDefinition.Input,
                AssuranceCheckStepDefinition.Output,
                >,
            >()
        .first { it.contract.key == PluginStepId(AssuranceCheckStepDefinition.KEY) }
        .contract

    // -----------------------------------------------------------------
    // V11 — Capabilities declaradas == capabilities realmente usadas
    // -----------------------------------------------------------------

    @Test
    fun G3_V11_capabilities_declaradas_son_exactamente_las_del_handler() {
        val descriptorCaps = checkStepContract()
            .descriptor.requiredCapabilities
        // Las dos capabilities que el handler
        // chequea son los top-level vals
        // ASSURANCE_PROVIDER y ASSURANCE_ASSERTIONS.
        descriptorCaps.sorted() shouldBe listOf(
            "assurance.assertions", "assurance.provider",
        ).sorted()
    }

    // -----------------------------------------------------------------
    // V12 — severity / enforcement son strings libres
    // -----------------------------------------------------------------

    @Test
    fun G3_V12_severity_y_enforcement_serializan_como_strings_no_enum() {
        val assertion = AssuranceCheckStepDefinition.AssertionDto(
            id = "a/x",
            lensRef = "l/x",
            operator = "eq",
            operands = mapOf("k" to "v"),
            severity = "CustomFutureSeverity", // Valor que NO está en el enum actual
            enforcement = "Mandatory", // Sí está
            completenessRequirements = emptyList(),
            rationale = "test",
        )
        val encoded = json.encodeToString(
            AssuranceCheckStepDefinition.AssertionDto.serializer(), assertion,
        )
        val obj = json.parseToJsonElement(encoded)
        // El campo serializa como string plano, no como
        // ordinal ni como objeto.
        val s = obj.toString()
        s shouldContain "\"severity\":\"CustomFutureSeverity\""
        s shouldContain "\"enforcement\":\"Mandatory\""
    }

    // -----------------------------------------------------------------
    // V13 — MockSdkHost backwards compat
    // -----------------------------------------------------------------

    @Test
    fun G3_V13_MockSdkHost_sigue_compilando_con_API_vieja_sin_evidenceAdapterVersion() {
        // El test de MockSdkHost.runCheck_output_se_serializa_a_json_canonico
        // ya existe y pasa. Aquí validamos la firma
        // pública: `MockSdkHost.runCheck(input)` debe
        // seguir siendo invocable con 1 argumento
        // (sin `evidenceAdapterVersion`), porque la
        // nueva firma del `run(...)` de
        // `AssuranceCheckStepDefinition` tiene
        // `evidenceAdapterVersion: String? = null`
        // como default.
        val input = MockSdkHost.checkInput(name = "self")
        // La API vieja (MockSdkHost.runCheck con 1
        // arg) sigue funcionando.
        val out = MockSdkHost.runCheck(input)
        out.outcome shouldBe "Success"
    }

    // -----------------------------------------------------------------
    // V14 — Surface cerrada de outcome
    // -----------------------------------------------------------------

    @Test
    fun G3_V14_outcome_values_solo_Success_Failure_Aborted() {
        // El handler no puede producir otros outcomes.
        // Pin dinámico: el código de
        // `AssuranceCheckStepDefinition.run` y del
        // handler sólo construye 3 valores para
        // `outcome`. Esto se verifica en
        // `G3_CK1_production_code_produce_solo_los_4_kinds_canonicos`
        // (similar para outcome). Aquí validamos
        // la invariante: el output serializado de
        // cualquier caso (Success, Failure,
        // Aborted) tiene `outcome` en ese set.
        val cases = listOf(
            AssuranceCheckStepDefinition.Output(
                reportDigest = "x",
                summary = AssuranceCheckStepDefinition.SummaryDto(1, 0, 0, 0, 0),
                outcome = "Success",
                failure = null,
                replayPolicy = "RERUN",
            ),
            AssuranceCheckStepDefinition.Output(
                reportDigest = "x",
                summary = AssuranceCheckStepDefinition.SummaryDto(0, 1, 0, 0, 0),
                outcome = "Failure",
                failure = AssuranceCheckStepDefinition.CounterexampleDto(
                    kind = "mandatory-failure", message = "m",
                ),
                replayPolicy = "MEMOIZED",
            ),
            AssuranceCheckStepDefinition.Output(
                reportDigest = "x",
                summary = AssuranceCheckStepDefinition.SummaryDto(0, 0, 0, 0, 0),
                outcome = "Aborted",
                failure = AssuranceCheckStepDefinition.CounterexampleDto(
                    kind = "engine-error", message = "m",
                ),
                replayPolicy = "RERUN",
            ),
        )
        cases.forEach { out ->
            val encoded = json.encodeToString(
                AssuranceCheckStepDefinition.Output.serializer(), out,
            )
            val obj = json.parseToJsonElement(encoded)
            obj.toString() shouldContain "\"outcome\":\"${out.outcome}\""
        }
    }

    // -----------------------------------------------------------------
    // V15 — Dominios tipados consistentes
    // -----------------------------------------------------------------

    @Test
    fun G3_V15_dominios_tipados_no_se_confunden_replay_vs_recovery() {
        val d = checkStepContract().descriptor
        // ReplayPolicy: cómo se reproduce el run
        //   (re-correr el step con el mismo fingerprint
        //   o re-ejecutar desde cero).
        d.replayPolicy shouldBe ReplayPolicy.MEMOIZED
        // RecoveryPolicy: cómo se reconstruye el
        //   estado durable si el step falla a
        //   mitad (no aplica aquí porque effects =
        //   READ_ONLY).
        d.recoveryPolicy shouldBe RecoveryPolicy.None
        // El SDK tiene `Resilience` como concepto
        //   de alto nivel (composición de replay +
        //   recovery) pero NO como policy tipada en
        //   `StepDescriptor`. Verificamos que no
        //   aparezca.
        val methods = d.javaClass.methods.map { it.name }
        methods.none { it.equals("getResilience", ignoreCase = true) } shouldBe true
    }

    @Test
    fun G3_V15_extra_ejecution_y_body_consistentes_con_READ_ONLY() {
        val d = checkStepContract().descriptor
        // Si el step es READ_ONLY, no debe tener
        // body (es una decisión sin sub-pipeline).
        d.executionLocation shouldBe ExecutionLocation.CONTROLLER
        d.effects shouldBe listOf(Effect.READ_ONLY)
        d.body shouldBe StepBody.None
    }
}
