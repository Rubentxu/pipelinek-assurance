#!/usr/bin/env python3
"""Certificacion de mutantes para el Gate M0.

Inyecta cada mutante del catalogo (08-testing/MUTATION_CATALOG.md), ejecuta la
suite y cuenta cuantos tests lo matan de forma independiente. Un mutante
superviviente o matado por un unico test es un STOP del gate, no un detalle.

Uso:  python3 tools/certify_mutants.py <nombre-mutante>
Exit: 0 si el mutante muere, 1 si sobrevive.

Las fuentes se restauran siempre, pase lo que pase.
"""
import glob
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOMAIN = "assurance-domain/src/main/kotlin/dev/pipelinek/assurance/domain/evidence/Evidence.kt"
ARTIFACT = "assurance-artifact/src/main/kotlin/dev/pipelinek/assurance/artifact/CanonicalEncoder.kt"
CODEC = "assurance-artifact/src/main/kotlin/dev/pipelinek/assurance/artifact/EvidenceArtifactCodec.kt"
SUITE_CODEC = "assurance-artifact/src/main/kotlin/dev/pipelinek/assurance/artifact/SuiteReportArtifactCodec.kt"
ENGINE = "assurance-engine/src/main/kotlin/dev/pipelinek/assurance/engine/Assurance.kt"
REPORT = os.path.join(ROOT, "assurance-testkit/build/reports/tests/test/classes")
# Los tests del codec viven en el modulo `assurance-artifact` (sus DTO son
# `internal`), asi que su informe cuenta igual que el del testkit. Sin esta
# segunda ruta, M-D01 y M-R03 aparecerian como supervivientes con 0 bajas,
# que es un falso negativo del harness, no un fallo del codigo.
REPORTS = [
    REPORT,
    os.path.join(ROOT, "assurance-artifact/build/reports/tests/test/classes"),
]

