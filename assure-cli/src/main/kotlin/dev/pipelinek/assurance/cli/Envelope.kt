package dev.pipelinek.assurance.cli

import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.Counterexample

/**
 * Envelope del CLI, tal y como lo define `AGENT_FIRST_CLI.md`.
 *
 * Es `data class` y no `JsonObject`: el envelope tiene una forma cerrada y la
 * spec lo define campo a campo. Construirlo a mano desde un mapa deja que
 * falten campos sin que nadie lo note, y un envelope sin `actions` en un
 * resultado accionable es exactamente el fallo que UAT-021 persigue.
 *
 * Se serializa con el encoder canónico de `assurance-artifact`, no con uno
 * propio, por el mismo motivo que el resto del sistema: dos serializadores
 * producen dos formas y el digest deja de ser la firma del contenido.
 */
data class Envelope(
    val apiVersion: String,
    val kind: String,
    val subject: String,
    val data: Map<String, String>,
    /** Acciones derivadas del registry. Vacío sólo en lo no accionable. */
    val actions: List<CapabilityRegistry.Action>,
) {
    companion object {
        /**
         * Envelope de un veredicto.
         *
         * La ley HATEOAS-like se aplica aquí, no en cada comando: cualquier
         * respuesta que represente algo accionable lleva `actions` pobladas
         * desde el registry. Si `resultado` es un `Passed`, no hay nada que
         * seguir y las acciones van vacías, que es la única excepción que la
         * spec admite.
         */
        fun deVeredicto(
            resultado: AssertionResult,
            subject: String,
            extra: Map<String, String> = emptyMap(),
        ): Envelope {
            val (kind, data, acciones) = when (resultado) {
                is AssertionResult.Passed -> Triple(
                    "AssertionPass",
                    mapOf(
                        "assertionId" to resultado.proof.assertionId.value,
                        "snapshotId" to resultado.proof.snapshotId,
                        "evidence" to resultado.proof.evidenceIds.joinToString(",") { it.value },
                    ),
                    emptyList(),
                )

                is AssertionResult.Failed -> {
                    val c = resultado.counterexample
                    Triple("AssertionFailure", mapaDe(c), CapabilityRegistry.accionesPara(c))
                }

                is AssertionResult.Inconclusive -> Triple(
                    "AssertionInconclusive",
                    mapOf(
                        "gaps" to resultado.gaps.joinToString(";") { "${it.capability}:${it.reason}" },
                    ),
                    emptyList(),
                )

                is AssertionResult.Unsupported -> Triple(
                    "AssertionUnsupported",
                    mapOf("reason" to resultado.reason.toString()),
                    emptyList(),
                )

                is AssertionResult.Error -> Triple(
                    "AssertionError",
                    mapOf(
                        "phase" to resultado.failure.phase,
                        "detail" to resultado.failure.detail,
                    ),
                    emptyList(),
                )
            }
            return Envelope(
                apiVersion = CapabilityRegistry.API_VERSION,
                kind = kind,
                subject = subject,
                data = extra + data,
                actions = acciones,
            )
        }

        private fun mapaDe(c: Counterexample): Map<String, String> = buildMap {
            put("assertionId", c.assertionId.value)
            put("explanation", c.explanation)
            put("subjectRefs", c.subjectRefs.joinToString(",") { "${it.namespace}:${it.value}" })
            put("evidenceRefs", c.evidenceRefs.joinToString(",") { it.value })
            put("hints", c.reproductionHints.joinToString(" | "))
            when (c) {
                is Counterexample.DependencyPath -> {
                    put("path", c.path.joinToString(" -> "))
                    put("fromLayer", c.fromLayer)
                    put("toLayer", c.toLayer)
                }

                is Counterexample.Cycle -> put("cycle", c.cycle.joinToString(" -> "))
                else -> Unit
            }
        }
    }
}
