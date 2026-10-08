package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Correlation
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.ArtifactRef
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionIR
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.DiffState
import dev.pipelinek.assurance.engine.Enforcement
import dev.pipelinek.assurance.engine.EvaluationFailure
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.Severity
import dev.pipelinek.assurance.engine.SuiteId
import dev.pipelinek.assurance.engine.UnsupportedReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * M0 — Codecs de las familias 2 y 3: suite y report.
 *
 * Ref: `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` §Family 2 y §Family 3.
 *
 * Por qué un codec aparte y no más métodos en `EvidenceArtifactCodec`: cada
 * familia es un media type distinto, con su `apiVersion` y su `kind`. Meterlas
 * en el mismo objeto habría que cerrar cada `when` sobre un discriminante que
 * nunca comparte valores, y el fallo de "se me olvidó una rama" se vuelve
 * invisible. Con un objeto por familia, el tipo del artefacto ya dice cuál es.
 *
 * La misma regla de `EvidenceArtifactCodec` se repite aquí a propósito:
 *
 * 1. **Cierre por `when` exhaustivo, sin `else`.** Cuando el dominio añada una
 *    variante de `Counterexample` o de `AssertionResult`, esto deja de
 *    compilar en vez de perder campos en silencio.
 * 2. **Mismas cotas de bounded decoding.** Una entrada no confiable es una
 *    entrada no confiable, venga de la familia que venga.
 * 3. **Sin claves ordenadas ni tiempos.** El JSON va en el orden de
 *    declaración del DTO, que es estable por construcción; el CBOR tampoco
 *    depende del orden de iteración de un `HashMap` porque los mapas viajan
 *    ordenados por `toSortedMap()`.
 */
object SuiteArtifactCodec {

    const val MEDIA_TYPE = "application/vnd.pipelinek.assurance.suite+cbor;version=1"
    const val API_VERSION = "assurance-suite/v1"

    fun encodeToCbor(suite: AssuranceSuiteIR): ByteArray {
        val dto = SuiteDto.of(suite)
        requireWithinLimits(dto)
        return Cbor.encodeToByteArray(SuiteDto.serializer(), dto)
    }

    fun decodeFromCbor(bytes: ByteArray): AssuranceSuiteIR {
        if (bytes.size > EvidenceArtifactCodec.MAX_INPUT_BYTES) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "suite de ${bytes.size} bytes excede ${EvidenceArtifactCodec.MAX_INPUT_BYTES}",
            )
        }
        val dto = try {
            Cbor.decodeFromByteArray(SuiteDto.serializer(), bytes)
        } catch (e: Exception) {
            throw EvidenceArtifactCodec.ArtifactDecodeException("suite CBOR no decodificable: ${e.message}", e)
        }
        return dto.toVerifiedDomain()
    }

    /**
     * Decodifica a dominio y comprueba el digest declarado.
     *
     * Mismo contrato que `EvidenceArtifactCodec`: el envelope declara su
     * digest y el decoder lo comprueba. Sin esta comprobación, un envelope de
     * suite con las aserciones cambiadas sigue decodificando bien y nadie lo
     * nota, que es justo el fallo que un artefacto con digest evita.
     */
    private fun SuiteDto.toVerifiedDomain(): AssuranceSuiteIR {
        requireWithinLimits(this)
        val suite = toDomain()
        val canonical = CanonicalEncoder.digestSuite(suite).hex
        if (digest != canonical) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "digest de suite declarado ${digest.take(12)} no coincide con el canónico " +
                    "${canonical.take(12)} (payload alterado en tránsito)",
            )
        }
        return suite
    }

    internal fun requireWithinLimits(dto: SuiteDto) {
        require(dto.lenses.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "lenses: ${dto.lenses.size} excede el limite"
        }
        require(dto.assertions.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "assertions: ${dto.assertions.size} excede el limite"
        }
        dto.apiVersion.requireWithinBound("Suite.apiVersion")
        dto.suiteApiVersion.requireWithinBound("Suite.suiteApiVersion")
        dto.suiteId.requireWithinBound("Suite.suiteId")
        dto.suiteVersion.requireWithinBound("Suite.suiteVersion")
        dto.digest.requireWithinBound("Suite.digest")
        dto.metadata.keys.forEach { it.requireWithinBound("Suite.metadata key") }
        dto.metadata.values.forEach { it.requireWithinBound("Suite.metadata value") }
        dto.requiredEvidence.forEach { it.requireWithinBound("Suite.requiredEvidence") }
        for (l in dto.lenses) {
            l.lensId.requireWithinBound("Lens.lensId")
            l.kind.requireWithinBound("Lens.kind")
            l.outputSchema.requireWithinBound("Lens.outputSchema")
            l.inputCapabilities.forEach { it.requireWithinBound("Lens.inputCapabilities") }
            l.arguments.keys.forEach { it.requireWithinBound("Lens.arguments key") }
            l.arguments.values.forEach { it.requireWithinBound("Lens.arguments value") }
        }
        for (a in dto.assertions) {
            a.id.requireWithinBound("Assertion.id")
            a.lensRef.requireWithinBound("Assertion.lensRef")
            a.operator.requireWithinBound("Assertion.operator")
            a.rationale.requireWithinBound("Assertion.rationale")
            a.operands.keys.forEach { it.requireWithinBound("Assertion.operands key") }
            a.operands.values.forEach { it.requireWithinBound("Assertion.operands value") }
            a.completenessRequirements.forEach { it.requireWithinBound("Assertion.completenessRequirements") }
            a.admittedAuthorities.forEach { it.requireWithinBound("Assertion.admittedAuthorities") }
        }
    }
}

