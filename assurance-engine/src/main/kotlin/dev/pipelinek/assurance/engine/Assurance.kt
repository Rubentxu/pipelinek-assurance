/**
 * M0 — Álgebra de assurance.
 *
 * Ref: `02-architecture/FUNCTIONAL_CORE.md`, `03-specifications/ASSERTIONS_AND_REPORTS.md`,
 * `03-specifications/ASSURANCE_IR.md`.
 *
 * AAT-2: este módulo no depende de implementaciones de provider.
 * AAT-7: ninguna lens escribe filesystem ni red.
 * AAT-8: los estados son ADTs sealed, nunca booleanos.
 * AAT-20: la rama `no evidence` no puede construir `Passed`.
 */
package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Correlation
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.TypedExternalId

// ---------------------------------------------------------------------------
// Identidades
// ---------------------------------------------------------------------------

/** Identidad de una assertion dentro de una suite. */
@JvmInline
value class AssertionId(val value: String) {
    init {
        require(value.isNotBlank()) { "AssertionId no puede estar vacio" }
    }

    override fun toString(): String = value
}

/** Identidad de una lens dentro de una suite. */
@JvmInline
value class LensId(val value: String) {
    init {
        require(value.isNotBlank()) { "LensId no puede estar vacio" }
    }

    override fun toString(): String = value
}

/** Identidad de una suite de assurance. */
@JvmInline
value class SuiteId(val value: String) {
    init {
        require(value.isNotBlank()) { "SuiteId no puede estar vacio" }
    }

    override fun toString(): String = value
}

// ---------------------------------------------------------------------------
// Enforcement y severity (03-specifications/ASSURANCE_DSL.md)
// ---------------------------------------------------------------------------

/**
 * Cuánto manda una assertion.
 *
 * Distinto de [Severity]: enforcement responde "qué pasa si falla", severity
 * responde "qué tan grave es". Una assertion `Advisory` de severidad `Critical`
 * sigue sin bloquear.
 */
enum class Enforcement {
    /** Se reporta pero nunca bloquea. Es el estado de nacimiento (ROADMAP §5.4). */
    Advisory,

    /** Falla el gate en fail-closed. */
    Mandatory,

    /** Bloquea sólo respecto a un baseline. */
    Ratchet,
}

enum class Severity {
    Info,
    Warning,
    Error,
    Critical,
}

// ---------------------------------------------------------------------------
// Lenses
// ---------------------------------------------------------------------------

/**
 * Resultado de proyectar un snapshot con una lens.
 *
 * Una lens NUNCA produce una violación directamente
 * (`03-specifications/LENSES.md`). Si la evidencia no permite proyectar, el
 * resultado es `ProjectionFailed` y quien decide el veredicto es la assertion.
 */
sealed interface ProjectionResult<out A> {
    data class Projected<A>(val value: A) : ProjectionResult<A>

    data class ProjectionFailed(
        val reason: ProjectionFailureReason,
        val gaps: List<EvidenceGap>,
    ) : ProjectionResult<Nothing>
}

sealed interface ProjectionFailureReason {
    /** La capability requerida no está en el snapshot. */
    data class MissingCapability(val capability: String) : ProjectionFailureReason

    /** El formato de la evidencia no es el esperado. */
    data class InvalidInput(val detail: String) : ProjectionFailureReason

    /** El kind de lens no está soportado por esta versión del engine. */
    data object UnsupportedLensKind : ProjectionFailureReason
}

/**
 * Proyección pura y tipada de evidencia a una vista para assertions.
 *
 * `fun interface` a propósito: una lens es un valor, no un objeto con estado.
 */
fun interface AssuranceLens<I, O> {
    fun project(input: I): ProjectionResult<O>
}

// ---------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------

/**
 * Evaluación de una assertion sobre una proyección.
 *
 * Contrato central de M0: **no evidence nunca es `Passed`**. No hay forma de
 * construir `Passed` sin [ProofRef], y [ProofRef] no se puede construir sin
 * evidencia que lo respalde. El mutante M-E01 y el UAT-002 atacan esto.
 */
sealed interface AssertionResult {
    /** Referencia a la evidencia que demuestra el éxito. No construible en vacío. */
    data class Passed(val proof: ProofRef) : AssertionResult

    data class Failed(val counterexample: Counterexample) : AssertionResult

    /** Falta evidencia relevante. Un `Mandatory` con `requireComplete` falla el gate. */
    data class Inconclusive(val gaps: List<EvidenceGap>) : AssertionResult {
        init {
            require(gaps.isNotEmpty()) { "Inconclusive exige al menos un gap" }
        }
    }

