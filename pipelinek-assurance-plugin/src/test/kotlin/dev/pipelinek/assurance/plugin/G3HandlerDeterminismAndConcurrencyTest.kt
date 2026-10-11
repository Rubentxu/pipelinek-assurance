/*
 * G3.2-V16/V17 — Idempotencia y thread-safety del handler.
 *
 * Propósito: el handler es un `StepHandler` que se invoca múltiples
 * veces durante una pipeline run. Tiene que ser:
 *   - V16: Idempotente — N invocaciones con mismo Input producen
 *     exactamente el mismo Output (mismo reportDigest, mismo summary,
 *     mismo replayPolicy).
 *   - V17: Thread-safe — invocaciones concurrentes del handler no
 *     se interfieren unas con otras. Cada invocación ve su propio
 *     estado y no contamina las demás.
 *
 * Contexto: el handler es `class AssuranceCheckHandler(private val
 * plugin: AssurancePluginContributor)` — NO es singleton. Cada
 * `run()` crea un `AssuranceCheckHandler` nuevo vía
 * `StepRegistration.handlerProvider`. Si el contributor mantiene
 * estado mutable compartido, el handler lo vería. V16+V17 verifican
 * que NO comparte estado mutable de manera que rompa el determinismo
 * ni la concurrencia.
 *
 * NOTA sobre `name`: el `name` del Input es un label del Step y
 * NO entra en el `reportDigest` (que viene de `report.suiteDigest`).
 * Por tanto, para que V17 produzca digests distintos, hay que variar
 * el `suite.id`, NO el `name`.
 */
package dev.pipelinek.assurance.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class G3HandlerDeterminismAndConcurrencyTest {

    /**
     * Construye un Input con un suite.id parametrizable. Reutiliza
     * el mismo shape de suite minimal que `MockSdkHost.checkInput`.
     */
    private fun inputWithSuiteId(suiteId: String, name: String = "self"): AssuranceCheckStepDefinition.Input {
        val minimal = MockSdkHost.checkInput(name = name)
        return minimal.copy(
            suite = minimal.suite.copy(id = suiteId),
        )
    }

    @Test
    fun G3_V16_idempotency_hundred_invocations_produce_identical_output() = runBlocking {
        // Ejecutamos el mismo input 100 veces seguidas en el mismo
        // MockSdkHost. Cada output debe ser byte-equal en su JSON
        // serializado (mismo reportDigest, mismo summary, misma
        // replayPolicy). Si el handler tuviera estado mutable no
        // idempotente (e.g. un counter compartido, un random con
        // semilla ausente), esto lo detectaría.
        val input = MockSdkHost.checkInput(name = "self-idempotency")

        val first = MockSdkHost.runCheck(input)
        val firstJson = MockSdkHost.encodeJson(first)

        repeat(99) { idx ->
            val out = MockSdkHost.runCheck(input)
            val json = MockSdkHost.encodeJson(out)
            check(json == firstJson) {
                "Non-idempotent at iteration $idx: " +
                    "firstReportDigest=${first.reportDigest} " +
                    "thisReportDigest=${out.reportDigest}"
            }
            // Specifically pin that reportDigest is byte-equal
            // across calls.
            check(out.reportDigest == first.reportDigest) {
                "reportDigest changed at iteration $idx: " +
                    "first=${first.reportDigest} this=${out.reportDigest}"
            }
        }
    }

    @Test
    fun G3_V17_concurrent_invocations_isolated() = runBlocking {
        // Lanzamos 20 invocaciones concurrentes del handler con
        // inputs DIFERENTES (cada uno con un suite.id distinto).
        // Cada output debe corresponderse con SU input, no con el
        // de otra coroutine. Si el handler compartiera estado
        // mutable (e.g. un accumulator global), veríamos
        // cross-contamination entre las invocaciones.
        //
        // Verificamos dos propiedades:
        // 1) Los 20 `reportDigest` son únicos (cada invocación
        //    ve SU suite, no la de otra coroutine).
        // 2) Si dos invocaciones concurrentes usan el mismo
        //    input, ambas producen el mismo digest (determinismo
        //    en concurrencia).
        val inputs = (0 until 20).map { i ->
            inputWithSuiteId(suiteId = "s/concurrent-$i")
        }

        // Ejecutamos en paralelo en el IO dispatcher.
        val results = withContext(Dispatchers.IO) {
            inputs.map { input ->
                async { MockSdkHost.runCheck(input) }
            }.awaitAll()
        }

        // 1) Todos los reportDigest son distintos — prueba que no
        //    hay state-sharing entre invocaciones concurrentes que
        //    haga que el mismo digest aparezca dos veces.
        val digests = results.map { it.reportDigest }
        check(digests.toSet().size == digests.size) {
            "Concurrent runs produced duplicate digests: $digests"
        }

        // 2) Todos los outcomes son Success (suite minimal con
        //    assertion noop; nada que falle).
        results.forEachIndexed { idx, out ->
            check(out.outcome == "Success") {
                "Output $idx not Success: ${out.outcome}"
            }
        }

        // 3) Mismo input en concurrencia produce mismo digest.
        //    Esta es la cara inversa: bajo concurrencia, el
        //    determinismo se preserva.
        val inputA = inputWithSuiteId(suiteId = "s/stable-concurrent")
        val inputB = inputWithSuiteId(suiteId = "s/stable-concurrent")

        val (outA, outB) = withContext(Dispatchers.IO) {
            listOf(
                async { MockSdkHost.runCheck(inputA) },
                async { MockSdkHost.runCheck(inputB) },
            ).awaitAll().let { it[0] to it[1] }
        }
        check(outA.reportDigest == outB.reportDigest) {
            "Concurrent stable inputs produced different digests: " +
                "A=${outA.reportDigest} B=${outB.reportDigest}"
        }
    }
}
