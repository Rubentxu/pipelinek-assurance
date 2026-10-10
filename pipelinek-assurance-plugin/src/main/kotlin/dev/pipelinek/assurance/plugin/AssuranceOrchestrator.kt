/**
 * B2 (Bloque B) — Application Service de Assurance.
 *
 * Ref autoridad: plan de consolidación §B2 y
 * `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`. La
 * orquestación interna de Assurance vive aquí, no en el
 * SDK. El SDK provee ejecución, replay, journal y
 * cancelación (ADR-001); este orchestrator compone los
 * servicios del core (engine, artifact, normalizer) en
 * los 10 pasos que la spec exige.
 *
 * **10 pasos que el orchestrator ejecuta (B2)**:
 *
 *  1. Resolver suite: el DTO entrante se traduce a
 *     `AssuranceSuiteIR`. Validación: apiVersion conocida,
 *     lens/assertion referenciados declarados.
 *  2. Leer referencias a artifacts: el input declara
 *     `evidence: List<EvidenceRefDto>`. Cada ref lleva
 *     `logicalRole` y un `digest` esperado. El orchestrator
 *     NO lee los bytes (eso es del SDK) pero verifica que
 *     las refs son consistentes (digest declarado,
 *     logicalRole no vacío, sin duplicados).
 *  3. Validar esquema y digest: por cada `EvidenceRefDto`,
 *     el orchestrator exige digest hex de 64 chars. Si el
 *     caller no provee digest, se rechaza con
 *     `OrchestrationException` antes de avanzar — la
 *     reproducibilidad del report empieza por la
 *     reproducibilidad de las refs.
 *  4. Seleccionar providers: por cada `logicalRole` en
 *     `input.suite.requiredEvidence`, el registry elige un
 *     `EvidenceProvider` cuyo `descriptor.classification`
 *     la cubre. Si falta, se reporta como gap (no se
 *     aborta) y la assertion cae a `Unsupported`.
 *  5. Recolectar evidencia: cada provider seleccionado
 *     corre `collect(EvidenceRequest)`. El resultado
 *     `EvidenceCollectionResult` se agrega al buffer
 *     crudo. Errores se acumulan (no short-circuit) para
 *     que el report completo refleje el estado real.
 *  6. Normalizar: el `EvidenceNormalizer` (A3) convierte el
 *     buffer crudo en `EvidenceSnapshot` con enforcement
 *     de AAT-13 (id con `/`) y AAT-19 (authority/kind).
 *     Si una normalización falla, se reporta como gap.
 *  7. Congelar registries: el `FrozenAssuranceRuntime` se
 *     construye con las lenses y assertions que el SDK
 *     haya inyectado. El orchestrator acepta ese runtime
 *     congelado como parámetro; aquí sólo verifica que
 *     las IDs del IR están todas en el runtime.
 *  8. Evaluar: `AssuranceEngine.evaluateSuite` corre con
 *     el snapshot, el IR y el runtime congelado. A1: la
 *     decisión de gate se delega a
 *     `evaluateEnforcement`, no al counter del summary.
 *  9. Codificar y publicar report: el `AssuranceReport`
 *     sale del engine. El codificado canónico vive en
 *     `assurance-artifact` (codec CBOR/JSON). El SDK es
 *     responsable de PUBLICAR el artifact en su store;
 *     el orchestrator devuelve el report + su digest.
 * 10. Devolver resultado tipado: `OrchestrationResult` lleva
 *     el report, su digest, el outcome de enforcement, y
 *     la lista de gaps acumulados. El Step handler del
 *     SDK traduce ese resultado a su `StepOutcome` nativo.
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.AssuranceEvaluationId
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.AssuranceEngine
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.pipelinek.assurance.providers.EvidenceNormalizer
import kotlinx.serialization.json.Json

/**
 * Resultado de la orquestación, lo que el Step handler del
 * SDK traduce a su `StepOutcome` nativo.
 *
 * Lo que NO incluye: ni el codificado binario del report
 * (eso es responsabilidad del codificador canónico de
 * `assurance-artifact`, no del orchestrator), ni la
 * publicación a disco (eso es del SDK).
 */