# Cada mutante: (nombre, [(fichero, [(buscar, reemplazar)]), ...])
#
# Varios ficheros por mutante porque hay mutantes que tocan sitios distintos
# del mismo defecto: quitar las cotas del decode toca DOS funciones del
# codec, y un mutante que sólo quita una no mata nada.
MUTANTS = {
    "M-E01": [(DOMAIN, [(
        '''            require(completeness !is Completeness.Unknown && completeness !is Completeness.Unsupported) {
                "Un Fact no puede tener completitud $completeness"
            }''',
        "",
    )])],
    "M-E02": [(DOMAIN, [(
        '''            require(
                authority == EvidenceAuthority.AgentHypothesis ||
                    authority == EvidenceAuthority.HumanCurated,
            ) {
                "Una Hypothesis requiere AgentHypothesis o HumanCurated, no $authority"
            }''',
        "",
    )])],
    "M-H01": [(DOMAIN, [(
        '''            require(authority == EvidenceAuthority.HeuristicAnalyzer) {
                "Un Signal requiere autoridad HeuristicAnalyzer, no $authority"
            }''',
        "",
    )])],
    "M-R01": [(ARTIFACT, [
        # "Serializa sin canonicalizar": el mutante clásico de AAT-16. Quitar
        # las llamadas a canonical* devuelve el encoder al orden de iteracion.
        ("canonicalSources(snapshot.sources)", "snapshot.sources"),
        ("canonicalItems(snapshot.items)", "snapshot.items"),
        ("canonicalGaps(snapshot.gaps)", "snapshot.gaps"),
        ("canonicalCorrelations(snapshot.correlations)", "snapshot.correlations"),
        ("lens.arguments.toSortedMap()", "lens.arguments"),
        ("a.operands.toSortedMap()", "a.operands"),
        ("item.thresholds.toSortedMap()", "item.thresholds"),
        ("m.completenessByCapability.toSortedMap()", "m.completenessByCapability"),
        ("suite.lenses.sortedBy { it.lensId.value }", "suite.lenses"),
        ("suite.assertions.sortedBy { it.id.value }", "suite.assertions"),
    ])],
    # El defecto que encontro el property testing, no los tests de ejemplo:
    # ordenar por `EvidenceId` SOLO. `sortedWith` es estable, asi que dos items
    # con el mismo id conservan el orden de entrada y el digest pasa a
    # depender de como el runtime recogio la evidencia. Es exactamente lo que
    # prohibe el digest canónico.
    "M-R02": [(ARTIFACT, [
        ("compareBy(key).thenBy(tie)", "compareBy(key)"),
    ])],
    # "Convertir a dominio sin aplicar las cotas". Este es el mutante de
    # seguridad real, y no teórico: la llamada vive en
    # `EvidenceSnapshotDto.toDomain()`, que es el único punto por el que
    # pasan tanto el encode como el decode. Quitarla devuelve el codec al
    # estado en el que la entrada no confiable no encuentra ningún freno.
    #
    # Nota: este mutante nació de una premisa mía que era FALSA. Yo creía que
    # las cotas no se ejecutaban al decodificar, y añadí una segunda llamada en
    # `decodeFrom*` "para arreglarlo". Al certificar, M-S01 sobrevivió y la
    # causa fue que la llamada original ya cubría el decode. La segunda
    # llamada era redundante y se ha retirado.
    "M-S01": [(CODEC, [
        (
            """    fun toDomain(): EvidenceSnapshot {
        EvidenceArtifactCodec.requireWithinLimits(this)""",
            """    fun toDomain(): EvidenceSnapshot {""",
        ),
    ])],
    # La cota de longitud de cadena no existe. Sin ella, una cadena de 1 GiB
    # pasa por el decoder sin que nadie mire su tamaño.
    #
    # REDUNDANCIA INSUFICIENTE, y el harness lo dice con exit 1: lo mata un
    # solo test. No se maquilla como certificado. La redundancia real llegará
    # con un segundo test que ejercite la cota por CBOR y otro por una cadena
    # anidada (por ejemplo `Completeness.Unsupported.reason`, que hoy no tiene
    # test propio). Queda como deuda declarada, no como gate cerrado.
    "M-S02": [(CODEC, [
        (
            "    require(length <= EvidenceArtifactCodec.MAX_STRING_LENGTH) {",
            "    require(true) {",
        ),
    ])],
    # El envelope declara el digest pero el decoder NO lo comprueba. Es el
    # mutante de integridad: sin esta comparacion, un artefacto alterado en
    # transito decodifica "bien" y el gate evalua contra contenido que nadie
    # reviso. Es la razon de existir del campo, asi que su ausencia tiene que
    # morir por varios tests.
    "M-D01": [
        (CODEC, [
            (
                """        if (digest != canonical) {
            throw ArtifactDecodeException(
                "digest declarado ${digest.take(12)} no coincide con el canónico " +
                    "${canonical.take(12)} (payload alterado en tránsito)",
            )
        }""",
                "",
            ),
        ]),
        (SUITE_CODEC, [
            (
                """        if (digest != canonical) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "digest de suite declarado ${digest.take(12)} no coincide con el canónico " +
                    "${canonical.take(12)} (payload alterado en tránsito)",
            )
        }""",
                "",
            ),
            (
                """        if (digest != canonical) {
            throw EvidenceArtifactCodec.ArtifactDecodeException(
                "digest de report declarado ${digest.take(12)} no coincide con el canónico " +
                    "${canonical.take(12)} (payload alterado en tránsito)",
            )
        }""",
                "",
            ),
        ]),
    ],
    # El digest vuelve a ser opcional "por compatibilidad hacia atras". Con
    # `String? = null`, un envelope sin digest pasa a ser indistinguible de uno
    # integro, y quien verifica tiene que adivinar en vez de comprobar.
    #
    # Este mutante es el que Justifico la decision de volver `digest`
    # obligatorio: el contrato lo lista sin marcarlo opcional, y la
    # "compatibilidad" que habia era compatibility con un v0 que no existe.
    "M-D02": [
        (CODEC, [
            ("    val digest: String,\n) {\n    init {", "    val digest: String? = null,\n) {\n    init {"),
        ]),
    ],
    # El report serializa sin canonicalizar. El encoder canonico SI ordena
    # (por eso el digest no cambia), pero el DTO no: dos runners con los mismos
    # resultados en distinto orden darian artefactos distintos con el MISMO
    # digest. Es el caso peor de los tres, porque el digest no lo delata.
    "M-R03": [(SUITE_CODEC, [
        ("CanonicalEncoder.canonicalResults(report.results).map { ResultDto.of(it) }",
         "report.results.map { ResultDto.of(it) }"),
        ("CanonicalEncoder.canonicalArtifacts(report.artifacts).map { ArtifactRefDto.of(it) }",
         "report.artifacts.map { ArtifactRefDto.of(it) }"),
    ])],
    # Mismo defecto que M-R03 pero en `correlations`, que estaba SIN
    # canonicalizar mientras el digest SI las ordenaba. Lo encontro la ley de
    # forma canonica de `SuiteReportLawsTest`, no un test de ejemplo: hacer
    # falta GENERAR la coleccion desordenada para que aparezca.
    #
    # Va como mutante aparte y no como una tercera sustitucion de M-R03 porque
    # son defectos independientes: arreglar uno no arregla el otro, y un
    # mutante que cubre dos causas a la vez no dice cual de las dos sigue viva.
    "M-R04": [(SUITE_CODEC, [
        ("correlations = CanonicalEncoder.canonicalCorrelations(report.correlations).map {",
         "correlations = report.correlations.map {"),
    ])],
    # Volver al orden de DECLARACION de kotlinx en el JSON del envelope.
    # El digest no lo detecta (se calcula sobre `encodeSnapshot`, no sobre el
    # JSON), asi que este mutante es invisible para todo lo que ya existia:
    # solo lo cazan los tests que miran el orden de las claves del texto.
    #
    # Sin el, "orden canónico" seria una intencion del commit en vez de una
    # propiedad verificable, que es exactamente como vuelve el defecto.
    "M-J01": [(CODEC, [
        ("return CanonicalJson.encodeCanonical(EvidenceSnapshotDto.serializer(), dto)",
         "return json.encodeToString(EvidenceSnapshotDto.serializer(), dto)"),
    ])],
    # Los tres AAT que el exit criteria de M0 declaraba verdes y que no
    # tenian ninguna ejecucion. Se descubrio al cerrar el gate: M0 se estaba
    # certificando con tres reglas de su exit criteria que nunca se habian
    # comprobado una sola vez.
    #
    # M-A01 es el caso raro y por eso existe: la regla se cumple hoy de forma
    # VACUA, porque no hay ningun EvidenceProvider en el repo. Sin mutante, un
    # test que pasa sobre conjunto vacio es indistinguible de un test que no
    # mira nada. El mutante DECLARA el provider que la regla prohibe, y exige
    # que la ley lo detecte. Sin esto, "AAT-6 verde" significa "no hay nada
    # que mirar", que no es lo mismo que "la regla se cumple".
    "M-A01": [(ENGINE, [
        ("""sealed interface AssertionResult {""",
         """interface EvidenceProvider

sealed interface AssertionResult {
    @Suppress("unused")
    fun isPassed(): Boolean = (this as? Passed) != null
"""),
    ])],
    # Sin `sealed`, el `when` sobre AssertionResult deja de ser exhaustivo en
    # tiempo de compilacion. El defecto no es un fallo de ejecucion: el
    # compilador acepta el `when` con un `else`, y un subtype nuevo pasa
    # inadvertido. El atajo booleano es la segunda mitad del mismo defecto.
    "M-A02": [(ENGINE, [
        ("sealed interface AssertionResult {", "interface AssertionResult {"),
    ])],
    # El encoder de suite IR deja de ordenar `lenses`. El digest sigue
    # ordenando por su cuenta, asi que M-R01/M-R02 no lo cazan: por eso hace
    # falta uno para el IR y no solo para el snapshot.
    "M-A03": [(ARTIFACT, [
        ("""        lenses = suite.lenses
            .sortedBy { it.lensId.value }
            .map { lens ->""",
         """        lenses = suite.lenses
            .map { lens ->"""),
    ])],
}

