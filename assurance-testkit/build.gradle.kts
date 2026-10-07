plugins {
    alias(libs.plugins.kotlin.jvm)
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
}
