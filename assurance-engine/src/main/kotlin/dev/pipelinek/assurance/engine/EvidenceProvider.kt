/**
 * M2 — SPI de productor de evidencia.
 *
 * Ref autoridad: `03-specifications/PROVIDER_SPI.md` (firma y shape del SPI),
 * `04-adrs/ADR-009-PROVIDER-DOES-NOT-GATE.md` (provider no decide el veredicto).
 *
 * Por qué interface y no fun interface: la spec requiere un `descriptor`
 * obligatorio. Una `fun interface` puede declararse como lambda, lo que
 * borraría la identidad del provider — y la identidad es lo que el
 * `EvidenceSourceManifest` registra. Un provider que se declare como lambda
 * y luego no pueda decir su `producerId` no es un provider, es un
 * `EvidenceSnapshot` con tres líneas de más.
 *
 * **AAT-6, por construcción:** este SPI no expone `AssertionResult`. Un
 * método que devolviera veredicto no compilaría. La ley que AAT-6 enuncia se
 * cumple por la forma del interface, no por convención.
 *
 * **AAT-2, observado:** este archivo vive en `assurance-engine` y NO
 * depende de `assurance-artifact`, fs, red ni coroutines. Es un contrato, no
 * una implementación. Las IMPLEMENTACIONES viven en módulos que pueden tener
 * dependencias (e.g. `assure-cli/providers/cognicode/` para CogniCode, JUnit,
 * SARIF), y NUNCA en `assurance-engine` ni `assurance-domain`. Eso preserva
 * la frontera: el engine conoce el contrato, no las concreciones.
 *
 * **Frontera con el core (PROVIDER_SPI.md):** el core NO invoca el SPI del
 * provider directamente. Un servicio de aplicación (en `assure-cli/` o en el
 * futuro `pipelinek-assurance-plugin/`) recoge las salidas de uno o varios
 * providers, las pasa por la función de normalización (en
 * `assurance-artifact` o `assurance-engine`), y obtiene un
 * `EvidenceSnapshot` que el core sí consume. Esta indirección existe por
 * una razón concreta: el core es puro y los providers son impuros. Mezclar
 * los dos en una llamada es lo que AAT-7 prohíbe para las lens y AAT-1
 * prohíbe para el dominio.
 */
package dev.pipelinek.assurance.engine

import dev.pipelinek.assurance.domain.evidence.RevisionRef

/**
 * SPI de productor de evidencia.
 *
 * Una lens no conoce al provider. El core no conoce al provider. El provider
 * no conoce a la lens ni al engine. La única responsabilidad del provider
 * es **recoger evidencia cruda y honesta** para una `EvidenceRequest`
 * concreta, declarando su `descriptor` y devolviendo un
 * `EvidenceCollectionResult` que un servicio de aplicación pueda normalizar.
 *
 * El `descriptor` es OBLIGATORIO: cualquier `EvidenceProvider` sin
 * descriptor no compila. La razón es la frontera epistémica: un provider
 * sin identidad no puede declarar su `producerId`/`producerVersion` en el
 * `EvidenceSourceManifest` del snapshot, y un manifest sin identidad es
 * indistinguible de "evidencia sin autor", que es lo que el modelo
 * rechaza (ADR-008).
 */
interface EvidenceProvider {

    /**
     * Identidad y capacidades del provider.
     *
     * Se materializa en cada `EvidenceSourceManifest.producerId` /
     * `producerVersion` del snapshot que produce este provider. Por eso el
     * descriptor es propiedad del interface y no un parámetro del método
     * `collect`: el descriptor no cambia entre llamadas, y un provider que
     * lo cambiara entre llamadas es un provider que miente sobre su propia
     * versión.
     */
    val descriptor: EvidenceProviderDescriptor

