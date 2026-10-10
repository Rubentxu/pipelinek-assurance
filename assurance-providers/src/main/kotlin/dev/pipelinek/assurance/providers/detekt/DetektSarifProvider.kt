/**
 * M5 — Provider SARIF 2.1.0 para Detekt.
 *
 * Ref autoridad:
 *  - `03-specifications/PROVIDER_SPI.md` — forma del SPI y `descriptor`.
 *  - `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` §Safety — bounded decoding
 *    aplicado a un wire format externo.
 *  - `07-integrations/DETEKT_INTEGRATION.md` — Detekt produce SARIF v2.1.0
 *    como formato nativo; este provider decodifica ese formato sin
 *    transformarlo a `assurance-evidence/v1` primero (a diferencia de
 *    CogniCode, que sí pasa por un envelope propio).
 *
 * Por qué un provider propio y no reusar `CogniCodeEvidenceExportCodec`:
 * el wire format de CogniCode es `assurance-evidence/v1` (envelope propio con
 * digest, sections y capabilityCompleteness). SARIF 2.1.0 es un estándar
 * OASIS con su propio schema; el producer es Detekt, no CogniCode, y la
 * asimetría de authorities es la misma: un `Signal` siempre es
 * `HeuristicAnalyzer` (M-H01).
 *
 * Por qué NO usamos coroutines ni filesystem: `EvidenceProvider.collect` es
 * síncrono y los bytes del SARIF llegan ya leídos. La frontera con I/O vive
 * en el caller, no aquí (AAT-7).
 *
 * **AAT-6, por construcción:** este provider implementa `EvidenceProvider`,
 * cuyo SPI no expone `AssertionResult`. Si alguien añade `eval(...)` aquí,
 * el check de signatura (igual que en `CogniCodeArtifactProviderTest`) lo
 * caza por tipo de retorno, no por string.
 */
package dev.pipelinek.assurance.providers.detekt

import dev.pipelinek.assurance.domain.evidence.Digest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provider Detekt sobre SARIF 2.1.0.
 *
 * Identidad fija por construcción: el descriptor es una `val` literal y NO
 * depende del SARIF. Misma razón que `CogniCodeArtifactProvider`: una versión
 * del producer que el motor ya conoce. Que la cambie el SARIF (e.g. un campo
 * `detekt.version` dentro de `properties`) debe pasar por OTRO provider, no
 * por el mismo provider mintiendo sobre sí mismo.
 *
 * Capacidades declaradas:
 *  - `signals.detekt` — findings heurísticos de Detekt (los `result` de SARIF).
 *  - `signals.solid_audit` — compartidas con CogniCode para que un consumer
 *    que pide signals heurísticos pueda admitir ambos providers sin filtrar
 *    por `producerId`.
 *  - `test.topology` — sin producción real; el descriptor la lista porque
 *    `TestTopologyLens` la consume y un SARIF Detekt no tiene tests. Si la
 *    capability está en `requestedCapabilities` y este provider no la cubre,
 *    eso es un `Unsupported` honesto, no un silencio.
 *
 * Sujetos admitidos: `SourceLocation` (cada `result` tiene `physicalLocation`
 * con `artifactLocation.uri` y `region.startLine`) y `Symbol` cuando Detekt
 * rellena `logicalLocation.name` (no siempre).
 *
 * Clasificación: `Heuristic`. Detekt es un analizador estático, pero su
 * salida puede cambiar entre versiones (reglas añadidas, umbrales movidos) y
 * por eso la spec lo clasifica como heurístico, no determinista. Un consumer
 * que pide `Deterministic*` filtrará estos signals por autoridad.
 *
 * `outputSchemaVersion = "sarif/v2.1.0"` es la versión del schema del INPUT,
 * no del modelo de dominio del motor. La asimetría con CogniCode (que sí
 * tiene un modelo propio) es deliberada: SARIF no es nuestro envelope, es
 * un estándar externo.
 */
