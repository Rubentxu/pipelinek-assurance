package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceAuthority
import dev.pipelinek.assurance.domain.evidence.EvidenceId
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSourceManifest
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.domain.evidence.Provenance
import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.domain.evidence.SnapshotId
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Leyes de `HexagonalArchitectureLens`.
 *
 * Aquí se certifican dos cosas que el roadmap exige a M1 y que son distintas:
 *
 *  - **UAT-005 / AAT-19.** Una señal heurística no puede satisfacer una
 *    assertion `Mandatory` que exige autoridad determinista. La ley no mira el
 *    `enforcement`: mira que la evidencia heurística **no llega a proyectarse**,
 *    con lo que la assertion acaba `Inconclusive`. Si la lens admitiera la
 *    heurística, la assertion recibiría un grafo indistinguible del bueno y
 *    `Inconclusive` se convertiría en un `Passed` sin base.
 *  - **AAT-7.** Ninguna lens escribe filesystem ni red. Se comprueba sobre el
 *    resultado y sobre la lista de gaps: si la lens hubiera leído el disco, un
 *    snapshot vacío proyectaría algo.
 *
 * Y una tercera, que es la razón de existir de la lens:
 *
 *  - **El STOP de M1.** La lens no conoce el provider. Se comprueba que dos
 *    grafos idénticos producidos por producers distintos y con versiones
 *    distintas dan el MISMO grafo proyectado.
 */