    /**
     * Recoge evidencia cruda para la revisión pedida.
     *
     * El provider **debe**:
     *  - declarar `gaps` por capability que no pudo producir (PROVIDER_SPI
     *    y `ARTIFACT_WIRE_CONTRACTS`): un gap no se oculta como `Complete`;
     *    un `PartialProduced("0/3")` es honesto, una lista vacía indistinguible
     *    de "cero findings" es la mentira que la spec llama "Silent Lies";
     *  - usar `EvidenceAuthority` correcta: un `Fact` exige
     *    `Deterministic*` o `RuntimeObserver`; un `Signal` exige
     *    `HeuristicAnalyzer`; un `Hypothesis` exige `AgentHypothesis` o
     *    `HumanCurated`;
     *  - no filtrar items en función de qué assertion se vaya a ejecutar:
     *    el provider no sabe qué assertion hay; el snapshot lo decide el
     *    caller después de normalizar;
     *  - propagar `subjectRevision` a la `Provenance` de cada item (UAT-024
     *    requiere que el mismo `revision` produzca el mismo digest);
     *  - no usar el reloj global para decidir la verdad (AAT-17);
     *  - no colisionar `EvidenceId` con el namespace de otro provider
     *    (ADR-008): el `producerId` del descriptor es el namespace.
     *
     * El provider **NO debe**:
     *  - retornar `AssertionResult` (AAT-6, M-V01);
     *  - decidir PASS/FAIL (ADR-009): un provider con veredicto es un motor
     *    de gates con la mitad de la información, y la mitad de un gate es
     *    un falso verde.
     */
    fun collect(request: EvidenceRequest): EvidenceCollectionResult
}

/**
 * Identidad y shape del provider.
 *
 * `id/version` se materializa en `EvidenceSourceManifest.producerId` y
 * `producerVersion`. `evidenceCapabilities` es lo que el provider sabe
 * producir; cualquier capability fuera de esta lista debe terminar en gap,
 * no en item. `subjectKinds` es el conjunto de sujetos sobre los que el
 * provider sabe trabajar; un provider que dice `Module` pero recibe
 * `Symbol` debe devolver `Unsupported`.
 *
 * `classification` distingue proveedores por su autoridad epistémica:
 *  - `Deterministic`: re-ejecutable bit a bit (e.g. parser estático).
 *  - `Runtime`: depende de la ejecución observada.
 *  - `Heuristic`: el resultado puede cambiar entre versiones o configuraciones.
 *
 * `inputFormats` lista los formatos que el provider sabe leer. Es
 * informativo para el caller, que decidirá si el formato que tiene coincide.
 *
 * `outputSchemaVersion` es la versión del schema que el `collect` emite.
 * Coincide con la `schemaVersion` del `EvidenceSourceManifest` que el
 * normalizador construirá.
 */
data class EvidenceProviderDescriptor(
    val id: String,
    val version: String,
    val evidenceCapabilities: List<String>,
    val subjectKinds: List<String>,
    val classification: ProviderClassification,
    val inputFormats: List<String>,
    val outputSchemaVersion: String,
) {
    init {
        require(id.isNotBlank()) { "EvidenceProviderDescriptor.id no puede estar en blanco" }
        require(version.isNotBlank()) { "EvidenceProviderDescriptor.version no puede estar en blanco" }
        require(outputSchemaVersion.isNotBlank()) {
            "EvidenceProviderDescriptor.outputSchemaVersion no puede estar en blanco"
        }
        require(evidenceCapabilities.none { it.isBlank() }) {
            "evidenceCapabilities no admite strings en blanco"
        }
        require(evidenceCapabilities.distinct().size == evidenceCapabilities.size) {
            "evidenceCapabilities contiene duplicados: $evidenceCapabilities"
        }
    }
}

enum class ProviderClassification {
    /** Re-ejecutable bit a bit. No usa reloj, red ni estado externo. */
    Deterministic,

    /** Depende de la ejecución observada. Re-ejecutable sobre la misma ejecución. */
    Runtime,

    /** Resultado puede cambiar entre versiones o configuraciones. */
    Heuristic,
}

/**
 * Petición de evidencia.
 *
 * `subjectRevision` identifica el commit o versión del sujeto sobre el que
 * se pide la evidencia. El provider debe **propagarlo** a la `Provenance`
 * de cada item, y un servicio de aplicación debe propagarlo al
 * `EvidenceSourceManifest` del snapshot. La razón no es decoración: UAT-024
 * (replay) exige que el mismo `revision` produzca el mismo digest, y eso
 * requiere que la revision esté en el snapshot.
 *
 * `requestedCapabilities` filtra opcionalmente las capabilities que el
 * caller va a consumir. El provider puede usarla como optimización: si sólo
 * se va a leer `architecture.dependency-graph`, no hace falta que produzca
 * `test.topology` y se ahorre el coste. **No es obligatorio** responder a
 * la lista: el resultado puede declarar capabilities de más, y el caller
 * descartará las que no necesite.
 */
data class EvidenceRequest(
    val subjectRevision: RevisionRef,
    val requestedCapabilities: List<String> = emptyList(),
) {
    init {
        require(requestedCapabilities.none { it.isBlank() }) {
            "EvidenceRequest no admite capabilities en blanco"
        }
    }
}

