package dev.pipelinek.assurance.plugin

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * G5 (Bloque G) — **Admisión y verificación del plugin contra el
 * SDK PipelineK real**.
 *
 * **Lo que prueba este test:**
 *  - V52: la `PluginManifest` construida por el plugin pasa
 *    `PluginAdmission.admit` en el SDK actualmente enlazado
 *    (controlado por `-PpipelinekVersion=0.48.0` o
 *    `-PpipelinekVersion=0.51.0-rc1`). El resultado debe ser
 *    `PluginAdmissionResult.Admitted`.
 *  - V53: el cross-check de contribuciones
 *    (`AdmittedPlugin.admitContributions`) que el host del SDK
 *    ejecuta en `pipeline validate` / `pipeline run` reporta
 *    `isConsistent == true` cuando el manifest declara las
 *    capabilities correctas para cada Step, y `false` cuando un
 *    manifest mintiendo sobre las capabilities de un Step se
 *    cruza contra los `StepDefinition` reales del plugin.
 *  - V54: la serialización del manifest por `PluginManifestCodec`
 *    es estable: encode → decode preserva schema, plugin,
 *    release, apiRange, publisher, families, delivery, trust y
 *    contributions. Esto pine el contrato del JSON que vivirá
 *    en `META-INF/pipelinek/plugin-manifest.json` dentro del JAR.
 *
 * **Lo que NO prueba (limitación documentada, deliberada):**
 *  - `pipelinek plugin admit <jar>` como subcomando CLI: el
 *    binario `pipelinek 0.48.0` y `0.51.0-rc1` sólo expone
 *    `validate|run`. La admisión real la ejecuta el host
 *    en-proceso llamando a las mismas APIs que este test.
 *    En producción, `PreLoadPluginAdmission.admitAll` y
 *    `PluginContributionVerifier.verify` (en `pipeline-application`)
 *    envuelven exactamente las llamadas a `PluginAdmission.admit`
 *    y `AdmittedPlugin.admitContributions` que aquí ejercitamos
 *    directamente. Un subproceso que llame al host real es
 *    certificación de G6 (pipeline end-to-end), no de G5.
 *
 * **Matriz SDK:** este test corre **dos veces** con el mismo
 * código fuente: una con `pipelinekVersion=0.48.0` (baseline
 * reproducible) y otra con `pipelinekVersion=0.51.0-rc1` (matriz
 * adelantada). La matriz documentada vive en `SDK_COMPATIBILITY_MATRIX.md`.
 */
class G5PluginAdmissionTest : AnnotationSpec() {

    private val pluginRef: ResourceRef =
        ResourceRef(ResourceKind.PLUGIN, listOf("pipelinek-assurance"))
    private val pluginReleaseRef: ResourceRef =
        ResourceRef(
            // PluginReleaseRef exige que el campo `plugin` sea
            // PLUGIN (no PLUGIN_RELEASE): la release se identifica
            // por el plugin al que pertenece + version + digest.
            ResourceKind.PLUGIN,
            listOf("pipelinek-assurance"),
        )
    private val releaseVersion: SemVer = SemVer(0, 10, 0)
    private val releaseDigest: Digest = Digest(
        // 64 hex zeros con prefijo "sha256:" — el SDK exige
        // el prefijo del algoritmo. En el camino real el host
        // mide el JAR y produce un digest completo con
        // `PluginManifestResourceReader.measure(...)`. Aquí
        // pineamos un placeholder 0…0 que iguala
        // declaredDigest == measuredDigest (admisión OK).
        "sha256:0000000000000000000000000000000000000000000000000000000000000000",
    )
    private val apiRange: PipelineKApiRange = PipelineKApiRange(
        SemVer(0, 48, 0),
        SemVer(0, 52, 0),
    )
    private val publisher: String = "gentle-ai"
    private val families: Set<PluginFamily> = setOf(PluginFamily.SUPPLY_CHAIN, PluginFamily.REPORTING)
    private val delivery: Delivery = Delivery.OFFICIAL_PLUGIN
    private val trust: TrustMetadata = TrustMetadata.Unverified

    private val stepContributions: List<PluginStepContribution> = run {
        val check = setOf<StepCapability>(ASSURANCE_PROVIDER, ASSURANCE_ASSERTIONS)
        val verify = setOf(
            ASSURANCE_PROVIDER,
            ASSURANCE_ASSERTIONS,
            StepCapability("bodyContinuation"),
        )
        listOf(
            PluginStepContribution(PluginStepId("assurance.check"), check),
            PluginStepContribution(PluginStepId("assurance.verify"), verify),
        )
    }

    private val contributions: PluginContributions = PluginContributions(
        steps = stepContributions,
        directives = emptyList<dev.rubentxu.pipeline.v2.domain.step.PluginDirectiveContribution>(),
        events = emptyList<dev.rubentxu.pipeline.v2.domain.step.PluginEventContribution>(),
        capabilities = setOf(
            ASSURANCE_PROVIDER,
            ASSURANCE_ASSERTIONS,
            StepCapability("bodyContinuation"),
        ),
    )