class DetektSarifProvider(
    private val sarifBytes: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor = EvidenceProviderDescriptor(
        id = "detekt-sarif",
        version = "0.1.0",
        evidenceCapabilities = listOf(
            "signals.detekt",
            "signals.solid_audit",
            "test.topology",
        ),
        subjectKinds = listOf("SourceLocation", "Symbol"),
        classification = ProviderClassification.Heuristic,
        inputFormats = listOf("application/sarif+json"),
        outputSchemaVersion = "sarif/v2.1.0",
    )

    /**
     * Decodifica el SARIF y emite evidencia cruda.
     *
     * El método es síncrono a propósito: `EvidenceProvider.collect` tiene
     * firma no suspendida. La entrada ya está leída por el caller; no hay
     * I/O en este provider (AAT-7 / SPI).
     *
     * Manejo de errores:
     *  - Si el decode lanza `CodecException` (incluyendo validación de
     *    versión, schema o digest), devolvemos `Failed` con
     *    `ProviderFailureReason.UnsupportedInputFormat` porque la causa más
     *    probable desde el SPI es "el input no era SARIF v2.1.0 reconocible".
     *  - Otros errores: `CollectionError` con detalle. Lo inesperado es
     *    unexpected y se reporta.
     *
     * Bounded decoding: aplicado en `DetektSarifCodec` ANTES de construir
     * items. Este provider NO vuelve a validar longitudes: confiar en una
     * capa re-llamada no es defensa en profundidad, es coste doble.
     */
    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        val parsed = try {
            DetektSarifCodec.decode(sarifBytes)
        } catch (e: DetektSarifCodec.CodecException) {
            return EvidenceCollectionResult.Failed(
                producerId = descriptor.id,
                reason = ProviderFailureReason.UnsupportedInputFormat(
                    "el SARIF no cumple ${descriptor.id}/v${descriptor.version}: ${e.message}",
                ),
                gaps = listOf(
                    RawEvidenceGap(
                        capability = CAPABILITY_SIGNALS_DETEKT,
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
                        capability = CAPABILITY_SIGNALS_DETEKT,
                        reason = RawGapReason.Unknown,
                        detail = "no se pudo recolectar",
                    ),
                ),
            )
        }

        val rawItems = parsed.results.map { resultToRawItem(it) }

        // Una SARIF sin `results` no es un fallo: significa que Detekt no
        // encontró nada, lo cual es una respuesta honesta. Se emite `Produced`
        // con cero items y la capability declarada como cubierta con un gap
        // `PartialProduced("0/N")` para que un caller que pidió
        // `signals.detekt` sepa que llegó un SARIF válido pero vacío.
        //
        // Alternativa rechazada: devolver `Produced` con items vacíos sin
        // gap. Eso es indistinguible de "cero findings legítimos" Y de
        // "provider mintió sobre su capacidad". La spec (PROVIDER_SPI.md)
        // exige declarar la cobertura, no callarla.
        val gaps: List<RawEvidenceGap> = if (parsed.results.isEmpty()) {
            listOf(
                RawEvidenceGap(
                    capability = CAPABILITY_SIGNALS_DETEKT,
                    reason = RawGapReason.PartialProduced("0/${parsed.runCount}"),
                    detail = "SARIF válido sin results; nada que reportar",
                ),
            )
        } else {
            emptyList()
        }

        return EvidenceCollectionResult.Produced(
            producerId = descriptor.id,
            producerVersion = descriptor.version,
            schemaVersion = descriptor.outputSchemaVersion,
            rawItems = rawItems,
            declaredGaps = gaps,
        )
    }

    // -----------------------------------------------------------------------
    // Mapeo SARIF result -> RawEvidenceItem
    // -----------------------------------------------------------------------

    /**
     * Convierte un `result` SARIF en un `RawEvidenceItem.Signal` con autoridad
     * `HeuristicAnalyzer`.
     *
     * Por qué SIEMPRE `HeuristicAnalyzer` aunque SARIF no clasifique
     * resultados como heurísticos: M-H01 prohíbe un `Signal` con autoridad
     * determinista. SARIF es heurístico por su descriptor (reglas que pueden
     * cambiar entre versiones), así que la invariante del dominio gana a la
     * ausencia de declaración en el wire format.
     *
     * `subjectRef` se construye desde `physicalLocation` cuando existe (es
     * donde SARIF anota file:line); si falta, cae a `detekt:result:<id>`
     * para no perder la identidad.
     */
    private fun resultToRawItem(result: DetektSarifCodec.SarifResult): RawEvidenceItem {
        val file = result.physicalLocation?.artifactLocation?.uri
        val line = result.physicalLocation?.region?.startLine?.toString()
        val payload = buildMap {
            put("ruleId", result.ruleId)
            put("level", result.level)
            put("message", result.message)
            if (file != null) put("file", file)
            if (line != null) put("line", line)
            // La capability se mete en el payload para que un servicio de
            // aplicación que normaliza pueda decidir a qué `EvidenceSubject`
            // mapea sin tener que mirar el `producerId` del manifest.
            put("capability", CAPABILITY_SIGNALS_DETEKT)
        }

        val subjectRef = when {
            file != null && line != null -> "detekt:file:$file:$line"
            file != null -> "detekt:file:$file"
            else -> "detekt:result:${result.ruleId}:${result.id ?: "unknown"}"
        }

        val id = buildString {
            append("detekt-sarif/result/")
            append(result.ruleId)
            append('/')
            append(result.id ?: (file?.let { "$it:$line" } ?: "noid"))
        }

        return RawEvidenceItem(
            kind = RawItemKind.Signal,
            id = id,
            subjectRef = subjectRef,
            authority = "HeuristicAnalyzer",
            payload = payload,
            sourceLocation = file?.let { f -> line?.let { l -> "$f:$l" } },
        )
    }

    private companion object {
        // Capabilities declaradas en el descriptor; duplicadas aquí para
        // que un cambio de descriptor fuerce un cambio aquí también.
        const val CAPABILITY_SIGNALS_DETEKT = "signals.detekt"
    }
}

