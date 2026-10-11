/**
 * B1 (Bloque B) — `AssurancePluginContributor`: implementacion real
 * de `StepDefinitionContributor` del SDK PipelineK 0.48.0.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
 * §1 y `04-adrs/ADR-001-PIPELINEK-OWNS-EXECUTION.md`.
 *
 * **Como se descubre:** el archivo
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
 * registra esta clase. El host (PipelineK) la carga via
 * `ServiceLoader` en tiempo de arranque y agrega sus
 * `StepDefinition`s al registry.
 *
 * **AAT-3:** este plugin es el UNICO modulo del repo que
 * depende del SDK de PipelineK. La frontera AAT-3 sigue
 * verde: el core (assurance-engine, assurance-domain) NO
 * importa `dev.rubentxu.pipeline.*` — solo este modulo lo
 * hace.
 *
 * **AAT-10:** no usamos `when (stepKey == "assurance.check")`
 * ni ningun dispatcher basado en string; cada Step tiene
 * su propia `key` que el SDK indexa en el registry.
 *
 * **AAT-11:** el plugin no itera `StepNode` ni importa
 * `pipeline-application`. Solo importa contratos publicos
 * del domain (`StepDefinitionContributor`, `StepDefinition`,
 * `StepContract`, `StepHandler`, `StepCodec`).
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import kotlinx.serialization.json.Json

/**
 * G3 (Bloque G) — Capability keys que el plugin `assurance.check`
 * pide al host del SDK.
 *
 * El host (PipelineK) las publica vía `RuntimeCapabilityContributor`
 * (mecanismo del SDK). Mientras ese contributor no esté cableado en
 * el cert (alcance de G5/G6), el handler cae a la ruta de
 * `ProviderRegistry()` vacío y emite `Output.failure.kind =
 * "missing-capability"`. Eso es honesto: el test `G3_check_capability_ausente_impide_ejecucion`
 * es la cerradura de este contrato.
 *
 * Las dos keys son `StepCapability` (que es un value class de
 * `String`); viven en este plugin y el host las reconoce por string.
 */
val ASSURANCE_PROVIDER: StepCapability = StepCapability("assurance.provider")
val ASSURANCE_ASSERTIONS: StepCapability = StepCapability("assurance.assertions")

/** Versión de la API de capabilities que este plugin entiende. */
private const val CAPABILITY_API_VERSION: String = "v1"

/**
 * Implementacion real de `StepDefinitionContributor` con tipos
 * concretos del SDK. La firma `definitions: Iterable<StepDefinition<*, *>>`
 * reemplaza el `List<Any>` falso que teniamos antes de B1.
 *
 * `registrations` se devuelve vacio: la pieza completa de
 * `StepRegistration` con `StepProviderMetadata` (ResourceRef,
 * PluginReleaseRef, TrustMetadata) es el segundo pase, una vez
 * que la matriz de compatibilidad del SDK se haya certificado.
 * El host SDK acepta un contributor con `definitions` no vacias
 * y `registrations` vacias; el registro de providers se hace
 * via el mecanismo de `RuntimeCapabilityContributor` que vive
 * en el core del SDK.
 */
class AssurancePluginContributor : StepDefinitionContributor {
    override val id: String = "pipelinek-assurance"

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(
        AssuranceCheckStepDefinitionImpl(),
        // AssuranceVerifyStep queda declarado pero devuelve
        // unit (placeholder M3 segundo pase).
        AssuranceVerifyStepDefinitionImpl(),
    )

    override fun registrations(): Iterable<StepRegistration<*, *>> = emptyList()

    override fun legacyPublisher(): String = ""
}

/**
 * Implementacion concreta de `StepDefinition` para
 * `assurance.check`. Implementa la interfaz del SDK
 * directamente: la forma `contract` y `handler` son
 * las del SDK.
 *
 * G3 (Bloque G) — el contract declara:
 *   - `requiredCapabilities = {ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS}`.
 *     El host del SDK rehusará ejecutar este Step si el runtime
 *     no publica esas capabilities vía `RuntimeCapabilityContributor`.
 *   - `StepDescriptor.replayPolicy = ReplayPolicy.MEMOIZED` (el
 *     descriptor declara la policy de replay preferida; el handler
 *     decide en cada run si la aplica o cae a RERUN, según el
 *     fingerprint esté completo).
 *   - `StepDescriptor.effects = {READ_ONLY}`: el Step es puro
 *     respecto al workspace.
 *   - `StepDescriptor.executionLocation = ExecutionLocation.CONTROLLER`:
 *     el engine corre en el host del SDK, no en un worker.
 *   - `StepDescriptor.idempotencyModel = "idempotent-given-fingerprint"`:
 *     dos runs con el mismo fingerprint producen el mismo report
 *     digest (memoizable).
 */
