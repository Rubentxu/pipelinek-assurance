package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * M0 — Orden canónico de claves JSON.
 *
 * Ref: ROADMAP §M0, deuda "orden canónico real de claves JSON".
 *
 * POR QUÉ ESTE ARCHIVO EXISTE
 *
 * `kotlinx.serialization` emite las claves de un objeto en el orden de
 * DECLARACIÓN de la clase. Eso es estable por construcción y durante dos
 * cortes pasó por canónico. No lo es: estable significa "igual en esta versión
 * del compilador", y canónico significa "igual ante cualquier implementación
 * que serialice el mismo dato".
 *
 * Lo grave es que el digest NO lo delata: `digestSnapshot` se calcula sobre
 * `encodeSnapshot`, que es texto propio con orden explícito, no sobre el JSON
 * del envelope. El envelope lleva el digest de un lado y el JSON del otro, así
 * que pueden discrepar sin que nada se entere. Un test que sólo comprueba "el
 * JSON decodifica" pasa igual con orden de declaración que con orden canónico.
 *
 * NOTA SOBRE EL ENFOQUE
 *
 * Estos tests NO vuelven a implementar el análisis de JSON para contar claves.
 * Ya se intentan tres veces en este repo y las tres fallaron por suposiciones
 * falsas sobre el escapado (y una de ellas costó un día). Cuando el problema es
 * "el orden de las claves", un parser mal escrito produce un diagnóstico
 * equivocado, que es peor que no tener test.
 *
 * Por eso el orden se lee del ARBOL que devuelve el parser real, usando
 * `JsonObject.keys`, que es el orden en que aparecen en el texto. La
 * afirmación es "el texto sale ordenado", y se comprueba sobre el parseo real
 * del texto real.
 */
class CanonicalJsonOrderTest : AnnotationSpec() {

    private val parser = Json { isLenient = false }

    /**
     * Snapshot con los cuatro tipos de item, un gap, una correlación y un
     * manifest: para que el JSON tenga objetos ANIDADOS que ordenar, no un
     * sobre plano de primitivas. Ordenar un JSON sin objetos anidados se
     * cumple con un solo `sortedBy` en el nivel raíz, y el test pasaría sin
     * probar lo que dice probar.
     */
    private val snapshot = EvidenceFixtures.snapshot(
        items = listOf(
            EvidenceFixtures.fact("synthetic/alpha/depends-on/1"),
            EvidenceFixtures.observation("synthetic/delta/invocation/1"),
            EvidenceFixtures.signal("synthetic/beta/smell/1"),
            EvidenceFixtures.hypothesis("synthetic/gamma/hypothesis/1"),
        ),
        gaps = listOf(EvidenceGap("SymbolGraph", EvidenceGap.GapReason.Lost, "expiro la cache")),
        correlations = listOf(EvidenceFixtures.correlation()),
    )

    /** Comprueba que TODOS los objetos del árbol tienen sus claves ordenadas. */
    private fun assertOrdenado(nodo: kotlinx.serialization.json.JsonElement, ruta: String) {
        when (nodo) {
            is JsonObject -> {
                val claves = nodo.keys.toList()
                // El `shouldBe` sobre la lista ordenada es la aserción. Si
                // kotlinx serializara en orden de declaración, aquí caería con
                // las dos listas al lado, que es un diagnóstico utilizable.
                claves shouldBe claves.sorted()
                nodo.forEach { (k, v) -> assertOrdenado(v, "$ruta.$k") }
            }

            is JsonArray -> nodo.forEachIndexed { i, v -> assertOrdenado(v, "$ruta[$i]") }

            else -> Unit
        }
    }

    @Test
    fun json_keys_are_emitted_in_lexicographic_order_recursively() {
        val texto = EvidenceArtifactCodec.encodeToJson(snapshot)
        assertOrdenado(parser.parseToJsonElement(texto), "raiz")
    }

