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
}