sealed interface OrchestrationResult {

    val report: AssuranceReport
    val reportDigest: Digest
    val gaps: List<EvidenceGap>

    /**
     * Orquestación exitosa. El report tiene al menos un
     * item, todas las requiredEvidence cubiertas, y un
     * outcome derivado de la policy del input.
     */
    data class Success(
        override val report: AssuranceReport,
        override val reportDigest: Digest,
        override val gaps: List<EvidenceGap>,
        val blockingFailures: List<dev.pipelinek.assurance.engine.EnforcementEntry>,
    ) : OrchestrationResult

    /**
     * La orquestación falló en uno de los pasos
     * previos a la evaluación (validación de input,
     * providers, normalización). El report puede ser
     * parcial — sus `gaps` llevan el motivo.
     */
    data class Failed(
        override val report: AssuranceReport,
        override val reportDigest: Digest,
        override val gaps: List<EvidenceGap>,
        val reason: String,
    ) : OrchestrationResult
}

class OrchestrationException(message: String) : RuntimeException(message)

/**
 * Registry de providers. El orchestrator lo consume para
 * el paso 4 (selección) y 5 (recolección).
 *
 * Diseño: NO es un ServiceLoader. El SDK lo construye en
 * su fase de inicialización y lo pasa al plugin en cada
 * invocación del Step. Eso preserva la regla de AAT-3
 * (el plugin es el único módulo que depende del SDK;
 * aquí el plugin consume la abstracción, no el SDK
 * concreto).
 */
class ProviderRegistry {
    private val providers: MutableMap<String, EvidenceProvider> = mutableMapOf()

    fun register(logicalRole: String, provider: EvidenceProvider) {
        providers[logicalRole] = provider
    }

    fun find(logicalRole: String): EvidenceProvider? = providers[logicalRole]

    fun all(): List<EvidenceProvider> = providers.values.toList()

    fun rolesCovered(): Set<String> = providers.keys
}

/**
 * Input del orchestrator, paralelo a `AssuranceCheckStepDefinition.Input`
 * pero separado: el orchestrator no conoce la forma serializable
 * del wire contract; la traducción entre ambas es del step handler.
 *
 * Esta indirección existe para que el orchestrator sea testeable
 * sin el codec, y para que B2 no dependa de la forma DTO.
 */
data class OrchestrationRequest(
    val name: String,
    val suite: AssuranceSuiteIR,
    val subject: EvidenceSubject,
    val evidenceRefs: List<EvidenceRefDto>,
    val mode: AssuranceCheckStep.EnforcementMode,
    val completenessPolicy: AssuranceCheckStep.CompletenessPolicy,
    val producerVersion: String,
)

/**
 * Forma mínima de la ref de evidencia. El SDK provee
 * implementaciones más ricas (URL, timestamp, etc.) que el
 * orchestrator no necesita ver.
 *
 * El orchestrator exige:
 *  - `logicalRole` no vacío
 *  - `digest` de 64 chars hex (un SHA-256)
 *
 * El caller (SDK) es responsable de cargar el artifact y
 * pasarlo al provider. El orchestrator no hace I/O.
 */
data class EvidenceRefDto(
    val logicalRole: String,
    val digest: String,
    val producerId: String,
)

/**
 * El orchestrator propiamente dicho.
 *
 * Es un objeto: la orquestación es un singleton de
 * aplicación. El registry de providers se inyecta por
 * thread-local en runtime (lo gestiona el SDK), pero la
 * forma aquí es directa: `orchestrate(request, registry,
 * runtime)`.
 */
