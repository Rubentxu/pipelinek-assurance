package dev.pipelinek.assurance.domain.capabilities

import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe

/**
 * M-CAP-DRIFT — Tests del registro centralizado de capabilities.
 *
 * Lo que se certifya:
 *   - El símbolo `ARCHITECTURE_DEPENDENCY_GRAPH` mantiene su valor
 *     canónico (`"architecture.dependency-graph"`) bajo cualquier
 *     refactor del archivo.
 *   - Un mutante que cambie el valor (M-CAP-DRIFT) rompe este
 *     test, lo que es exactamente el comportamiento que AAT-13
 *     quiere en el dominio: la distinción de namespaces es
 *     verificable, no implícita.
 */
class CapabilitiesTest : AnnotationSpec() {

    @Test
    fun architecture_dependency_graph_tiene_valor_canonico() {
        // M-CAP-DRIFT redundancia: el valor es el contrato. Un
        // cambio rompe este test.
        Capabilities.ARCHITECTURE_DEPENDENCY_GRAPH shouldBe "architecture.dependency-graph"
    }

    @Test
    fun signals_detekt_tiene_valor_canonico() {
        // M-CAP-DRIFT-2: análogo al anterior para SIGNALS_DETEKT.
        // Si el mutante añade un sufijo "-DRIFT", el test falla.
        Capabilities.SIGNALS_DETEKT shouldBe "signals.detekt"
    }

    @Test
    fun todas_las_capabilities_tienen_forma_namespace_dot_subnamespace() {
        // AAT-13: la forma `<namespace>.<subnamespace>` es la
        // convención. Un capability fuera de forma es un typo
        // detectable por este test.
        val all = listOf(
            Capabilities.ARCHITECTURE_DEPENDENCY_GRAPH,
            Capabilities.ARCHITECTURE_ENTITIES,
            Capabilities.ARCHITECTURE_RELATIONS,
            Capabilities.RUNTIME_INVOCATION_CHAIN,
            Capabilities.RUNTIME_CAUSAL_SLICE,
            Capabilities.RUNTIME_WINDOW,
            Capabilities.TEST_RESULTS,
            Capabilities.TEST_TOPOLOGY,
            Capabilities.SIGNALS_SOLID_AUDIT,
            Capabilities.SIGNALS_DETEKT,
        )
        for (capability in all) {
            (capability.contains(".")) shouldBe true
            (capability.split(".").size >= 2) shouldBe true
            (capability == capability.lowercase()) shouldBe true
        }
    }
}
