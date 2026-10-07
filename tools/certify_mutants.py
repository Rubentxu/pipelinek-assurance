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
REPORT = os.path.join(ROOT, "assurance-testkit/build/reports/tests/test/classes")

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
        ["./gradlew", ":assurance-testkit:test", "--no-daemon", "--rerun-tasks"],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if not glob.glob(os.path.join(REPORT, "*.html")):
        raise SystemExit(
            "STOP: el informe de tests no se genero. Sin el, la certificacion "
            "no puede afirmar nada. Comprueba que el codigo de test compile."
        )


def parse_report():
    """Devuelve [(test, status)] leyendo el informe HTML de Gradle.

    Cada test es una fila `<tr>` con tres celdas: nombre, duracion, estado.
    """
    results = []
    for f in glob.glob(os.path.join(REPORT, "*.html")):
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