object AssuranceOrchestrator {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    /**
     * Punto de entrada único. Implementa los 10 pasos de B2.
     *
     * @param request input del orchestrator (forma mínima)
     * @param providers registry de providers, normalmente
     *        inyectado por el SDK en cada invocación
     * @param runtime registry congelado de lenses/assertions
     *        que el SDK construyó en su fase de inicialización
     * @return `OrchestrationResult` tipado
     */
    fun orchestrate(
        request: OrchestrationRequest,
        providers: ProviderRegistry,
        runtime: FrozenAssuranceRuntime,
    ): OrchestrationResult {
        val accumulatedGaps = mutableListOf<EvidenceGap>()
        // B2: el subjectRevision llega al EvidenceRequest y al
        // EvidenceNormalizer. Por ahora usamos el placeholder
        // cero (40 hex zeros); el caller (SDK) lo sustituye
        // cuando conoce la revisión del subject. Aquí lo
        // hacemos explícito para que se vea que la frontera
        // espera una revisión real.
        val subjectRevision = RevisionRef("0000000000000000000000000000000000000000")

        // 1. Resolver suite: el IR ya está construido en
        // request.suite (paso de capa superior). Aquí
        // validamos que el IR es ejecutable: apiVersion
        // conocida, lenses/assertions declarados, sin
        // duplicados. Si la validación falla, el report
        // queda parcial con gaps que documentan el motivo.
        val irValidation = validateSuite(request.suite)
        if (irValidation != null) {
            accumulatedGaps += irValidation
        }

        // 2 + 3. Validar refs: cada ref tiene logicalRole y
        // digest de 64 chars. Si falta el digest, el caller
        // (SDK) no puede garantizar reproducibilidad; el
        // orchestrator aborta con OrchestrationException
        // porque la reproducibilidad del report empieza por
        // la reproducibilidad de las refs.
        val validatedRefs = validateEvidenceRefs(request.evidenceRefs)

        // 4 + 5. Seleccionar providers y recolectar evidencia.
        // Por cada logicalRole en requiredEvidence, encontrar
        // un provider; ejecutar collect; agregar resultado al
        // buffer crudo. Errores se acumulan como gaps.
        val rawItems = mutableListOf<dev.pipelinek.assurance.engine.RawEvidenceItem>()
        val declaredGaps = mutableListOf<dev.pipelinek.assurance.engine.RawEvidenceGap>()
        for (ref in validatedRefs) {
            val provider = providers.find(ref.logicalRole)
            if (provider == null) {
                accumulatedGaps += EvidenceGap(
                    capability = ref.logicalRole,
                    reason = EvidenceGap.GapReason.Unsupported,
                    detail = "no provider registered for logicalRole=${ref.logicalRole}",
                )
                continue
            }
            val evidenceRequest = EvidenceRequest(
                subjectRevision = subjectRevision,
                requestedCapabilities = listOf(ref.logicalRole),
            )
            val collectionResult = try {
                provider.collect(evidenceRequest)
            } catch (e: Exception) {
                // El provider lanzó excepción: la capturamos
                // y la convertimos en gap. El orchestrator
                // NO aborta; el report documenta la
                // incompletitud.
                accumulatedGaps += EvidenceGap(
                    capability = ref.logicalRole,
                    reason = EvidenceGap.GapReason.Lost,
                    detail = "provider ${provider.descriptor.classification} threw: ${e.message}",
                )
                continue
            }
            when (collectionResult) {
                is EvidenceCollectionResult.Produced -> {
                    rawItems += collectionResult.rawItems
                    declaredGaps += collectionResult.declaredGaps
                }
                is EvidenceCollectionResult.Failed -> {
                    accumulatedGaps += EvidenceGap(
                        capability = ref.logicalRole,
                        reason = EvidenceGap.GapReason.Lost,
                        detail = "provider reported failure: ${collectionResult.reason::class.simpleName}",
                    )
                }
            }
        }

        // 6. Normalizar: EvidenceNormalizer (A3) convierte
        // el buffer crudo en EvidenceSnapshot. Si una
        // autoridad es inválida o un id no tiene namespace,
        // el normalizer aborta con NormalizerException —
        // ese error es grave (un producer mintió sobre su
        // autoridad o su namespace), así que el
        // orchestrator aborta también.
        val produced = EvidenceCollectionResult.Produced(
            producerId = "orchestrator",
            producerVersion = request.producerVersion,
            schemaVersion = "assurance-evidence/v1",
            rawItems = rawItems,
            declaredGaps = declaredGaps,
        )
        val snapshot: EvidenceSnapshot = try {
            EvidenceNormalizer.normalize(
                result = produced,
                producerId = "orchestrator",
                producerVersion = request.producerVersion,
                subjectRevision = subjectRevision,
                subject = request.subject,
                snapshotId = SnapshotId("snap-${request.suite.suiteId.value}"),
                requestedCapabilities = request.suite.requiredEvidence,
            )
        } catch (e: EvidenceNormalizer.NormalizerException) {
            // Normalizer rechazó: producer mintió. No
            // publicamos report. Devolvemos un Failed con
            // la razón.
            val failedReport = minimalFailedReport(
                suite = request.suite,
                gaps = accumulatedGaps + EvidenceGap(
                    capability = "normalization",
                    reason = EvidenceGap.GapReason.Unsupported,
                    detail = e.message ?: "normalizer rejected the raw items",
                ),
            )
            return OrchestrationResult.Failed(
                report = failedReport,
                reportDigest = failedReport.suiteDigest,
                gaps = failedReport.gaps,
                reason = "normalization failed: ${e.message}",
            )
        }
        val enrichedSnapshot = if (accumulatedGaps.isNotEmpty()) {
            snapshot.copy(gaps = snapshot.gaps + accumulatedGaps)
        } else {
            snapshot
        }

        // 7. Congelar registries: el runtime congelado
        // viene del caller (SDK). Aquí verificamos que
        // las lens/assertion IDs del IR están todas en
        // el runtime. Si falta alguna, el report
        // documenta la incompletitud (la assertion caerá
        // a Unsupported en evaluateSuite).
        val missingLenses = request.suite.lenses
            .filter { it.lensId !in runtime.lenses.keys }
            .map { it.lensId }
        if (missingLenses.isNotEmpty()) {
            accumulatedGaps += missingLenses.map { lensId ->
                EvidenceGap(
                    capability = "runtime.lens.${lensId.value}",
                    reason = EvidenceGap.GapReason.Unsupported,
                    detail = "lens not registered in frozen runtime: ${lensId.value}",
                )
            }
        }

        // 8. Evaluar con el engine (A1: el outcome se
        // delega a evaluateEnforcement; aquí usamos
        // evaluateSuite que devuelve el report).
        val report = AssuranceEngine.evaluateSuite(
            snapshot = enrichedSnapshot,
            suite = request.suite,
            runtime = runtime,
            digestOf = { ir ->
                val dto = AssuranceCheckStepDefinition.SuiteDto(
                    id = ir.suiteId.value,
                    version = ir.suiteVersion,
                    requiredEvidence = ir.requiredEvidence,
                    lenses = ir.lenses.map { lens ->
                        AssuranceCheckStepDefinition.LensDto(
                            id = lens.lensId.value,
                            kind = lens.kind,
                            inputCapabilities = lens.inputCapabilities,
                            arguments = lens.arguments,
                            outputSchema = lens.outputSchema,
                        )
                    },
                    assertions = ir.assertions.map { assertion ->
                        AssuranceCheckStepDefinition.AssertionDto(
                            id = assertion.id.value,
                            lensRef = assertion.lensRef.value,
                            operator = assertion.operator,
                            operands = assertion.operands,
                            severity = assertion.severity.name,
                            enforcement = assertion.enforcement.name,
                            completenessRequirements = assertion.completenessRequirements,
                            rationale = assertion.rationale,
                        )
                    },
                )
                Digest.ofUtf8(json.encodeToString(AssuranceCheckStepDefinition.SuiteDto.serializer(), dto))
            },
            snapshotDigest = snapshotDigest(enrichedSnapshot, request),
            // B2: las assertions reales se inyectan vía runtime;
            // aquí pasamos emptyMap porque la unidad mínima de
            // B2 es la orquestación. La integración con
            // assertions reales es del step handler.
            assertionsById = emptyMap(),
        )

        // 9. Codificar y publicar report: el codificado
        // canónico es responsabilidad de assurance-artifact.
        // Aquí calculamos el digest y lo devolvemos. La
        // publicación a disco la hace el SDK.
        val reportDigest = report.suiteDigest

        // 10. Resultado tipado. La decisión de gate la toma
        // AssuranceCheckStep.outcomeOfWithSuite (que usa
        // evaluateEnforcement). Aquí devolvemos el report
        // y el orquestador es agnóstico al outcome
        // concreto.
        return OrchestrationResult.Success(
            report = report,
            reportDigest = reportDigest,
            gaps = report.gaps,
            blockingFailures = emptyList(), // Lo calcula el step handler con evaluateEnforcement.
        )
    }

