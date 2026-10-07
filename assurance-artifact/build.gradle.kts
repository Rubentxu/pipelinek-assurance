plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// M0: codec canonico CBOR/JSON + digest determinista.
// Serialization se usa SOLO para el boundary de artefactos, nunca en el core
// funcional. Artifacts decoded desde codigo no confian pasan por bounded
// decoding (09-operations/SECURITY_AND_TRUST.md).
//
// El corpus golden se regenera solo con una flag explicita:
//   ./gradlew :assurance-testkit:test -Passgold=regenerate
// Nunca implicitamente: un golden que se reescribe solo no protege de nada.
dependencies {
    api(project(":assurance-domain"))
    implementation(project(":assurance-engine"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlin.reflect)
}
