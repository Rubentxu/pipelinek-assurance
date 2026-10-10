/**
 * R2.3 / M5 — Provider de mutation testing (Pitest XML).
 *
 * Ref autoridad:
 *  - `03-specifications/PROVIDER_SPI.md` — forma del SPI.
 *  - `03-specifications/MUTATION_CATALOG.md` — los mutantes del
 *    catálogo se certifican con el harness `tools/certify_mutants.py`.
 *    El provider expone la **producción** de mutantes (métricas de
 *    cobertura de mutación) en formato consumible por las lenses.
 *
 * Producer: Pitest (Java/Kotlin). Wire format: XML con
 * `<mutations><mutation detected="true|false" status="KILLED|SURVIVED|
 * NO_COVERAGE|TIMED_OUT|MEMORY_ERROR" numberOfTestsRun="N"><sourceFile>
 * ...</sourceFile><mutatedClass>...</mutatedClass><mutatedMethod>
 * ...</mutatedMethod><lineNumber>...</lineNumber>...</mutation></mutations>`.
 *
 * Authority: `RuntimeObserver` (la ejecución del test runner mata o
 * no al mutante; el static analyzer no decide). M-H01.
 */
package dev.pipelinek.assurance.providers.mutation

import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.ProviderFailureReason
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind

class PitestMutationProvider(
    private val pitestXml: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor =
        EvidenceProviderDescriptor(
            id = "pitest",
            version = "1.0.0",
            classification = ProviderClassification.Runtime,
            inputFormats = listOf("application/vnd.pitest.mutations+xml;version=1"),
            outputSchemaVersion = "mutation/pitest/v1",
            evidenceCapabilities = listOf("mutation.killed", "mutation.survived"),
            subjectKinds = listOf("SourceLocation", "Symbol"),
        )

    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        val text = pitestXml.toString(Charsets.UTF_8)
        if (!text.contains("<mutation")) {
            return failed("pitest: no contiene <mutation>")
        }

        val items = mutableListOf<RawEvidenceItem>()
        val mutations = Regex(
            """<mutation\s+([^>]*)>([\s\S]*?)</mutation>""",
        ).findAll(text)

        for (m in mutations) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val status = Regex("""status="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: "UNKNOWN"
            val detected = Regex("""detected="([^"]+)"""").find(attrs)?.groupValues?.get(1) == "true"
            val sourceFile = Regex("""<sourceFile>([^<]+)</sourceFile>""").find(body)?.groupValues?.get(1) ?: "?"
            val mutatedClass = Regex("""<mutatedClass>([^<]+)</mutatedClass>""").find(body)?.groupValues?.get(1) ?: "?"
            val lineNumber = Regex("""<lineNumber>(\d+)</lineNumber>""").find(body)?.groupValues?.get(1) ?: "0"
            // C2: el `mutator` distingue dos mutaciones en
            // la misma línea/clase/fichero. Sin él, dos
            // operadores en la misma línea producirían
            // ids idénticos, colisionando en el
            // normalizer (AAT-13). Lo incluimos en el id
            // para preservar la identidad del mutante.
            val mutator = Regex("""<mutator>([^<]+)</mutator>""").find(body)?.groupValues?.get(1) ?: "unknown"
            items += RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "pitest/$sourceFile/$mutatedClass/$lineNumber/$mutator",
                subjectRef = "MutationSite/$sourceFile:$lineNumber",
                authority = "RuntimeObserver",
                payload = mapOf(
                    "sourceFile" to sourceFile,
                    "mutatedClass" to mutatedClass,
                    "lineNumber" to lineNumber,
                    "mutator" to mutator,
                    "status" to status,
                    "detected" to detected.toString(),
                ),
            )
        }

        if (items.isEmpty()) {
            return failed("pitest: ningún <mutation> parseable")
        }

        return EvidenceCollectionResult.Produced(
            producerId = descriptor.id,
            producerVersion = descriptor.version,
            schemaVersion = descriptor.outputSchemaVersion,
            rawItems = items,
            declaredGaps = emptyList(),
        )
    }

    private fun failed(reason: String): EvidenceCollectionResult.Failed =
        EvidenceCollectionResult.Failed(
            producerId = descriptor.id,
            reason = ProviderFailureReason.CollectionError(reason),
            gaps = emptyList(),
        )
}
