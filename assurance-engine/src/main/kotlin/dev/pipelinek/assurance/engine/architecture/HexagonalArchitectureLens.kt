package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

/**
 * Lens del vertical hexagonal: `DependencyGraph` desde evidencia.
 *
 * **El STOP de M1, obedecido.** "Si una lens necesita conocer el provider para
 * proyectar, el `Evidence IR` está mal". Esta lens no sabe qué produjo la
 * evidencia, ni con qué versión, ni si viene de CogniCode o de una ejecución
 * anterior. Recibe un `EvidenceSnapshot` y busca `Fact`s de la capability
 * `architecture.dependency-graph`. Si no las encuentra, devuelve
 * `ProjectionFailed`, y quien decide el veredicto es la assertion.
 *
 * Eso tiene una consecuencia que conviene tener presente al escribirla: **la
 * lens no sabe distinguir "no hay grafo" de "el grafo está roto"**, y no debe
 * intentarlo. Las dos son `ProjectionFailed`, y el detalle va en el `detail`
 * del gap. Una lens que intentara adivinar estaría haciendo dos trabajos:
 * proyectar y diagnosticar, y el segundo no le toca.
 *
 * **AAT-19, en la frontera de la lens.** Sólo acepta autoridad determinista.
 * La comprobación vive aquí y no en la assertion porque la assertion no ve la
 * evidencia, sólo la proyección: si la lens admitiera una heurística, la
 * assertion recibiría un grafo indistinguible del bueno y no podría saber que
 * lo está leyendo.
 *
 * **Cómo se localiza el grafo.** Por `predicate` y `capability`, que es donde
 * el IR dice que vive la información, no por el tipo de evidencia. Un `Fact`
 * con el `predicate` y la `capability` de esta lens lleva el grafo entero en
 * `objectValue`. Se busca por capability y no por el
 * `producerId`, porque la versión del producer es justo lo que la lens no
 * debe saber.
 */
object HexagonalArchitectureLens : AssuranceLens<EvidenceSnapshot, DependencyGraph> {
    /** Capability que la lens requiere del snapshot. */
    const val CAPABILITY: String = "architecture.dependency-graph"

    /** Predicate que declara que el `objectValue` es un grafo de dependencias. */
    const val PREDICATE: String = "declara-dependency-graph"

    override fun project(input: EvidenceSnapshot): ProjectionResult<DependencyGraph> {
        // La lens busca por capability ANTES de mirar el tipo de evidencia, y
        // no al revés. Filtrar por `Fact` primero hacía que un `Signal` de la
        // misma capability pareciera "no hay evidencia", que es un gap
        // equivocado: hay evidencia, lo que hay es del tipo que esta lens no
        // admite, y el gate tiene que poder distinguir las dos cosas para no
        // reportar "falta el analyser" cuando el analyser estaba y dio una
        // respuesta heurística.
        val declarada = input.items.filter { it.provenance.capability == CAPABILITY }

        if (declarada.isEmpty()) {
            return fallo(
                reason = ProjectionFailureReason.MissingCapability(CAPABILITY),
                gapReason = EvidenceGap.GapReason.Unknown,
                detail = "ninguna evidencia declara '$CAPABILITY'",
            )
        }

        // Evidencia de la capability que no es un `Fact` declarativo. Se
        // comprueba antes que el filtro de `predicate`, porque un `Signal`
        // nunca lleva el `predicate` que esta lens espera, y el motivo por el
        // que se rechaza es la autoridad, no el predicado ausente.
        val heuristica = declarada.filterIsInstance<EvidenceItem.Signal>()
        if (heuristica.isNotEmpty()) {
            // AAT-19. No es un error del snapshot: es un tipo de evidencia que
            // esta lens no admite, y el sitio honesto para decirlo es aquí,
            // antes de construir nada.
            return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' exige autoridad determinista y llega " +
                        heuristica.joinToString(", ") { "${it.authority} (${it.id.value})" },
                ),
                gapReason = EvidenceGap.GapReason.Unsupported,
                detail = "autoridad heurística no admitida por la lens hexagonal: " +
                    heuristica.joinToString(", ") { it.authority.name },
            )
        }

        val candidatos = declarada
            .filterIsInstance<EvidenceItem.Fact>()
            .filter { it.predicate == PREDICATE }

        if (candidatos.isEmpty()) {
            return fallo(
                reason = ProjectionFailureReason.MissingCapability(CAPABILITY),
                gapReason = EvidenceGap.GapReason.Unknown,
                detail = "ningun Fact declara '$CAPABILITY' con predicate '$PREDICATE'",
            )
        }

        // Más de un Fact para la misma capability es un conflicto de
        // autoridad, no un "último gana". Dos grafos declarados en el mismo
        // snapshot significan que alguien no sabe cuál vale, y elegir uno por
        // orden de lectura fabricaría un veredicto sobre una arquitectura que
        // nadie declaró.
        if (candidatos.size > 1) {
            return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "varios Facts declaran '$CAPABILITY': ${candidatos.map { it.id.value }.sorted()}",
                ),
                gapReason = EvidenceGap.GapReason.PartialProduced(
                    coveredFraction = "0/${candidatos.size}",
                ),
                detail = "conflicto: mas de un grafo declarado en el mismo snapshot",
            )
        }

        val hecho = candidatos.single()

        // AAT-19, segunda mitad. El `Signal` ya se ha rechazado arriba, así que
        // aquí sólo queda comprobar la autoridad del `Fact`. No es redundante
        // con el filtro anterior: la ley del dominio obliga a que un `Signal`
        // sea `HeuristicAnalyzer`, pero nada obliga a que un `Fact` sea
        // determinista, y un `Fact` heurístico es construible hoy.
        if (!hecho.authority.isDeterministic) {
            return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' exige autoridad determinista y llega " +
                        "${hecho.authority} (${hecho.id.value})",
                ),
                gapReason = EvidenceGap.GapReason.Unsupported,
                detail = "autoridad ${hecho.authority} no admitida por la lens hexagonal",
            )
        }

        val texto = hecho.objectValue
        if (texto == null) {
            return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "el Fact '${hecho.id.value}' declara '$CAPABILITY' sin objectValue",
                ),
                gapReason = EvidenceGap.GapReason.PartialProduced(coveredFraction = "0/1"),
                detail = "payload ausente",
            )
        }

        val grafo = ArchitectureGraphFormat.parse(texto)
            ?: return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' no declara un grafo legible: ${hecho.id.value}",
                ),
                gapReason = EvidenceGap.GapReason.PartialProduced(coveredFraction = "0/1"),
                detail = "payload ilegible: $texto",
            )

        return ProjectionResult.Projected(grafo)
    }

    private fun fallo(
        reason: ProjectionFailureReason,
        gapReason: EvidenceGap.GapReason,
        detail: String,
    ): ProjectionResult.ProjectionFailed = ProjectionResult.ProjectionFailed(
        reason = reason,
        gaps = listOf(EvidenceGap(capability = CAPABILITY, reason = gapReason, detail = detail)),
    )
}

