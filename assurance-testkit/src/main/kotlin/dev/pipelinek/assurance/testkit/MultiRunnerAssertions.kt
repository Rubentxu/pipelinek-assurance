package dev.pipelinek.assurance.testkit

import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceReport

/**
 * M9 — Mapeo `AssertionResult` → excepciones tipadas para runners.
 *
 * Ref: `03-specifications/JUNIT_KOTEST_ADAPTERS.md` y ROADMAP §M9.
 *
 * **Por qué este archivo NO importa JUnit Jupiter directamente:**
 * `assurance-testkit` no expone JUnit en su `api` (AAT-1, AAT-2:
 * mantener el testkit ligero y reusable). Pero la spec exige que el
 * mapeo sea idéntico desde JUnit, Kotest y un runner puro. La forma
 * que respeta esa restricción es: el helper produce **excepciones
 * tipadas por el contrato de JUnit/Kotest** (`AssertionError` para
 * failure, `AssumptionAbortedException`/`TestAbortedException` para
 * skip), y los adapters específicos de cada runner las traducen al
 * tipo del runner. Un test JUnit usa `Assertions.assertThrows`, un
 * test Kotest usa `shouldThrow`, y ambos esperan la misma jerarquía.
 *
 * El mapeo que la spec exige:
 *
 *   - `Passed`          → no-op (la suite continúa).
 *   - `Failed`          → `AssertionError` con explicación del counterexample.
 *   - `Inconclusive`    → `AssumptionAbortedException` (no `success`).
 *   - `Unsupported`     → `AssumptionAbortedException`.
 *   - `Error`           → `AssertionError` con phase/detail.
 *
 * `AssumptionAbortedException` está en `org.opentest4j`, que sí es
 * una dependencia transitiva de JUnit Jupiter. Si no está en el
 * classpath, lanzamos `IllegalStateException` con un mensaje que
 * el caller puede distinguir de un fallo real.
 */
object MultiRunnerAssertions {

    /**
     * Traduce un `AssertionResult` a una excepción tipada según la
     * spec JUNIT_KOTEST_ADAPTERS.md. **No lanza para `Passed`.**
     */
    fun assertInJunit(result: AssertionResult) {
        when (result) {
            is AssertionResult.Passed -> {
                // Success. La spec exige que NO se aborte, no que se
                // afirme algo. Un `assertTrue(true)` sería ruido.
            }
            is AssertionResult.Failed -> {
                val ce = result.counterexample
                throw AssertionError(
                    "assertion ${ce.assertionId.value} failed: ${ce.explanation}",
                )
            }
            is AssertionResult.Inconclusive -> {
                val detail = result.gaps.joinToString(";") { it.detail ?: it.reason.toString() }
                throwAborted("inconclusive: $detail")
            }
            is AssertionResult.Unsupported -> {
                throwAborted("unsupported: ${result.reason}")
            }
            is AssertionResult.Error -> {
                throw AssertionError(
                    "engine error in ${result.failure.phase}: ${result.failure.detail}",
                )
            }
        }
    }

    /**
     * Traduce un `AssuranceReport` entero.
     */
    fun assertReportInJunit(report: AssuranceReport) {
        for (r in report.results) {
            assertInJunit(r)
        }
    }

    /**
     * Lanza `AssumptionAbortedException` (org.opentest4j) si está
     * disponible; cae a `IllegalStateException` con un mensaje
     * distinto si no. La distinción permite que un caller JUnit
     * detecte el skip por el tipo, y un caller sin opentest4j en
     * el classpath reciba un error legible.
     */
    private fun throwAborted(message: String): Nothing {
        val cls = try {
            Class.forName("org.opentest4j.TestAbortedException")
        } catch (_: ClassNotFoundException) {
            null
        }
        if (cls != null) {
            throw cls.getDeclaredConstructor(String::class.java).newInstance(message) as Throwable
        }
        throw IllegalStateException("[aborted] $message")
    }
}
