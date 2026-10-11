package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * G2 (Bloque G) — congelación de `FrozenAssuranceRuntime`.
 *
 * El runtime es el catálogo inmutable que el
 * orchestrator recibe en cada evaluación. La regla
 * dice: una vez construido, el caller no puede mutar
 * el mapa de lenses. Estos tests son la cerradura.
 *
 * **No es un test de comportamiento**: no evalúa una
 * suite. Es un test de contrato del tipo. Si pasan
 * estos 3 tests, el runtime cumple la regla 0 + regla
 * 4 del bloque G.
 */
class FrozenAssuranceRuntimeTest : AnnotationSpec() {

    private val engineVersion = "0.1.0"
    private val lensIdA = LensId("l/a")
    private val lensIdB = LensId("l/b")
    private val noopLens: AssuranceLens<EvidenceSnapshot, String> =
        AssuranceLens { _ -> ProjectionResult.Projected("ok") }
    private val noopLensB: AssuranceLens<EvidenceSnapshot, String> =
        AssuranceLens { _ -> ProjectionResult.Projected("also-ok") }

    @Test
    fun G2_runtime_rechaza_engineVersion_blank() {
        val ex = shouldThrow<IllegalArgumentException> {
            FrozenAssuranceRuntime(engineVersion = "", lenses = emptyMap())
        }
        ex.message!!.contains("engineVersion") shouldBe true
    }

    @Test
    fun G2_freeze_aísla_mutaciones_del_caller() {
        // Caller construye un HashMap mutable.
        val mutable = HashMap<LensId, AssuranceLens<EvidenceSnapshot, *>>()
        mutable[lensIdA] = noopLens
        val runtime = FrozenAssuranceRuntime(
            engineVersion = engineVersion,
            lenses = mutable,
        ).freeze()

        // El caller muta su mapa original: el runtime
        // congelado no se entera.
        mutable[lensIdB] = noopLensB

        runtime.lenses.size shouldBe 1
        runtime.lenses[lensIdA] shouldBe noopLens
        (lensIdB in runtime.lenses) shouldBe false
    }

    @Test
    fun G2_runtime_acepta_lenses_por_mapOf_literal() {
        // Caso normal: el caller usa `mapOf(...)` (que ya
        // es inmutable). El runtime lo guarda tal cual.
        val runtime = FrozenAssuranceRuntime(
            engineVersion = engineVersion,
            lenses = mapOf(lensIdA to noopLens),
        )
        runtime.lenses[lensIdA] shouldBe noopLens
    }
}
