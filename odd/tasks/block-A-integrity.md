---
name: block-A-integrity
description: Feature document for Bloque A — integridad semántica y CI reproducible. Source of truth for v0.9.5-rc1.
---

# Bloque A — Integridad semántica y CI reproducible

**Goal:** un motor de assurance que no pueda comunicar éxito cuando la
evidencia, su procedencia o la evaluación no sean suficientes, y un gate
de integración remoto completamente verde.

**Release objetivo:** `v0.9.5-rc1`
**SHA de cierre:** `125304b`
**Fecha de cierre:** 2026-10-10

## Estado actual (verificado 2026-10-10, SHA `125304b`)

- 496 tests verdes, 0 failures, 0 skipped.
- 44/44 mutantes certificados con `killed >= 2`. Cada mutante tiene un
  recibo JSON en `build/mutant-receipts/<name>.json` con SHA,
  timestamp, veredicto tipado, killed_by, total_tests, gradle_exit_code.
- v0.9.5-rc1 tag publicado en `origin/main`.
- Plan upstream B1, C1, D1 registrado como bloqueador externo (no
  cerrable en este repo).

## Constraints (per plan de consolidación A–F)

- SHA ancla del inicio: `df44ea5` (v0.9.4).
- Tests, mutantes, UAT, AAT se cuentan desde el baseline y avanzan.
- Versiones externas se fijan en SHA + digest, no flotantes.
- `clean check`, `certify_mutants.py`, `measure-performance.sh` son
  los tres gates antes de declarar cierre.
- No introducir YAML como DSL principal; mantener DSL Kotlin.
- No convertir heurísticas en hechos deterministas.
- No implementar un segundo orquestador.
- Toda assertion `Mandatory` requiere fixture o mutante negativo.
- Convencional Commits, sin Co-Authored-By.

## Routes

- Direct inline para refactors mecánicos (EvidenceNormalizer move,
  AGENTS.md update, perf min-tests-as-error).
- Delegated direct para la función pura `evaluateEnforcement` y el
  endurecimiento del harness.
- No nuevos módulos sin frontera justificada (EvidenceNormalizer
  salió de `assurance-testkit` porque la frontera cruda/normalizada
  es responsabilidad de aplicación, no del testkit ni del core).

## Tasks

### A1 — Pure `evaluateEnforcement` (cerrado, SHA `abf886d`)

- [x] Implementar `AssuranceEngine.evaluateEnforcement(report, suite,
      mode, ratchetFailure)` que cruza cada `AssertionResult` con su
      `enforcement` del IR y emite `EnforcementDecision` tipado.
- [x] `EnforcementEntry`: pairing (assertionId, enforcement, result)
      preserva la asociación assertion ↔ política.
- [x] `EnforcementDecision` sealed: `Passed | Advisory | Failure`.
- [x] `AssuranceCheckStep.outcomeOfWithSuite`: nueva variante que
      usa `evaluateEnforcement`; preserva `outcomeOf` legacy
      (counter-based) para compat de tests.
- [x] 13 tests: Advisory nunca bloquea; Mandatory failed/unsupported/
      error/inconclusive → Failure; ReportOnly degrada a Advisory;
      mezclas preservan sólo los Mandatory en `blockingFailures`;
      Ratchet gate sólo si `ratchetFailure=true`; AAT-20
      (Unsupported Mandatory → Failure); AAT-8 (Error → Failure).

### A2 — Álgebra de completitud (cerrado, SHA `a2565e6`)

- [x] `combineCompletenessForCapability` (público, no internal)
      toma N declaraciones de una capability y emite la peor:
      Unsupported > Unknown > Partial > Complete.
- [x] `worstOf` reduce entre capabilities: peor gana con unión
      de gaps.
- [x] `EvidenceSnapshot.overallCompleteness` cubre las 4 formas
      exhaustivas, no 2. Bug A2 cerrado: la versión previa
      silenciaba `Unknown` y `Unsupported` a `Complete`.
