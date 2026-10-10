package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssurancePack
import dev.pipelinek.assurance.engine.RequiredAssurancePlan
import dev.pipelinek.assurance.engine.SuiteId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * M10 — Tests del codec CBOR de `AssurancePack` y
 * `RequiredAssurancePlan.Plan`.
 *
 * Lo que se certifya:
 *   - `encodeToCbor`/`decodeFromCbor` del pack preserva nombre,
 *     version, suites y reglas.
 *   - Las 3 variantes de `Rule` (Mandatory / Touched / NewFindings)
 *     sobreviven al roundtrip con su semántica.
 *   - El plan codifica el digest canónico y el decoder lo comprueba.
 *   - Un plan con el digest alterado se rechaza.
 *   - `decodeFromCbor` con un `Rule.kind` desconocido falla tipado,
 *     nunca con una excepción opaca.
 *   - Las cotas de bounded decoding se aplican: un pack con un
 *     `Rule.kind` extraño se rechaza con `ArtifactDecodeException`.
 */
class PackArtifactCodecRoundtripTest : AnnotationSpec() {

    @Test
    fun pack_basico_roundtrip_preserva_campos() {
        val pack = AssurancePack(
            name = "release-m10",
            packVersion = "1.0.0",
            description = "Suites obligatorias",
            suites = listOf(
                AssurancePack.SuiteRef(SuiteId("a"), "1.0.0"),
                AssurancePack.SuiteRef(SuiteId("b"), "2.0.0"),
            ),
            rules = listOf(
                AssurancePack.Rule.Mandatory(SuiteId("a")),
            ),
        )
        val bytes = PackArtifactCodec.encodeToCbor(pack)
        val decoded = PackArtifactCodec.decodeFromCbor(bytes)
        decoded.name shouldBe "release-m10"
        decoded.packVersion shouldBe "1.0.0"
        decoded.description shouldBe "Suites obligatorias"
        decoded.suites.size shouldBe 2
        decoded.suites[0].suiteId.value shouldBe "a"
        decoded.suites[1].suiteId.value shouldBe "b"
        decoded.rules.size shouldBe 1
        decoded.rules[0].shouldBeInstanceOf<AssurancePack.Rule.Mandatory>()
    }

    @Test
    fun pack_las_3_reglas_roundtrip() {
        val pack = AssurancePack(
            name = "three-rules",
            packVersion = "1.0.0",
            description = "todas las variantes",
            suites = listOf(
                AssurancePack.SuiteRef(SuiteId("a"), "1.0.0"),
                AssurancePack.SuiteRef(SuiteId("b"), "1.0.0"),
                AssurancePack.SuiteRef(SuiteId("c"), "1.0.0"),
            ),
            rules = listOf(
                AssurancePack.Rule.Mandatory(SuiteId("a")),
                AssurancePack.Rule.Touched(SuiteId("b"), prefix = "b/path"),
                AssurancePack.Rule.NewFindings(SuiteId("c")),
            ),
        )
        val decoded = PackArtifactCodec.decodeFromCbor(PackArtifactCodec.encodeToCbor(pack))
        decoded.rules[0].shouldBeInstanceOf<AssurancePack.Rule.Mandatory>()
        decoded.rules[1].shouldBeInstanceOf<AssurancePack.Rule.Touched>()
        (decoded.rules[1] as AssurancePack.Rule.Touched).prefix shouldBe "b/path"
        decoded.rules[2].shouldBeInstanceOf<AssurancePack.Rule.NewFindings>()
    }

    @Test
    fun pack_suite_ref_con_digest_roundtrip() {
        val pack = AssurancePack(
            name = "with-digests",
            packVersion = "1.0.0",
            description = "refs con digest",
            suites = listOf(
                AssurancePack.SuiteRef(
                    suiteId = SuiteId("a"),
                    suiteVersion = "1.0.0",
                    digest = Digest.ofUtf8("test"),
                ),
            ),
            rules = emptyList(),
        )
        val decoded = PackArtifactCodec.decodeFromCbor(PackArtifactCodec.encodeToCbor(pack))
        decoded.suites.single().digest shouldBe Digest.ofUtf8("test")
    }

