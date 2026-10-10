/**
 * B3 (Bloque B) — Lens y assertion built-in para `assurance.check`.
 *
 * Ref autoridad: plan de consolidación §B3 y
 * `03-specifications/LENSES.md`. Esta lens y assertion son
 * los **primitivos** del Step `assurance.check`: la única
 * garantía arquitectónica que el plugin puede dar en su modo
 * mínimo, sin un set de lenses/assertions externos.
 *
 * Por qué built-in y no en `assurance-engine`: la lens +
 * assertion viven aquí porque SON la implementación del Step.
 * El engine provee el álgebra (lens/assertion) y los tipos
 * (DependencyGraph, Counterexample); el plugin lo compone.
 * Moverlas al engine sería meter lógica de aplicación en el
 * core, lo que el ROADMAP §A-2 prohíbe.
 *
 * Lo que la lens hace:
 *   1. Toma un EvidenceSnapshot.
 *   2. Filtra los items `Fact` con `capability =
 *      "architecture.dependency-graph"`.
 *   3. Construye un DependencyGraph con módulos inferidos
 *      de los `subjectRef` ("module:foo") y aristas leídas
 *      de `payload["dependsOn"]` (lista separada por comas).
 *
 * Lo que la assertion hace:
 *   1. Recibe el DependencyGraph proyectado.
 *   2. Si encuentra una arista Domain → Adapters (regla
 *      hexagonal de M1), devuelve Failed con counterexample
 *      CausalSlice mínimo: los módulos, las aristas, y
 *      un `reproductionHints` que dice cómo reproducir.
 *   3. Si la projection falla (gaps de Unknown), devuelve
 *      Inconclusive.
 */
package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.TypedExternalId
import dev.pipelinek.assurance.domain.evidence.ExternalNamespace
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceAssertion
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.Counterexample
import dev.pipelinek.assurance.engine.ProofRef
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult
import dev.pipelinek.assurance.engine.architecture.DependencyEdge
import dev.pipelinek.assurance.engine.architecture.DependencyGraph
import dev.pipelinek.assurance.engine.architecture.Layer

/**
 * Lens built-in: extrae un DependencyGraph de los Fact
 * items del snapshot que declaran capability
 * `architecture.dependency-graph`.
 *
 * Forma del payload de un Fact (contrato con el provider):
 *   - `predicate`: nombre corto (e.g. "dependsOn")
 *   - `object`: lista separada por comas de módulos destino
 *
 * El `subjectRef` del item es `module:<nombre>`; ése es el
 * módulo origen. Si el predicate es "dependsOn" y el object
 * es "bar,baz", añadimos aristas (foo→bar) y (foo→baz).
 */
object BuiltinLens : AssuranceLens<EvidenceSnapshot, DependencyGraph> {
    override fun project(input: EvidenceSnapshot): ProjectionResult<DependencyGraph> {
        val factItems = input.items.filterIsInstance<EvidenceItem.Fact>()
            .filter { it.provenance.capability == "architecture.dependency-graph" }

        if (factItems.isEmpty()) {
            // Sin Fact items, projection fallida: la
            // incompletitud debe propagarse como Inconclusive
            // en la assertion, no como Pass silencioso.
            return ProjectionResult.ProjectionFailed(
                reason = ProjectionFailureReason.MissingCapability("architecture.dependency-graph"),
                gaps = listOf(
                    dev.pipelinek.assurance.domain.evidence.EvidenceGap(
                        capability = "architecture.dependency-graph",
                        reason = dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unknown,
                    ),
                ),
            )
        }

        val modules = mutableSetOf<String>()
        val layers = mutableMapOf<String, Layer>()
        val edges = mutableSetOf<DependencyEdge>()

        for (item in factItems) {
            val subjectModule = (item.subject as? EvidenceSubject.Module)?.path
                ?: continue
            modules.add(subjectModule)
            layers.putIfAbsent(subjectModule, inferLayer(subjectModule))
            if (item.predicate == "dependsOn") {
                val deps = item.objectValue?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: continue
                for (dep in deps) {
                    modules.add(dep)
                    layers.putIfAbsent(dep, inferLayer(dep))
                    edges.add(DependencyEdge(subjectModule, dep))
                }
            }
        }

        return ProjectionResult.Projected(
            DependencyGraph.of(
                modules = modules,
                layers = layers,
                edges = edges,
            ),
        )
    }

    private fun inferLayer(name: String): Layer = when {
        // Heurística mínima: por convención de M1, los
        // módulos del dominio tienen "domain" en el path;
        // adapters tienen "adapters"; infrastructure tiene
        // "infra" o "cli". Esto es un default; los callers
        // pueden sobreescribir vía fixture explícita.
        name.contains("domain", ignoreCase = true) -> Layer.Domain
        name.contains("adapters", ignoreCase = true) -> Layer.Adapters
        name.contains("infra", ignoreCase = true) -> Layer.Infrastructure
        name.contains("cli", ignoreCase = true) -> Layer.Infrastructure
        else -> Layer.Application
    }
}

/**
 * Assertion built-in: ninguna arista puede ir de la capa
 * Domain a una capa más externa (regla hexagonal de M1).
 *
 * Mandatory por defecto. Si la projection es un grafo
 * vacío (no hay Fact items), devuelve Inconclusive: la
 * incompletitud no es un PASS.
 */
class BuiltinAssertion(
    val assertionId: AssertionId = AssertionId("architecture.no-domain-to-external"),
    private val forbiddenFrom: Layer = Layer.Domain,
    private val forbiddenTo: Set<Layer> = setOf(Layer.Application, Layer.Adapters, Layer.Infrastructure),
) : AssuranceAssertion<DependencyGraph> {

    override fun evaluate(input: DependencyGraph): AssertionResult {
        val violations = input.edges.filter { edge ->
            val fromLayer = input.layers[edge.from]
            val toLayer = input.layers[edge.to]
            fromLayer == forbiddenFrom && toLayer in forbiddenTo
        }

        if (violations.isEmpty()) {
            return AssertionResult.Passed(
                ProofRef(
                    snapshotId = "graph",
                    evidenceIds = input.modules.map {
                        dev.pipelinek.assurance.domain.evidence.EvidenceId("module/$it")
                    },
                    assertionId = assertionId,
                ),
            )
        }

        val first = violations.first()
        val counterexample = Counterexample.CausalSlice(
            assertionId = assertionId,
            subjectRefs = listOf(
                TypedExternalId(ExternalNamespace.GitRevision, "module/${first.from}"),
                TypedExternalId(ExternalNamespace.GitRevision, "module/${first.to}"),
            ),
            evidenceRefs = input.modules.map {
                dev.pipelinek.assurance.domain.evidence.EvidenceId("module/$it")
            },
            explanation = "arista prohibida: ${first.from} (${input.layers[first.from]}) " +
                "→ ${first.to} (${input.layers[first.to]})",
            reproductionHints = listOf(
                "remove the dependsOn from ${first.from} to ${first.to} in the source",
                "or move ${first.from} out of layer ${input.layers[first.from]}",
            ),
            invocationChain = violations.map { "${it.from} → ${it.to}" },
        )
        return AssertionResult.Failed(counterexample)
    }
}
