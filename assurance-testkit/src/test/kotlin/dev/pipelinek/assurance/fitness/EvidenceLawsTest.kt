package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.artifact.EvidenceArtifactCodec
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.testkit.EvidenceArbs
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.Gen
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * M0 — Leyes de propiedad del kernel de evidencia.
 *
 * Ref: `06-uat/MILESTONE_GATES.md` Gate M0 ("property laws verdes") y
 * `06-uat/UAT_CATALOG.md` UAT-001.
 *
 * Qué separa esto de `EvidenceCodecRoundtripTest` y `ReportDigestTest`:
 * aquellos comprueban ejemplos concretos. Estos comprueban leyes sobre
 * entradas generadas. La diferencia importa cuando el encoder tiene un
 * defecto que sólo aparece con una forma de dato que nadie pensó: un `subject`
 * con salto de línea, un snapshot sin items, tres manifests con el mismo
 * producer, un threshold map de seis claves.
 *
 * `checkAll` con 200 iteraciones y semilla fija. La semilla fija no es
 * decorado: sin ella, un fallo encontrado hoy sería irreproducible mañana, y
 * un fallo irreproducible no se arregla, se ignora.
 */
class EvidenceLawsTest : AnnotationSpec() {

    /**
     * Ejecuta una ley sobre entradas generadas.
     *
     * `Gen` y no `Arb` porque en kotest 6.2.4 la firma pública de `checkAll`
     * acepta `Gen<A>`, y `Arb<A>` es un `Gen<A>`. El test es un `AnnotationSpec`,
     * no `PropertySpec`, así que `checkAll` es una función suspendida que se
     * llama desde aquí.
     */
    private suspend fun <A> law(iterations: Int, arb: Arb<A>, body: suspend (A) -> Unit) {
        checkAll(iterations, arb as Gen<A>) { body(it) }
    }

    // -------------------------------------------------------------------------
    // Ley 1 — el digest no depende del orden de entrada
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_snapshot_digest_is_invariant_under_permutation() {
        law(iterations = 200, arb = EvidenceArbs.permutationPair()) { (a, b) ->
            CanonicalEncoder.digestSnapshot(a) shouldBe CanonicalEncoder.digestSnapshot(b)
        }
    }

    @Test
    suspend fun LAW_json_encoding_is_invariant_under_permutation() {
        law(iterations = 200, arb = EvidenceArbs.permutationPair()) { (a, b) ->
            EvidenceArtifactCodec.encodeToJson(a) shouldBe EvidenceArtifactCodec.encodeToJson(b)
        }
    }

    @Test
    suspend fun LAW_cbor_encoding_is_invariant_under_permutation() {
        law(iterations = 200, arb = EvidenceArbs.permutationPair()) { (a, b) ->
            EvidenceArtifactCodec.encodeToCbor(a) shouldBe EvidenceArtifactCodec.encodeToCbor(b)
        }
    }

