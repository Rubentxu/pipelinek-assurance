/**
 * M5 — Provider JUnit XML (surefire / gradle test reports).
 *
 * Ref autoridad:
 *  - `03-specifications/PROVIDER_SPI.md` — forma del SPI y `descriptor`.
 *  - `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` §Safety — bounded
 *    decoding aplicado a un wire format externo.
 *  - Surefire XML schema (de facto) — JUnit 4 con extensiones de Ant
 *    Surefire: `<testsuite>` con `<testcase>` y, dentro, `<failure>` /
 *    `<error>` / `<skipped>`.
 *
 * Por qué un provider propio y no reusar la codec de CogniCode: JUnit XML
 * es un formato de surefire/gradle, no un envelope propio. No hay
 * `apiVersion`, no hay `digest`, no hay `capabilityCompleteness`. Lo que
 * hay es un árbol DOM chato con `testsuite -> testcase`. Un codec
 * unificado con CogniCode sería un acoplamiento por similitud
 * accidental, no por contrato.
 *
 * Por qué `javax.xml.parsers.DocumentBuilder`: ya está en el JDK, no
 * añade dependencias, y es la única API que garantiza que el parser
 * respeta los límites de XXE (entidades externas) sin tener que
 * configurar un parser externo. Un parser que carga DTDs externos es
 * una superficie de ataque (XXE), y `DocumentBuilderFactory` por
 * defecto en JDK reciente los desactiva, pero los configuramos
 * EXPLÍCITAMENTE para que un cambio de defaults del JDK no nos
 * exponga.
 *
 * **AAT-6, por construcción:** este provider implementa
 * `EvidenceProvider`, cuyo SPI no expone `AssertionResult`.
 */
package dev.pipelinek.assurance.providers.junit

import dev.pipelinek.assurance.domain.capabilities.Capabilities
import dev.pipelinek.assurance.engine.EvidenceCollectionResult
import dev.pipelinek.assurance.engine.EvidenceProvider
import dev.pipelinek.assurance.engine.EvidenceProviderDescriptor
import dev.pipelinek.assurance.engine.EvidenceRequest
import dev.pipelinek.assurance.engine.ProviderClassification
import dev.pipelinek.assurance.engine.ProviderFailureReason
import dev.pipelinek.assurance.engine.RawEvidenceGap
import dev.pipelinek.assurance.engine.RawEvidenceItem
import dev.pipelinek.assurance.engine.RawGapReason
import dev.pipelinek.assurance.engine.RawItemKind
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Provider JUnit XML (formato surefire / gradle test reports).
 *
 * Identidad fija por construcción: el descriptor es una `val` literal y
 * NO depende del XML. Misma razón que los otros providers: una versión
 * del producer que el motor ya conoce.
 *
 * Capacidades declaradas:
 *  - `test.results` — cada testcase con su estado (passed/failed/error/
 *    skipped) es un item. El consumer puede contar.
 *  - `test.topology` — nombre de la suite + classname de cada test
 *    forman un grafo de pertenencia (la lens `TestTopologyLens` lo
 *    agrega).
 *
 * Sujetos admitidos: `Test` (un testcase JUnit ES un test). SARIF
 * emite `SourceLocation`; aquí emitimos `Test`, porque el sujeto
 * natural de un testcase no es un archivo: es un test.
 *
 * Clasificación: `Deterministic`. Un test que pasa hoy pasa mañana con
 * el mismo código. JUnit XML no es runtime observability (e.g. OTel
 * spans); es un reporte post-mortem de una ejecución reproducible.
 *
 * `outputSchemaVersion = "junit/v4"` es la versión del formato JUnit
 * (v4 = Surefire XML, el formato dominante en gradle/maven). La
 * asimetría con SARIF (que tiene un schema URL) es deliberada: JUnit
 * XML nunca tuvo un versionado formal del schema, y "v4" es la
 * convención del ecosistema.
 */
