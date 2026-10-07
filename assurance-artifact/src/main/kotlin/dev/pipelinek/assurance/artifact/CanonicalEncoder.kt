package dev.pipelinek.assurance.artifact

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
import dev.pipelinek.assurance.engine.AssuranceSuiteIR

/**
 * M0 — Encoding canónico y digest determinista.
 * Ref: `03-specifications/ARTIFACT_WIRE_CONTRACTS.md`, `02-architecture/FUNCTIONAL_CORE.md` §8.
 *
 * Contrato de determinismo: para los mismos (snapshot, suite, engine version,
 * baseline digest) el digest canónico debe ser idéntico. De ahí las tres
 * reglas que impone este encoder:
 *
 * 1. **Orden canónico explícito.** Nada depende del orden de iteración de un
 *    `HashMap` ni del orden físico de entrada. M-R01 mata el mutante que
 *    serializa sin canonicalizar.
 * 2. **Sin timestamps en el digest semántico.** Un reloj rompe la
 *    reproducibilidad (AAT-17).
 * 3. **Separación por longitud.** Cada campo se emite con prefijo de longitud
 *    para que ninguna concatenación pueda colisionar.
 */
object CanonicalEncoder {

    private const val SNAPSHOT_SCHEMA = "assurance-evidence/v1"
    private const val SUITE_SCHEMA = "assurance-suite/v1"

    /**
     * Serializa un snapshot a su forma canónica.
     *
     * `items` se ordena por `EvidenceId` antes de emitir; `sources` por
     * `producerId`; `gaps` por `capability`; `correlations` por el par de ids.
     * La permutación de entrada no altera el resultado (UAT-001).
     */
    fun encodeSnapshot(snapshot: EvidenceSnapshot): String = buildString {
        appendField("schema", SNAPSHOT_SCHEMA)
        appendField("id", snapshot.id.value)
        appendField("subject", encodeSubject(snapshot.subject))

        appendField("sources", snapshot.sources.sortedBy { it.producerId }.joinToString("\n") {
            encodeManifest(it)
        })

        appendField("items", snapshot.items.sortedBy { it.id.value }.joinToString("\n") {
            encodeItem(it)
        })

        appendField("gaps", snapshot.gaps.sortedWith(compareBy({ it.capability }, { it.reason.toString() }))
            .joinToString("\n") { encodeGap(it) })

        appendField(
            "correlations",
            snapshot.correlations
                .sortedWith(compareBy({ it.from.namespace.name }, { it.from.value }, { it.to.value }))
                .joinToString("\n") { encodeCorrelation(it) },
        )
    }

    /** Digest canónico del snapshot. Base del determinismo de M0. */
    fun digestSnapshot(snapshot: EvidenceSnapshot): Digest =
        Digest.ofUtf8(encodeSnapshot(snapshot))

    /** Serializa una suite IR a su forma canónica. */
    fun encodeSuite(suite: AssuranceSuiteIR): String = buildString {
        appendField("schema", SUITE_SCHEMA)
        appendField("apiVersion", suite.apiVersion)
        appendField("suiteId", suite.suiteId.value)
        appendField("suiteVersion", suite.suiteVersion)
        // Orden canónico: AAT-16. Un Map sin ordenar rompe la paridad de digest
        // entre runners, que es el Exit de M9.
        appendField("requiredEvidence", suite.requiredEvidence.distinct().sorted().joinToString(","))
        appendField(
            "lenses",
            suite.lenses.sortedBy { it.lensId.value }.joinToString("\n") { lens ->
                buildString {
                    appendField("lensId", lens.lensId.value)
                    appendField("kind", lens.kind)
                    appendField("inputCapabilities", lens.inputCapabilities.distinct().sorted().joinToString(","))
                    appendField("arguments", lens.arguments.toSortedMap().entries.joinToString("\n") { e -> "${e.key}=${e.value}" })
                    appendField("outputSchema", lens.outputSchema)
                }
            },
        )
        appendField(
            "assertions",
            suite.assertions.sortedBy { it.id.value }.joinToString("\n") { a ->
                buildString {
                    appendField("id", a.id.value)
                    appendField("lensRef", a.lensRef.value)
                    appendField("operator", a.operator)
                    appendField("severity", a.severity.name)
                    appendField("enforcement", a.enforcement.name)
                    appendField("completenessRequirements", a.completenessRequirements.distinct().sorted().joinToString(","))
                    // AAT-19: las autoridades admitidas son parte del digest.
                    appendField("admittedAuthorities", a.admittedAuthorities.sorted().joinToString(","))
                    appendField("operands", a.operands.toSortedMap().entries.joinToString("\n") { e -> "${e.key}=${e.value}" })
                    appendField("rationale", a.rationale)
                }
            },
        )
        appendField("metadata", suite.metadata.toSortedMap().entries.joinToString("\n") { e -> "${e.key}=${e.value}" })
    }