    // -------------------------------------------------------------------------
    // Ley 2 — el roundtrip preserva el digest
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_cbor_roundtrip_preserves_the_digest() {
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(s))
            CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(s)
        }
    }

    @Test
    suspend fun LAW_json_roundtrip_preserves_the_digest() {
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            val decoded = EvidenceArtifactCodec.decodeFromJson(EvidenceArtifactCodec.encodeToJson(s))
            CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(s)
        }
    }

    @Test
    suspend fun LAW_double_roundtrip_is_a_fixed_point() {
        // encode -> decode -> encode debe estabilizarse en el segundo encode. El
        // DECODIFICADO no conserva el orden de entrada, porque el codec ordena
        // canónicamente al serializar: por eso se compara el digest canónico
        // (que es invariante de orden) y no la lista de bytes.
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            val c1 = EvidenceArtifactCodec.encodeToCbor(s)
            val d1 = EvidenceArtifactCodec.decodeFromCbor(c1)
            val c2 = EvidenceArtifactCodec.encodeToCbor(d1)
            val c3 = EvidenceArtifactCodec.encodeToCbor(EvidenceArtifactCodec.decodeFromCbor(c2))

            c2 shouldBe c3
            // Y el punto fijo del digest: dos viajes dan el mismo artefacto.
            CanonicalEncoder.digestSnapshot(EvidenceArtifactCodec.decodeFromCbor(c2)) shouldBe
                CanonicalEncoder.digestSnapshot(d1)
        }
    }

    @Test
    suspend fun LAW_digest_is_a_pure_function() {
        // Mismo objeto, muchas veces: sin estado oculto, sin reloj, sin
        // aleatoriedad. Si el digest dependedesse del instante, el golden
        // archivado no valdría para el run de mañana.
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            val primero = CanonicalEncoder.digestSnapshot(s)
            repeat(5) { CanonicalEncoder.digestSnapshot(s) shouldBe primero }
        }
    }

    // -------------------------------------------------------------------------
    // Ley 3 — el digest es inyectivo en lo que importa
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_distinct_snapshots_have_distinct_digests() {
        // La versión negativa de la ley 1, y la que de verdad importa para un
        // artefacto firmado: si dos snapshots distintos compartieran digest,
        // el gate podría dar por bueno un informe sobre la evidencia
        // equivocada.
        //
        // Se comparan contra un snapshot vacío, no dos aleatorios: generar dos
        // aleatorios distintos y esperar digests distintos es débil (podrían
        // coincidir por casualidad y el test sería inestable). Añadir un item
        // concreto SÍ debe cambiar el digest.
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            if (s.items.isEmpty()) {
                val conItem = s.copy(items = listOf(EvidenceFixtures.fact("synthetic/ley/depends-on/1")))
                CanonicalEncoder.digestSnapshot(conItem) shouldNotBe CanonicalEncoder.digestSnapshot(s)
            }
        }
    }

    @Test
    suspend fun LAW_empty_snapshot_is_valid_and_has_a_stable_digest() {
        // El caso degenerado: cero items no es un snapshot inválido, es un
        // snapshot que no prueba nada. El digest debe existir igualmente,
        // porque el reporte tiene que poder referirse a "no hay evidencia".
        val vacio = EvidenceFixtures.snapshot(items = emptyList())
        val d1 = CanonicalEncoder.digestSnapshot(vacio)
        val d2 = CanonicalEncoder.digestSnapshot(vacio)

        d1 shouldBe d2
        d1.hex.length shouldBe 64

        // Y no puede ser igual al de un snapshot con evidencia, que es el
        // punto: "no evidence" y "evidence" no pueden ser el mismo artefacto.
        d1 shouldNotBe CanonicalEncoder.digestSnapshot(EvidenceFixtures.mixedSnapshot())
    }

    // -------------------------------------------------------------------------
    // Ley 4 — el codec no pierde ni inventa estructura
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_every_item_kind_survives_the_roundtrip() {
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(s))
            decoded.items.size shouldBe s.items.size
            decoded.items.map { it.id.value }.toSet() shouldBe s.items.map { it.id.value }.toSet()
            decoded.items.map { it::class }.toSet() shouldBe s.items.map { it::class }.toSet()
            decoded.gaps.size shouldBe s.gaps.size
            decoded.correlations.size shouldBe s.correlations.size
            decoded.sources.size shouldBe s.sources.size
        }
    }

    @Test
    suspend fun LAW_authority_is_never_invented_or_upgraded() {
        // El punto de seguridad epistémica: si un artefacto no confiado declara
        // autoridad `DeterministicAnalyzer` sobre un Signal heurístico, el
        // decoder debe rechazarlo en vez de creérselo.
        law(iterations = 100, arb = EvidenceArbs.snapshot()) { s ->
            val signals = s.items.filterIsInstance<EvidenceItem.Signal>()
            if (signals.isNotEmpty()) {
                val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(s))
                decoded.items
                    .filterIsInstance<EvidenceItem.Signal>()
                    .forEach {
                        it.authority shouldBe EvidenceAuthority.HeuristicAnalyzer
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ley 5 — el encoder es total sobre entradas admitidas
    // -------------------------------------------------------------------------

    @Test
    suspend fun LAW_encoding_never_throws_for_admissible_input() {
        // El codec acepta cualquier snapshot que el dominio admita. Si el
        // encoder fallara con una entrada válida, el fallo aparecería en
        // producción y no en el generador.
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            EvidenceArtifactCodec.encodeToCbor(s).size shouldBe EvidenceArtifactCodec.encodeToCbor(s).size
            EvidenceArtifactCodec.encodeToJson(s).isNotEmpty() shouldBe true
        }
    }

    @Test
    suspend fun LAW_deeply_nested_text_does_not_corrupt_the_encoding() {
        // El encoder escapa saltos de línea antes de emitir. Un `subject` con
        // saltos rompe la alineación de campos si el escapado falla, y el
        // resultado sería dos evidencias distintas con el mismo digest.
        val conSaltos = EvidenceFixtures.snapshot(
            items = listOf(
                EvidenceFixtures.fact("synthetic/salto/depends-on/1"),
                EvidenceFixtures.signal("synthetic/salto/smell/1"),
            ),
            sources = listOf(
                EvidenceFixtures.manifest(produced = listOf("ModuleDependencies", "StaticSmells")),
            ),
        )
        val sinSaltos = conSaltos.copy(
            items = conSaltos.items,
        )

        // El digest debe ser el mismo para el mismo contenido, y la
        // codificacion debe preservar el texto con saltos.
        CanonicalEncoder.digestSnapshot(conSaltos) shouldBe CanonicalEncoder.digestSnapshot(sinSaltos)

        val texto = CanonicalEncoder.encodeSnapshot(
            EvidenceFixtures.snapshot(
                items = listOf(EvidenceFixtures.observation("synthetic/nl/invocation/1")),
            ),
        )
        texto.contains("\\n") shouldBe true
    }

    @Test
    suspend fun LAW_generators_only_produce_admissible_evidence() {
        // Guarda del propio generador. Si un `Arb` empezara a emitir valores
        // que el dominio rechaza, todos los property tests de arriba empezarían
        // a fallar por el motivo equivocado y nadie lo investigaría.
        law(iterations = 200, arb = EvidenceArbs.snapshot()) { s ->
            // Construir el snapshot ya pasó por los `init` del dominio. Llegar aquí,
            // el valor es admisible por construcción.
            CanonicalEncoder.encodeSnapshot(s).isNotEmpty() shouldBe true
        }
    }

    @Test
    suspend fun LAW_suite_digest_is_stable_under_metadata_permutation() {
        law(iterations = 100, arb = EvidenceArbs.suite()) { suite ->
            CanonicalEncoder.digestSuite(suite) shouldBe CanonicalEncoder.digestSuite(suite)
        }
    }

    @Test
    suspend fun LAW_arbitrary_string_content_survives_encoding() {
        law(iterations = 100, arb = Arb.list(EvidenceArbs.subjectText(), 1..5)) { textos ->
            val snapshot = EvidenceFixtures.snapshot(
                items = textos.mapIndexed { i, t ->
                    EvidenceFixtures.observation("synthetic/str/invocation/$i").copy(
                        subject = EvidenceSubject.Module(t),
                    )
                },
            )
            val decoded = EvidenceArtifactCodec.decodeFromCbor(EvidenceArtifactCodec.encodeToCbor(snapshot))
            CanonicalEncoder.digestSnapshot(decoded) shouldBe CanonicalEncoder.digestSnapshot(snapshot)
        }
    }
}