    @Test
    fun the_root_object_really_has_several_keys() {
        // Guarda contra el test anterior: si el envelope tuviera una sola
        // clave, "ordenado" se cumpliría trivialmente y el test no diría nada.
        // Este test es la red que hace que el otro signifique algo.
        val texto = EvidenceArtifactCodec.encodeToJson(snapshot)
        val claves = (parser.parseToJsonElement(texto) as JsonObject).keys
        (claves.size > 3) shouldBe true
        // La lista completa y EXACTA, no un "> 3". Escribir la lista al revés
        // que el orden canónico documenta el contrato exacto del envelope: si
        // alguien añade un campo, este test cae y hay que decidir
        // deliberadamente si es canónico y dónde va. Un `> 3` no lo
        // detectaría, que es justo lo que pasó con las `gaps` y `correlations`
        // la primera vez que se escribieron estas líneas.
        claves.toList() shouldBe listOf(
            "apiVersion",
            "correlations",
            "digest",
            "gaps",
            "kind",
            "manifest",
            "payload",
            "producer",
            "producerVersion",
            "snapshotId",
            "subject",
        )
    }

    @Test
    fun nested_objects_are_really_present() {
        // Segunda guarda: que el test anterior recorra objetos anidados y no
        // una lista plana de valores. Sin esto, "recursivo" es una afirmación.
        val texto = EvidenceArtifactCodec.encodeToJson(snapshot)
        val arbol = parser.parseToJsonElement(texto)
        val anidados = contarObjetos(arbol)
        (anidados > 3) shouldBe true
    }

    private fun contarObjetos(nodo: kotlinx.serialization.json.JsonElement): Int = when (nodo) {
        is JsonObject -> 1 + nodo.values.sumOf { contarObjetos(it) }
        is JsonArray -> nodo.sumOf { contarObjetos(it) }
        else -> 0
    }

    @Test
    fun json_is_stable_across_repeated_encodes() {
        EvidenceArtifactCodec.encodeToJson(snapshot) shouldBe
            EvidenceArtifactCodec.encodeToJson(snapshot)
    }

    @Test
    fun reordering_the_input_keys_does_not_change_the_output() {
        // La propiedad que de verdad importa: si dos JSON distintos SOLO se
        // diferencian en el orden de sus claves, al decodificar y re-codificar
        // tienen que dar el MISMO texto.
        //
        // Sin esto, el orden canónico sería una propiedad del serializador y
        // no del artefacto, que es la distinción que importa cuando el
        // artefacto lo escribe otra herramienta.
        val original = EvidenceArtifactCodec.encodeToJson(snapshot)
        val permutado = permutarRaiz(parser.parseToJsonElement(original) as JsonObject).toString()

        // El permutado tiene las mismas claves en otro orden: tiene que
        // decodificar igual y re-codificar al mismo texto.
        ((parser.parseToJsonElement(permutado) as JsonObject).keys.toList() !=
            (parser.parseToJsonElement(original) as JsonObject).keys.toList()) shouldBe true

        EvidenceArtifactCodec.encodeToJson(EvidenceArtifactCodec.decodeFromJson(permutado)) shouldBe
            original
    }

    /** Invierte el orden de las claves del objeto raíz, sin tocar los valores. */
    private fun permutarRaiz(objeto: JsonObject): JsonObject =
        JsonObject(objeto.entries.reversed().associate { it.key to it.value })

    @Test
    fun the_digest_does_not_depend_on_the_json_text() {
        // El digest se calcula sobre `encodeSnapshot`, no sobre el JSON del
        // envelope. Este test fija esa independencia, que es la razón por la
        // que el orden del JSON no lo detecta: un cambio de orden canónico
        // cambia los bytes del JSON y no cambia ni un bit del digest.
        CanonicalEncoder.digestSnapshot(snapshot) shouldBe
            CanonicalEncoder.digestSnapshot(
                EvidenceArtifactCodec.decodeFromJson(EvidenceArtifactCodec.encodeToJson(snapshot)),
            )

        // Y el texto JSON, en cambio, SÍ depende del orden: por eso hace falta
        // el orden canónico y no basta con que el digest cuadre.
        val permutado = permutarRaiz(
            parser.parseToJsonElement(EvidenceArtifactCodec.encodeToJson(snapshot)) as JsonObject,
        ).toString()
        permutado shouldNotBe EvidenceArtifactCodec.encodeToJson(snapshot)
    }
}