class HexagonalArchitectureLensTest : AnnotationSpec() {
    private val grafoTexto = """
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

    // -- UAT-005 / AAT-19: la heurística no satisface autoridad determinista

    @Test
    fun `una señal heurística del mismo grafo NO proyecta`() {
        // El caso que UAT-005 describe: hay evidencia, describe el grafo
        // correcto, y aun así no sirve. Un SRP signal que acertara con la
        // arquitectura no puede levantar un gate que exige determinismo,
        // porque "acertar ahora" no es la propiedad que se necesita: la
        // propiedad es "volver a acertar igual", y eso no lo garantiza una
        // heurística.
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(signalConGrafo(grafoTexto)),
        )

        resultado.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
    }

    @Test
    fun `una señal heurística acaba Inconclusive y NUNCA Passed`() {
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(signalConGrafo(grafoTexto)),
        )

        // Se evalúa la assertion sobre la proyección, no el grafo. Es el
        // camino completo que recorre el engine, y lo que se comprueba es que
        // la señal no llega a producir un veredicto.
        val veredicto = dev.pipelinek.assurance.engine.AssuranceEngine.evaluate(
            resultado,
            noDependency(
                assertionId = dev.pipelinek.assurance.engine.AssertionId("architecture.no-dependency"),
                snapshotId = "synthetic",
                evidenceIds = listOf(EvidenceId("synthetic/heuristic/signal/1")),
            ),
        )

        veredicto.shouldBeInstanceOf<AssertionResult.Inconclusive>()
    }

    @Test
    fun `el gap nombra la capability y la autoridad rechazada`() {
        // Sin esto, `Inconclusive` es una caja negra: el gate se pone
        // "inconcluso" y nadie sabe si es que falta evidencia, si está rota o
        // si es heurística. El gap tiene que decir cuál de las tres.
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(signalConGrafo(grafoTexto)),
        ).shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()

        val gap = resultado.gaps.single()
        gap.capability shouldBe HexagonalArchitectureLens.CAPABILITY
        (gap.detail!!) shouldContain "HeuristicAnalyzer"
    }

    @Test
    fun `un Fact con autoridad heurística TAMPOCO proyecta`() {
        // El caso que la ley del dominio NO cubre: `Signal` está atado a
        // `HeuristicAnalyzer` por construcción (M-H01), pero `Fact` no está
        // atado a nada, y un `Fact` heurístico se puede construir hoy. Si la
        // lens lo admitiera, AAT-19 tendría un agujero por el lado del
        // contenedor y no por el de la autoridad.
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(
                EvidenceItem.Fact(
                    id = EvidenceId("synthetic/graph/heuristic/1"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.HeuristicAnalyzer,
                    provenance = provenance(),
                    predicate = HexagonalArchitectureLens.PREDICATE,
                    objectValue = grafoTexto,
                ),
            ),
        )

        resultado.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
        resultado.gaps.single().detail!! shouldContain "HeuristicAnalyzer"
    }

    @Test
    fun `la misma evidencia determinista SÍ proyecta`() {
        // La otra mitad de UAT-005: la frontera tiene que tener los dos lados,
        // o la ley de arriba pasaría con una lens que no proyecta nunca.
        val resultado = HexagonalArchitectureLens.project(snapshotCon(factConGrafo(grafoTexto)))

        val proyectado = resultado.shouldBeInstanceOf<ProjectionResult.Projected<DependencyGraph>>()
        proyectado.value.modules shouldBe listOf(
            "adapter-artifact",
            "app-assurance",
            "domain-evidence",
            "infra-cli",
        )
    }

    // -- AAT-7: la lens no toca el mundo exterior

    @Test
    fun `un snapshot sin evidencia NO proyecta nada`() {
        // AAT-7 por observación: si la lens leyera el disco o pidiera algo al
        // exterior, un snapshot vacío daría un grafo. ProjectionFailed es lo
        // único honesto.
        val resultado = HexagonalArchitectureLens.project(snapshotCon())

        resultado.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
        resultado.gaps.single().reason shouldBe dev.pipelinek.assurance.domain.evidence.EvidenceGap.GapReason.Unknown
    }

    // -- El STOP de M1: la lens no conoce el provider

    @Test
    fun `dos producers distintos proyectan el mismo grafo`() {
        val deCogniCode = snapshotCon(
            factConGrafo(grafoTexto, producerId = "cognicode", producerVersion = "1.2.3"),
        )
        val delSelfModel = snapshotCon(
            factConGrafo(grafoTexto, producerId = "self-model", producerVersion = "0.0.1"),
        )

        val a = HexagonalArchitectureLens.project(deCogniCode)
            .shouldBeInstanceOf<ProjectionResult.Projected<DependencyGraph>>().value
        val b = HexagonalArchitectureLens.project(delSelfModel)
            .shouldBeInstanceOf<ProjectionResult.Projected<DependencyGraph>>().value

        // Si esto fallara, la lens estaría leyendo el producer: el STOP de M1
        // dice que eso significa que el IR está mal.
        a shouldBe b
    }

    @Test
    fun `la gramática del snapshot es la misma que la del fixture`() {
        // El snapshot y el fichero no pueden tener dos gramáticas que se
        // parezcan. Se comprueba que formatear y volver a parsear cierra, que
        // es la forma barata de detectar que se han separado.
        val grafo = ArchitectureGraphFormat.parse(grafoTexto)!!
        val idaYVuelta = ArchitectureGraphFormat.parse(ArchitectureGraphFormat.format(grafo))

        idaYVuelta shouldBe grafo
    }

    // -- Fail-closed de la proyección

    @Test
    fun `dos Facts con la misma capability NO se resuelven por orden de lectura`() {
        // "Último gana" fabricaría un veredicto sobre una arquitectura que
        // nadie declaró: si hay dos grafos, hay un conflicto, y el conflicto
        // se reporta.
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(
                factConGrafo(grafoTexto, id = "synthetic/graph/1"),
                factConGrafo(grafoTexto, id = "synthetic/graph/2"),
            ),
        )

        val fallo = resultado.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
        // El smart cast a `InvalidInput` no cruza módulos: `reason` es una
        // propiedad pública de un tipo de otro módulo, y el compilador no la
        // estrecha. Se hace la comprobación explícita, que además lee mejor.
        val motivo = fallo.reason
        motivo.shouldBeInstanceOf<ProjectionFailureReason.InvalidInput>()
        (motivo as ProjectionFailureReason.InvalidInput).detail shouldContain "varios Facts"
    }

    @Test
    fun `un grafo ilegible falla la proyección y no proyecta un grafo vacío`() {
        // Un grafo vacío "pasa" todas las assertions de arquitectura. Si una
        // evidencia corrupta se convirtiera en grafo vacío, el gate daría
        // verde sobre evidencia que no dice nada, que es el peor resultado
        // posible: un PASS fabricado.
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(factConGrafo("esto no es un grafo")),
        )

        resultado.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
    }

    @Test
    fun `un Fact sin objectValue falla la proyección`() {
        val resultado = HexagonalArchitectureLens.project(
            snapshotCon(
                EvidenceItem.Fact(
                    id = EvidenceId("synthetic/graph/vacio/1"),
                    subject = EvidenceSubject.Module("self"),
                    authority = EvidenceAuthority.DeterministicAnalyzer,
                    provenance = provenance(),
                    predicate = HexagonalArchitectureLens.PREDICATE,
                    objectValue = null,
                ),
            ),
        )

        resultado.shouldBeInstanceOf<ProjectionResult.ProjectionFailed>()
    }

    // -- Utilidades de construcción ----------------------------------------

    private fun revision() = RevisionRef("self@0000000000000000000000000000000000000000")

    private fun provenance(
        capability: String = HexagonalArchitectureLens.CAPABILITY,
        producerId: String = "self-model",
        producerVersion: String = "0.0.1",
    ) = Provenance(
        producerId = producerId,
        producerVersion = producerVersion,
        subjectRevision = revision(),
        capability = capability,
    )

    private fun factConGrafo(
        texto: String,
        id: String = "synthetic/graph/1",
        producerId: String = "self-model",
        producerVersion: String = "0.0.1",
    ) = EvidenceItem.Fact(
        id = EvidenceId(id),
        subject = EvidenceSubject.Module("self"),
        authority = EvidenceAuthority.DeterministicAnalyzer,
        provenance = provenance(producerId = producerId, producerVersion = producerVersion),
        predicate = HexagonalArchitectureLens.PREDICATE,
        objectValue = texto,
    )

    /**
     * La señal heurística que UAT-005 describe.
     *
     * El `Signal` exige autoridad `HeuristicAnalyzer` por construcción (M-H01),
     * así que no se puede construir una "señal determinista". Esa es la ley del
     * dominio haciendo su trabajo antes de que la lens tenga nada que decir.
     */
    private fun signalConGrafo(texto: String) = EvidenceItem.Signal(
        id = EvidenceId("synthetic/heuristic/srp/1"),
        subject = EvidenceSubject.Module("self"),
        authority = EvidenceAuthority.HeuristicAnalyzer,
        provenance = provenance(producerId = "detekt", producerVersion = "1.23.0"),
        signalKind = "srp-smell",
        score = "0.91",
        algorithmId = "detekt.srp",
        algorithmVersion = "1.23.0",
        thresholds = mapOf("threshold" to "0.8"),
    ).also {
        // El `Signal` no lleva `objectValue`: lleva la señal, no el grafo. Que
        // la lens lo rechace por la autoridad y no por el payload se comprueba
        // en el gap, que cita HeuristicAnalyzer. El `require` sólo evita que
        // el parámetro se lea como sin usar.
        require(texto.isNotEmpty()) { "el texto del grafo es contexto del caso, no del Signal" }
    }

    private fun snapshotCon(vararg items: dev.pipelinek.assurance.domain.evidence.EvidenceItem): EvidenceSnapshot = EvidenceSnapshot(
        id = SnapshotId("synthetic-snapshot"),
        subject = EvidenceSubject.Module("self"),
        sources = listOf(
            EvidenceSourceManifest(
                producerId = "self-model",
                producerVersion = "0.0.1",
                subjectRevision = revision(),
                requestedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                producedCapabilities = listOf(HexagonalArchitectureLens.CAPABILITY),
                completenessByCapability = mapOf(
                    HexagonalArchitectureLens.CAPABILITY to Completeness.Complete,
                ),
                schemaVersion = "1",
                digest = Digest("0".repeat(64)),
            ),
        ),
        items = items.toList(),
        gaps = emptyList(),
    )
}
