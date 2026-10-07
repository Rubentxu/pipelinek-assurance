plugins {
    alias(libs.plugins.kotlin.jvm)
}

// AAT-2: `assurance-engine` no depende de implementaciones de provider.
// Solo conoce el domain y tipos puros propios.
dependencies {
    implementation(project(":assurance-domain"))
    implementation(libs.kotlin.stdlib)
}
