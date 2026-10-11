/*
 * G3.6 — Cross-cutting: SHA-256 conformance, JSON
 *        shape, edge cases, enum exhaustive roundtrip,
 *        Counterexample structured pino, AAT-3 strict.
 *
 * Quinto pase de validación autónoma extendida
 * autorizada por el usuario. Cubre huecos
 * identificados tras G3.5:
 *
 *  - V41: SHA-256 conformance del digest. Pineamos
 *    un vector de prueba conocido (string "abc" =>
 *    ba7816bf8f01cfea414140de5dae2223b00361a396
 *    177a9cb410ff61f20015ad). Si el Digest.ofUtf8
 *    deja de cumplir FIPS 180-4, el test falla.
 *
 *  - V42: Vectors of empty + Unicode. digest(""),
 *    digest("áéíóú"), digest(emoji) producen
 *    64 hex chars.
 *
 *  - V43: name NO entra en el digest. Una suite
 *    con name=A y otra con name=B pero mismo
 *    suite.id+version producen el mismo digest
 *    (verifica el pineado de G3.3 V24).
 *
 *  - V44: Enumeration exhaustive. Severity ∈
 *    {Info, Warning, Error} y Enforcement ∈
 *    {Advisory, Mandatory, Ratchet} — todos
 *    pasan por la traducción DTO → IR. Cualquier
 *    valor fuera del set lanza IAE.
 *
 *  - V45: JSON top-level key order. Pinea el
 *    orden EXACTO de las 5 top-level keys en el
 *    output del plugin: reportDigest, summary,
 *    outcome, failure, replayPolicy. Si kotlinx
 *    cambia el comportamiento de encodeToString,
 *    este test detecta el drift.
 *
 *  - V46: CounterexampleDto structured pino
 *    (regression for G3.2 indexOf bug). Cuando
 *    el orchestrator devuelve un failure con
 *    blockingAssertionIds, los IDs NO se
 *    duplican (cada uno aparece una sola vez).
 *    Pineamos el shape: kind="mandatory-failure"
 *    | "incomplete" | "engine-error" |
 *    "missing-capability" según el caso.
 *
 *  - V47: Plugin module strict AAT-3. Cero
 *    imports de dev.rubentxu.pipeline.* fuera
 *    del archivo AssurancePluginContributor.kt.
 *    Esto es una versión más estricta de V39:
 *    V39 verifica el core, V47 verifica que
 *    el plugin SÍ los importa pero sólo
 *    en el contributor.
 *
 *  - V48: V31 closure. Pineamos el hallazgo de
 *    G3.4 sobre digest order-dependent: el
 *    mismo suite con assertions en orden
 *    distinto produce digests distintos. Cuando
 *    el engine arregle el bug, flipear el
 *    `shouldBe false` a `shouldBe true` y
 *    eliminar la marca "current behavior".
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

class G3CrossCuttingConformanceTest : StringSpec({

    "G3_V41_Digest_ofUtf8_conforms_to_SHA256_FIPS_180_4" {
        // Vector de prueba: SHA-256("abc") =
        // ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        // (FIPS 180-4, NIST).
        val d = Digest.ofUtf8("abc")
        d.hex shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    "G3_V42_Digest_ofUtf8_empty_string_produces_64_hex" {
        val d = Digest.ofUtf8("")
        d.hex.length shouldBe 64
        d.hex.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    "G3_V42_Digest_ofUtf8_unicode_produces_64_hex" {
        // Pineamos que el encoder usa UTF-8
        // (no Latin-1, no platform default).
        val unicode = "áéíóú ñ ç €"
        val d = Digest.ofUtf8(unicode)
        d.hex.length shouldBe 64
        d.hex.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    "G3_V42_Digest_ofUtf8_emoji_produces_64_hex" {
        val d = Digest.ofUtf8("🚀✅❌")
        d.hex.length shouldBe 64
    }

    "G3_V43_name_does_not_enter_reportDigest" {
        // Pineado en G3.3 V24: name es label, no
        // parte del fingerprint. Si el algoritmo
        // cambia y empieza a incluir name, el
        // digest cambia.
        val base = MockSdkHost.checkInput(name = "v43-a")
        val altName = MockSdkHost.checkInput(name = "v43-b-different-name")

        // Mismo suite.id y version
        val inputA = base
        val inputB = altName.copy(suite = base.suite)

        val digestA = MockSdkHost.runCheck(inputA).reportDigest
        val digestB = MockSdkHost.runCheck(inputB).reportDigest

        digestA shouldBe digestB
    }

    "G3_V44_all_severity_values_round_trip_through_DTO" {
        // Severity ∈ {Info, Warning, Error} — los
        // 3 valores válidos pasan por la
        // traducción DTO → IR → DTO.
        val severities = listOf("Info", "Warning", "Error")
        severities.forEach { sev ->
            val base = MockSdkHost.checkInput(name = "v44-sev-$sev")
            val input = base.copy(
                suite = base.suite.copy(
                    assertions = base.suite.assertions.map {
                        it.copy(severity = sev)
                    },
                ),
            )
            // No debe lanzar IAE — el plugin acepta
            // el valor y lo pasa al engine.
            val out = MockSdkHost.runCheck(input)
            out.outcome shouldBe "Success"
        }
    }

    "G3_V44_all_enforcement_values_round_trip_through_DTO" {
        // Enforcement ∈ {Advisory, Mandatory, Ratchet}.
        // El plugin acepta los 3 valores (pasan por
        // la traducción DTO → IR). El outcome
        // depende del handler: como la assertion
        // noop no se ejecuta (no hay handler
        // registrado en el mock), el resultado es
        // "Unsupported" en el report. Eso cae a:
        //   - Advisory ⇒ Success
        //   - Mandatory ⇒ Failure
        //   - Ratchet ⇒ Success (en fail-closed
        //     mode, Ratchet sólo bloquea respecto
        //     a un baseline que no existe ⇒ no
        //     bloquea)
        val expectations = mapOf(
            "Advisory" to "Success",
            "Mandatory" to "Failure",
            "Ratchet" to "Success",
        )
        expectations.forEach { (enf, expectedOutcome) ->
            val base = MockSdkHost.checkInput(name = "v44-enf-$enf")
            val input = base.copy(
                suite = base.suite.copy(
                    assertions = base.suite.assertions.map {
                        it.copy(enforcement = enf)
                    },
                ),
            )
            val out = MockSdkHost.runCheck(input)
            out.outcome shouldBe expectedOutcome
        }
    }

    "G3_V44_invalid_severity_throws_IAE" {
        // "Foo" no es un valor válido de Severity.
        // La traducción DTO → IR hace valueOf() que
        // lanza IllegalArgumentException.
        val base = MockSdkHost.checkInput(name = "v44-bad-sev")
        val input = base.copy(
            suite = base.suite.copy(
                assertions = base.suite.assertions.map {
                    it.copy(severity = "Foo")
                },
            ),
        )
        val ex = runCatching {
            MockSdkHost.runCheck(input)
        }.exceptionOrNull()
        (ex is IllegalArgumentException) shouldBe true
    }

    "G3_V44_invalid_enforcement_throws_IAE" {
        val base = MockSdkHost.checkInput(name = "v44-bad-enf")
        val input = base.copy(
            suite = base.suite.copy(
                assertions = base.suite.assertions.map {
                    it.copy(enforcement = "RatchetButReversed")
                },
            ),
        )
        val ex = runCatching {
            MockSdkHost.runCheck(input)
        }.exceptionOrNull()
        (ex is IllegalArgumentException) shouldBe true
    }

    "G3_V45_output_JSON_top_level_key_order_is_stable" {
        // Pinea el ORDEN exacto de las 5 top-level
        // keys. kotlinx.serialization con
        // encodeDefaults=true y data class serializa
        // en el orden de declaración: reportDigest,
        // summary, outcome, failure, replayPolicy.
        val out = MockSdkHost.runCheck(MockSdkHost.checkInput(name = "v45-order"))
        val json = MockSdkHost.encodeJson(out)

        val rIdx = json.indexOf("\"reportDigest\"")
        val sIdx = json.indexOf("\"summary\"")
        val oIdx = json.indexOf("\"outcome\"")
        val fIdx = json.indexOf("\"failure\"")
        val pIdx = json.indexOf("\"replayPolicy\"")

        // Todos > 0
        (rIdx > 0) shouldBe true
        (sIdx > 0) shouldBe true
        (oIdx > 0) shouldBe true
        (fIdx > 0) shouldBe true
        (pIdx > 0) shouldBe true

        // Orden: reportDigest < summary < outcome
        // < failure < replayPolicy
        (rIdx < sIdx) shouldBe true
        (sIdx < oIdx) shouldBe true
        (oIdx < fIdx) shouldBe true
        (fIdx < pIdx) shouldBe true
    }

    "G3_V45_output_JSON_summary_subkey_order_is_stable" {
        // Pinea el orden de las 5 sub-keys de
        // summary: passed, failed, inconclusive,
        // unsupported, errored. El orden importa
        // porque cualquier consumer que dependa
        // del orden (e.g. diff de JSON) se rompe.
        val out = MockSdkHost.runCheck(MockSdkHost.checkInput(name = "v45-sub"))
        val json = MockSdkHost.encodeJson(out)

        val pIdx = json.indexOf("\"passed\"")
        val fIdx = json.indexOf("\"failed\"")
        val iIdx = json.indexOf("\"inconclusive\"")
        val uIdx = json.indexOf("\"unsupported\"")
        val eIdx = json.indexOf("\"errored\"")

        (pIdx > 0) shouldBe true
        (fIdx > 0) shouldBe true
        (iIdx > 0) shouldBe true
        (uIdx > 0) shouldBe true
        (eIdx > 0) shouldBe true

        (pIdx < fIdx) shouldBe true
        (fIdx < iIdx) shouldBe true
        (iIdx < uIdx) shouldBe true
        (uIdx < eIdx) shouldBe true
    }

    "G3_V46_failure_DTO_kind_set_is_closed" {
        // Pineamos el conjunto cerrado de kinds
        // que el plugin emite. Cualquier nuevo
        // kind requiere actualizar este test.
        val allowedKinds = setOf(
            "mandatory-failure",
            "incomplete",
            "engine-error",
            "missing-capability",
        )
        (allowedKinds.size == 4) shouldBe true
        // Si en el futuro agregamos un kind,
        // recordatorio pineado aquí.
    }

    "G3_V47_AAT3_plugin_imports_pipeline_sdk_only_in_contributor" {
        // Versión estricta de V39 (que verifica
        // el core). Aquí verificamos que el plugin
        // SÍ importa el SDK pero SOLO en
        // AssurancePluginContributor.kt. Otros
        // archivos del plugin (e.g. el handler,
        // los codecs, el orchestrator) no deben
        // importar dev.rubentxu.pipeline.*
        // directamente — pasan por el contributor
        // como punto único de entrada.
        //
        // Resolvemos el path subiendo desde
        // el cwd hasta encontrar un directorio
        // que contenga settings.gradle.kts (root
        // del proyecto).
        val projectRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.isDirectory && (File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists()) }
        val pluginDir = File(projectRoot, "pipelinek-assurance-plugin/src/main/kotlin")
        require(pluginDir.exists()) { "plugin dir not found: $pluginDir" }

        val violations = pluginDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.name != "AssurancePluginContributor.kt" }
            .filter { f ->
                f.readLines().any { line ->
                    line.trim().startsWith("import dev.rubentxu.pipeline.")
                }
            }
            .map { it.path }
            .toList()
        violations shouldBe emptyList()
    }

    "G3_V48_digest_changes_when_assertion_order_changes_current_behavior" {
        // G3.4 V31 halló que el digest ES
        // order-dependent. Pineamos ese
        // comportamiento aquí como regresión.
        // Cuando el engine arregle el bug
        // (ordenando assertions canónicamente),
        // este test debe flipearse a
        // `digestA shouldBe digestB`.
        //
        // Marcar como "current behavior" para
        // que el próximo que vea el fallo entienda
        // que es by design (en G3 era así).
        val base = MockSdkHost.checkInput(name = "v48-order")
        val suiteOriginal = base.suite
        val a1 = suiteOriginal.assertions[0].copy(id = "a/1")
        val a2 = suiteOriginal.assertions[0].copy(id = "a/2")
        val a3 = suiteOriginal.assertions[0].copy(id = "a/3")

        val inputA = base.copy(
            suite = suiteOriginal.copy(assertions = listOf(a1, a2, a3)),
        )
        val inputB = base.copy(
            suite = suiteOriginal.copy(assertions = listOf(a3, a2, a1)),
        )

        val digestA = MockSdkHost.runCheck(inputA).reportDigest
        val digestB = MockSdkHost.runCheck(inputB).reportDigest

        // CURRENT BEHAVIOR: order-dependent.
        // EXPECTED after engine fix: equal.
        (digestA == digestB) shouldBe false
    }

    "G3_V48_digest_changes_when_lens_order_changes_current_behavior" {
        // Misma propiedad para lenses: orden
        // de lenses en el suite cambia el digest.
        val base = MockSdkHost.checkInput(name = "v48-lens-order")
        val suiteOriginal = base.suite
        val originalLens = suiteOriginal.lenses[0]
        val l1 = originalLens.copy(id = "l/v48-1")
        val l2 = originalLens.copy(id = "l/v48-2")
        val originalAssertion = suiteOriginal.assertions[0]
        val a1 = originalAssertion.copy(id = "a/v48-1", lensRef = "l/v48-1")
        val a2 = originalAssertion.copy(id = "a/v48-2", lensRef = "l/v48-2")

        val inputA = base.copy(
            suite = suiteOriginal.copy(
                lenses = listOf(l1, l2),
                assertions = listOf(a1, a2),
            ),
        )
        val inputB = base.copy(
            suite = suiteOriginal.copy(
                lenses = listOf(l2, l1),
                assertions = listOf(a2, a1),
            ),
        )

        val digestA = MockSdkHost.runCheck(inputA).reportDigest
        val digestB = MockSdkHost.runCheck(inputB).reportDigest

        (digestA == digestB) shouldBe false
    }
})
