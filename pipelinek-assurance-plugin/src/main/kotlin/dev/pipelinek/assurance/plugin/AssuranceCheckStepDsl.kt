/**
 * B3 (Bloque B) — Fachada Kotlin DSL para `assurance.check`.
 *
 * Ref: `odd/tasks/block-B-pipelinek-plugin.md` §B3.
 *
 * El plan B3 dice:
 *   - Fachada Kotlin DSL que baje a Step primitives públicos.
 *
 * Esta fachada envuelve el DTO `Input` de
 * `AssuranceCheckStepDefinition` con un builder idiomático de
 * Kotlin. El usuario puede declarar la suite, las evidence
 * refs, el modo de enforcement y la completeness policy
 * desde código Kotlin, sin escribir JSON a mano.
 *
 * Lo que esta fachada NO hace:
 *   - NO ejecuta la suite (eso es del handler, ya cubierto
 *     por `AssuranceCheckStepDefinition.run`).
 *   - NO reemplaza el `Input` DTO: produce un `Input`
 *     listo para pasarse al SDK.
 *   - NO introduce dependencias nuevas: vive en el plugin
 *     y consume sólo tipos del engine.
 *
 * Ejemplo:
 *
 * ```kotlin
 * val input = assuranceCheck {
 *     name = "self"
 *     suite(suiteIr) {
 *         // optional overrides on the IR
 *     }
 *     evidence {
 *         ref(
 *             mediaType = "assurance-evidence/v1",
 *             digest = "a".repeat(64),
 *             logicalRole = "architecture.dependency-graph",
 *         )
 *     }
 *     mode = AssuranceCheckStep.EnforcementMode.FailClosed
 *     completeness = AssuranceCheckStep.CompletenessPolicy.RequireComplete
 * }
 * ```
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.SuiteId
import dev.pipelinek.assurance.domain.evidence.Digest

/**
 * Builder de `AssuranceCheckStepDefinition.Input` con
 * sintaxis Kotlin idiomática.
 *
 * El builder mantiene la forma del DTO como source of
 * truth: el resultado es un `Input` exacto, no una
 * representación alternativa. La fachada es **azúcar**
 * sobre el DTO, no una capa adicional.
 */
class AssuranceCheckInputBuilder {
    var name: String = "self"
    private var suiteDto: AssuranceCheckStepDefinition.SuiteDto? = null
    private val evidenceRefs: MutableList<AssuranceCheckStepDefinition.EvidenceRefDto> = mutableListOf()
    var mode: AssuranceCheckStep.EnforcementMode = AssuranceCheckStep.EnforcementMode.FailClosed
    var completeness: AssuranceCheckStep.CompletenessPolicy =
        AssuranceCheckStep.CompletenessPolicy.RequireComplete

    /**
     * Declara la suite vía IR. Se convierte al DTO
     * canónico. Si el IR no tiene lenses o assertions,
     * se rechaza en la construcción del DTO (que ya
     * valida con `require`).
     */
    fun suite(
        ir: AssuranceSuiteIR,
        block: SuiteOverrides.() -> Unit = {},
    ) {
        val overrides = SuiteOverrides().apply(block)
        suiteDto = ir.toSuiteDto(overrides)
    }

    /**
     * Bloque de evidence refs. Cada `ref { ... }` agrega
     * una `EvidenceRefDto` al input.
     */
    fun evidence(block: EvidenceRefsBuilder.() -> Unit) {
        val b = EvidenceRefsBuilder().apply(block)
        evidenceRefs += b.refs
    }

    internal fun build(): AssuranceCheckStepDefinition.Input {
        val suite = suiteDto
            ?: throw IllegalStateException("suite no declarada; llama a suite(ir) { ... }")
        require(evidenceRefs.isNotEmpty()) {
            "evidence no declarada; llama a evidence { ref(...) } al menos una vez"
        }
        return AssuranceCheckStepDefinition.Input(
            name = name,
            suite = suite,
            evidence = evidenceRefs.toList(),
            mode = mode.name,
            completenessPolicy = completeness.name,
        )
    }
}

/**
 * Overrides opcionales sobre el DTO derivado del IR. Sirve
 * para que el caller NO tenga que construir el `SuiteDto`
 * a mano cuando quiere ajustar un campo (e.g. cambiar el
 * `outputSchema` de una lens sin tocar el IR).
 */
class SuiteOverrides {
    internal val lensOutputSchemas: MutableMap<String, String> = mutableMapOf()
    fun lens(lensIdValue: String, outputSchema: String) {
        lensOutputSchemas[lensIdValue] = outputSchema
    }
}

/**
 * Builder de evidence refs. Cada `ref(...)` produce un DTO
 * que el handler del Step pasa al orchestrator.
 */
class EvidenceRefsBuilder {
    internal val refs: MutableList<AssuranceCheckStepDefinition.EvidenceRefDto> = mutableListOf()
    fun ref(
        mediaType: String = "assurance-evidence/v1",
        digest: String,
        logicalRole: String,
    ) {
        require(digest.length == 64 && digest.all { it in HEX_CHARS }) {
            "digest invalido: $digest (esperado 64 hex chars)"
        }
        require(logicalRole.isNotBlank()) {
            "logicalRole no puede estar vacio"
        }
        refs += AssuranceCheckStepDefinition.EvidenceRefDto(
            mediaType = mediaType,
            digest = digest,
            logicalRole = logicalRole,
        )
    }

    private companion object {
        private val HEX_CHARS = "0123456789abcdef".toSet()
    }
}

/**
 * Punto de entrada de la fachada. Devuelve un `Input` listo
 * para `AssuranceCheckStepDefinition.run`.
 */
fun assuranceCheck(
    block: AssuranceCheckInputBuilder.() -> Unit,
): AssuranceCheckStepDefinition.Input =
    AssuranceCheckInputBuilder().apply(block).build()

// --- extension functions for IR → DTO conversion ---

private fun AssuranceSuiteIR.toSuiteDto(
    overrides: SuiteOverrides,
): AssuranceCheckStepDefinition.SuiteDto = AssuranceCheckStepDefinition.SuiteDto(
    id = suiteId.value,
    version = suiteVersion,
    requiredEvidence = requiredEvidence,
    lenses = lenses.map { lens ->
        val out = overrides.lensOutputSchemas[lens.lensId.value]
        AssuranceCheckStepDefinition.LensDto(
            id = lens.lensId.value,
            kind = lens.kind,
            inputCapabilities = lens.inputCapabilities,
            arguments = lens.arguments,
            outputSchema = out ?: lens.outputSchema,
        )
    },
    assertions = assertions.map { assertion ->
        AssuranceCheckStepDefinition.AssertionDto(
            id = assertion.id.value,
            lensRef = assertion.lensRef.value,
            operator = assertion.operator,
            operands = assertion.operands,
            severity = assertion.severity.name,
            enforcement = assertion.enforcement.name,
            completenessRequirements = assertion.completenessRequirements,
            rationale = assertion.rationale,
        )
    },
)
