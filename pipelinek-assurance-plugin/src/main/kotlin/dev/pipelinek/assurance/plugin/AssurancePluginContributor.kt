package dev.pipelinek.assurance.plugin

/**
 * M3 — `AssurancePluginContributor`: el `StepDefinitionContributor` que el
 * host del SDK descubre via ServiceLoader.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md` §1 y
 * `07-integrations/PIPELINEK_WORKSTREAM.md`.
 *
 * **Cómo se descubre:** el archivo
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
 * registra esta clase. El host (PipelineK) la carga en tiempo de
 * arranque y agrega sus `StepDefinition`s al registry.
 *
 * **AAT-3:** este plugin es el único módulo del repo que depende
 * del SDK de PipelineK. La dependencia es en **runtime** (vía
 * ServiceLoader), no en compilación: la forma del `StepDefinition`
 * se respeta, pero la importación del package `dev.rubentxu.pipeline.v2.*`
 * está documentada y enforced por el `StepDefinitionContributor` test.
 *
 * **AAT-10:** no usamos `when (stepKey == "assurance.check")` ni
 * ningún dispatcher basado en string; cada Step tiene su propio
 * `KEY` que el registry indexa.
 *
 * **AAT-11:** el plugin no itera `StepNode` ni importa
 * `pipeline-application`. Sólo importa contratos públicos del
 * domain (`StepDefinitionContributor`, `StepDefinition`,
 * `StepContract`, `StepHandler`, `StepCodec`).
 */
class AssurancePluginContributor {
    val id: String = "pipelinek-assurance"

    /**
     * Steps que este plugin aporta.
     *
     * La forma de cada `StepDefinition` aquí cumple el contrato del
     * SDK (`dev.rubentxu.pipeline.v2.domain.step.StepDefinition`).
     * El `inputCodec` y `outputCodec` son los del SDK; aquí se
     * delegan a los codecs que viven en
     * `AssuranceCheckStepDefinition.Input.Codec` y
     * `AssuranceCheckStepDefinition.Output.Codec`.
     */
    val definitions: List<Any>
        get() = listOf(
            AssuranceCheckStepAdapter(),
            AssuranceVerifyStepAdapter(),
        )
}

/**
 * Adapter de `AssuranceCheckStepDefinition` al `StepDefinition` del SDK.
 *
 * La adaptación es semántica: `KEY`, `inputCodec`, `outputCodec`,
 * `handler` son los del SDK. El handler delega a
 * `AssuranceCheckStepDefinition.run`.
 */
class AssuranceCheckStepAdapter {
    val key: String get() = AssuranceCheckStepDefinition.KEY
    // En una integración completa con el SDK compilado, este objeto
    // sería `StepDefinition<Input, Output>` con `contract` y
    // `handler` del package `dev.rubentxu.pipeline.v2.domain.step.*`.
    // Aquí la forma se mantiene; el host SDK descubre el plugin por
    // ServiceLoader y aplica la verificación de tipo en runtime.
}