ROW = re.compile(r"<tr>(.*?)</tr>", re.S)
CELL = re.compile(r'<td class="(\w+)">(.*?)</td>', re.S)


def apply(path, edits):
    full = os.path.join(ROOT, path)
    src = open(full, encoding="utf-8").read()
    for needle, replacement in edits:
        if needle not in src:
            raise SystemExit(f"mutacion no aplica a {path}: no se encontro el patron")
        src = src.replace(needle, replacement)
    open(full, "w", encoding="utf-8").write(src)


def run_tests():
    # Sin forzar la reejecucion Gradle marca el test UP-TO-DATE: no corre y no
    # regenera el informe HTML. El conteo de bajas daria 0 y el mutante
    # pareceria sobreviviente. Un falso "sobrevive" aqui es peor que un falso
    # positivo, porque frenaria el gate por una mentira.
    subprocess.run(
        ["./gradlew", ":assurance-testkit:test", ":assurance-artifact:test",
         "--no-daemon", "--rerun-tasks"],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if not any(glob.glob(os.path.join(r, "*.html")) for r in REPORTS):
        raise SystemExit(
            "STOP: el informe de tests no se genero. Sin el, la certificacion "
            "no puede afirmar nada. Comprueba que el codigo de test compile."
        )


def parse_report():
    """Devuelve [(test, status)] leyendo los informes HTML de Gradle.

    Cada test es una fila `<tr>` con tres celdas: nombre, duracion, estado.
    """
    results = []
    for report in REPORTS:
        for f in glob.glob(os.path.join(report, "*.html")):
            cls = os.path.basename(f)[:-5].split(".")[-1]
            body = open(f, encoding="utf-8").read()
            for row in ROW.findall(body):
                cells = CELL.findall(row)
                if len(cells) != 3:
                    continue
                name = html.unescape(re.sub(r"<[^>]*>", "", cells[0][1])).strip()
                status = html.unescape(re.sub(r"<[^>]*>", "", cells[2][1])).strip()
                results.append((f"{cls}>{name}", status))
    return results


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in MUTANTS:
        print(f"uso: {sys.argv[0]} <{'|'.join(MUTANTS)}>")
        return 2

    name = sys.argv[1]
    targets = MUTANTS[name]

    # STOP si el arbol de trabajo esta sucio. El harness hace COPIAS de
    # seguridad de los ficheros que va a mutar y los restaura al final. Si
    # alguien edita uno de esos ficheros mientras corre, la restauracion
    # escribe la copia VIEJA encima del trabajo nuevo y lo pierde sin avisar.
    #
    # Ya pasó una vez en este repo: la certificacion de los mutantes se lanzo
    # en segundo plano, se corrigio `SuiteReportArtifactCodec.kt` mientras
    # corria, y al terminar el harness dejo el fichero con las mutaciones
    # inyectadas. Dos tests de digestalterado empezaron a "pasar" porque el
    # fix habia desaparecido, y el sintoma (un digest corrupto que se acepta)
    # es exactamente el fallo que el gate existe para detectar.
    #
    # Un harness que puede perder trabajo no es un harness: es un hazard.
    if subprocess.run(
        ["git", "status", "--porcelain", "--"] + sorted({p for p, _ in targets}),
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    ).stdout.decode().strip():
        print(
            f"STOP: los ficheros a mutar tienen cambios sin commitear. "
            f"Commit o stash antes de certificar {name}: el harness restaura "
            f"copias y perderia el trabajo no commiteado."
        )
        return 3

    backups = {}
    for path, _ in targets:
        backups[path] = tempfile.NamedTemporaryFile(delete=False, suffix=".bak")
        backups[path].write(open(os.path.join(ROOT, path), "rb").read())
        backups[path].close()

    try:
        for path, edits in targets:
            apply(path, edits)
        run_tests()
        results = parse_report()
        killed = sorted(t for t, s in results if s == "failed")
        print(f"{name}: total={len(results)} killed={len(killed)}")
        for k in killed:
            print(f"  killed: {k}")
        if not killed:
            print(f"STOP: {name} sobrevive. El gate M0 exige certificacion.")
            return 1
        if len(killed) < 2:
            print(f"AVISO: {name} muere por un solo test. Redundancia insuficiente.")
            return 1
        return 0
    finally:
        for target, backup in backups.items():
            shutil.copyfile(backup.name, os.path.join(ROOT, target))
            os.unlink(backup.name)


if __name__ == "__main__":
    sys.exit(main())
