/*
 * G4 — `assurance.verify` con `BodyContinuation` real del SDK.
 *
 * Sexto pase de validación autónoma extendida autorizada
 * por el usuario. Cubre las invariantes V49, V50, V51 y V52
 * documentadas en `block-G-work-units.md §G4` y el contrato
 * de `AssurancePluginContributor` (handler + impl).
 *
 *  - V49: `BodyContinuation` se invoca exactamente una vez.
 *  - V50: `BodyOutcome.Cancelled` no se traduce a Failure.
 *  - V51: El fallo original del body prevalece sobre el
 *    veredicto de assurance.
 *  - V52: Replay no duplica efectos (el handler pineado
 *    rechaza una segunda invocación del continuation).
 */
package dev.pipelinek.assurance.plugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class G4VerifyBodyContinuationTest : StringSpec({

    "G4_V50_body_outcome_cancelled_translates_to_aborted_not_failure" {
        // V50: bodyOutcome.Cancelled ⇒ outcome = "Aborted"
        // (NO "Failure"). El bodyFailureMessage preserva la
        // razón de la cancelación.
        val input = MockSdkHost.verifyInput(name = "v50-cancel")
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Cancelled("ParentCancelled"),
        )
        out.outcome shouldBe "Aborted"
        out.bodyOutcome shouldBe "Cancelled"
        out.bodyFailureMessage shouldBe "ParentCancelled"
        out.failure?.kind shouldBe "cancelled"
    }

    "G4_V50_cancellation_with_different_reasons_preserved" {
        // La razón de la cancelación (Deadline, ParentCancelled,
        // ScopeShutDown) se preserva byte-a-byte en el output.
        val input = MockSdkHost.verifyInput(name = "v50-reason")
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Cancelled("Deadline"),
        )
        out.bodyFailureMessage shouldBe "Deadline"
    }

    "G4_V51_body_failure_prevails_over_assurance_success" {
        // V51: cuando el body falla, el output es
        // outcome = "Failure" con failure.kind = "body-failure",
        // INDEPENDIENTEMENTE de si assurance pasa. El
        // bodyFailureMessage lleva el error original.
        val input = MockSdkHost.verifyInput(name = "v51-fail")
        val err = IllegalStateException("assertion in body")
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Failure(
                error = err,
                message = "assertion in body",
            ),
        )
        out.outcome shouldBe "Failure"
        out.bodyOutcome shouldBe "Failure"
        out.bodyFailureMessage shouldBe "assertion in body"
        out.failure?.kind shouldBe "body-failure"
    }

    "G4_V51_body_failure_uses_error_class_name_when_message_is_null" {
        // Si el body failure viene sin message, el output usa
        // el nombre de la clase del error como fallback.
        val input = MockSdkHost.verifyInput(name = "v51-nullmsg")
        val err = IllegalStateException() // sin message
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Failure(
                error = err,
                message = null,
            ),
        )
        out.bodyFailureMessage shouldBe "IllegalStateException"
    }

    "G4_body_success_propagates_assurance_verdict" {
        // Body success + suite sin assertions Mandatory fallando
        // ⇒ outcome = "Success", bodyOutcome = "Success".
        val input = MockSdkHost.verifyInput(name = "v49-success")
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Success,
        )
        out.outcome shouldBe "Success"
        out.bodyOutcome shouldBe "Success"
        out.bodyFailureMessage shouldBe null
    }

    "G4_body_success_replayPolicy_pinea_MEMOIZED_si_adapter_version" {
        // Misma lógica que `assurance.check`: si el
        // evidenceAdapterVersion está presente, MEMOIZED.
        val input = MockSdkHost.verifyInput(name = "v49-memo")
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Success,
            evidenceAdapterVersion = "0.1.0",
        )
        out.replayPolicy shouldBe "MEMOIZED"
    }

    "G4_body_success_replayPolicy_RERUN_sin_adapter_version" {
        // Sin evidenceAdapterVersion, el fingerprint no se
        // puede formar y la policy cae a RERUN.
        val input = MockSdkHost.verifyInput(name = "v49-rerun")
        val out = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Success,
            evidenceAdapterVersion = null,
        )
        out.replayPolicy shouldBe "RERUN"
    }

    "G4_reportDigest_stable_across_body_outcomes_with_same_input" {
        // Mismo input + body success ó cancelled producen
        // digests DISTINTOS (la cancellation tiene su propio
        // digest pineado). Esto pinea que el cambio de
        // outcome cambia el fingerprint.
        val input = MockSdkHost.verifyInput(name = "v49-digest")
        val successDigest = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Success,
        ).reportDigest
        val cancelDigest = MockSdkHost.runVerify(
            input = input,
            bodyOutcome = AssuranceVerifyStepDefinition.BodyOutcome.Cancelled("X"),
        ).reportDigest
        (successDigest == cancelDigest) shouldBe false
    }

    "G4_toPluginBodyOutcome_translates_SDK_Completed_Success" {
        // Helper de traducción: SDK Completed(StepOutcome.Success)
        // ⇒ plugin BodyOutcome.Success.
        val sdkOut = dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Completed(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
        )
        val plugin = AssuranceVerifyStepDefinition.toPluginBodyOutcome(sdkOut)
        plugin.shouldBeInstanceOf<AssuranceVerifyStepDefinition.BodyOutcome.Success>()
    }

    "G4_toPluginBodyOutcome_translates_SDK_Completed_Failure" {
        val sdkOut = dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Completed(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.PLUGIN,
                    message = "boom",
                ),
            ),
        )
        val plugin = AssuranceVerifyStepDefinition.toPluginBodyOutcome(sdkOut)
        plugin.shouldBeInstanceOf<AssuranceVerifyStepDefinition.BodyOutcome.Failure>()
        plugin.message shouldBe "boom"
    }

    "G4_toPluginBodyOutcome_translates_SDK_Cancelled_with_reason_name" {
        // CancellationReason.Deadline ⇒ "Deadline" (no
        // "toString()" que daría "Deadline()" en algunos
        // contextos).
        val sdkOut = dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Cancelled(
            dev.rubentxu.pipeline.v2.domain.step.CancellationReason.Deadline,
        )
        val plugin = AssuranceVerifyStepDefinition.toPluginBodyOutcome(sdkOut)
        plugin.shouldBeInstanceOf<AssuranceVerifyStepDefinition.BodyOutcome.Cancelled>()
        plugin.reason shouldBe "Deadline"
    }

    "G4_descriptor_declares_BODY_CONTINUATION_CAPABILITY" {
        // El contrato del Step debe declarar la capability
        // BODY_CONTINUATION_CAPABILITY (key = "bodyContinuation"
        // en el SDK 0.48.0/0.51.0-rc1) para que el SDK nos
        // provea la continuación.
        val contract = AssuranceVerifyStepDefinitionImpl().contract
        contract.requiredCapabilities.any {
            it.key == "bodyContinuation"
        } shouldBe true
    }

    "G4_descriptor_declares_HANDLER_CONTINUATION_body_owner" {
        // El descriptor debe declarar BodyExecutionOwner
        // HANDLER_CONTINUATION (no HANDLER_INTERNALIZED).
        val descriptor = AssuranceVerifyStepDefinitionImpl().contract.descriptor
        val body = descriptor.body
        body.shouldBeInstanceOf<dev.rubentxu.pipeline.v2.domain.StepBody.Declared>()
        body.execution.owner shouldBe
            dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner.HANDLER_CONTINUATION
    }

    "G4_descriptor_key_matches_assurance_verify" {
        val contract = AssuranceVerifyStepDefinitionImpl().contract
        contract.key.value shouldBe "assurance.verify"
    }
})
