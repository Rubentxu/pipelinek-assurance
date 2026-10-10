package dev.pipelinek.assurance.domain

import dev.pipelinek.assurance.domain.evidence.Completeness
import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.combineCompletenessForCapability
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A2 (Bloque A) — Tests del álgebra de completitud.
 *
 * Ref: `03-specifications/EVIDENCE_MODEL.md` (la asimetría Fact /
 * Observation en Completeness). El plan A2 exige:
 *
 *   1. Las cuatro formas (Complete / Partial / Unknown / Unsupported)
 *      se manejan coherentemente.
 *   2. Las reglas de combinación por capability están definidas y
 *      probadas.
 *   3. No se fabrica `Complete` cuando un productor requerido no
 *      pudo observar.
 *   4. Se preserva la diferencia entre ausencia de hallazgos,
 *      ausencia de datos y ausencia de soporte.
 *
 * La función pura testeada es `combineCompletenessForCapability`
 * (exportada a nivel de módulo para ser probada aisladamente).
 */
class CompletenessAlgebraTest : AnnotationSpec() {

    // -----------------------------------------------------------------
    // Regla 1: Unsupported gana
    // -----------------------------------------------------------------

    @Test
    fun unsupported_gana_sobre_complete() {
        val r = combineCompletenessForCapability(
            listOf(Completeness.Complete, Completeness.Unsupported("foo")),
        )
        r.shouldBeInstanceOf<Completeness.Unsupported>()
        (r as Completeness.Unsupported).reason shouldBe "foo"
    }

    @Test
    fun unsupported_gana_sobre_unknown() {
        val r = combineCompletenessForCapability(
            listOf(Completeness.Unknown, Completeness.Unsupported("cap not observable")),
        )
        r.shouldBeInstanceOf<Completeness.Unsupported>()
    }

    @Test
    fun unsupported_gana_sobre_partial() {
        val r = combineCompletenessForCapability(
            listOf(
                Completeness.Partial(listOf(gap("a"))),
                Completeness.Unsupported("structured"),
            ),
        )
        r.shouldBeInstanceOf<Completeness.Unsupported>()
    }

    // -----------------------------------------------------------------
    // Regla 2: Unknown gana si no hay Unsupported
    // -----------------------------------------------------------------

    @Test
    fun unknown_gana_sobre_complete() {
        val r = combineCompletenessForCapability(
            listOf(Completeness.Complete, Completeness.Unknown),
        )
        r.shouldBeInstanceOf<Completeness.Unknown>()
    }

    @Test
    fun unknown_gana_sobre_partial() {
        val r = combineCompletenessForCapability(
            listOf(Completeness.Partial(listOf(gap("a"))), Completeness.Unknown),
        )
        r.shouldBeInstanceOf<Completeness.Unknown>()
    }

    // -----------------------------------------------------------------
    // Regla 3: Partial gana sobre Complete, con unión de gaps
    // -----------------------------------------------------------------

    @Test
    fun partial_y_complete_es_partial_con_gaps_del_partial() {
        val r = combineCompletenessForCapability(
            listOf(
                Completeness.Complete,
                Completeness.Partial(listOf(gap("missing-source"))),
            ),
        )
        val p = r.shouldBeInstanceOf<Completeness.Partial>()
        p.gaps.map { it.capability } shouldBe listOf("missing-source")
    }

    @Test
    fun multiples_partials_unen_sus_gaps() {
        val r = combineCompletenessForCapability(
            listOf(
                Completeness.Partial(listOf(gap("a"))),
                Completeness.Partial(listOf(gap("b"))),
                Completeness.Complete,
            ),
        )
        val p = r.shouldBeInstanceOf<Completeness.Partial>()
        val caps = p.gaps.map { it.capability }.toSet()
        caps shouldBe setOf("a", "b")
    }

    @Test
    fun multiples_partials_duplican_gaps_se_deduplican() {
        val r = combineCompletenessForCapability(
            listOf(
                Completeness.Partial(listOf(gap("a"))),
                Completeness.Partial(listOf(gap("a"))),
            ),
        )
        val p = r.shouldBeInstanceOf<Completeness.Partial>()
        p.gaps.size shouldBe 1
    }

    // -----------------------------------------------------------------
    // Regla 4: todos Complete = Complete
    // -----------------------------------------------------------------

    @Test
    fun todos_complete_es_complete() {
        val r = combineCompletenessForCapability(
            listOf(Completeness.Complete, Completeness.Complete, Completeness.Complete),
        )
        r shouldBe Completeness.Complete
    }

    // -----------------------------------------------------------------
    // Regla 5: lista vacía = Complete (nada incompleto)
    // -----------------------------------------------------------------

    @Test
    fun lista_vacia_es_complete() {
        // Si nadie declara la capability, no se considera incompleta.
        // La cuestión de "es required" la resuelve el caller (requiredEvidence).
        val r = combineCompletenessForCapability(emptyList())
        r shouldBe Completeness.Complete
    }

    // -----------------------------------------------------------------
    // Idempotencia y conmutatividad
    // -----------------------------------------------------------------

    @Test
    fun combinacion_es_conmutativa_con_unknown_y_complete() {
        val a = combineCompletenessForCapability(
            listOf(Completeness.Unknown, Completeness.Complete),
        )
        val b = combineCompletenessForCapability(
            listOf(Completeness.Complete, Completeness.Unknown),
        )
        a shouldBe b
    }

    @Test
    fun combinacion_es_conmutativa_con_unsupported() {
        val a = combineCompletenessForCapability(
            listOf(Completeness.Unsupported("a"), Completeness.Complete),
        )
        val b = combineCompletenessForCapability(
            listOf(Completeness.Complete, Completeness.Unsupported("a")),
        )
        a.shouldBeInstanceOf<Completeness.Unsupported>()
        b.shouldBeInstanceOf<Completeness.Unsupported>()
    }

    @Test
    fun combinacion_es_idempotente() {
        val single = listOf(Completeness.Unknown)
        combineCompletenessForCapability(single) shouldBe
            combineCompletenessForCapability(single + single + single)
    }

    // -----------------------------------------------------------------
    // Anti-regresión: el bug que A2 cierra
    // -----------------------------------------------------------------

    @Test
    fun unknown_no_se_silencia_a_complete() {
        // El bug A2: la versión previa colapsaba Unknown a
        // Complete. Esta prueba es la que, en verde, garantiza que
        // un productor que dice "no sé" nunca produce `Complete`.
        val r = combineCompletenessForCapability(listOf(Completeness.Unknown))
        r shouldNotBe Completeness.Complete
    }

    @Test
    fun unsupported_no_se_silencia_a_complete() {
        // Mismo principio: un productor que no pudo observar no es
        // lo mismo que uno que observó todo.
        val r = combineCompletenessForCapability(
            listOf(Completeness.Unsupported("not observable")),
        )
        r shouldNotBe Completeness.Complete
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun gap(capability: String): EvidenceGap = EvidenceGap(
        capability = capability,
        reason = EvidenceGap.GapReason.Unknown,
    )
}
