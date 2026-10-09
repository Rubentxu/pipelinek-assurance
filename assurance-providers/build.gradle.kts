plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// M2: módulo de adapters de evidence providers.
//
// Frontera (PROVIDER_SPI.md, AAT-2, AAT-4):
//   - Implementa `dev.pipelinek.assurance.engine.EvidenceProvider`.
//   - No depende de PipelineK (AAT-3 lo prohíbe en cualquier módulo que no
//     sea `pipelinek-assurance-plugin`, y este módulo no es ese).
//   - Consume artefactos externos por wire format (`assurance-evidence/v1`),
//     nunca por tipos internos del producer.
//
// Por qué módulo nuevo y no `assure-cli` o `assurance-artifact`:
//   - `assurance-engine` no admite implementaciones de provider (AAT-2).
//   - `assurance-artifact` contiene codecs, no adaptadores que normalicen a
//     `EvidenceSnapshot`: mezclar el codec y la normalización borraría la
//     frontera entre "bytes externos" y "modelo de dominio".
//   - `assure-cli` es la capa Infrastructure que ejecuta el binario `assure`;
//     un provider de evidence no es CLI.
//
// Las implementaciones viven aquí. Las decisiones de versión se materializan
// en el `EvidenceProviderDescriptor` del SPI. Los DTOs del wire format son
// internos (no se exportan) porque un consumer externo que necesitara el
// formato debería implementar OTRO provider, no reusar el DTO.
dependencies {
    api(project(":assurance-domain"))
    api(project(":assurance-engine"))
    implementation(project(":assurance-artifact"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    testImplementation(project(":assurance-domain"))
    testImplementation(project(":assurance-engine"))
    testImplementation(project(":assurance-artifact"))
    testImplementation(project(":assurance-testkit"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit.jupiter)
}