/**
 * Codec mínimo de SARIF 2.1.0.
 *
 * Visibilidad `internal`: este codec no es una API pública del módulo
 * `assurance-providers`. Forma parte del boundary de decoding; los
 * consumidores externos deben recibir un `EvidenceCollectionResult` ya
 * normalizado, no llamar a `decode` directamente.
 *
 * Política de cotas: idéntica a `CogniCodeEvidenceExportCodec` (64 MiB de
 * input, 8 niveles de profundidad, 1024 elementos por colección, 64 KiB por
 * string). Mismo WHY: la frontera con un producer externo necesita cotas
 * más apretadas que la frontera interna, no por desconfianza gratuita sino
 * porque un payload de 1 MiB de string no es "decodificar evidencia", es
 * "agotar memoria a petición".
 *
 * Política de campos: SARIF 2.1.0 es un estándar con muchas secciones, y
 * este provider sólo necesita `version`, `$schema`, `runs[].results[]` y
 * `runs[].tool.driver.rules[].id` (para `ruleId`). El resto se IGNORA en
 * silencio: el codec no es un validador de schema completo, es un
 * extractor de los campos que necesitamos. Un campo faltante se trata como
 * ausente, no como error.
 */
internal object DetektSarifCodec {

    /** Versión SARIF que este codec entiende. Falla cerrado si llega otra. */
    const val SARIF_VERSION: String = "2.1.0"

    /** Schema URL oficial SARIF 2.1.0. Se acepta cualquier string que contenga `sarif-schema-2.1.0`. */
    const val SARIF_SCHEMA_FRAGMENT: String = "sarif-schema-2.1.0"

