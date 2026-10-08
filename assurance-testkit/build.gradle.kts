plugins {
    alias(libs.plugins.kotlin.jvm)
}

// El corpus golden NUNCA se escribe desde el test de verificacion.
// `generateGolden` lo reescribe, y solo se invoca a mano cuando el formato
// cambia a proposito (por ejemplo al subir apiVersion). Un golden que se
// regenera en cada build siempre pasaria y no protegeria de nada.
tasks.register<JavaExec>("generateGolden") {
    group = "verification"
    description = "Regenera el corpus golden de digests. Manual y explicito, nunca parte de check."
    mainClass.set("dev.pipelinek.assurance.fitness.GoldenCorpusGenerator")
    classpath = sourceSets["test"].runtimeClasspath
    args(rootDir.absolutePath)
}

// Testkit: fixtures, generadores de property tests y helpers de evidencia
// sintetica. Es el unico modulo que conoce kotest, y solo como `test`.
// AAT-19: la evidencia heuristica no puede satisfacer una assertion que exige
// autoridad determinista sin admision explicita; los generadores de aqui
// producen los cuatro casos (fact/observation/signal/hypothesis) por separado.
dependencies {
    api(project(":assurance-domain"))
    api(project(":assurance-engine"))
    api(project(":assurance-artifact"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.junit.jupiter)
    // `assurance-artifact` declara kotlinx.serialization como `implementation`,
    // asi que no llega aqui. El test de orden canonico de JSON NECESITA el
    // parser real: leer el orden de las claves con un parser escrito a mano
    // produce un diagnostico equivocado cuando falla, que es peor que no
    // tener test. Es `testImplementation` y no `api` a proposito: el testkit
    // no promete JSON a nadie.
    testImplementation(libs.kotlinx.serialization.json)
}
