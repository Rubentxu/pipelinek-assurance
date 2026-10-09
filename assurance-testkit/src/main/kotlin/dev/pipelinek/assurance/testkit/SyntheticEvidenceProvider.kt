package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.RawEvidenceGap
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawGapReason
import dev.pipelinek.assurance.engine.RawItemKind

/**
 * M2 — Provider sintético para differential proof.
 *
 * Ref: `03-specifications/PROVIDER_SPI.md` y ROADMAP §M2.
 *
 * El provider real de CogniCode produce evidence desde un artefacto externo
 * (`assurance-evidence/v1` CBOR/JSON). El provider sintético produce la
 * **misma evidence** desde una lista en memoria, sin necesidad de I/O ni
 * de un export pre-codificado. La differential proof de M2-T5 compara los
 * dos paths: para el mismo grafo, el synthetic y el cognicode deben dar
 * `EvidenceCollectionResult.Produced` con la misma estructura.
 *
 * El provider sintético NO vive en `assurance-engine` (que prohíbe
 * implementaciones de provider, AAT-2) ni en `assurance-providers` (que
 * contendría un fake al lado de un real). Vive en `assurance-testkit`:
 * las implementaciones de test son del testkit, no de los módulos de
 * producto. Esto preserva la frontera "testkit puede tener fixtures y
 * dobles, no producto".
 *
 * El descriptor coincide con el de `CogniCodeArtifactProvider` en
 * capabilities y classification; el `id` y la `version` son distintos a
 * propósito: el caller que mira el `EvidenceSourceManifest` puede
 * distinguir "vino de synthetic" vs "vino de cognicode real", y un
 * informe de provenance dice cuál fue la fuente.
 */
class SyntheticEvidenceProvider(
    private val producerId: String = "synthetic",
    private val producerVersion: String = "0.1.0",
    private val outputSchemaVersion: String = "assurance-evidence/v1",
    private val items: List<RawEvidenceItem> = emptyList(),
    private val gaps: List<RawEvidenceGap> = emptyList(),
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor = EvidenceProviderDescriptor(
        id = producerId,
        version = producerVersion,
        evidenceCapabilities = listOf(
            "architecture.dependency-graph",
            "architecture.entities",
            "architecture.relations",
            "signals.solid_audit",
        ),
        subjectKinds = listOf("Module", "Symbol", "SourceLocation"),
        classification = ProviderClassification.Deterministic,
        inputFormats = listOf("internal://synthetic-fixture"),
        outputSchemaVersion = outputSchemaVersion,
    )

    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        if (items.isEmpty() && gaps.isEmpty()) {
            return EvidenceCollectionResult.Failed(
                producerId = producerId,
                reason = dev.pipelinek.assurance.engine.ProviderFailureReason.CollectionError(
                    "SyntheticEvidenceProvider construido sin items ni gaps",
                ),
                gaps = listOf(
                    RawEvidenceGap(
                        capability = "architecture.dependency-graph",
                        reason = RawGapReason.Unknown,
                        detail = "el provider fue construido con colecciones vacías",
                    ),
                ),
            )
        }
        return EvidenceCollectionResult.Produced(
            producerId = producerId,
            producerVersion = producerVersion,
            schemaVersion = outputSchemaVersion,
            rawItems = items,
            declaredGaps = gaps,
        )
    }
}

/**
 * Helper para construir `RawEvidenceItem` con el shape que el motor
 * espera, sin obligar al caller a repetir los nombres de campo en cada
 * sitio. La firma coincide con la convención que
 * `CogniCodeArtifactProvider` sigue, así que un `Fact` producido por
 * el synthetic y otro producido por el cognicode son estructuralmente
 * comparables en la differential proof.
 */
object SyntheticEvidenceItems {
    fun fact(
        id: String,
        subjectRef: String,
        authority: String,
        payload: Map<String, String>,
        sourceLocation: String? = null,
    ): RawEvidenceItem = RawEvidenceItem(
        kind = RawItemKind.Fact,
        id = id,
        subjectRef = subjectRef,
        authority = authority,
        payload = payload,
        sourceLocation = sourceLocation,
    )

    fun signal(
        id: String,
        subjectRef: String,
        signalKind: String,
        score: String,
        algorithmId: String,
        algorithmVersion: String,
        thresholds: Map<String, String> = emptyMap(),
    ): RawEvidenceItem = RawEvidenceItem(
        kind = RawItemKind.Signal,
        id = id,
        subjectRef = subjectRef,
        // M-H01: Signal siempre HeuristicAnalyzer.
        authority = "HeuristicAnalyzer",
        payload = mapOf(
            "signalKind" to signalKind,
            "score" to score,
            "algorithmId" to algorithmId,
            "algorithmVersion" to algorithmVersion,
        ) + thresholds,
    )
}
