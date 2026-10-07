# UAT Catalog

## UAT-001 Deterministic snapshot

Misma evidence entrada en distinto orden físico produce mismo snapshot digest.

## UAT-002 Missing evidence is not success

Eliminar ModuleDependencies produce Inconclusive/FAIL según completeness policy; jamás PASS.

## UAT-003 Minimal forbidden dependency

Mutante `domain -> infrastructure` genera failure con witness path exacto.

## UAT-004 Minimal cycle

Mutante A->B->C->A devuelve SCC/ciclo reproducible.

## UAT-005 Heuristic authority isolation

Un SRP signal no falla una mandatory deterministic assertion salvo admisión explícita.

## UAT-006 CogniCode multilang evidence

Dos fixtures de lenguajes diferentes con misma estructura arquitectónica producen proyección equivalente.

## UAT-007 CogniCode completeness gap

Analyzer parcial declara gap; assurance no interpreta ausencia como cero violations.

## UAT-008 External plugin zero-core-edit

Instalar JAR de assurance sobre distribución PipelineK y ejecutar `assurance.check` sin modificar core registry.

## UAT-009 Mandatory static gate

PipelineK run para antes de deploy cuando assurance.check devuelve mandatory failure.

## UAT-010 Baseline freeze

Violaciones históricas conocidas -> EXISTING; nueva violación -> NEW y falla ratchet.

## UAT-011 Baseline expiry

Exception expirada deja de suppress.

## UAT-012 Body verify success

`assurance.verify { sh(test) }`: body success + suite pass => Step success.

## UAT-013 Body failure preservation

Body falla; suite también encuentra violation. Step outcome conserva body failure como primary y report secundario.

## UAT-014 Cancellation propagation

Cancelar ancestor durante body; assurance.verify no traduce cancelación a failure.

## UAT-015 Runtime forbidden edge

Chronos fixture introduce direct adapter-to-adapter call; ObservedArchitectureLens falla con causal slice.

## UAT-016 Runtime incomplete

Pérdida/gap en Chronos export -> Inconclusive.

## UAT-017 OTel missing span

External call sin span correlacionado -> fail con subject/source/correlation refs.

## UAT-018 OTel identity separation

TraceId igual textual a otro ID no causa colisión semántica.

## UAT-019 Mutation strength

Un mutante de evaluator que convierte Inconclusive en Passed debe ser matado por tests.

## UAT-020 JUnit parity

La misma suite produce mismo canonical report digest en pure runner y JUnit adapter.

## UAT-021 Agent discoverability

Desde `assure report` un agente puede seguir actions hasta counterexample y reproduce sin conocimiento previo de comandos.

## UAT-022 Self-host architecture

El repositorio `pipelinek-assurance` se analiza a sí mismo y valida domain/application/adapters.

## UAT-023 Self-host negative mutation

Introducir dependency prohibida en worktree de test hace rojo el assurance gate propio.

## UAT-024 Replay

Reejecutar `assurance.check` con mismos artifact digests usa/reproduce resultado sin recolección externa.

## UAT-025 Crash-safe artifact visibility

Report artifact sólo se publica como completo cuando canonical encoding/digest han terminado; partial no aparece como valid report.
