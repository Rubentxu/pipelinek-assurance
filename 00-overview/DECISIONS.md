# Decisiones cerradas y preguntas falsables

## Cerradas

### D1 — Plugin externo real

El producto vive en repositorio separado y entra en PipelineK exclusivamente por los seams públicos de plugin. El core de PipelineK no aprende nombres `assurance.*`.

### D2 — Assertion no es Step

Una assertion es dato puro/evaluable. Los Steps son unidades durables de ejecución, efectos y lifecycle. Nunca se representa una suite de cientos de rules como cientos de Steps.

### D3 — Tres Step families iniciales

`assurance.check`, `assurance.verify`, `assurance.diff`.

### D4 — `assurance.verify` es body-owning

Usa `RegistryBlockSpec` + `BodyContinuation`. No itera hijos directamente, no conoce `StepNode`, no inventa OpIds y no reimplementa retry/recovery.

### D5 — Functional core / imperative shell

El core de assurance no hace I/O, no usa reloj global, no llama procesos, no habla con red y no lee workspace. Providers/adapters producen evidence; el engine proyecta/evalúa.

### D6 — Epistemología explícita

`Fact`, `Observation`, `Signal`, `Hypothesis` son categorías distintas. La autoridad y la completitud son dimensiones ortogonales.

### D7 — `no evidence != PASS`

Resultados: `Passed`, `Failed`, `Inconclusive`, `Unsupported`, `Error`.

### D8 — CogniCode/Chronos no deciden el gate

Ambos exportan evidencia. La suite de assurance decide. Un detector heurístico no adquiere autoridad por provenir de CogniCode.

### D9 — No MCP como seam

Integración reproducible por CLI + artefacto versionado/content-addressed. MCP puede seguir existiendo para UX de otros productos.

### D10 — CLI agent-first separado de `pipelinek observe`

`pipelinek observe` sigue siendo la superficie de runs. `assure` explora artifacts/snapshots/reports offline y devuelve affordances autodescubribles.

### D11 — JUnit/Kotest son adapters

La suite canónica no depende del runner. Puede ejecutarse en PipelineK, JUnit Platform, Kotest y testkit puro.

### D12 — Self-hosting progresivo

El sistema empieza a probar su propia arquitectura en cuanto M2 produzca snapshots deterministas; no se espera a production-ready.

## Preguntas que requieren spike

### Q1 — Dónde delimitar exactamente la ventana Chronos de `assurance.verify`

Debe existir un handshake explícito start/seal que no dependa de tiempo de pared. Si Chronos no puede delimitar una sesión/segmento por token durable, se añade un export seam antes de promover runtime assurance.

### Q2 — Qué output de CogniCode es suficiente como Evidence v1

No asumir que los DTO MCP actuales son contrato. Debe definirse export `assurance-evidence/v1` con facts, provenance, completeness y stable ids.

### Q3 — Necesidad de `assurance.snapshot` Step separado

Se difiere hasta demostrar reutilización real entre múltiples checks. V1 permite que `assurance.check` normalice inputs y produzca snapshot/report como outputs tipados.

### Q4 — Event contributor del plugin

Si S6 Event Plugin SDK está integrado y certificado cuando se implemente M5, usarlo. Si no, no inventar bypass: bloquear esa parte y mantener report artifact/typed Step output.

### Q5 — Reglas runtime que necesitan instrumentación activa

`assurance.verify` no debe convertirse en un profiler. Si una lens necesita instrumentación, el provider (Chronos/OTel) la gobierna y declara capabilities/limitations.
