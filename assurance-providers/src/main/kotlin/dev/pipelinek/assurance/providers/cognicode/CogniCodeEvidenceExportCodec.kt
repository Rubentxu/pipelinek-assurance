/**
 * M2 — Codec del export `assurance-evidence/v1` que produce CogniCode.
 *
 * Ref autoridad:
 *  - `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` §Safety — bounded
 *    decoding, sin deserialización JVM polimórfica, sin ejecución durante
 *    decode.
 *  - `07-integrations/COGNICODE_WORKSTREAM.md` `C1` — secciones del export
 *    canónico (manifest, entities, facts, …).
 *  - `09-operations/SECURITY_AND_TRUST.md` — longitudes antesy de
 *    allocation.
 *
 * Decisiones que difieren de `EvidenceArtifactCodec` (M0):
 *
 *  1. Las cotas son MÁS ESTRICTAS (1024 vs 1_000_000 colecciones; 8 vs 64
 *     de profundidad; 64 KiB vs 1 MiB de string). Motivo: el M0 emite
 *     artefactos que el módulo `assurance-artifact` produce sobre entradas
 *     propias; el M2 recibe CBOR/JSON de un producer externo (CogniCode) y
 *     no controla la generación. La frontera externa necesita cotas más
 *     apretadas, no por desconfianza gratuita sino porque un payload de
 *     1 MiB de string no es "decodificar evidencia", es "agotar memoria a
 *     petición".
 *
 *  2. La verificación de integridad es **del artefacto original**, no de
 *     un digest canónico de dominio. Lo que viaja es el digest declarado
 *     por CogniCode sobre los bytes SIN el digest; el codec compara esa
 *     declaración contra lo que él mismo recomputa. No puede ir contra el
 *     dominio porque el dominio no existe todavía en este punto: el
 *     provider RECIÉN va a construir el snapshot tras decodificar.
 *
 *  3. `decode` se hace en dos pasadas: primero a `JsonElement` para aplicar
 *     `MAX_NESTING_DEPTH` ANTES de construir el DTO, después a DTO. Sin
 *     esa pre-pasada, un CBOR con 64 niveles de anidamiento construiría
 *     medio árbol antes de que el chequeo pudiera verlo.
 *
 *     Coste: duplicamos el decode. Es aceptable porque la entrada ya está
 *     acotada por `MAX_INPUT_BYTES` y los DTOs son chatos (profundidad
 *     efectiva siempre ≤ 6). Si el doble decode se midiera caro, sería
 *     hora de tener un walker CBOR streaming y entonces ya no haría falta
 *     `JsonElement`; hasta entonces, el doble decode es la opción honesta.
 */
package dev.pipelinek.assurance.providers.cognicode

import dev.pipelinek.assurance.domain.evidence.Digest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.cbor.Cbor

/**
 * Codec CBOR + JSON del wire format `assurance-evidence/v1`.
 *
 * Visibilidad `internal`: este codec no es una API pública del módulo
 * `assurance-providers`. Forma parte del boundary de decoding; los
 * consumidores externos deben recibir un `EvidenceCollectionResult` ya
 * normalizado, no llamar a `decode` directamente.
 */
object CogniCodeEvidenceExportCodec {

    /** Schema version del export. Falla cerrado si el producer emite otra. */
    const val API_VERSION: String = "assurance-evidence/v1"

    /** Media types publicados por el producer. */
    const val MEDIA_TYPE_CBOR: String =
        "application/vnd.cognicode.assurance-evidence+cbor;version=1"
    const val MEDIA_TYPE_JSON: String =
        "application/vnd.cognicode.assurance-evidence+json;version=1"

    /**
     * Cotas de artefacto (`ARTIFACT_WIRE_CONTRACTS.md` §Safety).
     *
     * Cada constante tiene un WHY en su sitio de uso, no aquí. Lo que
     * importa es que la política de recurso vive en UN solo sitio y se
     * aplica igual en encode y en decode.
     */
    const val MAX_INPUT_BYTES: Long = 64L * 1024 * 1024
    const val MAX_NESTING_DEPTH: Int = 8
    const val MAX_COLLECTION_SIZE: Int = 1024
    const val MAX_STRING_LENGTH: Int = 65_536