    data class Unsupported(val reason: UnsupportedReason) : AssertionResult

    data class Error(val failure: EvaluationFailure) : AssertionResult
}

/**
 * Evidencia que sostiene un `Passed`.
 *
 * Se construye sólo desde un `EvidenceSnapshot` real. No hay constructor
 * público que acepte una lista vacía (AAT-20).
 */
data class ProofRef(
    val snapshotId: String,
    val evidenceIds: List<EvidenceId>,
    val assertionId: AssertionId,
) {
    init {
        require(evidenceIds.isNotEmpty()) {
            "Un Passed exige evidencia que lo respalde; un ProofRef vacio es ilegal (AAT-20)"
        }
    }

    companion object {
        /**
         * Extrae las refs de evidencia del snapshot. Devuelve `null` si no hay
         * evidencia, en lugar de un `ProofRef` degenerado.
         */
        fun from(
            snapshot: EvidenceSnapshot,
            assertionId: AssertionId,
            filter: (EvidenceId) -> Boolean,
        ): ProofRef? {
            val matching = snapshot.items.map { it.id }.filter(filter)
            if (matching.isEmpty()) return null
            return ProofRef(snapshot.id.value, matching, assertionId)
        }
    }
}

sealed interface UnsupportedReason {
    data class UnknownLensKind(val kind: String) : UnsupportedReason
    data class UnknownOperator(val operator: String) : UnsupportedReason
    data class UnknownEvidenceKind(val kind: String) : UnsupportedReason
    data class AuthorityNotAdmitted(
        val required: String,
        val offered: String,
    ) : UnsupportedReason
}

data class EvaluationFailure(
    val phase: String,
    val detail: String,
    val cause: String? = null,
)

// ---------------------------------------------------------------------------
// Counterexamples
// ---------------------------------------------------------------------------

/**
 * Contraejemplo estructurado, nunca un mensaje plano.
 *
 * `03-specifications/ASSERTIONS_AND_REPORTS.md` lista los tipos V1. Cada uno
 * lleva su propia forma de evidencia mínima.
 */
sealed interface Counterexample {
    val assertionId: AssertionId
    val subjectRefs: List<TypedExternalId>
    val evidenceRefs: List<EvidenceId>
    val explanation: String
    val reproductionHints: List<String>

    data class DependencyPath(
        override val assertionId: AssertionId,
        override val subjectRefs: List<TypedExternalId>,
        override val evidenceRefs: List<EvidenceId>,
        override val explanation: String,
        override val reproductionHints: List<String>,
        val path: List<String>,
        val fromLayer: String,
        val toLayer: String,
    ) : Counterexample

    data class Cycle(
        override val assertionId: AssertionId,
        override val subjectRefs: List<TypedExternalId>,
        override val evidenceRefs: List<EvidenceId>,
        override val explanation: String,
        override val reproductionHints: List<String>,
        val cycle: List<String>,
    ) : Counterexample

    data class CausalSlice(
        override val assertionId: AssertionId,
        override val subjectRefs: List<TypedExternalId>,
        override val evidenceRefs: List<EvidenceId>,
        override val explanation: String,
        override val reproductionHints: List<String>,
        val invocationChain: List<String>,
    ) : Counterexample

    data class Mutation(
        override val assertionId: AssertionId,
        override val subjectRefs: List<TypedExternalId>,
        override val evidenceRefs: List<EvidenceId>,
        override val explanation: String,
        override val reproductionHints: List<String>,
        val mutatedSymbol: String,
        val killedBy: String?,
    ) : Counterexample

    data class MissingTrace(
        override val assertionId: AssertionId,
        override val subjectRefs: List<TypedExternalId>,
        override val evidenceRefs: List<EvidenceId>,
        override val explanation: String,
        override val reproductionHints: List<String>,
        val missingSpanFor: String,
        val expectedPropagation: String,
    ) : Counterexample

    data class BaselineRegression(
        override val assertionId: AssertionId,
        override val subjectRefs: List<TypedExternalId>,
        override val evidenceRefs: List<EvidenceId>,
        override val explanation: String,
        override val reproductionHints: List<String>,
        val stableId: String,
        val state: DiffState,
    ) : Counterexample
}

enum class DiffState {
    New,
    Existing,
    Resolved,
    Regressed,
    Changed,
}

// ---------------------------------------------------------------------------
// Suite IR
// ---------------------------------------------------------------------------

