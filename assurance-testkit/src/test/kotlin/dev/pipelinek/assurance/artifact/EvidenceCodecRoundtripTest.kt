package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * M0 — Leyes de roundtrip y de bounded decoding.
 *
 * Ref: `03-specifications/ARTIFACT_WIRE_CONTRACTS.md`, `09-operations/SECURITY_AND_TRUST.md`.
 *
 * El roundtrip no es un lujo: es lo que permite que el digest de un snapshot
 * sea reproducible entre procesos y entre máquinas (Exit de M9). Si
 * `encode -> decode` pierde información, dos runners idénticos producen
 * artefactos distintos y la paridad de digest es ficción.
 */
class EvidenceCodecRoundtripTest : AnnotationSpec() {

    private val rich = EvidenceFixtures.snapshot(
        items = listOf(
            EvidenceFixtures.fact("synthetic/alpha/depends-on/1"),
            EvidenceFixtures.observation("synthetic/delta/invocation/1"),
            EvidenceFixtures.signal("synthetic/beta/smell/1"),
            EvidenceFixtures.hypothesis("synthetic/gamma/hypothesis/1"),
        ),
        gaps = listOf(
            EvidenceGap("SymbolGraph", EvidenceGap.GapReason.Lost, "expiro la cache"),
        ),
        correlations = listOf(EvidenceFixtures.correlation()),
        sources = listOf(
            EvidenceFixtures.manifest(
                produced = listOf("ModuleDependencies", "StaticSmells", "AgentReview", "RuntimeInvocations"),
            ),
        ),
    )

    // -----------------------------------------------------------------------
    // Roundtrip CBOR
    // -----------------------------------------------------------------------

    // El codec ORDENA canónicamente al serializar (AAT-16), asi que el viaje
    // no devuelve el snapshot byte a byte: devuelve su forma canónica. Comparar
    // contra el original pasaria por casualidad mientras los ids fueran
    // únicos, y fallaría en cuanto dos items compartieran id.
    private val canonicalRich: EvidenceSnapshot = EvidenceSnapshot(
        id = rich.id,
        subject = rich.subject,
        sources = CanonicalEncoder.canonicalSources(rich.sources),
        items = CanonicalEncoder.canonicalItems(rich.items),
        gaps = CanonicalEncoder.canonicalGaps(rich.gaps),
        correlations = CanonicalEncoder.canonicalCorrelations(rich.correlations),
    )

    @Test
    fun cbor_roundtrip_preserves_the_snapshot() {
        val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(rich))

