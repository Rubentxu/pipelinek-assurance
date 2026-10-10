# Certificación del catálogo de mutantes — 2026-10-10

**SHA certificado:** `c3df2a7` (cierre Bloque A — v0.9.5-rc1)
**Tag:** `v0.9.5-rc1`
**Comando:** `python3 tools/certify_mutants.py <mutante>` por cada uno,
secuencialmente, sobre el mismo SHA.

## Resultado global

**44/44 mutantes certificados** con el harness endurecido de A4.

Cada mutante tiene un **recibo JSON** en `build/mutant-receipts/<name>.json`
con SHA, timestamp, veredicto tipado, killed_by, total_tests, gradle_exit_code
y un tail del log de infraestructura si la hubo.

## Veredictos

| Veredicto | Significado | Count |
|---|---|---|
| DEAD | killed >= 2 (gate verde) | 44 |
| UNDER_KILLED | killed == 1 (redundancia insuficiente) | 0 |
| SURVIVED | killed == 0 (gate failure) | 0 |
| INFRA_FAILED | Gradle no levantó la suite | 0 |

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
