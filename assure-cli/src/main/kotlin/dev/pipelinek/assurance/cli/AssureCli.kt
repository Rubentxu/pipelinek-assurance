package dev.pipelinek.assurance.cli

import dev.pipelinek.assurance.artifact.DependencyGraphFixtureCodec
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.architecture.DependencyGraph
import dev.pipelinek.assurance.engine.architecture.acyclic
import dev.pipelinek.assurance.engine.architecture.noDependency

/**
 * Código de salida del proceso.
 *
 * No es un score: es la respuesta a la única pregunta que un pipeline hace,
 * "¿paso?". Los cuatro valores del IR (`Inconclusive`, `Unsupported`, `Error`)
 * se colapsan a 1 a propósito. Colapsarlos a códigos distintos daría la
 * impresión de que un pipeline puede distinguirlos sin leer el JSON, y no
 * puede: la ley de `no evidence != PASS` depende de que la diferencia entre
 * "pasó" y "no sé" sea visible, y un exit code que no la distingue la borra.
 */
object ExitCode {
    const val OK: Int = 0
    const val NOT_PASSED: Int = 1
    const val USAGE: Int = 2
}

/**
 * Resultado de un comando: envelope más código de salida.
 *
 * El envelope y el código viajan juntos a propósito. Un CLI que escribe el
 * envelope en stdout y el código aparte permite que uno se pierda y el otro no,
 * y un agente que lee sólo uno de los dos acaba con un veredicto sin contexto o
 * con un contexto sin veredicto.
 */
data class CommandResult(val envelope: Envelope?, val exitCode: Int)

/**
 * Despacho de los tres comandos de M1.
 *
 * Es una función pura: recibe `args` y devuelve un [CommandResult], sin tocar
 * `System.out`, sin leer el reloj y sin salir del proceso. Leer el fichero del
 * fixture entra por [leerGrafo], que sí recibe texto, para que el comando sea
 * comprobable sin disco. La consecuencia es deliberada: el CLI se prueba entero
 * sin levantar un filesystem temporal, y la ley "el CLI no decide el
 * veredicto" se puede comprobar porque aquí no hay nada que decidir fuera del
 * IR.
 */
object AssureCli {
    private val EVIDENCIA_SINTETICA = "synthetic/self-model/hexagonal/1"

    /**
     * Ejecuta un comando.
     *
     * El nombre del comando se resuelve por **prefijo más largo** contra el
     * registry, no contra un `when` de literales. La razón es concreta: la spec
     * define `assure evidence path <finding>`, dos palabras, y comparar
     * `args[0]` con `"evidence path"` no coincide nunca. La primera versión
     * tenía exactamente ese `when` y `assure evidence path` devolvía exit 2
     * como si el comando no existiera, mientras el propio envelope anunciaba
     * `assure evidence path <finding>` como acción a seguir. Un comando
     * ofrecido que devuelve error de uso es peor que un comando ausente.
     *
     * Resolver contra el registry además ata el dispatcher al contrato: no hay
     * una lista de comandos en dos sitios que puedan divergir en silencio.
     *
     * Devuelve un envelope de error con `actions` vacías en vez de lanzar. Un
     * CLI que revienta con una excepción obliga al agente a leer una traza, y
     * la ley de `AGENT_FIRST_CLI.md` es exactamente lo contrario: la respuesta
     * tiene que ser machine-readable y seguir siendo explorable.
     */
    fun ejecutar(args: List<String>, leeGrafo: (String) -> String): CommandResult {
        if (args.isEmpty()) return uso("falta el comando", CapabilityRegistry.nombres)

        val (nombre, operandos) = resolverComando(args)
            ?: return uso("comando desconocido: ${args.first()}", CapabilityRegistry.nombres)

        val operand = operandos.firstOrNull()
        return when (nombre) {
            "report" -> report(operand, leeGrafo)
            "explain" -> explicar(operand)
            "evidence path" -> evidencePath(operand)
            // Registry y dispatcher han divergido. Es un error de construccion
            // del propio CLI, no del usuario, y por eso se dice como tal en vez
            // de caer en "comando desconocido".
            else -> error(
                "comando registrado sin implementar",
                mapOf("comando" to nombre, "registrados" to CapabilityRegistry.nombres.joinToString(", ")),
            )
        }
    }

