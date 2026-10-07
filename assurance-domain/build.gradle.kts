plugins {
    alias(libs.plugins.kotlin.jvm)
}

// AAT-1: `assurance-domain` no depende de PipelineK, filesystem, network,
// coroutine runtime ni CLI. Este modulo solo declara kotlin-stdlib.
// Cualquier dependencia anadida aqui rompe el fitness function.
dependencies {
    implementation(libs.kotlin.stdlib)
}
