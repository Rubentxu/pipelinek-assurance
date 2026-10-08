package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceAssertion
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.ProofRef

/**
 * Assertions del vertical hexagonal: `noDependency` y `acyclic`.
 *
 * Las dos comparten la misma forma de pensar sobre el witness, y por eso
 * comparten un módulo: **el witness tiene que ser mínimo y reproducible sin
 * logs**. Un contraejemplo que obliga a leer un log para entender qué falló no
 * es evidencia, es un mensaje de error con tipado (STOP de M1).
 *
 * "Mínimo" tiene un significado concreto aquí y es comprobable:
 *
 *  - `noDependency` devuelve el **camino más corto** entre la capa prohibida y
 *    la culpable, no cualquier camino. Con dos rutas posibles, devolver la
 *    larga produce un witness que varía con el orden de exploración del grafo,
 *    y un witness que varía no es reproducible.
 *  - `acyclic` devuelve el **ciclo más pequeño**, y en orden canónico dentro
 *    del ciclo. Un SCC de tres nodos devuelve el ciclo de tres; uno de cuatro
 *    con un sub-ciclo de dos devuelve el de dos, porque el sub-ciclo es el
 *    defecto real y el resto es contexto.
 */

/**
 * Matriz de dependencias permitidas entre capas.
 *
 * Se declara como dato y no se infiere del `rank` del enum, porque la regla
 * "sólo se depende de capas inferiores" NO es la regla de la arquitectura
 * hexagonal, y se comporta de forma distinta en los tres casos que importan:
 *
 *  - `Adapters -> Infrastructure` **sí** es legal (un adaptador llama a un
 *    cliente HTTP que vive en infrastructure), y `Infrastructure -> Adapters`
 *    también (el runtime registra los adaptadores). La dependencia
 *    infrastructure/adapters va en las dos direcciones según el proyecto.
 *  - `Infrastructure -> Infrastructure` **sí** es legal: un cliente de base de
 *    datos depende de otro cliente de base de datos. Prohibirlo produce un
 *    grafo irreal y hace que la assertion falle por cosas que no son
 *    defectos. Es lo que pasaba al escribir esto: la primera versión de la
 *    matriz no incluía la propia capa, y los tests "fallaban" describiendo
 *    `infra-a -> infra-b` como infracción cuando es la dependencia más normal
 *    que hay.
 *  - `Adapters -> Application` es legal, y `Application -> Adapters` no.
 *
 * La regla es una sola y se dice entera: **no se depende de una capa más
 * externa**. Todo lo demás es legal, incluida la capa propia.
 *
 * Eso da la matriz completa, y es la primera versión coherente. Las dos
 * anteriores la escribieron por partes y se quedaron a medias:
 *
 *  - Se añadieron `Adapters -> Adapters` e `Infrastructure -> Infrastructure` como
 *    legales porque son dependencias normales de cualquier proyecto, y con
 *    razón. Pero se dejaron `Domain -> Domain` y `Application -> Application`
 *    como prohibidas "porque el centro no depende de nada". Eso es
 *    incoherente: la misma dependencia dentro de la capa es legal en los
 *    bordes e ilegal en el centro, y no hay ninguna razón de arquitectura
 *    detrás, sólo que esas dos filas no se pensaron.
 *  - El self-model de este repo es lo que lo destapó, y no por una casualidad:
 *    `assurance-artifact` depende de `assurance-engine`, y los dos son
 *    `Application`. Escribí el fixture con los dos como Application porque los
 *    dos son lógica de aplicación, y la assertion lo marcó como defecto. O la
 *    política era incorrecta o el self-model mentía, y el self-model decía la
 *    verdad: el codec de artefactos necesita el grafo del engine.
 *
 * Lo que sí se prohíbe en hexagonal es el sentido **hacia afuera**:
 *
 *  - `Application` no depende de `Adapters` ni de `Infrastructure`;
 *  - **`Adapters` no depende de `Infrastructure`.** Ésta es la que la ley
 *    4x4 cazó: la matriz la tenía como legal porque "un adaptador llama a un
 *    cliente HTTP" suena razonable, pero ese cliente HTTP es un puerto del
 *    domain, no infraestructura. Si un adaptador necesita un cliente
 *    concreto, lo que necesita es un puerto. Permitir la dependencia deja que
 *    la infraestructura se cuele en los adaptadores y el centro pierde el
 *    sentido.
 *  - `Domain` no depende de ninguna de las otras tres.
 *
 * `Domain -> Domain` sí es legal: dos entidades del dominio colaboran, y
 * prohibirlas produce grafos que no existen. Un grafo irreal hace que la
 * assertion falle por cosas que no son defectos, que es la forma más cara de
 * perder la confianza en el gate.
 */
