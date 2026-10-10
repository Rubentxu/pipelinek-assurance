package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

/**
 * M7 — `ObservedArchitectureLens`: dependencia arquitectónica observada.
 *
 * Ref autoridad: `03-specifications/LENSES.md` §ObservedArchitectureLens,
 * `03-specifications/ASSERTIONS_AND_REPORTS.md` §Counterexample.CausalSlice.
 *
 * **Qué observa:** edges de dependencia vistos en runtime por Chronos
 * (o por un producer equivalente). El `DependencyGraph` declarado en
 * M1 es el que el código dice tener; este lens es el que el código
 * realmente tiene. La diferencia entre los dos es lo que la
 * `ConsistencyLens` (M10) detecta como contradicción.
 *
 * **Cómo se materializa:** los items runtime de Chronos
 * (`CapabilityCompletenessDto` con `runtime.invocation-chain`) se
 * agregan en un grafo de dependencias observadas: cada `Observation`
 * con `payload["edge"]` representa una arista de un módulo a otro.
 * El grafo se construye con el mismo `DependencyGraph` que la
 * `HexagonalArchitectureLens` (mismas invariantes).
 *
 * **AAT-19:** la authority es `RuntimeObserver` por construcción. La
 * lens NO admite `HeuristicAnalyzer` para esta capability.
 *
 * **AAT-7:** la lens es pura. No reconstruye la lista de invocations
 * a partir de un id; recibe el `EvidenceSnapshot` ya producido.
 */
object ObservedArchitectureLens : AssuranceLens<EvidenceSnapshot, DependencyGraph> {

    const val CAPABILITY: String = "runtime.invocation-chain"
    const val EDGE_PAYLOAD_KEY: String = "edge"

    override fun project(input: EvidenceSnapshot): ProjectionResult<DependencyGraph> {
        val items = input.items.filter { item ->
            item.provenance.capability == CAPABILITY
        }
        if (items.isEmpty()) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.MissingCapability(CAPABILITY),
                gaps = listOf(
                    dev.pipelinek.assurance.domain.evidence.EvidenceGap(
                        capability = CAPABILITY,
                        reason = dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unknown,
                        detail = "ninguna evidencia runtime declara '$CAPABILITY'",
                    ),
                ),
            )
        }

        // AAT-19: sólo RuntimeObserver. Un `Signal` con un score
        // "runtime" no es un edge observado.
        val rechazados = items.filter { it.authority.name != "RuntimeObserver" }
        if (rechazados.isNotEmpty()) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' sólo admite RuntimeObserver; llegan ${rechazados.size} con otra autoridad",
                ),
                gaps = listOf(
                    dev.pipelinek.assurance.domain.evidence.EvidenceGap(
                        capability = CAPABILITY,
                        reason = dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unsupported,
                        detail = "autoridad no admitida: ${rechazados.map { it.authority.name }.distinct()}",
                    ),
                ),
            )
        }

        // Construir el grafo a partir de los edges en los payloads.
        val modules = mutableSetOf<String>()
        val edges = mutableListOf<DependencyEdge>()
        for (item in items) {
            val edge = extractEdge(item) ?: continue
            modules += edge.from
            modules += edge.to
            edges += edge
        }

        if (modules.isEmpty()) {
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' tiene ${items.size} items pero ninguno codifica un edge en su payload",
                ),
                gaps = listOf(
                    dev.pipelinek.assurance.domain.evidence.EvidenceGap(
                        capability = CAPABILITY,
                        reason = dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.PartialProduced("0/${items.size}"),
                        detail = "items sin payload 'edge'",
                    ),
                ),
            )
        }

        val layers = modules.associateWith { Layer.Application }
        return ProjectionResult.Projected(
            DependencyGraph.of(
                modules = modules,
                layers = layers,
                edges = edges,
            ),
        )
    }

    private fun extractEdge(item: EvidenceItem): DependencyEdge? {
        // El payload del item runtime es `Map<String, String>` (vía
        // `Observation.observation` se parsea aquí). En V1 el edge
        // se codifica como `edge=module_a->module_b`. La lens es
        // estricta: un item sin edge se ignora silenciosamente (no
        // falla la lens, porque Chronos puede emitir items
        // adicionales que no son edges — la granularidad es del
        // provider, no de la lens).
        val payload: Map<String, String> = when (item) {
            is EvidenceItem.Observation -> parseKeyValue(item.observation)
            // Otros tipos de EvidenceItem no son edges runtime. Un
            // item sin edge se ignora silenciosamente (no falla la
            // lens, porque Chronos puede emitir items adicionales
            // que no son edges — la granularidad es del provider,
            // no de la lens). Se enumeran explícitamente para
            // cumplir el invariante "sealed when sin else".
            is EvidenceItem.Fact,
            is EvidenceItem.Signal,
            is EvidenceItem.Hypothesis -> emptyMap()
        }
        val edge = payload[EDGE_PAYLOAD_KEY] ?: return null
        val parts = edge.split("->", limit = 2)
        if (parts.size != 2) return null
        val from = parts[0].trim()
        val to = parts[1].trim()
        if (from.isBlank() || to.isBlank()) return null
        return runCatching { DependencyEdge(from, to) }.getOrNull()
    }

    private fun parseKeyValue(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (part in text.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                out[kv[0].trim()] = kv[1].trim()
            }
        }
        return out
    }
}