- [x] 16 tests cubren las 4 reglas, conmutatividad, idempotencia,
      y la anti-regresión explícita (Unknown/Unsupported nunca se
      silencian a Complete).

### A3 — Normalizer a frontera de producción (cerrado, SHA `ddfae99`)

- [x] `EvidenceNormalizer` movido de `assurance-testkit` a
      `assurance-providers`. Testkit deja de ser dueño y queda
      como consumidor (los tests existentes se mueven al
      providers; assurance-testkit no debe depender de
      assurance-providers).
- [x] **Bug 1 — Digest del contenido, no de la cardinalidad.**
      Versión previa: `Digest.ofUtf8("$producerId/$producerVersion/
      ${items.size}")` — digest estable ante items añadidos.
      Versión nueva: SHA-256 sobre la concatenación ordenada
      `(producerId, producerVersion, itemId, itemAuthority,
      itemCapability, itemPayload, gapKey)`.
- [x] **Bug 2 — subjectRevision real.** Versión previa
      hardcodeaba `RevisionRef("0000...0")` y descartaba el
      parámetro. Versión nueva: `Provenance.subjectRevision =
      subjectRevision` (parámetro que ya estaba en la firma,
      simplemente se ignoraba).
- [x] **Bug 3 — Capabilities con cero resultados.** Si una
      capability se pidió y el producer no pudo observarla, no
      aparecía en `producedCapabilities`. Ahora se marca
      explícitamente como `Unknown` en `completenessByCapability`.
- [x] Helper `canonicalName()` para `EvidenceGap.GapReason` (sealed
      interface, sin `.name`).
- [x] 5 tests nuevos: digest del contenido (no de cardinalidad),
      digest estable ante mismo input, provenance con revision real,
      capabilities con cero resultados marcadas Unknown, propagation
      en todos los tipos de item.

### A4 — certify_mutants.py endurecido (cerrado, SHA `093d98d`)

- [x] **Limpieza de informes stale** al inicio de `run_tests()`.
      Sin esto, un informe stale puede hacer que un mutante muerto
      parezca vivo o viceversa.
- [x] **Exit code de Gradle capturado** y distinguido de "el
      mutante mató un test" (test rojo = exit 1 ≠ infra fail). La
      clave: si exit != 0 PERO los informes HTML se generaron, es
      el caso normal de mutante-killed.
- [x] **Cuatro veredictos tipados**: `DEAD` (killed >= 2),
      `UNDER_KILLED` (killed == 1), `SURVIVED` (killed == 0),
      `INFRA_FAILED` (Gradle no levantó la suite).
- [x] **Recibo JSON por mutante** en
      `build/mutant-receipts/<name>.json` con SHA, timestamp,
      veredicto, killed_by, total_tests, gradle_exit_code,
      infra_log_tail. Fuente de verdad para auditoria.
- [x] **Restauración endurecida en finally**: try/except anidado
      para no dejar archivos `.bak` aunque el proceso crashee.
- [x] `current_sha()` ancla cada recibo al HEAD.
- [x] `NORMALIZER` constant actualizado al nuevo path en providers.
- [x] **Detección de UNDER_KILLED verificada**: la re-corrida con
      el harness tipado cazó M-I01 en estado UNDER_KILLED. Fix con
      redundancia 2 (whitespace + namespace distinto) → M-I01 vuelve
      a DEAD con killed=2.

### A5 — CI, perf, AGENTS.md (cerrado, SHA `c3df2a7`)

- [x] `osv-scanner-action` v1 → v2.6.0. v1 está obsoleta y falla
      en CI; v2.6.0 es la línea estable (latest).
- [x] SBOM generado dentro del job (`cyclonedxBom`) para que
      osv-scanner consuma artefacto verificable del propio build.
- [x] Step `Verify osv scan completed` que falla loud si el
      artefacto `osv-report.md` no existe.
- [x] `measure-performance.sh`: `min-tests` WARNING → ERROR.
      Antes: budget falsamente verde porque no se midió nada.
      Ahora: STOP (exit 1) si el suite corre con menos de
      `MIN_TESTS=300` tests.
