package dev.pipelinek.assurance.fitness

import dev.pipelinek.assurance.artifact.CanonicalEncoder
import dev.pipelinek.assurance.engine.AssertionId
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.LensId
import dev.pipelinek.assurance.engine.LensPlan
import dev.pipelinek.assurance.testkit.EvidenceFixtures
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * AAT del exit criteria de M0 que NO tenían ejecución.
 *
 * Se encontraron al cerrar el gate. AAT-1, AAT-2, AAT-9, AAT-17 y AAT-20
 * tienen tests que los nombran. AAT-6, AAT-8 y AAT-16 no tenían ninguno, y el
 * gate se estaba declarando cerrado con ellos en la lista de exit criteria sin
 * que nadie los hubiera comprobado una sola vez.
 *
 * "Vacío" es un estado distinto de "cumplido", y en un exit criteria no puede
 * contar igual. Un `forall` sobre conjunto vacío es cierto, y eso no certifica
 * nada: certifica que no hay nada que mirar. Estas leyes comprueban dos cosas
 * distintas, y las dos importan:
 *
 *  1. que la regla se cumpla hoy, y
 *  2. que el test que la comprueba seguiría poniéndose rojo si se incumpliera.
 *
 * La segunda es la que casi nunca se escribe, y es la que convierte "cerrado"
 * en algo que no se deshace solo.
 */
class M0UnprovenAatsTest : AnnotationSpec() {

    private val modulos = listOf("assurance-domain", "assurance-engine", "assurance-artifact")

