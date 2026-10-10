package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionIR
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Enforcement
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.engine.Severity
import dev.pipelinek.assurance.engine.SuiteId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * B3 (Bloque B) — Tests de la fachada Kotlin DSL.
 *
 * Ref: `odd/tasks/block-B-pipelinek-plugin.md` §B3.
 *
 * El plan B3 dice:
 *   - Fachada Kotlin DSL que baje a Step primitives públicos.
 *
 * Estos tests verifican que `assuranceCheck { ... }` produce
 * un `AssuranceCheckStepDefinition.Input` válido, en la
 * forma exacta que espera el handler del Step. La fachada
 * NO agrega funcionalidad: baja al DTO canónico, sin
 * transformaciones.
 */
class AssuranceCheckStepDslTest : AnnotationSpec() {

    @Test
    fun B3_DSL_minimo_produce_Input_valido() {
        val input = assuranceCheck {
            name = "self"
            suite(sampleSuite())
            evidence {
                ref(digest = "a".repeat(64), logicalRole = "arch.dependency-graph")
            }
        }
        input.name shouldBe "self"
        input.suite.id shouldBe "s1"
        input.evidence.single().logicalRole shouldBe "arch.dependency-graph"
        input.mode shouldBe "FailClosed"
        input.completenessPolicy shouldBe "RequireComplete"
    }

    @Test
    fun B3_DSL_respeta_modo_y_completeness_del_builder() {
        val input = assuranceCheck {
            name = "self"
            suite(sampleSuite())
            evidence {
                ref(digest = "b".repeat(64), logicalRole = "arch.dependency-graph")
            }
            mode = AssuranceCheckStep.EnforcementMode.ReportOnly
            completeness = AssuranceCheckStep.CompletenessPolicy.ReportMissing
        }
        input.mode shouldBe "ReportOnly"
        input.completenessPolicy shouldBe "ReportMissing"
    }

    @Test
    fun B3_DSL_permite_multiples_evidence_refs() {
        val input = assuranceCheck {
            name = "self"
            suite(sampleSuite())
            evidence {
                ref(digest = "a".repeat(64), logicalRole = "arch.dependency-graph")
                ref(digest = "b".repeat(64), logicalRole = "runtime.spans")
            }
        }
        input.evidence.size shouldBe 2
        input.evidence.map { it.logicalRole } shouldBe
            listOf("arch.dependency-graph", "runtime.spans")
    }

    @Test
    fun B3_DSL_lens_override_cambia_el_outputSchema() {
        // El override permite ajustar un campo del DTO
        // sin reconstruir el IR completo.
        val input = assuranceCheck {
            name = "self"
            suite(sampleSuite()) {
                lens(lensIdValue = "l1", outputSchema = "CustomGraph")
            }
            evidence {
                ref(digest = "c".repeat(64), logicalRole = "arch.dependency-graph")
            }
        }
        input.suite.lenses.single().outputSchema shouldBe "CustomGraph"
    }

    @Test
    fun B3_DSL_sin_suite_lanza_estado_invalido() {
        val ex = shouldThrow<IllegalStateException> {
            assuranceCheck {
                name = "self"
                evidence {
                    ref(digest = "d".repeat(64), logicalRole = "arch.dependency-graph")
                }
            }
        }
        ex.message shouldContain "suite"
    }

    @Test
    fun B3_DSL_sin_evidence_lanza_estado_invalido() {
        val ex = shouldThrow<IllegalArgumentException> {
            assuranceCheck {
                name = "self"
                suite(sampleSuite())
                evidence { /* vacio */ }
            }
        }
        ex.message shouldContain "evidence"
    }

    @Test
    fun B3_DSL_digest_invalido_lanza_IllegalArgumentException() {
        val ex = shouldThrow<IllegalArgumentException> {
            assuranceCheck {
                name = "self"
                suite(sampleSuite())
                evidence {
                    ref(digest = "no-es-hex", logicalRole = "arch.dependency-graph")
                }
            }
        }
        ex.message shouldContain "digest"
    }

    @Test
    fun B3_DSL_logicalRole_vacio_lanza_IllegalArgumentException() {
        val ex = shouldThrow<IllegalArgumentException> {
            assuranceCheck {
                name = "self"
                suite(sampleSuite())
                evidence {
                    ref(digest = "a".repeat(64), logicalRole = "  ")
                }
            }
        }
        ex.message shouldContain "logicalRole"
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun sampleSuite(): AssuranceSuiteIR = AssuranceSuiteIR(
        apiVersion = "assurance-ir/v1",
        suiteId = SuiteId("s1"),
        suiteVersion = "1.0.0",
        requiredEvidence = listOf("arch.dependency-graph"),
        lenses = listOf(
            LensPlan(
                lensId = LensId("l1"),
                kind = "BuiltinHexagonal",
                inputCapabilities = listOf("arch.dependency-graph"),
                outputSchema = "DependencyGraph",
            ),
        ),
        assertions = listOf(
            AssertionIR(
                id = AssertionId("a/no-domain-to-external"),
                lensRef = LensId("l1"),
                operator = "no-edge-between-layers",
                operands = mapOf("from" to "Domain", "to" to "Adapters"),
                severity = Severity.Error,
                enforcement = Enforcement.Mandatory,
                completenessRequirements = listOf("arch.dependency-graph"),
                rationale = "regla hexagonal",
            ),
        ),
    )
}
