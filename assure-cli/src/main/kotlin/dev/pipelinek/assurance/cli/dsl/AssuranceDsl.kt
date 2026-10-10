package dev.pipelinek.assurance.cli.dsl

import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionIR
import dev.pipelinek.assurance.engine.AssurancePack
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Enforcement
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.engine.Severity
import dev.pipelinek.assurance.engine.SuiteId

/**
 * M10 — DSL para autoria de `AssuranceSuiteIR` y `AssurancePack`.
 *
 * Ref: ROADMAP §3 M0/M1/M10, "façade `assurance-dsl`".
 *
 * **Por qué existe:**
 *   - Elimina la verbosidad de construir `AssuranceSuiteIR` a mano
 *     desde un test o un script de release. Sin DSL, cada suite
 *     requiere 6+ argumentos posicionales y builders anidados;
 *     con DSL, la estructura jerárquica es visible a simple vista.
 *   - Permite versionar y firmar el IR en un solo lugar: la
 *     conversión a `AssuranceSuiteIR`/`AssurancePack` está en un
 *     sitio y no se repite.
 *
 * **Lo que NO hace:**
 *   - No es un parser: no lee YAML, JSON ni TOML. Es un builder
 *     Kotlin. La carga desde un archivo vendría en un codec aparte.
 *   - No valida la coherencia cross-suite (esa es la labor de
 *     `RequiredAssurancePlan.build`).
 *   - No exporta a un formato canónico: la `AssuranceSuiteIR`
 *     resultante pasa por `CanonicalEncoder.digestSuite` igual que
 *     cualquier otra suite.
 */
@DslMarker
annotation class AssuranceDslMarker

@AssuranceDslMarker
class SuiteBuilder internal constructor(
    val id: String,
) {
    var version: String = "1.0.0"
    var requiredEvidence: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()
    private val lenses = mutableListOf<LensPlan>()
    private val assertions = mutableListOf<AssertionIR>()

    fun lens(
        lensId: String,
        kind: String,
        inputCapabilities: List<String> = emptyList(),
        arguments: Map<String, String> = emptyMap(),
        outputSchema: String = "HexagonalArchitecture/v1",
    ) {
        lenses += LensPlan(
            lensId = LensId(lensId),
            kind = kind,
            inputCapabilities = inputCapabilities,
            arguments = arguments,
            outputSchema = outputSchema,
        )
    }

    fun assertion(
        id: String,
        lensRef: String,
        operator: String,
        operands: Map<String, String> = emptyMap(),
        severity: Severity = Severity.Critical,
        enforcement: Enforcement = Enforcement.Mandatory,
        completenessRequirements: List<String> = emptyList(),
        rationale: String = "",
    ) {
        assertions += AssertionIR(
            id = AssertionId(id),
            lensRef = LensId(lensRef),
            operator = operator,
            operands = operands,
            severity = severity,
            enforcement = enforcement,
            completenessRequirements = completenessRequirements,
            rationale = rationale,
        )
    }

    internal fun build(): AssuranceSuiteIR = AssuranceSuiteIR(
        apiVersion = "assurance-ir/v1",
        suiteId = SuiteId(id),
        suiteVersion = version,
        requiredEvidence = requiredEvidence,
        lenses = lenses,
        assertions = assertions,
        metadata = metadata,
    )
}

@AssuranceDslMarker
class PackBuilder internal constructor(
    val name: String,
    val version: String,
) {
    var description: String = ""
    private val suites = mutableMapOf<String, AssuranceSuiteIR>()
    private val suiteRefs = mutableMapOf<String, AssurancePack.SuiteRef>()
    private val rules = mutableListOf<AssurancePack.Rule>()

    fun suite(id: String, block: SuiteBuilder.() -> Unit) {
        val builder = SuiteBuilder(id).apply(block)
        val ir = builder.build()
        suites[id] = ir
        // El pack mantiene una referencia "ligera" (id+version+digest)
        // en lugar de embeber el IR completo. El caller que quiera el
        // IR cruza `availableSuites` (de RequiredAssurancePlan) con
        // estos ids.
        suiteRefs[id] = AssurancePack.SuiteRef(
            suiteId = ir.suiteId,
            suiteVersion = ir.suiteVersion,
        )
    }

    fun mandatory(suiteId: String) {
        // El DSL bridgea la regla a `metadata["mandatory"]=true` para
        // que `RequiredAssurancePlan.build` la detecte como
        // `MandatoryBaseline` sin que el caller tenga que duplicar
        // el flag.
        rules += AssurancePack.Rule.Mandatory(SuiteId(suiteId))
        val existing = suites[suiteId]
        if (existing != null) {
            val patched = existing.copy(metadata = existing.metadata + ("mandatory" to "true"))
            suites[suiteId] = patched
            val ref = suiteRefs[suiteId]
            if (ref != null) {
                suiteRefs[suiteId] = ref.copy(suiteVersion = patched.suiteVersion)
            }
        }
    }

    fun touched(suiteId: String, prefix: String? = null) {
        rules += AssurancePack.Rule.Touched(SuiteId(suiteId), prefix)
    }

    fun newFindings(suiteId: String) {
        rules += AssurancePack.Rule.NewFindings(SuiteId(suiteId))
    }

    internal fun build(): AssurancePack = AssurancePack(
        name = name,
        packVersion = version,
        description = description,
        suites = suiteRefs.values.toList(),
        rules = rules,
    )

    /**
     * Devuelve el mapa de `AssuranceSuiteIR` declaradas en el pack, indexado
     * por `suiteId.value`. Útil cuando un caller tiene el pack y quiere
     * ejecutar las suites: cruza `availableSuites` con los `SuiteRef`s.
     */
    fun declaredSuites(): Map<String, AssuranceSuiteIR> = suites.toMap()
}

/**
 * Construye un `AssurancePack` con la DSL.
 *
 * Ejemplo:
 * ```
 * val pack = assurancePack("release-m10", "1.0.0") {
 *     description = "Suites obligatorias para release"
 *     suite("architecture.hexagonal") {
 *         lens(lensId = "lens/hex", kind = "architecture.hexagonal")
 *         assertion("hex/no-cycle", lensRef = "lens/hex", operator = "no-edge-between-sets")
 *     }
 *     mandatory("architecture.hexagonal")
 * }
 * ```
 */
fun assurancePack(
    name: String,
    packVersion: String,
    block: PackBuilder.() -> Unit,
): AssurancePack = PackBuilder(name, packVersion).apply(block).build()
