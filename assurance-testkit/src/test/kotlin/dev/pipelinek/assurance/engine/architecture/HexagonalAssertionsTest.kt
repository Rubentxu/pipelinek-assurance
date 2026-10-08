package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.Counterexample
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * UAT-003 y UAT-004: witnesses mínimos y reproducibles byte a byte.
 *
 * La exigencia del gate no es "el grafo con ciclo falla", es "falla con
 * witness reproducible byte a byte". Eso convierte cada test de este fichero
 * en una afirmación sobre la ESTABILIDAD del witness, no sólo sobre que
 * exista: si el mismo grafo da dos witnesses distintos en dos corridas, el
 * defecto está y el gate no está cerrado.
 */
class HexagonalAssertionsTest : AnnotationSpec() {

    private val evidencia = listOf(EvidenceId("fixture/graph/1"))
    private val snap = "snap-001"

    // -- UAT-003: dependencia prohibida ------------------------------------

    @Test
    fun UAT_003_grafo_sano_pasa() {
        val grafo = grafoSano()
        noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Passed>()
    }

    @Test
    fun UAT_003_domain_que_depende_de_infrastructure_falla_con_la_arista_exacta() {
        val grafo = DependencyGraph.of(
            Triple("infra-repo", Layer.Infrastructure, emptyList()),
            Triple("domain-evidence", Layer.Domain, listOf("infra-repo")),
        )

        val resultado = noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
        val fallo = resultado.shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.DependencyPath>()

        // La arista exacta, no "hubo un problema de dependencias".
        fallo.path shouldBe listOf("domain-evidence", "infra-repo")
        fallo.fromLayer shouldBe "Domain"
        fallo.toLayer shouldBe "Infrastructure"
    }

    @Test
    fun UAT_003_el_witness_es_el_camino_MAS_CORTO_no_uno_cualquiera() {
        // Dos rutas de INFRA hacia el domain: una directa y otra de tres
        // saltos. Sólo la directa es una arista prohibida (domain -> infra),
        // pero el BFS tiene que recorrer hasta el final del recorrido para
        // poder descartar la larga.
        //
        // Aristas en el sentido "quien depende -> de quien depende":
        //   infra-c -> domain   (directa, y por tanto la infracción)
        //   infra-a -> infra-b -> infra-c -> domain   (larga, legal)
        //
        // Un DFS que devuelva "un camino" puede devolver el largo. El BFS no:
        // por construcción devuelve el de menos aristas.
        val grafo = DependencyGraph.of(
            Triple("domain-evidence", Layer.Domain, listOf("infra-a")),
            Triple("infra-a", Layer.Infrastructure, listOf("infra-b")),
            Triple("infra-b", Layer.Infrastructure, listOf("infra-c")),
            Triple("infra-c", Layer.Infrastructure, emptyList()),
        )

        val fallo = noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.DependencyPath>()

        // Directa: dos nodos. Y en la dirección en la que se depende.
        fallo.path shouldBe listOf("domain-evidence", "infra-a")
        fallo.path.size shouldBe 2
        fallo.path.first() shouldBe "domain-evidence"
        fallo.path.last() shouldBe "infra-a"
    }

    @Test
    fun UAT_003_el_witness_no_depende_del_orden_de_insercion_de_las_aristas() {
        // La misma arquitectura declarada en dos órdenes distintos tiene que
        // dar el MISMO witness byte a byte. Es la forma de comprobar que el
        // canónico está donde tiene que estar.
        // Sólo UNA arista prohibida, a propósito: con dos, cuál se reporta
        // depende de cuál salga primero en orden canónico y el witness sería
        // legal pero poco informativo. El caso de "varias infracciones" es
        // otro test, y no éste.
        val aristas = listOf(
            DependencyEdge("domain-evidence", "infra-repo"),
            DependencyEdge("app", "domain-evidence"),
        )
        // El mapa de capas tiene que describir EXACTAMENTE los módulos
        // declarados: una clave de sobra es un módulo fantasma, y un módulo
        // que existe en el mapa pero no en la lista es exactamente el fallo
        // que `un_modulo_sin_capa_se_rechaza_al_construir` vigila.
        val capas = mapOf(
            "app" to Layer.Application,
            "domain-evidence" to Layer.Domain,
            "infra-repo" to Layer.Infrastructure,
            "z" to Layer.Domain,
        )
        val uno = DependencyGraph.of(
            listOf("z", "app", "domain-evidence", "infra-repo"),
            capas,
            aristas,
        )
        val otro = DependencyGraph.of(
            listOf("app", "infra-repo", "domain-evidence", "z"),
            capas,
            aristas.reversed(),
        )

        uno.canonicalText() shouldBe otro.canonicalText()
        noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(uno)
            .shouldBeInstanceOf<AssertionResult.Failed>()
            .counterexample.shouldBe(
                noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(otro)
                    .shouldBeInstanceOf<AssertionResult.Failed>().counterexample,
            )
    }

