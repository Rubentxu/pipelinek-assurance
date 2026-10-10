/**
 * M0 — Dominio de evidencia epistémica.
 *
 * Ref: `03-specifications/EVIDENCE_MODEL.md`, `02-architecture/FUNCTIONAL_CORE.md`,
 * ADR-004.
 *
 * Leyes que este archivo sostiene (AAT-8, AAT-9, AAT-20):
 * - Los estados cerrados son ADTs sealed con constructor propio.
 * - `Hypothesis` no puede construirse como `Fact` determinista por API pública.
 * - Autoridad y completitud son dimensiones ortogonales.
 *
 * AAT-1: este paquete no importa nada de filesystem, red, coroutines, CLI o
 * PipelineK. Sólo `kotlin-stdlib`.
 */
package dev.pipelinek.assurance.domain.evidence

import java.security.MessageDigest
import java.time.LocalDate

// ---------------------------------------------------------------------------
// Identidades estables (ADR-008)
// ---------------------------------------------------------------------------

/**
 * Identidad de un item de evidencia.
 *
 * Combina namespace de provider + identidad semántica del sujeto + kind +
 * discriminador estable, según `03-specifications/EVIDENCE_MODEL.md`.
 */
@JvmInline
value class EvidenceId(val value: String) {
    init {
        require(value.isNotBlank()) { "EvidenceId no puede estar vacio" }
        require(NAMESPACE_PATTERN.matches(value)) {
            "EvidenceId debe seguir namespace/subject/kind/discriminator: $value"
        }
    }

    override fun toString(): String = value

    private companion object {
        val NAMESPACE_PATTERN = Regex("""[a-z0-9][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*)*/.+""")
    }
}

/** Identidad de un snapshot completo de evidencia. */
@JvmInline
value class SnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "SnapshotId no puede estar vacio" }
    }

    override fun toString(): String = value
}

/**
 * Digest SHA-256 en hexadecimal.
 *
 * Se usa como valor en el core, no como wrapper de `String` libre, para que
 * AAT-13 (tipos distintos para identidades externas) no se degrade en la
 * práctica.
 */
@JvmInline
value class Digest(val hex: String) {
    init {
        require(hex.length == 64) { "Digest debe tener 64 hex chars, tiene ${hex.length}" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Digest debe ser hex lowercase" }
    }

    override fun toString(): String = hex

    companion object {
        /** Digest SHA-256 de los bytes dados. */
        fun of(bytes: ByteArray): Digest = Digest(sha256Hex(bytes))

        /** Digest SHA-256 de la representación UTF-8 del texto dado. */
        fun ofUtf8(text: String): Digest = Digest(sha256Hex(text.toByteArray(Charsets.UTF_8)))

        private fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { byte ->
                val v = byte.toInt() and 0xff
                HEX[v ushr 4].toString() + HEX[v and 0x0f]
            }
        }

        private const val HEX = "0123456789abcdef"
    }
}

/** Revisión (commit) sobre la que se produjo la evidencia. */
@JvmInline
value class RevisionRef(val value: String) {
    init {
        require(value.isNotBlank()) { "RevisionRef no puede estar vacio" }
    }

    override fun toString(): String = value
}

/**
 * Identidad de una evaluación de assurance.
 *
 * Distinta de `PipelineStepOpId` (ADR-008): assurance no fabrica OpIds.
 */
@JvmInline
value class AssuranceEvaluationId(val value: String) {
    init {
        require(value.isNotBlank()) { "AssuranceEvaluationId no puede estar vacio" }
    }

    override fun toString(): String = value
}

// ---------------------------------------------------------------------------
// Autoridad epistémica (ADR-004)
// ---------------------------------------------------------------------------

/**
 * Autoridad de un item de evidencia.
 *
 * Ortogonal a [Completeness]: un item `Complete` puede ser `HeuristicAnalyzer`
 * y uno `Partial` puede ser `DeterministicAdapter`.
 *
 * AAT-19: una assertion que exige autoridad determinista no puede satisfacer
 * un item cuya autoridad sea heurística o de hipótesis.
 */
enum class EvidenceAuthority {
    /** Adaptador con resultado reproducible bit a bit. */
    DeterministicAdapter,

    /** Analyzer determinista sobre la entrada, sin I/O. */
    DeterministicAnalyzer,

    /** Observación de una ejecución concreta. */
    RuntimeObserver,

    /** Heurística, ranking o score derivado. Nunca gatea sin admisión. */
    HeuristicAnalyzer,

    /** Curado por una persona. */
    HumanCurated,

