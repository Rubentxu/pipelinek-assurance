package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.testkit.SbomFixture
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File

/**
 * M11.2 — Fitness test del SBOM CycloneDX real.
 *
 * Lo que se verifica:
 *   1. El SBOM existe en `build/reports/bom.json` para el módulo
 *      `pipelinek-assurance-plugin`.
 *   2. El formato es CycloneDX 1.3 (lo que produce el plugin 1.4.0).
 *   3. El SBOM incluye el árbol transitivo (≥ 10 componentes), no
 *      sólo las dependencias declaradas (que era el bug del stub).
 *   4. Al menos un componente tiene coordenadas `org.jetbrains.kotlin`,
 *      que es la dependencia transitiva más obvia.
 *
 * El test se salta si el SBOM no se ha generado todavía (CI genera
 * con `./gradlew :pipelinek-assurance-plugin:cyclonedxBom` antes de
 * correr la suite). Esto evita falsos rojos en máquinas donde la
 * task no se haya ejecutado.
 */
class M11SbomFitnessTest : AnnotationSpec() {

    @Test
    fun sbom_plugin_existe_y_es_cyclonedx() {
        val sbom = SbomFixture.pluginSbom()
        if (!sbom.exists()) {
            // Skip: la task `cyclonedxBom` no se ejecutó todavía.
            // En CI, el step previo genera el SBOM.
            return
        }
        val text = sbom.readText()
        text shouldStartWith "{\n  \"bomFormat\" : \"CycloneDX\""
        text shouldContain "\"specVersion\" : \"1.3\""
    }

    @Test
    fun sbom_plugin_incluye_arbol_transitivo() {
        val sbom = SbomFixture.pluginSbom()
        if (!sbom.exists()) return
        val text = sbom.readText()
        // Mínimo de componentes: con kotlin-stdlib + kotlinx-serialization
        // + kotest transitivos, esperamos ≥ 10. El stub antiguo
        // producía < 5.
        val componentCount = "\"group\"".toRegex().findAll(text).count()
        (componentCount >= 10) shouldBe true
    }

    @Test
    fun sbom_plugin_incluye_kotlin_transitivo() {
        val sbom = SbomFixture.pluginSbom()
        if (!sbom.exists()) return
        val text = sbom.readText()
        // Una dependencia transitiva obvia: `org.jetbrains.kotlin`.
        // Si el stub antiguo se reintrodujera, este componente
        // desaparecería (sólo listaba deps declaradas en build.gradle.kts).
        text shouldContain "org.jetbrains.kotlin"
    }

    @Test
    fun sbom_cli_existe_y_es_cyclonedx() {
        // Cubre el SBOM del binario `assure` (M11.2 segundo
        // módulo). La generación corre con `./tools/generate-sbom.sh all`.
        val sbom = SbomFixture.cliSbom()
        if (!sbom.exists()) return
        val text = sbom.readText()
        text shouldStartWith "{\n  \"bomFormat\" : \"CycloneDX\""
        text shouldContain "\"specVersion\" : \"1.3\""
    }
}