/**
 * IR cerrado y serializable de una suite.
 *
 * La DSL Kotlin (M1) es sólo una façade de autoría: esto es la autoridad
 * reproducible. Un digest de suite debe ser idéntico entre runners.
 */
data class AssuranceSuiteIR(
    val apiVersion: String,
    val suiteId: SuiteId,
    val suiteVersion: String,
    val requiredEvidence: List<String>,
    val lenses: List<LensPlan>,
    val assertions: List<AssertionIR>,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(lenses.isNotEmpty()) { "Una suite necesita al menos una lens" }
        require(assertions.isNotEmpty()) { "Una suite necesita al menos una assertion" }

        // AAT-16: toda assertion debe referenciar una lens declarada.
        val declared = lenses.map { it.lensId }.toSet()
        val dangling = assertions.map { it.lensRef }.filterNot { it in declared }
        require(dangling.isEmpty()) {
            "Assertions referencian lenses no declaradas: $dangling"
        }
    }
}

data class LensPlan(
    val lensId: LensId,
    /** Namespaced: `architecture.hexagonal`, `architecture.cycles`. */
    val kind: String,
    val inputCapabilities: List<String>,
    val arguments: Map<String, String> = emptyMap(),
    val outputSchema: String,
)

data class AssertionIR(
    val id: AssertionId,
    val lensRef: LensId,
    /** `no-edge-between-sets`, `acyclic`, `path-must-not-exist`, ... */
    val operator: String,
    val operands: Map<String, String>,
    val severity: Severity,
    val enforcement: Enforcement,
    /** Capabilities cuya ausencia produce `Inconclusive`. */
    val completenessRequirements: List<String>,
    val rationale: String,
    /**
     * Autoridad que esta assertion admite.
     *
     * AAT-19: si no se admite `HeuristicAnalyzer`, un `Signal` no la satisface.
     */
    val admittedAuthorities: Set<String> = setOf("DeterministicAdapter", "DeterministicAnalyzer", "RuntimeObserver"),
)

// ---------------------------------------------------------------------------
// Evaluation
// ---------------------------------------------------------------------------

/**
 * Assertion evaluable.
 *
 * `fun interface`: una assertion es dato evaluable, no un Step (ADR-002).
 */
fun interface AssuranceAssertion<A> {
    fun evaluate(input: A): AssertionResult
}

/**
 * Contexto congelado de evaluación.
 *
 * `02-architecture/FUNCTIONAL_CORE.md`: no hay registries mutables después del
 * freeze. El evaluador recibe esto, no un `MutableMap` global.
 */
data class FrozenAssuranceRuntime(
    val engineVersion: String,
    val lenses: Map<LensId, AssuranceLens<EvidenceSnapshot, *>>,
) {
    init {
        require(engineVersion.isNotBlank()) { "engineVersion es obligatorio para el digest" }
    }
}

/**
 * Resultado de evaluar una suite completa.
 */
data class AssuranceReport(
    val evaluationId: AssuranceEvaluationId,
    val snapshotDigest: Digest,
    val suiteDigest: Digest,
    val engineVersion: String,
    val results: List<AssertionResult>,
    val gaps: List<EvidenceGap>,
    val artifacts: List<ArtifactRef>,
    val correlations: List<Correlation>,
) {
    /** Resumen por estado. Nunca un score agregado (ADR-006). */
    val summary: AssuranceSummary
        get() {
            var passed = 0
            var failed = 0
            var inconclusive = 0
            var unsupported = 0
            var errored = 0
            for (r in results) {
                when (r) {
                    is AssertionResult.Passed -> passed++
                    is AssertionResult.Failed -> failed++
                    is AssertionResult.Inconclusive -> inconclusive++
                    is AssertionResult.Unsupported -> unsupported++
                    is AssertionResult.Error -> errored++
                }
            }
            return AssuranceSummary(passed, failed, inconclusive, unsupported, errored)
        }
}

data class AssuranceSummary(
    val passed: Int,
    val failed: Int,
    val inconclusive: Int,
    val unsupported: Int,
    val errored: Int,
) {
    val total: Int get() = passed + failed + inconclusive + unsupported + errored
}

data class ArtifactRef(
    val digest: Digest,
    val mediaType: String,
    val logicalRole: String,
)

/**
 * Funciones conceptuales puras del functional core.
 *
 * No leen filesystem, no llaman red, no leen clock global.
 */
object AssuranceEngine {