class JUnitXmlProvider(
    private val junitXmlBytes: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor = EvidenceProviderDescriptor(
        id = "junit-xml",
        version = "0.1.0",
        evidenceCapabilities = listOf(
            Capabilities.TEST_RESULTS,
            Capabilities.TEST_TOPOLOGY,
        ),
        subjectKinds = listOf("Test"),
        classification = ProviderClassification.Deterministic,
        inputFormats = listOf("application/junit+xml"),
        outputSchemaVersion = "junit/v4",
    )

    /**
     * Parsea el XML JUnit y emite evidencia cruda.
     *
     * El método es síncrono a propósito: `EvidenceProvider.collect` tiene
     * firma no suspendida. La entrada ya está leída por el caller; no
     * hay I/O en este provider (AAT-7 / SPI).
     *
     * Manejo de errores:
     *  - Si el parse lanza `CodecException` (incluyendo XML inválido,
     *    XXE detectado, profundidad excesiva, colección
     *    sobredimensionada), devolvemos `Failed` con
     *    `ProviderFailureReason.UnsupportedInputFormat`.
     *  - Otros errores: `CollectionError` con detalle.
     *
     * Bounded decoding: aplicado en `JUnitXmlCodec` ANTES de construir
     * items. Este provider NO vuelve a validar longitudes.
     */
    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        val parsed = try {
            JUnitXmlCodec.decode(junitXmlBytes)
        } catch (e: JUnitXmlCodec.CodecException) {
            return EvidenceCollectionResult.Failed(
                producerId = descriptor.id,
                reason = ProviderFailureReason.UnsupportedInputFormat(
                    "el JUnit XML no cumple ${descriptor.id}/v${descriptor.version}: ${e.message}",
                ),
                gaps = listOf(
                    RawEvidenceGap(
                        capability = CAPABILITY_TEST_RESULTS,
                        reason = RawGapReason.Unknown,
                        detail = "decode falló: ${e.message}",
                    ),
                ),
            )
        } catch (e: Exception) {
            return EvidenceCollectionResult.Failed(
                producerId = descriptor.id,
                reason = ProviderFailureReason.CollectionError(
                    "decodificación no recuperable: ${e::class.simpleName}: ${e.message}",
                ),
                gaps = listOf(
                    RawEvidenceGap(
                        capability = CAPABILITY_TEST_RESULTS,
                        reason = RawGapReason.Unknown,
                        detail = "no se pudo recolectar",
                    ),
                ),
            )
        }

        val rawItems = parsed.testCases.map { tc -> testCaseToRawItem(tc) }

        return EvidenceCollectionResult.Produced(
            producerId = descriptor.id,
            producerVersion = descriptor.version,
            schemaVersion = descriptor.outputSchemaVersion,
            rawItems = rawItems,
            declaredGaps = emptyList(),
        )
    }

    // -----------------------------------------------------------------------
    // Mapeo testcase -> RawEvidenceItem
    // -----------------------------------------------------------------------

    /**
     * Convierte un `testcase` a `RawEvidenceItem`.
     *
     * Política de kinds por status (la asimetría es deliberada, no un
     * bug):
     *  - `passed` -> `Fact`. El test pasó; es una afirmación
     *    reproducible. `DeterministicAdapter` (un test no es un
     *    analyzer, es un adapter que ejecuta código).
     *  - `failed` -> `Observation`. El test falló, pero el
     *    "qué falló" es la observación; el motor decide si eso es un
     *    defect.
     *  - `errored` -> `Observation`. Similar a failed pero
     *    distinguible: un error es un problema de setup, no un
     *    assertion fallida. El provider preserva la distinción en
     *    `payload["status"]` y deja al consumer la decisión de
     *    tratarlos igual o distinto.
     *  - `skipped` -> `Fact` con `payload["status"]="skipped"`. Un
     *    test skipped no es una observación (no se ejecutó); es un
     *    hecho: "este test no se ejecutó". El consumer lo cuenta
     *    aparte.
     *
     * Por qué autoridad siempre `DeterministicAdapter`: un test es
     * un adaptador que ejecuta código real. No es un analyzer
     * estático ni un runtime observer (esos son OTel/Chronos). El
     * `DeterministicAdapter` describe exactamente la autoridad
     * epistémica de un test JUnit: bit-a-bit reproducible sobre el
     * mismo commit.
     */
    private fun testCaseToRawItem(tc: JUnitXmlCodec.TestCase): RawEvidenceItem {
        val (kind, _) = when (tc.status) {
            JUnitXmlCodec.TestStatus.PASSED -> RawItemKind.Fact to "passed"
            JUnitXmlCodec.TestStatus.FAILED -> RawItemKind.Observation to "failed"
            JUnitXmlCodec.TestStatus.ERRORED -> RawItemKind.Observation to "errored"
            JUnitXmlCodec.TestStatus.SKIPPED -> RawItemKind.Fact to "skipped"
        }
        val capability = CAPABILITY_TEST_TOPOLOGY
        val payload = buildMap {
            put("classname", tc.classname)
            put("name", tc.name)
            put("time", tc.time)
            put("status", tc.status.name.lowercase())
            put("capability", capability)
            if (tc.suite != null) put("suite", tc.suite)
        }
        val subjectRef = buildString {
            append("junit:Test:")
            append(tc.classname)
            append('.')
            append(tc.name)
        }
        val id = buildString {
            append("junit-xml/result/")
            append(tc.classname)
            append('/')
            append(tc.name)
        }
        return RawEvidenceItem(
            kind = kind,
            id = id,
            subjectRef = subjectRef,
            authority = "DeterministicAdapter",
            payload = payload,
            sourceLocation = null,
        )
    }

    private companion object {
        // Capabilities declaradas en el descriptor; referencian
        // `Capabilities` en `assurance-domain` para que un cambio del
        // nombre canónico se haga en un solo sitio (ver M-CAP-DRIFT).
        const val CAPABILITY_TEST_RESULTS = Capabilities.TEST_RESULTS
        const val CAPABILITY_TEST_TOPOLOGY = Capabilities.TEST_TOPOLOGY
    }
}

