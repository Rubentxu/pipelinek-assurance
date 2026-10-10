/**
 * M2 — Provider que decodifica un export `assurance-evidence/v1` de CogniCode
 * y lo convierte en `EvidenceCollectionResult` para el motor.
 *
 * Ref autoridad:
 *  - `03-specifications/PROVIDER_SPI.md` — forma del SPI y `descriptor`.
 *  - `07-integrations/COGNICODE_WORKSTREAM.md` `C1`–`C5` — secciones del
 *    wire format y la asimetría heurístico/determinista (C5: signals
 *    heurísticos no se transforman en facts).
 *  - `03-specifications/EVIDENCE_MODEL.md` — autoridades y completitud
 *    permitidas por cada variante de item.
 *  - `04-adrs/ADR-009-PROVIDER-DOES-NOT-GATE.md` — el provider produce
 *    evidencia, NO decide veredicto.
 *
 * Por qué este provider implementa el SPI pero no devuelve `EvidenceSnapshot`
 * directamente: la frontera entre wire externo y modelo de dominio es del
 * *servicio de aplicación*. El SPI entrega `EvidenceCollectionResult.Produced`
 * con `RawEvidenceItem`; la normalización a `EvidenceSnapshot` ocurre arriba.
 * Mezclar las dos capas borraría la frontera entre "bytes externos" y
 * "modelo de dominio" que `ARTIFACT_WIRE_CONTRACTS.md` quiere preservar.
 *
 * Por qué NO usamos coroutines ni filesystem: `EvidenceProvider.collect` es
 * síncrono, y los bytes del export llegan ya leídos. Un provider que abriera
 * el fichero él mismo sería uno que cruza AAT-3 (dependencia de I/O) y
 * AAT-7 (lens/proveedor sin red ni FS).
 *
 * **AAT-6, por construcción:** este provider implementa `EvidenceProvider`,
 * cuyo SPI no expone `AssertionResult`. Si alguien añade `eval(...)` aquí,
 * el check de `M2ProviderSpiLawsTest` lo caza por signatura, no por string.
 */
package dev.pipelinek.assurance.providers.cognicode

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

/**
 * Provider CogniCode sobre el wire format `assurance-evidence/v1`.
 *
 * Identidad fija por construcción: el descriptor es una `val` literal y
 * NO depende del export. La razón es la frontera epistémica: una versión
 * del producer que el motor ya conoce. Que la cambie el export (e.g. un
 * campo `producer.version`) debe pasar por OTRO provider, no por el mismo
 * provider mintiendo sobre sí mismo.
 *
 * Capacidades declaradas:
 *  - `architecture.dependency-graph` (facts + relations)
 *  - `architecture.entities` (entities)
 *  - `architecture.relations` (relations)
 *  - `signals.solid_audit` (signals con `algorithmId` SOLID)
 *
 * Sujetos admitidos: `Module`, `Symbol`, `SourceLocation`.
 *
 * Clasificación: `Deterministic`. CogniCode es un parser estático; la
 * misma revisión sobre la misma entrada produce el mismo export (C0 del
 * workstream). Si en una versión futura CogniCode incluyera trazas runtime,
 * la clasificación cambiaría a `Runtime`; hasta entonces, declarar
 * `Deterministic` permite al motor admitir autoridades `Deterministic*`
 * (AAT-19) sin marcar gaps.
 *
 * Formatos de entrada: ambos media types del wire format. Decidir cuál
 * usa el caller; este provider decodifica lo que le llegue.
 *
 * `outputSchemaVersion = "assurance-evidence/v1"` es la versión del modelo
 * del EXPORT, no del modelo de dominio del motor. Coincide con la del
 * codec por construcción; el motor la propaga al `EvidenceSourceManifest`.
 */