object ReportArtifactCodec {

    const val MEDIA_TYPE = "application/vnd.pipelinek.assurance.report+cbor;version=1"
    const val API_VERSION = "assurance-report/v1"

    fun encodeToCbor(report: AssuranceReport): ByteArray {
        val dto = ReportDto.of(report)
        requireWithinLimits(dto)
        return Cbor.encodeToByteArray(ReportDto.serializer(), dto)
    }

    fun decodeFromCbor(bytes: ByteArray): AssuranceReport {
        if (bytes.size > EvidenceArtifactCodec.MAX_INPUT_BYTES) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "report de ${bytes.size} bytes excede ${EvidenceArtifactCodec.MAX_INPUT_BYTES}",
            )
        }
        val dto = try {
            Cbor.decodeFromByteArray(ReportDto.serializer(), bytes)
        } catch (e: Exception) {
            throw EvidenceArtifactCodec.ArtifactDecodeException("report CBOR no decodificable: ${e.message}", e)
        }
        return dto.toVerifiedDomain()
    }

    /**
     * Decodifica a dominio y comprueba el digest declarado.
     *
     * El orden es el mismo que en evidence y suite: cotas e invariantes de
     * dominio primero, digest después. Un report con una cota violada debe
     * decir qué cota, no "el digest no cuadra".
     */
    private fun ReportDto.toVerifiedDomain(): AssuranceReport {
        requireWithinLimits(this)
        val report = toDomain()
        val canonical = CanonicalEncoder.digestReport(report).hex
        if (digest != canonical) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "digest de report declarado ${digest.take(12)} no coincide con el canónico " +
                    "${canonical.take(12)} (payload alterado en tránsito)",
            )
        }
        return report
    }

    internal fun requireWithinLimits(dto: ReportDto) {
        require(dto.results.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "results: ${dto.results.size} excede el limite"
        }
        require(dto.gaps.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "gaps: ${dto.gaps.size} excede el limite"
        }
        require(dto.artifacts.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "artifacts: ${dto.artifacts.size} excede el limite"
        }
        require(dto.correlations.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "correlations: ${dto.correlations.size} excede el limite"
        }
        dto.evaluationId.requireWithinBound("Report.evaluationId")
        dto.snapshotDigest.requireWithinBound("Report.snapshotDigest")
        dto.suiteDigest.requireWithinBound("Report.suiteDigest")
        dto.engineVersion.requireWithinBound("Report.engineVersion")
        dto.digest.requireWithinBound("Report.digest")
        dto.gaps.forEach { it.requireWithinLimits() }
        dto.artifacts.forEach {
            it.digestHex.requireWithinBound("ArtifactRef.digest")
            it.mediaType.requireWithinBound("ArtifactRef.mediaType")
            it.logicalRole.requireWithinBound("ArtifactRef.logicalRole")
        }
    }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
