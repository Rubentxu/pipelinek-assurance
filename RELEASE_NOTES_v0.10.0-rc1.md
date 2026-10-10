# Release Notes — v0.10.0-rc1 (draft, B+B+C+D+E+F in-repo)

> **Status:** Draft. No se tagea hasta que **B1** (SDK PipelineK
> v0.48.0-rc2) esté verde. La rama `consolidation/A-F` contiene
> todo el trabajo en-repo de los 6 bloques.

## Resumen

Tramo de consolidación A-F sobre `consolidation/A-F` (16
commits sobre v0.9.5-rc1, 589 tests verdes). El core gana un
gate basado en per-assertion enforcement (no counter), un
orchestrator de 10 pasos, semántica completa de diff con
5 estados, motor de ratchets, fachada Kotlin DSL para
`assurance.check`, y cobertura de fallos/crash/replay,
auditoría de seguridad y revisión arquitectónica.

## Cambios por bloque

### Bloque A — Integridad semántica y CI reproducible (v0.9.5-rc1)

- **A1** `evaluateEnforcement` pura en el engine, reemplaza
  el gate counter-based con un veredicto per-assertion que
  preserva la asociación assertion ↔ policy.
- **A2** Algebra de completitud cubre las 4 formas
  (Complete / Partial / Unknown / Unsupported) por
  capability, no solo 2.
- **A3** `EvidenceNormalizer` movido del testkit a la
  frontera de producción (`assurance-providers`). Digest
  por contenido (no por cardinalidad), `subjectRevision`
  real propagado, capabilities con cero resultados marcadas
  como `Unknown`.
- **A4** `tools/certify_mutants.py` endurecido: 4 veredictos
  tipados (DEAD/UNDER_KILLED/SURVIVED/INFRA_FAILED), recibos
  JSON por mutante, limpieza de reports stale, distinción
  entre gradle exit != 0 (test killed) y fallo de infra.
- **A5** CI: `osv-scanner-action` v2.6.0, SBOM CycloneDX
  generado dentro del job, performance budget
  (`tools/measure-performance.sh`) con min-tests como error.

### Bloque B — Primer plugin PipelineK ejecutable (B1 BLOQUEADO)

- **B2** `AssuranceOrchestrator` con los 10 pasos del
  contrato (`assurance.check`): resolver suite, validar
  refs, seleccionar providers, recolectar, normalizar,
  congelar registries, evaluar, codificar, publicar,
  devolver resultado tipado. 9 tests.
- **B3** `assurance.check` real end-to-end:
  - `BuiltinLens` (object) y `BuiltinAssertion` (class)
    para la regla hexagonal (Domain → Adapters prohibido).
  - `AssuranceCheckStepDefinition.run` overload que delega
    al orchestrator (no sólo construye DTOs sin ejecutar).
  - 8 E2E tests del orchestrator + 4 del step handler.
- **B3 DSL** `AssuranceCheckStepDsl` fachada Kotlin:
  `assuranceCheck { suite(ir); evidence { ref(...) }; mode; }`
  produce el `Input` DTO canónico. 8 tests.
- **B4** Contrato del report artifact: roundtrip con digest
  estable, escritura determinista, rechazo de incompleto
  (digest alterado, bytes truncados, garbage). 7 tests.

### Bloque C — Estático, baselines y ratchets (C1 BLOQUEADO)

- **C2** `PitestMutationProvider` distingue mutaciones en
  la misma línea por `mutator` (resuelve colisiones de
  ID). 4 tests nuevos (colisión, identidad, parcial,
  inválido).
- **C3** `DiffEngine` clasifica los 5 estados:
  - NEW: assertionId no en baseline
  - EXISTING: assertionId en baseline, fingerprint idéntico
  - CHANGED: mismo assertionId, fingerprint distinto
    (la violación se movió)
  - RESOLVED: estaba en baseline, ya no aparece
  - REGRESSED: estaba en baseline con `expires` vencido
- **C4** `RatchetEngine` con `RatchetPolicy`
  (`forbidNew`/`forbidRegressed`/`forbidChanged`/
  `noNewCycles`/`maxUnresolvedCount`/`maxCyclesCount`) y
  `RatchetException(owner, rationale, expires)`. 12 tests.

### Bloque D — Runtime (D1, D2, D3 BLOQUEADOS)

- **D5** `D5FailureModesTest`: 7 tests cubriendo fallo del
  observer (provider throw → gap Lost), pérdida de evidencia
  (provider Lost gap → Inconclusive), crash en handler
  (BodyContinuation throw → BodyOutcome.Failure), crash en
  normalizer (producer miente → `OrchestrationResult.Failed`),
  e interrupción al publicar (`evaluationId` con prefijo
  `eval-failed-` para que el caller no publique report
  parcial).

### Bloque E — Agent-first, packs y runners

- **E1** CLI agent-first: 3 comandos nuevos en el
  registry (capabilities, providers, next). 7 tests.
- **E2** `assurancePack { suite { ... } }` DSL para autoría
  de IR; `Rule.Mandatory`/`Touched`/`NewFindings` bridgean
  a `RequiredAssurancePlan`.
- **E3** Paridad de report entre JUnit Platform, Kotest y
  Step PipelineK: digest canónico estable a través de
  invocaciones. 6 tests.

### Bloque F — Certificación de producción

- **F2** Recibo de tests firmado por SHA:
  `tools/collect-test-receipt.py` produce JSON con
  `schemaVersion`, `commit`, `engineVersion`, `testCounts`,
  `modules`, `digest` (SHA-256). Digest estable a
  regeneraciones. Verificado por
  `tools/test_collect-test-receipt.sh`.
- **F3** Benchmarks del engine: lens sintética proyecta
  1k modules en <2s (medido 79ms), 10k en <30s (medido 5s),
  codificación de 500 failed en <2s (medido 24ms). 4 tests.
- **F4** Auditoría de seguridad: no deserialización
  arbitraria, límites de entrada y memoria, protección
  CBOR, integridad de manifests y digests, trust/provenance,
  Failed != Produced. 9 tests.
- **F5** Revisión arquitectónica: functional core puro,
  engine sin imports de providers/plugin, plugin sin
  `pipeline-application`, IDs tipados, cancelación como
  sealed interface, plugin no escribe journal, sin
  dependencia MCP. 9 tests.

## Verificación

- **589 tests verdes** (reciente recibo:
  `build/test-receipts/98a8bdac7b74.json`).
- **44/44 mutantes certificados** (cert v0.9.5-rc1).
- **16 commits** sobre v0.9.5-rc1, rama
  `consolidation/A-F` pusheada a `origin`.

## STOP gates (heredados del plan)

- B1 sin SDK real → no se tagea v0.10.0-rc1
- C1 sin CogniCode real → no se tagea v0.11.0-rc1
- D1/D3 sin Chronos/OTel real → no se tagea v0.12.0-rc1
- F1 sin matriz SDK → no se tagea v1.0.0-rc1

## Cómo promover

```bash
git checkout main
git merge --no-ff consolidation/A-F
git tag -s v0.10.0-rc1 -m "v0.10.0-rc1: A-F in-repo consolidated"
git push origin main v0.10.0-rc1
```

(Requiere que B1 esté verificado antes de promover; el
release notes se ajusta cuando el SDK real esté disponible.)