class CogniCodeArtifactProvider(
    private val exportBytes: ByteArray,
) : EvidenceProvider {

    override val descriptor: EvidenceProviderDescriptor = EvidenceProviderDescriptor(
        id = "cognicode",
        version = "0.1.0",
        evidenceCapabilities = listOf(
            Capabilities.ARCHITECTURE_DEPENDENCY_GRAPH,
            Capabilities.ARCHITECTURE_ENTITIES,
            Capabilities.ARCHITECTURE_RELATIONS,
            Capabilities.SIGNALS_SOLID_AUDIT,
        ),
        subjectKinds = listOf("Module", "Symbol", "SourceLocation"),
        classification = ProviderClassification.Deterministic,
        inputFormats = listOf(
            CogniCodeEvidenceExportCodec.MEDIA_TYPE_CBOR,
            CogniCodeEvidenceExportCodec.MEDIA_TYPE_JSON,
        ),
        outputSchemaVersion = CogniCodeEvidenceExportCodec.API_VERSION,
    )

    /**
     * Decodifica el export y emite evidencia cruda.
     *
     * El método es síncrono a propósito: `EvidenceProvider.collect` tiene
     * firma no suspendida. La entrada ya está leída por el caller; no
     * hay I/O en este provider (AAT-7 / SPI).
     *
     * Manejo de errores:
     *  - Si el decode lanza `CodecException`, devolvemos `Failed` con
     *    `ProviderFailureReason.UnsupportedInputFormat` porque es la
     *    causa más probable desde el punto de vista del SPI: el input
     *    que llegó no era reconocible como una evidencia asegurable.
     *    NO devolvemos `Failed` con `CollectionError` aquí porque la
     *    colección de evidencia no empezó; falló antes.
     *  - Otros errores: `CollectionError` con detalle. Lo inesperado
     *    es unexpected, y se reporta.
     *
     * Bounded decoding: ya aplicado por el codec. Este provider NO
     * vuelve a validar longitudes: confiaría en una capa re-llamada no
     * es defensa en profundidad, es coste doble.
     */
    override fun collect(request: EvidenceRequest): EvidenceCollectionResult {
        val dto = try {
            // Detectamos el formato por los primeros bytes: CBOR válido
            // empieza con un byte mayor (≥ 0x40 mayoritariamente); JSON
            // empieza con '{' o '['. Cuando no podemos decidir, probamos
            // CBOR primero porque es el formato canónico (workstream `C1`).
            if (looksLikeJson(exportBytes)) {
                CogniCodeEvidenceExportCodec.decodeFromJson(exportBytes.decodeToString())
            } else {
                CogniCodeEvidenceExportCodec.decodeFromCbor(exportBytes)
            }
        } catch (e: CogniCodeEvidenceExportCodec.CodecException) {
            return EvidenceCollectionResult.Failed(
                producerId = descriptor.id,
                reason = ProviderFailureReason.UnsupportedInputFormat(
                    "el export no cumple ${descriptor.id}/v${descriptor.version}: ${e.message}",
                ),
                gaps = listOf(
                    RawEvidenceGap(
                        capability = descriptor.evidenceCapabilities.first(),
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
                gaps = descriptor.evidenceCapabilities.map {
                    RawEvidenceGap(it, RawGapReason.Unknown, detail = "no se pudo recolectar")
                },
            )
        }

        val rawItems = buildList {
            addAll(dto.entities.map { entityToRawItem(it) })
            addAll(dto.facts.map { factToRawItem(it) })
            addAll(dto.relations.map { relationToRawItem(it) })
            addAll(dto.signals.map { signalToRawItem(it) })
        }

        val declaredGaps = buildDeclaredGaps(dto, rawItems)

        return EvidenceCollectionResult.Produced(
            producerId = descriptor.id,
            producerVersion = descriptor.version,
            schemaVersion = descriptor.outputSchemaVersion,
            rawItems = rawItems,
            declaredGaps = declaredGaps,
        )
    }

    // -----------------------------------------------------------------------
    // Mapeo DTO -> RawEvidenceItem
    // -----------------------------------------------------------------------

    /**
     * Convierte una `EntityDto` en un `RawEvidenceItem.Fact` con predicado
     * sintético `cognicode.entity`. La razón: una entidad no es una
     * afirmación, pero el SPI solo conoce cuatro kinds y `Fact` es el más
     * cercano cuando el "sujeto es una entidad". `Observation` se descartó
     * porque exigiría `RuntimeObserver` y CogniCode es determinista.
     */
    private fun entityToRawItem(entity: EntityDto): RawEvidenceItem = RawEvidenceItem(
        kind = RawItemKind.Fact,
        id = idFor("entity", entity.kind, entity.id),
        subjectRef = subjectRefFor(entity),
        authority = deterministicOrFallback(entity.kind),
        payload = mapOf(
            "predicate" to "cognicode.entity",
            "objectKind" to entity.kind,
            "objectName" to entity.name,
            "objectLayer" to (entity.layer ?: ""),
            "capability" to CAPABILITY_ENTITIES,
        ),
    )

    /**
     * Mapea un `FactDto` directamente.
     *
     * `objectValue` puede ser null (en cuyo caso el `payload["object"]` es
     * la cadena vacía): el `EvidenceItem.Fact.objectValue: String?` lo
     * tolera, y la verdad semántica es "este hecho no tiene objeto".
     */
    private fun factToRawItem(fact: FactDto): RawEvidenceItem = RawEvidenceItem(
        kind = RawItemKind.Fact,
        id = idFor("fact", fact.predicate, fact.id),
        subjectRef = "cognicode:entity:${fact.entityRef}",
        authority = deterministicOrFallback(fact.authority),
        payload = mapOf(
            "predicate" to fact.predicate,
            "object" to (fact.objectValue ?: ""),
            "sourceAnchorRef" to (fact.sourceAnchorRef ?: ""),
            "capability" to capabilityForFact(fact.predicate),
        ),
    )

    /**
     * Mapea una `RelationDto` a un `Fact` con predicado = `RelationDto.kind`.
     *
     * Una relación es estructural (e.g. `depends-on`, `imports`, `calls`),
     * y el `Evidence` (correlation evidence) es el `Fact` que la sostiene.
     * Hasta que el servicio de aplicación construya la `Correlation`, el
     * `payload["evidenceRef"]` la lleva y el patrón
     * `cognicode/relation/{kind}/{from}+{to}` forma el `EvidenceId`.
     */
    private fun relationToRawItem(relation: RelationDto): RawEvidenceItem = RawEvidenceItem(
        kind = RawItemKind.Fact,
        id = idFor("relation", relation.kind, "${relation.from}+${relation.to}"),
        subjectRef = "cognicode:relation:${relation.from}->${relation.to}",
        authority = "DeterministicAnalyzer",
        payload = mapOf(
            "predicate" to relation.kind,
            "from" to relation.from,
            "to" to relation.to,
            "evidenceRef" to relation.evidence,
            "capability" to CAPABILITY_RELATIONS,
        ),
    )

    /**
     * Mapea un `SignalDto` a un `RawEvidenceItem.Signal` con autoridad
     * `HeuristicAnalyzer`.
     *
     * Por qué SIEMPRE `HeuristicAnalyzer` aunque CogniCode ya no
     * clasifique: M-H01 prohíbe un `Signal` con autoridad determinista.
     * Si CogniCode evolucionara a `DeterministicAnalyzer` (e.g. una regla
     * que se vuelve bit-a-bit reproducible), el item deja de ser Signal
     * y se convierte en Fact en una versión futura del provider. Hoy,
     * todo signal es heurístico por invariante del dominio.
     */
    private fun signalToRawItem(signal: SignalDto): RawEvidenceItem = RawEvidenceItem(
        kind = RawItemKind.Signal,
        id = idFor("signal", signal.kind, signal.id),
        subjectRef = "cognicode:entity:${signal.entityRef}",
        authority = "HeuristicAnalyzer",
        payload = mapOf(
            "signalKind" to signal.kind,
            "score" to signal.score,
            "algorithmId" to signal.algorithmId,
            "algorithmVersion" to signal.algorithmVersion,
            "entityRef" to signal.entityRef,
            "thresholds" to signal.thresholds.entries.joinToString("|") { "${it.key}=${it.value}" },
            "capability" to CAPABILITY_SIGNALS,
        ),
    )

    // -----------------------------------------------------------------------
    // Gaps desde capabilityCompleteness
    // -----------------------------------------------------------------------

    /**
     * Deriva gaps del mapa `capabilityCompleteness` Y de la sección
     * raíz `gaps` del export.
     *
     * Reglas por variante:
     *  - `Complete` sin items producidos: NO gap (es honesto decir
     *    "producí cero items completos", p.ej. un grafo vacío real).
     *  - `Partial` sin items producidos: gap `PartialProduced("0/N")`
     *    donde N = nº de gaps declarados en el Partial. Es el caso que
     *    `COGNICODE_WORKSTREAM.md` `C4` enuncia: "lenguaje soportado
     *    parcialmente no devuelve lista vacía como 'sin cycles'".
     *  - `Unknown` y `Unsupported` con o sin items: gap dedicado, SIEMPRE
     *    que la capability esté en `requestedCapabilities` (UAT-024 no
     *    aplica aquí: el provider no sabe qué pidió el caller, así que
     *    emite el gap y deja al servicio de aplicación el filtrado).
     *  - Capabilities declaradas en `gaps` raíz: se emiten tal cual.
     *
     * Duplicados entre las dos fuentes: se deduplican por
     * `(capability, reason)` para que el snapshot no cuente el mismo
     * gap dos veces.
     */
    private fun buildDeclaredGaps(
        dto: CogniCodeEvidenceExportDto,
        rawItems: List<RawEvidenceItem>,
    ): List<RawEvidenceGap> {
        val itemsByCapability = rawItems
            .mapNotNull { it.payload["capability"] }
            .groupingBy { it }
            .eachCount()

        val computed = mutableListOf<RawEvidenceGap>()
        for ((capability, completeness) in dto.capabilityCompleteness) {
            when (completeness) {
                is CapabilityCompletenessDto.CompleteDto -> Unit
                is CapabilityCompletenessDto.PartialDto -> {
                    val produced = itemsByCapability[capability] ?: 0
                    if (produced == 0) {
                        computed.add(
                            RawEvidenceGap(
                                capability = capability,
                                reason = RawGapReason.PartialProduced("0/${completeness.gaps.size}"),
                                detail = "Partial declarado pero sin items producidos",
                            ),
                        )
                    }
                }
                is CapabilityCompletenessDto.UnknownDto -> computed.add(
                    RawEvidenceGap(capability = capability, reason = RawGapReason.Unknown),
                )
                is CapabilityCompletenessDto.UnsupportedDto -> computed.add(
                    RawEvidenceGap(
                        capability = capability,
                        reason = RawGapReason.Unsupported,
                        detail = completeness.reason,
                    ),
                )
            }
        }

        val fromRoot = dto.gaps.map { g ->
            val r = when (g.reason) {
                "Unsupported" -> RawGapReason.Unsupported
                "Unknown" -> RawGapReason.Unknown
                "Lost" -> RawGapReason.Lost
                else -> RawGapReason.PartialProduced(g.reason)
            }
            RawEvidenceGap(capability = g.capability, reason = r, detail = g.detail)
        }

        return (computed + fromRoot).distinctBy { it.capability to it.reason }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Construye un `EvidenceId` válido con el patrón
     * `cognicode/{section}/{kind}/{discriminator}`.
     *
     * La validación de `EvidenceId` exige un prefijo lowercase separado
     * por `/` del resto. Como `cognicode` es lowercase y el resto cae
     * después del `/`, las secciones/kind pueden tener mayúsculas (e.g.
     * `Module`) sin romper la regex del value class.
     *
     * Si el discriminador contiene caracteresproblemáticos para el
     * `EvidenceId` (sabemos: ninguno hoy), podrían necesitar encoding.
     * Por simplicidad y porque el discriminador sale de un producer
     * conocido, dejamos la cadena tal cual.
     */
    private fun idFor(section: String, kind: String, discriminator: String): String =
        "cognicode/$section/$kind/$discriminator"

    /**
     * Devuelve un `subjectRef` razonable a partir de una entidad CogniCode.
     *
     * El `RawEvidenceItem.subjectRef` es texto y el servicio de aplicación
     * decide cómo convertirlo a `EvidenceSubject`. La convención aquí es:
     *  - `kind == "Module"` -> `cognicode:module:{name}`
     *  - `kind == "Symbol"` -> `cognicode:symbol:{name}`
     *  - resto -> `cognicode:entity:{id}` (genérico)
     */
    private fun subjectRefFor(entity: EntityDto): String = when (entity.kind) {
        "Module" -> "cognicode:module:${entity.name}"
        "Symbol" -> "cognicode:symbol:${entity.name}"
        else -> "cognicode:entity:${entity.id}"
    }

    /**
     * Resuelve la autoridad a partir del string de CogniCode, con fallback.
     *
     * Si el producer declara una autoridad no conocida, el decoder ya
     * pasó la cadena sin error; aquí decidimos qué hacer. La política
     * conservadora es degradar a `DeterministicAnalyzer`: CogniCode es
     * determinista por descriptor, así que cualquier autoridad que declare
     * debería caer dentro de `Deterministic*`. Una cadena inesperada se
     * trata como `DeterministicAnalyzer` y se deja al motor el gap si
     * la assertion exige más.
     */
    private fun deterministicOrFallback(rawAuthority: String): String =
        when (rawAuthority) {
            "DeterministicAdapter",
            "DeterministicAnalyzer",
            "RuntimeObserver",
            -> rawAuthority
            else -> "DeterministicAnalyzer"
        }

    /**
     * Heurística de capacidad para un Fact.
     *
     * Mapea el predicado a una de las capabilities del descriptor cuando
     * es posible. Predicados desconocidos caen a
     * `architecture.dependency-graph` porque es la capability genérica
     * para hechos relacionales (imports/calls/depends-on). Cualquier
     * service de aplicación puede refinar.
     */
    private fun capabilityForFact(predicate: String): String =
        when (predicate) {
            "imports", "calls", "depends-on", "extends", "implements" ->
                CAPABILITY_DEPENDENCY_GRAPH
            "owns-module", "contains-symbol" -> CAPABILITY_ENTITIES
            else -> CAPABILITY_DEPENDENCY_GRAPH
        }

    /** ¿Los bytes parecen JSON? Probe barato (primeros bytes no-espacio). */
    private fun looksLikeJson(bytes: ByteArray): Boolean {
        for (b in bytes) {
            if (b.toInt() == 0x20.toInt() || b.toInt() == 0x09.toInt() || b.toInt() == 0x0A.toInt()) continue
            val c = b.toInt().toChar()
            return c == '{' || c == '['
        }
        return false
    }

    private companion object {
        // Capacidades declaradas en el descriptor; referencian
        // `Capabilities` en `assurance-domain` para que un cambio del
        // nombre canónico se haga en un solo sitio (ver M-CAP-DRIFT).
        const val CAPABILITY_DEPENDENCY_GRAPH = Capabilities.ARCHITECTURE_DEPENDENCY_GRAPH
        const val CAPABILITY_ENTITIES = Capabilities.ARCHITECTURE_ENTITIES
        const val CAPABILITY_RELATIONS = Capabilities.ARCHITECTURE_RELATIONS
        const val CAPABILITY_SIGNALS = Capabilities.SIGNALS_SOLID_AUDIT
    }
}