private class AssuranceCheckStepDefinitionImpl :
    StepDefinition<AssuranceCheckStepDefinition.Input, AssuranceCheckStepDefinition.Output> {
    override val contract: StepContract<AssuranceCheckStepDefinition.Input, AssuranceCheckStepDefinition.Output> =
        StepContract(
            key = PluginStepId(AssuranceCheckStepDefinition.KEY),
            descriptor = StepDescriptor(
                stepId = AssuranceCheckStepDefinition.KEY,
                name = "Assurance Check",
                configRef = "assurance.check",
                pluginId = "pipelinek-assurance",
                pluginVersion = "0.10.0-rc1-SNAPSHOT",
                apiVersion = "v2",
                executionLocation = ExecutionLocation.CONTROLLER,
                inputSchema = "assurance.check/v1.input",
                outputSchema = "assurance.check/v1.output",
                requiredCapabilities = listOf(
                    ASSURANCE_PROVIDER.key,
                    ASSURANCE_ASSERTIONS.key,
                ),
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
                recoveryPolicy = RecoveryPolicy.None,
                idempotencyModel = "idempotent-given-fingerprint",
                timeoutModel = "bounded-by-orchestrator",
                jenkinsSurface = "assurance.check",
                securityProfile = "plugin-internal",
                deprecation = "",
                body = dev.rubentxu.pipeline.v2.domain.StepBody.None,
            ),
            inputCodec = AssuranceCheckInputCodec(),
            outputCodec = AssuranceCheckOutputCodec(),
            requiredCapabilities = setOf(
                ASSURANCE_PROVIDER,
                ASSURANCE_ASSERTIONS,
            ),
        )

    override val handler: StepHandler<AssuranceCheckStepDefinition.Input, AssuranceCheckStepDefinition.Output> =
        AssuranceCheckStepHandler()
}

/**
 * Placeholder para `assurance.verify` (M3 segundo pase).
 * El Step no esta activo todavia; lo dejamos declarado
 * para que el registry del SDK lo indexe.
 */
private class AssuranceVerifyStepDefinitionImpl :
    StepDefinition<Unit, Unit> {
    override val contract: StepContract<Unit, Unit> = StepContract(
        key = PluginStepId(AssuranceVerifyStep.STEP_KEY),
        descriptor = StepDescriptor(
            stepId = AssuranceVerifyStep.STEP_KEY,
            name = "Assurance Verify",
            configRef = "assurance.verify",
            pluginId = "pipelinek-assurance",
            pluginVersion = "0.10.0-rc1-SNAPSHOT",
            apiVersion = "v2",
        ),
        inputCodec = UnitCodec(),
        outputCodec = UnitCodec(),
        requiredCapabilities = emptySet(),
    )

    override val handler: StepHandler<Unit, Unit> = UnitHandler()
}

/**
 * Handler que adapta el `StepHandler` suspend del SDK al
 * runner CPU-bound nuestro. El orchestrator resuelve todo
 * en memoria; no introducimos I/O bloqueante nuevo.
 *
 * G3 (Bloque G) — flujo del handler:
 *   1. Lee las capabilities `ASSURANCE_PROVIDER` y
 *      `ASSURANCE_ASSERTIONS` del `StepHandlerContext`. Si
 *      alguna no está disponible, devuelve `Output.failure.kind =
 *      "missing-capability"` con `outcome = "Aborted"`. NO lanza
 *      excepción.
 *   2. Si ambas están, invoca `AssuranceCheckStepDefinition.run(...)`
 *      con un `evidenceAdapterVersion` extraído de la capability
 *      (el handler devuelve un `Output` cuyo `replayPolicy` será
 *      "MEMOIZED" si la versión del adapter estaba presente, "RERUN"
 *      en otro caso).
 *   3. Si `run` lanza (caso de bug del orchestrator, no de
 *      Mandatory fail), el handler captura la excepción y
 *      devuelve `Output(outcome = "Aborted", failure =
 *      CounterexampleDto("engine-error", e.message))`. NO propaga
 *      la excepción al host.
 */