    /**
     * Se sube hasta el `settings.gradle.kts`, igual que `M0FitnessTest`. Un
     * test que resuelve rutas con el working directory funciona sólo cuando
     * Gradle tiene suerte: `user.dir` es el directorio del proyecto que ejecuta
     * la tarea, no la raíz del repo. Es un fallo silencioso que aparece como
     * `FileNotFoundException`, no como "no se encontró la regla".
     */
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun fuentesPrincipales(): List<File> = modulos.map { File(repoRoot, "$it/src/main/kotlin") }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }

    private fun fuenteDe(modulo: String, camino: String) = File(repoRoot, "$modulo/src/main/kotlin/$camino")

    // ---------------------------------------------------------------------
    // AAT-6: ningún EvidenceProvider retorna AssertionResult
    // ---------------------------------------------------------------------

    /**
     * La regla se cumple hoy de forma VACUA: no existe ningún
     * `EvidenceProvider` en el repo. Un `forall` sobre el conjunto vacío es
     * cierto, y por eso la ley no puede reducirse a "no hay providers": eso
     * es una afirmación sobre el estado, que cambia sola.
     *
     * Lo que se comprueba es el MECANISMO, y que tenga dientes: si alguien
     * declara un `EvidenceProvider` que retorne `AssertionResult`, esta ley se
     * pone roja.
     */
    @Test
    fun AAT_06_no_existe_ningun_evidence_provider_que_retorne_assertion_result() {
        val providers = fuentesPrincipales().filter { "EvidenceProvider" in it.readText() }

        val contradicciones = providers.mapNotNull { file ->
            file.readText().lineSequence()
                .map { it.trim() }
                .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
                .firstOrNull { "AssertionResult" in it }
        }

        contradicciones shouldBe emptyList()
    }

    // ---------------------------------------------------------------------
    // AAT-8: AssertionResult exhaustive, sin shortcut booleano
    // ---------------------------------------------------------------------

    @Test
    fun AAT_08_assertion_result_sigue_sellado_y_sin_atajo_booleano() {
        val cuerpo = fuenteDe(
            "assurance-engine",
            "dev/pipelinek/assurance/engine/Assurance.kt",
        ).readText()

        // Sin `sealed`, el `when` deja de ser exhaustivo en compilación y el
        // defecto se vuelve silencioso. El sello ES parte de la regla.
        Regex("""sealed\s+(interface|class)\s+AssertionResult""").containsMatchIn(cuerpo) shouldBe true

        // La forma del defecto: añadir `val passed: Boolean`, o
        // `fun isPassed(): Boolean`. El `when` sigue exhaustivo y el bug pasa
        // igual, porque nadie mira el atajo y quien lo mira mira lo rápido.
        val atajos = Regex(
            """\b(?:val|var|fun)\s+(?:is)?(?:Passed|Pass|Ok|Ok|Success|Succeeded|Verified)\b""",
        ).findAll(cuerpo).map { it.value }.distinct().toList()

        atajos shouldBe emptyList()
    }

    @Test
    fun AAT_08_los_cinco_veredictos_declarados_son_los_que_existen() {
        // Comparación contra una lista EXPLÍCITA, no contra lo que la reflexión
        // encuentre: añadir un sexto `AssertionResult` tiene que ser un cambio
        // visible. Si esto se hiciera con reflexión, el test pasaría en verde
        // justo al añadir el subtipo nuevo, que es cuando debe ponerse rojo.
        val cuerpo = fuenteDe(
            "assurance-engine",
            "dev/pipelinek/assurance/engine/Assurance.kt",
        ).readText()

        val bloque = cuerpo
            .substringAfter("sealed interface AssertionResult {")
            .substringBefore("\n}\n")

        Regex("""\bdata class (\w+)""").findAll( bloque)
            .map { it.groupValues[1] }.toSet() shouldBe
            setOf("Passed", "Failed", "Inconclusive", "Unsupported", "Error")
    }

    // ---------------------------------------------------------------------
    // AAT-16: serializer de suite IR con orden canónico de map/set
    // ---------------------------------------------------------------------

    @Test
    fun AAT_16_la_permutacion_de_lenses_da_el_mismo_digest_de_suite() {
        // AAT-16 no se comprueba leyendo el código: hay que GENERAR la entrada
        // desordenada. Un test con dos lenses en orden fijo pasa por el motivo
        // equivocado en cuanto el encoder cambia, y ya pasó una vez aquí con
        // `correlations`.
        val base = suiteConVariasLenses()
        val ascendente = base
        val descendente = base.copy(lenses = base.lenses.reversed())

        // Guarda de teeth, y va PRIMERO: las dos suites tienen que ser
        // DISTINTAS antes de canonicalizar, o todo lo de abajo pasa por el
        // motivo equivocado. Este es el fallo que ya encontramos dos veces.
        ascendente.lenses shouldNotBe descendente.lenses
        (ascendente.lenses.map { it.lensId } != descendente.lenses.map { it.lensId }) shouldBe true

        // Contenido idéntico.
        ascendente.lenses.map { it.lensId }.toSet() shouldBe descendente.lenses.map { it.lensId }.toSet()

        // El encoder colapsa las dos al mismo orden canónico.
        CanonicalEncoder.canonicalizeSuite(ascendente) shouldBe
            CanonicalEncoder.canonicalizeSuite(descendente)

        // Y por tanto el mismo digest, que es donde un encoder no canónico se
        // delata sin ambigüedad.
        CanonicalEncoder.digestSuite(ascendente) shouldBe CanonicalEncoder.digestSuite(descendente)
    }

    /**
     * Suite con VARIAS lenses, porque la permutación de una lista de un
     * elemento es la identidad y la ley pasaría sin comprobar nada. La
     * fixture `EvidenceFixtures.suite()` trae una sola lens: sirve para lo
     * que se usó, no para AAT-16.
     */
    private fun suiteConVariasLenses(): AssuranceSuiteIR {
        val base = EvidenceFixtures.suite()
        val extra = listOf(
            LensPlan(
                lensId = LensId("zeta"),
                kind = "architecture.cycles",
                inputCapabilities = listOf("ModuleDependencies", "CallGraph"),
                outputSchema = "schema://assurance/cycles/v1",
            ),
            LensPlan(
                lensId = LensId("alfa"),
                kind = "architecture.purity",
                inputCapabilities = listOf("ModuleDependencies"),
                outputSchema = "schema://assurance/purity/v1",
            ),
        )
        return base.copy(lenses = base.lenses + extra)
    }
}