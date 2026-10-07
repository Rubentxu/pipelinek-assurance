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

    // Tests de los codecs. VIVEN AQUI, y no en `assurance-testkit`, por una
    // razón concreta: los DTO del envelope son `internal`, y en Kotlin
    // `internal` es de MODULO. Para construir un envelope sin `digest`, o con
    // el digest alterado, hace falta usar los serializers REALES, y desde otro
    // modulo no se ven.
    //
    // Se comprobo por las malas: la via desde el testkit era reimplementar la
    // codificacion CBOR a mano, y fallo tres veces seguidas por tres
    // supuestos falsos sobre el formato (byte de longitud delante de cada
    // clave, `0x78 0x40` delante del valor del digest, mapas INDEFINIDOS sin
    // recuento en los bytes). Un test que reimplementa el codec acaba
    // probando su propia imaginacion; uno que usa el serializer prueba el
    // codec de verdad.
    testImplementation(project(":assurance-domain"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit.jupiter)
}