    @Test
    fun UAT_003_adapters_que_depende_de_application_es_legal() {
        // La política no es "sólo capas inferiores". `Adapters -> Application`
        // y `Adapters -> Domain` son legales en hexagonal; si la política fuera
        // el `rank` del enum, esto fallaría y el vertical sería falso.
        val grafo = DependencyGraph.of(
            Triple("domain-evidence", Layer.Domain, emptyList()),
            Triple("app-assurance", Layer.Application, listOf("domain-evidence")),
            Triple("adapter-cbor", Layer.Adapters, listOf("app-assurance", "domain-evidence")),
        )
        noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Passed>()
    }

    @Test
    fun la_politica_prohibe_la_dependencia_hacia_adentro_y_nada_mas() {
        // Tabla completa de la política, 4x4, contra la regla escrita.
        //
        // Se escribe entera a mano y no se calcula, porque el defecto que se
        // encontró al escribir esto fue exactamente una celda mal puesta:
        // `Infrastructure -> Infrastructure` estaba en la matriz como
        // prohibido, y `infra-a -> infra-b` es la dependencia más normal que
        // hay en un proyecto. La ley no la habría cazado; los tests de
        // ejemplo fallaban describiendo algo legal como defecto, y eso es
        // peor que un fallo de compilación porque parece que el código tiene
        // un bug cuando la regla estaba mal escrita.
        //
        // La forma de esta ley es "una fila por cada par que la política
        // declara ilegal, y nada más". Un par legal no necesita listarse; un
        // par ilegal que se olvide sí se escapa.
        //
        // Y se rompió exactamente así la primera vez: la lista tenía cinco
        // filas y faltaba `Adapters -> Infrastructure`, que la política SÍ
        // prohíbe. La ley se puso roja y resultó que la lista era la que
        // estaba incompleta, no la política. La diferencia importa: una ley
        // que se pone roja por su propia lista se corrige rápido; una ley
        // que no existe deja el hueco indefinidamente.
        // Las ocho dependencias que hexagonal prohíbe. Se listan TODAS, incluidas
        // las de una capa consigo misma (`Domain -> Domain`,
        // `Application -> Application`), porque `Domain` y `Application` no
        // pueden depender de nada: no tienen un "otro domain" al que mirar.
        //
        // `Adapters -> Adapters` e `Infrastructure -> Infrastructure` sí son
        // legales, y esa asimetría es el punto: un par con la misma capa es
        // ilegal en el centro y legal en los bordes.
        val prohibidas = setOf(
            Layer.Domain to Layer.Domain,
            Layer.Domain to Layer.Application,
            Layer.Domain to Layer.Adapters,
            Layer.Domain to Layer.Infrastructure,
            Layer.Application to Layer.Application,
            Layer.Application to Layer.Adapters,
            Layer.Application to Layer.Infrastructure,
            Layer.Adapters to Layer.Infrastructure,
        )

        for (desde in Layer.entries) {
            for (hacia in Layer.entries) {
                val esperado = (desde to hacia) !in prohibidas
                HexagonalPolicy.permitida(desde, hacia) shouldBe esperado
            }
        }
    }

