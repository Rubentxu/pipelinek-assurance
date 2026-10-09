// pipelinek-assurance — settings de bootstrap (W0)
//
// Modulos logicos de M0, segun ROADMAP.md §3/M0 y 05-roadmap/IMPLEMENTATION_GUIDE.md.
// NO se anaden mas modulos hasta que una frontera real lo exija.
//
// W0 prohibe dependencia de PipelineK. El unico modulo que podra tocarla es
// `pipelinek-assurance-plugin`, que se crea en M3 (AAT-3).

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pipelinek-assurance"

include("assurance-domain")
include("assurance-engine")
include("assurance-artifact")
include("assurance-testkit")
include("assurance-providers")
include("assure-cli")
include("pipelinek-assurance-plugin")