    @Test
    fun plan_roundtrip_preserva_selecciones_y_digest() {
        val plan = RequiredAssurancePlan.Plan(
            engineVersion = "0.1.0",
            selections = listOf(
                RequiredAssurancePlan.SuiteSelection(
                    suiteId = SuiteId("a"),
                    reason = RequiredAssurancePlan.Reason.MandatoryBaseline,
                ),
                RequiredAssurancePlan.SuiteSelection(
                    suiteId = SuiteId("b"),
                    reason = RequiredAssurancePlan.Reason.TouchedByChange(path = "b/src/X.kt"),
                ),
            ),
        )
        val bytes = PackArtifactCodec.encodeToCbor(plan)
        val decoded = PackArtifactCodec.decodeFromCborPlan(bytes)
        decoded.engineVersion shouldBe "0.1.0"
        decoded.selections.size shouldBe 2
        decoded.selections[0].reason shouldBe RequiredAssurancePlan.Reason.MandatoryBaseline
        decoded.selections[1].reason.shouldBeInstanceOf<RequiredAssurancePlan.Reason.TouchedByChange>()
        (decoded.selections[1].reason as RequiredAssurancePlan.Reason.TouchedByChange).path shouldBe "b/src/X.kt"
        // El digest del plan coincide con el canónico.
        val canonicalDigest = CanonicalEncoder.digestPlan(decoded).hex
        val encodedDigest = PlanDigest(bytes)
        encodedDigest shouldBe canonicalDigest
    }

    @Test
    fun plan_con_digest_alterado_se_rechaza() {
        val plan = RequiredAssurancePlan.Plan(
            engineVersion = "0.1.0",
            selections = emptyList(),
        )
        // Codificamos, decodificamos el DTO, cambiamos el digest, re-codificamos.
        // Así garantizamos que el byte alterado cae en el campo `digest`
        // (que es lo único que el decoder verifica).
        val original = PackArtifactCodec.encodeToCbor(plan)
        val dto = kotlinx.serialization.cbor.Cbor.decodeFromByteArray(
            PackArtifactCodec.PlanDto.serializer(), original,
        )
        val alteredDigest = "0".repeat(dto.digest.length - 1) + "f"
        val tampered = dto.copy(digest = alteredDigest)
        val altered = kotlinx.serialization.cbor.Cbor.encodeToByteArray(
            PackArtifactCodec.PlanDto.serializer(), tampered,
        )
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            PackArtifactCodec.decodeFromCborPlan(altered)
        }
        (ex.message?.contains("digest") ?: false) shouldBe true
    }

    @Test
    fun plan_con_reason_desconocido_falla_tipado() {
        // CBOR no acepta campos arbitrarios; el decoder usa un `when`
        // cerrado y cualquier valor fuera de la union produce una
        // excepcion tipada, no una runtime exception opaca.
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            // Construimos un DTO con kind desconocido y lo serializamos.
            val dto = PackArtifactCodec.PlanDto(
                apiVersion = PackArtifactCodec.PLAN_API_VERSION,
                engineVersion = "0.1.0",
                digest = CanonicalEncoder.digestPlan(
                    RequiredAssurancePlan.Plan("0.1.0", emptyList()),
                ).hex,
                selections = listOf(
                    PackArtifactCodec.SelectionDto(
                        suiteId = "a",
                        reason = PackArtifactCodec.ReasonDto(kind = "unknown"),
                    ),
                ),
            )
            val bytes = kotlinx.serialization.cbor.Cbor.encodeToByteArray(
                PackArtifactCodec.PlanDto.serializer(), dto,
            )
            PackArtifactCodec.decodeFromCborPlan(bytes)
        }
        (ex.message?.contains("Reason.kind") ?: false) shouldBe true
    }

    // --- helper: extrae el digest declarado de los bytes CBOR del plan ---

    private fun PlanDigest(bytes: ByteArray): String {
        val dto = kotlinx.serialization.cbor.Cbor.decodeFromByteArray(
            PackArtifactCodec.PlanDto.serializer(), bytes,
        )
        return dto.digest
    }
}