/**
 * Gramática compartida por el fixture en disco y por el `Fact` del snapshot.
 *
 * Vive aquí y no en `assurance-artifact` por una razón de dependencia: la lens
 * vive en `assurance-engine`, y `assurance-artifact` depende del engine, así
 * que el engine no puede depender del artefacto. La gramática describe qué
 * significa un grafo, que es dominio, no serialización de artefacto.
 *
 * `parse` devuelve `null` en vez de lanzar, y la diferencia es deliberada: el
 * engine no decide qué hacer con una evidencia ilegible, y el único sitio que
 * puede convertir eso en gap o en error es la lens, no el parser.
 *
 * Es `public` y no `internal` por una razón concreta: es la gramática
 * compartida, y si sólo la lens pudiera usarla, el provider que escriba el
 * `Fact` en M2 tendría que duplicarla. Duplicar una gramática es exactamente
 * como se crea la divergencia silenciosa entre el fichero en disco y el
 * snapshot, que es el defecto que la ley del roundtrip existe para cazar.
 */
object ArchitectureGraphFormat {
    /** Serializa un grafo al formato canónico, idéntico al del fixture. */
    fun format(graph: DependencyGraph): String = buildString {
        appendLine("modules")
        graph.modules.forEach { appendLine("  $it @ ${graph.layers.getValue(it).name}") }
        appendLine("edges")
        graph.edges.forEach { appendLine("  ${it.from} -> ${it.to}") }
    }

    /** Lee un grafo del formato canónico, o `null` si no es legible. */
    fun parse(texto: String): DependencyGraph? {
        val modulos = mutableListOf<Pair<String, Layer>>()
        val aristas = mutableListOf<DependencyEdge>()
        var seccion: String? = null

        for (bruta in texto.lineSequence()) {
            val linea = bruta.substringBefore('#').trim()
            if (linea.isEmpty()) continue
            when {
                linea == "modules" || linea == "edges" -> seccion = linea
                seccion == "modules" -> {
                    val partes = linea.split("@", limit = 2)
                    if (partes.size != 2) return null
                    val capa = Layer.of(partes[1].trim()) ?: return null
                    modulos += partes[0].trim() to capa
                }

                seccion == "edges" -> {
                    val partes = linea.split("->", limit = 2)
                    if (partes.size != 2) return null
                    aristas += runCatching { DependencyEdge(partes[0].trim(), partes[1].trim()) }
                        .getOrNull() ?: return null
                }

                else -> return null
            }
        }
        if (modulos.isEmpty()) return null
        val nombres = modulos.map { it.first }
        if (nombres.distinct().size != nombres.size) return null
        val conocidas = nombres.toSet()
        if (aristas.any { it.to !in conocidas || it.from !in conocidas }) return null
        return DependencyGraph.of(nombres, modulos.associate { it.first to it.second }, aristas)
    }
}
