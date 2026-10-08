package dev.pipelinek.assurance.engine.architecture

/**
 * Capa hexagonal de un módulo.
 *
 * El enum es cerrado y ordenado por el flujo de dependencias permitido, no
 * alfabéticamente. El orden importa: `permitidas` se lee de aquí, y un enum
 * reordenado cambia qué dependencias son legales sin que ninguna línea de
 * código cambie. Por eso el orden es explícito y no `entries.sorted()`.
 */
enum class Layer(val rank: Int) {
    Domain(0),
    Application(1),
    Adapters(2),
    Infrastructure(3);

    init {
        require(rank in 0..3) { "rank fuera de rango en $this" }
    }

    companion object {
        fun of(nombre: String): Layer? = entries.firstOrNull { it.name.equals(nombre, ignoreCase = true) }
    }
}

/**
 * Arista del grafo de dependencias: `from` depende de `to`.
 *
 * La arista tiene dirección explícita porque "depende de" no es simétrica, y
 * el error más caro de un grafo de arquitectura es tratar la dependencia
 * como si fuera simétrica: `domain -> infrastructure` y
 * `infrastructure -> domain` son respuestas opuestas, y un grafo que no
 * distingue una de otra no está diciendo nada sobre la arquitectura.
 */
data class DependencyEdge(
    val from: String,
    val to: String,
) {
    init {
        require(from.isNotBlank()) { "arista con origen vacio" }
        require(to.isNotBlank()) { "arista con destino vacio" }
        require(from != to) { "arista reflexiva $from -> $to: el self-model no lo declara" }
    }

    override fun toString(): String = "$from -> $to"
}

/**
 * Grafo de dependencias entre módulos, con su clasificación por capa.
 *
 * Es **dato inmutable y ya canónico**: el constructor normaliza. No es un
 * `MutableMap` con un `canonicalize()` aparte, porque entonces cada consumidor
 * tiene que acordarse de llamar al canonicalizador, y basta uno que se olvide
 * para que dos ejecuciones den artefactos distintos.
 *
 * Es también el tipo que la lens proyecta: la lens no recibe el fichero, ni el
 * provider, ni un parser. Recibe esto. Es el STOP de M1 ("si una lens necesita
 * conocer el provider para proyectar, el Evidence IR está mal"), y la razón
 * por la que este tipo existe.
 */
data class DependencyGraph private constructor(
    val modules: List<String>,
    val layers: Map<String, Layer>,
    val edges: List<DependencyEdge>,
) {
    init {
        require(modules.distinct().size == modules.size) {
            "modulos duplicados: ${modules.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
        }
        require(modules.isNotEmpty()) { "un grafo sin modulos no dice nada" }
        val esperados = modules.toSet()
        require(layers.keys == esperados) {
            "todo modulo necesita capa exactamente una vez: " +
                "sin capa en ${esperados - layers.keys}, " +
                "con capa de mas en ${layers.keys - esperados}"
        }
    }

    companion object {
        /**
         * Fábrica única, y normaliza aquí.
         *
         * Antes el constructor era público y la normalización vivía en un
         * constructor **secundario** con los mismos tipos. Kotlin no lo
         * distingue del primario, así que nunca se llamaba, y `modules` y
         * `edges` llegaban en el orden que el caller decidiera. El invariante
         * "el grafo es canónico por construcción" era verdad en el KDoc y
         * falsa en el código, que es la peor forma de que sea falso.
         *
         * Se hace privado el constructor y se expone sólo esta fábrica por
         * una razón que no es de estilo: es lo que hace imposible saltarse la
         * normalización desde fuera del tipo. Si mañana alguien necesita
         * construir un grafo sin normalizar, es porque la normalización se
         * ha movido, y no porque haya añadido una línea en otro sitio.
         */
        fun of(
            modules: Collection<String>,
            layers: Map<String, Layer>,
            edges: Collection<DependencyEdge>,
        ): DependencyGraph = DependencyGraph(
            modules = modules.distinct().sorted(),
            layers = layers,
            edges = edges.distinct().sortedWith(compareBy({ it.from }, { it.to })),
        )

        /** Azúcar para declarar un grafo a partir de (nombre, capa, destinos). */
        fun of(vararg declaracion: Triple<String, Layer, List<String>>): DependencyGraph = of(
            modules = declaracion.map { it.first },
            layers = declaracion.associate { it.first to it.second },
            edges = declaracion.flatMap { (from, _, hacia) -> hacia.map { DependencyEdge(from, it) } },
        )
    }

    private val porNombre: Map<String, ModuleNode> = modules.associateWith { nombre ->
        ModuleNode(nombre, layers.getValue(nombre), edges.filter { it.from == nombre }.map { it.to })
    }

    operator fun get(nombre: String): ModuleNode =
        porNombre[nombre] ?: throw NoSuchElementException("modulo desconocido: $nombre")

    /** Aristas incidentes en `nombre`, en las dos direcciones. */
    fun incidentes(nombre: String): List<DependencyEdge> =
        edges.filter { it.from == nombre || it.to == nombre }

    /** `DependencyGraph` → representación estable para digest. */
    fun canonicalText(): String = buildString {
        append("modules\n")
        modules.forEach { append("  ").append(it).append(" @ ").append(layers.getValue(it).name).append('\n') }
        append("edges\n")
        edges.forEach { append("  ").append(it.from).append(" -> ").append(it.to).append('\n') }
    }
}

/** Nodo con sus dependencias directas de salida, en orden canónico. */
data class ModuleNode(
    val name: String,
    val layer: Layer,
    val dependsOn: List<String>,
)