    /** Propuesta de un agente LLM. Nunca es un Fact. */
    AgentHypothesis,
    ;

    /**
     * ¿Esta autoridad es determinista?
     *
     * ADR-004 y AAT-19: sólo las tres primeras. Un `Signal` heurístico no
     * puede satisfacer una assertion que exige `Deterministic`.
     */
    val isDeterministic: Boolean
        get() = this == DeterministicAdapter ||
            this == DeterministicAnalyzer ||
            this == RuntimeObserver

    /** En V1 ninguna autoridad es suficiente por sí sola para bloquear un gate. */
    val mayGate: Boolean get() = false
}

// ---------------------------------------------------------------------------
// Completitud (ADR-004)
// ---------------------------------------------------------------------------

/** Un hueco en la evidencia, con la razón por la que no se pudo producir. */
data class EvidenceGap(
    val capability: String,
    val reason: GapReason,
    val detail: String? = null,
) {
    sealed interface GapReason {
        /** El provider no soporta esta capability. */
        data object Unsupported : GapReason

        /** El provider la soporta pero produjo cobertura parcial. */
        data class PartialProduced(val coveredFraction: String) : GapReason

        /** No se sabe si se soporta. */
        data object Unknown : GapReason

        /** La evidencia se perdió después de producirse. */
        data object Lost : GapReason
    }
}

/**
 * Completitud de una capability.
 *
 * NO es confianza. `Complete` + `HeuristicAnalyzer` es un estado válido:
 * la evidencia está completa pero su autoridad es heurística.
 */
sealed interface Completeness {
    data object Complete : Completeness

    data class Partial(val gaps: List<EvidenceGap>) : Completeness {
        init {
            require(gaps.isNotEmpty()) { "Partial exige al menos un gap" }
        }
    }

    data object Unknown : Completeness

    data class Unsupported(val reason: String) : Completeness
}

// ---------------------------------------------------------------------------
// Sujetos y procedencia
// ---------------------------------------------------------------------------

/** Referencia al sujeto sobre el que afirma la evidencia. */
sealed interface EvidenceSubject {
    data class Module(val path: String) : EvidenceSubject
    data class Symbol(val qualifiedName: String) : EvidenceSubject
    data class SourceLocation(val file: String, val line: Int, val column: Int?) : EvidenceSubject
    data class Test(val id: String) : EvidenceSubject
    data class RuntimeSpan(val typedRef: String) : EvidenceSubject
}

/** Origen de la evidencia. Describe procedencia, NO implica confianza. */
data class Provenance(
    val producerId: String,
    val producerVersion: String,
    val subjectRevision: RevisionRef,
    val capability: String,
    /** URL o path del artefacto externo del que se leyó. */
    val artifactRef: String? = null,
    val artifactDigest: Digest? = null,
)

// ---------------------------------------------------------------------------
// Items de evidencia (ADR-004)
// ---------------------------------------------------------------------------

/**
 * Un item de evidencia.
 *
 * Las cuatro categorías son tipos distintos y no se suben ni se bajan entre sí
 * por conversión implícita. En particular no existe `toFact()` ni constructor
 * que convierta un [Hypothesis] en [Fact] (AAT-9).
 */
sealed interface EvidenceItem {
    val id: EvidenceId
    val subject: EvidenceSubject
    val authority: EvidenceAuthority
    val provenance: Provenance

    /** Afirmación reproducible sobre el snapshot. */
    data class Fact(
        override val id: EvidenceId,
        override val subject: EvidenceSubject,
        override val authority: EvidenceAuthority,
        override val provenance: Provenance,
        val predicate: String,
        val objectValue: String?,
        val completeness: Completeness = Completeness.Complete,
    ) : EvidenceItem {
        init {
            // M-E01: un Fact nunca se declara `Unknown` ni `Unsupported`.

        }
    }

    /** Algo observado en una ejecución concreta. */
    data class Observation(
        override val id: EvidenceId,
        override val subject: EvidenceSubject,
        override val authority: EvidenceAuthority,
        override val provenance: Provenance,
        val observation: String,
        val completeness: Completeness = Completeness.Complete,
    ) : EvidenceItem

    /** Señal heurística o derivada. Nunca es un Fact (M-H01). */
    data class Signal(
        override val id: EvidenceId,
        override val subject: EvidenceSubject,
        override val authority: EvidenceAuthority,
        override val provenance: Provenance,
        val signalKind: String,
        val score: String,
        val algorithmId: String,
        val algorithmVersion: String,
        val thresholds: Map<String, String> = emptyMap(),
        val completeness: Completeness = Completeness.Complete,
    ) : EvidenceItem {
        init {
            // M-H01: un Signal no puede declararse de autoridad determinista.
            require(authority == EvidenceAuthority.HeuristicAnalyzer) {
                "Un Signal requiere autoridad HeuristicAnalyzer, no $authority"
            }
        }
    }

