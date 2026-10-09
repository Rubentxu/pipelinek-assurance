/**
 * R2.3 / M5 — Provider de cobertura JaCoCo.
 *
 * Ref autoridad:
 *  - `03-specifications/PROVIDER_SPI.md` — forma del SPI.
 *  - `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` §Safety — bounded decoding.
 *
 * El producer es JaCoCo (Kotlin/Java). El wire format es XML con
 * `<report><package name=...><class name=...><method name=...><line
 * nr=... mi=N ci=M .../></method></class></package></report>`.
 *
 * Authority: `RuntimeObserver` (la cobertura la mide el test runner,
 * no el static analyzer). M-H01.
 *
 * **AAT-6, por construcción:** el provider implementa
 * `EvidenceProvider`, sin `AssertionResult` en signatura. La
 * lógica de "cobertura suficiente" es de las assertions, no del
 * provider.
 */
package dev.pipelinek.assurance.providers.coverage

import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.ProviderFailureReason
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawItemKind

class JacocoCoverageProvider(
    private val jacocoXml: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor =
        EvidenceProviderDescriptor(
            id = "jacoco",
            version = "1.0.0",
            classification = ProviderClassification.Runtime,
            inputFormats = listOf("application/vnd.jacoco.report+xml;version=1"),
            outputSchemaVersion = "coverage/jacoco/v1",
            evidenceCapabilities = listOf("coverage.line", "coverage.branch"),
            subjectKinds = listOf("SourceLocation"),
        )

    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        val text = jacocoXml.toString(Charsets.UTF_8)
        if (!text.contains("<report>")) {
            return failed("jacoco: no contiene <report>")
        }

        val items = mutableListOf<RawEvidenceItem>()
        val lineTag = Regex("""<line\s+([^/>]*)/>""")
        for (m in lineTag.findAll(text)) {
            val attrs = m.groupValues[1]
            val nr = Regex("""nr="(\d+)"""").find(attrs)?.groupValues?.get(1) ?: continue
            val ci = Regex("""ci="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val mi = Regex("""mi="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val covered = ci > 0
            items += RawEvidenceItem(
                kind = RawItemKind.Observation,
                id = "jacoco/line/$nr",
                subjectRef = "CoverageLine/$nr",
                authority = "RuntimeObserver",
                payload = mapOf(
                    "line" to nr,
                    "covered" to covered.toString(),
                    "ci" to ci.toString(),
                    "mi" to mi.toString(),
                ),
            )
        }

        if (items.isEmpty()) {
            return failed("jacoco: ninguna línea con ci/mi encontrada")
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
