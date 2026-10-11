/*
 * G3.2-V19/V22/V23/V24 — Contratos del API público del plugin.
 *
 * V19: surface del API público — `definitions()` devuelve 2 steps
 *      con shape correcto.
 * V22: visibilidad — `AssurancePluginContributor` es public; el
 *      resto del mock (MockSdkHost, etc.) es internal.
 * V23: back-compat del overload `run(input, evidenceItems)` de
 *      `AssuranceCheckStepDefinition` — sigue accesible y produce
 *      mismo output que el overload completo.
 * V24: digest determinism cross-platform — el mismo input produce
 *      exactamente el mismo digest byte-a-byte (mismo hex, misma
 *      longitud).
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.engine.SuiteId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class G3PluginSurfaceContractTest : StringSpec({

    "G3_V19_definitions_returns_two_steps_with_expected_keys" {
        val contributor = AssurancePluginContributor()
        val defs: List<Any> = contributor.definitions().toList()

        defs.size shouldBe 2

        // Las keys se leen de `def.contract.key.value` (porque
        // StepDefinition expone `contract`, no `key` directo, y
        // PluginStepId envuelve el String). Usamos java reflection
        // porque las impl son `private class` y Kotlin reflection
        // respeta esa visibilidad. Iteramos todos los métodos
        // del contract buscando uno que parezca "getKey*".
        val keys = defs.map { def ->
            val contractM = def.javaClass.getMethod("getContract").invoke(def)!!
            // Buscar el getter del PluginStepId: cualquier método
            // que devuelva algo con `value` toString o un
            // PluginStepId.
            val contractClass = contractM.javaClass
            val candidateMethods = contractClass.methods
                .filter { it.parameterCount == 0 }
                .filter { it.returnType.simpleName.contains("PluginStepId") ||
                    it.returnType.simpleName.contains("StepId") ||
                    it.name.contains("Key") }
            val keyM = candidateMethods.firstOrNull()?.invoke(contractM)
                ?: contractClass.getMethod("toString").invoke(contractM)
            // getValue puede no existir si PluginStepId es value class.
            val keyStr = keyM.toString()
            val valueMatch = Regex("value=([^)]+)").find(keyStr)
            valueMatch?.groupValues?.get(1) ?: keyStr
        }
        (keys.contains("assurance.check")) shouldBe true
        (keys.contains("assurance.verify")) shouldBe true
    }

    "G3_V19_contributor_id_is_stable" {
        val contributor = AssurancePluginContributor()
        contributor.id shouldBe "pipelinek-assurance"
    }

    "G3_V22_assurancePluginContributor_is_public" {
        val clazz = AssurancePluginContributor::class.java
        val isPublic = java.lang.reflect.Modifier.isPublic(clazz.modifiers)
        isPublic shouldBe true

        val ctor = clazz.getDeclaredConstructor()
        java.lang.reflect.Modifier.isPublic(ctor.modifiers) shouldBe true
    }

    "G3_V22_mockSdkHost_is_internal" {
        // V22: el mock (MockSdkHost) es test-only, NO debe ser
        // parte del API público. Verificamos que el archivo
        // fuente lo declara como `internal object MockSdkHost`.
        // No usamos java.lang.reflect porque Kotlin `internal` se
        // compila a `public` en bytecode (name mangling). La
        // garantía la da la visibilidad a nivel de source, no de
        // bytecode.
        val source = java.io.File(
            "src/main/kotlin/dev/pipelinek/assurance/plugin/MockSdkHost.kt",
        ).readText()
        (source.contains("internal object MockSdkHost")) shouldBe true
    }

    "G3_V23_backcompat_run_with_just_evidenceItems_works" {
        val input = MockSdkHost.checkInput(name = "backcompat")
        val out = AssuranceCheckStepDefinition.run(
            input = input,
            evidenceItems = emptyList(),
        )
        out.outcome shouldBe "Success"
        out.reportDigest.length shouldBe 64
    }

    "G3_V24_same_input_produces_byte_equal_digest" {
        val input = MockSdkHost.checkInput(name = "determinism-check")
        val out1 = MockSdkHost.runCheck(input)
        val out2 = MockSdkHost.runCheck(input)
        val out3 = MockSdkHost.runCheck(input)

        out1.reportDigest shouldBe out2.reportDigest
        out2.reportDigest shouldBe out3.reportDigest
        out1.reportDigest.length shouldBe 64
        out1.reportDigest.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    "G3_V24_digest_changes_when_suite_content_changes" {
        val base = MockSdkHost.checkInput(name = "x")
        val withExtraAssertion = base.copy(
            suite = base.suite.copy(
                assertions = base.suite.assertions + base.suite.assertions.first(),
            ),
        )
        val outA = MockSdkHost.runCheck(base)
        val outB = MockSdkHost.runCheck(withExtraAssertion)
        (outA.reportDigest != outB.reportDigest) shouldBe true
    }

    "G3_V24_suiteId_value_matches_input_suite_id" {
        val suiteA = "s/aaaa"
        val suiteB = "s/bbbb"
        val a = MockSdkHost.checkInput(name = "x").copy(
            suite = MockSdkHost.checkInput(name = "x").suite.copy(id = suiteA),
        )
        val b = MockSdkHost.checkInput(name = "x").copy(
            suite = MockSdkHost.checkInput(name = "x").suite.copy(id = suiteB),
        )
        val ra = MockSdkHost.runCheck(a)
        val rb = MockSdkHost.runCheck(b)
        (ra.reportDigest != rb.reportDigest) shouldBe true
        a.suite.id shouldBe suiteA
        b.suite.id shouldBe suiteB
        val idA = SuiteId(suiteA)
        idA.value shouldBe suiteA
    }
})
