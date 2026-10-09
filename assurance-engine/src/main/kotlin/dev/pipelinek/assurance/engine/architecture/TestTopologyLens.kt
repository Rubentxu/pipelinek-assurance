/**
 * M5 — Lens `TestTopology`.
 *
 * Ref autoridad: `03-specifications/LENSES.md` (forma y contrato de las lenses).
 *
 * **AAT-7, observado:** esta lens NO toca fs ni red. Es una proyección pura
 * de un `EvidenceSnapshot` a una vista agregada de tests.
 *
 * **El STOP de M1, obedecido.** La lens busca por `capability` y por
 * predicate, no por `producerId`. Un JUnit XML y un Chronos report
 * que produzcan tests se agregan de la misma forma; la lens no sabe
 * cuál fue.
 *
 * **Forma del output:**
 *   - `totalTests: Int`
 *   - `passed: Int`, `failed: Int`, `errors: Int`, `skipped: Int`
 *   - `tests: List<TestDescriptor>`
 *
 * **Contrato de item:** un item es un test si
 *   - `provenance.capability` ∈ {`test.topology`, `test.results`}, Y
 *   - `subject is EvidenceSubject.Test`, Y
 *   - `EvidenceItem.Fact.predicate == "junit.testcase"` (un predicado
 *     dedicado, no cualquier predicate: la lens sólo consume items
 *     que SABEN que son tests, no cualquier Fact del snapshot).
 *
 * El `objectValue` del Fact codifica el test:
 *   `classname=X;name=Y;status=Z;time=T;suite=S`
 *
 * Status admitidos: `passed`, `failed`, `errors`, `skipped`. Si el
 * status no encaja, el item se cuenta como `errors` con
 * `status = "errors"`, preservando la invariante `totalTests == tests.size`.
 */
package dev.pipelinek.assurance.engine.architecture

import dev.pipelinek.assurance.domain.evidence.EvidenceGap
import dev.pipelinek.assurance.domain.evidence.EvidenceItem
import dev.pipelinek.assurance.domain.evidence.EvidenceSnapshot
import dev.pipelinek.assurance.domain.evidence.EvidenceSubject
import dev.pipelinek.assurance.engine.AssuranceLens
import dev.pipelinek.assurance.engine.ProjectionFailureReason
import dev.pipelinek.assurance.engine.ProjectionResult

data class TestDescriptor(
    val id: String,
    val classname: String,
    val name: String,
    val status: String,
    val time: String,
    val suite: String?,
)

data class TestTopology(
    val totalTests: Int,
    val passed: Int,
    val failed: Int,
    val errors: Int,
    val skipped: Int,
    val tests: List<TestDescriptor>,
) {
    init {
        require(passed + failed + errors + skipped == totalTests) {
            "TestTopology: conteos inconsistentes: $passed+$failed+$errors+$skipped != $totalTests"
        }
        require(tests.size == totalTests) {
            "TestTopology: tests.size (${tests.size}) != totalTests ($totalTests)"
        }
    }
}

object TestTopologyLens : AssuranceLens<EvidenceSnapshot, TestTopology> {

    const val CAPABILITY: String = "test.topology"
    const val CAPABILITY_RESULTS: String = "test.results"
    const val PREDICATE: String = "junit.testcase"

    private val KNOWN_STATUSES: Set<String> = setOf("passed", "failed", "errors", "errored", "skipped")
    private val KNOWN_CAPABILITIES: Set<String> = setOf(CAPABILITY, CAPABILITY_RESULTS)

