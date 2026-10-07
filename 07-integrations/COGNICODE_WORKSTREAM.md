# Workstream CogniCode — adaptación para pipelinek-assurance

## Objetivo

Exponer la inteligencia estática existente como evidence versionada sin convertir CogniCode en componente del gate ni duplicar su graph.

## Estado aprovechable observado

CogniCode ya posee conceptos compatibles:

- Evidence kernel con `Fact` subject-predicate-object;
- `SnapshotId` y provenance;
- `ProducerKind` que separa deterministic analyzers, runtime observer, LLM, human;
- LLM output rechazado como extracted Fact;
- Tarjan/SCC y dependency graph deterministas;
- arquitectura ejecutable mediante constraints admitidos;
- signals/heurísticas para SOLID y smells;
- causal Intelligence Event Log en evolución.

## C0 — Freeze current semantics

Antes de modificar export:

- golden de facts para fixture multi-language;
- golden de architecture SCC/path;
- heuristic SOLID fixture documentado como Signal;
- stable ids/source locations actuales.

## C1 — `assurance-evidence/v1`

Nuevo contrato externo versionado. No reutilizar directamente DTO MCP.

Debe exportar:

```text
manifest
snapshot
entities
facts
relations
signals
source anchors
provenance
capability completeness
gaps
```

Formatos iniciales: canonical CBOR + JSON debug.

## C2 — CLI export

```bash
cognicode export assurance \
  --workspace . \
  --capability symbols \
  --capability dependencies \
  --capability architecture \
  --output build/cognicode-assurance.cbor
```

CLI devuelve digest/schema/version.

## C3 — Stable identity

Definir qué IDs sobreviven a comment shift, line move y rename. Si una clase de entity no tiene stable identity, marcar explicitamente limitation; no fabricar equivalencia.

## C4 — Completeness

Cada capability declara:

- Complete;
- Partial(gaps);
- Unsupported;
- Unknown.

Ejemplo: lenguaje soportado parcialmente no devuelve lista vacía como “sin cycles”.

## C5 — Heuristic demotion clarity

Outputs de `solid_audit`, god-function scoring u otros thresholds deben exportarse como `Signal`, con algorithm id/version/config/thresholds.

## C6 — Architecture constraints bridge

Las `ArchitectureConstraint` admitidas de CogniCode pueden exportarse como evidence/metadata y servir para generar suites, pero CogniCode no debe imponer el verdict dentro de assurance. Mantener authority lineage.

## C7 — Self-validation

CogniCode usa `pipelinek-assurance` para probar al menos:

- sus propias dependencias domain/application/infrastructure;
- no nuevos ciclos;
- evidence kernel sin presentation deps.

Esto no sustituye sus tests existentes; añade una segunda consumer proof del export.

## Cambios que NO hacer

- no eliminar MCP;
- no introducir PipelineK imports en CogniCode core;
- no convertir el export en llamada específica `pipelinek`;
- no meter AssuranceSuite DSL en Rust;
- no transformar heuristic signals en facts.
