package dev.pipelinek.assurance.cli.dsl

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.engine.AssurancePack
import dev.pipelinek.assurance.engine.Diff
import dev.pipelinek.assurance.engine.RequiredAssurancePlan
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M10 — Tests de la DSL `assurancePack { suite { ... } }`.
 *
 * Lo que se certifya:
 *   - La DSL produce un `AssurancePack` válido.
 *   - El IR emitido por la DSL es digerible por `CanonicalEncoder`.
 *   - El pack DSL se integra con `RequiredAssurancePlan.build` sin
 *     adaptadores.
 *   - Las reglas Mandatory / Touched / NewFindings se exponen como
 *     funciones DSL y producen el mismo `Rule` que la API directa.
 */
class AssuranceDslTest : AnnotationSpec() {

    @Test
    fun assurancePack_basico_construye_pack_y_emit_digest() {
        val pack = assurancePack("release-m10", "1.0.0") {
            description = "Suites obligatorias para release"
            suite("architecture.hexagonal") {
                lens(lensId = "lens/hex", kind = "architecture.hexagonal")
                assertion(
                    id = "hex/no-cycle",
                    lensRef = "lens/hex",
                    operator = "no-edge-between-sets",
                )
            }
            mandatory("architecture.hexagonal")
        }
        pack.suites.size shouldBe 1
        pack.rules.size shouldBe 1
        pack.rules.single().shouldBeInstanceOf<AssurancePack.Rule.Mandatory>()
    }

    @Test
    fun assurancePack_el_IR_es_canonizable() {
        val (pack, irs) = packWithIrs("digest-m10", "1.0.0") {
            description = "digester"
            suite("architecture.hexagonal") {
                lens(lensId = "lens/hex", kind = "architecture.hexagonal")
                assertion("hex/no-cycle", lensRef = "lens/hex", operator = "no-edge-between-sets")
            }
        }
        pack.suites.size shouldBe 1
        val ir = irs["architecture.hexagonal"]
        (ir != null) shouldBe true
        // El digest de la suite es estable bajo el mismo contenido.
        val d1 = CanonicalEncoder.digestSuite(ir!!).hex
        val d2 = CanonicalEncoder.digestSuite(ir).hex
        d1 shouldBe d2
    }

    @Test
    fun assurancePack_se_integra_con_RequiredAssurancePlan() {
        val (pack, irs) = packWithIrs("plan-bridge", "1.0.0") {
            description = "bridge"
            suite("architecture.hexagonal") {
                lens(lensId = "lens/hex", kind = "architecture.hexagonal")
                assertion("hex/no-cycle", lensRef = "lens/hex", operator = "no-edge-between-sets")
            }
            mandatory("architecture.hexagonal")
        }
        val available = irs.values.toList()
        val plan = RequiredAssurancePlan.build(
            RequiredAssurancePlan.Input(
                availableSuites = available,
                changedPaths = emptyList(),
                diff = emptyDiff(),
                engineVersion = "0.1.0",
            ),
        )
        // mandatory(architecture.hexagonal) → 1 selección MandatoryBaseline.
        plan.selections.size shouldBe 1
        plan.selections.single().reason shouldBe RequiredAssurancePlan.Reason.MandatoryBaseline
        // El pack expuesto tiene 1 SuiteRef y 1 Rule.
        pack.suites.size shouldBe 1
        pack.rules.size shouldBe 1
    }

    @Test
    fun assurancePack_reglas_touched_y_new_findings_expuestas() {
        val pack = assurancePack("rules", "1.0.0") {
            description = "rules"
            suite("a") {
                lens(lensId = "lens/a", kind = "a.kind")
                assertion("a/x", lensRef = "lens/a", operator = "op")
            }
            suite("b") {
                lens(lensId = "lens/b", kind = "b.kind")
                assertion("b/x", lensRef = "lens/b", operator = "op")
            }
            touched("a", prefix = "a/path")
            newFindings("b")
        }
        pack.rules.size shouldBe 2
        pack.rules[0].shouldBeInstanceOf<AssurancePack.Rule.Touched>()
        (pack.rules[0] as AssurancePack.Rule.Touched).prefix shouldBe "a/path"
        pack.rules[1].shouldBeInstanceOf<AssurancePack.Rule.NewFindings>()
    }

    private fun emptyDiff() = Diff(
        baselineName = "none",
        baselineDigest = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("b"),
        currentDigest = dev.pipelinek.assurance.domain.evidence.Digest.ofUtf8("c"),
        engineVersion = "0.1.0",
        entries = emptyList(),
    )

    /**
     * Helper que ejecuta la DSL devolviendo (pack, IRs) para que el
     * test pueda acceder a ambos. `assurancePack` solo devuelve el
     * `AssurancePack` (la API pública); el IR queda en el `PackBuilder`
     * durante la construcción. Aquí usamos el builder directamente
     * para extraer ambos.
     */
    private fun packWithIrs(
        name: String,
        version: String,
        block: PackBuilder.() -> Unit,
    ): Pair<AssurancePack, Map<String, dev.pipelinek.assurance.engine.AssuranceSuiteIR>> {
        val builder = PackBuilder(name, version).apply(block)
        return builder.build() to builder.declaredSuites()
    }
}