object HexagonalPolicy {
    /** Capas de las que se puede depender, por capa de origen. */
    val permitidas: Map<Layer, Set<Layer>> = mapOf(
        Layer.Domain to setOf(Layer.Domain),
        Layer.Application to setOf(Layer.Domain, Layer.Application),
        Layer.Adapters to setOf(Layer.Domain, Layer.Application, Layer.Adapters),
        Layer.Infrastructure to setOf(
            Layer.Domain,
            Layer.Application,
            Layer.Adapters,
            Layer.Infrastructure,
        ),
    )

    /** Una dependencia `from -> to` es legal si `to` está permitida desde `from`. */
    fun permitida(desde: Layer, hacia: Layer): Boolean = hacia in (permitidas[desde] ?: emptySet())

    /** La primera capa hacia la que no se permite depender, para el mensaje. */
    fun primeraProhibida(desde: Layer): Layer? = Layer.entries.firstOrNull { !permitida(desde, it) }
}

/**
 * `noDependency`: ninguna capa depende de una capa que la política prohíbe.
 *
 * Devuelve el camino más corto desde el módulo infractor hasta el módulo
 * prohibido, con la arista exacta en [Counterexample.DependencyPath.path]. Es
 * lo que UAT-003 llama "witness path exacto", y "exacto" significa el más
 * corto, no uno cualquiera.
 */
fun noDependency(
    assertionId: AssertionId = AssertionId("architecture.no-dependency"),
    snapshotId: String,
    evidenceIds: List<EvidenceId>,
): AssuranceAssertion<DependencyGraph> = AssuranceAssertion { graph ->
    // Un módulo es infractor si su capa tiene alguna capa prohibida como
    // destino, y el defecto es que LLEGUE a ella. Se busca el camino más
    // corto desde el módulo infractor hasta el módulo prohibido más cercano.
    //
    // La primera versión iteraba sobre las ARISTAS prohibidas y pedía el
    // camino más corto de `edge.from` a `edge.to`. Eso es degenerado: si
    // `(from, to)` es una arista del grafo, el BFS la ve en la primera
    // expansión y devuelve siempre `[from, to]`. Veinte líneas de búsqueda
    // para calcular su propia entrada, y el `sortedBy { camino.size }` que
    // elegía el testigo era código muerto: todas las infracciones empataban.
    //
    // Lo que UAT-003 llama "witness path exacto" es el camino más corto entre
    // el módulo infractor y la capa prohibida, y ese camino puede tener saltos
    // de verdad: `domain-a -> adapter-b -> infra-c` es una infracción aunque no
    // exista ninguna arista que vaya de `domain` a `infra`.
    val infractores = graph.modules.filter { modulo ->
        val desde = graph.layers.getValue(modulo)
        Layer.entries.any { hacia -> !HexagonalPolicy.permitida(desde, hacia) }
    }
    val prohibidasDesde = infractores.associateWith { modulo ->
        val desde = graph.layers.getValue(modulo)
        Layer.entries.filter { hacia -> !HexagonalPolicy.permitida(desde, hacia) }
            .toSet()
    }

    val candidatos: List<Infraccion> = buildList {
        for (modulo in infractores) {
            // Destinos prohibidos para ESTE módulo, ordenados para que el
            // recorrido sea idéntico con independencia de nada externo.
            for (capaDestino in prohibidasDesde.getValue(modulo).sortedBy { it.name }) {
                // Módulos que están en la capa prohibida, alcanzables desde
                // `modulo`. Se elige el de camino MÁS CORTO, y si empatan se
                // escoge el canónico para que el witness no dependa del
                // orden de exploración.
                //
                // Aquí NO se hace el caso "destino adyacente" por separado:
                // una versión anterior optimizaba el caso de la arista directa
                // y añadía `[modulo, destino]` sin comprobar que la arista
                // existiera. Eso declaraba infracción entre dos módulos sin
                // ninguna arista que los uniera, que es fabricar un defecto.
                // El BFS ya devuelve el camino de un salto si la arista está.
                val masCerca = graph.modules
                    .asSequence()
                    // El destino tiene que ser OTRO módulo. `caminoMasCorto`
                    // devuelve `[nodo]` cuando origen y destino coinciden, que
                    // tiene longitud 1 y gana cualquier comparación de
                    // longitud. Sin este filtro, un módulo de `Application`
                    // se declaraba infracor de sí mismo con un "camino" de
                    // cero saltos, y el grafo sano fallaba.
                    .filter { it != modulo }
                    .filter { graph.layers.getValue(it) == capaDestino }
                    .map { it to caminoMasCorto(graph, desde = modulo, hasta = it) }
                    .filter { (_, camino) -> camino != null }
                    .sortedWith(compareBy({ it.second!!.size }, { it.first }))
                    .firstOrNull()

                val elegido = masCerca ?: continue
                add(
                    Infraccion(
                        desde = modulo,
                        hacia = capaDestino,
                        destino = elegido.first,
                        camino = elegido.second!!,
                    ),
                )
            }
        }
    }

    if (candidatos.isEmpty()) {
        AssertionResult.Passed(ProofRef(snapshotId, evidenceIds, assertionId))
    } else {
        // Orden estable: primero por longitud del camino, luego por módulo y
        // destino en orden canónico. Sin el desempate, dos infracciones con
        // caminos de la misma longitud darían el witness más o menos al azar
        // según el orden de exploración.
        val elegida = candidatos
            .sortedWith(compareBy({ it.camino.size }, { it.desde }, { it.destino }))
            .first()

        val desde = elegida.desde
        val hacia = elegida.hacia
        val camino = elegida.camino

        AssertionResult.Failed(
            Counterexample.DependencyPath(
                assertionId = assertionId,
                subjectRefs = listOf(typedRef(camino.first()), typedRef(camino.last())),
                evidenceRefs = evidenceIds,
                explanation = "La capa $desde no puede alcanzar la capa $hacia: " +
                    "${camino.joinToString(" -> ")}",
                reproductionHints = listOf(
                    "ruta minima: ${camino.joinToString(" -> ")}",
                    "capas: ${camino.joinToString(" -> ") { graph.layers.getValue(it).name }}",
                    "capa prohibida desde $desde: ${prohibidasDesde.getValue(desde).sortedBy { it.name }.joinToString(", ") { it.name }}",
                ),
                path = camino,
                fromLayer = graph.layers.getValue(camino.first()).name,
                toLayer = graph.layers.getValue(camino.last()).name,
            ),
        )
    }
}

