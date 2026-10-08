package dev.pipelinek.assurance.artifact

import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.engine.architecture.Layer
import dev.pipelinek.assurance.engine.architecture.acyclic
import dev.pipelinek.assurance.engine.architecture.noDependency
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain

/**
 * Leyes del codec del fixture de dependency graph.
 *
 * El objetivo de estas leyes no es "el parser funciona": es que **el texto en
 * disco sea una proyección del grafo y no una segunda fuente de verdad**. Si
 * el roundtrip no cierra, el fichero y el grafo son dos cosas distintas, y una
 * corrección hecha en el fichero no se ve reflejada en la evaluación, o al
 * revés.
 */
class DependencyGraphFixtureCodecTest : AnnotationSpec() {
    private val sano = """
        modules
          domain-evidence @ Domain
          app-assurance @ Application
          adapter-artifact @ Adapters
          infra-cli @ Infrastructure
        edges
          app-assurance -> domain-evidence
          adapter-artifact -> app-assurance
          adapter-artifact -> domain-evidence
          infra-cli -> adapter-artifact
    """.trimIndent()

    /**
     * Ejecuta el codec y devuelve el mensaje del error.
     *
     * El helper existe por una razón concreta: cada ley fail-closed tiene que
     * comprobar **qué** dice el error, no sólo que hubo uno. Un
     * `shouldThrow` a secas acepta "NullPointerException" como éxito, y eso
     * certificaría que el codec falla, no que falla por la razón correcta.
     * Volver a escribir la comparación del mensaje en cada ley es lo que
     * permite que cada una afirme algo distinto.
     */
    private fun rechazo(texto: String): String =
        shouldThrow<IllegalArgumentException> { DependencyGraphFixtureCodec.decode(texto) }
            .message!!

    @Test
    fun `el roundtrip devuelve el mismo grafo`() {
        val original = DependencyGraphFixtureCodec.decode(sano)
        val vuelta = DependencyGraphFixtureCodec.decode(
            DependencyGraphFixtureCodec.encode(original),
        )

        vuelta shouldBe original
    }

    @Test
    fun `el texto canonico no depende del orden de declaracion`() {
        // El orden de entrada del fichero y el orden con el que se construyó el
        // grafo son independientes. Si el encode dependiera del orden de
        // entrada, dos repos con la misma arquitectura darían dos textos y por
        // tanto dos digests, y el digest dejaría de ser la firma de la
        // arquitectura para ser la firma del fichero.
        val desordenado = """
            modules
              infra-cli @ Infrastructure
              adapter-artifact @ Adapters
              domain-evidence @ Domain
              app-assurance @ Application
            edges
              infra-cli -> adapter-artifact
              adapter-artifact -> domain-evidence
              adapter-artifact -> app-assurance
              app-assurance -> domain-evidence
        """.trimIndent()

        DependencyGraphFixtureCodec.encode(DependencyGraphFixtureCodec.decode(desordenado)) shouldBe
            DependencyGraphFixtureCodec.encode(DependencyGraphFixtureCodec.decode(sano))
    }

    @Test
    fun `encode es idempotente sobre su propia salida`() {
        // El encode no sólo tiene que ser estable entre grafos iguales: el
        // texto que produce tiene que volver a producir el mismo texto. Es la
        // diferencia entre "ordenado" y "canónico", y es la propiedad de la que
        // depende que un fichero reescrito sin cambios no ensucie el git.
        val una = DependencyGraphFixtureCodec.encode(DependencyGraphFixtureCodec.decode(sano))
        val dos = DependencyGraphFixtureCodec.encode(DependencyGraphFixtureCodec.decode(una))
        val tres = DependencyGraphFixtureCodec.encode(DependencyGraphFixtureCodec.decode(dos))

        dos shouldBe una
        tres shouldBe dos
    }

    @Test
    fun `los comentarios y el espacio en blanco no cambian el grafo`() {
        val conRuido = """
            # un comentario arriba

            modules
              domain-evidence   @   Domain
              app-assurance @ Application   # comentario de cola
              adapter-artifact @ Adapters
              infra-cli @ Infrastructure

            edges
              app-assurance -> domain-evidence
              adapter-artifact -> app-assurance
              adapter-artifact -> domain-evidence
              infra-cli -> adapter-artifact
        """.trimIndent()

        DependencyGraphFixtureCodec.decode(conRuido) shouldBe DependencyGraphFixtureCodec.decode(sano)
    }

    @Test
    fun `el grafo decodificado sale canonico`() {
        val grafo = DependencyGraphFixtureCodec.decode(sano)

        grafo.modules shouldBe grafo.modules.sorted()
        grafo.edges shouldBe grafo.edges.sortedWith(compareBy({ it.from }, { it.to }))
    }

    // -- Fail-closed --------------------------------------------------------

    @Test
    fun `un modulo sin capa se rechaza y nombra la linea`() {
        // El error tiene que decir QUÉ línea, no sólo que algo está mal. Un
        // "fixture inválido" sin línea obligaba a revisar el fichero entero
        // para encontrar una línea que quizá no existe.
        rechazo(
            """
            modules
              app-assurance
              domain-evidence @ Domain
            edges
            """.trimIndent(),
        ) shouldContain "linea 2"
    }

