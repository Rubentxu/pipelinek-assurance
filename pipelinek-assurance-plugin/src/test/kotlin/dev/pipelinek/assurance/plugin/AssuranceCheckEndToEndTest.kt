package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.architecture.Layer
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * B3 (Bloque B) — Tests E2E del Step `assurance.check` real.
 *
 * El plan B3 dice:
 *   - Sustituir el runtime vacío de
 *     `AssuranceCheckStepDefinition.run`.
 *   - Verificar que ejecuta las lenses y assertions registradas,
 *     no sólo que construye DTOs.
 *   - Crear un ejemplo reproducible: grafo correcto → Success
 *     con pruebas positivas; dependencia prohibida → Failure
 *     con counterexample, source refs y report persistido.
 *
 * Lo que se hace aquí:
 *   1. `e2e_grafo_correcto_es_Success` — un grafo sin aristas
 *      prohibidas pasa el gate.
 *   2. `e2e_dependencia_prohibida_es_Failure_con_counterexample`
 *      — un grafo con Domain→Adapters produce Failure con el
 *      counterexample de la arista prohibida.
 *   3. `e2e_sin_evidence_produce_Inconclusive` — sin Fact items
 *      la projection falla y la assertion cae a Inconclusive.
 *   4. `e2e_modo_ReportOnly_degrada_Failure_a_Success` — el
 *      modo ReportOnly (B3) convierte el Mandatory fail en
 *      Success (preserva el report pero no aborta el pipeline).
 */
class AssuranceCheckEndToEndTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // Provider fake: devuelve Fact items para uno o más grafos
    // -----------------------------------------------------------------

    private fun graphProvider(
        vararg modules: Triple<String, Layer, List<String>>,
    ): EvidenceProvider = object : EvidenceProvider {
        override val descriptor: EvidenceProviderDescriptor =
            EvidenceProviderDescriptor(
                id = "test/graph",
                version = "0.1.0",
                evidenceCapabilities = listOf("architecture.dependency-graph"),
                subjectKinds = listOf("Module"),
                classification = ProviderClassification.Deterministic,
                inputFormats = listOf("assurance-evidence/v1"),
                outputSchemaVersion = "assurance-evidence/v1",
            )
        override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
            val items = modules.map { (name, _, deps) ->
                dev.pipelinek.assurance.engine.RawEvidenceItem(
                    kind = dev.pipelinek.assurance.engine.RawItemKind.Fact,
                    id = "cognicode/$name/dependsOn",
                    subjectRef = "module:$name",
                    authority = "DeterministicAdapter",
                    payload = mapOf(
                        "predicate" to "dependsOn",
                        "object" to deps.joinToString(","),
                        "capability" to "architecture.dependency-graph",
                    ),
                )
            }
            return EvidenceCollectionResult.Produced(
                producerId = "test/graph",
                producerVersion = "0.1.0",
                schemaVersion = "assurance-evidence/v1",
                rawItems = items,
                declaredGaps = emptyList(),
            )
        }
    }

    // -----------------------------------------------------------------
    // 1. Grafo correcto (sin aristas prohibidas) → Success
    // -----------------------------------------------------------------

    @Test
    fun e2e_grafo_correcto_es_Success() {
        // Grafo correcto: domain NO tiene aristas salientes
        // hacia capas externas. application y adapters apuntan
        // al domain; infrastructure apunta a adapters. Todas
        // las aristas respetan la regla hexagonal.
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                graphProvider(
                    Triple("domain", Layer.Domain, listOf()),
                    Triple("application", Layer.Application, listOf("domain")),
                    Triple("adapters", Layer.Adapters, listOf("domain")),
                    Triple("infrastructure", Layer.Infrastructure, listOf("adapters")),
                ),
            )
        }
        val request = OrchestrationRequest(
            name = "self",
            suite = standardSuite(),
            subject = EvidenceSubject.Module("self"),
            evidenceRefs = listOf(
                EvidenceRefDto(
                    logicalRole = "architecture.dependency-graph",
                    digest = "a".repeat(64),
                    producerId = "test/graph",
                ),
            ),
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
        val runtime = standardRuntime()
        val result = AssuranceOrchestrator.orchestrate(request, registry, runtime, standardAssertions())
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        // El outcome: la assertion corrió y pasó.
        val decision = AssuranceCheckStep.outcomeOfWithSuite(
            report = success.report,
            suite = request.suite,
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        decision.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Success>()
    }

    // -----------------------------------------------------------------
    // 2. Dependencia prohibida (Domain → Adapters) → Failure con counterexample
    // -----------------------------------------------------------------

    @Test
    fun e2e_dependencia_prohibida_es_Failure_con_counterexample() {
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                graphProvider(
                    Triple("domain", Layer.Domain, listOf("adapters")),
                    Triple("adapters", Layer.Adapters, listOf()),
                ),
            )
        }
        val request = OrchestrationRequest(
            name = "self",
            suite = standardSuite(),
            subject = EvidenceSubject.Module("self"),
            evidenceRefs = listOf(
                EvidenceRefDto(
                    logicalRole = "architecture.dependency-graph",
                    digest = "b".repeat(64),
                    producerId = "test/graph",
                ),
            ),
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
        val runtime = standardRuntime()
        val result = AssuranceOrchestrator.orchestrate(request, registry, runtime, standardAssertions())
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        // La assertion corrió y falló.
        val failed = success.report.results[0]
        failed.shouldBeInstanceOf<AssertionResult.Failed>()
        val counterexample = failed.counterexample
        counterexample.explanation shouldContain "domain"
        counterexample.explanation shouldContain "adapters"
        // El outcome: gate failure porque la Mandatory falló.
        val decision = AssuranceCheckStep.outcomeOfWithSuite(
            report = success.report,
            suite = request.suite,
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        val failure = decision.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Failure>()
        failure.reason shouldContain "Mandatory"
    }

    // -----------------------------------------------------------------
    // 3. Sin Fact items → Inconclusive (no PASS por incompletitud)
    // -----------------------------------------------------------------

    @Test
    fun e2e_sin_evidence_produce_Inconclusive() {
        // Provider vacío: retorna 0 Fact items. La projection
        // falla con MissingCapability; la assertion cae a
        // Inconclusive. Gate no debe ser Success.
        val emptyProvider = object : EvidenceProvider {
            override val descriptor: EvidenceProviderDescriptor =
                EvidenceProviderDescriptor(
                    id = "test/empty",
                    version = "0.1.0",
                    evidenceCapabilities = listOf("architecture.dependency-graph"),
                    subjectKinds = listOf("Module"),
                    classification = ProviderClassification.Deterministic,
                    inputFormats = listOf("assurance-evidence/v1"),
                    outputSchemaVersion = "assurance-evidence/v1",
                )
            override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
                EvidenceCollectionResult.Produced(
                    producerId = "test/empty",
                    producerVersion = "0.1.0",
                    schemaVersion = "assurance-evidence/v1",
                    rawItems = emptyList(),
                    declaredGaps = emptyList(),
                )
        }
        val registry = ProviderRegistry().apply {
            register("architecture.dependency-graph", emptyProvider)
        }
        val request = OrchestrationRequest(
            name = "self",
            suite = standardSuite(),
            subject = EvidenceSubject.Module("self"),
            evidenceRefs = listOf(
                EvidenceRefDto(
                    logicalRole = "architecture.dependency-graph",
                    digest = "c".repeat(64),
                    producerId = "test/empty",
                ),
            ),
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
        val result = AssuranceOrchestrator.orchestrate(
            request = request,
            providers = registry,
            runtime = standardRuntime(),
            assertionsById = standardAssertions(),
        )
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        // La projection falló, así que la assertion cae a
        // Inconclusive (no Failed, no Passed).
        val inconclusive = success.report.results[0]
        inconclusive.shouldBeInstanceOf<AssertionResult.Inconclusive>()
        // Y el gate es Failure por incompletitud bajo
        // RequireComplete.
        val decision = AssuranceCheckStep.outcomeOfWithSuite(
            report = success.report,
            suite = request.suite,
            mode = AssuranceCheckStep.EnforcementMode.FailClosed,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        decision.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Failure>()
    }

    // -----------------------------------------------------------------
    // 4. Modo ReportOnly degrada Mandatory fail a Success
    // -----------------------------------------------------------------

    @Test
    fun e2e_modo_ReportOnly_degrada_Failure_a_Success() {
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                graphProvider(
                    Triple("domain", Layer.Domain, listOf("adapters")),
                ),
            )
        }
        val request = OrchestrationRequest(
            name = "self",
            suite = standardSuite(),
            subject = EvidenceSubject.Module("self"),
            evidenceRefs = listOf(
                EvidenceRefDto(
                    logicalRole = "architecture.dependency-graph",
                    digest = "d".repeat(64),
                    producerId = "test/graph",
                ),
            ),
            // Modo ReportOnly: el Mandatory fail no aborta.
            mode = AssuranceCheckStep.EnforcementMode.ReportOnly,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
            producerVersion = "0.1.0",
        )
        val result = AssuranceOrchestrator.orchestrate(request, registry, standardRuntime(), standardAssertions())
        val success = result.shouldBeInstanceOf<OrchestrationResult.Success>()
        val decision = AssuranceCheckStep.outcomeOfWithSuite(
            report = success.report,
            suite = request.suite,
            mode = AssuranceCheckStep.EnforcementMode.ReportOnly,
            completenessPolicy = AssuranceCheckStep.CompletenessPolicy.RequireComplete,
        )
        decision.shouldBeInstanceOf<AssuranceCheckStep.StepOutcome.Success>()
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun standardSuite(): dev.pipelinek.assurance.engine.AssuranceSuiteIR =
        dev.pipelinek.assurance.engine.AssuranceSuiteIR(
            apiVersion = "assurance-ir/v1",
            suiteId = dev.pipelinek.assurance.engine.SuiteId("e2e"),
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
                    rationale = "regla hexagonal: domain no puede depender de capas externas",
                ),
            ),
        )

    private fun standardRuntime(): dev.pipelinek.assurance.engine.FrozenAssuranceRuntime =
        dev.pipelinek.assurance.engine.FrozenAssuranceRuntime(
            engineVersion = "0.1.0",
            lenses = mapOf(
                dev.pipelinek.assurance.engine.LensId("l/builtin") to BuiltinLens,
            ),
        )

    private fun standardAssertions(): Map<dev.pipelinek.assurance.engine.AssertionId, dev.pipelinek.assurance.engine.AssuranceAssertion<*>> =
        mapOf(
            dev.pipelinek.assurance.engine.AssertionId("a/no-domain-to-external") to BuiltinAssertion(),
        )

    // -----------------------------------------------------------------
    // 5. Step handler wired (B3) — el `run` del Step
    //    ejecuta el orchestrator, no sólo construye DTOs.
    //
    //    Plan B3: "Sustituir el runtime vacío de
    //    AssuranceCheckStepDefinition.run. Verificar que ejecuta
    //    las lenses y assertions registradas, no sólo que
    //    construye DTOs."
    //
    //    Estos tests ejercitan el overload
    //    `run(input, evidenceItems, providers, runtime,
    //    assertionsById)` con BuiltinLens y BuiltinAssertion.
    // -----------------------------------------------------------------

    @Test
    fun e2e_step_handler_grafo_correcto_devuelve_Success() {
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                graphProvider(
                    Triple("domain", Layer.Domain, listOf()),
                    Triple("application", Layer.Application, listOf("domain")),
                    Triple("adapters", Layer.Adapters, listOf("domain")),
                ),
            )
        }
        val runtime = standardRuntime()
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = standardSuite().toSuiteDto(),
            evidence = listOf(
                AssuranceCheckStepDefinition.EvidenceRefDto(
                    mediaType = "assurance-evidence/v1",
                    digest = "a".repeat(64),
                    logicalRole = "architecture.dependency-graph",
                ),
            ),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val output = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = registry,
            runtime = runtime,
            assertionsById = standardAssertions(),
        )
        output.outcome shouldBe "Success"
    }

    @Test
    fun e2e_step_handler_dependencia_prohibida_devuelve_Failure() {
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                graphProvider(
                    Triple("domain", Layer.Domain, listOf("adapters")),
                ),
            )
        }
        val runtime = standardRuntime()
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = standardSuite().toSuiteDto(),
            evidence = listOf(
                AssuranceCheckStepDefinition.EvidenceRefDto(
                    mediaType = "assurance-evidence/v1",
                    digest = "b".repeat(64),
                    logicalRole = "architecture.dependency-graph",
                ),
            ),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val output = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = registry,
            runtime = runtime,
            assertionsById = standardAssertions(),
        )
        output.outcome shouldBe "Failure"
    }

    @Test
    fun e2e_step_handler_sin_evidence_devuelve_Failure_por_incomplete() {
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                object : dev.pipelinek.assurance.engine.EvidenceProvider {
                    override val descriptor = dev.pipelinek.assurance.engine.EvidenceProviderDescriptor(
                        id = "test/empty",
                        version = "0.1.0",
                        evidenceCapabilities = listOf("architecture.dependency-graph"),
                        subjectKinds = listOf("Module"),
                        classification = ProviderClassification.Deterministic,
                        inputFormats = listOf("assurance-evidence/v1"),
                        outputSchemaVersion = "assurance-evidence/v1",
                    )
                    override fun collect(request: EvidenceRequest): EvidenceCollectionResult =
                        EvidenceCollectionResult.Produced(
                            producerId = "test/empty",
                            producerVersion = "0.1.0",
                            schemaVersion = "assurance-evidence/v1",
                            rawItems = emptyList(),
                            declaredGaps = emptyList(),
                        )
                },
            )
        }
        val runtime = standardRuntime()
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = standardSuite().toSuiteDto(),
            evidence = listOf(
                AssuranceCheckStepDefinition.EvidenceRefDto(
                    mediaType = "assurance-evidence/v1",
                    digest = "c".repeat(64),
                    logicalRole = "architecture.dependency-graph",
                ),
            ),
            mode = "FailClosed",
            completenessPolicy = "RequireComplete",
        )
        val output = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = registry,
            runtime = runtime,
            assertionsById = standardAssertions(),
        )
        output.outcome shouldBe "Failure"
    }

    @Test
    fun e2e_step_handler_ReportOnly_degrada_Failure_a_Success() {
        val registry = ProviderRegistry().apply {
            register(
                "architecture.dependency-graph",
                graphProvider(
                    Triple("domain", Layer.Domain, listOf("adapters")),
                ),
            )
        }
        val runtime = standardRuntime()
        val input = AssuranceCheckStepDefinition.Input(
            name = "self",
            suite = standardSuite().toSuiteDto(),
            evidence = listOf(
                AssuranceCheckStepDefinition.EvidenceRefDto(
                    mediaType = "assurance-evidence/v1",
                    digest = "d".repeat(64),
                    logicalRole = "architecture.dependency-graph",
                ),
            ),
            mode = "ReportOnly",
            completenessPolicy = "RequireComplete",
        )
        val output = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
            providers = registry,
            runtime = runtime,
            assertionsById = standardAssertions(),
        )
        output.outcome shouldBe "Success"
    }

    private fun dev.pipelinek.assurance.engine.AssuranceSuiteIR.toSuiteDto(): AssuranceCheckStepDefinition.SuiteDto =
        AssuranceCheckStepDefinition.SuiteDto(
            id = suiteId.value,
            version = suiteVersion,
            requiredEvidence = requiredEvidence,
            lenses = lenses.map {
                AssuranceCheckStepDefinition.LensDto(
                    id = it.lensId.value,
                    kind = it.kind,
                    inputCapabilities = it.inputCapabilities,
                    arguments = it.arguments,
                    outputSchema = it.outputSchema,
                )
            },
            assertions = assertions.map {
                AssuranceCheckStepDefinition.AssertionDto(
                    id = it.id.value,
                    lensRef = it.lensRef.value,
                    operator = it.operator,
                    operands = it.operands,
                    severity = it.severity.name,
                    enforcement = it.enforcement.name,
                    completenessRequirements = it.completenessRequirements,
                    rationale = it.rationale,
                )
            },
        )
}