/**
 * Codec mínimo de JUnit XML.
 *
 * Visibilidad `internal`: este codec no es una API pública del módulo
 * `assurance-providers`. Forma parte del boundary de decoding; los
 * consumidores externos deben recibir un `EvidenceCollectionResult` ya
 * normalizado, no llamar a `decode` directamente.
 *
 * Política de cotas: misma que `CogniCodeEvidenceExportCodec` y
 * `DetektSarifCodec` (64 MiB de input, 1024 elementos por colección).
 * Sin cota de profundidad: el JDK `DocumentBuilder` ya limita la
 * profundidad por defecto (`entityExpansionLimit`, etc.); un XML mal
 * formado se rechaza con `SAXException` antes de construir un árbol
 * arbitrario. Lo que SÍ acotamos es el tamaño del texto, porque es la
 * primera línea de defensa contra un input gigante.
 *
 * Política XXE: por defecto en JDK recientes las entidades externas
 * están deshabilitadas, pero las configuramos EXPLÍCITAMENTE. La
 * razón no es desconfianza en el JDK: es que un cambio de defaults
 * (e.g. upgrade a una JDK que los habilita por defecto) nos
 * expondría sin que ningún test falle. Hacer la configuración
 * explícita es la forma honesta de decir "este parser no carga
 * entidades externas, y eso es por diseño".
 */
internal object JUnitXmlCodec {

    /** Cotas — heredadas de los otros codecs por la misma razón. */
    const val MAX_INPUT_BYTES: Long = 64L * 1024 * 1024
    const val MAX_COLLECTION_SIZE: Int = 1024

    // -----------------------------------------------------------------------
    // Tipos
    // -----------------------------------------------------------------------

    /**
     * Resultado del decode: lista plana de testcases.
     *
     * El surefire XML tiene `<testsuites>` (raíz) o `<testsuite>`
     * (raíz directa). Aceptamos ambos porque gradle y maven emiten
     * cada uno su variante. Aplanamos a una lista de testcases
     * porque para el consumer la jerarquía no aporta: lo que importa
     * es cuántos tests pasaron/fallaron, no cómo se anidaban las
     * suites. La `suite` (nombre) se preserva en el payload del
     * item para que el consumer pueda reconstruir la jerarquía si
     * quiere.
     */
    data class Decoded(
        val testCases: List<TestCase>,
    )

    data class TestCase(
        val classname: String,
        val name: String,
        val time: String,
        val status: TestStatus,
        val suite: String?,
    )

