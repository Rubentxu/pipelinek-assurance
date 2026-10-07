plugins {
    alias(libs.plugins.kotlin.jvm)
}

// M0: codec canonico CBOR/JSON + digest determinista.
// Serialization se usa SOLO para el boundary de artefactos, nunca en el core
// funcional. Artifacts decoded desde codigo no confian pasan por bounded
// decoding (09-operations/SECURITY_AND_TRUST.md).
dependencies {
    api(project(":assurance-domain"))
    implementation(project(":assurance-engine"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)
}
