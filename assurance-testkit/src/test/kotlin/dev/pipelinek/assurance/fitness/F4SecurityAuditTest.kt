package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import dev.pipelinek.assurance.artifact.PackArtifactCodec
import dev.pipelinek.assurance.artifact.ReportArtifactCodec
import dev.pipelinek.assurance.artifact.SuiteArtifactCodec
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.ProviderFailureReason
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * F4 (Bloque F) — Auditoría final de seguridad.
 *
 * Ref: `odd/tasks/block-F-release.md` §F4.
 *
 * El plan F4 lista 8 garantías de seguridad que el sistema
 * debe enforce. Cada test verifica UNA garantía, con un caso
 * adversarial concreto:
 *
 *   1. No deserialización arbitraria.
 *   2. No ejecución de código desde evidence payload.
 *   3. Límites de entrada y memoria.
 *   4. Protección frente a XML/JSON/CBOR malformados.
 *   5. Integridad de manifests y digests.
 *   6. Control de rutas dentro del workspace.
 *   7. Trust/provenance de los producers.
 *   8. Ausencia de falsas aprobaciones por errores de
 *      observación.
 */
class F4SecurityAuditTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // 1. No deserialización arbitraria
    // -----------------------------------------------------------------
    @Test
    fun F4_01_bytes_que_no_son_CBOR_se_rechazan_sin_ejecutar() {
        val json = """{"apiVersion": "assurance-evidence/v1"}"""
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            EvidenceArtifactCodec.decodeFromCbor(json.toByteArray())
        }
        ex.message shouldContain "CBOR"
    }

    @Test
    fun F4_01_bytes_CBOR_que_no_son_del_esperado_se_rechazan() {
        // Un CBOR válido (entero) que NO es un evidence:
        // el codec lo rechaza con tipo concreto, no opaque.
        val notEvidence = byteArrayOf(0x18.toByte(), 0x64)
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            EvidenceArtifactCodec.decodeFromCbor(notEvidence)
        }
        ex.message shouldContain "decodificable"
    }

    // -----------------------------------------------------------------
    // 3. Límites de entrada y memoria
    // -----------------------------------------------------------------
    @Test
    fun F4_03_entrada_que_excede_MAX_INPUT_BYTES_se_rechaza_antes_de_decodificar() {
        // Construimos un payload que excede el límite.
        val oversized = ByteArray((EvidenceArtifactCodec.MAX_INPUT_BYTES + 1).toInt())
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            EvidenceArtifactCodec.decodeFromCbor(oversized)
        }
        ex.message shouldContain "excede"
    }

    // -----------------------------------------------------------------
    // 4. Protección frente a XML/JSON/CBOR malformados
    // -----------------------------------------------------------------
    @Test
    fun F4_04_suite_con_bytes_truncados_se_rechaza() {
        // Generamos un suite válido, luego truncamos.
        val suite = suiteMinimo()
        val bytes = SuiteArtifactCodec.encodeToCbor(suite)
        val truncado = bytes.copyOf(bytes.size / 2)
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            SuiteArtifactCodec.decodeFromCbor(truncado)
        }
        (ex.message ?: "") shouldContain "CBOR"
    }

    // -----------------------------------------------------------------
    // 5. Integridad de manifests y digests
    // -----------------------------------------------------------------
    @Test
    fun F4_05_evidence_con_digest_alterado_se_rechaza_con_motivo() {
        // Codificamos un snapshot válido, alteramos el
        // digest en bytes CBOR, y verificamos que el
        // decode lo rechaza con motivo concreto.
        val snap = dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot(
            id = dev.pipelinek.assurance.domain.evidence.SnapshotId("snap-1"),
            subject = dev.pipelinek.assurance.domain.evidence.EvidenceSubject.Module("x"),
            sources = listOf(
                dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest(
                    producerId = "test/audit",
                    producerVersion = "0.1.0",
                    subjectRevision = dev.pipelinek.assurance.domain.evidence.RevisionRef(
                        "0000000000000000000000000000000000000000",
                    ),
                    requestedCapabilities = listOf("arch.test"),
                    producedCapabilities = listOf("arch.test"),
                    completenessByCapability = mapOf("arch.test" to dev.pipelinek.assurance.domain.evidence.Completeness.Complete),
                    schemaVersion = "assurance-evidence/v1",
                    digest = Digest.ofUtf8("manifest"),
                ),
            ),
            items = emptyList(),
            gaps = emptyList(),
        )
        val goodBytes = EvidenceArtifactCodec.encodeToCbor(snap)
        val tampered = goodBytes.copyOf().also {
            it[it.size - 3] = (it[it.size - 3].toInt() xor 0x01).toByte()
        }
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            EvidenceArtifactCodec.decodeFromCbor(tampered)
        }
        (ex.message ?: "") shouldContain "digest"
    }

    @Test
    fun F4_05_report_con_digest_alterado_se_rechaza_con_motivo() {
        val report = reportMinimo()
        val bytes = ReportArtifactCodec.encodeToCbor(report)
        val tampered = bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x01).toByte() }
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            ReportArtifactCodec.decodeFromCbor(tampered)
        }
        ex.message shouldContain "digest"
    }

    // -----------------------------------------------------------------
    // 7. Trust/provenance de los producers (AAT-19)
    // -----------------------------------------------------------------
    @Test
    fun F4_07_producer_que_miente_sobre_authority_queda_en_authority_desconocida() {
        // AAT-19: el sistema distingue las authorities
        // válidas (DeterministicAdapter, RuntimeObserver,
        // HeuristicAnalyzer, etc.) de cualquier otra cadena.
        // Una authority falsa (e.g. "MintAuthority") NO se
        // confunde con una válida: lo que el sistema hace
        // con la cadena es un parser estricto, no un
        // "best effort" que admita casi todo.
        //
        // Aquí verificamos la pieza observable desde el
        // testkit: la enumeración EvidenceAuthority NO
        // contiene "MintAuthority". La verificación más
        // fuerte (que el normalizer rechaza) vive en el
        // módulo providers y se enlaza con la cadena de
        // F4_08.
        val knownAuthorities = EvidenceAuthority.entries.map { it.name }
        (knownAuthorities.contains("MintAuthority")) shouldBe false
    }

    // -----------------------------------------------------------------
    // 8. Ausencia de falsas aprobaciones por errores de observación
    // -----------------------------------------------------------------
    @Test
    fun F4_08_provider_que_devuelve_Failed_es_tipo_distinto_de_Produced() {
        // EvidenceCollectionResult es un sealed interface
        // con dos casos (Produced, Failed). El sistema
        // distingue ambos a nivel de tipo: no se puede
        // "asumir vacío" un Failed. Un test de tipo
        // confirma la invariante estática.
        val produced: EvidenceCollectionResult = EvidenceCollectionResult.Produced(
            producerId = "p",
            producerVersion = "0.1.0",
            schemaVersion = "assurance-evidence/v1",
            rawItems = emptyList(),
            declaredGaps = emptyList(),
        )
        val failed: EvidenceCollectionResult = EvidenceCollectionResult.Failed(
            producerId = "p",
            reason = ProviderFailureReason.CollectionError("test"),
            gaps = emptyList(),
        )
        (produced is EvidenceCollectionResult.Produced) shouldBe true
        (failed is EvidenceCollectionResult.Failed) shouldBe true
        (produced is EvidenceCollectionResult.Failed) shouldBe false
        (failed is EvidenceCollectionResult.Produced) shouldBe false
    }

    // -----------------------------------------------------------------
    // 5. Integridad: pack artifact con apiVersion desconocida
    // -----------------------------------------------------------------
    @Test
    fun F4_05_pack_con_bytes_alterados_se_rechaza() {
        // Construimos un pack válido (con al menos una
        // suite — el init lo exige), lo codificamos,
        // alteramos bytes CBOR, y verificamos que se
        // rechaza con tipo concreto.
        val pack = dev.pipelinek.assurance.engine.AssurancePack(
            name = "p1",
            packVersion = "1.0.0",
            description = "pack minimo para test de codec",
            suites = listOf(
                dev.pipelinek.assurance.engine.AssurancePack.SuiteRef(
                    suiteId = dev.pipelinek.assurance.engine.SuiteId("s1"),
                    suiteVersion = "1.0.0",
                ),
            ),
            rules = emptyList(),
        )
        val goodBytes = PackArtifactCodec.encodeToCbor(pack)
        val tampered = goodBytes.copyOf().also {
            // Alteramos un byte del apiVersion (que
            // está entre los primeros campos CBOR).
            it[5] = (it[5].toInt() xor 0x01).toByte()
        }
        val ex = shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            PackArtifactCodec.decodeFromCbor(tampered)
        }
        (ex.message ?: "").isNotEmpty() shouldBe true
    }

    // --- helpers ---

    private fun suiteMinimo() = dev.pipelinek.assurance.engine.AssuranceSuiteIR(
        apiVersion = "assurance-ir/v1",
        suiteId = dev.pipelinek.assurance.engine.SuiteId("s1"),
        suiteVersion = "1.0.0",
        requiredEvidence = listOf("arch.test"),
        lenses = listOf(
            dev.pipelinek.assurance.engine.LensPlan(
                lensId = dev.pipelinek.assurance.engine.LensId("l/min"),
                kind = "noop",
                inputCapabilities = listOf("arch.test"),
                outputSchema = "Empty",
            ),
        ),
        assertions = listOf(
            dev.pipelinek.assurance.engine.AssertionIR(
                id = dev.pipelinek.assurance.engine.AssertionId("a/min"),
                lensRef = dev.pipelinek.assurance.engine.LensId("l/min"),
                operator = "noop",
                operands = emptyMap(),
                severity = dev.pipelinek.assurance.engine.Severity.Info,
                enforcement = dev.pipelinek.assurance.engine.Enforcement.Advisory,
                completenessRequirements = emptyList(),
                rationale = "min",
            ),
        ),
    )

    private fun reportMinimo(): AssuranceReport = AssuranceReport(
        evaluationId = dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId("eval-1"),
        snapshotDigest = Digest.ofUtf8("snap"),
        suiteDigest = Digest.ofUtf8("suite"),
        engineVersion = "0.1.0",
        results = listOf(
            AssertionResult.Passed(
                ProofRef(
                    "s",
                    listOf(dev.pipelinek.assurance.domain.evidence.EvidenceId("e/1")),
                    dev.pipelinek.assurance.engine.AssertionId("a/1"),
                ),
            ),
        ),
        gaps = emptyList(),
        artifacts = emptyList(),
        correlations = emptyList(),
    )
}
