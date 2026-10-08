package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.engine.architecture.DependencyEdge
import dev.pipelinek.assurance.engine.architecture.DependencyGraph
import dev.pipelinek.assurance.engine.architecture.Layer

/**
 * Codec del fixture de dependency graph, en texto plano y determinista.
 *
 * **Por qué texto y no JSON.** El fixture describe la arquitectura declarada a
 * mano de un repositorio: lo lee una persona, lo edita a mano y lo revisa en un
 * diff. Un formato de texto con una arista por línea hace que el diff de un PR
 * que añade una dependencia sea exactamente una línea añadida. Con JSON, añadir
 * una arista al final de un array produce una coma y un salto de línea, y el
 * diff mezcla el cambio con el ruido.
 *
 * Lo que NO se negocia es la determinismo: dos fixtures con el mismo grafo
 * producen el mismo texto byte a byte, sea cual sea el orden en que se declararon
 * los módulos y las aristas. Eso no es un detalle del codec: es la propiedad de
 * la que depende que dos ejecuciones del self-model produzcan el mismo digest.
 *
 * **Fail-closed en la decodificación.** Un fixture que menciona un módulo sin
 * capa, una arista hacia un módulo inexistente o una capa que no existe se
 * rechaza al leer, con un error que nombra la línea. No se rellena con
 * valores por omisión: un fixture con un hueco es una arquitectura que nadie
 * declaró, y tratarla como "todo permitido" es la forma más silenciosa de
 * que un grafo mal declarado pase el gate.
 */
object DependencyGraphFixtureCodec {
    private const val MODULES = "modules"
    private const val EDGES = "edges"

    /** Serializa a la forma canónica. Es idempotente: `encode(decode(t)) == t`. */
    fun encode(graph: DependencyGraph): String = buildString {
        appendLine("# dependency graph fixture")
        appendLine("# formato: <seccion> seguido de entradas ordenadas; determinista")
        appendLine(MODULES)
        graph.modules.forEach { modulo ->
            appendLine("$INDENT${modulo} ${SEP_LAYER} ${graph.layers.getValue(modulo).name}")
        }
        appendLine(EDGES)
        graph.edges.forEach { arista ->
            appendLine("$INDENT${arista.from}$SEP_ARROW${arista.to}")
        }
    }

    /**
     * Decodifica y devuelve un grafo **ya canónico**.
     *
     * La canonicalización no se deja para después: el grafo que sale de aquí
     * cumple el invariante del tipo, y un caller que construya el grafo a mano
     * por otra ruta tiene que usar la fábrica, que también lo cumple.
     */
    fun decode(texto: String): DependencyGraph {
        val modulos = mutableListOf<Pair<String, Layer>>()
        val aristas = mutableListOf<DependencyEdge>()
        var seccion: String? = null
        var linea = 0

        for (bruta in texto.lineSequence()) {
            linea++
            val sinComentarios = bruta.substringBefore('#').trim()
            if (sinComentarios.isEmpty()) continue

            when {
                sinComentarios == MODULES || sinComentarios == EDGES -> seccion = sinComentarios
                seccion == null -> throw IllegalArgumentException(
                    "linea $linea: entrada antes de cualquier seccion: '$sinComentarios'",
                )

                seccion == MODULES -> modulos += parseModulo(linea, sinComentarios)
                else -> aristas += parseArista(linea, sinComentarios)
            }
        }

        if (modulos.isEmpty()) {
            throw IllegalArgumentException("el fixture no declara ningun modulo: un grafo vacio no dice nada")
        }

        val nombres = modulos.map { it.first }
        val duplicados = nombres.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicados.isEmpty()) {
            "modulos declarados mas de una vez: ${duplicados.sorted()}"
        }

        val capas = modulos.associate { it.first to it.second }
        val conocidas = nombres.toSet()
        val huerfanas = aristas.map { it.to }.distinct().filterNot { it in conocidas }
        require(huerfanas.isEmpty()) {
            "aristas hacia modulos no declarados: ${huerfanas.sorted()}. " +
                "El fixture tiene que declarar todos los modulos: un destino sin capa " +
                "es invisible para la assertion y por tanto legal por omision."
        }

        return DependencyGraph.of(
            modules = nombres,
            layers = capas,
            edges = aristas,
        )
    }

    private fun parseModulo(linea: Int, entrada: String): Pair<String, Layer> {
        val partes = entrada.split(SEP_LAYER, limit = 2)
        require(partes.size == 2 && partes[0].isNotBlank()) {
            "linea $linea: un modulo se declara como '<nombre> <capa>': '$entrada'"
        }
        val capa = Layer.of(partes[1].trim())
        require(capa != null) {
            "linea $linea: capa desconocida '${partes[1].trim()}'. " +
                "Validas: ${Layer.entries.joinToString(", ") { it.name }}"
        }
        return partes[0].trim() to capa
    }

    private fun parseArista(linea: Int, entrada: String): DependencyEdge {
        val partes = entrada.split(SEP_ARROW, limit = 2)
        require(partes.size == 2) {
            "linea $linea: una arista se declara como '<desde> -> <hasta>': '$entrada'"
        }
        return runCatching { DependencyEdge(partes[0].trim(), partes[1].trim()) }
            .getOrElse { causa ->
                throw IllegalArgumentException("linea $linea: arista invalida '$entrada': ${causa.message}", causa)
            }
    }

    private const val INDENT = "  "
    private const val SEP_LAYER = "@"
    private const val SEP_ARROW = "->"
}