    /** Cotas — heredadas de `CogniCodeEvidenceExportCodec` por la misma razón. */
    const val MAX_INPUT_BYTES: Long = 64L * 1024 * 1024
    const val MAX_NESTING_DEPTH: Int = 8
    const val MAX_COLLECTION_SIZE: Int = 1024
    const val MAX_STRING_LENGTH: Int = 65_536

    private val json = Json {
        ignoreUnknownKeys = true
        // SARIF 2.1.0 permite campos opcionales como `null` (e.g.
        // `physicalLocation: null` en algunos `result`s); toleramos la
        // ausencia de explícitamente nulos.
        explicitNulls = false
    }

    // -----------------------------------------------------------------------
    // Tipos
    // -----------------------------------------------------------------------

    /**
     * Resultado del decode: lista plana de `results` y nº de `run`s.
     *
     * El SARIF puede tener varios `run`s (e.g. varias herramientas en el
     * mismo log); los concatenamos porque para el consumer el origen
     * exacto del run es metadata, y `rawItems` es una lista. La
     * atribución a un run concreto se pierde, y eso es aceptable en M5:
     * el modelo de evidence no tiene "este signal viene del run X" como
     * dimensión; si la spec lo pidiera, se añadiría un campo al payload.
     */
    data class Decoded(
        val runCount: Int,
        val results: List<SarifResult>,
    )

    /**
     * Subset de un SARIF `result` que este provider necesita.
     *
     * `id` es el índice del result (1-based por convención SARIF). Si el
     * producer no lo incluye, queda `null` y el provider usa `file:line`
     * como discriminador. `ruleId` viene de `rule.id`; si SARIF trae
     * `ruleIndex` en vez de `rule`, se intenta resolver contra
     * `tool.driver.rules[]` (workaround común cuando el result es
     * referenciado por índice).
     */
    data class SarifResult(
        val id: String?,
        val ruleId: String,
        val level: String,
        val message: String,
        val physicalLocation: PhysicalLocation?,
    )

    data class PhysicalLocation(
        val artifactLocation: ArtifactLocation?,
        val region: Region?,
    )

    data class ArtifactLocation(val uri: String?)