    /**
     * Proyecta con una lens.
     *
     * Una lens que necesita conocer el provider es un error de diseño, no un
     * caso de uso: M0 STOP. Por eso el input es siempre `EvidenceSnapshot`.
     */
    fun <O> project(
        snapshot: EvidenceSnapshot,
        lens: AssuranceLens<EvidenceSnapshot, O>,
    ): ProjectionResult<O> = lens.project(snapshot)

    /**
     * Evalúa una assertion sobre una proyección.
     *
     * La propagación de `ProjectionFailed` a `Inconclusive` es lo que impide que
     * la falta de evidencia se convierta en un veredicto.
     */
    fun <A> evaluate(
        projection: ProjectionResult<A>,
        assertion: AssuranceAssertion<A>,
    ): AssertionResult = when (projection) {
        is ProjectionResult.Projected -> assertion.evaluate(projection.value)
        is ProjectionResult.ProjectionFailed -> AssertionResult.Inconclusive(projection.gaps)
    }

    /**
     * Evalúa una suite.
     *
     * Sin short-circuit: se acumulan todos los resultados para un informe
     * completo. Sólo el Step handler decide cómo transformar el report a
     * outcome.
     */
    fun evaluateSuite(
        snapshot: EvidenceSnapshot,
        suite: AssuranceSuiteIR,
        runtime: FrozenAssuranceRuntime,
        digestOf: (AssuranceSuiteIR) -> Digest,
        snapshotDigest: Digest,
        assertionsById: Map<AssertionId, AssuranceAssertion<*>>,
    ): AssuranceReport {
        val lensById = suite.lenses.associateBy { it.lensId }
        val results = suite.assertions.map { ir ->
            val lens = runtime.lenses[ir.lensRef]
                ?: return@map AssertionResult.Unsupported(
                    UnsupportedReason.UnknownLensKind(lensById[ir.lensRef]?.kind ?: ir.lensRef.value),
                )
            val assertion = assertionsById[ir.id]
                ?: return@map AssertionResult.Unsupported(
                    UnsupportedReason.UnknownOperator(ir.operator),
                )
            // El registry congelado indexa por id; el tipo concreto de la
            // proyeccion lo aporta la assertion. La unica asercion segura aqui
            // es que ambos estan en el mismo registry, no que compartan tipo.
            // El segundo cast sobra: ProjectionResult es covariant en `out A`.
            @Suppress("UNCHECKED_CAST")
            evaluate(
                project(snapshot, lens as AssuranceLens<EvidenceSnapshot, Any?>),
                assertion as AssuranceAssertion<Any?>,
            )
        }

        val gaps = snapshot.gaps +
            suite.requiredEvidence.filterNot { cap -> snapshot.items.any { it.provenance.capability == cap } }
                .map { EvidenceGap(it, EvidenceGap.GapReason.Unsupported, "capability requerida ausente") }

        return AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-${snapshot.id.value}-${suite.suiteId.value}"),
            snapshotDigest = snapshotDigest,
            suiteDigest = digestOf(suite),
            engineVersion = runtime.engineVersion,
            results = results,
            gaps = gaps,
            artifacts = emptyList(),
            correlations = snapshot.correlations,
        )
    }

    /**
     * Decisión de enforcement a partir del resultado completo.
     *
     * A1 (Bloque A): NO usar únicamente los contadores agregados
     * del report para decidir el gate. La asociación assertion ↔
     * política se preserva: el IR declara `enforcement` y la
     * función lo cruza con el `AssertionResult` de cada assertion
     * para emitir un `EnforcementDecision` tipado.
     *
     * Comportamiento por (enforcement, result):
     *
     * | enforcement | Passed        | Failed/Inconclusive/Unsupported/Error |
     * |-------------|---------------|--------------------------------------|
     * | Advisory    | no afecta     | no afecta                            |
     * | Mandatory   | no afecta     | gate failure                         |
     * | Ratchet     | no afecta     | (gestionado por baseline, ver F1)    |
     *
     * Modo de ejecución:
     * - FailClosed: cualquier gate failure produce `EnforcementDecision.Failure`.
     * - ReportOnly: los gate failures se degradan a `Advisory` (el Step sigue, el
     *   report documenta el fallo). Esto es lo que un `assurance.check --report-only`
     *   o un dev-server usaría.
     *
     * AAT-20: la rama `no evidence` (Unsupported con cap requerida ausente) sobre
     * una assertion Mandatory es `Failure`, no `Passed`. Lo que aquí se codifica
     * es: `Unsupported` sobre Mandatory ⇒ `Failure` (con su `UnsupportedReason`).
     *
     * AAT-19: `Signal` heurístico sobre assertion que exige `Deterministic` es
     * detectado por el dominio antes de llegar aquí; pero esta función es robusta
     * ante esa entrada: `Unsupported` (motivada por incompatibilidad de autoridad)
     * ⇒ `Failure` si Mandatory.
     *
     * **Política de Ratchet**: el ratcheting contra baseline se delega a un
     * componente externo (DiffEngine) que produce un `DiffState` por finding.
     * Esta función sólo marca como `Failure` las `Mandatory` puras (no
     * ratchets); un Ratchet con baseline se resuelve en el componente de
     * diff. La entrada `ratchetFailure` permite al caller inyectar el
     * resultado del diff sin que esta función conozca el formato de baseline.
     */
    fun evaluateEnforcement(
        report: AssuranceReport,
        suite: AssuranceSuiteIR,
        mode: EnforcementMode,
        ratchetFailure: Boolean = false,
    ): EnforcementDecision {
        // Index por id para mantener la asociación assertion ↔ enforcement.
        val enforcementById: Map<AssertionId, Enforcement> =
            suite.assertions.associate { it.id to it.enforcement }

        val perAssertion: List<EnforcementEntry> = report.results.map { result ->
            // El id del AssertionResult no se preserva actualmente
            // (A1 secundario: el IR debe llevar su id al resultado para
            // que el join sea directo). Por ahora, enforcemos por posición:
            // report.results tiene el mismo orden que suite.assertions
            // porque evaluateSuite los itera en ese orden.
            val irAssertion = suite.assertions.getOrNull(
                report.results.indexOf(result),
            )
            val enforcement = irAssertion?.enforcement ?: Enforcement.Advisory
            EnforcementEntry(
                assertionId = irAssertion?.id,
                enforcement = enforcement,
                result = result,
            )
        }

        val blockingFailures: List<EnforcementEntry> = perAssertion.filter { entry ->
            when (entry.enforcement) {
                Enforcement.Advisory -> false
                Enforcement.Mandatory -> entry.result !is AssertionResult.Passed
                Enforcement.Ratchet -> ratchetFailure
            }
        }

        return when {
            blockingFailures.isEmpty() -> EnforcementDecision.Passed(perAssertion)
            mode == EnforcementMode.ReportOnly -> EnforcementDecision.Advisory(blockingFailures)
            else -> EnforcementDecision.Failure(blockingFailures, mode)
        }
    }
}

