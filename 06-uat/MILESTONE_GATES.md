# Gates por hito

## Gate M0

- domain/api tests verdes;
- property laws verdes;
- M-E01/M-E02/M-R01 muertos;
- no I/O dependency fitness;
- canonical golden corpus estable.

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
