/**
 * M2 — DTOs del wire format `assurance-evidence/v1` que produce CogniCode.
 *
 * Ref autoridad:
 *  - `07-integrations/COGNICODE_WORKSTREAM.md` — secciones del export (`C1`).
 *  - `03-specifications/ARTIFACT_WIRE_CONTRACTS.md` — codec rules y
 *    bounded decoding.
 *  - `03-specifications/PROVIDER_SPI.md` — qué capabilities puede declarar
 *    un provider de evidence.
 *
 * Por qué DTOs intermedios en vez de `@Serializable` sobre los ADTs del
 * dominio: el codec no deserializa contra `EvidenceSnapshot` directamente
 * (mismo motivo que `EvidenceArtifactCodec`): un DTO intermedio hace que el
 * roundtrip sea comprobable y deja las invariantes del dominio al constructor
 * real, no al decoder.
 *
 * Por qué `internal`: este formato es un boundary externo. Quien lo consuma
 * desde fuera del módulo debe usar otro provider, no reusar el DTO. La
 * visibilidad `internal` (módulo Gradle) hace explícita esa frontera.
 */
package dev.pipelinek.assurance.providers.cognicode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope de export de CogniCode, conforme a `assurance-evidence/v1`.
 *
 * Decisión de scope (no en los tests): este es el SHAPE que CogniCode
 * produce. Es un superset del M0 `EvidenceArtifact`: el M0 es el snapshot
 * de evidence ya normalizado; éste es el artefacto intermedio que CogniCode
 * emite antes de que el servicio de aplicación lo proyecte a items de
 * evidence vía `CogniCodeArtifactProvider`. Las dos formas no compiten: una
 * es entrada del otro.
 *
 * El campo `digest` es OBLIGATORIO. `ARTIFACT_WIRE_CONTRACTS.md §Family 1`
 * falla cerrado si falta; la asimetría ausente/inconsistente es lo que la
 * propiedad "fail-closed" exige no admitir.
 */
@Serializable
internal data class CogniCodeEvidenceExportDto(
    val apiVersion: String,
    val kind: String,
    val producer: ProducerInfoDto,
    val subject: SubjectRefDto,
    val manifest: ManifestSectionDto,
    val entities: List<EntityDto>,
    val facts: List<FactDto>,
    val relations: List<RelationDto>,
    val signals: List<SignalDto>,
    val sourceAnchors: List<SourceAnchorDto>,
    val provenance: ProvenanceDto,
    val capabilityCompleteness: Map<String, CapabilityCompletenessDto>,
    val gaps: List<CapabilityGapDto>,
    val digest: String,
) {
    init {
        // Fail-closed de schema: una versión desconocida no se adivina.
        require(apiVersion == CogniCodeEvidenceExportCodec.API_VERSION) {
            "apiVersion desconocida: $apiVersion (esperada ${CogniCodeEvidenceExportCodec.API_VERSION})"
        }
        require(kind == "EvidenceExport") {
            "kind inesperado: $kind (esperado EvidenceExport)"
        }
    }
}

/**
 * Identidad del producer CogniCode.
 *
 * `id`/`version` se replican aquí y en `manifest` a propósito: producer y
 * manifest viven en secciones distintas del envelope y el motor puede
 * necesitarlas por separado al normalizar. Duplicar el dato y dejarlo
 * divergir es peor que duplicarlo y validarlo.
 */
@Serializable
internal data class ProducerInfoDto(
    val id: String,
    val version: String,
    val schemaVersion: String,
) {
    init {
        require(id.isNotBlank()) { "ProducerInfo.id no puede estar en blanco" }
        require(version.isNotBlank()) { "ProducerInfo.version no puede estar en blanco" }
        require(schemaVersion.isNotBlank()) { "ProducerInfo.schemaVersion no puede estar en blanco" }
    }
}

/**
 * Revisión y tipo del sujeto sobre el que CogniCode produjo la evidencia.
 *
 * `revision` es lo que UAT-024 exige propagar al `EvidenceSnapshot` para
 * que el mismo `revision` produzca el mismo digest.
 */
@Serializable
internal data class SubjectRefDto(
    val revision: String,
    val kind: String,
) {
    init {
        require(revision.isNotBlank()) { "SubjectRef.revision no puede estar en blanco" }
        require(kind.isNotBlank()) { "SubjectRef.kind no puede estar en blanco" }
    }
}