internal data class SuiteDto(
    val apiVersion: String,
    val kind: String,
    /**
     * `apiVersion` de la IR, que NO es el del envelope.
     *
     * Son dos campos distintos y esta es la razón de que el DTO los tenga
     * separados. Una versión anterior de este codec guardaba la IR completa en
     * el campo `apiVersion` del envelope y validaba que fuera
     * `assurance-suite/v1`. Eso sólo podía funcionar con las suites que
     * happenaran a declarar esa cadena en su IR, y `AssuranceSuiteIR` no
     * exige ningún valor: es un campo libre. El resultado era que una suite
     * legítima con `apiVersion = "assurance/v1"` no se podía ni codificar.
     *
     * El envelope declara la versión del WIRE; la IR declara la suya. Al
     * revés se pierde una de las dos, y la que se pierde es siempre la del
     * contenido.
     */
    val suiteApiVersion: String,
    val suiteId: String,
    val suiteVersion: String,
    val requiredEvidence: List<String>,
    val lenses: List<LensDto>,
    val assertions: List<AssertionDto>,
    val metadata: Map<String, String> = emptyMap(),
    /**
     * Digest canónico de la suite. OBLIGATORIO.
     *
     * Igual que en `EvidenceArtifactCodec`: el contrato lista `digest` en el
     * envelope sin marcarlo opcional, y M0 es el primer codec de suite, así
     * que no hay artefactos antiguos a los que haya que seguir leyendo.
     */
    val digest: String,
) {
    init {
        require(apiVersion == SuiteArtifactCodec.API_VERSION) { "apiVersion desconocida: $apiVersion" }
        require(kind == "AssuranceSuite") { "kind inesperado: $kind" }
    }

    fun toDomain(): AssuranceSuiteIR = AssuranceSuiteIR(
        apiVersion = suiteApiVersion,
        suiteId = SuiteId(suiteId),
        suiteVersion = suiteVersion,
        requiredEvidence = requiredEvidence,
        lenses = lenses.map { it.toDomain() },
        assertions = assertions.map { it.toDomain() },
        metadata = metadata,
    )

    companion object {
        fun of(suite: AssuranceSuiteIR): SuiteDto {
            // La canonicalización la hace UNA sola función, la del
            // `CanonicalEncoder`, no una copia local con los mismos
            // `sortedBy`. Estaba duplicada aquí y en
            // `canonicalizeSuite`, y sólo la de aquí ejecutaba en
            // producción: la otra era código que sólo usaban los tests.
            //
            // Dos copias del criterio son una forma elegante de perder la
            // paridad de digest, porque las dos compilan, las dos parecen
            // correctas y sólo una manda. Es el mismo modo de fallo que
            // `correlations`, donde el digest ordenaba una cosa y el codec
            // otra: dos autoridades que ordenan y no se hablan.
            val canonica = CanonicalEncoder.canonicalizeSuite(suite)
            return SuiteDto(
                apiVersion = SuiteArtifactCodec.API_VERSION,
                kind = "AssuranceSuite",
                suiteApiVersion = canonica.apiVersion,
                suiteId = canonica.suiteId.value,
                suiteVersion = canonica.suiteVersion,
                // Orden canónico (AAT-16): una lista sin ordenar rompe la
                // paridad de digest entre runners, que es el Exit de M9.
                requiredEvidence = canonica.requiredEvidence,
                lenses = canonica.lenses.map { LensDto.of(it) },
                assertions = canonica.assertions.map { AssertionDto.of(it) },
                metadata = canonica.metadata,
                digest = CanonicalEncoder.digestSuite(suite).hex,
            )
        }
    }
}