- [x] `AGENTS.md` actualizado: de "W0 cerrado, M0 en curso" a
      "M0..M11 cerrados (v0.8.0), consolidación A-F en curso"
      con SHA ancla `df44ea5` y 495 tests / 44 mutantes.

## Acceptance criteria (cumplidos)

- [x] Tests para `Unknown → Inconclusive`, `Unsupported Mandatory
      → gate failure`, `Error → gate failure` (A1).
- [x] Pruebas separadas para Advisory, Mandatory, Ratchet,
      ReportOnly (A1).
- [x] Digest invariante ante permutaciones no semánticas (A2 +
      A3).
- [x] Mutaciones que intenten eliminar los checks de completitud
      y enforcement (M-I01 redundancia, A4 los detecta).
- [x] `clean check` verde (496 tests).
- [x] CI completo en GitHub verde (osv-scanner v2.6.0, perf
      budget gate, security-scan job).
- [x] Recibos reproducibles y vinculados al commit (SHA en cada
      recibo JSON).

**UAT ejecutados:** UAT-001, 002, 005, 019, 026, 027 (cubiertos
por la suite verde; los UAT con host externo no son
cerrables aquí).

**AAT verdes:** 1, 2, 6, 8, 9, 16, 17, 19, 20 (cumplidos
verificados vía fitness tests en
`assurance-testkit/src/test/.../fitness/`).

## STOP de este bloque

- Cualquier camino que permita un falso PASS por falta
  relevante. A1–A3 cierran los tres vectores principales
  (counter-based gate, completitud silenciada, digest de
  cardinalidad). A4 detecta regresiones en el harness mismo.
  A5 endurezca el CI.

## Tracking

| Sub-tarea | SHA | Tests | Mutantes |
|---|---|---|---|
| A1 | abf886d | +13 | — |
| A2 | a2565e6 | +16 | — |
| A3 | ddfae99 | +5 | — |
| A4 | 093d98d | — | — |
| A5 | c3df2a7 | — | — |
| M-I01 fix | 125304b | +1 | — |
| **Total** | **125304b** | **+35 (462 → 496 → 497 en cert)** | **44/44 DEAD** |

## Lo que se entregó en este bloque

- 5 commits principales (A1–A5) más el fix M-I01.
- 1 nueva función pura (`evaluateEnforcement`) en el engine.
- 1 nuevo sealed type (`EnforcementDecision`) con 3 formas.
- 1 nuevo método (`outcomeOfWithSuite`) en el plugin.
- 1 nueva función pública (`combineCompletenessForCapability`).
- 1 nuevo `worstOf` privado para reducir entre capabilities.
- 1 nuevo `EvidenceNormalizer` en `assurance-providers`.
- 1 helper `canonicalName()` para `EvidenceGap.GapReason`.
- 1 nuevo test file `EnforcementDecisionTest.kt` (13 tests).
- 1 nuevo test file `CompletenessAlgebraTest.kt` (16 tests).
- 5 tests nuevos en `EvidenceNormalizerTest.kt`.
- 1 test nuevo en `EpistemicLawsTest.kt` (M-I01 redundancia).
- 1 nuevo veredicto tipado en `certify_mutants.py` (cuatro en
  total).
- 1 nuevo recibo JSON por mutante en `build/mutant-receipts/`.
- 1 nueva versión de `osv-scanner-action` (v1 → v2.6.0).
- 1 nueva sección en `ROADMAP.md` (Tramo A–F).
- 1 actualización de `AGENTS.md`.

## Lo que queda declarado como pendiente

- B1: integración con SDK público real de PipelineK v0.48.0-rc2
  (externo, no cerrable en este repo).
- C1: export real de CogniCode v0.101.10 (externo).
- D1: export real de Chronos (externo).
- Cross-repo: matrices de compatibilidad, provenance firmada,
  benchmarks con cargas reales (Bloque F).