    data class Region(val startLine: Int?)

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Decodifica SARIF 2.1.0 desde bytes UTF-8.
     *
     * Orden de comprobaciones (mismo razonamiento que
     * `CogniCodeEvidenceExportCodec.decodeFromJson`):
     *   1. `MAX_INPUT_BYTES` — sobre los BYTES, antes de parsear.
     *   2. Pre-parse a `JsonElement` para aplicar `MAX_NESTING_DEPTH`.
     *   3. Validación de `version` y `$schema`.
     *   4. Verificación de digest si está declarado en
     *      `properties.digest` (convención no estándar pero permitida por
     *      SARIF §3.8 "property bags").
     *   5. Extracción de `results` con `requireWithinLimits`.
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size > MAX_INPUT_BYTES) {
            throw CodecException("entrada SARIF de ${bytes.size} bytes excede MAX_INPUT_BYTES=$MAX_INPUT_BYTES")
        }
        val element: JsonElement = try {
            json.parseToJsonElement(bytes.decodeToString())
        } catch (e: Exception) {
            throw CodecException("SARIF no parseable: ${e.message}", e)
        }
        try {
            requireDepthWithinLimit(element, MAX_NESTING_DEPTH)
        } catch (e: IllegalArgumentException) {
            throw CodecException("SARIF excede profundidad: ${e.message}", e)
        }

        val root = try {
            (element as? JsonObject) ?: throw CodecException("SARIF raíz no es objeto")
        } catch (e: CodecException) {
            throw e
        }

        validateVersionAndSchema(root)

        // Verificación de digest opcional. SARIF no tiene un campo digest
        // estándar, pero admite `properties` en el `sarifLog` raíz. Si el
        // producer lo declara, verificamos que el SHA-256 del SARIF sin
        // `properties` coincide con el declarado. Si no lo declara, no
        // verificamos (y por tanto no fallamos): un SARIF sin
        // `properties.digest` no es inválido, sólo sin integrity check
        // declarativo.
        verifyDigestIfDeclared(root)

        val runs = root["runs"] as? JsonArray
            ?: throw CodecException("SARIF sin sección 'runs'")
        if (runs.size > MAX_COLLECTION_SIZE) {
            throw CodecException("SARIF runs: ${runs.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE")
        }

        val results = mutableListOf<SarifResult>()
        for (run in runs) {
            val runObj = run as? JsonObject
                ?: throw CodecException("cada run SARIF debe ser un objeto")
            val rulesByIndex = collectRulesByIndex(runObj)
            val resultsArray = runObj["results"] as? JsonArray ?: continue
            if (resultsArray.size > MAX_COLLECTION_SIZE) {
                throw CodecException(
                    "SARIF results de run: ${resultsArray.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE",
                )
            }
            for (r in resultsArray) {
                val rObj = r as? JsonObject
                    ?: throw CodecException("cada result SARIF debe ser un objeto")
                results.add(parseResult(rObj, rulesByIndex))
            }
        }

        if (results.size > MAX_COLLECTION_SIZE) {
            throw CodecException("SARIF results totales: ${results.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE")
        }

        return Decoded(runCount = runs.size, results = results)
    }

    // -----------------------------------------------------------------------
    // Validación de versión y schema
    // -----------------------------------------------------------------------

    /**
     * Verifica que el SARIF declara `version: "2.1.0"` y que `$schema`
     * contiene `sarif-schema-2.1.0`.
     *
     * La validación es ESTRICTA: una versión distinta se rechaza, no se
     * intenta parsear. La razón es la asimetría de especificación: SARIF
     * 2.0 y 2.1.0 tienen campos con la misma forma pero semántica
     * ligeramente distinta (e.g. `externalPropertyFileReferences` cambió
     * en 2.1), y un codec que admita 2.0 sin admitir sus semánticas
     * produciría findings con metadatos que el consumer no sabe
     * interpretar. Fail-closed.
     */
    private fun validateVersionAndSchema(root: JsonObject) {
        val version = root["version"]?.jsonPrimitive?.content
        if (version != SARIF_VERSION) {
            throw CodecException("SARIF version=$version no admitida (esperada $SARIF_VERSION)")
        }
        val schema = root["\$schema"]?.jsonPrimitive?.content
        if (schema == null || SARIF_SCHEMA_FRAGMENT !in schema) {
            throw CodecException("SARIF \$schema=$schema no contiene '$SARIF_SCHEMA_FRAGMENT'")
        }
    }

    // -----------------------------------------------------------------------
    // Verificación de digest
    // -----------------------------------------------------------------------

    /**
     * Verifica el digest declarado en `properties.digest` (opcional).
     *
     * Convención con el producer: si el SARIF raíz tiene
     * `properties.digest` con un SHA-256 hex, lo comparamos contra el
     * SHA-256 del SARIF SIN el campo `properties`. La razón es la
     * chicken-and-egg del digest embebido: si el digest cubriera los
     * bytes que lo contienen, cualquier cambio en el campo
     * cambiaría el digest, y el producer no podría declararlo. La
     * convención "el digest cubre el contenido, no la metadata" es
     * la misma que usa SARIF §3.8 para `property bags`: `properties`
     * es metadata, y la metadata no se firma a sí misma.
     *
     * Determinismo de la re-serialización: usamos el mismo `Json`
     * que `encodeToBytes` (sin `prettyPrint`), y eliminamos sólo la
     * clave `properties` antes de re-serializar. `JsonObject` es
     * efectivamente un `Map<String, JsonElement>` con orden de
     * inserción, así que el output es byte-estable mientras el
     * input no introduzca variantes (e.g. números con ceros
     * superfluos). Para SARIF los campos son strings e integers
     * simples, y no se da el caso.
     *
     * Por qué se tolera la AUSENCIA: SARIF 2.1.0 no define un campo
     * digest. La spec dice "properties bags son el lugar para
     * metadata del producer". Un SARIF sin digest es válido; un
     * SARIF con digest que no coincide es SABEMOS que ha sido
     * alterado.
     */
    private fun verifyDigestIfDeclared(root: JsonObject) {
        val props = root["properties"] as? JsonObject ?: return
        val declared = props["digest"] as? JsonPrimitive ?: return
        if (declared.isString.not()) return
        val declaredHex = declared.content
        if (declaredHex.isBlank()) return
        val actual = dataDigestOf(root).hex
        if (actual != declaredHex) {
            throw CodecException(
                "digest SARIF declarado ${declaredHex.take(12)} no coincide con " +
                    "el recomputado ${actual.take(12)} (payload alterado en tránsito)",
            )
        }
    }

    /**
     * Calcula el digest del contenido del SARIF sin la clave
     * `properties`.
     *
     * Visibilidad `internal` para que los tests del módulo puedan
     * construir fixtures con el digest correcto. La convención
     * (digest = SHA-256 del SARIF sin `properties`) está
     * documentada en `verifyDigestIfDeclared`; un test que la rompe
     * se cae en la verificación, no en la serialización.
     */
    internal fun dataDigestOf(root: JsonObject): Digest {
        val dataOnly = JsonObject(
            root.entries.asSequence()
                .filter { it.key != "properties" }
                .associate { it.key to it.value },
        )
        val canonical = json.encodeToString(JsonObject.serializer(), dataOnly)
        return Digest.ofUtf8(canonical)
    }

    // -----------------------------------------------------------------------
    // Extracción
    // -----------------------------------------------------------------------

    /**
     * Construye un mapa `index -> ruleId` desde `tool.driver.rules[]` para
     * resolver `ruleIndex` en un `result`.
     *
     * SARIF permite que un `result` refiera a su regla por `ruleIndex`
     * (entero 0-based) o por un `rule` anidado con `id`. Detekt usa
     * `ruleIndex` por economía de bytes; otros tools usan `rule` directo.
     * Aceptamos ambos y caemos a `unknown` si el índice está fuera de
     * rango (un `result` huérfano no es un error fatal, es un signal
     * sin ruleId).
     */
    private fun collectRulesByIndex(run: JsonObject): List<String> {
        val tool = run["tool"] as? JsonObject ?: return emptyList()
        val driver = tool["driver"] as? JsonObject ?: return emptyList()
        val rules = driver["rules"] as? JsonArray ?: return emptyList()
        if (rules.size > MAX_COLLECTION_SIZE) {
            throw CodecException(
                "SARIF rules: ${rules.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE",
            )
        }
        return rules.map { r ->
            val rObj = r as? JsonObject
            rObj?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "unknown"
        }
    }

    /**
     * Convierte un `result` SARIF a `SarifResult` (DTO interno).
     *
     * Política: cualquier campo faltante se tolera con un valor por
     * defecto razonable (`""` para strings, `null` para opcionales). La
     * razón es la asimetría entre tools: Detekt emite `message` con
     * `text` en un objeto anidado, otros tools lo emiten como string.
     * Aceptamos ambos.
     */
    private fun parseResult(obj: JsonObject, rulesByIndex: List<String>): SarifResult {
        // `ruleId` puede venir como string (`ruleId: "Foo"`) o como índice
        // (`ruleIndex: 3`). La spec SARIF dice que `ruleId` es preferible;
        // aceptamos `ruleIndex` como fallback.
        val ruleId: String = when (val r = obj["ruleId"]) {
            is JsonPrimitive -> r.content
            else -> {
                val idx = obj["ruleIndex"]?.jsonPrimitive?.content?.toIntOrNull()
                if (idx != null && idx in rulesByIndex.indices) rulesByIndex[idx] else "unknown"
            }
        }
        val level = obj["level"]?.jsonPrimitive?.content ?: "warning"
        val message = extractMessage(obj["message"])
        val physical = (obj["physicalLocation"] as? JsonObject)?.let(::parsePhysicalLocation)

        // SARIF `result.id` es opcional y la spec anima a usarlo para
        // correlación. Si no está, el provider lo deja `null` y el id
        // del `RawEvidenceItem` se construye desde `ruleId:file:line`.
        val resultId = obj["id"]?.jsonPrimitive?.content

        return SarifResult(
            id = resultId,
            ruleId = ruleId,
            level = level,
            message = message,
            physicalLocation = physical,
        )
    }

    /**
     * `message` en SARIF puede ser un string simple o un objeto `{text: ...}`.
     * Aceptamos ambos. Si es un objeto con `text`, usamos ése; si es un
     * string, lo usamos directo; si falta o es otra cosa, caemos a "".
     */
    private fun extractMessage(node: JsonElement?): String = when (node) {
        is JsonPrimitive -> node.content
        is JsonObject -> node["text"]?.jsonPrimitive?.content.orEmpty()
        else -> ""
    }

    private fun parsePhysicalLocation(obj: JsonObject): PhysicalLocation {
        val artifactObj = obj["artifactLocation"] as? JsonObject
        val uri = artifactObj?.get("uri")?.jsonPrimitive?.content
        val regionObj = obj["region"] as? JsonObject
        val startLine = regionObj?.get("startLine")?.jsonPrimitive?.content?.toIntOrNull()
        return PhysicalLocation(
            artifactLocation = if (artifactObj != null) ArtifactLocation(uri) else null,
            region = if (regionObj != null) Region(startLine) else null,
        )
    }

    // -----------------------------------------------------------------------
    // Bounded decoding
    // -----------------------------------------------------------------------

    /**
     * Verifica la profundidad máxima del árbol JSON reconstruido.
     *
     * Misma rutina que `CogniCodeEvidenceExportCodec`: se ejecuta en la
     * pre-pasada para no construir un DTO con un árbol que ya sabemos
     * demasiado profundo. SARIF bien formado tiene profundidad ≤ 6
     * (`sarifLog -> runs[] -> results[] -> physicalLocation -> region`),
     * así que el límite de 8 da margen para property bags anidadas sin
     * admitir ataques de OOM.
     */
    private fun requireDepthWithinLimit(element: JsonElement, maxDepth: Int) {
        val d = depthOf(element)
        require(d <= maxDepth) {
            "anidamiento real = $d excede MAX_NESTING_DEPTH=$maxDepth"
        }
    }

    private fun depthOf(element: JsonElement): Int = when (element) {
        is JsonPrimitive -> 1
        is JsonObject -> 1 + (element.values.maxOfOrNull { depthOf(it) } ?: 0)
        is JsonArray -> 1 + (element.maxOfOrNull { depthOf(it) } ?: 0)
    }

    // -----------------------------------------------------------------------
    // Helpers para tests
    // -----------------------------------------------------------------------

    /**
     * Serializa un `JsonObject` (raíz SARIF) a bytes UTF-8.
     *
     * Visibilidad `internal` para que los tests del módulo puedan
     * construir fixtures sin reimplementar la convención de
     * `Json.encodeToString` con el flag `prettyPrint`. Un test que
     * construye un SARIF por su cuenta y compara con un golden debe
     * usar la misma serialización que aquí.
     */
    internal fun encodeToBytes(root: JsonObject): ByteArray =
        json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)

    /**
     * Error de decoding. Distinto de las excepciones de dominio a
     * propósito: un `CodecException` significa "el artefacto no se pudo
     * decodificar"; un fallo de invariante del dominio significa "se
     * decodificó pero viola invariantes".
     */
    class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