    @Test
    fun `una capa desconocida se rechaza listando las validas`() {
        val mensaje = rechazo(
            """
            modules
              app-assurance @ Presentacion
            edges
            """.trimIndent(),
        )

        mensaje shouldContain "Presentacion"
        mensaje shouldContain "Domain"
    }

    @Test
    fun `una arista hacia un modulo no declarado se rechaza`() {
        // Fail-closed en la frontera del self-model. Si `infra-cli` no aparece
        // en `modules`, no tiene capa, y un destino sin capa es invisible para
        // `noDependency`: la arista hacia él no se puede evaluar y el grafo
        // pasa sin decir nada. Rechazar es lo único que no fabrica un PASS.
        rechazo(
            """
            modules
              domain-evidence @ Domain
            edges
              domain-evidence -> infra-cli
            """.trimIndent(),
        ) shouldContain "infra-cli"
    }

    @Test
    fun `un modulo declarado dos veces se rechaza`() {
        rechazo(
            """
            modules
              app-assurance @ Application
              app-assurance @ Domain
            edges
            """.trimIndent(),
        ) shouldContain "app-assurance"
    }

    @Test
    fun `una arista reflexiva se rechaza nombrando el por que`() {
        // `a -> a` no es un ciclo: el self-model no declara que un módulo
        // dependa de sí mismo a nivel de módulo. Aceptarlo haría que `acyclic`
        // lo reportara como ciclo de uno, que es un defecto inventado.
        rechazo(
            """
            modules
              app-assurance @ Application
            edges
              app-assurance -> app-assurance
            """.trimIndent(),
        ) shouldContain "reflexiva"
    }

    @Test
    fun `una entrada antes de cualquier seccion se rechaza`() {
        rechazo("app-assurance -> domain-evidence") shouldContain "seccion"
    }

    @Test
    fun `un fixture sin modulos se rechaza`() {
        rechazo("modules\nedges\n") shouldContain "no dice nada"
    }

    @Test
    fun `el self-model sintetico declara las cuatro capas`() {
        // UAT-022 pide que el repo se valide a sí mismo con domain, application,
        // adapters e infrastructure. Esta ley ata el fixture real a ese
        // contrato: si alguien lo vacía o le quita una capa, se pone roja.
        DependencyGraphFixtureCodec.decode(SELF_MODEL_SINTETICO).layers.values.toSet() shouldBe
            Layer.entries.toSet()
    }

    @Test
    fun `el self-model sintetico pasa las dos assertions`() {
        // El self-model declara la arquitectura REAL de este repo. Si una
        // arista del grafo violara la política hexagonal, el gate propio
        // estaría rojo y eso significaría que la arquitectura declarada no
        // describe el repo, no que el repo tenga un defecto. La ley ata las dos
        // cosas: el fixture tiene que pasar Y tener las cuatro capas.
        val grafo = DependencyGraphFixtureCodec.decode(SELF_MODEL_SINTETICO)

        // El `evidenceIds` NO puede ir vacío, y no es un detalle del test:
        // AAT-20 dice que un `Passed` sin evidencia que lo respalde es ilegal,
        // y el motor lo rechaza al construir el `ProofRef`. Una assertion que
        // evaluara un grafo sin evidencia y devolviera Passed sería la
        // forma más directa de mentir sobre lo que se ha comprobado.
        noDependency(
            snapshotId = "self-model",
            evidenceIds = listOf(EVIDENCIA_SINTETICA),
        ).evaluate(grafo).shouldBeInstanceOf<AssertionResult.Passed>()

        acyclic(
            snapshotId = "self-model",
            evidenceIds = listOf(EVIDENCIA_SINTETICA),
        ).evaluate(grafo).shouldBeInstanceOf<AssertionResult.Passed>()
    }

    companion object {
        /**
         * Evidencia sintética que respalda la evaluación del self-model.
         *
         * No se puede dejar vacía porque AAT-20 lo prohíbe: `no evidence != PASS`.
         */
        val EVIDENCIA_SINTETICA: EvidenceId = EvidenceId("synthetic/self-model/hexagonal/1")

        /**
         * Self-model sintético del layout real de `pipelinek-assurance`.
         *
         * Es **sintético**: declara la arquitectura que el repo declara de sí
         * mismo, escrita a mano, no extraída por análisis. Eso es lo que permite
         * certificar la ley con evidencia determinista antes de que exista el
         * provider; la instancia extraída llega en M2, y UAT-023 la sustituye.
         */
        val SELF_MODEL_SINTETICO: String = """
            # self-model sintetico de pipelinek-assurance
            modules
              assurance-domain @ Domain
              assurance-artifact @ Application
              assurance-engine @ Application
              assurance-testkit @ Adapters
              assure-cli @ Infrastructure
            edges
              assurance-artifact -> assurance-domain
              assurance-artifact -> assurance-engine
              assurance-engine -> assurance-domain
              assurance-testkit -> assurance-artifact
              assurance-testkit -> assurance-engine
              assure-cli -> assurance-domain
              assure-cli -> assurance-artifact
              assure-cli -> assurance-engine
        """.trimIndent()
    }
}
