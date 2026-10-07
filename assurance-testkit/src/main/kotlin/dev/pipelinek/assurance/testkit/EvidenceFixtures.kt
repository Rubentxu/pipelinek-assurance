package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.Correlation
import dev.pipelinek.assurance.domain.evidence.CorrelationRelation
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.AssertionIR
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssuranceAssertion
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Enforcement
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult
import dev.pipelinek.assurance.engine.Severity
import dev.pipelinek.assurance.engine.SuiteId

/**
 * Generadores de evidencia sintética para los property tests de M0.
 *
 * Todo aquí es determinista: ni reloj, ni red, ni aleatoriedad sin semilla.
 * Un fixture que cambie entre ejecuciones rompe el digest de referencia.
 */
object EvidenceFixtures {

    val REVISION: RevisionRef = RevisionRef("0000000000000000000000000000000000000000")

    fun provenance(
        capability: String,
        producerId: String = "synthetic",
        producerVersion: String = "0.1.0",
        revision: RevisionRef = REVISION,
    ): Provenance = Provenance(
        producerId = producerId,
        producerVersion = producerVersion,
        subjectRevision = revision,
        capability = capability,
    )

    /**
     * Digest SHA-256 de un texto.
     *
     * Antes esto era un LCG con la forma de un hash: 64 hex chars que parecian
     * correctos pero no eran SHA-256. Cualquier referencia golden archivada con
     * ese valor habria sido falsa, y `Digest.of` solo hex-encodaba sin hashear.
     */
    fun digestOf(seed: String): Digest = Digest.ofUtf8(seed)

    /** Un Fact determinista. */
    fun fact(
        id: String,
        capability: String = "ModuleDependencies",
        subject: EvidenceSubject = EvidenceSubject.Module("core"),
        authority: EvidenceAuthority = EvidenceAuthority.DeterministicAnalyzer,
        completeness: Completeness = Completeness.Complete,
    ): EvidenceItem.Fact = EvidenceItem.Fact(
        id = EvidenceId(id),
        subject = subject,
        authority = authority,
        provenance = provenance(capability),
        predicate = "depends-on",
        objectValue = "adapter",
        completeness = completeness,
    )

    /** Un Signal heurístico. M-H01 exige autoridad HeuristicAnalyzer. */
    fun signal(
        id: String,
        capability: String = "StaticSmells",
        score: String = "0.42",
    ): EvidenceItem.Signal = EvidenceItem.Signal(
        id = EvidenceId(id),
        subject = EvidenceSubject.Module("core"),
        authority = EvidenceAuthority.HeuristicAnalyzer,
        provenance = provenance(capability),
        signalKind = "god-function",
        score = score,
        algorithmId = "synthetic-smells",
        algorithmVersion = "0.1.0",
    )

    /** Una hipótesis de agente. Nunca un Fact (ADR-004). */
    fun hypothesis(
        id: String,
        capability: String = "AgentReview",
        authority: EvidenceAuthority = EvidenceAuthority.AgentHypothesis,
    ): EvidenceItem.Hypothesis = EvidenceItem.Hypothesis(
        id = EvidenceId(id),
        subject = EvidenceSubject.Module("core"),
        authority = authority,
        provenance = provenance(capability),
        claim = "este modulo viola SRP",
        reasoning = "tres razones de lectura",
        confidence = "low",
    )

    /** Una observación de runtime. */
    fun observation(
        id: String,
        capability: String = "RuntimeInvocations",
    ): EvidenceItem.Observation = EvidenceItem.Observation(
        id = EvidenceId(id),
        subject = EvidenceSubject.RuntimeSpan("chronos-inv-1"),
        authority = EvidenceAuthority.RuntimeObserver,
        provenance = provenance(capability),
        observation = "adapter invoked domain",
    )

    fun manifest(
        producerId: String = "synthetic",
        produced: List<String> = listOf("ModuleDependencies"),
        completenessByCapability: Map<String, Completeness> =
            produced.associateWith { Completeness.Complete },
    ): EvidenceSourceManifest = EvidenceSourceManifest(
        producerId = producerId,
        producerVersion = "0.1.0",
        subjectRevision = REVISION,
        requestedCapabilities = produced,
        producedCapabilities = produced,
        completenessByCapability = completenessByCapability,
        schemaVersion = "assurance-evidence/v1",
        digest = digestOf(producerId),
    )

