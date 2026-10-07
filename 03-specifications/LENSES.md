# SPEC — Lenses

## Definition

Una lens es una proyección pura y tipada de evidence a una vista adecuada para assertions.

## Catálogo inicial

### `HexagonalArchitectureLens`

Inputs: module/symbol dependency facts.  
Output: layers, edges, boundary crossings, SCCs, witnesses.

### `ConnascenceLens`

Static: name/type/meaning/position/algorithm.  
Dynamic: execution/timing/value/identity, siempre con autoridad RuntimeObserved salvo prueba adicional.

### `SeamLens`

Ports, constructor/function injection, process boundaries, filesystem/network seams, plugin boundaries.

### `SmellLens`

Deterministic smells separados de heuristic smells.

### `SolidLens`

- DIP puede apoyarse en structural facts y ser assertion fuerte.
- ISP puede producir métricas/relations.
- SRP/OCP suelen producir signals; no gate duro por defecto.
- LSP puede enriquecerse con runtime/property evidence.

### `ObservedArchitectureLens`

Projection sobre invocations/runtime relations.

### `ObservabilityLens`

Spans, logs, links, external calls, context propagation.

### `TestTopologyLens`

Relaciona symbols -> tests -> assertions -> coverage -> mutants -> runtime paths.

### `ConsistencyLens`

Compara DeclaredArchitecture / StructuralArchitecture / ObservedArchitecture y detecta contradicciones.

## Contraejemplos

Cada lens debe poder proporcionar witnesses mínimos cuando sea factible: shortest forbidden path, SCC minimal cycle, causal slice, surviving mutant.
