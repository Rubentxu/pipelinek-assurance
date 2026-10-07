# Primeros Work Packages

## WP-001 — Canonical Evidence Kernel

**Scope:** M0.  
**Output:** immutable ADTs, codecs, digest.  
**Mutants:** M-E01, M-E02, M-R01.  
**Exit:** UAT-001/002 verdes.

Commit families sugeridas:

```text
feat(domain): add epistemic evidence algebra
feat(artifact): add canonical evidence encoding
property(domain): certify evidence digest invariance
```

## WP-002 — Assurance Algebra

**Scope:** M0.  
**Output:** Lens/Assertion/Report algebra.  
**Exit:** no I/O deps; exhaustive result states.

## WP-003 — Hexagonal Static Vertical

**Scope:** M1.  
**Output:** graph fixture, HexagonalLens, no-dependency + acyclic assertions, minimal witnesses.  
**Mutants:** M-A01/A02.

## WP-004 — Agent CLI Minimal

Sólo:

```text
assure report
assure explain
assure evidence path
```

sobre fixtures. No capability explosion.

## WP-CG-001 — CogniCode Evidence Export Characterization

Repo CogniCode. Freeze outputs antes del nuevo export. Definir `assurance-evidence/v1` sin tocar MCP semantics.

## WP-CG-002 — CogniCode Exporter

CLI + CBOR/JSON + completeness/gaps.

## WP-005 — CogniCode Provider Differential

Repo assurance. Synthetic vs real exporter projection parity.

## WP-006 — External `assurance.check`

Añadir PipelineK dependency por primera vez. Installed distribution UAT; zero core edits.

## WP-007 — Baseline / Ratchet

Primera adopción real en repo con deuda conocida.

## WP-CH-001 — Chronos Window Contract Characterization

Repo Chronos. No implementación hasta demostrar qué primitive real delimita session/window.

## WP-CH-002 — Chronos Assurance Export

Export sealed runtime evidence con gaps.

## WP-008 — Body-owning `assurance.verify`

Sólo después de WP-CH-002.
