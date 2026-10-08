# Gates por hito

## Gate M0

**Estado: CERRADO (2026-10-08, `b1cfbc5`/`c416521` y corrección posterior).**
Evidencia observada:

- domain/api tests verdes — `clean check` BUILD SUCCESSFUL, 174 tests,
  0 fallos, 0 skipped;
- property laws verdes — `SuiteReportLawsTest`, `EvidenceLawsTest`,
  `EpistemicLawsTest`, `CanonicalJsonOrderTest`;
- M-E01/M-E02/M-R01 muertos — certificate los tres; ademas M-H01, M-R02,
  M-R03, M-R04, M-J01, M-S01, M-S02, M-D01, M-D02, M-V01, M-V02, M-V03.
  Quince en total, y ninguno muere por un único test;
- no I/O dependency fitness — fitness tests en `assurance-domain`, sin fs,
  red, coroutines ni CLI;
- canonical golden corpus estable — verificado por `check`, no regenerado en
  la corrida de cierre. El golden sí se regeneró a conciencia antes, cuando el
  orden canónico JSON movió `snapshot.json.sha256`.

**Exit criteria verificado AAT por AAT, no heredado del documento.** Los ocho AAT que el
exit criteria de M0 declara son 1, 2, 6, 8, 9, 16, 17 y 20. Verificados uno a
uno, **AAT-6, AAT-8 y AAT-16 no tenían ninguna ejecución**: el gate se estaba
certificando con tres reglas de su propio exit criteria que nadie había
comprobado. Ahora los tres tienen ley y mutante propio (M-V01, M-V02, M-V03).

AAT-6 merece la nota: se cumple hoy de forma ** vacua, porque no existe ningún
`EvidenceProvider` en el repo. Por eso su mutante *declara* el provider
prohibido, en vez de limitarse a comprobar que no hay ninguno. Sin eso, "AAT-6
verde" y "el test no mira nada" serían la misma observación.

El proceso falló una vez aquí y queda escrito: el gate se cerró y se pusheó
antes de verificar el exit criteria. Cerrar por la lista de criterios, en vez
de por lo que los criterios dicen, es la forma de cerrar un gate que no está
cerrado.

Cerrado este gate, la precondición de M1 queda satisfecha.

## Gate M1

- UAT-003/004;
- M-A01/A02 muertos;
- witness reproducible;
- self synthetic suite verde.

## Gate M2

- CogniCode export schema golden;
- UAT-006/007;
- differential projection parity;
- self-host CogniCode snapshot.

## Gate M3

- plugin manifest validation;
- installed external JAR;
- UAT-008/009/024;
- zero core edits verified by diff/fitness;
- same-SHA focused PipelineK plugin gate.

## Gate M4

- UAT-010/011;
- baseline identity stability;
- M-B01 muerto.

## Gate M5

- malformed SARIF/JUnit reports fail typed;
- heuristic/deterministic distinction;
- cross-provider source locations.

## Gate M6

- Chronos window artifact deterministic;
- loss/gap UAT;
- no timestamp-based window approximation.

## Gate M7

- UAT-012..016;
- M-P01/P02/P03/M-C01 muertos;
- crash/restart/replay behavior documentado;
- body failure precedence certified.

## Gate M8

- UAT-017/018;
- M-O01/M-I01 muertos;
- trace/span IDs remain typed/external.

## Gate M9

- UAT-020/021;
- same suite/report parity;
- CLI affordance traversal contract.

## Gate M10

- self-host release argument;
- at least one negative fixture per promoted advanced lens;
- no heuristic mandatory gate without explicit admission.

## Gate M11

- full same-SHA suite;
- supply chain artifacts;
- installed distribution UAT;
- compatibility matrix;
- performance budgets based on measurements, not guessed.