    override fun project(input: EvidenceSnapshot): ProjectionResult<TestTopology> {
        val candidates = input.items.filter { item ->
            val cap = item.provenance.capability
            cap in KNOWN_CAPABILITIES
        }
        if (candidates.isEmpty()) {
            return fallo(
                reason = ProjectionFailureReason.MissingCapability(CAPABILITY),
                gapReason = EvidenceGap.GapReason.Unknown,
                detail = "ninguna evidencia declara '$CAPABILITY' ni '$CAPABILITY_RESULTS'",
            )
        }

        // AAT-19: solo autoridades deterministas o RuntimeObserver.
        // Un Signal heurístico no es un test.
        val rechazadosPorAutoridad = candidates.filter {
            it.authority.name != "DeterministicAdapter" && it.authority.name != "RuntimeObserver"
        }
        if (rechazadosPorAutoridad.isNotEmpty()) {
            return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' solo admite DeterministicAdapter o RuntimeObserver; " +
                        "llegan ${rechazadosPorAutoridad.size} con autoridad no admitida: " +
                        rechazadosPorAutoridad.joinToString(", ") { "${it.authority} (${it.id.value})" },
                ),
                gapReason = EvidenceGap.GapReason.Unsupported,
                detail = "autoridad no admitida por la lens test-topology",
            )
        }

        // Sólo Facts con predicate PREDICATE. Otros tipos (Observation,
        // Signal, Hypothesis) no entran como tests en V1.
        val testsItems = candidates.filterIsInstance<EvidenceItem.Fact>()
            .filter { it.predicate == PREDICATE }

        // Si hay items en la capability pero ninguno con el predicate
        // correcto, la lens falla con InvalidInput: la capability
        // existe pero el formato no es el esperado.
        if (testsItems.isEmpty() && rechazadosPorAutoridad.isEmpty()) {
            return fallo(
                reason = ProjectionFailureReason.InvalidInput(
                    "'$CAPABILITY' tiene ${candidates.size} items pero ninguno es un Fact con predicate '$PREDICATE'",
                ),
                gapReason = EvidenceGap.GapReason.PartialProduced(coveredFraction = "0/${candidates.size}"),
                detail = "predicate incorrecto; se esperaba '$PREDICATE'",
            )
        }

        // Construir descriptores con validación. Un item con classname
        // ausente, status desconocido o formato de id inválido se
        // rechaza con `InvalidInput`: la lens admite items de tests
        // con el shape esperado, no "cualquier cosa en la capability".
        val descriptors = mutableListOf<TestDescriptor>()
        val seen = mutableSetOf<Pair<String, String>>()
        for (item in testsItems) {
            val d = parseDescriptor(item)
                ?: return fallo(
                    reason = ProjectionFailureReason.InvalidInput(
                        "item '${item.id.value}' no tiene el shape esperado: classname o status ausente",
                    ),
                    gapReason = EvidenceGap.GapReason.Unsupported,
                    detail = "testcase mal formado",
                )
            // Deduplicación por (classname, name): si un mismo test
            // aparece dos veces (e.g. el mismo test en dos reports),
            // el segundo se ignora. La deduplicación se hace en la
            // lens y no en el provider para que la cuenta no dependa
            // del orden de producers.
            if (d.classname to d.name in seen) continue
            seen += d.classname to d.name
            descriptors += d
        }

        val byStatus = descriptors.groupingBy { it.status }.eachCount()
        return ProjectionResult.Projected(
            TestTopology(
                totalTests = descriptors.size,
                passed = byStatus["passed"] ?: 0,
                failed = byStatus["failed"] ?: 0,
                // `errored` y `errors` son la misma categoría; el
                // provider JUnit XML emite `errored`, el reporte del
                // engine usa `errors`. Sumamos los dos.
                errors = (byStatus["errors"] ?: 0) + (byStatus["errored"] ?: 0),
                skipped = byStatus["skipped"] ?: 0,
                tests = descriptors,
            ),
        )
    }

    /**
     * Parsea un item `Fact` con predicate `PREDICATE` a un
     * `TestDescriptor`. Devuelve `null` si el shape no es válido.
     *
     * Convenciones:
     *   - `id` del item: `namespace/classname#name` (la lens extrae
     *     classname y name del id, no del payload).
     *   - `objectValue` del Fact: `status=X;time=Y;suite=Z` (status
     *     obligatorio; time y suite opcionales).
     */
    private fun parseDescriptor(item: EvidenceItem.Fact): TestDescriptor? {
        // El `subject` del item es `EvidenceSubject.Test(id)`, donde
        // `id` codifica `classname#name` o `classname/name`. La lens
        // extrae ambos y construye el `id` canónico del descriptor
        // como `classname#name`. El `EvidenceId` del item puede
        // tener cualquier namespace del provider; el `id` del
        // descriptor es la identidad de la suite de tests, no la
        // identidad del provider.
        val subject = item.subject
        if (subject !is EvidenceSubject.Test) return null
        val (classname, name) = parseClassnameName(subject.id) ?: return null
        if (classname.isBlank() || name.isBlank()) return null

        val payload = item.objectValue.orEmpty()
        val parts = if (payload.isBlank()) emptyMap() else parseKeyValue(payload)
        val rawStatus = parts["status"] ?: return null
        if (rawStatus !in KNOWN_STATUSES) return null
        val time = parts["time"] ?: "0.0"
        val suite = parts["suite"]?.takeIf { it.isNotBlank() }
        return TestDescriptor(
            id = "$classname#$name",
            classname = classname,
            name = name,
            status = rawStatus,
            time = time,
            suite = suite,
        )
    }

    private fun parseClassnameName(id: String): Pair<String, String>? {
        // Formatos aceptados:
        //   `prefix/classname/name`     — classname y name en el path.
        //   `prefix/classname#name`     — classname y name separados por `#`.
        // En ambos casos, la última parte del path o lo que sigue a `#`
        // es el `name`, y la penúltima es el `classname`.
        val lastHash = id.lastIndexOf('#')
        if (lastHash > 0) {
            val rawClassname = id.substringBeforeLast('#', "")
            val classname = rawClassname.substringAfterLast('/', rawClassname)
            val name = id.substring(lastHash + 1)
            return classname to name
        }
        // Sin `#`: name = última parte, classname = penúltima.
        val parts = id.split('/')
        if (parts.size < 2) return null
        val name = parts.last()
        val classname = parts[parts.size - 2]
        return classname to name
    }

    private fun parseKeyValue(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (part in text.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                out[kv[0].trim()] = kv[1].trim()
            }
        }
        return out
    }

    private fun fallo(
        reason: ProjectionFailureReason,
        gapReason: EvidenceGap.GapReason,
        detail: String,
    ): ProjectionResult.ProjectionFailed = ProjectionResult.ProjectionFailed(
        reason = reason,
        gaps = listOf(EvidenceGap(capability = CAPABILITY, reason = gapReason, detail = detail)),
    )
}