@Serializable
internal data class LensDto(
    val lensId: String,
    val kind: String,
    val inputCapabilities: List<String>,
    val arguments: Map<String, String> = emptyMap(),
    val outputSchema: String,
) {
    fun toDomain() = LensPlan(
        lensId = LensId(lensId),
        kind = kind,
        inputCapabilities = inputCapabilities,
        arguments = arguments,
        outputSchema = outputSchema,
    )

    companion object {
        fun of(lens: LensPlan) = LensDto(
            lensId = lens.lensId.value,
            kind = lens.kind,
            inputCapabilities = lens.inputCapabilities.distinct().sorted(),
            arguments = lens.arguments.toSortedMap(),
            outputSchema = lens.outputSchema,
        )
    }
}

@Serializable
internal data class AssertionDto(
    val id: String,
    val lensRef: String,
    val operator: String,
    val operands: Map<String, String>,
    val severity: String,
    val enforcement: String,
    val completenessRequirements: List<String>,
    val rationale: String,
    val admittedAuthorities: List<String>,
) {
    fun toDomain() = AssertionIR(
        id = AssertionId(id),
        lensRef = LensId(lensRef),
        operator = operator,
        operands = operands,
        severity = Severity.valueOf(severity),
        enforcement = Enforcement.valueOf(enforcement),
        completenessRequirements = completenessRequirements,
        rationale = rationale,
        admittedAuthorities = admittedAuthorities.toSet(),
    )

    companion object {
        fun of(a: AssertionIR) = AssertionDto(
            id = a.id.value,
            lensRef = a.lensRef.value,
            operator = a.operator,
            operands = a.operands.toSortedMap(),
            severity = a.severity.name,
            enforcement = a.enforcement.name,
            completenessRequirements = a.completenessRequirements.distinct().sorted(),
            rationale = a.rationale,
            // AAT-19: las autoridades admitidas son parte del digest.
            admittedAuthorities = a.admittedAuthorities.sorted(),
        )
    }
}

@Serializable
internal data class ReportDto(
    val apiVersion: String,
    val kind: String,
    val evaluationId: String,
    val snapshotDigest: String,
    val suiteDigest: String,
    val engineVersion: String,
    val results: List<ResultDto>,
    val gaps: List<GapDto>,
    val artifacts: List<ArtifactRefDto> = emptyList(),
    val correlations: List<CorrelationDto> = emptyList(),
    /**
     * Digest canónico del report. OBLIGATORIO, por las mismas razones que en
     * `EvidenceSnapshotDto` y `SuiteDto`.
     */
    val digest: String,
) {
    init {
        require(apiVersion == ReportArtifactCodec.API_VERSION) { "apiVersion desconocida: $apiVersion" }
        require(kind == "AssuranceReport") { "kind inesperado: $kind" }
    }

    fun toDomain(): AssuranceReport = AssuranceReport(
        evaluationId = AssuranceEvaluationId(evaluationId),
        snapshotDigest = Digest(snapshotDigest),
        suiteDigest = Digest(suiteDigest),
        engineVersion = engineVersion,
        results = results.map { it.toDomain() },
        gaps = gaps.map { it.toDomain() },
        artifacts = artifacts.map { it.toDomain() },
        correlations = correlations.map { it.toDomain() },
    )

    companion object {
        fun of(report: AssuranceReport) = ReportDto(
            apiVersion = ReportArtifactCodec.API_VERSION,
            kind = "AssuranceReport",
            evaluationId = report.evaluationId.value,
            snapshotDigest = report.snapshotDigest.hex,
            suiteDigest = report.suiteDigest.hex,
            engineVersion = report.engineVersion,
            // Orden canónico ANTES de serializar, con el MISMO criterio que usa el
            // digest. Sin esto, dos reports con los mismos resultados en
            // distinto orden darian artefactos distintos con el mismo
            // contenido, y el digest (que sí ordena) no lo detectaría: el
            // digest coincidiría y los bytes no.
            results = CanonicalEncoder.canonicalResults(report.results).map { ResultDto.of(it) },
            gaps = CanonicalEncoder.canonicalGaps(report.gaps).map { GapDto.of(it) },
            artifacts = CanonicalEncoder.canonicalArtifacts(report.artifacts).map { ArtifactRefDto.of(it) },
            // `correlations` estaba sin canonicalizar. No era cosmético: el
            // digest SÍ las ordena (`canonicalCorrelations`), así que dos
            // informes con las mismas correlaciones en distinto orden producían
            // artefactos byte-a-byte DISTINTOS con el MISMO digest. El digest
            // no lo delata, que es el peor caso posible en un artefacto
            // firmado. Lo encontró la ley de forma canónica, no un test de
            // ejemplo: hace falta generar la colección desordenada.
            correlations = CanonicalEncoder.canonicalCorrelations(report.correlations).map {
                CorrelationDto.of(it)
            },
            digest = CanonicalEncoder.digestReport(report).hex,
        )
    }
}