    /**
     * Prefijo más largo de [args] que sea un comando registrado.
     *
     * "Más largo" y no "primero" porque `assure evidence path` tiene que
     * ganarle a cualquier comando llamado `evidence`, y el orden del registro
     * no debería decidir qué comando gana.
     */
    private fun resolverComando(args: List<String>): Pair<String, List<String>>? {
        val candidatos = CapabilityRegistry.comandos.mapNotNull { comando ->
            val partes = comando.name.split(" ")
            if (args.size >= partes.size && args.take(partes.size) == partes) {
                comando.name to partes.size
            } else {
                null
            }
        }
        val mejor = candidatos.maxByOrNull { it.second } ?: return null
        return mejor.first to args.drop(mejor.second)
    }

    private fun report(ref: String?, leeGrafo: (String) -> String): CommandResult {
        if (ref == null) return uso("'report' necesita el ref del fixture", CapabilityRegistry.nombres)

        // `runCatching` y `map` juntos NO capturan el error: `Result.map`
        // ejecuta su bloque fuera del try, así que una excepción del codec
        // escapaba hacia el caller y `assure report` reventaba con una traza
        // en vez de devolver un envelope. Fail-closed significa que un fixture
        // ilegible produce exit 1 con envelope, no una excepción: la primera
        // versión hacía exactamente lo contrario, y un pipeline que sólo mira
        // el exit code se comía el crash como si fuera un fallo cualquiera.
        val grafo: DependencyGraph = runCatching {
            DependencyGraphFixtureCodec.decode(leeGrafo(ref))
        }.getOrElse { causa ->
            return error(
                "fixture ilegible",
                mapOf("ref" to ref, "causa" to (causa.message ?: causa::class.simpleName.orEmpty())),
            )
        }

        val evidencia = listOf(dev.pipelinek.assurance.domain.evidence.EvidenceId(EVIDENCIA_SINTETICA))
        val noDependencia = noDependency(
            assertionId = AssertionId("architecture.no-dependency"),
            snapshotId = ref,
            evidenceIds = evidencia,
        ).evaluate(grafo)
        val ciclos = acyclic(
            assertionId = AssertionId("architecture.acyclic"),
            snapshotId = ref,
            evidenceIds = evidencia,
        ).evaluate(grafo)

        // Un report es el resumen de las dos assertions. El exit code es 0
        // sólo si AMBAS pasan: un gate que pasa con una de dos assertion
        // falladas no está pasando nada, y es el error más caro porque
        // parece verde.
        val resultados = listOf(noDependencia, ciclos)
        val algunoNoPasa = resultados.any { it !is AssertionResult.Passed }
        val envelope = Envelope.deVeredicto(
            resultado = if (algunoNoPasa) resultados.first { it !is AssertionResult.Passed } else noDependencia,
            subject = ref,
            extra = mapOf(
                "assertions" to resultados.joinToString(",") { idDe(it) },
                "passed" to resultados.count { it is AssertionResult.Passed }.toString(),
                "total" to resultados.size.toString(),
            ),
        )
        return CommandResult(envelope, if (algunoNoPasa) ExitCode.NOT_PASSED else ExitCode.OK)
    }

    private fun idDe(resultado: AssertionResult): String = when (resultado) {
        is AssertionResult.Passed -> resultado.proof.assertionId.value
        is AssertionResult.Failed -> resultado.counterexample.assertionId.value
        else -> "?"
    }

    private fun explicar(finding: String?): CommandResult {
        if (finding == null) return uso("'explain' necesita el finding", CapabilityRegistry.nombres)
        val id = runCatching { CapabilityRegistry.assertionIdDe(finding) }
            .getOrElse { return error("finding invalido", mapOf("finding" to finding, "causa" to it.message.orEmpty())) }

        // `explain` no puede re-derivar un contraejemplo sin volver a
        // evaluar, y eso exigiría el snapshot. En vez de inventar una
        // explicación, se dice exactamente por qué no se puede: un comando que
        // devuelve una explicación aproximada es peor que uno que admite que
        // necesita el artefacto.
        return CommandResult(
            envelope = Envelope(
                apiVersion = CapabilityRegistry.API_VERSION,
                kind = "ExplainUnavailable",
                subject = id.value,
                data = mapOf(
                    "motivo" to "explain necesita el snapshot; usa 'report' y lee el campo data.explanation",
                ),
                actions = listOf(
                    CapabilityRegistry.Action(
                        rel = "report",
                        command = CapabilityRegistry.buscar("report")!!.invocationSobre("<ref-del-fixture>"),
                    ),
                ),
            ),
            exitCode = ExitCode.OK,
        )
    }