/**
 * Sección `manifest` del envelope CogniCode.
 *
 * Por qué `digest` aparece también aquí y en el envelope raíz: el envelope
 * raíz firma el contenido COMPLETO (incluye `manifest`); el `manifest.digest`
 * es el del MANIFEST considerado aisladamente, útil para auditorías
 * específicas del manifest. Misma forma, contenido distinto. El decoder
 * verifica el del envelope raíz; el `manifest.digest` es informativo.
 */
@Serializable
internal data class ManifestSectionDto(
    val requestedCapabilities: List<String>,
    val producedCapabilities: List<String>,
    val completenessByCapability: Map<String, CapabilityCompletenessDto>,
    val schemaVersion: String,
    val digest: String,
) {
    init {
        require(schemaVersion.isNotBlank()) { "ManifestSection.schemaVersion no puede estar en blanco" }
        // No validamos `digest` aquí: el codec puede tolerar longitudes
        // arbitrarias para exponer el tamaño real, y el bounded decoding
        // tiene su propia capa (no la del init).
    }
}

/**
 * Entidad estática de CogniCode (módulo, símbolo, etc.).
 *
 * `layer` es opcional: no toda entidad tiene capa arquitectónica
 * (e.g. un símbolo hoja). Cuando falta, se omite del payload y del
 * `EvidenceSubject` resultante.
 */
@Serializable
internal data class EntityDto(
    val id: String,
    val kind: String,
    val name: String,
    val layer: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Entity.id no puede estar en blanco" }
        require(kind.isNotBlank()) { "Entity.kind no puede estar en blanco" }
        require(name.isNotBlank()) { "Entity.name no puede estar en blanco" }
    }
}

/**
 * Hecho determinista `subject-predicate-object` que CogniCode ya tenía.
 *
 * `authority` viaja como string para aceptar autoridades nuevas sin
 * romper el decoder; el codec la mapea a `EvidenceAuthority` por nombre.
 * Si la autoridad no casa, el `RawEvidenceItem.authority` que el provider
 * produce cae a `DeterministicAnalyzer` y el gap se declara en la
 * siguiente auditoría (no en el decoder).
 */
@Serializable
internal data class FactDto(
    val id: String,
    val entityRef: String,
    val predicate: String,
    val objectValue: String?,
    val authority: String,
    val sourceAnchorRef: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Fact.id no puede estar en blanco" }
        require(entityRef.isNotBlank()) { "Fact.entityRef no puede estar en blanco" }
        require(predicate.isNotBlank()) { "Fact.predicate no puede estar en blanco" }
        require(authority.isNotBlank()) { "Fact.authority no puede estar en blanco" }
    }
}

/**
 * Relación tipada entre dos entidades CogniCode.
 *
 * El campo `evidence` es el id del `Fact` que la sostiene (UAT-001 /
 * `Correlation.evidence`). Sin `evidence`, la relación es opaca: no se
 * sabe qué la motivó.
 */
@Serializable
internal data class RelationDto(
    val from: String,
    val to: String,
    val kind: String,
    val evidence: String,
) {
    init {
        require(from.isNotBlank()) { "Relation.from no puede estar en blanco" }
        require(to.isNotBlank()) { "Relation.to no puede estar en blanco" }
        require(kind.isNotBlank()) { "Relation.kind no puede estar en blanco" }
        require(evidence.isNotBlank()) { "Relation.evidence no puede estar en blanco" }
    }
}

/**
 * Señal heurística exportada por CogniCode.
 *
 * Por qué `score` es `String`: el score puede ser numérico, categórico o
 * textual, según la heurística (e.g. SOLID = "yes"/"partial"/"no";
 * god-function = "0.42"). El tipo de dominio es `String`, no `Double`,
 * y la frontera entre producer y consumer lo respeta.
 *
 * `thresholds` es `Map<String,String>` para conservar la misma forma que
 * `EvidenceItem.Signal.thresholds`: las claves son config del algoritmo,
 * los valores son las cotas que cada heurística eligió.
 */