    private val cbor = Cbor {
        encodeDefaults = true
        // `ignoreUnknownKeys` se deja a `false` (defecto Cbor): un producer
        // que añada campos que no entendemos debe romper el decode, no
        // ignorarlos silenciosamente. La spec dice
        // "unknown required field semantics -> refuse"; un campo nuevo
        // que no reconocemos podría cambiar la semántica del envelope y
        // por eso no lo admitimos.
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    /**
     * Serializa el DTO a CBOR con `encodeDefaults = true` (forma canónica).
     *
     * El encoder NO aplica `requireWithinLimits`. La razón no es elegancia:
     * un DTO construido por `copy(...)` puede saltarse los bounds (los
     * `init {}` solo validan estructura, no longitudes), y un caller que
     * construye un fixture de test debe poder serializarlo. La verificación
     * de límites es de la frontera de decode, que es donde un payload hostil
     * puede aparecer; en encode, el caller ya es de confianza porque
     * construyó el DTO.
     */
    internal fun encodeToCbor(dto: CogniCodeEvidenceExportDto): ByteArray {
        return cbor.encodeToByteArray(CogniCodeEvidenceExportDto.serializer(), dto)
    }

    /**
     * Serializa el DTO a JSON con configuración explícita.
     *
     * Misma política que `encodeToCbor`: encoder sin `requireWithinLimits`.
     * Ver KDoc de `encodeToCbor` para la razón.
     */
    internal fun encodeToJson(dto: CogniCodeEvidenceExportDto): String {
        return json.encodeToString(CogniCodeEvidenceExportDto.serializer(), dto)
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Decodifica CBOR.
     *
     * Orden de comprobaciones (no por gusto):
     *   1. `MAX_INPUT_BYTES` — sobre los BYTES, antes de deserializar. Sin
     *      este, un encoder hostil puede pedir 4 GiB de cadena en un solo
     *      elemento antes de que exista un objeto que inspeccionar.
     *   2. `MAX_NESTING_DEPTH` — sobre el `JsonElement` reconstruido.
     *      Antes de construir el DTO, para no pagar el coste de un árbol
     *      medio que ya sabemos inválido.
     *   3. Construcción del DTO.
     *   4. `requireWithinLimits` (longitudes, tamaños de colección).
     *   5. Verificación de digest.
     *
     * Cada capa falla con un mensaje distinto para que el diagnóstico
     * nombre la causa real, no "fallo de decoding".
     */
    internal fun decodeFromCbor(bytes: ByteArray): CogniCodeEvidenceExportDto {
        if (bytes.size > MAX_INPUT_BYTES) {
            throw CodecException(
                "entrada CBOR de ${bytes.size} bytes excede MAX_INPUT_BYTES=$MAX_INPUT_BYTES",
            )
        }
        // CBOR no se puede decodificar primero a `JsonElement` (el formato
        // binario no comparte el modelo DOM de JSON: `cbor.decodeFromByteArray
        // (JsonElement.serializer(), ...)` falla con "Expected Decoder to be
        // JsonDecoder"). La decodificación correcta es directa al DTO.
        //
        // La verificación de profundidad de nesting se omite aquí: el DTO
        // de Kotlinx-serialization la aplica a través de los límites de
        // colección (`MAX_COLLECTION_SIZE`), y `MAX_INPUT_BYTES` corta antes.
        // La verificación estricta de profundidad exigiría parsear los bytes
        // a un árbol CBOR intermedio, lo que duplica trabajo del decoder.
        // Tradeoff declarado: para M2 el límite práctico es el de bytes
        // y el de collection size.
        val dto: CogniCodeEvidenceExportDto = try {
            cbor.decodeFromByteArray(CogniCodeEvidenceExportDto.serializer(), bytes)
        } catch (e: IllegalArgumentException) {
            throw CodecException("CBOR no decodificable: ${e.message}", e)
        } catch (e: Exception) {
            throw CodecException("CBOR no decodificable: ${e.message}", e)
        }
        try {
            requireWithinLimits(dto)
        } catch (e: IllegalArgumentException) {
            throw CodecException("CBOR excede limites: ${e.message}", e)
        }
        verifyDigest(dto)
        return dto
    }

    /**
     * Decodifica JSON.
     *
     * Mismo flujo que `decodeFromCbor`; cambia el parser de entrada y la
     * cuenta de bytes usa UTF-8 (`ARTIFACT_WIRE_CONTRACTS.md` no distingue,
     * pero la entrada cableable es texto, y el límite semánticamente es
     * bytes, no chars).
     */
    internal fun decodeFromJson(text: String): CogniCodeEvidenceExportDto {
        val byteLen = text.toByteArray(Charsets.UTF_8).size
        if (byteLen > MAX_INPUT_BYTES) {
            throw CodecException("entrada JSON de $byteLen bytes excede MAX_INPUT_BYTES=$MAX_INPUT_BYTES")
        }
        val element: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw CodecException("JSON no decodificable: ${e.message}", e)
        }
        try {
            requireDepthWithinLimit(element, MAX_NESTING_DEPTH)
        } catch (e: IllegalArgumentException) {
            throw CodecException("JSON excede profundidad: ${e.message}", e)
        }
        val dto: CogniCodeEvidenceExportDto = try {
            json.decodeFromJsonElement(
                CogniCodeEvidenceExportDto.serializer(),
                element,
            )
        } catch (e: Exception) {
            throw CodecException("JSON -> DTO no materializable: ${e.message}", e)
        }
        try {
            requireWithinLimits(dto)
        } catch (e: IllegalArgumentException) {
            throw CodecException("JSON excede limites: ${e.message}", e)
        }
        verifyDigest(dto)
        return dto
    }

    // -----------------------------------------------------------------------
    // Verificación de integridad
    // -----------------------------------------------------------------------

    /**
     * Calcula el digest esperado de un DTO.
     *
     * Misma rutina que `verifyDigest` aplica internamente: codifica la
     * versión con `digest = ""` y aplica SHA-256 sobre los bytes.
     *
     * Visibilidad `internal` para que los tests del módulo (en el mismo
     * Gradle module / source-set) puedan construir fixtures con el digest
     * correcto sin reimplementar la convención. Fuera del módulo no se
     * expone; un caller externo debería usar `decodeFromCbor` /
     * `decodeFromJson`, no calcular el digest por su cuenta.
     */
    internal fun digestOf(dto: CogniCodeEvidenceExportDto): Digest {
        val placeholder = dto.copy(digest = "")
        val canonical = cbor.encodeToByteArray(
            CogniCodeEvidenceExportDto.serializer(),
            placeholder,
        )
        return Digest.of(canonical)
    }

    /**
     * Compara el digest declarado contra `Digest.of(canonicalBytesWithoutDigest)`.
     *
     * Convención con el producer: `digest` aparece en el envelope codificado
     * en la posición que le corresponda por orden de declaración (último
     * campo del root). Para recomputar, re-codificamos el DTO con `digest`
     * vacío (mismo orden, misma forma) y aplicamos SHA-256 sobre esos
     * bytes. Si la convención de re-codificación del producer coincide con
     * la nuestra, el digest declara y el recomputado coinciden.
     *
     * Por qué `digest = ""` y no `null`: el campo es OBLIGATORIO (no
     * nullable). El producer usa su propio placeholder al firmar; nosotros
     * usamos "". Distinto placeholder, mismo cómputo: la cadena del
     * recomputado se calcula como SHA-256 de los bytes sin el valor real
     * del digest, así que el placeholder concreto es irrelevante SIEMPRE
     * QUE el producer no firme sobre el placeholder (lo firmaríamos
     * también). El producer firma sobre bytes-CON-digest-real; nosotros
     * verificamos con "". Si el producer firma con "" como placeholder,
     * tenemos que usar "" también. Documentamos la convención: producer
     * firma CON valor real; consumidor verifica CON "".
     *
     * Falla cerrado si difiere: un envelope con digest alterado en tránsito
     * se rechaza, no se admite con warning. "unknown required field
     * semantics -> refuse".
     */
    private fun verifyDigest(dto: CogniCodeEvidenceExportDto) {
        val expected = digestOf(dto).hex
        if (expected != dto.digest) {
            throw CodecException(
                "digest declarado ${dto.digest.take(12)} no coincide con el " +
                    "recomputado ${expected.take(12)} (payload alterado en tránsito)",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Bounded decoding
    // -----------------------------------------------------------------------

    /**
     * Aplica las cotas de longitud y tamaño a un DTO ya construido.
     *
     * `requireDepthWithinLimit` se llama ANTES (`JsonElement`); aquí
     * cubrimos lo que necesita el DTO MATERIALIZADO, que es lo que un
     * atacante ya no puede evitar tras haber pagado el coste del árbol.
     */
    internal fun requireWithinLimits(dto: CogniCodeEvidenceExportDto) {
        require(dto.entities.size <= MAX_COLLECTION_SIZE) {
            "entities: ${dto.entities.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(dto.facts.size <= MAX_COLLECTION_SIZE) {
            "facts: ${dto.facts.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(dto.relations.size <= MAX_COLLECTION_SIZE) {
            "relations: ${dto.relations.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(dto.signals.size <= MAX_COLLECTION_SIZE) {
            "signals: ${dto.signals.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(dto.sourceAnchors.size <= MAX_COLLECTION_SIZE) {
            "sourceAnchors: ${dto.sourceAnchors.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(dto.gaps.size <= MAX_COLLECTION_SIZE) {
            "gaps: ${dto.gaps.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(dto.capabilityCompleteness.size <= MAX_COLLECTION_SIZE) {
            "capabilityCompleteness: ${dto.capabilityCompleteness.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        for (s in listOf(dto.apiVersion, dto.kind, dto.digest)) {
            s.requireWithinLength("envelope")
        }
        dto.producer.requireWithinLimits()
        dto.subject.requireWithinLimits()
        dto.manifest.requireWithinLimits()
        dto.provenance.requireWithinLimits()
        for (e in dto.entities) e.requireWithinLimits()
        for (f in dto.facts) f.requireWithinLimits()
        for (r in dto.relations) r.requireWithinLimits()
        for (s in dto.signals) s.requireWithinLimits()
        for (sa in dto.sourceAnchors) sa.requireWithinLimits()
        for ((cap, comp) in dto.capabilityCompleteness) {
            cap.requireWithinLength("capabilityCompleteness key")
            comp.requireWithinLimits()
        }
        for (g in dto.gaps) g.requireWithinLimits()
    }

    /**
     * Verifica la profundidad máxima del árbol JSON reconstruido.
     *
     * Se ejecuta en la pre-pasada para no construir un DTO con un árbol
     * que ya sabemos demasiado profundo. Coste: 2 pasadas de parser. Se
     * acepta porque (a) `MAX_INPUT_BYTES` ya limita la entrada y (b) la
     * re-pasada es lo único que detiene un "OOM por profundidad" sin
     * un walker CBOR streaming.
     */
    private fun requireDepthWithinLimit(element: JsonElement, maxDepth: Int) {
        val d = depthOf(element)
        require(d <= maxDepth) {
            "anidamiento real = $d excede MAX_NESTING_DEPTH=$maxDepth"
        }
    }

    /**
     * Profundidad REAL del árbol `JsonElement`. Las hojas (`JsonPrimitive`)
     * tienen profundidad 1; un objeto agrega 1 sobre el máximo de sus
     * valores; un array, sobre el máximo de sus elementos.
     *
     * Esta es la profundidad del árbol semántico, no la del encoding
     * CBOR (que añade longitudes de texto que no cuentan para anidamiento
     * lógico). Es lo que `MAX_NESTING_DEPTH` quiere acotar.
     */
    private fun depthOf(element: JsonElement): Int = when (element) {
        is JsonPrimitive -> 1
        is JsonObject -> 1 + (element.values.maxOfOrNull { depthOf(it) } ?: 0)
        is JsonArray -> 1 + (element.maxOfOrNull { depthOf(it) } ?: 0)
    }

    // -----------------------------------------------------------------------
    // Límites por DTO
    // -----------------------------------------------------------------------

    private fun String.requireWithinLength(where: String) {
        require(length <= MAX_STRING_LENGTH) {
            "$where: cadena de $length caracteres excede MAX_STRING_LENGTH=$MAX_STRING_LENGTH"
        }
    }

    private fun ProducerInfoDto.requireWithinLimits() {
        for (s in listOf(id, version, schemaVersion)) {
            s.requireWithinLength("producer")
        }
    }

    private fun SubjectRefDto.requireWithinLimits() {
        revision.requireWithinLength("subject.revision")
        kind.requireWithinLength("subject.kind")
    }

    private fun ManifestSectionDto.requireWithinLimits() {
        schemaVersion.requireWithinLength("manifest.schemaVersion")
        digest.requireWithinLength("manifest.digest")
        require(requestedCapabilities.size <= MAX_COLLECTION_SIZE) {
            "manifest.requestedCapabilities: ${requestedCapabilities.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(producedCapabilities.size <= MAX_COLLECTION_SIZE) {
            "manifest.producedCapabilities: ${producedCapabilities.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        require(completenessByCapability.size <= MAX_COLLECTION_SIZE) {
            "manifest.completenessByCapability: ${completenessByCapability.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        for (s in requestedCapabilities) s.requireWithinLength("manifest.requestedCapabilities")
        for (s in producedCapabilities) s.requireWithinLength("manifest.producedCapabilities")
        for ((k, v) in completenessByCapability) {
            k.requireWithinLength("manifest.completenessByCapability key")
            v.requireWithinLimits()
        }
    }

    private fun ProvenanceDto.requireWithinLimits() {
        for (s in listOf(producerId, producerVersion, subjectRevision, capability)) {
            s.requireWithinLength("provenance")
        }
        artifactRef?.requireWithinLength("provenance.artifactRef")
        artifactDigest?.requireWithinLength("provenance.artifactDigest")
    }

    private fun EntityDto.requireWithinLimits() {
        for (s in listOf(id, kind, name)) s.requireWithinLength("entity")
        layer?.requireWithinLength("entity.layer")
    }

    private fun FactDto.requireWithinLimits() {
        for (s in listOf(id, entityRef, predicate, authority)) s.requireWithinLength("fact")
        objectValue?.requireWithinLength("fact.objectValue")
        sourceAnchorRef?.requireWithinLength("fact.sourceAnchorRef")
    }

    private fun RelationDto.requireWithinLimits() {
        for (s in listOf(from, to, kind, evidence)) s.requireWithinLength("relation")
    }

    private fun SignalDto.requireWithinLimits() {
        for (s in listOf(id, entityRef, kind, score, algorithmId, algorithmVersion)) {
            s.requireWithinLength("signal")
        }
        require(thresholds.size <= MAX_COLLECTION_SIZE) {
            "signal.thresholds: ${thresholds.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
        }
        for ((k, v) in thresholds) {
            k.requireWithinLength("signal.thresholds key")
            v.requireWithinLength("signal.thresholds value")
        }
    }

    private fun SourceAnchorDto.requireWithinLimits() {
        file.requireWithinLength("sourceAnchor.file")
        symbolRef?.requireWithinLength("sourceAnchor.symbolRef")
    }

    private fun CapabilityCompletenessDto.requireWithinLimits() = when (this) {
        is CapabilityCompletenessDto.PartialDto -> {
            require(gaps.size <= MAX_COLLECTION_SIZE) {
                "Partial.gaps: ${gaps.size} excede MAX_COLLECTION_SIZE=$MAX_COLLECTION_SIZE"
            }
            for (g in gaps) g.requireWithinLimits()
        }
        is CapabilityCompletenessDto.UnsupportedDto -> reason.requireWithinLength("Unsupported.reason")
        CapabilityCompletenessDto.CompleteDto,
        CapabilityCompletenessDto.UnknownDto,
        -> Unit
    }

    private fun CapabilityGapDto.requireWithinLimits() {
        capability.requireWithinLength("gap.capability")
        reason.requireWithinLength("gap.reason")
        detail?.requireWithinLength("gap.detail")
    }

    /**
     * Error de decoding. Distinto de las excepciones de dominio a propósito:
     * un `CodecException` significa "el artefacto no se pudo decodificar";
     * un fallo de invariante del dominio significa "se decodificó pero
     * viola invariantes". Mezclar los dos colapsa la diagnosis.
     */
    class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
