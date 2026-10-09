package dev.pipelinek.assurance.plugin

/**
 * M7 — `AssuranceVerifyStepAdapter`: el adapter del Step `assurance.verify`
 * para el host del SDK.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
 * §`assurance.verify` y `04-adrs/ADR-003-BODY-OWNING-RUNTIME-VERIFY.md`.
 *
 * **AAT-3, AAT-11:** el plugin es el único módulo del repo que
 * depende del SDK de PipelineK. La dependencia es en runtime via
 * ServiceLoader; el `StepDefinition` del SDK cumple su contrato.
 *
 * El `handler` del Step delega en `AssuranceVerifyStep.run` con
 * la matriz body × assurance documentada. La cancelación se
 * propaga como `Cancelled` (no como `Failure`), preservando la
 * semántica de control estructurado.
 */
class AssuranceVerifyStepAdapter {
    val key: String get() = AssuranceVerifyStep.STEP_KEY
}