    fun digestSuite(suite: AssuranceSuiteIR): Digest = Digest.ofUtf8(encodeSuite(suite))

    // -----------------------------------------------------------------------
    // Formas canónicas de cada tipo
    // -----------------------------------------------------------------------

    private fun encodeManifest(m: EvidenceSourceManifest): String = buildString {
        appendField("producerId", m.producerId)
        appendField("producerVersion", m.producerVersion)
        appendField("subjectRevision", m.subjectRevision.value)
        appendField("requestedCapabilities", m.requestedCapabilities.distinct().sorted().joinToString(","))
        appendField("producedCapabilities", m.producedCapabilities.distinct().sorted().joinToString(","))
        // M-E01: la completitud por capability entra al digest, ordenada. Sin
        // esto un `Partial` y un `Complete` darían el mismo digest.
        appendField(
            "completenessByCapability",
            m.completenessByCapability.toSortedMap().entries.joinToString("\n") { e ->
                "${e.key}=${encodeCompleteness(e.value)}"
            },
        )
        appendField("schemaVersion", m.schemaVersion)
        appendField("digest", m.digest.hex)
    }

    private fun encodeItem(item: EvidenceItem): String = buildString {
        appendField("kind", item::class.simpleName!!)
        appendField("id", item.id.value)
        appendField("subject", encodeSubject(item.subject))
        appendField("authority", item.authority.name)
        appendField("producer", item.provenance.producerId)
        appendField("producerVersion", item.provenance.producerVersion)
        appendField("revision", item.provenance.subjectRevision.value)
        appendField("capability", item.provenance.capability)
        appendField("artifactDigest", item.provenance.artifactDigest?.hex ?: "-")

        when (item) {
            is EvidenceItem.Fact -> {
                appendField("predicate", item.predicate)
                appendField("object", item.objectValue ?: "-")
                // M-E01 ataca aquí.
                appendField("completeness", encodeCompleteness(item.completeness))
            }
            is EvidenceItem.Observation -> {
                appendField("observation", item.observation)
                appendField("completeness", encodeCompleteness(item.completeness))
            }
            is EvidenceItem.Signal -> {
                appendField("signalKind", item.signalKind)
                appendField("score", item.score)
                appendField("algorithm", "${item.algorithmId}@${item.algorithmVersion}")
                // Orden canónico de map: M-R01.
                appendField("thresholds", item.thresholds.toSortedMap().entries.joinToString("\n") { e -> "${e.key}=${e.value}" })
                appendField("completeness", encodeCompleteness(item.completeness))
            }
            is EvidenceItem.Hypothesis -> {
                appendField("claim", item.claim)
                appendField("reasoning", item.reasoning)
                appendField("confidence", item.confidence)
            }
        }
    }

    private fun encodeCompleteness(c: Completeness): String = when (c) {
        is Completeness.Complete -> "complete"
        is Completeness.Unknown -> "unknown"
        is Completeness.Unsupported -> "unsupported:${c.reason}"
        is Completeness.Partial -> buildString {
            append("partial")
            c.gaps.sortedWith(compareBy({ it.capability }, { it.reason.toString() })).forEach {
                append("|").append(it.capability).append(":").append(it.reason.toString())
            }
        }
    }

    private fun encodeSubject(s: EvidenceSubject): String = when (s) {
        is EvidenceSubject.Module -> "module:${s.path}"
        is EvidenceSubject.Symbol -> "symbol:${s.qualifiedName}"
        is EvidenceSubject.SourceLocation -> "loc:${s.file}:${s.line}:${s.column ?: -1}"
        is EvidenceSubject.Test -> "test:${s.id}"
        is EvidenceSubject.RuntimeSpan -> "span:${s.typedRef}"
    }

    private fun encodeGap(g: EvidenceGap): String = buildString {
        appendField("capability", g.capability)
        appendField("reason", g.reason.toString())
        appendField("detail", g.detail ?: "-")
    }

    private fun encodeCorrelation(c: Correlation): String = buildString {
        appendField("from", "${c.from.namespace.name}:${c.from.value}")
        appendField("relation", c.relation.name)
        appendField("to", "${c.to.namespace.name}:${c.to.value}")
        appendField("evidence", c.evidence.value)
    }

    /**
     * Emite un campo con prefijo de longitud.
     *
     * Sin la longitud, `a="bc" b="a"` y `a="b" b="ca"` producirían el mismo
     * texto. El separador de longitud elimina esa clase de colisión.
     */
    private fun StringBuilder.appendField(name: String, value: String) {
        val v = value.replace("\n", "\\n")
        append(name).append(':').append(v.length).append(':').append(v).append('\n')
    }
}

