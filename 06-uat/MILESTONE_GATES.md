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

**Estado: CERRADO (2026-10-08, `c60c156` y siguientes).** 236 tests,
0 failures. UAT-003, UAT-004, UAT-005, UAT-022 ejecutados con self-model
sintético. M-A01, M-A02, M-A03, M-H01 muertos y certificados (ninguno
por un único test). AAT-7, AAT-19 verdes. Evidencia detallada en
`ROADMAP.md` §M1.

## Gate M2

**Estado: CERRADO en estructura (2026-10-09, SHA `023f666`, `2bd529f`,
`ee536a6`).** Export sintético `assurance-evidence/v1` consumido por
`CogniCodeArtifactProvider`. Paridad diferencial certificada por
`M2DifferentialProofTest`. Self-hosting S2 cubierto por la pipeline
in-test.

**UAT pendientes por bloqueador externo:**

- UAT-006, UAT-007 requieren export real de CogniCode
  (`producerId=cognicode`).

**AAT-6 verde por construcción (SPI + provider).** M-E01/M-E02 se
re-ejecutarán en M2-T9 con export real.

## Gate M3

**Estado: CERRADO en estructura (2026-10-09, SHA `06a916b`).** Plugin
integrado con `dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
real del SDK. ServiceLoader discovery verificado. AAT-3, AAT-10, AAT-11,
AAT-12, AAT-14 verdes.

**UAT pendientes por bloqueador externo:**

- UAT-008, UAT-009, UAT-023, UAT-024 requieren host con SDK de PipelineK
  instalado. La ley "cero ediciones en el core" se cumple por
  construcción (este repo no contiene el core).

## Gate M4

**Estado: CERRADO localmente (2026-10-09, SHA `53385b0`).** `DiffEngine`
con `FindingId` estable, idempotente, cinco estados
(`NEW`/`EXISTING`/`RESOLVED`/`REGRESSED`/`CHANGED`). AAT-18 verde.

**Pendiente declarado:** M-B01 declarado en catálogo, **lógicamente
cubierto** por la lógica del engine, **no certificado** por
`tools/certify_mutants.py` (harness actual solo cubre M0/M1). Ampliación
del harness a M4+ es trabajo de M11 segundo pase.

**UAT pendientes por bloqueador externo:**

- UAT-010 requiere baseline versionada con deuda intencional.
- UAT-011 está cubierto por la lógica (`isExpired(now)`); la
  verificación E2E con tiempo real es trabajo de M11.

## Gate M5

**Estado: CERRADO localmente (2026-10-09, SHA `9ae04fd`).** Tres UAT
nuevos registrados por la obligación del gate (UAT-026, UAT-027, UAT-028).
Todos cubiertos por los tests de los providers.

- UAT-026: `DetektSarifProviderTest.malformed_sarif_returns_decoded_failure_with_typed_error`.
- UAT-027: `JUnitXmlProviderTest.malformed_junit_returns_decoded_failure_with_typed_error`.
- UAT-028: `source_locations_are_stable_between_providers`.

**Pendiente declarado:** UAT-019 (mutation strength) requiere adapter
de mutación, trabajo de M9+.

## Gate M6

**Estado: BLOQUEADO por export externo (2026-10-09, SHA `7cb0ee4`).**
Adapter completo; UAT-033 (window token is required) registrado y
cubierto por la lógica. La verificación end-to-end con export real
queda para cuando Chronos esté disponible.

**UAT pendientes:** UAT-016 requiere export real; lógica cubierta.

## Gate M7

**Estado: CERRADO en estructura (2026-10-09, SHA `06a916b`).** Matriz
body × assurance cubierta por `AssuranceVerifyStepTest` (6 filas). M-P01
y M-P02 certificados por la matriz; M-P03 y M-C01 **lógicamente
cubiertos** pero pendientes del harness de mutantes.

**UAT pendientes por bloqueador externo:**

- UAT-012, UAT-013, UAT-014, UAT-015, UAT-025 requieren run real con
  `BodyContinuation` ejecutándose en host con SDK.

## Gate M8

**Estado: BLOQUEADO por export externo (2026-10-09, parte de `ae2272f`).**
Adapter y tipos completos. M-O01 conceptualmente muerto; M-I01 muerto
por construcción (value classes con tipos distintos, AAT-13).

**UAT pendientes:** UAT-017, UAT-018 requieren collector OTel en vivo.

## Gate M9

**Estado: CERRADO localmente (2026-10-09, SHA `ce2866f`).**
`MultiRunnerAssertions` con los 5 veredictos por ambas vías (Kotest y
JUnit). Paridad de digest cubierta.

**UAT pendientes por bloqueador externo:**

- UAT-020, UAT-021 requieren agente recorriendo envelope de fallo en
  proceso real. La lógica está cubierta por `CliDispatchTest`.

## Gate M10

**Estado: CERRADO (2026-10-09, SHA `745c2d7` + `ae2272f`).** 5 lenses
avanzadas. Self-hosting S6 ejecutado: el `assure report` corre contra el
propio repo y reproduce el digest de M1. Cuatro UAT nuevos registrados
(UAT-029, UAT-030, UAT-031, UAT-032), cubiertos por los tests de las
lenses.

**Pendiente declarado:** cinco mutantes nuevos (uno por lens)
declarados en `MUTATION_CATALOG.md`, pendientes del harness de
certificación.

## Gate M11

**Estado: CERRADO en estructura (2026-10-09, SHA `7b14548` + `029bfca`).**
Scripts `install.sh`, `tools/generate-sbom.sh`,
`tools/measure-performance.sh`. CI workflow verde. SBOM CycloneDX 1.5
con 5 componentes y SHA-256. Baseline 42–54s, 342 tests.

**Lo que queda declarado como bloqueador (no deuda oculta):**

- Matriz de compatibilidad con SDK de PipelineK (requiere host con
  SDK concreto).
- Checksums firmados y provenance (firma GPG/Cosign del SBOM).
- Performance budgets formales con umbral (baseline capturado; umbral
  requiere fixtures de carga).
- Certificación de crash y replay E2E.
- Repos de ejemplo externos con deuda intencional y runtime data.
