# Release Notes — v0.10.0-rc1 (tagged)

> **Status:** Tag `v0.10.0-rc1` creado localmente sobre
> `consolidation/A-F` (HEAD `efa6763`, 2026-10-10).
> **B1 cerrado** con SDK PipelineK 0.48.0 stable instalado
> en asdf y disponible en `~/.m2/repository/`. Push del tag
> y merge a `main` quedan como decisión del usuario.

## Resumen

Tramo de consolidación A-F sobre `consolidation/A-F` (29
commits sobre v0.9.5-rc1, 590 tests verdes). El core gana un
gate basado en per-assertion enforcement (no counter), un
orchestrator de 10 pasos, semántica completa de diff con
5 estados, motor de ratchets, fachada Kotlin DSL para
`assurance.check`, y cobertura de fallos/crash/replay,
auditoría de seguridad y revisión arquitectónica.

B1 integró el plugin contra el SDK real PipelineK 0.48.0:
`AssurancePluginContributor` implementa
`StepDefinitionContributor`, `ServiceLoader` lo descubre,
codecs tipados (`StepCodec<I,O>` con `EncodedStepValue`)
sustituyen al `List<Any>` falso.

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

### Bloque B — Primer plugin PipelineK ejecutable (B1 cerrado)

- **B1** Integración con SDK PipelineK 0.48.0 (stable):
  - `pipelinek-assurance-plugin/build.gradle.kts` declara
    `dev.rubentxu.pipeline.v2:pipeline-{domain,events,
    output,scripting-api}:0.48.0` vía mavenLocal() + flatDir
    sobre `~/.asdf/installs/pipelinek/0.48.0/lib/`.
  - `AssurancePluginContributor` implementa la interfaz
    `StepDefinitionContributor` del SDK (no más `List<Any>`).
  - `definitions: Iterable<StepDefinition<*, *>>` con
    `assurance.check` y `assurance.verify` (placeholder).
  - `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
    registra la clase; `ServiceLoader.load(...)` la descubre
    en el test
    `serviceloader_del_sdk_descubre_el_contributor`.
  - Codecs JSON (`StepCodec<I,O>`) envuelven los DTOs en
    `EncodedStepValue`; handler suspende vía `StepHandler`.
  - Matriz de compatibilidad inicial: `06-uat/SDK_COMPATIBILITY_MATRIX.md`
    certifica 0.48.0 stable. Versiones 0.48.0-rc1/rc2 y 0.49+
    marcadas como `❌` (no certificables todavía).
  - Local CI: `ci/assurance.pipeline.kts` corre con
    PipelineK 0.48.0 (no GitHub Actions).
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

- **590 tests verdes** (recuento sobre
  `*/build/test-results/*/TEST-*.xml` a 2026-10-10;
  re-confirmado el 2026-10-11 sobre HEAD `e3a2875`).
- **Cert de mutantes 43/43 ejecutados** sobre
  `e3a2875`; los recibos JSON en
  `build/mutant-receipts/*.json` son la fuente de
  verdad. Histograma real:

  | Veredicto | N | Mutantes |
  |---|---|---|
  | `DEAD` (killed ≥ 2) | **38** | ver `08-testing/MUTATION_CATALOG.md` § "Estado de cert" |
  | `UNDER_KILLED` | **2** | `M-10-CONTENT`, `M-CODEC01` (redundancia insuficiente) |
  | `INFRA_FAILED` | **3** | `M-D02`, `M-V01`, `M-V02` (hallazgos reales, codec frágil) |
  | `SURVIVED` | **0** | — |
  | **Total** | **43** | — |

  Los **5 mutantes con hallazgo no se cuentan como
  "certificados"**; cada uno tiene análisis en
  `odd/tasks/block-A-integrity.md` §
  "Annexo: cert post-cierre A". El bloque siguiente
  que los absorba es G
  (`block-G-pipelinek-real-integration.md`).
- **32 commits** sobre v0.9.5-rc1, rama
  `consolidation/A-F` (verificado: `git rev-list --count
  v0.9.5-rc1..HEAD` sobre `e3a2875` reporta 32).

## STOP gates (heredados del plan)

- C1 sin CogniCode real → no se tagea v0.11.0-rc1
- D1/D3 sin Chronos/OTel real → no se tagea v0.12.0-rc1
- F1 ya certificada para 0.48.0; el resto del matrix
  depende de B1 SDK upstream → no se tagea v1.0.0-rc1

## Cómo promover

```bash
git checkout main
git merge --no-ff consolidation/A-F
git push origin main v0.10.0-rc1
```

El tag `v0.10.0-rc1` ya está creado localmente (`git tag -l
v0.10.0-rc1`). El usuario decide push y merge.