/**
 * Resultado crudo de `collect` antes de normalización.
 *
 * El servicio de aplicación (en `assure-cli/`, o en el plugin en M3) es el
 * que toma este resultado, lo cruza con la `EvidenceRequest`, y construye
 * un `EvidenceSnapshot` que el core sí puede consumir. La indirección
 * existe porque `EvidenceSnapshot` exige invariantes de modelo
 * (manifest no vacío, ids canónicos, authority correcta) que el SPI no
 * puede imponer: el SPI sólo recoge evidencia; la consistencia es del
 * servicio de aplicación, y el motor la verifica.
 *
 * Las dos variantes:
 *  - `Produced`: hay evidencia cruda. La lista puede ser vacía si el
 *    provider declara la capability como `Complete` con cero items
 *    legítimos, pero **nunca** si la capability está en `requestedCapabilities`
 *    y el provider no la cubrió. Esa segunda es `Failed` con gap.
 *  - `Failed`: el provider no pudo producir. Los `gaps` dicen por qué.
 *    Un provider que retorna `Failed` sin `gaps` está mintiendo sobre su
 *    propia incompletitud.
 */
sealed interface EvidenceCollectionResult {
    data class Produced(
        val producerId: String,
        val producerVersion: String,
        val schemaVersion: String,
        val rawItems: List<RawEvidenceItem>,
        val declaredGaps: List<RawEvidenceGap>,
    ) : EvidenceCollectionResult

    data class Failed(
        val producerId: String,
        val reason: ProviderFailureReason,
        val gaps: List<RawEvidenceGap>,
    ) : EvidenceCollectionResult
}

/**
 * Item crudo, sin forma de dominio todavía.
 *
 * El servicio de aplicación es el responsable de mapear cada `RawEvidenceItem`
 * a un `EvidenceItem` del dominio, validando que el `kind` pedido es legal
 * y que la `authority` casa con la clasificación del descriptor. El core
 * no ve este tipo: lo consume el `assure-cli/providers/` o el plugin.
 */
data class RawEvidenceItem(
    val kind: RawItemKind,
    val id: String,
    val subjectRef: String,
    val authority: String,
    val payload: Map<String, String>,
    val sourceLocation: String? = null,
)

enum class RawItemKind { Fact, Observation, Signal, Hypothesis }

/**
 * Gap crudo. El servicio de aplicación lo convierte a
 * `EvidenceGap(capability, reason, detail)` del dominio.
 */
data class RawEvidenceGap(
    val capability: String,
    val reason: RawGapReason,
    val detail: String? = null,
)

/**
 * Razón cruda de un gap antes de la normalización a `EvidenceGap.GapReason`.
 *
 * `PartialProduced` lleva la fracción de cobertura como String (`"3/5"`,
 * `"0/3"`) para que el servicio de aplicación pueda reportar el progreso
 * sin reinterpretar la lista de items. El dominio tiene
 * `EvidenceGap.GapReason.PartialProduced(val coveredFraction: String)` y la
 * forma cruda espeja esa.
 *
 * `sealed interface` y no `enum` por la misma razón que el dominio: una
 * variante con dato (`PartialProduced(coveredFraction)`) y tres sin dato.
 * Forzar enum obligaría a meter el String como propiedad externa del gap,
 * y eso es justo el acoplamiento que la frontera cruda/normalizada evita.
 */
sealed interface RawGapReason {
    /** El provider no soporta esta capability. */
    data object Unsupported : RawGapReason

    /** El provider la soporta pero produjo cobertura parcial. */
    data class PartialProduced(val coveredFraction: String) : RawGapReason

    /** No se sabe si se soporta. */
    data object Unknown : RawGapReason

    /** La evidencia se perdió después de producirse. */
    data object Lost : RawGapReason

    /**
     * El producer declaró una razón que el provider no reconoce. Se
     * mantiene el string original en [rawReason] para que el motor
     * pueda reportarlo, pero el provider NO la re-clasifica
     * silenciosamente como `PartialProduced` (que era el bug que
     * M-COGN01 ataca).
     */
    data class Other(val rawReason: String) : RawGapReason
}

/**
 * Razón de fallo del provider.
 *
 * Distinta de los gaps: un provider puede `Failed` por una razón externa
 * (e.g. su CLI no se pudo invocar), no por una capability no soportada.
 */
sealed interface ProviderFailureReason {
    data class CollectionError(val detail: String) : ProviderFailureReason
    data class UnsupportedInputFormat(val detail: String) : ProviderFailureReason
    data class ProviderUnavailable(val detail: String) : ProviderFailureReason
}
