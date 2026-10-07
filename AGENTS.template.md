# AGENTS.md template — pipelinek-assurance

## Governance

- SDDK obligatorio.
- `ROADMAP.md` única autoridad de secuenciación.
- completed = acceptance verified.
- surgical tests durante implementación; full suite en integración/release.
- commits atómicos Conventional Commits.

## Architecture laws

1. Functional core has no I/O.
2. Assertions are not Steps.
3. Providers do not gate.
4. `no evidence != PASS`.
5. Heuristic != deterministic.
6. External IDs never collapse.
7. PipelineK owns body execution/recovery/journal.
8. Plugin never imports `pipeline-application`.
9. No MCP on the deterministic gate path.
10. Every mandatory assertion needs a known failing fixture/mutant before promotion.

## Testing

- property tests for algebra/digests;
- mutation tests for semantic laws;
- UAT catalog IDs referenced by milestone receipts;
- full installed external-plugin UAT before release.

## Documentation

Historical/superseded docs -> `docs/history/`. Do not rewrite historical receipts to claim newer semantics.