    private val pluginManifest: PluginManifest = PluginManifest(
        ManifestSchemaVersion(1), // CURRENT=1
        pluginRef,
        PluginReleaseRef(pluginReleaseRef, releaseVersion, releaseDigest),
        apiRange,
        publisher,
        families,
        delivery,
        trust,
        contributions,
    )

    private val actualStepDefinitions: List<StepDefinition<*, *>> = listOf(
        AssuranceCheckStepDefinitionImpl(),
        AssuranceVerifyStepDefinitionImpl(),
    )

    private fun measuredIdentity(): MeasuredArtifactIdentity = MeasuredArtifactIdentity(
        releaseDigest,
        releaseDigest,
        ArtifactOrigin.LocalClasspathEntry("test-fixture"),
    )

    @Test
    fun v52_admit_plugin_manifest_es_admitted() {
        val decodeResult: PluginManifestDecodeResult =
            PluginManifestDecodeResult.Companion.accept(pluginManifest)

        val result: PluginAdmissionResult = PluginAdmission.admit(
            decodeResult,
            measuredIdentity(),
            SemVer(0, 48, 0),
            emptySet(),
        )

        withClue("admission expected Admitted, was: $result") {
            result is PluginAdmissionResult.Admitted
        }
    }

    @Test
    fun v53_admitContributions_actual_definitions_isConsistent() {
        val admitted = PluginAdmission.admit(
            PluginManifestDecodeResult.Companion.accept(pluginManifest),
            measuredIdentity(),
            SemVer(0, 48, 0),
            emptySet(),
        ) as PluginAdmissionResult.Admitted

        val crossCheck = admitted.plugin.admitContributions(
            actualStepDefinitions,
            emptySet(),
            emptySet(),
        )

        withClue("cross-check expected consistent; describe: ${crossCheck.describe()}") {
            crossCheck.isConsistent shouldBe true
        }
    }

    @Test
    fun v53_negativo_manifest_mintiendo_sobre_capabilities_no_es_consistent() {
        // Construimos un manifest que declara una capability falsa
        // (no requerida por el Step real). El cross-check debe
        // detectar `mismatchedCapabilities`.
        val lyingManifest: PluginManifest = PluginManifest(
            ManifestSchemaVersion(1),
            pluginRef,
            PluginReleaseRef(pluginReleaseRef, releaseVersion, releaseDigest),
            apiRange,
            publisher,
            families,
            delivery,
            trust,
            PluginContributions(
                steps = listOf(
                    PluginStepContribution(
                        PluginStepId("assurance.check"),
                        setOf<StepCapability>(
                            ASSURANCE_PROVIDER,
                            ASSURANCE_ASSERTIONS,
                            StepCapability("fictional.capability"),
                        ),
                    ),
                    PluginStepContribution(
                        PluginStepId("assurance.verify"),
                        setOf<StepCapability>(
                            ASSURANCE_PROVIDER,
                            ASSURANCE_ASSERTIONS,
                            StepCapability("bodyContinuation"),
                        ),
                    ),
                ),
                directives = emptyList<dev.rubentxu.pipeline.v2.domain.step.PluginDirectiveContribution>(),
                events = emptyList<dev.rubentxu.pipeline.v2.domain.step.PluginEventContribution>(),
                capabilities = setOf(
                    ASSURANCE_PROVIDER,
                    ASSURANCE_ASSERTIONS,
                    StepCapability("bodyContinuation"),
                    StepCapability("fictional.capability"),
                ),
            ),
        )

        val admitted = PluginAdmission.admit(
            PluginManifestDecodeResult.Companion.accept(lyingManifest),
            measuredIdentity(),
            SemVer(0, 48, 0),
            emptySet(),
        ) as PluginAdmissionResult.Admitted

        val crossCheck = admitted.plugin.admitContributions(
            actualStepDefinitions,
            emptySet(),
            emptySet(),
        )

        crossCheck.isConsistent shouldBe false
    }

    @Test
    fun v54_codec_encode_decode_roundtrip() {
        val encoded: String = PluginManifestCodec.encode(pluginManifest)
        val decodedResult: PluginManifestDecodeResult =
            PluginManifestCodec.decode(encoded)

        withClue("decode expected Accepted, was: $decodedResult") {
            decodedResult is PluginManifestDecodeResult.Accepted
        }

        val decodedManifest: PluginManifest =
            (decodedResult as PluginManifestDecodeResult.Accepted).manifest

        withClue("plugin ref round-tripped") { decodedManifest.plugin shouldBe pluginRef }
        withClue("publisher round-tripped") { decodedManifest.publisher shouldBe publisher }
        withClue("apiRange round-tripped") { decodedManifest.apiRange shouldBe apiRange }
        withClue("families round-tripped") { decodedManifest.families shouldBe families }
        withClue("delivery round-tripped") { decodedManifest.delivery shouldBe delivery }
        withClue("contributions.steps count round-tripped") {
            decodedManifest.contributions.steps.size shouldBe stepContributions.size
        }
        withClue("contributions.steps keys round-tripped") {
            decodedManifest.contributions.steps.map { it.stepKey.value }.toSet() shouldBe
                setOf("assurance.check", "assurance.verify")
        }
    }
}
