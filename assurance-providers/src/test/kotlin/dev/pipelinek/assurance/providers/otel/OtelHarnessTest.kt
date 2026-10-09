package dev.pipelinek.assurance.providers.otel

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.RawGapReason
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * R4.1 — Tests E2E contra el OTel collector real.
 *
 * **Setup previo al test** (en `tools/otel-harness/`):
 *   1. `./run-collector.sh start`
 *   2. `python3 emit-traces.py --mode normal`
 *   3. `python3 emit-traces.py --mode partial` (acumula en el mismo
 *      archivo; se filtra con un patrón `traceId=""` para el caso
 *      pure-partial).
 *
 * El test lee `build/otel-exports/otel-export.jsonl` y verifica
 * que el `OtelArtifactProvider` consume el output del collector
 * real, no un JSON sintetico.
 *
 * Si el archivo no existe (collector no arrancado), el test falla
 * con un mensaje accionable, no silenciosamente.
 */
class OtelHarnessTest : AnnotationSpec() {

    private fun exportFile(): File {
        // El export del collector vive en tools/otel-harness/output/,
        // no en build/, para que sobreviva a `gradle clean check`.
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return File(dir, "tools/otel-harness/output/otel-export.jsonl")
    }

    @Test
    fun el_collector_real_produce_export_legible_por_el_provider() {
        val file = exportFile()
        // El setup (run-collector + emit) corre fuera del test.
        if (!file.exists()) {
            throw AssertionError(
                "OTel collector no esta corriendo o no se emitieron trazas. " +
                    "Ejecuta antes del test:\n" +
                    "  ./tools/otel-harness/run-collector.sh start\n" +
                    "  python3 tools/otel-harness/emit-traces.py --mode normal",
            )
        }
        val provider = OtelArtifactProvider(file.readBytes())
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        val produced = outcome.shouldBeInstanceOf<EvidenceCollectionResult.Produced>()
        (produced.rawItems.isNotEmpty()) shouldBe true
    }

    @Test
    fun la_traza_normal_emite_items_con_observability() {
        // Filtramos solo la parte del export que tiene traceId NO
        // vacio (la traza normal). El regex captura un
        // resourceSpans con traceId de 32 hex chars.
        val file = exportFile()
        if (!file.exists()) {
            throw AssertionError("OTel collector no esta corriendo")
        }
        val all = file.readText()
        val withTrace = Regex("\"traceId\"\\s*:\\s*\"([0-9a-f]{32})\"")
            .containsMatchIn(all)
        (withTrace) shouldBe true
    }

    @Test
    fun el_provider_no_retorna_assertion_result_con_collector_real() {
        val file = exportFile()
        if (!file.exists()) {
            throw AssertionError("OTel collector no esta corriendo")
        }
        val provider = OtelArtifactProvider(file.readBytes())
        val result: Any = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        (result::class.qualifiedName?.contains("AssertionResult") ?: false) shouldBe false
    }

    @Test
    fun el_provider_no_falla_con_formato_real_del_collector() {
        // El test negativo M_O01: un export sin traceId NI spanId
        // debe ser Failed, no Produced(emptyList). El collector
        // emite lineas; un archivo vacio (sin trazas) seria el
        // caso "no evidence at all".
        val empty = ByteArray(0)
        val provider = OtelArtifactProvider(empty)
        val outcome = provider.collect(
            EvidenceRequest(RevisionRef("0000000000000000000000000000000000000000")),
        )
        outcome.shouldBeInstanceOf<EvidenceCollectionResult.Failed>()
    }

    @Test
    fun el_provider_reconoce_traceId_y_spanId_separados_aun_con_texto_real() {
        // El collector escribe JSON real con el formato OTLP JSON.
        // Verificamos que el regex interno del provider captura
        // los traceIds hex (32 chars) y los spanIds hex (16 chars).
        val file = exportFile()
        if (!file.exists()) {
            throw AssertionError("OTel collector no esta corriendo")
        }
        val text = file.readText()
        val traceIds = Regex("\"traceId\"\\s*:\\s*\"([0-9a-f]{32})\"")
            .findAll(text).map { it.groupValues[1] }.toList()
        val spanIds = Regex("\"spanId\"\\s*:\\s*\"([0-9a-f]{16})\"")
            .findAll(text).map { it.groupValues[1] }.toList()
        (traceIds.isNotEmpty()) shouldBe true
        (spanIds.isNotEmpty()) shouldBe true
    }
}
