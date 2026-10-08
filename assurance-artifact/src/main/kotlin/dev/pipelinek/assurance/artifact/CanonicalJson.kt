package dev.pipelinek.assurance.artifact

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Orden canónico real de claves JSON.
 *
 * POR QUÉ EXISTE
 *
 * `kotlinx.serialization` emite las claves de un objeto en el ORDEN DE
 * DECLARACIÓN de la clase. Eso es estable por construcción, y por eso durante
 * dos cortes pudo parecer suficiente. No lo es: estable no es canónico.
 * Estable significa "igual en esta versión del compilador"; canónico
 * significa "igual ante cualquier implementación que serialice el mismo
 * dato".
 *
 * Qué rompe tener orden de declaración en vez de orden canónico:
 *
 * 1. Un artefacto JSON re-serializado por otra herramienta (un formateador,
 *    un proxy, otro lenguaje) sale con otro orden y no coincide byte a byte.
 *    La comparación byte a byte de artefactos JSON es la que se usa para
 *    "este report es el mismo que el de ayer", y deja de servir.
 * 2. Dos DTO distintos con los mismos datos y distinto orden de declaración
 *    producen artefactos distintos. El mismo problema que M-R03 tenía en CBOR,
 *    en el medio que no tenía defensa.
 *
 * NO afecta al digest, y por eso el digest no lo delata: `digestSnapshot` se
 * calcula sobre `encodeSnapshot`, que es texto propio con orden explícito, no
 * sobre el JSON del envelope. El envelope lleva el digest de un lado y el JSON
 * del otro, y por eso pueden discrepar sin que nada se entere.
 *
 * EL CRITERIO
 *
 * Orden lexicográfico UTF-16 del nombre de la clave, aplicado recursivamente a
 * todos los objetos. UTF-16 y no UTF-8 porque es el orden en el que compara
 * `String.compareTo`, que es el que ya usa `sortedWith` en el resto del
 * codificador. Mezclar dos criterios de orden dentro del mismo proyecto es como
 * se consigue un "canónico" que dos implementaciones no comparten.
 *
 * Los ARRAYS no se reordenan. Un array es una secuencia con significado, y
 * ordenarlo destruiría información: el orden de `results` lo fija
 * `canonicalResults`, no esta función. Esta sólo toca el interior de los
 * objetos.
 */
internal object CanonicalJson {

    /** El mismo `Json` que usa el codec, para no divergir en escapes ni flags. */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    /**
     * Serializa un DTO a JSON con las claves de TODOS los objetos en orden
     * lexicográfico.
     *
     * Se serializa primero con el serializador del codec y luego se reordena el
     * árbol, en vez de construir el árbol a mano: así el esquema sigue siendo el
     * que declara el DTO y no una segunda definición del mismo dato que
     * habría que mantener en paralelo.
     */
    inline fun <reified T> encodeCanonical(serializer: KSerializer<T>, value: T): String =
        canonicalize(json.encodeToJsonElement(serializer, value)).toString()

    /** Ordena recursivamente las claves de un árbol JSON. */
    fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            // `sortedBy` sobre el nombre de la clave usa el orden natural de
            // `String`, que es comparación UTF-16: el criterio del KDoc.
            element.entries.sortedBy { it.key }.forEach { (key, value) ->
                put(key, canonicalize(value))
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEach { add(canonicalize(it)) }
        }

        // Primitivas y null: no tienen claves que ordenar.
        is JsonPrimitive, JsonNull -> element
    }
}