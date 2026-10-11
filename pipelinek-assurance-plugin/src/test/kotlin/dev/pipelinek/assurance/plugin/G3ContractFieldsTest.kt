package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * G3 (Bloque G) — Valida que los dominios tipados del
 * `StepDescriptor` que `AssuranceCheckStepDefinitionImpl`
 * registra son los que el SDK puede actuar:
 *
 * - `executionLocation` debe ser `CONTROLLER` (no
 *   WORKER, no AGENT): la decisión de pass/fail debe
 *   tomarse en el orquestador, no en un sidecar sin
 *   capability access.
 * - `effects` debe contener `READ_ONLY` (no `WRITE` ni
 *   `IDEMPOTENT_WRITE`): assurance.check NO muta
 *   estado durable. La política de recovery es `None`
 *   precisamente porque no hay nada que recuperar.
 * - `replayPolicy` debe ser `MEMOIZED`: el reintento
 *   determinista se hace por el orquestador
 *   comparando `reportDigest`, no por el SDK
 *   re-ejecutando el step.
 * - `recoveryPolicy` debe ser `None`: el SDK no
 *   reconstruye resultados por su cuenta; el orquestador
 *   decide retry o skip.
 * - `body` debe ser `StepBody.None` (no `Linear`,
 *   no `CanonicalEngine`, no `Terminal`):
 *   `assurance.check` es un step de decisión sin
 *   sub-pipeline.
 *
 * Estos valores se eligen en `AssurancePluginContributor`
 * y se exponen al SDK a través de
 * `StepDefinitionContributor.descriptors()`. Si alguien
 * edita esa línea y pone, p.ej., `replayPolicy = REPLAY`
 * (que re-ejecuta el step), el comportamiento del SDK
 * cambia silenciosamente. Este test atrapa esa
 * regresión.
 *
 * Los 5 campos de texto libre (`idempotencyModel`,
 * `timeoutModel`, `jenkinsSurface`, `securityProfile`,
 * `deprecation`) el SDK no los valida — son
 * metadatos consumidos por la UI/registry. Se
 * valida únicamente que no estén vacíos (no
 * rompemos el contrato del descriptor).
 */
class G3ContractFieldsTest : AnnotationSpec() {

    private fun descriptorFor(
        key: String,
    ): dev.rubentxu.pipeline.v2.domain.StepDescriptor {
        // `definitions()` expone `StepDefinition<*,*>`, y cada
        // uno lleva su `contract.descriptor` poblado con los
        // dominios tipados que el SDK consume.
        return AssurancePluginContributor()
            .definitions()
            .first { it.contract.key == PluginStepId(key) }
            .contract
            .descriptor
    }

    @Test
    fun G3_check_executionLocation_es_CONTROLLER() {
        descriptorFor(AssuranceCheckStepDefinition.KEY).executionLocation shouldBe
            ExecutionLocation.CONTROLLER
    }

    @Test
    fun G3_check_effects_contiene_READ_ONLY_y_nada_de_escritura() {
        val effects = descriptorFor(AssuranceCheckStepDefinition.KEY).effects
        effects shouldBe listOf(Effect.READ_ONLY)
    }

    @Test
    fun G3_check_replayPolicy_es_MEMOIZED() {
        descriptorFor(AssuranceCheckStepDefinition.KEY).replayPolicy shouldBe
            ReplayPolicy.MEMOIZED
    }

    @Test
    fun G3_check_recoveryPolicy_es_None() {
        descriptorFor(AssuranceCheckStepDefinition.KEY).recoveryPolicy shouldBe
            RecoveryPolicy.None
    }

    @Test
    fun G3_check_body_es_None() {
        descriptorFor(AssuranceCheckStepDefinition.KEY).body shouldBe StepBody.None
    }

    @Test
    fun G3_check_requiredCapabilities_contiene_proveedor_y_assertions() {
        val caps = descriptorFor(AssuranceCheckStepDefinition.KEY)
            .requiredCapabilities
        caps shouldBe listOf("assurance.provider", "assurance.assertions")
    }

    @Test
    fun G3_check_text_fields_no_vacios() {
        val d = descriptorFor(AssuranceCheckStepDefinition.KEY)
        d.idempotencyModel.isNotBlank() shouldBe true
        d.timeoutModel.isNotBlank() shouldBe true
        d.jenkinsSurface.isNotBlank() shouldBe true
        d.securityProfile.isNotBlank() shouldBe true
        // `deprecation` es un campo opcional: una string vacía
        // significa "step no deprecated" (no rompe el contrato).
        // El SDK lo acepta como nullable en semántica aunque el
        // tipo sea no-null. Lo validamos como `isEmpty` para
        // que un cambio accidental a "yes" o similar haga
        // ruido, pero no imponemos no-vacío.
        d.deprecation.isEmpty() shouldBe true
    }
}
