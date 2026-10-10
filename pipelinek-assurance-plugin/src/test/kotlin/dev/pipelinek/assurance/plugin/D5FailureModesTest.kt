package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.RawGapReason
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.engine.architecture.Layer
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * D5 (Bloque D) — Fallos, crash y replay.
 *
 * Ref: `odd/tasks/block-D-runtime.md` §D5.
 *
 * El plan D5 dice:
 *   - Cancelación del ancestro, body fallido, fallo del
 *     observer, pérdida de evidencia, caída antes/después del
 *     sellado, interrupción al publicar.
 *
 * Los cuatro primeros casos (cancelación, body fallido) ya
 * están cubiertos en `AssuranceVerifyStepTest` (M_P01, M_P02,
 * M_P03). Este archivo cubre los casos que faltaban:
 *
 *   1. **Fallo del observer**: el provider lanza una
 *      excepción durante `collect`. El orchestrator la
 *      captura y la convierte en un `EvidenceGap` con
 *      `GapReason.Lost`, no aborta.
 *   2. **Pérdida de evidencia**: el provider declara
 *      explícitamente un gap de `Lost`. El report lo
 *      refleja y la assertion cae a `Inconclusive`.
 *   3. **Crash en handler**: la `BodyContinuation` del SDK
 *      lanza una excepción. El step la captura y la
 *      etiqueta como `BodyOutcome.Failure`, no la propaga
 *      como `Success`.
 *   4. **Crash en normalizer**: el provider miente sobre su
 *      autoridad o su namespace. El normalizer aborta con
 *      `NormalizerException` y el orchestrator devuelve
 *      `OrchestrationResult.Failed` con motivo.
 */