/**
 * `acyclic`: el grafo de dependencias no tiene ciclos.
 *
 * Devuelve el ciclo más pequeño en orden canónico. El caso que importa es
 * `A -> B -> C -> A`, que es el mutante M-A03: con las tres aristas, cada
 * nodo tiene grado de salida 1 y la topología **parece** un árbol. Sólo el
 * recorrido cerrado lo delata, y por eso la búsqueda es explícita y no un
 * "visited" mal puesto.
 */
fun acyclic(
    assertionId: AssertionId = AssertionId("architecture.acyclic"),
    snapshotId: String,
    evidenceIds: List<EvidenceId>,
): AssuranceAssertion<DependencyGraph> = AssuranceAssertion { graph ->
    val ciclo = cicloMasPequeno(graph)

    if (ciclo == null) {
        AssertionResult.Passed(ProofRef(snapshotId, evidenceIds, assertionId))
    } else {
        AssertionResult.Failed(
            Counterexample.Cycle(
                assertionId = assertionId,
                subjectRefs = ciclo.map { typedRef(it) },
                evidenceRefs = evidenceIds,
                explanation = "Ciclo de dependencias de ${ciclo.size} modulos: " +
                    ciclo.joinToString(" -> ") + " -> ${ciclo.first()}",
                reproductionHints = listOf(
                    "ciclo: ${ciclo.joinToString(" -> ")} -> ${ciclo.first()}",
                    "cerrar el ciclo: eliminar ${
                        ciclo.zipWithNext { a, b -> "$a -> $b" }.plus("${ciclo.last()} -> ${ciclo.first()}").first()
                    }",
                ),
                cycle = ciclo,
            ),
        )
    }
}

/** Una arista prohibida, con su contexto, antes de elegir cuál se reporta. */
private data class Infraccion(
    val desde: String,
    val hacia: Layer,
    val destino: String,
    val camino: List<String>,
)

// ---------------------------------------------------------------------------
// Búsqueda: camino más corto y ciclo más pequeño
// ---------------------------------------------------------------------------

/**
 * Camino más corto de `desde` a `hasta`, en número de aristas.
 *
 * BFS, no DFS. No por rendimiento: por determinismo. Un DFS devuelve *un*
 * camino, y cuál depende del orden de exploración. Con el orden canónico de
 * aristas el resultado es determinista, pero sigue siendo el camino que
 * encuentre primero, no el más corto. Un witness que depende del algoritmo es
 * un witness que se mueve cuando se cambia el algoritmo.
 */
