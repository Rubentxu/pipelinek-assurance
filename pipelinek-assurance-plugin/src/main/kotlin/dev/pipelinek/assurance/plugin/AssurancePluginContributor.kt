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

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import kotlinx.serialization.json.Json

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
            ),
            inputCodec = AssuranceCheckInputCodec(),
            outputCodec = AssuranceCheckOutputCodec(),
            requiredCapabilities = emptySet(),
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
 */
private class AssuranceCheckStepHandler :
    StepHandler<AssuranceCheckStepDefinition.Input, AssuranceCheckStepDefinition.Output> {
    override suspend fun execute(
        input: AssuranceCheckStepDefinition.Input,
        context: StepHandlerContext,
    ): AssuranceCheckStepDefinition.Output =
        AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
        )
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

    override fun decode(payload: EncodedStepValue): AssuranceCheckStepDefinition.Input =
        json.decodeFromString(
            AssuranceCheckStepDefinition.Input.serializer(),
            payload.value,
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

    override fun decode(payload: EncodedStepValue): AssuranceCheckStepDefinition.Output =
        json.decodeFromString(
            AssuranceCheckStepDefinition.Output.serializer(),
            payload.value,
        )

    override fun schema(): String = "assurance.check/v1.output"
}

private class UnitCodec :
    dev.rubentxu.pipeline.v2.domain.step.StepCodec<Unit> {
    override fun encode(value: Unit): EncodedStepValue = EncodedStepValue("{}")
    override fun decode(payload: EncodedStepValue): Unit = Unit
    override fun schema(): String = "noop/v1"
}