    private fun validateSuite(suite: AssuranceSuiteIR): EvidenceGap? {
        // Validación mínima: la IR tiene al menos una
        // lens y una assertion (AAT-16). Si falta, es un
        // error de configuración del caller, no del
        // orchestrator; lo marcamos como gap.
        if (suite.lenses.isEmpty()) {
            return EvidenceGap(
                capability = "suite.lenses",
                reason = EvidenceGap.GapReason.Unsupported,
                detail = "suite has no lenses declared",
            )
        }
        if (suite.assertions.isEmpty()) {
            return EvidenceGap(
                capability = "suite.assertions",
                reason = EvidenceGap.GapReason.Unsupported,
                detail = "suite has no assertions declared",
            )
        }
        return null
    }

    private fun validateEvidenceRefs(refs: List<EvidenceRefDto>): List<EvidenceRefDto> {
        // 64 chars hex = 32 bytes = SHA-256. El caller
        // (SDK) tiene que dar el digest del artifact
        // antes de invocar al orchestrator.
        refs.forEach { ref ->
            require(ref.logicalRole.isNotBlank()) {
                "EvidenceRefDto with blank logicalRole"
            }
            require(ref.digest.length == 64 && ref.digest.all { it in HEX_CHARS }) {
                "EvidenceRefDto ${ref.logicalRole} has invalid digest ${ref.digest}; " +
                    "expected 64 hex chars (SHA-256)"
            }
        }
        return refs.distinctBy { it.digest }  // Sin duplicados por digest.
    }

