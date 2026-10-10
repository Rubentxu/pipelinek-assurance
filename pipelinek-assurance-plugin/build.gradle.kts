plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// CycloneDX 1.4.0 no expone plugin id público; se aplica por
// clase vía buildscript classpath del root project.
apply<org.cyclonedx.gradle.CycloneDxPlugin>()

// CycloneDX SBOM — `cyclonedxBom` task genera `build/reports/
// sbom.json` y `build/reports/sbom.xml` con el árbol transitivo
// completo de dependencias. Esta es la fuente de verdad del M11.2:
// reemplaza al stub `tools/generate-sbom.sh` que sólo enumeraba
// dependencias declaradas (no transitivas).
//
// CycloneDX Gradle plugin 1.4.0: la configuración se pasa vía
// `getStringParameter` / `getBooleanParameter` sobre properties
// del proyecto (`cyclonedx.schemaVersion`, etc.). Defaults del
// plugin (schema 1.3, JSON only) son suficientes para M11.2; el
// `osv-scanner` consume CycloneDX 1.3+ sin problemas.
tasks.named("cyclonedxBom") {
    val task = this
    if (task is org.cyclonedx.gradle.CycloneDxTask) {
        // El task 1.4 no expone setters públicos; el plugin
        // lee de `project.ext` o de system properties. Por
        // ahora usamos defaults (JSON, schema 1.3) que el
        // plugin acepta.
    }
}

// M3 — pipelinek-assurance-plugin.
//
// AAT-3: este es el ÚNICO módulo del repo que depende del SDK de PipelineK.
// La frontera existe por una razón: si el core (`assurance-engine`,
// `assurance-domain`, etc.) pudiera importar `pipeline-application` o
// cualquier clase del SDK, AAT-10 (PipelineK core sin `assurance.*`) y
// AAT-11 (`assurance.verify` no itera `StepNode` ni importa el coordinator)
// serían imposibles de enforcing por import static, y el gate de M3 no
// podría certificar "cero ediciones en el core de PipelineK".
//
// **Estado actual:** el SDK de PipelineK no está disponible en este
// repositorio (M3 depende de un artefacto externo). El módulo existe
// como placeholder estructural para que AAT-3 (que verifica que el
// módulo EXISTE) siga siendo observable, y para que cuando el SDK esté
// disponible la integración sea:
//
//   implementation("dev.pipelinek:pipelinek-sdk:<version>")
//
// con un classpath que el caller resuelve. Las dependencias
// declaradas hoy son sólo el motor y el artifact; el SDK se añade en
// el momento de la integración real (M3 segundo pase, fuera del scope
// de este commit).
//
// Lo que el módulo DECLARA ahora:
//   - `dev.pipelinek.assurance.plugin.AssuranceCheckStep` — el contrato
//     del Step `assurance.check` que el SDK consumirá, con inputs
//     tipados y outputs tipados. La implementación se cablea al SDK en
//     el momento en que la dependencia se añade.
//
// Lo que el módulo NO declara:
//   - ningún `when (stepKey == "assurance...")` en coordinator (AAT-10);
//   - ningún subtipo de `StepSpec` específico de assurance (PIPELINEK_WORKSTREAM);
//   - ninguna escritura directa al plugin journal (PIPELINEK_WORKSTREAM);
//   - ningún traversal de `StepNode` desde `assurance.verify` (AAT-11).
dependencies {
    implementation(project(":assurance-domain"))
    implementation(project(":assurance-engine"))
    implementation(project(":assurance-artifact"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)

    // Testkit: el plugin se prueba contra fixtures del core, no contra
    // un PipelineK real. La UAT-008 (instalado en distribución real)
    // ocurre fuera de `check`, en un worktree con la distribución
    // instalada.
    testImplementation(project(":assurance-domain"))
    testImplementation(project(":assurance-engine"))
    testImplementation(project(":assurance-artifact"))
    testImplementation(project(":assurance-testkit"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit.jupiter)
}