    private fun evidencePath(finding: String?): CommandResult {
        if (finding == null) return uso("'evidence path' necesita el finding", CapabilityRegistry.nombres)
        val id = runCatching { CapabilityRegistry.assertionIdDe(finding) }
            .getOrElse { return error("finding invalido", mapOf("finding" to finding, "causa" to it.message.orEmpty())) }

        return CommandResult(
            envelope = Envelope(
                apiVersion = CapabilityRegistry.API_VERSION,
                kind = "EvidencePath",
                subject = id.value,
                data = mapOf(
                    "evidencia" to EVIDENCIA_SINTETICA,
                    "nota" to "la evidencia de un counterexample se lee del propio report",
                ),
                actions = emptyList(),
            ),
            exitCode = ExitCode.OK,
        )
    }

    private fun uso(problema: String, comandos: List<String>) = CommandResult(
        envelope = Envelope(
            apiVersion = CapabilityRegistry.API_VERSION,
            kind = "UsageError",
            subject = problema,
            data = mapOf(
                "problema" to problema,
                "comandos" to comandos.joinToString(", "),
            ),
            // Un error de uso también es accionable: ofrece los comandos que
            // sí existen. Si no los ofreciera, un agente que escribe mal el
            // comando se queda sin siguiente paso.
            actions = comandos.map { CapabilityRegistry.Action(rel = "command", command = it) },
        ),
        exitCode = ExitCode.USAGE,
    )

    private fun error(titulo: String, datos: Map<String, String>) = CommandResult(
        envelope = Envelope(
            apiVersion = CapabilityRegistry.API_VERSION,
            kind = "Error",
            subject = titulo,
            data = datos,
            actions = listOf(
                CapabilityRegistry.Action(
                    rel = "report",
                    command = CapabilityRegistry.buscar("report")!!.invocationSobre("<ref-del-fixture>"),
                ),
            ),
        ),
        exitCode = ExitCode.NOT_PASSED,
    )

    /** `main` real: escribe JSON y sale con el código. */
    fun main(args: Array<String>) {
        val resultado = ejecutar(args.toList()) { ref ->
            java.io.File(ref).readText()
        }
        println(EnvelopeJson.encode(resultado.envelope))
        if (resultado.exitCode != ExitCode.OK) kotlin.system.exitProcess(resultado.exitCode)
    }
}

/**
 * Serialización del envelope a JSON.
 *
 * Va en su propio fichero y no usa `CanonicalJson` de `assurance-artifact` a
 * propósito: el envelope del CLI es una superficie pública para agentes, y su
 * forma la fija `AGENT_FIRST_CLI.md`. Compartir el codec con el artefacto
 * ataría el contrato del CLI a la versión de serialización del artefacto, que
 * cambia por razones suyas.
 *
 * Las claves salen ordenadas y los `actions` conservan su orden, que es el
 * orden en que un agente debería seguir.
 */
object EnvelopeJson {
    fun encode(envelope: Envelope?): String {
        if (envelope == null) return "null"
        return buildString {
            append('{')
            append("\"apiVersion\":").append(cadena(envelope.apiVersion)).append(',')
            append("\"kind\":").append(cadena(envelope.kind)).append(',')
            append("\"subject\":").append(cadena(envelope.subject)).append(',')
            append("\"data\":{")
            envelope.data.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(cadena(k)).append(':').append(cadena(v))
            }
            append("},")
            append("\"actions\":[")
            envelope.actions.forEachIndexed { i, a ->
                if (i > 0) append(',')
                append("{\"rel\":").append(cadena(a.rel))
                append(",\"command\":").append(cadena(a.command)).append('}')
            }
            append("]")
            append('}')
        }
    }

    private fun cadena(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.toInt() < 0x20 -> append("\\u").append(c.toInt().toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