    /**
     * Snapshot sintético con items en un orden dado.
     *
     * El orden es parámetro explícito porque UAT-001 verifica que dos
     * permutaciones del mismo conjunto producen el mismo digest.
     */
    fun snapshot(
        items: List<EvidenceItem>,
        snapshotId: String = "snap-001",
        gaps: List<EvidenceGap> = emptyList(),
        correlations: List<Correlation> = emptyList(),
        sources: List<EvidenceSourceManifest> = listOf(manifest()),
    ): EvidenceSnapshot = EvidenceSnapshot(
        id = SnapshotId(snapshotId),
        subject = EvidenceSubject.Module("core"),
        sources = sources,
        items = items,
        gaps = gaps,
        correlations = correlations,
    )

    /** Snapshot con los tres casos de autoridad, para las leyes epistémicas. */
    fun mixedSnapshot(): EvidenceSnapshot = snapshot(
        items = listOf(
            fact("synthetic/alpha/depends-on/1"),
            signal("synthetic/beta/smell/1"),
            hypothesis("synthetic/gamma/hypothesis/1"),
            observation("synthetic/delta/invocation/1"),
        ),
        sources = listOf(
            manifest(
                produced = listOf("ModuleDependencies", "StaticSmells", "AgentReview", "RuntimeInvocations"),
            ),
        ),
    )

    fun correlation(
        from: ExternalNamespace = ExternalNamespace.ChronosInvocationId,
        fromValue: String = "inv-1",
        to: ExternalNamespace = ExternalNamespace.OTelSpanId,
        toValue: String = "span-1",
        evidence: String = "synthetic/alpha/depends-on/1",
    ): Correlation = Correlation(
        from = TypedExternalId(from, fromValue),
        relation = CorrelationRelation.CorrelatedWith,
        to = TypedExternalId(to, toValue),
        evidence = EvidenceId(evidence),
    )

    // -----------------------------------------------------------------------
    // Suite IR
    // -----------------------------------------------------------------------

    /** Suite mínima con una lens y una assertion coherentes entre sí (AAT-16). */
    fun suite(
        suiteId: String = "architecture",
        suiteVersion: String = "0.1.0",
        requiredEvidence: List<String> = listOf("ModuleDependencies"),
        assertionId: String = "a",
    ): AssuranceSuiteIR = AssuranceSuiteIR(
        apiVersion = "assurance/v1",
        suiteId = SuiteId(suiteId),
        suiteVersion = suiteVersion,
        requiredEvidence = requiredEvidence,
        lenses = listOf(
            LensPlan(
                lensId = LensId("deps"),
                kind = "architecture.dependencies",
                inputCapabilities = listOf("ModuleDependencies"),
                outputSchema = "schema://assurance/deps/v1",
            ),
        ),
        assertions = listOf(
            AssertionIR(
                id = AssertionId(assertionId),
                lensRef = LensId("deps"),
                operator = "no-edge-between-sets",
                operands = mapOf("domain" to "core", "forbidden" to "adapters"),
                severity = Severity.Error,
                enforcement = Enforcement.Advisory,
                completenessRequirements = listOf("ModuleDependencies"),
                rationale = "el domain no depende de adapters",
            ),
        ),
    )

    /** Runtime congelado con la lens de dependencias registrada. */
    fun frozenRuntime(engineVersion: String = "0.1.0"): FrozenAssuranceRuntime =
        FrozenAssuranceRuntime(
            engineVersion = engineVersion,
            lenses = mapOf(
                LensId("deps") to AssuranceLens<EvidenceSnapshot, List<EvidenceItem>> { input ->
                    val facts = input.items.filter { it.provenance.capability == "ModuleDependencies" }
                    if (facts.isEmpty()) {
                        ProjectionResult.ProjectionFailed(
                            ProjectionFailureReason.MissingCapability("ModuleDependencies"),
                            listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.Unsupported)),
                        )
                    } else {
                        ProjectionResult.Projected(facts)
                    }
                },
            ),
        )
}