@Serializable
internal data class SignalDto(
    val id: String,
    val entityRef: String,
    val kind: String,
    val score: String,
    val algorithmId: String,
    val algorithmVersion: String,
    val thresholds: Map<String, String>,
) {
    init {
        require(id.isNotBlank()) { "Signal.id no puede estar en blanco" }
        require(entityRef.isNotBlank()) { "Signal.entityRef no puede estar en blanco" }
        require(kind.isNotBlank()) { "Signal.kind no puede estar en blanco" }
        require(score.isNotBlank()) { "Signal.score no puede estar en blanco" }
        require(algorithmId.isNotBlank()) { "Signal.algorithmId no puede estar en blanco" }
        require(algorithmVersion.isNotBlank()) { "Signal.algorithmVersion no puede estar en blanco" }
    }
}

/**
 * Localización fuente de un `Fact` o `Signal`.
 *
 * `column` y `symbolRef` son opcionales: los lenguajes sin noción de
 * columna (algunos formateadores) o sin símbolo atómico los omiten.
 */
@Serializable
internal data class SourceAnchorDto(
    val file: String,
    val line: Int,
    val column: Int? = null,
    val symbolRef: String? = null,
) {
    init {
        require(file.isNotBlank()) { "SourceAnchor.file no puede estar en blanco" }
        require(line >= 0) { "SourceAnchor.line no puede ser negativo: $line" }
    }
}

/**
 * Procedencia de un item exportado por CogniCode.
 *
 * Trae campos ortogonales al envelope: el producer, la revision, el
 * artefacto del que se leyó. El provider los propaga al `Provenance`
 * del item. Si el `artifactDigest` no casa con el digest del envelope,
 * el caller lo sabrá por comparación, no por re-decodificación.
 */
@Serializable
internal data class ProvenanceDto(
    val producerId: String,
    val producerVersion: String,
    val subjectRevision: String,
    val capability: String,
    val artifactRef: String? = null,
    val artifactDigest: String? = null,
) {
    init {
        require(producerId.isNotBlank()) { "Provenance.producerId no puede estar en blanco" }
        require(producerVersion.isNotBlank()) { "Provenance.producerVersion no puede estar en blanco" }
        require(subjectRevision.isNotBlank()) { "Provenance.subjectRevision no puede estar en blanco" }
        require(capability.isNotBlank()) { "Provenance.capability no puede estar en blanco" }
    }
}

/**
 * Completitud declarada por CogniCode para una capability.
 *
 * Sealed interface con `@SerialName` en snake_case por la convención del
 * proyecto: cualquier `@SerialName` que aparezca debe estar en snake_case
 * (`No @SerialName keys that aren't in snake_case in the JSON`).
 *
 * Por qué `Partial` lleva `gaps` y `Unsupported` lleva `reason`: la
 * asimetría es la misma que `Completeness` en el dominio: la parcialidad
 * se narra, lo no soportado se justifica. `Complete` y `Unknown` no
 * llevan payload porque la respuesta es el tipo mismo.
 */
@Serializable
internal sealed interface CapabilityCompletenessDto {
    @Serializable
    @SerialName("complete")
    data object CompleteDto : CapabilityCompletenessDto

    @Serializable
    @SerialName("partial")
    data class PartialDto(
        val gaps: List<CapabilityGapDto>,
    ) : CapabilityCompletenessDto {
        init {
            require(gaps.isNotEmpty()) {
                "Partial expone al menos un gap, o deja de ser Partial"
            }
        }
    }

    @Serializable
    @SerialName("unknown")
    data object UnknownDto : CapabilityCompletenessDto

    @Serializable
    @SerialName("unsupported")
    data class UnsupportedDto(
        val reason: String,
    ) : CapabilityCompletenessDto {
        init {
            require(reason.isNotBlank()) {
                "Unsupported.reason no puede estar en blanco: declare por qué"
            }
        }
    }
}

/**
 * Gap de capability.
 *
 * El campo `reason` es string para tolerar razones que CogniCode pueda
 * añadir sin breaking change. El provider lo parsea contra
 * `EvidenceGap.GapReason`; lo desconocido cae a `PartialProduced("<raw>")`,
 * que es lo más honesto: no afirma lo que no entiende, lo expone.
 */
@Serializable
internal data class CapabilityGapDto(
    val capability: String,
    val reason: String,
    val detail: String? = null,
) {
    init {
        require(capability.isNotBlank()) { "Gap.capability no puede estar en blanco" }
        require(reason.isNotBlank()) { "Gap.reason no puede estar en blanco" }
    }
}
