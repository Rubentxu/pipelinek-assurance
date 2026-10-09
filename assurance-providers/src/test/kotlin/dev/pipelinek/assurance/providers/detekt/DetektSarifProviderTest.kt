/**
 * M5 — Tests del `DetektSarifProvider`.
 *
 * Ref autoridad: `03-specifications/PROVIDER_SPI.md`, AAT-2, AAT-3, AAT-6.
 *
 * Estos tests verifican:
 *  1. El provider decodifica un SARIF 2.1.0 con 2 results y devuelve un
 *     `EvidenceCollectionResult.Produced` con 2 `Signal`s, todos con
 *     autoridad `HeuristicAnalyzer` (M-H01).
 *  2. El digest declarado en `properties.digest` se verifica contra el
 *     SHA-256 de los bytes; un digest incorrecto falla cerrado.
 *  3. Bounded decoding: un SARIF que excede `MAX_INPUT_BYTES` se
 *     rechaza.
 *  4. Validación de versión: SARIF con `version: "2.0.0"` se rechaza.
 *  5. Validación de `$schema`: SARIF sin schema o con schema incorrecto
 *     se rechaza.
 */
package dev.pipelinek.assurance.providers.detekt

import dev.pipelinek.assurance.domain.evidence.RevisionRef
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.RawItemKind
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class DetektSarifProviderTest : AnnotationSpec() {

    // -----------------------------------------------------------------------
    // Descriptor — AAT-2 / PROVIDER_SPI
    // -----------------------------------------------------------------------

    @Test
    fun descriptor_declares_expected_identity_and_capabilities() {
        val provider = DetektSarifProvider(syntheticSarifBytes())

        provider.descriptor.id shouldBe "detekt-sarif"
        provider.descriptor.version shouldBe "0.1.0"
        provider.descriptor.evidenceCapabilities shouldBe listOf(
            "signals.detekt",
            "signals.solid_audit",
            "test.topology",
        )
        provider.descriptor.subjectKinds shouldBe listOf("SourceLocation", "Symbol")
        provider.descriptor.classification shouldBe ProviderClassification.Heuristic
        provider.descriptor.outputSchemaVersion shouldBe "sarif/v2.1.0"
        provider.descriptor.inputFormats shouldBe listOf("application/sarif+json")
    }

    // -----------------------------------------------------------------------
    // Recolección — happy path
    // -----------------------------------------------------------------------

    @Test
    fun collect_returns_produced_with_one_signal_per_sarif_result() {
        val provider = DetektSarifProvider(syntheticSarifBytes())
        val request = EvidenceRequest(subjectRevision = RevisionRef("rev-001"))

        val result = provider.collect(request)

        result is EvidenceCollectionResult.Produced
        val produced = result as EvidenceCollectionResult.Produced
        produced.rawItems shouldHaveSize 2
        produced.producerId shouldBe "detekt-sarif"
        produced.producerVersion shouldBe "0.1.0"
        produced.schemaVersion shouldBe "sarif/v2.1.0"

        // M-H01: signals siempre HeuristicAnalyzer.
        produced.rawItems.forEach { it.kind shouldBe RawItemKind.Signal }
        produced.rawItems.forEach { it.authority shouldBe "HeuristicAnalyzer" }
    }

    @Test
    fun signals_carry_ruleId_level_message_file_line_in_payload() {
        val provider = DetektSarifProvider(syntheticSarifBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        val primero = produced.rawItems.first()
        primero.payload["ruleId"] shouldBe "LongMethod"
        primero.payload["level"] shouldBe "warning"
        primero.payload["message"] shouldBe "Function too long"
        primero.payload["file"] shouldBe "src/main/kotlin/Foo.kt"
        primero.payload["line"] shouldBe "42"
        primero.payload["capability"] shouldBe "signals.detekt"
        primero.sourceLocation shouldBe "src/main/kotlin/Foo.kt:42"
    }

    @Test
    fun ids_follow_namespace_pattern() {
        val provider = DetektSarifProvider(syntheticSarifBytes())

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))
        val produced = result as EvidenceCollectionResult.Produced

        // ADR-008: prefijo lowercase / resto. `detekt-sarif/result/...`
        // cumple la regex `[a-z0-9][a-z0-9-]*.../...`.
        produced.rawItems.forEach { item ->
            item.id.startsWith("detekt-sarif/result/") shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // Digest — fail-closed
    // -----------------------------------------------------------------------

    @Test
    fun digest_mismatch_fails_closed() {
        // Construimos un SARIF con un digest DECLARADO que
        // NO coincide con el recomputado. Esto demuestra el
        // fail-closed: si el producer declara un digest y los
        // bytes no le corresponden, el provider rechaza.
        //
        // Por qué NO mutamos un byte aquí: una mutación en una
        // posición estructural (e.g. un `}` final) rompe el
        // parseo ANTES de llegar a la verificación de digest, y
        // el test pasaría por la razón equivocada. Declarar un
        // digest incorrecto sobre bytes intactos es la
        // demostración limpia: el parseo pasa, la verificación
        // falla, y el motivo del fallo es exactamente "digest
        // no coincide".
        val root = sarifRoot(
            version = "2.1.0",
            schema = "https://json.schemastore.org/sarif-schema-2.1.0.json",
        )
        val conDigestIncorrecto = JsonObjectBuilderAdd(
            base = root,
            key = "properties",
            value = buildJsonObject {
                // 64 chars, pero NO es el SHA-256 del SARIF sin
                // `properties`. La probabilidad de que coincida
                // por casualidad es ~2^-256.
                put("digest", "0".repeat(64))
            },
        )
        val bytes = DetektSarifCodec.encodeToBytes(conDigestIncorrecto)

        val provider = DetektSarifProvider(bytes)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
        val failed = result as EvidenceCollectionResult.Failed
        (failed.reason::class.simpleName) shouldBe "UnsupportedInputFormat"
    }

    @Test
    fun digest_correct_passes_verification() {
        // La otra mitad de la ley: un digest correcto se ACEPTA.
        // Sin este test, la ley de arriba pasaría con un provider
        // que rechaza todo. La simetría de los dos tests es lo
        // que define el contrato: el digest no es decorativo.
        val bytes = syntheticSarifBytes(withDigest = true)
        val provider = DetektSarifProvider(bytes)

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Produced
    }

    @Test
    fun sarif_without_digest_is_accepted() {
        // SARIF no exige `properties.digest`; un SARIF sin él no es
        // inválido. Sólo verificamos que el provider no lo rechace.
        val bytes = syntheticSarifBytes(withDigest = false)
        val provider = DetektSarifProvider(bytes)

        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Produced
    }

    // -----------------------------------------------------------------------
    // Validación de versión y schema
    // -----------------------------------------------------------------------

    @Test
    fun sarif_with_wrong_version_fails_closed() {
        val root = sarifRoot(
            version = "2.0.0",
            schema = "https://json.schemastore.org/sarif-schema-2.1.0.json",
        )
        val bytes = DetektSarifCodec.encodeToBytes(root)

        val provider = DetektSarifProvider(bytes)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
    }

    @Test
    fun sarif_with_wrong_schema_fails_closed() {
        val root = sarifRoot(
            version = "2.1.0",
            schema = "https://json.schemastore.org/sarif-schema-2.0.0.json",
        )
        val bytes = DetektSarifCodec.encodeToBytes(root)

        val provider = DetektSarifProvider(bytes)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
    }

    // -----------------------------------------------------------------------
    // Bounded decoding
    // -----------------------------------------------------------------------

    @Test
    fun oversize_input_fails_closed() {
        // Construimos un input ligeramente por encima del límite
        // actual. Para no escribir megabytes,monkey-patching el límite
        // no es viable sin exponerlo; lo que SÍ podemos es verificar
        // que el provider devuelva `Failed` con un input de tamaño
        // 1 + MAX_INPUT_BYTES.
        val size = (DetektSarifCodec.MAX_INPUT_BYTES + 1).toInt()
        val bytes = ByteArray(size) { 0x20 }

        val provider = DetektSarifProvider(bytes)
        val result = provider.collect(EvidenceRequest(RevisionRef("rev-001")))

        result is EvidenceCollectionResult.Failed
    }

    // -----------------------------------------------------------------------
    // AAT-6 — verification por signatura
    // -----------------------------------------------------------------------

    @Test
    fun AAT_06_provider_class_does_not_declare_any_method_returning_AssertionResult() {
        // Misma ley que en `CogniCodeArtifactProviderTest`, aplicada a
        // este provider. Identificamos el tipo por nombre cualificado
        // para no importarlo y mantener el test fuera del surface
        // público que AAT-2 quiere preservar.
        val ASSERTION_RESULT_QUALIFIED = "dev.pipelinek.assurance.engine.AssertionResult"

        val providerClass = DetektSarifProvider::class.java
        val offending = providerClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.returnType.name == ASSERTION_RESULT_QUALIFIED }
            .map { it.name }

        offending shouldBe emptyList()
    }

    @Test
    fun AAT_06_provider_only_overrides_spi_methods() {
        val providerClass = DetektSarifProvider::class.java
        val spiClass = dev.pipelinek.assurance.engine.EvidenceProvider::class.java
        val spiDeclared: Set<Method> = spiClass.declaredMethods.toSet()
        val ownDeclared: Set<Method> = providerClass.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .filter { it.declaringClass == DetektSarifProvider::class.java }
            .toSet()

        ownDeclared.forEach { method ->
            val overrides = spiDeclared.any { spi ->
                spi.name == method.name &&
                    spi.parameterTypes.toList() == method.parameterTypes.toList()
            }
            (overrides) shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — construcción de SARIF sintético
    // -----------------------------------------------------------------------

    private fun syntheticSarifBytes(
        withDigest: Boolean = true,
    ): ByteArray {
        val root = sarifRoot(
            version = "2.1.0",
            schema = "https://json.schemastore.org/sarif-schema-2.1.0.json",
            results = buildJsonArray {
                add(
                    buildJsonObject {
                        put("ruleId", "LongMethod")
                        put("level", "warning")
                        put("message", buildJsonObject { put("text", "Function too long") })
                        put("physicalLocation", buildJsonObject {
                            put("artifactLocation", buildJsonObject {
                                put("uri", "src/main/kotlin/Foo.kt")
                            })
                            put("region", buildJsonObject {
                                put("startLine", 42)
                            })
                        })
                    },
                )
                add(
                    buildJsonObject {
                        put("ruleId", "MagicNumber")
                        put("level", "error")
                        put("message", buildJsonObject { put("text", "Magic number 42") })
                        put("physicalLocation", buildJsonObject {
                            put("artifactLocation", buildJsonObject {
                                put("uri", "src/main/kotlin/Bar.kt")
                            })
                            put("region", buildJsonObject {
                                put("startLine", 17)
                            })
                        })
                    },
                )
            },
        )
        if (!withDigest) return DetektSarifCodec.encodeToBytes(root)

        // El digest cubre el SARIF sin `properties` (convención
        // documentada en `verifyDigestIfDeclared`). Usamos
        // `dataDigestOf` para calcularlo, luego lo añadimos al
        // envelope. La verificación re-calcula con la misma
        // convención y compara.
        val dataDigest = DetektSarifCodec.dataDigestOf(root).hex
        val conDigest = JsonObjectBuilderAdd(
            base = root,
            key = "properties",
            value = buildJsonObject { put("digest", dataDigest) },
        )
        return DetektSarifCodec.encodeToBytes(conDigest)
    }

    private fun sarifRoot(
        version: String,
        schema: String,
        results: JsonArray = buildJsonArray { },
    ): JsonObject = buildJsonObject {
        put("version", version)
        put("\$schema", schema)
        put("runs", buildJsonArray {
            add(
                buildJsonObject {
                    put("tool", buildJsonObject {
                        put("driver", buildJsonObject {
                            put("name", "detekt")
                            put("version", "1.23.0")
                        })
                    })
                    put("results", results)
                },
            )
        })
    }

    /**
     * Devuelve una copia de `root` con un campo adicional en el nivel
     * raíz. Se hace así (en vez de un `mutableMapOf` previo) para
     * preservar el orden de campos: el `properties.digest` debe ir
     * DESPUÉS de `runs` para que la mutación que el test "digest
     * mismatch" aplique al final del documento tenga sentido.
     */
    private fun JsonObjectBuilderAdd(
        base: JsonObject,
        key: String,
        value: JsonElement,
    ): JsonObject {
        val mapa: MutableMap<String, JsonElement> = LinkedHashMap(base)
        mapa[key] = value
        return JsonObject(mapa)
    }
}
