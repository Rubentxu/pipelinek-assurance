package dev.pipelinek.assurance.cli

import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.Counterexample

/**
 * Registro de capacidades del CLI.
 *
 * **Por qué existe esto y no una lista de comandos sueltos.** La ley
 * HATEOAS-like de `03-specifications/AGENT_FIRST_CLI.md` dice que toda respuesta
 * machine-readable accionable incluye los comandos válidos para seguir
 * explorando, "derivados del capability registry, no hardcodeados por el
 * agente".
 *
 * Esa palabra, *derivados*, es el contrato. Si cada respuesta del CLI
 * escribiera su propio array de `actions` con strings literales, la ley sería
 * indescifrable: nadie puede comprobar que un comando exista, y un comando
 * borrado del CLI seguiría apareciendo en las salidas de los demás. Con un
 * registry, los `actions` se generan desde la misma tabla que despacha, y una
 * ley puede afirmar que todo comando emitido es ejecutable.
 *
 * El cost es explícito y es el correcto: añadir un comando obliga a añadirlo
 * aquí, y aquí es donde se prueba que hace algo.
 */
object CapabilityRegistry {
    /** Versión del envelope. Cambiarla rompe clientes, así que no se cambia por gusto. */
    const val API_VERSION: String = "assurance.pipelinek.dev/v1"

    /** Un comando del CLI, con lo que sabe hacer y sobre qué opera. */
    data class Command(
        val name: String,
        val summary: String,
        /** Sufijo fijo de argumentos, `null` si el comando no lleva operandos. */
        val operand: String?,
    ) {
        /** Comando invocable tal cual, sin argumentos. */
        val invocation: String get() = if (operand == null) "assure $name" else "assure $name <$operand>"

        /** Comando invocable sobre un recurso concreto. */
        fun invocationSobre(recurso: String): String =
            if (operand == null) "assure $name" else "assure $name $recurso"
    }

    /**
     * Comandos de M1. WP-004 limita el CLI a tres, y la restricción es real:
     * un CLI con trece comandos tiene tres de probados y trece de adivinados.
     *
     * Los que el roadmap no pide todavía (`capabilities`, `reproduce`, `diff`)
     * no se registran. Registrarlos "porque la spec los lista" sería
     * implementar la mitad de un contrato y llamarla cumplida.
     */
    val comandos: List<Command> = listOf(
        Command("report", "Veredicto de un snapshot sobre un grafo de dependencias", "ref"),
        Command("explain", "Explicación de un contraejemplo, con su camino o ciclo", "finding"),
        Command("evidence path", "Ruta de evidencia que sustenta el contraejemplo", "finding"),
    )

    private val porNombre: Map<String, Command> = comandos.associateBy { it.name }

    /** `null` si el comando no existe. */
    fun buscar(nombre: String): Command? = porNombre[nombre.trim()]

    /** Nombres de todos los comandos, en orden canónico. */
    val nombres: List<String> get() = comandos.map { it.name }

    /**
     * Relaciones disponibles sobre un contraejemplo concreto.
     *
     * Se generan del registro y no se escriben a mano en cada respuesta, por
     * el motivo de la ley: si `evidence path` no estuviera en el registro,
     * ninguna respuesta podría ofrecerlo, y si estuviera pero no funcionara,
     * esta función seguiría emitiéndolo. La segunda mitad la cubre el test que
     * ejecuta cada comando emitido.
     */
    fun accionesPara(counterexample: Counterexample): List<Action> {
        val hallazgo = counterexample.assertionId.value
        return listOf(
            Action(
                rel = "counterexample",
                command = buscar("evidence path")!!.invocationSobre(hallazgo),
            ),
            Action(
                rel = "explain",
                command = buscar("explain")!!.invocationSobre(hallazgo),
            ),
        )
    }

    /** Una relación del envelope, tal y como la spec la define. */
    data class Action(val rel: String, val command: String)

    /**
     * Verifica que un `AssertionResult` produce `actions` no vacías.
     *
     * Un `Passed` no es accionable y no lleva `actions`: no hay nada que
     * seguir. Un veredicto que no sea `Passed` y no tenga acciones es un
     * callejón sin salida para el agente, que es exactamente lo que la ley
     * prohíbe.
     */
    fun requiereAcciones(resultado: AssertionResult): Boolean = when (resultado) {
        is AssertionResult.Passed -> false
        else -> true
    }

    /** Id de assertion legible por un humano, para usarlo como `<finding>`. */
    fun findingDe(counterexample: Counterexample): String = counterexample.assertionId.value

    /** Normaliza un `<finding>` a un [AssertionId] o falla con un mensaje útil. */
    fun assertionIdDe(finding: String): AssertionId =
        runCatching { AssertionId(finding) }.getOrElse {
            throw IllegalArgumentException(
                "'$finding' no es un id de assertion valido. " +
                    "Los findings de este CLI se nombran como la assertion que los produjo, " +
                    "por ejemplo 'architecture.no-dependency'.",
            )
        }
}
