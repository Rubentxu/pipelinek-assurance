package dev.pipelinek.assurance.cli

import dev.pipelinek.assurance.artifact.DependencyGraphFixtureCodec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests del CLI.
 *
 * El punto de [ejecutar] es que recibe el texto del fixture por parámetro y no
 * lo lee de disco. Gracias a eso, estas leyes se comprueban sin levantar un
 * filesystem temporal, y una ley que necesita I/O para probarse es una ley
 * que sólo se prueba en la máquina que la escribió.
 */
class AssureCliTest : StringSpec({

    val LIMPIO = """
        modules
          assurance-domain @ Domain
          assurance-engine @ Application
          assurance-artifact @ Application
          assure-cli @ Infrastructure
        edges
          assurance-engine -> assurance-domain
          assurance-artifact -> assurance-domain
          assure-cli -> assurance-engine
    """.trimIndent()

    /** El dominio depende de un adaptador: viola la ley de capas. */
    val CON_DEPENDENCIA_PROHIBIDA = """
        modules
          assurance-domain @ Domain
          assurance-artifact @ Application
          assurance-testkit @ Adapters
        edges
          assurance-domain -> assurance-testkit
    """.trimIndent()

    /** Ciclo entre dos módulos de la misma capa: viola aciclicidad. */
    val CON_CICLO = """
        modules
          assurance-artifact @ Application
          assurance-engine @ Application
        edges
          assurance-artifact -> assurance-engine
          assurance-engine -> assurance-artifact
    """.trimIndent()

    fun cli(texto: String) = { _: String -> texto }

    "report devuelve 0 y passed cuando el self-model cumple la ley" {
        val resultado = AssureCli.ejecutar(listOf("report", "self-model.graph"), cli(LIMPIO))

        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.kind shouldBe "AssertionPass"
        resultado.envelope.data["passed"] shouldBe "2"
        resultado.envelope.data["total"] shouldBe "2"
    }

    /**
     * Ley: un Passed no lleva acciones.
     *
     * No es que no pueda llevarlas, es que no *debe*: ofrecer `explain` sobre
     * un finding que no existe sería mandar al agente a un callejón sin salida.
     */
    "report con veredicto Passed no ofrece acciones que no se pueden seguir" {
        val resultado = AssureCli.ejecutar(listOf("report", "self-model.graph"), cli(LIMPIO))

        resultado.envelope!!.actions.shouldContainExactly()
    }

    "report devuelve 1 cuando una dependencia viola la capa" {
        val resultado = AssureCli.ejecutar(listOf("report", "x"), cli(CON_DEPENDENCIA_PROHIBIDA))

        resultado.exitCode shouldBe ExitCode.NOT_PASSED
        resultado.envelope!!.kind shouldBe "AssertionFailure"
        resultado.envelope.data["assertionId"] shouldBe "architecture.no-dependency"
    }

    "report devuelve 1 cuando hay un ciclo" {
        val resultado = AssureCli.ejecutar(listOf("report", "x"), cli(CON_CICLO))

        resultado.exitCode shouldBe ExitCode.NOT_PASSED
        resultado.envelope!!.kind shouldBe "AssertionFailure"
        resultado.envelope.data["assertionId"] shouldBe "architecture.acyclic"
    }

    /**
     * Ley central de UAT-021: todo veredicto no-Passed lleva al menos una
     * acción, y la acción existe como comando.
     *
     * El test no mira que el string tenga forma de comando: lo ejecuta. Esa
     * es la diferencia entre una ley comprobable y un patrón decorativo.
     */
    "un fallo ofrece acciones y todas son comandos que el CLI acepta" {
        val resultado = AssureCli.ejecutar(listOf("report", "x"), cli(CON_DEPENDENCIA_PROHIBIDA))
        val envelope = resultado.envelope!!

        envelope.actions.isNotEmpty() shouldBe true

        for (accion in envelope.actions) {
            // Se separa por el registro, no por `split(" ")` ingenuo: un
            // comando de dos palabras como `evidence path` no sobrevive a un
            // split, y el propio envelope lo emite. Si el comando emitido no
            // se puede trocear de vuelta en sus partes, el agente tampoco
            // puede ejecutarlo, y la ley HATEOAS sería decorativa.
            val argumentos = accion.command.removePrefix("assure ")
            val nombre = CapabilityRegistry.nombres.first {
                argumentos == it || argumentos.startsWith("$it ")
            }
            val palabrasNombre = nombre.split(" ")
            val operandos = argumentos.split(" ").drop(palabrasNombre.size)

            CapabilityRegistry.buscar(nombre) shouldNotBe null

            // Ejecutarlo de verdad: el verbo, el operando y el resultado tienen
            // que encajar. Un exit 2 significaría que le estamos ofreciendo un
            // comando que el propio CLI rechaza.
            val seguido = AssureCli.ejecutar(palabrasNombre + operandos, cli(CON_DEPENDENCIA_PROHIBIDA))
            seguido.exitCode shouldNotBe ExitCode.USAGE
        }
    }

    "las acciones de un fallo apuntan al finding que lo produjo" {
        val resultado = AssureCli.ejecutar(listOf("report", "x"), cli(CON_DEPENDENCIA_PROHIBIDA))
        val envelope = resultado.envelope!!

        envelope.actions.forEach { it.command shouldContain("architecture.no-dependency") }
    }

    "el contador passed y total cuentan cada assertion evaluada" {
        // Acyclic pasa, noDependency falla: passed debe ser 1, no 2.
        val texto = """
            modules
              assurance-domain @ Domain
              assurance-artifact @ Application
              assurance-testkit @ Adapters
            edges
              assurance-domain -> assurance-testkit
              assurance-artifact -> assurance-domain
        """.trimIndent()
        val resultado = AssureCli.ejecutar(listOf("report", "x"), cli(texto))

        resultado.envelope!!.data["passed"] shouldBe "1"
        resultado.envelope!!.data["total"] shouldBe "2"
        resultado.exitCode shouldBe ExitCode.NOT_PASSED
    }

    "un comando desconocido da 2 y ofrece los comandos que si existen" {
        val resultado = AssureCli.ejecutar(listOf("teletransporte", "x"), cli(LIMPIO))

        resultado.exitCode shouldBe ExitCode.USAGE
        resultado.envelope!!.kind shouldBe "UsageError"
        resultado.envelope!!.data["problema"] shouldContain("teletransporte")
        // Los comandos que si existen se ofrecen de salida.
        resultado.envelope!!.actions.size shouldBe CapabilityRegistry.comandos.size
        resultado.envelope!!.actions.forEach { it.rel shouldBe "command" }
    }

    "sin argumentos da 2 y no lanza" {
        AssureCli.ejecutar(emptyList(), cli(LIMPIO)).exitCode shouldBe ExitCode.USAGE
    }

    "un finding no valido da error legible, no una traza" {
        val resultado = AssureCli.ejecutar(listOf("explain", ""), cli(LIMPIO))

        resultado.exitCode shouldBe ExitCode.NOT_PASSED
        resultado.envelope!!.kind shouldBe "Error"
        resultado.envelope.data["causa"] shouldContain("no es un id de assertion valido")
    }

    "un fixture ilegible falla cerrado con exit 1, no con Passed" {
        val resultado = AssureCli.ejecutar(listOf("report", "x")) { "esto no es un grafo" }

        resultado.exitCode shouldBe ExitCode.NOT_PASSED
        resultado.envelope!!.kind shouldBe "Error"
        // Fail-closed: un fixture que no se puede leer JAMMAS produce Passed.
        resultado.envelope!!.kind shouldNotBe "AssertionPass"
    }

    "evidence path responde con la evidencia y sin inventar rutas" {
        val resultado = AssureCli.ejecutar(listOf("evidence", "path", "architecture.acyclic"), cli(LIMPIO))

        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.kind shouldBe "EvidencePath"
        resultado.envelope.data["evidencia"] shouldBe "synthetic/self-model/hexagonal/1"
    }

    "explain admite que necesita el snapshot en vez de inventar una explicacion" {
        val resultado = AssureCli.ejecutar(listOf("explain", "architecture.acyclic"), cli(LIMPIO))

        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.kind shouldBe "ExplainUnavailable"
        // Aun asi, es accionable: ofrece el siguiente paso.
        resultado.envelope!!.actions.isNotEmpty() shouldBe true
    }

    "el envelope serializa a JSON de forma estable y con data ordenado" {
        val resultado = AssureCli.ejecutar(listOf("report", "x"), cli(CON_DEPENDENCIA_PROHIBIDA))
        val a = EnvelopeJson.encode(resultado.envelope)
        val b = EnvelopeJson.encode(resultado.envelope)

        // Determinismo primero: la misma entrada da el mismo bytes.
        a shouldBe b
        a shouldContain("\"apiVersion\":\"assurance.pipelinek.dev/v1\"")
        a shouldContain("\"actions\":[")

        // El orden del envelope lo fija la spec, no el serializer: apiVersion,
        // kind, subject, data, actions. Lo que el serializer ordena es el
        // contenido de `data`, que es donde el orden no significa nada y sí
        // tendría que ser estable.
        val ordenSpec = listOf("apiVersion", "kind", "subject", "data", "actions")
        val indices = ordenSpec.map { a.indexOf("\"$it\":") }
        indices shouldBe indices.sorted()

        val dentroDeData = a.substringAfter("\"data\":{").substringBefore("},")
        val clavesData = Regex("\"(\\w+)\":").findAll(dentroDeData).map { it.groupValues[1] }.toList()
        clavesData shouldBe clavesData.sorted()
    }

    "el escape de cadenas produce JSON valido para entradas raras" {
        val envelope = Envelope(
            apiVersion = "v1",
            kind = "T",
            subject = "comilla\" y barra\\ y\nnueva",
            data = emptyMap(),
            actions = emptyList(),
        )
        val json = EnvelopeJson.encode(envelope)

        json shouldContain("""\"""")
        json shouldContain("""\\""")
        json shouldContain("""\n""")
    }

    "el codec y el CLI comparten la misma lectura del self-model en disco" {
        // El fixture real del repo tiene que decodificar y pasar la ley.
        val texto = SelfModelFixture.TEXTO
        val grafo = DependencyGraphFixtureCodec.decode(texto)
        val resultado = AssureCli.ejecutar(listOf("report", "self-model.graph"), cli(texto))

        grafo.modules.size shouldBe 5
        resultado.exitCode shouldBe ExitCode.OK
    }

    "el self-model en disco existe y su nombre de modulo no es una promesa" {
        // Si assure-cli estuviera declarado pero sin existir como modulo, el
        // grafo seguiria siendo valido y este test pasaria. Lo que ata el
        // nombre al mundo es que el modulo exista de verdad, asi que se
        // comprueba el build file, no solo el fixture.
        java.io.File(SelfModelFixture.RUTA).readText() shouldContain("assure-cli @ Infrastructure")
        SelfModelFixture.existeModulo() shouldBe true
    }

    // --- E1 (Bloque E) — comandos agent-first ---

    "assure capabilities lista todos los comandos disponibles" {
        val resultado = AssureCli.ejecutar(listOf("capabilities"), cli(""))
        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.kind shouldBe "Capabilities"
        resultado.envelope.data["count"] shouldBe "6"
        resultado.envelope.data["comandos"] shouldContain "report"
        resultado.envelope.data["comandos"] shouldContain "explain"
        resultado.envelope.data["comandos"] shouldContain "evidence path"
        resultado.envelope.data["comandos"] shouldContain "capabilities"
        resultado.envelope.data["comandos"] shouldContain "providers"
        resultado.envelope.data["comandos"] shouldContain "next"
    }

    "assure providers documenta los built-in (BuiltinLens + BuiltinAssertion)" {
        val resultado = AssureCli.ejecutar(listOf("providers"), cli(""))
        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.kind shouldBe "Providers"
        resultado.envelope.data["lenses"] shouldContain "BuiltinLens"
        resultado.envelope.data["assertions"] shouldContain "BuiltinAssertion"
    }

    "assure next passed devuelve OK con el veredicto" {
        val resultado = AssureCli.ejecutar(listOf("next", "passed"), cli(""))
        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.kind shouldBe "Next"
        resultado.envelope.data["veredicto"] shouldBe "passed"
    }

    "assure next failed sugiere explain" {
        val resultado = AssureCli.ejecutar(listOf("next", "failed"), cli(""))
        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.data["sugerencia"] shouldContain "explain"
    }

    "assure next inconclusive sugiere evidence path" {
        val resultado = AssureCli.ejecutar(listOf("next", "inconclusive"), cli(""))
        resultado.exitCode shouldBe ExitCode.OK
        resultado.envelope!!.data["sugerencia"] shouldContain "evidence path"
    }

    "assure next sin veredicto da error legible (exit 2, no trace)" {
        val resultado = AssureCli.ejecutar(listOf("next"), cli(""))
        resultado.exitCode shouldBe ExitCode.USAGE
    }

    "assure next con veredicto desconocido da error legible" {
        val resultado = AssureCli.ejecutar(listOf("next", "blue"), cli(""))
        resultado.exitCode shouldBe ExitCode.USAGE
    }
})

/**
 * El self-model que el repo se aplica a si mismo, leído desde disco.
 *
 * [RAIZ] existe porque Gradle ejecuta los tests con el directorio del módulo
 * como CWD, no el de la raíz. Resolver la raíz hacia arriba evita el doble
 * fallo clásico de estas pruebas: o leer un fichero que no existe, o meter una
 * copia del fixture dentro del módulo, que es como un self-model acaba
 * certificándose a si mismo con datos que él mismo escribió.
 */
object SelfModelFixture {
    val RAIZ: String by lazy {
        generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "08-testing/self-model.graph").isFile }
            ?.path
            ?: error(
                "no se encuentra 08-testing/self-model.graph subiendo desde ${java.io.File(".").absolutePath}; " +
                    "el self-model debe vivir en la raiz del repo, no dentro del modulo",
            )
    }

    val RUTA: String get() = "$RAIZ/08-testing/self-model.graph"

    val TEXTO: String by lazy { java.io.File(RUTA).readText() }

    /** El modulo `assure-cli` tiene que existir de verdad, no solo estar declarado. */
    fun existeModulo(): Boolean = java.io.File("$RAIZ/assure-cli/build.gradle.kts").isFile
}
