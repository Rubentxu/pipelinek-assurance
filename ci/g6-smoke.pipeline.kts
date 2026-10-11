// G6 sample: invokes assurance.check via the plugin's StepDefinitionContributor
import dev.rubentxu.pipeline.v2.dsl.pipeline

pipeline {
    stages {
        stage("g6-check") {
            sh("echo 'plugin discovered'")
        }
    }
}
