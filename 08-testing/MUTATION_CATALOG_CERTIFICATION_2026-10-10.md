# Certificación del catálogo de mutantes — 2026-10-10

**SHA certificado (Bloque A):** `c3df2a7` (cierre Bloque A — v0.9.5-rc1)
**SHA certificado (Bloque G):** `6084fcb` (re-cert G7)
**Tag:** `v0.9.5-rc1` (Bloque A); tag de v0.13.0-rc1 pendiente (G9)
**Comando:** `python3 tools/certify_mutants.py <mutante>` por cada uno,
secuencialmente, sobre el mismo SHA.

## Resultado global

**Bloque A (2026-10-10):** 44/44 mutantes certificados (44 DEAD).
**Bloque G (re-cert G7):** 43 mutantes certificados:
- 40 DEAD (killed ≥ 2)
- 3 INFRA_FAILED (mutaciones que rompen la compilación; el
  harness no puede certificarlas ni rechazarlas)
- 0 UNDER_KILLED (M-CODEC01 y M-10-CONTENT cerrados con tests
  redundantes; ver `odd/tasks/block-G-G6-status.md` §G7)
- 0 MUTATION_NOT_APPLIED (M-B01 y M-NORM-01 cerrados con fix de
  patrón; el cert ahora distingue este caso con un quinto
  veredicto)
- 0 SURVIVED (gate verde)

Cada mutante tiene un **recibo JSON** en `build/mutant-receipts/<name>.json`
con SHA, timestamp, veredicto tipado, killed_by, total_tests, gradle_exit_code
y un tail del log de infraestructura si la hubo.

## Veredictos

| Veredicto | Significado | Count (G7) |
|---|---|---|
| DEAD | killed >= 2 (gate verde) | 40 |
| INFRA_FAILED | Gradle no compiló con la mutación aplicada | 3 |
| UNDER_KILLED | killed == 1 (redundancia insuficiente) | 0 |
| SURVIVED | killed == 0 (gate failure) | 0 |
| MUTATION_NOT_APPLIED | patrón no encontrado en la fuente (G7 fix) | 0 |

## Detalle por mutante

(Re-ejecutar `python3 tools/certify_mutants.py <name>` para
regenerar este detalle y el recibo JSON asociado. El script
ya no resume la tabla; los recibos son la fuente de verdad.)

## Contexto histórico

- **v0.8.0 (2026-10-10):** 32/32 mutantes certificados.
- **v0.9.0 (post-audit):** 38/38 mutantes. P0 audit findings 1-6 cerrados.
- **v0.9.1:** 40/40. Capabilities refactor (5 providers), M-CAP-DRIFT-2.
- **v0.9.2:** 41/41. API_VERSION refactor + codec tests redundantes.
- **v0.9.3:** 42/42. M2-T8 EvidenceNormalizer (M-NORM-01).
- **v0.9.4:** 44/44. M-CHRONOS-BOUNDED y M-OTEL-BOUNDED con redundancia.
  AAT-02/04/05/07/11/14/15/18 convertidas a fitness tests ejecutables.
- **v0.9.5-rc1 (Bloque A cerrado):** 44/44 con recibos JSON
  persistidos y cuatro veredictos tipados en el harness.

## Lo que cambió en este ciclo (A4)

- `tools/certify_mutants.py` endurecido:
  - **Limpieza de informes stale** al inicio de `run_tests()`.
  - **Exit code de Gradle capturado** y distinguido de
    "mutante mató un test" (test rojo = exit 1 ≠ infra fail).
  - **Cuatro veredictos tipados**: DEAD / UNDER_KILLED / SURVIVED / INFRA_FAILED.
  - **Recibo JSON por mutante** en `build/mutant-receipts/<name>.json`
    con SHA, timestamp, veredicto, killed_by, gradle_exit_code.
  - **Restauración endurecida en finally**: try/except anidado
    para evitar dejar archivos `.bak` aunque el proceso crashee.
  - **Helper `current_sha()`** ancla cada recibo al HEAD.

## Lo que cambió en este ciclo (G7 — Bloque G)

- **Quinto veredicto** `MUTATION_NOT_APPLIED`: cuando el patrón
  de mutación no se encuentra en la fuente actual, el cert ya
  no escribe un recibo `verdict: DEAD, killed_count: 0` (falso
  positivo); escribe `verdict: MUTATION_NOT_APPLIED`. El
  default del verdict en el harness cambió de `DEAD` a
  `MUTATION_NOT_APPLIED`. La distinción es crítica: un
  mutante que NO se aplicó NO mató tests, pero tampoco
  sobrevivió — requiere actualizar el patrón.
- **Fix de patrón M-B01**: `baselineById[id]` →
  `baselineByAssertionId[id.assertionId]` (el código se renombró
  en M4).
- **Fix de path M-NORM-01**: `assurance-engine/.../EvidenceNormalizer.kt`
  → `assurance-providers/.../EvidenceNormalizer.kt` (el
  normalizer se movió en M2).
- **Tests redundantes**:
  - M-CODEC01: `plan_con_digest_recalculado_tras_modificar_selecciones_se_rechaza`
    en `assurance-artifact/testkit`.
  - M-10-CONTENT: `M_10_CONTENT_redundante_name_finding_reporta_strength_2_y_subject_no_vacio`
    en `assurance-testkit`.

Resultado del cert G7: **40 DEAD, 3 INFRA_FAILED, 0 SURVIVED**.
Ver `odd/tasks/block-G-G6-status.md` §G7 para el detalle.

## Lo que cambió en este ciclo (A1-A3 + A5)

- **A1**: `AssuranceEngine.evaluateEnforcement` reemplaza el gate
  counter-based en `AssuranceCheckStep.run` con una función pura
  que cruza cada `AssertionResult` con su `enforcement` del IR.
 13 tests nuevos.
- **A2**: `EvidenceSnapshot.overallCompleteness` ahora cubre
  las 4 formas (Complete/Partial/Unknown/Unsupported) sin
  silenciar Unknown/Unsupported a Complete. 16 tests nuevos.
- **A3**: `EvidenceNormalizer` movido de `assurance-testkit` a
  `assurance-providers` (frontera de producción). 3 correcciones:
  digest del contenido (no de cardinalidad), propagación del
  `subjectRevision` real, capabilities con cero resultados como
  Unknown. 5 tests nuevos.
- **A5**: `osv-scanner-action` v1 → v2.6.0; SBOM generado dentro
  del job; "Verify osv scan completed"; perf script con
  min-tests-as-error (antes era WARNING silencioso); AGENTS.md
  actualizado al estado real (v0.9.4, M0..M11 cerrados, A-F
  en curso).
