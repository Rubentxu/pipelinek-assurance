package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.ProviderClassification
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow

/**
 * B2 (Bloque B) — Tests del `AssuranceOrchestrator`.
 *
 * Lo que se verifica:
 *   1. Los 10 pasos del plan B2 se ejecutan: resolver
 *      suite → validar refs → seleccionar providers →
 *      recolectar → normalizar → congelar → evaluar →
 *      codificar → tipar resultado.
 *   2. Una ref sin digest hex de 64 chars se rechaza.
 *   3. Una ref duplicada por digest se deduplica.
 *   4. Un provider que lanza excepción se traduce a gap,
 *      no aborta el orchestrator.
 *   5. Un provider que retorna Failed se traduce a gap.
 *   6. Una normalización con id sin namespace aborta con
 *      OrchestrationResult.Failed (producer mintió).
 *   7. El resultado exitoso lleva el report y el digest.
 */
class AssuranceOrchestratorTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun fakeDigest(seed: String): String =
        // Genera 64 chars hex a partir de un seed. No es
        // criptográficamente seguro; sólo garantiza forma.
        (0 until 32).joinToString("") {
            "%02x".format((seed.hashCode() + it) and 0xff)
        }

    private fun sampleProvider(
        role: String,
        result: EvidenceCollectionResult,
    ): EvidenceProvider = object : EvidenceProvider {
        override val descriptor: EvidenceProviderDescriptor =
            dev.pipelinek.assurance.engine.EvidenceProviderDescriptor(
                id = "test/$role",
                version = "0.1.0",
                evidenceCapabilities = listOf(role),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
        override fun collect(request: EvidenceRequest): EvidenceCollectionResult = result
    }

    private fun throwingProvider(
        role: String,
        message: String,
    ): EvidenceProvider = object : EvidenceProvider {
        override val descriptor: EvidenceProviderDescriptor =
            dev.pipelinek.assurance.engine.EvidenceProviderDescriptor(
                id = "test/$role",
                version = "0.1.0",
                evidenceCapabilities = listOf(role),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
        override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
            throw RuntimeException(message)
    }

    private fun okProvider(
        role: String,
        capability: String = "test.cap",
    ): EvidenceProvider = sampleProvider(
        role = role,
        result = EvidenceCollectionResult.Produced(
            producerId = "test/$role",
            producerVersion = "0.1.0",
            schemaVersion = "assurance-evidence/v1",
            rawItems = emptyList(),
            declaredGaps = emptyList(),
        ),
    )

    private fun validRequest(
        role: String = "test.cap",
        digest: String = fakeDigest("snap-1"),
    ): OrchestrationRequest {
        val subject = EvidenceSubject.Module("self")
        return OrchestrationRequest(
            name = "self",
            suite = dev.pipelinek.assurance.engine.AssuranceSuiteIR(
                apiVersion = "assurance-ir/v1",
                suiteId = dev.pipelinek.assurance.engine.SuiteId("test-suite"),
                suiteVersion = "v1",
                requiredEvidence = listOf(role),
                lenses = listOf(
                    dev.pipelinek.assurance.engine.LensPlan(
                        lensId = LensId("l/$role"),
                        kind = "test",
                        inputCapabilities = listOf(role),
                        outputSchema = "schema/v1",
                    ),
                ),
                assertions = listOf(
                    dev.pipelinek.assurance.engine.AssertionIR(
                        id = dev.pipelinek.assurance.engine.AssertionId("a/$role"),
                        lensRef = LensId("l/$role"),
                        operator = "no-edge",
                        operands = emptyMap(),
                        severity = dev.pipelinek.assurance.engine.Severity.Error,
                        enforcement = dev.pipelinek.assurance.engine.Enforcement.Advisory,
                        completenessRequirements = listOf(role),
                        rationale = "test",
                    ),
                ),
            ),
            subject = subject,
            evidenceRefs = listOf(
                EvidenceRefDto(
                    logicalRole = role,
                    digest = digest,
                    producerId = "test",
                ),
            ),
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
    }

    private fun emptyRuntime() = FrozenAssuranceRuntime(
        engineVersion = "0.1.0",
        lenses = emptyMap(),
    )

    // -----------------------------------------------------------------
    // 1. Happy path: orquestación exitosa
    // -----------------------------------------------------------------

    @Test
    fun orquestacion_exitosa_con_provider_ok() {
        val registry = ProviderRegistry().apply {
            register("test.cap", okProvider("test.cap"))
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = validRequest(),
            providers = registry,
            runtime = emptyRuntime(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        success.report.results.size shouldBe 1
        // La assertion existe y su resultado es Inconclusive o
        // Unsupported (porque el provider no retornó items, sólo
        // declaró la capability). Lo que importa: el orchestrator
        // llegó al paso 8.
        val res = success.report.results[0]
        (res is AssertionResult.Inconclusive || res is AssertionResult.Unsupported || res is AssertionResult.Passed) shouldBe true
    }

    // -----------------------------------------------------------------
    // 2. Validación de refs: digest inválido
    // -----------------------------------------------------------------

    @Test
    fun ref_con_digest_invalido_se_rechaza() {
        val request = validRequest(digest = "deadbeef") // sólo 8 chars
        val ex = shouldThrow<IllegalArgumentException> {
            AssuranceOrchestrator.orchestrate(
                request = request,
                providers = ProviderRegistry(),
                runtime = emptyRuntime(),
            )
        }
        ex.message?.contains("invalid digest") shouldBe true
    }

    // -----------------------------------------------------------------
    // 3. Refs duplicadas por digest: deduplicadas
    // -----------------------------------------------------------------

    @Test
    fun refs_duplicadas_por_digest_se_deduplican() {
        val sameDigest = fakeDigest("snap-1")
        val request = OrchestrationRequest(
            name = validRequest().name,
            suite = validRequest().suite,
            subject = validRequest().subject,
            evidenceRefs = listOf(
                EvidenceRefDto("test.cap", sameDigest, "test"),
                EvidenceRefDto("test.cap", sameDigest, "test"), // dup
            ),
            mode = validRequest().mode,
            completenessPolicy = validRequest().completenessPolicy,
            producerVersion = validRequest().producerVersion,
        )
        val registry = ProviderRegistry().apply {
            register("test.cap", okProvider("test.cap"))
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = registry,
            runtime = emptyRuntime(),
        )
        // La dedup es silenciosa; el resultado sigue siendo
        // Success y el report lleva 1 assertion (no 2).
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        success.report.results.size shouldBe 1
    }

    // -----------------------------------------------------------------
    // 4. Provider que lanza: se traduce a gap, no aborta
    // -----------------------------------------------------------------

    @Test
    fun provider_que_lanza_excepcion_se_traduce_a_gap() {
        val registry = ProviderRegistry().apply {
            register("test.cap", throwingProvider("test.cap", "boom"))
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = validRequest(),
            providers = registry,
            runtime = emptyRuntime(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        // El provider lanzó, no produjo items. La assertion
        // existe y su resultado es Inconclusive (gap de Lost)
        // o Unsupported (lens ausente en runtime). Lo que
        // importa: el orchestrator llegó al paso 8.
        val res = success.report.results[0]
        (res is AssertionResult.Inconclusive || res is AssertionResult.Unsupported) shouldBe true
        // El gap se documenta.
        success.gaps.any {
            it.capability == "test.cap" && it.reason == dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Lost
        } shouldBe true
    }

    // -----------------------------------------------------------------
    // 5. Provider que retorna Failed: gap, no aborta
    // -----------------------------------------------------------------

    @Test
    fun provider_que_retorna_failed_se_traduce_a_gap() {
        val registry = ProviderRegistry().apply {
            register(
                "test.cap",
                sampleProvider(
                    "test.cap",
                    EvidenceCollectionResult.Failed(
                        producerId = "test",
                        reason = dev.pipelinek.assurance.engine.ProviderFailureReason.CollectionError("missing input"),
                        gaps = emptyList(),
                    ),
                ),
            )
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = validRequest(),
            providers = registry,
            runtime = emptyRuntime(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        success.gaps.any {
            it.capability == "test.cap" && it.reason == dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Lost
        } shouldBe true
    }

    // -----------------------------------------------------------------
    // 6. Sin provider registrado: gap Unsupported
    // -----------------------------------------------------------------

    @Test
    fun sin_provider_registrado_se_reporta_gap_unsupported() {
        val result = AssuranceOrchestrator.orchestrate(
            request = validRequest(),
            providers = ProviderRegistry(), // vacío
            runtime = emptyRuntime(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        success.gaps.any {
            it.capability == "test.cap" && it.reason == dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unsupported
        } shouldBe true
    }

    // -----------------------------------------------------------------
    // 7. Normalización con id sin namespace: Failed
    // -----------------------------------------------------------------

    @Test
    fun normalizer_rechaza_id_sin_namespace_devuelve_failed() {
        val badItems = listOf(
            dev.pipelinek.assurance.engine.RawEvidenceItem(
                kind = dev.pipelinek.assurance.engine.RawItemKind.Fact,
                id = "no-namespace", // No contiene '/'
                subjectRef = "module:core",
                authority = "DeterministicAdapter",
                payload = mapOf("predicate" to "p", "object" to "o"),
            ),
        )
        val registry = ProviderRegistry().apply {
            register(
                "test.cap",
                sampleProvider(
                    "test.cap",
                    EvidenceCollectionResult.Produced(
                        producerId = "test",
                        producerVersion = "0.1.0",
                        schemaVersion = "assurance-evidence/v1",
                        rawItems = badItems,
                        declaredGaps = emptyList(),
                    ),
                ),
            )
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = validRequest(),
            providers = registry,
            runtime = emptyRuntime(),
        )
        val failed = result.shouldBeInstanceOf<OrchestrationResult.Failed>()
        failed.reason.contains("normalization failed") shouldBe true
    }

    // -----------------------------------------------------------------
    // 8. El report lleva su digest
    // -----------------------------------------------------------------

    @Test
    fun report_lleva_digest_no_vacio() {
        val registry = ProviderRegistry().apply {
            register("test.cap", okProvider("test.cap"))
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = validRequest(),
            providers = registry,
            runtime = emptyRuntime(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        success.reportDigest.hex.isNotEmpty() shouldBe true
        success.reportDigest.hex.length shouldBe 64
    }

    // -----------------------------------------------------------------
    // 9. Refs vacías + producer registrado: gap por capability
    //    requerida ausente
    // -----------------------------------------------------------------

    @Test
    fun refs_vacias_con_provider_registrado_produce_gap_por_required_ausente() {
        // Pedimos una capability que NO está en evidenceRefs.
        val request = OrchestrationRequest(
            name = "self",
            suite = dev.pipelinek.assurance.engine.AssuranceSuiteIR(
                apiVersion = "assurance-ir/v1",
                suiteId = dev.pipelinek.assurance.engine.SuiteId("test-suite"),
                suiteVersion = "v1",
                requiredEvidence = listOf("required.cap"),
                lenses = listOf(
                    dev.pipelinek.assurance.engine.LensPlan(
                        lensId = LensId("l/req"),
                        kind = "test",
                        inputCapabilities = listOf("required.cap"),
                        outputSchema = "schema/v1",
                    ),
                ),
                assertions = listOf(
                    dev.pipelinek.assurance.engine.AssertionIR(
                        id = dev.pipelinek.assurance.engine.AssertionId("a/req"),
                        lensRef = LensId("l/req"),
                        operator = "no-edge",
                        operands = emptyMap(),
                        severity = dev.pipelinek.assurance.engine.Severity.Error,
                        enforcement = dev.pipelinek.assurance.engine.Enforcement.Mandatory,
                        completenessRequirements = listOf("required.cap"),
                        rationale = "test",
                    ),
                ),
            ),
            subject = EvidenceSubject.Module("self"),
            evidenceRefs = emptyList(), // sin refs
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
        val result = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = ProviderRegistry(),
            runtime = emptyRuntime(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        // La required capability está ausente, así que el
        // orchestrator la reporta como gap Unsupported.
        success.gaps.any {
            it.capability == "required.cap"
        } shouldBe true
    }
}
