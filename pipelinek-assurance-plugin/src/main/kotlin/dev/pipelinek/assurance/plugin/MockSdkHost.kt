/**
 * M11.8 — Mock SDK host para tests E2E del plugin sin SDK real.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
 * (forma del Step), `07-integrations/PIPELINEK_WORKSTREAM.md` H1..
 *
 * **Por qué este archivo existe:** el plugin `assurance.check` se
 * integra con el SDK de PipelineK por ServiceLoader en runtime, no
 * en compilación. El SDK real (`dev.pipelinek:pipelinek-sdk:v2`)
 * no está disponible en este repositorio (M3 depende de un
 * artefacto externo). Sin el SDK, los tests existentes cubren la
 * lógica del Step (`AssuranceCheckStep.outcomeOf`,
 * `AssuranceVerifyStepAdapter`) pero NO cubren el ciclo:
 *
 *   caller → SDK → plugin.step.run → SDK → caller
 *
 * Este mock implementa la **forma del SDK host** que el plugin
 * espera (el trait que el SDK real expone al plugin para pedirle
 * el `StepDefinition`): un `StepDefinitionContributor` con
 * `contribute()`, un `StepDefinition` con `key` y `run`, y un
 * `StepContext` que permite al plugin leer evidence.
 *
 * **Lo que el mock NO simula:** el ciclo de vida completo del
 * SDK (replay, journal, manifest resolution). Sólo la SHAPE del
 * step runner. Los tests que lo usan ejercitan el plugin de
 * punta a punta, sin tocar el SDK real.
 *
 * **Por qué `internal` y no `public`:** la API del mock es de
 * TEST. Exportarlo como `public` invitaría a callers reales a
 * usarlo, y el mock no es un SDK — es un stub de test.
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssuranceEngine
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.FrozenAssuranceRuntime
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.SuiteId
import kotlinx.serialization.json.Json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * SHAPE mínima del SDK que el plugin necesita. Mapea, sin
 * pretender ser exhaustivo, los tipos que
 * `dev.rubentxu.pipeline.v2.domain.step.StepDefinition` declara
 * en el SDK real.
 *
 * Por qué existe como interface aquí: el plugin no depende del
 * SDK en compilación (AAT-3). Lo que sí hace es esperar UNA
 * FORMA — y esa forma es lo que este archivo documenta y el
 * mock implementa. Cuando el SDK real esté disponible, el mock
 * se reemplaza por un adapter al SDK real, y la shape queda
 * validada por los tests que ya escribimos contra el mock.
 */
internal interface MockStepDefinition<I, O> {
    val key: String
    fun run(input: I, context: MockStepContext): O
}

/**
 * Contexto que el SDK pasa al step. Aquí es in-memory: el
 * `evidenceItems` los pasa el test, y el `loadSnapshot` se
 * satisface con un snapshot pre-construido.
 */
internal data class MockStepContext(
    val evidenceItems: List<EvidenceItem>,
)

/**
 * Mock del SDK host: ejecuta un `MockStepDefinition` con un
 * `MockStepContext` y devuelve el output. La salida del mock
 * ES el output del step — sin transformaciones del SDK.
 *
 * Por qué un `runStep` explícito y no un `eval`: la spec del
 * SDK real distingue el "caller invoca step" del "SDK ejecuta
 * step"; el mock respeta esa separación para que cuando el SDK
 * real se conecte, la sustitución sea 1:1.
 */
internal object MockSdkHost {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Ejecuta `AssuranceCheckStepDefinition.run` con un contexto
     * mock. Devuelve el output del Step.
     */
    fun runCheck(input: AssuranceCheckStepDefinition.Input): AssuranceCheckStepDefinition.Output {
        // M11.8: en lugar de instanciar el StepDefinition del SDK
        // real (que no existe en este repo), invocamos directamente
        // la función del plugin. Es lo que el SDK haría: serializar
        // input → dispatch por key → llamar al handler → serializar
        // output. Acortamos el dispatch porque la lógica del
        // handler es lo que queremos testear.
        val context = MockStepContext(evidenceItems = emptyList())
        val output = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = context.evidenceItems,
        )
        return output
    }

    /**
     * Ejecuta `AssuranceVerifyStepAdapter.run` con un contexto
     * mock. Devuelve el output del Step.
     */
    fun runVerify(
        evidenceItems: List<EvidenceItem> = emptyList(),
    ): AssuranceVerifyStepAdapter {
        // M11.8: el verify adapter es el port del SDK; en el
        // mock simplemente devolvemos la instancia y dejamos
        // que el test invoque `key` o asserts sobre la forma.
        return AssuranceVerifyStepAdapter()
    }

    /**
     * Helper para construir un `AssuranceCheckStepDefinition.Input`
     * mínimo para tests. Devuelve el input, NO lo ejecuta.
     */
    fun checkInput(
        name: String = "self",
        suite: AssuranceCheckStepDefinition.SuiteDto = minimalSuite(),
        evidence: List<AssuranceCheckStepDefinition.EvidenceRefDto> = emptyList(),
        mode: String = "FailClosed",
        completenessPolicy: String = "RequireComplete",
    ): AssuranceCheckStepDefinition.Input = AssuranceCheckStepDefinition.Input(
        name = name,
        suite = suite,
        evidence = evidence,
        mode = mode,
        completenessPolicy = completenessPolicy,
    )

    private fun minimalSuite(): AssuranceCheckStepDefinition.SuiteDto =
        AssuranceCheckStepDefinition.SuiteDto(
            id = "s/mock",
            version = "0.0.1",
            requiredEvidence = emptyList(),
            // Una lens mínima para que `AssuranceSuiteIR` no
            // rechace la suite (requiere >= 1). La lens no se
            // ejecuta porque el runtime mock pasa `lenses =
            // emptyMap()` (lens sin handler registrado). El
            // resultado sigue siendo 0 passed / 0 failed.
            lenses = listOf(
                AssuranceCheckStepDefinition.LensDto(
                    id = "l/noop",
                    kind = "NoOp",
                    inputCapabilities = emptyList(),
                    arguments = emptyMap(),
                    outputSchema = "noop/v0",
                ),
            ),
            // Una assertion mínima para que `AssuranceSuiteIR`
            // no rechace la suite (requiere >= 1). La assertion
            // nunca se ejecuta porque `assertionsById =
            // emptyMap()` en el handler. Resultado: 0 passed.
            assertions = listOf(
                AssuranceCheckStepDefinition.AssertionDto(
                    id = "a/noop",
                    lensRef = "l/noop",
                    operator = "noop",
                    operands = emptyMap(),
                    severity = "Info",
                    enforcement = "Advisory",
                    completenessRequirements = emptyList(),
                    rationale = "noop assertion for mock SDK host",
                    admittedAuthorities = setOf("DeterministicAdapter"),
                ),
            ),
        )

    /**
     * Helper para serializar input/output a JSON, igual que
     * haría el SDK real. Útil para tests que verifican la
     * forma del wire contract del Step.
     */
    inline fun <reified T> encodeJson(value: T): String =
        json.encodeToString(serializer(), value)
}