/**
 * Modo de ejecución del gate.
 *
 * Ref: Bloque A. La decisión se preserva de `AssuranceCheckStep` y
 * se sube al engine para que la función pura la pueda consumir sin
 * importar el plugin.
 */
enum class EnforcementMode {
    /** Mandatory fail ⇒ gate failure. Default. */
    FailClosed,

    /** Mandatory fail ⇒ se reporta pero no bloquea. Dev/seguimiento. */
    ReportOnly,
}

/**
 * Decisión de gate tipada, resultado de `evaluateEnforcement`.
 *
 * Tres formas exhaustivas:
 * - `Passed`: el gate pasa. La lista lleva el detalle por assertion
 *   para que el Step handler pueda serializar el report sin perder
 *   información.
 * - `Advisory`: el gate habría fallado pero el modo es ReportOnly.
 *   El Step sigue; el report documenta qué falló.
 * - `Failure`: gate rojo. El Step para el pipeline. La lista lleva
 *   las assertions que rompieron el gate.
 *
 * `Failure` y `Advisory` comparten `blockingFailures` con el mismo
 * tipo para que el caller pueda iterar sin doble dispatch.
 */
sealed interface EnforcementDecision {
    val perAssertion: List<EnforcementEntry>

    data class Passed(override val perAssertion: List<EnforcementEntry>) : EnforcementDecision

    data class Advisory(val blockingFailures: List<EnforcementEntry>) : EnforcementDecision {
        override val perAssertion: List<EnforcementEntry> get() = blockingFailures
    }

    data class Failure(
        val blockingFailures: List<EnforcementEntry>,
        val mode: EnforcementMode,
    ) : EnforcementDecision {
        override val perAssertion: List<EnforcementEntry> get() = blockingFailures
    }
}

/**
 * Entrada por assertion que cruza el `AssertionResult` con su `Enforcement`.
 *
 * Es la asociación assertion ↔ política que el plan A1 pide preservar:
 * un `Failed` que es `Advisory` no es lo mismo que un `Failed` que es
 * `Mandatory`, y el `EnforcementDecision` los trata distinto.
 */
data class EnforcementEntry(
    val assertionId: AssertionId?,
    val enforcement: Enforcement,
    val result: AssertionResult,
)