    /**
     * Propuesta de un agente o una persona, sin autoridad.
     *
     * ADR-004: esto NUNCA se convierte en un Fact. M-E02 mata el mutante que
     * lo acepta como tal.
     */
    data class Hypothesis(
        override val id: EvidenceId,
        override val subject: EvidenceSubject,
        override val authority: EvidenceAuthority,
        override val provenance: Provenance,
        val claim: String,
        val reasoning: String,
        val confidence: String,
    ) : EvidenceItem {
        init {
            require(
                authority == EvidenceAuthority.AgentHypothesis ||
                    authority == EvidenceAuthority.HumanCurated,
            ) {
                "Una Hypothesis requiere AgentHypothesis o HumanCurated, no $authority"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Manifest del producer
// ---------------------------------------------------------------------------

/** Lo que un producer declara sobre su propia cobertura. */
data class EvidenceSourceManifest(
    val producerId: String,
    val producerVersion: String,
    val subjectRevision: RevisionRef,
    val requestedCapabilities: List<String>,
    val producedCapabilities: List<String>,
    /** Completitud por capability, no global. */
    val completenessByCapability: Map<String, Completeness>,
    val schemaVersion: String,
    val digest: Digest,
) {
    init {
        // Un provider no puede declarar una capacidad que no produjo como
        // `Complete`. PROVIDER_SPI.md: devuelve `Unsupported` o `Partial`.
        val unknown = completenessByCapability.keys - producedCapabilities.toSet()
        require(unknown.isEmpty()) {
            "Capacities con completitud declarada pero no producidas: $unknown"
        }
    }
}

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

/**
 * Snapshot completo de evidencia sobre un sujeto.
 *
 * `items` es una lista, no un Set: el orden canónico lo impone el serializer,
 * no la colección. Esto hace posible la property law de permutación (UAT-001).
 */
data class EvidenceSnapshot(
    val id: SnapshotId,
    val subject: EvidenceSubject,
    val sources: List<EvidenceSourceManifest>,
    val items: List<EvidenceItem>,
    val gaps: List<EvidenceGap>,
    val correlations: List<Correlation> = emptyList(),
) {
    init {
        // ADR-008: una correlation siempre necesita ambos extremos tipados.
        require(sources.isNotEmpty()) { "Un snapshot necesita al menos un source manifest" }
    }

    /** `Partial` si algún manifest declara incompletitud, si no `Complete`. */
    val overallCompleteness: Completeness
        get() {
            val allGaps = sources.flatMap { it.completenessByCapability.values }
                .filterIsInstance<Completeness.Partial>()
                .flatMap { it.gaps }
            // El mismo gap puede declararse a nivel snapshot y en el manifest
            // que lo produjo. Sin deduplicar, el reporte cuenta dos veces la
            // misma carencia y el digest de completitud se deforma.
            val gaps = (gaps + allGaps).distinct()
            return if (gaps.isEmpty()) Completeness.Complete else Completeness.Partial(gaps)
        }
}

/**
 * Relación tipada entre identidades externas.
 *
 * Nunca colapsa IDs. `PipelineStepOpId`, `ChronosInvocationId`, `OTelTraceId` y
 * `CogniCodeEntityId` son namespaces distintos (ADR-008, AAT-13).
 */
data class Correlation(
    val from: TypedExternalId,
    val relation: CorrelationRelation,
    val to: TypedExternalId,
    val evidence: EvidenceId,
)

/** ID externo con namespace explícito. Nunca un `String` desnudo. */
data class TypedExternalId(
    val namespace: ExternalNamespace,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "TypedExternalId no puede estar vacio" }
    }
}

enum class ExternalNamespace {
    PipelineRunId,
    PipelineStepOpId,
    AssuranceEvaluationId,
    CogniCodeSnapshotId,
    CogniCodeEntityId,
    ChronosSessionId,
    ChronosInvocationId,
    OTelTraceId,
    OTelSpanId,
    GitRevision,
}

enum class CorrelationRelation {
    CorrelatedWith,
    DerivedFrom,
    ObservedIn,
    TestsFor,
}

/** Fecha de expiración de una excepción de baseline. */
@JvmInline
value class ExpiryDate(val value: LocalDate) {
    override fun toString(): String = value.toString()
}