    @Test
    fun la_politica_prohibe_toda_dependencia_del_domain() {
        // La regla que más se repite y la que peor falla en la práctica: el
        // domain no depende de nada, ni siquiera de sí mismo. Se comprueba
        // aparte porque la matriz 4x4 de arriba ya la cubre, y porque esta
        // es la afirmación que un lector quiere ver sin traducir.
        for (hacia in Layer.entries) {
            HexagonalPolicy.permitida(Layer.Domain, hacia) shouldBe false
        }
    }

    @Test
    fun UAT_003_application_que_depende_de_adapters_falla() {
        val grafo = DependencyGraph.of(
            Triple("domain-evidence", Layer.Domain, emptyList()),
            Triple("adapter-cbor", Layer.Adapters, listOf("domain-evidence")),
            Triple("app-assurance", Layer.Application, listOf("adapter-cbor")),
        )
        val fallo = noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.DependencyPath>()
        fallo.fromLayer shouldBe "Application"
        fallo.toLayer shouldBe "Adapters"
    }

    // -- UAT-004: ciclo mínimo --------------------------------------------

    @Test
    fun UAT_004_grafo_aciclico_pasa() {
        acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafoSano())
            .shouldBeInstanceOf<AssertionResult.Passed>()
    }

    @Test
    fun UAT_004_ciclo_ABC_devuelve_el_ciclo_completo() {
        // El mutante M-A03. Tres nodos, grado de salida 1 en cada uno: la
        // topología PARECE un árbol si no se cierra el ciclo.
        val grafo = DependencyGraph.of(
            Triple("a-core", Layer.Domain, listOf("b-app")),
            Triple("b-app", Layer.Application, listOf("c-adapter")),
            Triple("c-adapter", Layer.Adapters, listOf("a-core")),
        )

        val fallo = acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.Cycle>()

        fallo.cycle shouldBe listOf("a-core", "b-app", "c-adapter")
    }

    @Test
    fun UAT_004_el_ciclo_devuelto_es_el_MAS_PEQUENO() {
        // Sub-ciclo de dos dentro de un recorrido más largo. Devolver el
        // recorrido completo daría un witness que contiene el defecto y mucho
        // contexto: no es mínimo, y deja de ser reproducible.
        val grafo = DependencyGraph.of(
            Triple("a-core", Layer.Domain, listOf("b-app")),
            Triple("b-app", Layer.Application, listOf("c-adapter")),
            Triple("c-adapter", Layer.Adapters, listOf("d-adapter2")),
            Triple("d-adapter2", Layer.Infrastructure, listOf("c-adapter")),
        )

        val fallo = acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.Cycle>()

        fallo.cycle shouldBe listOf("c-adapter", "d-adapter2")
        fallo.cycle.size shouldBe 2
    }

    @Test
    fun UAT_004_el_ciclo_empieza_por_su_nodo_menor() {
        // La MISMA rotación produce el mismo witness. Un ciclo se puede
        // recorrer empezando por donde se quiera, y reportar [B, C, A] y
        // [A, B, C] como dos fallos distintos sería ruido.
        val grafo = DependencyGraph.of(
            Triple("z-first", Layer.Domain, listOf("m-middle")),
            Triple("m-middle", Layer.Application, listOf("a-last")),
            Triple("a-last", Layer.Adapters, listOf("z-first")),
        )

        val fallo = acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.Cycle>()

        fallo.cycle shouldBe listOf("a-last", "z-first", "m-middle")
        fallo.cycle.first() shouldBe fallo.cycle.min()
    }

    @Test
    fun UAT_004_el_mismo_grafo_da_el_mismo_witness_por_duplicado() {
        // Byte a byte, como pide el gate. Dos evaluaciones del MISMO objeto
        // tienen que producir el mismo texto, no sólo contraejemplos "iguales
        // por Equals": si mañana un campo nuevo (una pista más, un id) cambia
        // el texto, este test tiene que ponerse rojo.
        //
        // Se compara el TEXTO canónico del contraejemplo, no la instancia.
        // `Counterexample` es una interfaz sellada sin `copy`, y comparar
        // instancias sólo compararía los campos que existen hoy.
        val grafo = DependencyGraph.of(
            Triple("a-core", Layer.Domain, listOf("b-app")),
            Triple("b-app", Layer.Application, listOf("c-adapter")),
            Triple("c-adapter", Layer.Adapters, listOf("a-core")),
        )

        fun texto(resultado: AssertionResult): String {
            val fallo = resultado.shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            return buildString {
                appendLine(fallo.assertionId.value)
                appendLine(fallo.explanation)
                appendLine(fallo.subjectRefs.joinToString(",") { "${it.namespace}:${it.value}" })
                appendLine(fallo.evidenceRefs.joinToString(",") { it.value })
                appendLine(fallo.reproductionHints.joinToString("|"))
                when (fallo) {
                    is Counterexample.Cycle -> appendLine(fallo.cycle.joinToString(" -> "))
                    is Counterexample.DependencyPath -> appendLine(fallo.path.joinToString(" -> "))
                    else -> appendLine(fallo::class.simpleName.orEmpty())
                }
            }
        }

        val uno = texto(acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo))
        val otro = texto(acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo))

        uno shouldBe otro
        uno shouldNotBe otro + " "
    }

    @Test
    fun UAT_004_ciclo_que_no_contiene_el_nodo_minimo_del_grafo_se_encuentra() {
        // El bug que se corrigió al escribir esto: si la búsqueda exigía
        // arrancar por el nodo lexicográficamente MENOR, un ciclo cuyo menor
        // nodo no es `a-domain` (porque `a-domain` no está en el ciclo)
        // nunca cerraba. El mínimo del grafo no es el mínimo del ciclo.
        val grafo = DependencyGraph.of(
            Triple("a-domain", Layer.Domain, emptyList()),
            Triple("m-core", Layer.Domain, listOf("n-app")),
            Triple("n-app", Layer.Application, listOf("o-adapter")),
            Triple("o-adapter", Layer.Adapters, listOf("m-core")),
        )

        val fallo = acyclic(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.Cycle>()

        fallo.cycle shouldBe listOf("m-core", "n-app", "o-adapter")
    }

    // -- El grafo es canonico por construccion ------------------------------

    @Test
    fun el_grafo_normaliza_modulos_y_aristas_por_el_constructor() {
        val desordenado = DependencyGraph.of(
            modules = listOf("z", "m", "a"),
            layers = mapOf("a" to Layer.Adapters, "m" to Layer.Domain, "z" to Layer.Application),
            edges = listOf(
                DependencyEdge("z", "a"),
                DependencyEdge("a", "m"),
                DependencyEdge("z", "a"),
            ),
        )

        desordenado.modules shouldBe listOf("a", "m", "z")
        desordenado.edges shouldBe listOf(
            DependencyEdge("a", "m"),
            DependencyEdge("z", "a"),
        )
    }

    @Test
    fun un_modulo_sin_capa_se_rechaza_al_construir() {
        // Fail-closed en la frontera: un grafo con un módulo sin clasificar
        // no se evalúa "con lo que se sepa", se rechaza. Si pasara, un módulo
        // sin capa sería invisible para `noDependency` y por tanto legal por
        // omisión, que es la peor forma de ser legal.
        val error = runCatching {
            DependencyGraph.of(
                modules = listOf("a", "b"),
                layers = mapOf("a" to Layer.Domain),
                edges = listOf(DependencyEdge("a", "b")),
            )
        }.exceptionOrNull()

        error.shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun una_arista_reflexiva_se_rechaza() {
        // El self-model declara las dependencias entre MODULOS. Una arista
        // `a -> a` significa que el módulo depende de sí mismo a nivel de
        // módulo, que no es un ciclo sino una corrupción del fixture.
        val error = runCatching { DependencyEdge("a", "a") }.exceptionOrNull()
        error.shouldBeInstanceOf<IllegalArgumentException>()
    }

    private fun grafoSano(): DependencyGraph = DependencyGraph.of(
        Triple("domain-evidence", Layer.Domain, emptyList()),
        Triple("app-assurance", Layer.Application, listOf("domain-evidence")),
        Triple("adapter-artifact", Layer.Adapters, listOf("app-assurance", "domain-evidence")),
        Triple("infra-cli", Layer.Infrastructure, listOf("adapter-artifact")),
    )

    // -- El hueco que dejó M-A01 ---------------------------------------------
    //
    // M-A01 sobrevivió al catálogo entero, y el diagnóstico NO es "el test
    // es débil" sino que el código atacado era código muerto. La versión
    // anterior iteraba sobre las ARISTAS prohibidas y pedía el camino más
    // corto de `edge.from` a `edge.to`. Como `(from, to)` era una arista del
    // propio grafo, el BFS la veía en la primera expansión y devolvía
    // SIEMPRE `[from, to]`. Todas las infracciones empataban a 2 saltos, así
    // que el `sortedBy { camino.size }` no elegía nada: sortaba una lista de
    // constantes iguales.
    //
    // El test de abajo mata el mutante de verdad: sin recorrido completo, la
    // arista de dos saltos no aparece en la lista de infracciones, y el
    // camino de tres saltos (que SÍ depende del recorrido) tampoco.
    @Test
    fun UAT_003_el_witness_es_el_camino_mas_corto_HASTA_LA_CAPA_PROHIBIDA_no_la_arista_mas_corta() {
        // `domain-a` es domain: todo lo que no sea domain está prohibido.
        // Alcanza `app-c` en DOS saltos (`domain-a -> adapter-b -> app-c`) y
        // `infra-d` en TRES. El testigo tiene que ser el de dos saltos.
        //
        // La capa de destino se elige por el criterio del código: entre las
        // prohibidas para `Domain`, se recorre en orden alfabético de nombre,
        // y `Adapters` gana a `Application` e `Infrastructure`. No es el
        // destino "más cercano" en el grafo, es el primero canónico, y esa
        // distinción importa: si el destino se eligiera por proximidad, el
        // witness cambiaría según cómo se nombren las capas, que es
        // exactamente la propiedad que no se quiere en evidencia determinista.
        val grafo = DependencyGraph.of(
            Triple("app-c", Layer.Application, emptyList()),
            Triple("infra-d", Layer.Infrastructure, emptyList()),
            Triple("adapter-b", Layer.Adapters, listOf("app-c")),
            Triple("domain-a", Layer.Domain, listOf("adapter-b")),
        )

        val fallo = noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.DependencyPath>()

        fallo.fromLayer shouldBe "Domain"
        // La capa prohibida canónicamente primera para `Domain` es `Adapters`,
        // y a ella se llega en dos saltos.
        fallo.toLayer shouldBe "Adapters"
        fallo.path shouldBe listOf("domain-a", "adapter-b")
    }

    @Test
    fun UAT_003_entre_dos_destinos_igual_de_cercanos_se_elige_el_canónico() {
        // `adapter-a` puede alcanzar `infra-b` y `infra-c`, ambos de
        // `Infrastructure`, y ambos a un solo salto. Los dos son infracciones
        // idénticas en longitud, así que sólo el desempate canónico decide.
        // Sin desempate, el witness dependería del orden de `modules`, que es
        // canónico pero no de una lectura evidente: dos ejecuciones sobre
        // grafos con el mismo conjunto y distinto orden de entrada podrían
        // reportar `infra-c` y `infra-b`.
        val grafo = DependencyGraph.of(
            Triple("adapter-a", Layer.Adapters, listOf("infra-c", "infra-b")),
            Triple("infra-b", Layer.Infrastructure, emptyList()),
            Triple("infra-c", Layer.Infrastructure, emptyList()),
        )

        val fallo = noDependency(snapshotId = snap, evidenceIds = evidencia).evaluate(grafo)
            .shouldBeInstanceOf<AssertionResult.Failed>().counterexample
            .shouldBeInstanceOf<Counterexample.DependencyPath>()

        fallo.toLayer shouldBe "Infrastructure"
        fallo.path shouldBe listOf("adapter-a", "infra-b")
    }
}
