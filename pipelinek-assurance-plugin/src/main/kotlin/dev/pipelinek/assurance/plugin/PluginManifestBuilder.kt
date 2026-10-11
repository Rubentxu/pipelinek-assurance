// G6 (Bloque G) — Generator for `META-INF/pipelinek/plugin-manifest.json`.
//
// **Scope:** this file is the BUILD-TIME source of truth for the
// plugin manifest. It is executed by the Gradle task
// `generatePluginManifest`, which pipes the resulting JSON into
// `build/resources/main/META-INF/pipelinek/plugin-manifest.json`,
// where Gradle's `processResources` then embeds it into the JAR.
//
// **Why a separate generator and not a static file:**
//   - The manifest must round-trip through the SDK's
//     `PluginManifestCodec.encode`. A hand-written JSON file would
//     drift from the codec's grammar and silently fail the runtime
//     admission (`MalformedDocument` from `PreLoadPluginAdmission`).
//   - The values mirror `G5PluginAdmissionTest`'s constants. If they
//     diverge, the test `v54_codec_encode_decode_roundtrip` will fail,
//     and the G6 admission gate will fail in the same way. The test
//     is the *de facto* regression guard.
//
// **Where the manifest is consumed:**
//   - At runtime, by `PreLoadPluginAdmission.admitAll` (called from
//     `pipelinek run` automatically — no CLI subcommand, see
//     G5PluginAdmissionTest docs).
//   - At build time, by `G6ManifestEmbedTest` which loads the JSON
//     from the test resources and decodes it through
//     `PluginManifestCodec.decode` to ensure round-trip stability.
package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Builds the [PluginManifest] for the
 * `pipelinek-assurance-plugin` JAR and serializes it via
 * [PluginManifestCodec.encode].
 *
 * The values are **identical** to the test fixtures in
 * `G5PluginAdmissionTest`. If you change them here, update
 * the test (or vice versa) and re-run the dual matrix.
 */
object PluginManifestBuilder {

    /** Single source of truth, shared with the runtime generator. */
    val PLUGIN_REF: ResourceRef =
        ResourceRef(ResourceKind.PLUGIN, listOf("pipelinek-assurance"))

    val RELEASE_REF_PLUGIN: ResourceRef =
        ResourceRef(ResourceKind.PLUGIN, listOf("pipelinek-assurance"))

    val RELEASE_VERSION: SemVer = SemVer(0, 10, 0)

    /**
     * Placeholder digest. At runtime the host re-measures the JAR
     * with `PluginManifestResourceReader.measure` and compares
     * against this declared value. For the embed test we only need
     * a syntactically valid `sha256:`-prefixed hex; the actual
     * measure of the build artefact is the host's job, not ours.
     */
    val RELEASE_DIGEST: Digest = Digest(
        "sha256:0000000000000000000000000000000000000000000000000000000000000000",
    )

    val API_RANGE: PipelineKApiRange = PipelineKApiRange(
        SemVer(0, 48, 0),
        SemVer(0, 52, 0),
    )

    const val PUBLISHER: String = "gentle-ai"

    val FAMILIES: Set<PluginFamily> = setOf(PluginFamily.SUPPLY_CHAIN, PluginFamily.REPORTING)

    val DELIVERY: Delivery = Delivery.OFFICIAL_PLUGIN

    val TRUST: TrustMetadata = TrustMetadata.Unverified

    val STEP_CONTRIBUTIONS: List<PluginStepContribution> = listOf(
        PluginStepContribution(
            PluginStepId("assurance.check"),
            setOf(ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS),
        ),
        PluginStepContribution(
            PluginStepId("assurance.verify"),
            setOf(
                ASSURANCE_PROVIDER,
                ASSURANCE_ASSERTIONS,
                StepCapability("bodyContinuation"),
            ),
        ),
    )

    val CONTRIBUTIONS: PluginContributions = PluginContributions(
        steps = STEP_CONTRIBUTIONS,
        directives = emptyList(),
        events = emptyList(),
        capabilities = setOf(
            ASSURANCE_PROVIDER,
            ASSURANCE_ASSERTIONS,
            StepCapability("bodyContinuation"),
        ),
    )

    val MANIFEST: PluginManifest = PluginManifest(
        ManifestSchemaVersion(1),
        PLUGIN_REF,
        PluginReleaseRef(RELEASE_REF_PLUGIN, RELEASE_VERSION, RELEASE_DIGEST),
        API_RANGE,
        PUBLISHER,
        FAMILIES,
        DELIVERY,
        TRUST,
        CONTRIBUTIONS,
    )
}

/**
 * Entry point for the Gradle `JavaExec` task.
 *
 * Usage from `build.gradle.kts`:
 * ```
 * tasks.register<JavaExec>("generatePluginManifest") {
 *     classpath = sourceSets["main"].runtimeClasspath
 *     mainClass.set("dev.pipelinek.assurance.plugin.PluginManifestGenerator")
 *     args(manifestOutputPath)
 * }
 * ```
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: PluginManifestGenerator <output-path>")
        kotlin.system.exitProcess(2)
    }
    val outputPath: Path = Path.of(args[0])
    val encoded: String = PluginManifestCodec.encode(PluginManifestBuilder.MANIFEST)
    Files.createDirectories(outputPath.parent)
    outputPath.writeText(encoded)
    System.out.println("Wrote ${encoded.length} bytes of manifest to $outputPath")
}