    enum class TestStatus { PASSED, FAILED, ERRORED, SKIPPED }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Decodifica JUnit XML desde bytes UTF-8.
     *
     * Pasos:
     *   1. `MAX_INPUT_BYTES` — sobre los BYTES, antes de parsear.
     *   2. Construcción de `DocumentBuilder` con XXE off.
     *   3. Parse del árbol DOM.
     *   4. Extracción de testcases con `requireWithinLimits`.
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size > MAX_INPUT_BYTES) {
            throw CodecException(
                "entrada JUnit XML de ${bytes.size} bytes excede MAX_INPUT_BYTES=$MAX_INPUT_BYTES",
            )
        }
        val doc = try {
            newSafeDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (e: Exception) {
            throw CodecException("JUnit XML no parseable: ${e.message}", e)
        }
        val root = doc.documentElement
            ?: throw CodecException("JUnit XML sin elemento raíz")

        // Aceptamos `<testsuites>` (raíz con uno o más `<testsuite>`) o
        // `<testsuite>` directo. Surefire/gradle emiten `<testsuites>`;
        // algunos tools antiguos emiten `<testsuite>` directo.
        val suiteElements: List<Element> = when (root.tagName) {
            "testsuites" -> childElements(root, "testsuite")
            "testsuite" -> listOf(root)
            else -> throw CodecException("JUnit XML raíz inesperada: ${root.tagName} (esperada testsuites o testsuite)")
        }
        if (suiteElements.size > MAX_COLLECTION_SIZE) {
            throw CodecException(
                "testsuites: ${suiteElements.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE",
            )
        }

        val cases = mutableListOf<TestCase>()
        for (suite in suiteElements) {
            val suiteName = suite.getAttribute("name").ifBlank { null }
            val caseElements = childElements(suite, "testcase")
            if (caseElements.size > MAX_COLLECTION_SIZE) {
                throw CodecException(
                    "testcase de suite '$suiteName': ${caseElements.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE",
                )
            }
            for (case in caseElements) {
                cases.add(parseTestCase(case, suiteName))
            }
        }
        if (cases.size > MAX_COLLECTION_SIZE) {
            throw CodecException(
                "testcases totales: ${cases.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE",
            )
        }
        return Decoded(testCases = cases)
    }

    // -----------------------------------------------------------------------
    // Construcción del parser seguro
    // -----------------------------------------------------------------------

    /**
     * Construye un `DocumentBuilder` con XXE deshabilitado
     * explícitamente.
     *
     * El JDK moderno (≥ 17) trae `FEATURE_SECURE_PROCESSING` por
     * defecto, pero configurar cada feature manualmente es la única
     * forma de que un cambio de defaults no nos exponga. Las
     * features que nos importan:
     *  - `XMLConstants.FEATURE_SECURE_PROCESSING` — activa el
     *    modo seguro general del parser.
     *  - `disallow-doctype-decl` — rechaza `<!DOCTYPE>`, que es la
     *    puerta de entrada de XXE. Si un test quiere emitir un
     *    JUnit XML con DOCTYPE, este provider lo rechaza, y eso
     *    es por diseño: un test report no necesita DOCTYPE.
     *  - `external-general-entities` y `external-parameter-entities`
     *    deshabilitados — bloquean la carga de DTDs externos.
     *  - `load-external-dtd` deshabilitado — bloquea la carga de
     *    DTDs externos desde el sistema.
     */
    private fun newSafeDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Forma explícita de desactivar XXE, no implícita por
            // esperanza de defaults seguros.
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder()
    }

    // -----------------------------------------------------------------------
    // Extracción
    // -----------------------------------------------------------------------

    /**
     * Devuelve los hijos directos del elemento con el tag pedido.
     *
     * Por qué `childElements` y no `getElementsByTagName`: el segundo
     * es recursivo y trae nietos. Si un testcase se llama
     * `<error>` y dentro tiene un sub-elemento `<error>`, el método
     * recursivo los confunde. Iterar hijos directos es la forma
     * honesta de decir "estos son los testcases de esta suite, no
     * cualquier `testcase` en cualquier sub-árbol".
     */
    private fun childElements(parent: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n: Node = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n is Element && n.tagName == tag) {
                out.add(n)
            }
        }
        return out
    }

    /**
     * Convierte un `<testcase>` a `TestCase` (DTO interno).
     *
     * Política de status:
     *  - `<failure>` hijo -> `FAILED`.
     *  - `<error>` hijo -> `ERRORED`.
     *  - `<skipped>` hijo -> `SKIPPED`.
     *  - sin ninguno -> `PASSED`.
     *
     * El orden de la inspección es importante: un testcase
     * técnicamente no puede tener `<failure>` Y `<error>` a la vez
     * (surefire no lo emite), pero si lo hace, miramos `<error>`
     * primero porque un error es más grave que un failure (un
     * failure es "el test pasó hasta la assertion X"; un error
     * es "el setup explotó").
     */
    private fun parseTestCase(el: Element, suite: String?): TestCase {
        val classname = el.getAttribute("classname")
        val name = el.getAttribute("name")
        val time = el.getAttribute("time").ifBlank { "0.0" }
        val status = when {
            childElements(el, "error").isNotEmpty() -> TestStatus.ERRORED
            childElements(el, "failure").isNotEmpty() -> TestStatus.FAILED
            childElements(el, "skipped").isNotEmpty() -> TestStatus.SKIPPED
            else -> TestStatus.PASSED
        }
        return TestCase(
            classname = classname,
            name = name,
            time = time,
            status = status,
            suite = suite,
        )
    }

    /**
     * Error de decoding. Distinto de las excepciones de dominio a
     * propósito: un `CodecException` significa "el artefacto no se
     * pudo decodificar"; un fallo de invariante del dominio
     * significa "se decodificó pero viola invariantes".
     */
    class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