@Serializable
internal data class ArtifactRefDto(
    val digestHex: String,
    val mediaType: String,
    val logicalRole: String,
) {
    fun toDomain() = ArtifactRef(Digest(digestHex), mediaType, logicalRole)

    companion object {
        fun of(a: ArtifactRef) = ArtifactRefDto(a.digest.hex, a.mediaType, a.logicalRole)
    }
}

@Serializable
internal sealed interface ResultDto {
    fun toDomain(): AssertionResult

    @Serializable
    @SerialName("Passed")
    data class PassedDto(
        val snapshotId: String,
        val evidenceIds: List<String>,
        val assertionId: String,
    ) : ResultDto {
        override fun toDomain(): AssertionResult.Passed = AssertionResult.Passed(
            ProofRef(snapshotId, evidenceIds.map { EvidenceId(it) }, AssertionId(assertionId)),
        )

        companion object {
            fun of(r: AssertionResult.Passed) = PassedDto(
                snapshotId = r.proof.snapshotId,
                // Orden canónico: el mismo conjunto de evidencia en distinto
                // orden es el mismo hecho, y no puede cambiar el digest.
                evidenceIds = r.proof.evidenceIds.map { it.value }.distinct().sorted(),
                assertionId = r.proof.assertionId.value,
            )
        }
    }

    @Serializable
    @SerialName("Failed")
    data class FailedDto(val counterexample: CounterexampleDto) : ResultDto {
        override fun toDomain(): AssertionResult.Failed = AssertionResult.Failed(counterexample.toDomain())

        companion object {
            fun of(r: AssertionResult.Failed) = FailedDto(CounterexampleDto.of(r.counterexample))
        }
    }

    @Serializable
    @SerialName("Inconclusive")
    data class InconclusiveDto(val gaps: List<GapDto>) : ResultDto {
        override fun toDomain(): AssertionResult.Inconclusive =
            AssertionResult.Inconclusive(gaps.map { it.toDomain() })

        companion object {
            fun of(r: AssertionResult.Inconclusive) = InconclusiveDto(
                r.gaps.map { GapDto.of(it) },
            )
        }
    }

    @Serializable
    @SerialName("Unsupported")
    data class UnsupportedDto(val reason: UnsupportedReasonDto) : ResultDto {
        override fun toDomain(): AssertionResult.Unsupported =
            AssertionResult.Unsupported(reason.toDomain())

        companion object {
            fun of(r: AssertionResult.Unsupported) = UnsupportedDto(UnsupportedReasonDto.of(r.reason))
        }
    }

    @Serializable
    @SerialName("Error")
    data class ErrorDto(val failure: FailureDto) : ResultDto {
        override fun toDomain(): AssertionResult.Error = AssertionResult.Error(failure.toDomain())

        companion object {
            fun of(r: AssertionResult.Error) = ErrorDto(FailureDto.of(r.failure))
        }
    }

    companion object {
        /**
         * Sin `else`: si el motor añade un `AssertionResult`, esto deja de
         * compilar. Un `else -> error()` compilaría y perdería el resultado en
         * silencio, que es peor que no compilar.
         */
        fun of(r: AssertionResult): ResultDto = when (r) {
            is AssertionResult.Passed -> PassedDto.of(r)
            is AssertionResult.Failed -> FailedDto.of(r)
            is AssertionResult.Inconclusive -> InconclusiveDto.of(r)
            is AssertionResult.Unsupported -> UnsupportedDto.of(r)
            is AssertionResult.Error -> ErrorDto.of(r)
        }
    }
}

