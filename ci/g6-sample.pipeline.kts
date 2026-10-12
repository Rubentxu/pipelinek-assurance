// G6 sample: invokes assurance.check via the plugin's StepDefinitionContributor
//
// The plugin JAR is in the SDK classpath (via the asdf install's lib/
// directory). At runtime the SDK performs:
//   1. PreLoadPluginAdmission.admitAll(jarPaths, sdkSemVer)
//      → reads META-INF/pipelinek/plugin-manifest.json
//      → verifies declared digest against measured digest
//      → enforces apiRange compatibility
//   2. PluginContributionVerifier.verify(admitted, classLoader)
//      → ServiceLoader<StepDefinitionContributor>
//      → cross-checks each `StepRegistration` against the
//        manifest's declared steps
//   3. Step lookup by `stepKey` happens against the registered
//      `StepDefinition`s
//
// If anything fails, `pipelinek run` exits non-zero with the
// diagnostic above the "Discovered external Step plugins" line.
import dev.pipelinek.assurance.plugin.assuranceCheck
import dev.rubentxu.pipeline.v2.dsl.pipeline

pipeline {
    stages {
        stage("g6-check") {
            // The plugin exposes `assuranceCheck(scope, stepKey,
            // encodedInput)` as a top-level extension that wraps
            // the SDK's `registryStep` correctly. We pass a
            // minimal JSON payload; the handler will see
            // `name=null` and report a missing-field failure
            // (which is the honest observable for a smoke test).
            assuranceCheck(
                this,
                "assurance.check",
                "{}",
            )
        }
    }
}