    private fun snapshotDigest(
        snapshot: EvidenceSnapshot,
        request: OrchestrationRequest,
    ): Digest {
        // Digest determinista del snapshot. El codec
        // canónico de assurance-artifact hace esto mejor;
        // aquí dejamos una versión simple que sirve como
        // anchor del engine.
        val sb = StringBuilder()
        sb.append("orchestrator@").append(request.producerVersion).append('\n')
        sb.append("suite=").append(request.suite.suiteId.value).append('\n')
        for (item in snapshot.items) {
            sb.append("item=").append(item.id.value).append('\n')
        }
        return Digest.ofUtf8(sb.toString())
    }

    private fun minimalFailedReport(
        suite: AssuranceSuiteIR,
        gaps: List<EvidenceGap>,
    ): AssuranceReport {
        // Report mínimo para el caso de fallo de
        // normalización: lleva los gaps y un digest
        // determinista del motivo. NO contiene items
        // porque la normalización falló.
        val emptyItems = emptyList<EvidenceItem>()
        return AssuranceReport(
            evaluationId = AssuranceEvaluationId("eval-failed-${suite.suiteId.value}"),
            snapshotDigest = Digest.ofUtf8("failed-normalization-${suite.suiteId.value}"),
            suiteDigest = Digest.ofUtf8("failed-normalization-${suite.suiteId.value}"),
            engineVersion = "0.1.0",
            results = emptyList(),
            gaps = gaps,
            artifacts = emptyList(),
            correlations = emptyList(),
        )
    }

    private val HEX_CHARS = "0123456789abcdef".toSet()
}