@Serializable
internal sealed interface UnsupportedReasonDto {
    fun toDomain(): UnsupportedReason

    @Serializable
    @SerialName("UnknownLensKind")
    data class UnknownLensKindDto(val kind: String) : UnsupportedReasonDto {
        override fun toDomain() = UnsupportedReason.UnknownLensKind(kind)
    }

    @Serializable
    @SerialName("UnknownOperator")
    data class UnknownOperatorDto(val operator: String) : UnsupportedReasonDto {
        override fun toDomain() = UnsupportedReason.UnknownOperator(operator)
    }

    @Serializable
    @SerialName("UnknownEvidenceKind")
    data class UnknownEvidenceKindDto(val kind: String) : UnsupportedReasonDto {
        override fun toDomain() = UnsupportedReason.UnknownEvidenceKind(kind)
    }

    @Serializable
    @SerialName("AuthorityNotAdmitted")
    data class AuthorityNotAdmittedDto(val required: String, val offered: String) : UnsupportedReasonDto {
        override fun toDomain() = UnsupportedReason.AuthorityNotAdmitted(required, offered)
    }

    companion object {
        fun of(r: UnsupportedReason): UnsupportedReasonDto = when (r) {
            is UnsupportedReason.UnknownLensKind -> UnknownLensKindDto(r.kind)
            is UnsupportedReason.UnknownOperator -> UnknownOperatorDto(r.operator)
            is UnsupportedReason.UnknownEvidenceKind -> UnknownEvidenceKindDto(r.kind)
            is UnsupportedReason.AuthorityNotAdmitted -> AuthorityNotAdmittedDto(r.required, r.offered)
        }
    }
}

@Serializable
internal data class FailureDto(
    val phase: String,
    val detail: String,
    val cause: String? = null,
) {
    fun toDomain() = EvaluationFailure(phase, detail, cause)

    companion object {
        fun of(f: EvaluationFailure) = FailureDto(f.phase, f.detail, f.cause)
    }
}

@Serializable
internal sealed interface CounterexampleDto {
    fun toDomain(): Counterexample

    companion object {
        /**
         * Los `hints` se tratan como conjunto: son pistas, no una secuencia, y
         * su orden no debe cambiar el digest. Esto se corrigió en el encoder
         * (`digestReport`) y aquí se hace lo mismo, por el mismo motivo.
         */
        fun of(c: Counterexample): CounterexampleDto {
            val base = Base.of(c)
            return when (c) {
                is Counterexample.DependencyPath -> DependencyPathDto(
                    base, c.path, c.fromLayer, c.toLayer,
                )
                is Counterexample.Cycle -> CycleDto(base, c.cycle)
                is Counterexample.CausalSlice -> CausalSliceDto(base, c.invocationChain)
                is Counterexample.Mutation -> MutationDto(base, c.mutatedSymbol, c.killedBy)
                is Counterexample.MissingTrace -> MissingTraceDto(
                    base, c.missingSpanFor, c.expectedPropagation,
                )
                is Counterexample.BaselineRegression -> BaselineRegressionDto(
                    base, c.stableId, c.state.name,
                )
            }
        }
    }

    @Serializable
    data class Base(
        val assertionId: String,
        val subjectRefs: List<String>,
        val evidenceRefs: List<String>,
        val explanation: String,
        val reproductionHints: List<String>,
    ) {
        companion object {
            fun of(c: Counterexample) = Base(
                assertionId = c.assertionId.value,
                subjectRefs = c.subjectRefs.map { "${it.namespace.name}:${it.value}" }.sorted(),
                evidenceRefs = c.evidenceRefs.map { it.value }.distinct().sorted(),
                explanation = c.explanation,
                reproductionHints = c.reproductionHints.distinct().sorted(),
            )
        }
    }

    @Serializable
    @SerialName("DependencyPath")
    data class DependencyPathDto(
        val base: Base,
        val path: List<String>,
        val fromLayer: String,
        val toLayer: String,
    ) : CounterexampleDto {
        override fun toDomain() = Counterexample.DependencyPath(
            assertionId = AssertionId(base.assertionId),
            subjectRefs = base.subjectRefs.map { parseRef(it) },
            evidenceRefs = base.evidenceRefs.map { EvidenceId(it) },
            explanation = base.explanation,
            reproductionHints = base.reproductionHints,
            path = path,
            fromLayer = fromLayer,
            toLayer = toLayer,
        )
    }

