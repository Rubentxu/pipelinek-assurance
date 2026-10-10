import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// CycloneDX Gradle plugin via buildscript classpath. La razón: el
// plugin id `org.cyclonedx.bom` no publica un plugin marker en
// Gradle Plugin Portal; el artefacto real vive en Maven Central
// con coordenadas `org.cyclonedx:cyclonedx-gradle-plugin`. El
// buildscript clásico evita el plugin id resolution.
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.cyclonedx:cyclonedx-gradle-plugin:${libs.versions.cyclonedx.get()}")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            // Alineado con pipeline-kotlin: toolchain 21, el JDK mas bajo que
            // soporta Kotlin 2.4 y el que exige el SDK de PipelineK en M3.
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                // Explicit opt-in por modulo. El core funcional no usa nada
                // experimental; los tests si (property testing).
                // -jvm-default=enable es el equivalente moderno de la antigua
                // -Xjvm-default=all: comportamiento de interface method por
                // defecto en Kotlin 2.4.
                freeCompilerArgs.addAll(
                    "-jvm-default=enable",
                )
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // M0 STOP: si un test depende del reloj global o de orden de red, el
            // determinismo del digest esta comprometido.
            systemProperty("user.language", "en")
            systemProperty("user.country", "US")
            testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