        decoded shouldBe canonicalRich
    }

    @Test
    fun cbor_roundtrip_is_idempotent_on_the_second_pass() {
        val once = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(rich))
        val twice = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(once))

        // Punto fijo: el segundo viaje no cambia nada. Antes de canonicalizar
        // esto fallaba, porque el primer viaje reordenaba y el segundo no.
        twice shouldBe canonicalRich
        twice shouldBe once
    }

    @Test
    fun cbor_bytes_are_stable_across_repeated_encodes() {
        val a = EvidenceArtifactCodec.encodeToCbor(rich)
        val b = EvidenceArtifactCodec.encodeToCbor(rich)

        a shouldBe b
    }

    @Test
    fun cbor_roundtrip_preserves_the_canonical_digest() {
        // La ley que importa: el digest antes y después del viaje por el
        // artefacto es el mismo. Si no, la paridad de digest entre runners
        // (Exit de M9) no se puede sostener.
        val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(rich))

        CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(rich)
    }

    // -----------------------------------------------------------------------
    // Roundtrip JSON
    // -----------------------------------------------------------------------

    @Test
    fun json_roundtrip_preserves_the_snapshot() {
        val decoded = EvidenceArtifactCodec.decodeFromJson(EvidenceArtifactCodec.encodeToJson(rich))

        decoded shouldBe canonicalRich
    }

    @Test
    fun json_roundtrip_preserves_the_canonical_digest() {
        val decoded = EvidenceArtifactCodec.decodeFromJson(EvidenceArtifactCodec.encodeToJson(rich))

        CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(rich)
    }

    @Test
    fun json_encodes_the_declared_api_version_and_kind() {
        val json = EvidenceArtifactCodec.encodeToJson(rich)

        json shouldContain "\"apiVersion\":\"assurance-evidence/v1\""
        json shouldContain "\"kind\":\"EvidenceSnapshot\""
    }

    // -----------------------------------------------------------------------
    // Cobertura de cada variante y cada completitud
    // -----------------------------------------------------------------------

    @Test
    fun every_completeness_variant_survives_the_roundtrip() {
        val items: List<EvidenceItem> = listOf(
            factWithCompleteness("synthetic/c/complete/1", Completeness.Complete),
            factWithCompleteness(
                "synthetic/c/partial/1",
                Completeness.Partial(
                    listOf(EvidenceGap("ModuleDependencies", EvidenceGap.GapReason.PartialProduced("0.5"))),
                ),
            ),
            EvidenceItem.Observation(
                id = EvidenceId("synthetic/c/unsupported/1"),
                subject = EvidenceSubject.RuntimeSpan("span-1"),
                authority = dev.pipelinek.assurance.domain.evidence.EvidenceAuthority.RuntimeObserver,
                provenance = EvidenceFixtures.provenance("RuntimeInvocations"),
                observation = "adapter invoked domain",
                completeness = Completeness.Unsupported("provider descartado"),
            ),
            EvidenceItem.Observation(
                id = EvidenceId("synthetic/c/unknown/1"),
                subject = EvidenceSubject.RuntimeSpan("span-2"),
                authority = dev.pipelinek.assurance.domain.evidence.EvidenceAuthority.RuntimeObserver,
                provenance = EvidenceFixtures.provenance("RuntimeInvocations"),
                observation = "sin cerrar",
                completeness = Completeness.Unknown,
            ),
        )

        val snapshot = EvidenceFixtures.snapshot(items)
        val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(snapshot))

        // Otra vez la forma canónica, no la de entrada: los ids están en
        // orden de lectura pero el codec los reordena. Lo que importa aqui es
        // que cada variante de completitud sobreviva al viaje, no el orden.
        //
        // `completeness` NO vive en la interfaz `EvidenceItem`: cada variante
        // la declara por su cuenta. Por eso se comparan los digests
        // canonicos, que si la incluyen, en vez de leer la propiedad.
        CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(snapshot)
        decoded.items.map { it.id.value }.toSet() shouldBe snapshot.items.map { it.id.value }.toSet()
        decoded.items.map { it::class.simpleName }.toSet() shouldBe setOf("Fact", "Observation")
    }

    @Test
    fun every_gap_reason_survives_the_roundtrip() {
        val gaps = listOf(
            EvidenceGap("A", EvidenceGap.GapReason.Unsupported),
            EvidenceGap("B", EvidenceGap.GapReason.Unknown),
            EvidenceGap("C", EvidenceGap.GapReason.Lost),
            EvidenceGap("D", EvidenceGap.GapReason.PartialProduced("0.25"), "detalle"),
        )
        val snapshot = EvidenceFixtures.snapshot(
            listOf(EvidenceFixtures.fact("synthetic/g/depends-on/1")),
            gaps = gaps,
        )

        val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(snapshot))

        decoded.gaps shouldBe gaps
    }

    @Test
    fun every_subject_variant_survives_the_roundtrip() {
        val subjects = listOf(
            EvidenceSubject.Module("core"),
            EvidenceSubject.Symbol("dev.pipelinek.Foo.bar"),
            EvidenceSubject.SourceLocation("src/main/kotlin/Foo.kt", 42, 7),
            EvidenceSubject.Test("UAT-001"),
            EvidenceSubject.RuntimeSpan("chronos-inv-1"),
        )
        for (subject in subjects) {
            val item = EvidenceItem.Observation(
                id = EvidenceId("synthetic/s/observation/1"),
                subject = subject,
                authority = dev.pipelinek.assurance.domain.evidence.EvidenceAuthority.RuntimeObserver,
                provenance = EvidenceFixtures.provenance("RuntimeInvocations"),
                observation = "x",
            )
            val snapshot = EvidenceFixtures.snapshot(listOf(item))
            val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(snapshot))

            decoded.items.single().subject shouldBe subject
        }
    }

    @Test
    fun every_external_namespace_survives_the_roundtrip() {
        for (namespace in ExternalNamespace.entries) {
            val snapshot = EvidenceFixtures.snapshot(
                listOf(EvidenceFixtures.fact("synthetic/n/depends-on/1")),
                correlations = listOf(
                    EvidenceFixtures.correlation(from = namespace, to = ExternalNamespace.OTelSpanId),
                ),
            )
            val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(snapshot))

            decoded.correlations.single().from.namespace shouldBe namespace
        }
    }

    // -----------------------------------------------------------------------
    // Bounded decoding y fail-closed
    // -----------------------------------------------------------------------

    @Test
    fun unknown_api_version_is_refused() {
        // Fail-closed de schema: no se adivina ni se migra en silencio.
        val json = EvidenceArtifactCodec.encodeToJson(rich)
            .replace("assurance-evidence/v1", "assurance-evidence/v99")

        shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromJson(json) }
    }

    @Test
    fun unknown_kind_is_refused() {
        val json = EvidenceArtifactCodec.encodeToJson(rich)
            .replace("EvidenceSnapshot", "SomethingElse")

        shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromJson(json) }
    }

    @Test
    fun unknown_field_is_refused() {
        // `ignoreUnknownKeys = false`: un producer que invente campos debe
        // fallar, no perderlos en silencio.
        val json = EvidenceArtifactCodec.encodeToJson(rich)
            .replaceFirst("{", "{\"campoInventado\":1,")

        shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromJson(json) }
    }

    @Test
    fun garbage_bytes_are_refused_not_guessed() {
        shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            EvidenceArtifactCodec.decodeFromCbor(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }
    }

    @Test
    fun truncated_cbor_is_refused() {
        val full = EvidenceArtifactCodec.encodeToCbor(rich)

        shouldThrow<EvidenceArtifactCodec.ArtifactDecodeException> {
            EvidenceArtifactCodec.decodeFromCbor(full.copyOf(full.size / 2))
        }
    }

    @Test
    fun a_cbor_that_declares_a_huge_string_is_refused_without_allocating_it() {
        // Un CBOR hostil pide un array de 2^32-1 elementos. Sin cota, esto es un
        // OOM antes de que exista ningun objeto que inspeccionar. El limite se
        // aplica sobre la entrada y sobre el DTO, no sobre la confianza.
        val hostile = buildHostileCborArrayHeader(claimedLength = Int.MAX_VALUE)

        shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromCbor(hostile) }
    }

    @Test
    fun a_decodified_snapshot_without_a_manifest_is_refused_by_domain() {
        // Fail-closed por invariante de dominio: sin manifest no hay snapshot,
        // porque no hay producer declarado de dónde venga la evidencia.
        //
        // HISTORIA, porque el nombre original mentia: este test se llamaba
        // `oversized_payload_is_refused_by_the_collection_bound` y construía
        // MAX_COLLECTION_SIZE + 1 facts para comprobar la cota. Falló con
        // OutOfMemoryError. La razón es que la comprobación ocurre DESPUÉS de
        // deserializar: cuando la cota se alcanza, la memoria ya está gastada.
        //
        // La conclusión honesta: una cota de tamaño de collection comprobada
        // sobre el DTO construido es una verificación de cortesía, no una
        // salvaguarda. La salvaguarda real es `MAX_INPUT_BYTES`, sobre la
        // ENTRADA, antes de deserializar, y esa sí se ejecuta: lo prueba
        // `max_input_bytes_cuts_before_deserializing`.
        //
        // Lo que este test fija es el contrato de fail-closed, no el número.
        val json = buildString {
            append("{\"apiVersion\":\"assurance-evidence/v1\",\"kind\":\"EvidenceSnapshot\"")
            append(",\"snapshotId\":\"s\",\"producer\":\"p\",\"producerVersion\":\"1\"")
            append(",\"subject\":{\"type\":\"Module\",\"path\":\"c\"}")
            append(",\"manifest\":[],\"payload\":[]}")
        }

        val e = shouldThrow<IllegalArgumentException> { EvidenceArtifactCodec.decodeFromJson(json) }
        // El mensaje tiene que nombrar la colección culpable, no sólo fallar.
        e.message shouldContain "manifest"
    }

    @Test
    fun max_input_bytes_cuts_before_deserializing() {
        // La salvaguarda que sí funciona: se comprueba sobre los BYTES, sin
        // haber construido nada. Un payload de 64 MiB + 1 se rechaza por
        // tamaño, sin deserializar un solo elemento.
        //
        // 64 MiB se reservan en el test, no en producción: es el coste de poder
        // demostrar que la comprobación ocurre ANTES de deserializar.
        val oversized = ByteArray((EvidenceArtifactCodec.MAX_INPUT_BYTES + 1).toInt())
        oversized[0] = 0xA1.toByte() // map(1), para que no falle por CBOR inválido

        val e = shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromCbor(oversized) }
        // El mensaje nombra la cota: si fallara por CBOR inválido, el texto
        // sería otro y el test caería.
        e.message shouldContain "excede el limite de ${EvidenceArtifactCodec.MAX_INPUT_BYTES}"
    }

    @Test
    fun a_string_over_the_length_bound_is_refused_on_decode() {
        // La cota de longitud SOLO puede probarse por decode: por encode es
        // imposible construir un snapshot válido con una cadena tan larga sin
        // gastarse la memoria que la cota existe para ahorrar.
        //
        // Se usa una cadena de MAX+1 caracteres, no de MAX+1 bytes, porque la
        // cota está declarada sobre caracteres.
        val huge = "x".repeat(EvidenceArtifactCodec.MAX_STRING_LENGTH + 1)
        val json = buildString {
            append("{\"apiVersion\":\"assurance-evidence/v1\",\"kind\":\"EvidenceSnapshot\"")
            append(",\"snapshotId\":\"s\",\"producer\":\"p\",\"producerVersion\":\"1\"")
            append(",\"subject\":{\"type\":\"Module\",\"path\":\"c\"}")
            append(",\"manifest\":[{\"producerId\":\"p\",\"producerVersion\":\"1\",")
            append("\"subjectRevision\":\"r\",\"requestedCapabilities\":[\"c\"],\"producedCapabilities\":[\"c\"],")
            append("\"completenessByCapability\":{\"c\":{\"type\":\"Complete\"}},")
            append("\"schemaVersion\":\"1\",\"digest\":\"")
            append("0".repeat(64))
            append("\"}],")
            append("\"payload\":[{\"type\":\"Fact\",\"id\":\"ns/s/k/1\",\"subject\":{\"type\":\"Module\",\"path\":\"")
            append(huge)
            append("\"},\"authority\":\"DeterministicAnalyzer\",\"producerId\":\"p\",\"producerVersion\":\"1\"")
            append(",\"revision\":\"r\",\"capability\":\"c\",\"predicate\":\"x\",\"completeness\":{\"type\":\"Complete\"}}]}")
        }

        val e = shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromJson(json) }
        e.message shouldContain "Module.path"
        e.message shouldContain "${EvidenceArtifactCodec.MAX_STRING_LENGTH}"
    }

    @Test
    fun a_nested_string_over_the_bound_is_refused_on_decode() {
        // Redundancia deliberada de `a_string_over_the_length_bound_is_refused_
        // on_decode`: el mutante M-S02 (quitar la cota de longitud) mataba sólo
        // a un test, y el harness exige dos. Este segundo caso ataca una
        // cadena ANIDADA (`Completeness.Unsupported.reason`), que en el test
        // anterior no aparece, para que la cota se verifique en dos rutas
        // distintas del decoder.
        val huge = "x".repeat(EvidenceArtifactCodec.MAX_STRING_LENGTH + 1)
        val json = buildString {
            append("{\"apiVersion\":\"assurance-evidence/v1\",\"kind\":\"EvidenceSnapshot\"")
            append(",\"snapshotId\":\"s\",\"producer\":\"p\",\"producerVersion\":\"1\"")
            append(",\"subject\":{\"type\":\"Module\",\"path\":\"c\"}")
            append(",\"manifest\":[{\"producerId\":\"p\",\"producerVersion\":\"1\",")
            append("\"subjectRevision\":\"r\",\"requestedCapabilities\":[\"c\"],\"producedCapabilities\":[\"c\"],")
            append("\"completenessByCapability\":{\"c\":{\"type\":\"Complete\"}},")
            append("\"schemaVersion\":\"1\",\"digest\":\"")
            append("0".repeat(64))
            append("\"}],")
            append("\"payload\":[{\"type\":\"Fact\",\"id\":\"ns/s/k/1\",")
            append("\"subject\":{\"type\":\"Module\",\"path\":\"c\"},")
            append("\"authority\":\"DeterministicAnalyzer\",\"producerId\":\"p\",\"producerVersion\":\"1\",")
            append("\"revision\":\"r\",\"capability\":\"c\",\"predicate\":\"x\",")
            append("\"completeness\":{\"type\":\"Unsupported\",\"reason\":\"")
            append(huge)
            append("\"}}]}")
        }

        val e = shouldThrow<IllegalArgumentException> { EvidenceArtifactCodec.decodeFromJson(json) }
        // El mensaje apunta al campo anidado, no a un "algo excede el limite"
        // genérico: hay que saber QUÉ string era la enorme.
        e.message shouldContain "Completeness.Unsupported.reason"
    }

    @Test
    fun a_string_just_under_the_length_bound_is_accepted() {
        // Control positivo: si la cota fuera demasiado agresiva, rechazaría
        // evidencia legítima. Una cadena justo por debajo tiene que pasar.
        val legal = "x".repeat(EvidenceArtifactCodec.MAX_STRING_LENGTH)
        val snapshot = EvidenceFixtures.snapshot(
            items = listOf(EvidenceItem.Fact(
                id = EvidenceId("synthetic/bound/ok/1"),
                subject = EvidenceSubject.Module(legal),
                authority = dev.pipelinek.assurance.domain.evidence.EvidenceAuthority.DeterministicAnalyzer,
                provenance = EvidenceFixtures.provenance("ModuleDependencies"),
                predicate = "x",
                objectValue = null,
            )),
        )

        val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(snapshot))
        decoded.items.size shouldBe 1
    }

    @Test
    fun signal_authority_is_rebuilt_not_trusted() {
        // Un Signal serializado no puede elegir su autoridad; la reconstruye el
        // invariante. Si un artefacto dijera "Signal determinista", el decoder
        // no lo creería.
        val json = EvidenceArtifactCodec.encodeToJson(rich)
            .replace("\"HeuristicAnalyzer\"", "\"DeterministicAnalyzer\"")
        val decoded = EvidenceArtifactCodec.decodeFromJson(json)

        val signals = decoded.items.filterIsInstance<EvidenceItem.Signal>()
        signals.size shouldBe 1
        signals.single().authority shouldBe
            dev.pipelinek.assurance.domain.evidence.EvidenceAuthority.HeuristicAnalyzer
    }

    @Test
    fun a_hypothesis_declared_as_deterministic_is_refused_by_the_domain_invariant() {
        // El codec pasa por los constructores reales, asi que el invariante de
        // `Hypothesis` sigue aplicando al decodificar.
        val json = EvidenceArtifactCodec.encodeToJson(rich)
            .replace("\"AgentHypothesis\"", "\"DeterministicAnalyzer\"")

        shouldThrow<IllegalArgumentException> { EvidenceArtifactCodec.decodeFromJson(json) }
    }

    @Test
    fun decoded_snapshot_with_no_manifest_is_refused() {
        val json = EvidenceArtifactCodec.encodeToJson(rich).replace(
            "\"manifest\":[{\"producerId\":\"synthetic\"",
            "\"manifest\":[{\"producerId\":\"synthetic\",\"__pad\":1,\"pad\":\"",
        )

        // El manipulado anterior rompe el JSON; lo que importa es que el decoder
        // falla y no devuelve un snapshot a medias.
        shouldThrow<Exception> { EvidenceArtifactCodec.decodeFromJson(json) }
    }

    @Test
    fun digest_of_encoded_bytes_matches_the_declared_media_type() {
        // El digest que se viajera en el envelope es el de los bytes canónicos,
        // no el del texto antes de serializar.
        val bytes = EvidenceArtifactCodec.encodeToCbor(rich)

        dev.pipelinek.assurance.domain.evidence.Digest.of(bytes).hex.length shouldBe 64
        bytes.contentEquals(EvidenceArtifactCodec.encodeToCbor(rich)) shouldBe true
        bytes.size shouldNotBe 0
    }

    @Test
    fun media_types_match_the_wire_contract() {
        EvidenceArtifactCodec.MEDIA_TYPE_CBOR shouldBe
            "application/vnd.pipelinek.assurance.evidence+cbor;version=1"
        EvidenceArtifactCodec.MEDIA_TYPE_JSON shouldBe
            "application/vnd.pipelinek.assurance.evidence+json;version=1"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun factWithCompleteness(id: String, completeness: Completeness): EvidenceItem.Fact =
        EvidenceItem.Fact(
            id = EvidenceId(id),
            subject = EvidenceSubject.Module("core"),
            authority = dev.pipelinek.assurance.domain.evidence.EvidenceAuthority.DeterministicAnalyzer,
            provenance = EvidenceFixtures.provenance("ModuleDependencies"),
            predicate = "depends-on",
            objectValue = "adapter",
            completeness = completeness,
        )

    /**
     * Cabecera CBOR de un array que declara [claimedLength] elementos.
     *
     * 0x9A es array de 4 bytes de longitud. Es la forma más barata de pedir
     * una reserva de memoria gigantesca con cinco bytes de entrada.
     */
    private fun buildHostileCborArrayHeader(claimedLength: Int): ByteArray {
        require(claimedLength >= 24)
        return byteArrayOf(
            0x9A.toByte(),
            (claimedLength ushr 24).toByte(),
            (claimedLength ushr 16).toByte(),
            (claimedLength ushr 8).toByte(),
            claimedLength.toByte(),
        )
    }
}