internal fun caminoMasCorto(graph: DependencyGraph, desde: String, hasta: String): List<String>? {
    if (desde == hasta) return listOf(desde)
    if (desde !in graph.modules || hasta !in graph.modules) return null

    val vistos = mutableSetOf(desde)
    // Cola de pares (nodo actual, camino desde el origen). El camino viaja con
    // el nodo en vez de reconstruirse: sin predecesores, y sin depender de un
    // `parent` mutable que otro caller pudiera tocar.
    val cola = ArrayDeque<Pair<String, List<String>>>()
    cola.addLast(desde to listOf(desde))

    while (cola.isNotEmpty()) {
        val (nodo, camino) = cola.removeFirst()
        for (siguiente in graph[nodo].dependsOn.sorted()) {
            if (siguiente == hasta) return camino + siguiente
            if (vistos.add(siguiente)) {
                cola.addLast(siguiente to (camino + siguiente))
            }
        }
    }
    return null
}

/**
 * Ciclo más pequeño del grafo, como lista de nodos SIN repetir el primero.
 *
 * BFS sobre caminos, no DFS recursivo con `visited` global: el `visited` global
 * es correcto para *detectar* un ciclo pero no para encontrar el más pequeño,
 * porque descarta caminos que aún podrían cerrar un ciclo más corto.
 *
 * El coste es exponencial en grafos densos, y eso es aceptable aquí por una
 * razón concreta: el grafo de un repositorio tiene decenas de nodos y pocas
 * aristas, y un ciclo pequeño se encuentra a los pocos pasos. Se deja escrito
 * porque es la clase de límite que un día alguien va a necesitar levantar, y
 * es mejor que se descubra leyendo el algoritmo.
 */
internal fun cicloMasPequeno(graph: DependencyGraph): List<String>? {
    val cola = ArrayDeque<List<String>>()
    // Se siembra con CADA nodo, y no sólo con el mínimo global, por un motivo
    // concreto: si se exigiera arrancar por el nodo lexicográficamente menor
    // del ciclo, un ciclo cuyo menor nodo no es el mínimo del grafo nunca se
    // cerraría. El canonico va DENTRO del ciclo (menor primero), no en el
    // arranque de la búsqueda.
    for (inicio in graph.modules) {
        cola.addLast(listOf(inicio))
    }

    var examined = 0
    while (cola.isNotEmpty()) {
        val camino = cola.removeFirst()
        examined++
        if (examined > MAX_CYCLE_SEARCH) {
            throw IllegalStateException(
                "la busqueda de ciclo minimo supero $MAX_CYCLE_SEARCH caminos sin cerrar; " +
                    "el grafo es demasiado denso para este algoritmo y el limite " +
                    "debe revisarse, no subirse a ojo",
            )
        }
        for (siguiente in graph[camino.last()].dependsOn.sorted()) {
            when {
                // Vuelta al origen: ciclo cerrado.
                siguiente == camino.first() && camino.size >= 2 -> return camino.normalizado()
                // Volver a un nodo INTERMEDIO también cierra un ciclo: A->B->C->B
                // es un ciclo de dos (B, C) aunque el camino sea más largo. Sin
                // este caso, el BFS sólo encontraría el ciclo que cierra en el
                // arranque y el sub-ciclo se escaparía.
                siguiente in camino -> {
                    val desdeIndice = camino.indexOf(siguiente)
                    if (desdeIndice >= 0 && camino.size - desdeIndice >= 2) {
                        return camino.subList(desdeIndice, camino.size).normalizado()
                    }
                }
                else -> cola.addLast(camino + siguiente)
            }
        }
    }
    return null
}

/**
 * Rota el ciclo para que empiece por su nodo mínimo.
 *
 * Un mismo ciclo se puede recorrer en rotaciones distintas y en ambos sentidos;
 * sin esto, dos ejecuciones sobre el mismo grafo podrían reportar el mismo
 * ciclo como `[A, B, C]` y como `[B, C, A]`, que son el mismo defecto descrito
 * de dos maneras. Un witness tiene que ser único, no sólo correcto.
 */
private fun List<String>.normalizado(): List<String> {
    if (size < 2) return this
    val i = indexOf(min())
    if (i <= 0) return this
    return drop(i) + take(i)
}

/** Techo de caminos examinados antes de declarar el límite alcanzado. */
internal const val MAX_CYCLE_SEARCH = 200_000

private fun typedRef(nombre: String): TypedExternalId =
    TypedExternalId(dev.pipelinek.assurance.domain.evidence.ExternalNamespace.AssuranceEvaluationId, nombre)