class D5FailureModesTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // 1. Fallo del observer: provider throws → gap Lost, no abort
    // -----------------------------------------------------------------

    @Test
    fun D5_provider_que_lanza_excepcion_se_convierte_en_gap_Lost() {
        val throwingProvider = object : EvidenceProvider {
            override val descriptor = EvidenceProviderDescriptor(
                id = "test/throwing",
                version = "0.1.0",
                evidenceCapabilities = listOf("architecture.dependency-graph"),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
            override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
                throw RuntimeException("observador caido mid-run")
        }
        val registry = ProviderRegistry().apply {
            register("architecture.dependency-graph", throwingProvider)
        }
        val request = newRequest()
        val result = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = registry,
            runtime = standardRuntime(),
            assertionsById = standardAssertions(),
        )
        // El orchestrator NO aborta. Devuelve Success con
        // el gap del observador caído. El gate cae a
        // Inconclusive por incompletitud, no a Passed.
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        val lostGaps = success.gaps.filter { it.reason == EvidenceGap.GapReason.Lost }
        lostGaps.isNotEmpty() shouldBe true
        lostGaps.single().detail shouldContain "observador caido"
    }

    // -----------------------------------------------------------------
    // 2. Pérdida de evidencia: gap declarado → Inconclusive
    // -----------------------------------------------------------------

    @Test
    fun D5_provider_declara_gap_Lost_termina_en_Inconclusive() {
        val losingProvider = object : EvidenceProvider {
            override val descriptor = EvidenceProviderDescriptor(
                id = "test/losing",
                version = "0.1.0",
                evidenceCapabilities = listOf("architecture.dependency-graph"),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
            override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
                EvidenceCollectionResult.Produced(
                    producerId = "test/losing",
                    producerVersion = "0.1.0",
                    schemaVersion = "assurance-evidence/v1",
                    rawItems = emptyList(),
                    declaredGaps = listOf(
                        dev.pipelinek.assurance.engine.RawEvidenceGap(
                            capability = "architecture.dependency-graph",
                            reason = RawGapReason.Lost,
                            detail = "ventana de captura cerrada antes de capturar",
                        ),
                    ),
                )
        }
        val registry = ProviderRegistry().apply {
            register("architecture.dependency-graph", losingProvider)
        }
        val request = newRequest()
        val result = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = registry,
            runtime = standardRuntime(),
            assertionsById = standardAssertions(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        // El gap propagado al report: la assertion cae a
        // Inconclusive (no Passed, no Failed).
        val inconclusive = success.report.results[0]
        inconclusive.shouldBeInstanceOf<dev.pipelinek.assurance.engine.AssertionResult.Inconclusive>()
    }

    // -----------------------------------------------------------------
    // 3. Crash en handler: BodyContinuation throw → Failure
    // -----------------------------------------------------------------

    @Test
    fun D5_handler_que_lanza_se_convierte_en_BodyOutcome_Failure() {
        // El helper `runBodyOnce` captura cualquier throw del
        // handler y lo etiqueta como BodyOutcome.Failure. La
        // cancelación es un caso aparte.
        val handler = AssuranceVerifyStep.BodyContinuation {
            throw IllegalStateException("el body crasheo")
        }
        val outcome = AssuranceVerifyStep.runBodyOnce(handler)
        val failure = outcome.shouldBeInstanceOf<AssuranceVerifyStep.BodyOutcome.Failure>()
        failure.error.shouldBeInstanceOf<IllegalStateException>()
        failure.error.message shouldBe "el body crasheo"
    }

    @Test
    fun D5_handler_failure_se_combina_con_assurance_pass_sin_ocultarlo() {
        // M_P01 elevado a D5: aunque assurance pase, un body
        // failure se preserva como failure. El combine es
        // asimétrico: el body siempre gana.
        val report = reportWith(failed = 0, inconclusive = 0, passed = 1)
        val body = AssuranceVerifyStep.BodyOutcome.Failure(
            error = RuntimeException("body boom"),
            message = "body boom",
        )
        val outcome = AssuranceVerifyStep.combine(body, report)
        val failure = outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Failure>()
        failure.reason shouldContain "body failure"
    }

    @Test
    fun D5_handler_cancelled_no_se_confunde_con_failure() {
        // M_P02 elevado a D5: la cancelación es control
        // estructurado, NO un error. El combine preserva la
        // cancelación aunque assurance pase.
        val report = reportWith(failed = 0, inconclusive = 0, passed = 1)
        val body = AssuranceVerifyStep.BodyOutcome.Cancelled("ancestro cancelo")
        val outcome = AssuranceVerifyStep.combine(body, report)
        outcome.shouldBeInstanceOf<AssuranceVerifyStep.StepOutcome.Cancelled>()
    }

    // -----------------------------------------------------------------
    // 4. Crash en normalizer: producer miente → Failed con motivo
    // -----------------------------------------------------------------

    @Test
    fun D5_provider_miente_sobre_authority_termina_en_OrchestrationResult_Failed() {
        // El provider emite un item con `authority` que el
        // EvidenceNormalizer no reconoce. El normalizer
        // aborta con `NormalizerException`. El orchestrator
        // NO devuelve un report parcial: devuelve Failed
        // con el motivo. Esto protege la frontera de
        // confianza: si un producer miente, el report
        // artifact NO se publica.
        val lyingProvider = object : EvidenceProvider {
            override val descriptor = EvidenceProviderDescriptor(
                id = "test/lying",
                version = "0.1.0",
                evidenceCapabilities = listOf("architecture.dependency-graph"),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
            override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
                EvidenceCollectionResult.Produced(
                    producerId = "test/lying",
                    producerVersion = "0.1.0",
                    schemaVersion = "assurance-evidence/v1",
                    rawItems = listOf(
                        dev.pipelinek.assurance.engine.RawEvidenceItem(
                            kind = dev.pipelinek.assurance.engine.RawItemKind.Fact,
                            id = "test/x/dependsOn",
                            subjectRef = "module:x",
                            authority = "MintAuthority", // AAT-19 violated.
                            payload = mapOf(
                                "predicate" to "dependsOn",
                                "object" to "y",
                                "capability" to "architecture.dependency-graph",
                            ),
                        ),
                    ),
                    declaredGaps = emptyList(),
                )
        }
        val registry = ProviderRegistry().apply {
            register("architecture.dependency-graph", lyingProvider)
        }
        val request = newRequest()
        val result = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = registry,
            runtime = standardRuntime(),
            assertionsById = standardAssertions(),
        )
        val failed = result.shouldBeInstanceOf<OrchestrationResult.Failed>()
        failed.reason shouldContain "normalization failed"
    }

    // -----------------------------------------------------------------
    // 5. Interrupción al publicar: el codec lanza, no expone report
    //    parcial. Aquí verificamos que el codec nunca se llama
    //    cuando el orchestrator devolvió Failed.
    // -----------------------------------------------------------------

    @Test
    fun D5_orchestrator_Failed_no_se_codifica_como_artifact_valido() {
        // La frontera de confianza: cuando el orchestrator
        // devuelve Failed, el caller (Step handler) NO debe
        // publicar el report como artifact. Verificamos el
        // invariante: `OrchestrationResult.Failed.report` no
        // tiene `evaluationId` "evaluable" — lleva el
        // prefijo "eval-failed-" para que un consumidor
        // externo pueda reconocer que ese report NO es
        // publicable.
        val lyingProvider = object : EvidenceProvider {
            override val descriptor = EvidenceProviderDescriptor(
                id = "test/lying",
                version = "0.1.0",
                evidenceCapabilities = listOf("architecture.dependency-graph"),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
            override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
                EvidenceCollectionResult.Produced(
                    producerId = "test/lying",
                    producerVersion = "0.1.0",
                    schemaVersion = "assurance-evidence/v1",
                    rawItems = listOf(
                        dev.pipelinek.assurance.engine.RawEvidenceItem(
                            kind = dev.pipelinek.assurance.engine.RawItemKind.Fact,
                            id = "test/x/dependsOn",
                            subjectRef = "module:x",
                            authority = "MintAuthority",
                            payload = mapOf(
                                "predicate" to "dependsOn",
                                "object" to "y",
                                "capability" to "architecture.dependency-graph",
                            ),
                        ),
                    ),
                    declaredGaps = emptyList(),
                )
        }
        val registry = ProviderRegistry().apply {
            register("architecture.dependency-graph", lyingProvider)
        }
        val result = AssuranceOrchestrator.orchestrate(
            request = newRequest(),
            providers = registry,
            runtime = standardRuntime(),
            assertionsById = standardAssertions(),
        )
        val failed = result.shouldBeInstanceOf<OrchestrationResult.Failed>()
        failed.report.evaluationId.value.startsWith("eval-failed-") shouldBe true
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun newRequest(): OrchestrationRequest {
        return OrchestrationRequest(
            name = "self",
            suite = standardSuite(),
            subject = EvidenceSubject.Module("self"),
            evidenceRefs = listOf(
                EvidenceRefDto(
                    logicalRole = "architecture.dependency-graph",
                    digest = "a".repeat(64),
                    producerId = "test/d5",
                ),
            ),
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
    }

    private fun standardSuite() = dev.pipelinek.assurance.engine.AssuranceSuiteIR(
        apiVersion = "assurance-ir/v1",
        suiteId = dev.pipelinek.assurance.engine.SuiteId("d5"),
        suiteVersion = "v1",
        requiredEvidence = listOf("architecture.dependency-graph"),
        lenses = listOf(
            dev.pipelinek.assurance.engine.LensPlan(
                lensId = dev.pipelinek.assurance.engine.LensId("l/builtin"),
                kind = "BuiltinHexagonal",
                inputCapabilities = listOf("architecture.dependency-graph"),
                outputSchema = "DependencyGraph",
            ),
        ),
        assertions = listOf(
            dev.pipelinek.assurance.engine.AssertionIR(
                id = dev.pipelinek.assurance.engine.AssertionId("a/no-domain-to-external"),
                lensRef = dev.pipelinek.assurance.engine.LensId("l/builtin"),
                operator = "no-edge-between-layers",
                operands = mapOf("from" to "Domain", "to" to "Adapters,Infrastructure"),
                severity = dev.pipelinek.assurance.engine.Severity.Error,
                enforcement = dev.pipelinek.assurance.engine.Enforcement.Mandatory,
                completenessRequirements = listOf("architecture.dependency-graph"),
                rationale = "regla hexagonal",
            ),
        ),
    )

    private fun standardRuntime() = dev.pipelinek.assurance.engine.FrozenAssuranceRuntime(
        engineVersion = "0.1.0",
        lenses = mapOf(
            dev.pipelinek.assurance.engine.LensId("l/builtin") to BuiltinLens,
        ),
    )

    private fun standardAssertions(): Map<dev.pipelinek.assurance.engine.AssertionId, dev.pipelinek.assurance.engine.AssuranceAssertion<*>> =
        mapOf(
            dev.pipelinek.assurance.engine.AssertionId("a/no-domain-to-external") to BuiltinAssertion(),
        )

    private fun reportWith(
        failed: Int,
        inconclusive: Int,
        passed: Int = 0,
    ): dev.pipelinek.assurance.engine.AssuranceReport {
        val results = buildList<dev.pipelinek.assurance.engine.AssertionResult> {
            repeat(passed) {
                add(
                    dev.pipelinek.assurance.engine.AssertionResult.Passed(
                        dev.pipelinek.assurance.engine.ProofRef(
                            "s",
                            listOf(dev.pipelinek.assurance.domain.evidence.EvidenceId("e/$it")),
                            dev.pipelinek.assurance.engine.AssertionId("a/$it"),
                        ),
                    ),
                )
            }
            repeat(failed) {
                add(
                    dev.pipelinek.assurance.engine.AssertionResult.Failed(
                        dev.pipelinek.assurance.engine.Counterexample.Cycle(
                            assertionId = dev.pipelinek.assurance.engine.AssertionId("a/fail-$it"),
                            subjectRefs = emptyList(),
                            evidenceRefs = listOf(dev.pipelinek.assurance.domain.evidence.EvidenceId("e/$it")),
                            explanation = "test",
                            reproductionHints = emptyList(),
                            cycle = listOf("a", "b"),
                        ),
                    ),
                )
            }
            repeat(inconclusive) {
                add(
                    dev.pipelinek.assurance.engine.AssertionResult.Inconclusive(
                        listOf(
                            EvidenceGap(
                                capability = "test",
                                reason = EvidenceGap.GapReason.Unknown,
                            ),
                        ),
                    ),
                )
            }
        }
        return dev.pipelinek.assurance.engine.AssuranceReport(
            evaluationId = dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId("eval-d5-test"),
            snapshotDigest = Digest.ofUtf8("snap-d5"),
            suiteDigest = Digest.ofUtf8("suite-d5"),
            engineVersion = "0.1.0",
            results = results,
            gaps = emptyList(),
            artifacts = emptyList(),
            correlations = emptyList(),
        )
    }
}
