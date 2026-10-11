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
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.pipeline

pipeline {
    stages {
        stage("g6-check") {
            // `registryStep` takes (String, PluginStepId,
            // EncodedStepValue) at the Kotlin level. Both
            // `PluginStepId` and `EncodedStepValue` are inline
            // classes wrapping String; the Kotlin compiler
            // should auto-wrap, but the script context here
            // doesn't do that reliably (the kts saw
            // "EncodedStepValue expected" when we passed raw
            // String). Passing them explicitly works.
            registryStep(
                "green-suite",
                PluginStepId("assurance.check"),
                EncodedStepValue("{}"),
            )
        }
    }
}
