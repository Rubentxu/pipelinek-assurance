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
import dev.pipelinek.assurance.engine.ArtifactRef
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.UnsupportedReason

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
    private const val REPORT_SCHEMA = "assurance-report/v1"

    /**
     * Serializa un snapshot a su forma canónica.
     *
     * `items` se ordena por `EvidenceId` antes de emitir; `sources` por
     * `producerId`; `gaps` por `capability`; `correlations` por el par de ids.
     * La permutación de entrada no altera el resultado (UAT-001).
     *
     * Los items se ordenan por un criterio TOTAL, no sólo por `id`. Encontrado
     * por property testing, no por los tests de ejemplo: `EvidenceSnapshot`
     * NO exige ids únicos, y con dos items que comparten id, `sortedBy` es
     * estable, así que su orden relativo lo decidía el orden de entrada. Dos
     * runs con el mismo contenido y distinto orden de items producían
     * digests distintos, que es exactamente lo que el digest canónico
     * prohíbe. Ordenar por (id, contenido) rompe el empate de forma
     * determinista.
     */
    fun encodeSnapshot(snapshot: EvidenceSnapshot): String = buildString {
        appendField("schema", SNAPSHOT_SCHEMA)
        appendField("id", snapshot.id.value)
        appendField("subject", encodeSubject(snapshot.subject))

        appendField(
            "sources",
            canonicalSources(snapshot.sources).joinToString("\n") { encodeManifest(it) },
        )

        appendField(
            "items",
            // El desempate por contenido completo: necesario cuando dos items
            // comparten `EvidenceId`, porque `sortedWith` es estable.
            canonicalItems(snapshot.items).joinToString("\n") { encodeItem(it) },
        )

        appendField("gaps", canonicalGaps(snapshot.gaps).joinToString("\n") { encodeGap(it) })

        appendField(
            "correlations",
            canonicalCorrelations(snapshot.correlations).joinToString("\n") { encodeCorrelation(it) },
        )
    }

    /**
     * Orden canónico de items. Clave `EvidenceId`, desempate por contenido.
     *
     * PÚBLICO a propósito: el CBOR y el JSON tienen que ordenar con EXACTAMENTE
     * el mismo criterio que el digest, o el artefacto y el digest contarian
     * historias distintas. Duplicar el criterio en el codec sería pedir que
     * diverjan.
     */
    fun canonicalItems(items: List<EvidenceItem>): List<EvidenceItem> =
        items.sortedWith(totalOrder({ it.id.value }) { encodeItem(it) })

    /** Orden canónico de manifests. Ver [canonicalItems]. */
    fun canonicalSources(sources: List<EvidenceSourceManifest>): List<EvidenceSourceManifest> =
        sources.sortedWith(totalOrder({ it.producerId }) { encodeManifest(it) })

    /**
     * Orden canónico de resultados de report. Ver [canonicalItems].
     *
     * Mismo criterio que `digestReport` usa para el campo `results`: por
     * nombre de clase y luego por clave del resultado, con desempate por la
     * representacion canonica. Sin este desempate, dos `Inconclusive` con los
     * mismos gaps en distinto orden darian artefactos distintos con el mismo
     * contenido.
     */
    fun canonicalResults(results: List<AssertionResult>): List<AssertionResult> =
        results.sortedWith(
            totalOrder<AssertionResult>({ it::class.simpleName ?: "" }) { encodeResult(it) },
        )

    /** Orden canónico de referencias a artefactos. Ver [canonicalItems]. */
    fun canonicalArtifacts(artifacts: List<ArtifactRef>): List<ArtifactRef> =
        artifacts.sortedWith(totalOrder({ it.logicalRole }) { encodeArtifactRef(it) })

    /** Orden canónico de gaps. Ver [canonicalItems]. */
    fun canonicalGaps(gaps: List<EvidenceGap>): List<EvidenceGap> =
        gaps.sortedWith(totalOrder({ it.capability }) { encodeGap(it) })

    /** Orden canónico de correlaciones. Ver [canonicalItems]. */
    fun canonicalCorrelations(links: List<Correlation>): List<Correlation> =
        links.sortedWith(
            totalOrder({ "${it.from.namespace.name}:${it.from.value}" }) { encodeCorrelation(it) },
        )

    /**
     * Orden total por clave, con desempate por representación canónica.
     *
     * Sin el desempate, dos elementos con la misma clave conservan su orden de
     * entrada (`sortedWith` es estable), y el digest pasa a depender del orden
     * en que el runtime recogió la evidencia.
     */
    private fun <T> totalOrder(key: (T) -> String, tie: (T) -> String = { "" }): Comparator<T> =
        compareBy(key).thenBy(tie)

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

    /**
     * Forma canónica de una suite: la misma que [encodeSuite] serializa.
     *
     * Existe porque `decode(encode(x))` NO devuelve `x` cuando `x` llega con sus
     * colecciones desordenadas: devuelve la forma canónica de `x`. Sin esta
     * función, la ley de roundtrip tiene dos salidas válidas, escribir
     * `decode(encode(x)) == x` (falso) o escribir `== canonical(x)` (verdadero
     * pero con el criterio duplicado en el test, donde puede divergir del
     * codificador sin que nadie lo note). Exponer el criterio una vez y
     * reutilizarlo deja el test sin espacio de interpretación.
     *
     * `requiredEvidence`, `inputCapabilities` y `completenessRequirements`
     * también pierden duplicados: el digest los cuenta una vez, así que un
     * artefacto con `"a", "a"` y otro con `"a"` son el mismo informe.
     */
    fun canonicalizeSuite(suite: AssuranceSuiteIR): AssuranceSuiteIR = suite.copy(
        requiredEvidence = suite.requiredEvidence.distinct().sorted(),
        lenses = suite.lenses
            .sortedBy { it.lensId.value }
            .map { lens ->
                lens.copy(
                    inputCapabilities = lens.inputCapabilities.distinct().sorted(),
                    arguments = lens.arguments.toSortedMap(),
                )
            },
        assertions = suite.assertions
            .sortedBy { it.id.value }
            .map { a ->
                a.copy(
                    operands = a.operands.toSortedMap(),
                    completenessRequirements = a.completenessRequirements.distinct().sorted(),
                    admittedAuthorities = a.admittedAuthorities.toSortedSet(),
                )
            },
        metadata = suite.metadata.toSortedMap(),
    )

    /**
     * Forma canónica de un report. Ver [canonicalizeSuite] para por qué esto
     * existe y por qué no es un detalle interno del codec.
     *
     * El orden es el de `canonicalResults`, `canonicalGaps`,
     * `canonicalArtifacts` y `canonicalCorrelations`, los mismos que usa
     * [digestReport]. Si divergieran, dos informes con el mismo digest
     * codificarían a bytes distintos, que es el peor caso posible: el digest no
     * lo delata.
     */
    fun canonicalizeReport(report: AssuranceReport): AssuranceReport = report.copy(
        results = canonicalResults(report.results),
        gaps = canonicalGaps(report.gaps),
        artifacts = canonicalArtifacts(report.artifacts),
        correlations = canonicalCorrelations(report.correlations),
    )

    /**
     * Digest canónico de un report.
     *
     * No incluye el `evaluationId` ni los digests de entrada: son funciones del
     * resto de campos, y meterlos duplicaria la informacion sin anadir
     * verificabilidad. Tampoco incluye el instante de evaluacion, porque no
     * existe en el tipo.
     *
     * Si dos runners evaluan la misma evidencia con la misma suite y el mismo
     * engine, este digest es identico. Eso es lo que hace que un report sea
     * comparable byte a byte entre maquinas.
     */
    fun digestPlan(plan: dev.pipelinek.assurance.engine.RequiredAssurancePlan.Plan): Digest = Digest.ofUtf8(
        buildString {
            appendField("schema", "assurance-plan/v1")
            appendField("engineVersion", plan.engineVersion)
            appendField(
                "selections",
                plan.selections.joinToString("\n") { sel ->
                    val reason = when (val r = sel.reason) {
                        dev.pipelinek.assurance.engine.RequiredAssurancePlan.Reason.MandatoryBaseline ->
                            "mandatory"
                        dev.pipelinek.assurance.engine.RequiredAssurancePlan.Reason.NewFindingsPresent ->
                            "new-findings"
                        is dev.pipelinek.assurance.engine.RequiredAssurancePlan.Reason.TouchedByChange ->
                            "touched:${r.path}"
                    }
                    "${sel.suiteId.value}=$reason"
                },
            )
        },
    )

    fun digestReport(report: AssuranceReport): Digest = Digest.ofUtf8(
        buildString {
            appendField("schema", REPORT_SCHEMA)
            appendField("engineVersion", report.engineVersion)
            appendField("snapshotDigest", report.snapshotDigest.hex)
            appendField("suiteDigest", report.suiteDigest.hex)
            appendField(
                "results",
                canonicalResults(report.results).joinToString("\n") { encodeResult(it) },
            )
            appendField(
                "gaps",
                canonicalGaps(report.gaps).joinToString("\n") { g ->
                    buildString {
                        appendField("capability", g.capability)
                        appendField("reason", g.reason.toString())
                        appendField("detail", g.detail ?: "-")
                    }
                },
            )
            appendField(
                "artifacts",
                canonicalArtifacts(report.artifacts).joinToString("\n") { a ->
                    buildString {
                        appendField("logicalRole", a.logicalRole)
                        appendField("mediaType", a.mediaType)
                        appendField("digest", a.digest.hex)
                    }
                },
            )
            appendField(
                "correlations",
                canonicalCorrelations(report.correlations)
                    .joinToString("\n") { encodeCorrelation(it) },
            )
        },
    )

    /**
     * Clave estable para ordenar resultados.
     *
     * Ordenar por tipo+clave hace que el digest no dependa del orden en que el
     * runtime evaluó, que es una decisión de implementación y no del dominio.
     *
     * La clave DEBE ser única por resultado, no sólo por tipo. Si dos
     * `Unsupported` comparten clave, el orden relativo entre ellos lo decide
     * el `sort` estable, es decir, el orden de entrada, y dos runs con el
     * mismo contenido darían digests distintos. Por eso `Unsupported` usa su
     * razón codificada, no la cadena vacía.
     */
    private fun resultKey(r: AssertionResult): String = when (r) {
        is AssertionResult.Passed -> "passed:${r.proof.assertionId.value}"
        is AssertionResult.Failed -> "failed:${r.counterexample.assertionId.value}"
        is AssertionResult.Inconclusive -> r.gaps
            .sortedWith(compareBy({ it.capability }, { it.reason.toString() }))
            .joinToString(separator = "|") { "${it.capability}:${it.reason}" }
            .ifEmpty { "inconclusive" }
        is AssertionResult.Unsupported -> "unsupported:${encodeUnsupportedReason(r.reason)}"
        is AssertionResult.Error -> "error:${r.failure.phase}:${r.failure.detail}"
    }

    private fun encodeResult(r: AssertionResult): String = buildString {
        appendField("kind", r::class.simpleName ?: "Unknown")
        when (r) {
            is AssertionResult.Passed -> {
                appendField("assertionId", r.proof.assertionId.value)
                appendField("snapshotId", r.proof.snapshotId)
                appendField(
                    "evidenceIds",
                    r.proof.evidenceIds.map { it.value }.distinct().sorted().joinToString(","),
                )
            }
            is AssertionResult.Failed -> {
                appendField("assertionId", r.counterexample.assertionId.value)
                appendField("explanation", r.counterexample.explanation)
                appendField(
                    "evidenceRefs",
                    r.counterexample.evidenceRefs.map { it.value }.distinct().sorted().joinToString(","),
                )
                appendField(
                    "subjectRefs",
                    r.counterexample.subjectRefs
                        .sortedWith(compareBy({ it.namespace.name }, { it.value }))
                        .joinToString(",") { "${it.namespace.name}:${it.value}" },
                )
                // Los hints son un SET ordenado, no una lista: el motor puede
                // emitirlos duplicados sin que eso signifique nada, y duplicados
                // no deben cambiar el digest.
                appendField(
                    "hints",
                    r.counterexample.reproductionHints.distinct().sorted().joinToString("|"),
                )
                // Los campos propios de cada variante SÍ entran al digest.
                //
                // Esto no era opcional: un `DependencyPath` con `path=[a,b]` y
                // otro con `path=[x,y]` producían el mismo digest, porque sólo
                // se codificaban los campos comunes de la interfaz. Dos
                // contraejemplos con distinto significado eran indistinguibles
                // en el artefacto firmado.
                appendField("counterexampleKind", r.counterexample::class.simpleName ?: "Unknown")
                encodeCounterexampleFields(r.counterexample)
            }
            is AssertionResult.Inconclusive -> {
                appendField(
                    "gaps",
                    r.gaps.sortedWith(compareBy({ it.capability }, { it.reason.toString() }))
                        .joinToString("\n") { g -> "${g.capability}:${g.reason}" },
                )
            }
            is AssertionResult.Unsupported -> appendField("reason", encodeUnsupportedReason(r.reason))
            is AssertionResult.Error -> {
                appendField("phase", r.failure.phase)
                appendField("detail", r.failure.detail)
                appendField("cause", r.failure.cause ?: "-")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Formas canónicas de cada tipo
    // -----------------------------------------------------------------------

    /**
     * Codifica los campos PROPIOS de cada variante de contraejemplo.
     *
     * Los campos comunes (assertionId, explanation, refs, hints) los emite
     * [encodeResult]. Aquí van los que existen sólo en una variante.
     *
     * El `when` es exhaustivo a propósito: cuando el dominio añada un
     * contraejemplo nuevo, esto deja de compilar. Con un `else` compilaría y
     * ese contraejemplo entraría al digest sin sus campos, que es peor que no
     * entrar.
     */
    private fun StringBuilder.encodeCounterexampleFields(c: Counterexample) {
        when (c) {
            is Counterexample.DependencyPath -> {
                appendField("path", c.path.joinToString(">"))
                appendField("fromLayer", c.fromLayer)
                appendField("toLayer", c.toLayer)
            }
            is Counterexample.Cycle -> appendField("cycle", c.cycle.joinToString(">"))
            is Counterexample.CausalSlice -> appendField("invocationChain", c.invocationChain.joinToString(">"))
            is Counterexample.Mutation -> {
                appendField("mutatedSymbol", c.mutatedSymbol)
                appendField("killedBy", c.killedBy ?: "-")
            }
            is Counterexample.MissingTrace -> {
                appendField("missingSpanFor", c.missingSpanFor)
                appendField("expectedPropagation", c.expectedPropagation)
            }
            is Counterexample.BaselineRegression -> {
                appendField("stableId", c.stableId)
                appendField("state", c.state.name)
            }
        }
    }

    /**
     * Codifica una razón de `Unsupported`.
     *
     * El `toString()` por defecto de un `data class` de Kotlin sí distingue
     * variantes, pero su formato no es un contrato: cambia entre versiones de
     * Kotlin sin que nadie lo note, y el digest cambiaría con el compilador.
     * Codificar a mano lo hace estable.
     */
    private fun encodeUnsupportedReason(r: UnsupportedReason): String = when (r) {
        is UnsupportedReason.UnknownLensKind -> "unknown-lens-kind:${r.kind}"
        is UnsupportedReason.UnknownOperator -> "unknown-operator:${r.operator}"
        is UnsupportedReason.UnknownEvidenceKind -> "unknown-evidence-kind:${r.kind}"
        is UnsupportedReason.AuthorityNotAdmitted -> "authority-not-admitted:${r.required}/${r.offered}"
    }

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

    private fun encodeArtifactRef(a: ArtifactRef): String = buildString {
        appendField("logicalRole", a.logicalRole)
        appendField("mediaType", a.mediaType)
        appendField("digest", a.digest.hex)
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