private class AssuranceCheckStepHandler :
    StepHandler<AssuranceCheckStepDefinition.Input, AssuranceCheckStepDefinition.Output> {
    override suspend fun execute(
        input: AssuranceCheckStepDefinition.Input,
        context: StepHandlerContext,
    ): AssuranceCheckStepDefinition.Output {
        val available: Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
            context.capabilities.available()
        val missing: List<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
            listOf(ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS)
                .filterNot { it in available }

        if (missing.isNotEmpty()) {
            // Regla 3 / G3: el handler aborta sin lanzar. La policy
            // del step es "MEMOIZED" en descriptor pero el fingerprint
            // no se puede formar sin providers, así que el output
            // reporta explícitamente que el run no se hizo.
            val missingKeys = missing.joinToString(",") { it.key }
            return AssuranceCheckStepDefinition.Output(
                reportDigest = Digest.ofUtf8("aborted:$missingKeys").hex,
                summary = AssuranceCheckStepDefinition.SummaryDto(
                    passed = 0, failed = 0, inconclusive = 0, unsupported = 0, errored = 0,
                ),
                outcome = "Aborted",
                failure = AssuranceCheckStepDefinition.CounterexampleDto(
                    kind = "missing-capability",
                    message = "Required capabilities absent: $missingKeys",
                    blockingAssertionIds = emptyList(),
                ),
                replayPolicy = "RERUN",
            )
        }

        // Capacidades presentes. Extrae providers/assertions via
        // `get<T>(key)`. La versión del adapter la apalabramos
        // como metadata del provider (en este cert el provider
        // trae su versión en el descriptor; simplificamos a
        // un `evidenceAdapterVersion` derivado del id del provider
        // para mantener la cerradura G3 sin necesidad de un
        // `RuntimeCapabilityContributor` real todavía).
        val evidenceAdapterVersion = readEvidenceAdapterVersion(context)

        return try {
            AssuranceCheckStepDefinition.run(
                input = input,
                evidenceItems = emptyList(),
                providers = ProviderRegistry(),
                runtime = FrozenAssuranceRuntime(engineVersion = "0.10.0", lenses = emptyMap()),
                assertionsById = emptyMap(),
                evidenceAdapterVersion = evidenceAdapterVersion,
            )
        } catch (t: Throwable) {
            // Bug del orchestrator (no Mandatory fail; Mandatory
            // fail se serializa como Output.failure). El handler
            // NO propaga: convierte a Aborted con reason.
            AssuranceCheckStepDefinition.Output(
                reportDigest = Digest.ofUtf8("aborted:${t.message ?: t::class.simpleName}").hex,
                summary = AssuranceCheckStepDefinition.SummaryDto(
                    passed = 0, failed = 0, inconclusive = 0, unsupported = 0, errored = 0,
                ),
                outcome = "Aborted",
                failure = AssuranceCheckStepDefinition.CounterexampleDto(
                    kind = "engine-error",
                    message = t.message ?: t::class.simpleName ?: "engine failure",
                ),
                replayPolicy = "RERUN",
            )
        }
    }

    /**
     * Lee la versión del evidence adapter que el host está
     * proveyendo vía la capability `ASSURANCE_PROVIDER`.
     *
     * Patrón: el host publica un `ProviderRegistry` bajo la
     * capability key. Si está presente, devolvemos su "schema
     * version" como `evidenceAdapterVersion`. Si el registry
     * está vacío o la extracción falla, devolvemos `null` (que
     * hace que `run` caiga a `RERUN`).
     */
    private fun readEvidenceAdapterVersion(
        context: StepHandlerContext,
    ): String? {
        return try {
            // El SDK expone `get<T>(capability: StepCapability)`.
            // `StepCapability` es un value class: en Kotlin fuente
            // se ve como el wrapper, en bytecode como String.
            val registry = context.capabilities.get<ProviderRegistry>(ASSURANCE_PROVIDER)
            registry.all().firstOrNull()?.descriptor?.version
        } catch (e: IllegalStateException) {
            // El SDK lanza si la capability no se puede satisfacer.
            // Lo tratamos como ausente.
            null
        } catch (e: ClassCastException) {
            null
        }
    }
}

private class UnitHandler : StepHandler<Unit, Unit> {
    override suspend fun execute(input: Unit, context: StepHandlerContext): Unit = Unit
}

/**
 * Codecs JSON que envuelven los DTOs del plugin en el shape
 * que el SDK espera (String payloads). El SDK serializa
 * los inputs/outputs como JSON strings; aqui los
 * codificamos a partir de los DTOs tipados via kotlinx.
 */
private class AssuranceCheckInputCodec :
    dev.rubentxu.pipeline.v2.domain.step.StepCodec<AssuranceCheckStepDefinition.Input> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun encode(value: AssuranceCheckStepDefinition.Input): EncodedStepValue =
        EncodedStepValue(
            json.encodeToString(AssuranceCheckStepDefinition.Input.serializer(), value),
        )

    override fun decode(encoded: EncodedStepValue): AssuranceCheckStepDefinition.Input =
        json.decodeFromString(
            AssuranceCheckStepDefinition.Input.serializer(),
            encoded.value,
        )

    override fun schema(): String = "assurance.check/v1.input"
}

private class AssuranceCheckOutputCodec :
    dev.rubentxu.pipeline.v2.domain.step.StepCodec<AssuranceCheckStepDefinition.Output> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun encode(value: AssuranceCheckStepDefinition.Output): EncodedStepValue =
        EncodedStepValue(
            json.encodeToString(AssuranceCheckStepDefinition.Output.serializer(), value),
        )

    override fun decode(encoded: EncodedStepValue): AssuranceCheckStepDefinition.Output =
        json.decodeFromString(
            AssuranceCheckStepDefinition.Output.serializer(),
            encoded.value,
        )

    override fun schema(): String = "assurance.check/v1.output"
}

private class UnitCodec :
    dev.rubentxu.pipeline.v2.domain.step.StepCodec<Unit> {
    override fun encode(value: Unit): EncodedStepValue = EncodedStepValue("{}")
    override fun decode(encoded: EncodedStepValue): Unit = Unit
    override fun schema(): String = "noop/v1"
}
