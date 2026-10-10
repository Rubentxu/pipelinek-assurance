package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssurancePack
import dev.pipelinek.assurance.engine.RequiredAssurancePlan
import dev.pipelinek.assurance.engine.SuiteId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * M10 — Codec para `AssurancePack` y `RequiredAssurancePlan.Plan`.
 *
 * Ref: ROADMAP §3 M10, "assurance-dsl (packs versionados)".
 *
 * Por qué un codec aparte: el pack y el plan son media types distintos del
 * `AssuranceSuiteIR` y del `AssuranceReport`. Cada uno tiene su `apiVersion`
 * y su `kind`, y meterlos en un codec compartido obligaría a discriminarlos
 * por una etiqueta que nunca comparte valores. La regla de EvidenceArtifactCodec
 * se repite a propósito: cierre por `when` exhaustivo sin `else`, mismas
 * cotas de bounded decoding, sin claves ordenadas ni tiempos.
 *
 * El pack y el plan comparten este archivo porque sus DTOs son pequeños y
 * emparentados: el plan se deriva del pack vía `RequiredAssurancePlan.build`
 * y se consume en el mismo flujo. Si el codec creciera, se separaría.
 */
object PackArtifactCodec {

    /** Media type del pack (familia 4 del wire contract). */
    const val PACK_MEDIA_TYPE = "application/vnd.pipelinek.assurance.pack+cbor;version=1"
    const val PACK_API_VERSION = "assurance-pack/v1"

    /** Media type del plan (familia 5 del wire contract). */
    const val PLAN_MEDIA_TYPE = "application/vnd.pipelinek.assurance.plan+cbor;version=1"
    const val PLAN_API_VERSION = "assurance-plan/v1"

    // -----------------------------------------------------------------------
    // AssurancePack
    // -----------------------------------------------------------------------

    fun encodeToCbor(pack: AssurancePack): ByteArray {
        val dto = PackDto.of(pack)
        requireWithinLimits(dto)
        return Cbor.encodeToByteArray(PackDto.serializer(), dto)
    }