    @Serializable
    @SerialName("Cycle")
    data class CycleDto(val base: Base, val cycle: List<String>) : CounterexampleDto {
        override fun toDomain() = Counterexample.Cycle(
            assertionId = AssertionId(base.assertionId),
            subjectRefs = base.subjectRefs.map { parseRef(it) },
            evidenceRefs = base.evidenceRefs.map { EvidenceId(it) },
            explanation = base.explanation,
            reproductionHints = base.reproductionHints,
            cycle = cycle,
        )
    }

    @Serializable
    @SerialName("CausalSlice")
    data class CausalSliceDto(val base: Base, val invocationChain: List<String>) : CounterexampleDto {
        override fun toDomain() = Counterexample.CausalSlice(
            assertionId = AssertionId(base.assertionId),
            subjectRefs = base.subjectRefs.map { parseRef(it) },
            evidenceRefs = base.evidenceRefs.map { EvidenceId(it) },
            explanation = base.explanation,
            reproductionHints = base.reproductionHints,
            invocationChain = invocationChain,
        )
    }

    @Serializable
    @SerialName("Mutation")
    data class MutationDto(
        val base: Base,
        val mutatedSymbol: String,
        val killedBy: String?,
    ) : CounterexampleDto {
        override fun toDomain() = Counterexample.Mutation(
            assertionId = AssertionId(base.assertionId),
            subjectRefs = base.subjectRefs.map { parseRef(it) },
            evidenceRefs = base.evidenceRefs.map { EvidenceId(it) },
            explanation = base.explanation,
            reproductionHints = base.reproductionHints,
            mutatedSymbol = mutatedSymbol,
            killedBy = killedBy,
        )
    }

    @Serializable
    @SerialName("MissingTrace")
    data class MissingTraceDto(
        val base: Base,
        val missingSpanFor: String,
        val expectedPropagation: String,
    ) : CounterexampleDto {
        override fun toDomain() = Counterexample.MissingTrace(
            assertionId = AssertionId(base.assertionId),
            subjectRefs = base.subjectRefs.map { parseRef(it) },
            evidenceRefs = base.evidenceRefs.map { EvidenceId(it) },
            explanation = base.explanation,
            reproductionHints = base.reproductionHints,
            missingSpanFor = missingSpanFor,
            expectedPropagation = expectedPropagation,
        )
    }

    @Serializable
    @SerialName("BaselineRegression")
    data class BaselineRegressionDto(
        val base: Base,
        val stableId: String,
        val state: String,
    ) : CounterexampleDto {
        override fun toDomain() = Counterexample.BaselineRegression(
            assertionId = AssertionId(base.assertionId),
            subjectRefs = base.subjectRefs.map { parseRef(it) },
            evidenceRefs = base.evidenceRefs.map { EvidenceId(it) },
            explanation = base.explanation,
            reproductionHints = base.reproductionHints,
            stableId = stableId,
            state = DiffState.valueOf(state),
        )
    }
}

/**
 * Reconstruye un `TypedExternalId` desde su forma serializada.
 *
 * El separador `:` no puede aparecer en el valor: `ExternalNamespace` es un
 * enum cerrado y el format de `EvidenceId` no admite `:`. Si algún día
 * admitiera, este parser tiene que volverse explícito, no más listo.
 */
private fun parseRef(s: String): TypedExternalId {
    val sep = s.indexOf(':')
    require(sep > 0) { "subjectRef mal formado: $s" }
    return TypedExternalId(
        ExternalNamespace.valueOf(s.substring(0, sep)),
        s.substring(sep + 1),
    )
}

private fun String.requireWithinBound(where: String) {
    require(length <= EvidenceArtifactCodec.MAX_STRING_LENGTH) {
        "$where: cadena de $length caracteres excede ${EvidenceArtifactCodec.MAX_STRING_LENGTH}"
    }
}
