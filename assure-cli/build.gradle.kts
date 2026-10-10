plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.application)
}

apply<org.cyclonedx.gradle.CycloneDxPlugin>()

// CycloneDX SBOM para el binario `assure` (M11.2). Mismo setup
// que el módulo del plugin (defaults del plugin 1.4.0).
tasks.named("cyclonedxBom") {
    val task = this
    if (task is org.cyclonedx.gradle.CycloneDxTask) {
        // Defaults: JSON, schema 1.3
    }
}

// `assure-cli` es la UNICA capa `Infrastructure` del core, y eso no es
// decorativo: la ley de capas dice que `Infrastructure` puede depender de todo
// lo de dentro y que nada depende de ella. El CLI es lo unico que lee ficheros
// y escribe stdout, que es exactamente lo que la capa existe para.
//
// Depende de `assurance-artifact` y no al revés, porque el fixture en disco se
// decodifica con `DependencyGraphFixtureCodec`. Al revés habria una dependencia
// entre dos modulos de capas distintas en las dos direcciones, que es
// exactamente el defecto que la propia assertion del CLI comprueba.
dependencies {
    implementation(project(":assurance-domain"))
    implementation(project(":assurance-engine"))
    implementation(project(":assurance-artifact"))
    implementation(libs.kotlin.stdlib)

    testImplementation(project(":assurance-domain"))
    testImplementation(project(":assurance-engine"))
    testImplementation(project(":assurance-artifact"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("dev.pipelinek.assurance.cli.MainKt")
}

// `assure report 08-testing/self-model.graph` tiene que funcionar tal cual se
// escribe, con la raiz del repo como directorio de trabajo. Gradle pone por
// defecto el directorio del modulo, y ahi el path no existe: el CLI respondía
// "fixture ilegible" a un fichero que si existe, que es la peor clase de
// fallo, el que hace dudar del fixture en vez del path.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