    fun decodeFromCbor(bytes: ByteArray): AssurancePack {
        if (bytes.size > EvidenceArtifactCodec.MAX_INPUT_BYTES) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "pack de ${bytes.size} bytes excede ${EvidenceArtifactCodec.MAX_INPUT_BYTES}",
            )
        }
        val dto = try {
            Cbor.decodeFromByteArray(PackDto.serializer(), bytes)
        } catch (e: Exception) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "pack CBOR no decodificable: ${e.message}", e,
            )
        }
        return dto.toVerifiedDomain()
    }

    private fun PackDto.toVerifiedDomain(): AssurancePack {
        requireWithinLimits(this)
        val pack = toDomain()
        // El pack es versionado por `packVersion`; no declaramos un digest
        // separado porque el contenido es trivialmente recomputable desde
        // el IR de las suites y las reglas.
        return pack
    }

    // -----------------------------------------------------------------------
    // RequiredAssurancePlan.Plan
    // -----------------------------------------------------------------------

    fun encodeToCbor(plan: RequiredAssurancePlan.Plan): ByteArray {
        val dto = PlanDto.of(plan)
        requireWithinLimits(dto)
        return Cbor.encodeToByteArray(PlanDto.serializer(), dto)
    }

    fun decodeFromCborPlan(bytes: ByteArray): RequiredAssurancePlan.Plan {
        if (bytes.size > EvidenceArtifactCodec.MAX_INPUT_BYTES) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "plan de ${bytes.size} bytes excede ${EvidenceArtifactCodec.MAX_INPUT_BYTES}",
            )
        }
        val dto = try {
            Cbor.decodeFromByteArray(PlanDto.serializer(), bytes)
        } catch (e: Exception) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "plan CBOR no decodificable: ${e.message}", e,
            )
        }
        return dto.toVerifiedDomain()
    }

    private fun PlanDto.toVerifiedDomain(): RequiredAssurancePlan.Plan {
        requireWithinLimits(this)
        val plan = toDomain()
        // El digest del plan se verifica con el canonical encoder.
        val canonical = CanonicalEncoder.digestPlan(plan).hex
        if (digest != canonical) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "digest de plan declarado ${digest.take(12)} no coincide con el canónico " +
                    "${canonical.take(12)} (payload alterado en tránsito)",
            )
        }
        return plan
    }

    internal fun requireWithinLimits(dto: PackDto) {
        require(dto.suites.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "suites: ${dto.suites.size} excede el limite"
        }
        require(dto.rules.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "rules: ${dto.rules.size} excede el limite"
        }
    }

    internal fun requireWithinLimits(dto: PlanDto) {
        require(dto.selections.size <= EvidenceArtifactCodec.MAX_COLLECTION_SIZE) {
            "selections: ${dto.selections.size} excede el limite"
        }
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    @Serializable
    data class PackDto(
        @SerialName("apiVersion") val apiVersion: String,
        @SerialName("name") val name: String,
        @SerialName("packVersion") val packVersion: String,
        @SerialName("description") val description: String,
        @SerialName("suites") val suites: List<SuiteRefDto>,
        @SerialName("rules") val rules: List<RuleDto>,
    ) {
        companion object {
            fun of(pack: AssurancePack): PackDto = PackDto(
                apiVersion = PACK_API_VERSION,
                name = pack.name,
                packVersion = pack.packVersion,
                description = pack.description,
                suites = pack.suites.map { SuiteRefDto.of(it) },
                rules = pack.rules.map { RuleDto.of(it) },
            )
        }

        fun toDomain(): AssurancePack = AssurancePack(
            name = name,
            packVersion = packVersion,
            description = description,
            suites = suites.map { it.toDomain() },
            rules = rules.map { it.toDomain() },
        )
    }

    @Serializable
    data class SuiteRefDto(
        @SerialName("suiteId") val suiteId: String,
        @SerialName("suiteVersion") val suiteVersion: String,
        @SerialName("digest") val digest: String? = null,
        @SerialName("pathPrefix") val pathPrefix: String? = null,
    ) {
        companion object {
            fun of(ref: AssurancePack.SuiteRef): SuiteRefDto = SuiteRefDto(
                suiteId = ref.suiteId.value,
                suiteVersion = ref.suiteVersion,
                digest = ref.digest?.hex,
                pathPrefix = ref.pathPrefix,
            )
        }

        fun toDomain(): AssurancePack.SuiteRef = AssurancePack.SuiteRef(
            suiteId = SuiteId(suiteId),
            suiteVersion = suiteVersion,
            digest = digest?.let { Digest(it) },
            pathPrefix = pathPrefix,
        )
    }

    @Serializable
    data class RuleDto(
        @SerialName("kind") val kind: String, // "mandatory" | "touched" | "newFindings"
        @SerialName("suiteId") val suiteId: String,
        @SerialName("path") val path: String? = null,
    ) {
        companion object {
            fun of(rule: AssurancePack.Rule): RuleDto = when (rule) {
                is AssurancePack.Rule.Mandatory -> RuleDto(
                    kind = "mandatory",
                    suiteId = rule.suiteId.value,
                )
                is AssurancePack.Rule.Touched -> RuleDto(
                    kind = "touched",
                    suiteId = rule.suiteId.value,
                    path = rule.prefix,
                )
                is AssurancePack.Rule.NewFindings -> RuleDto(
                    kind = "newFindings",
                    suiteId = rule.suiteId.value,
                )
            }
        }

        fun toDomain(): AssurancePack.Rule = when (kind) {
            "mandatory" -> AssurancePack.Rule.Mandatory(SuiteId(suiteId))
            "touched" -> AssurancePack.Rule.Touched(SuiteId(suiteId), path)
            "newFindings" -> AssurancePack.Rule.NewFindings(SuiteId(suiteId))
            else -> throw EvidenceArtifactCodec.ArtifactDecodeException(
                "Rule.kind desconocido: $kind (se esperaba mandatory|touched|newFindings)",
            )
        }
    }

    @Serializable
    data class PlanDto(
        @SerialName("apiVersion") val apiVersion: String,
        @SerialName("engineVersion") val engineVersion: String,
        @SerialName("digest") val digest: String,
        @SerialName("selections") val selections: List<SelectionDto>,
    ) {
        companion object {
            fun of(plan: RequiredAssurancePlan.Plan): PlanDto = PlanDto(
                apiVersion = PLAN_API_VERSION,
                engineVersion = plan.engineVersion,
                digest = CanonicalEncoder.digestPlan(plan).hex,
                selections = plan.selections.map { SelectionDto.of(it) },
            )
        }

        fun toDomain(): RequiredAssurancePlan.Plan = RequiredAssurancePlan.Plan(
            engineVersion = engineVersion,
            selections = selections.map { it.toDomain() },
        )
    }

    @Serializable
    data class SelectionDto(
        @SerialName("suiteId") val suiteId: String,
        @SerialName("reason") val reason: ReasonDto,
    ) {
        companion object {
            fun of(sel: RequiredAssurancePlan.SuiteSelection): SelectionDto = SelectionDto(
                suiteId = sel.suiteId.value,
                reason = ReasonDto.of(sel.reason),
            )
        }

        fun toDomain(): RequiredAssurancePlan.SuiteSelection = RequiredAssurancePlan.SuiteSelection(
            suiteId = SuiteId(suiteId),
            reason = reason.toDomain(),
        )
    }

    @Serializable
    data class ReasonDto(
        @SerialName("kind") val kind: String, // "mandatory" | "touched" | "newFindings"
        @SerialName("path") val path: String? = null,
    ) {
        companion object {
            fun of(reason: RequiredAssurancePlan.Reason): ReasonDto = when (reason) {
                RequiredAssurancePlan.Reason.MandatoryBaseline -> ReasonDto(kind = "mandatory")
                RequiredAssurancePlan.Reason.NewFindingsPresent -> ReasonDto(kind = "newFindings")
                is RequiredAssurancePlan.Reason.TouchedByChange -> ReasonDto(
                    kind = "touched",
                    path = reason.path,
                )
            }
        }

        fun toDomain(): RequiredAssurancePlan.Reason = when (kind) {
            "mandatory" -> RequiredAssurancePlan.Reason.MandatoryBaseline
            "newFindings" -> RequiredAssurancePlan.Reason.NewFindingsPresent
            "touched" -> RequiredAssurancePlan.Reason.TouchedByChange(
                path = path ?: throw EvidenceArtifactCodec.ArtifactDecodeException(
                    "Reason.touched sin path",
                ),
            )
            else -> throw EvidenceArtifactCodec.ArtifactDecodeException(
                "Reason.kind desconocido: $kind",
            )
        }
    }
}
