# Workstream PipelineK — seams a consumir y gaps permitidos

## Baseline

PipelineK ya demuestra:

- external atomic Step via `StepDefinitionContributor`;
- generic `RegistryStepSpec`;
- external body Step via `RegistryBlockSpec`;
- `HANDLER_CONTINUATION` + `BodyContinuation`;
- typed outputs/outcomes;
- open registry fail-closed;
- plugin manifests/capabilities.

## P0 — No core work by default

M0-M4 de assurance deben construirse sin cambios en PipelineK.

## P1 — Event SDK dependency

Cuando assurance necesite eventos custom, consumir Event Plugin SDK sólo si S6 está integrado/certificado. Si no, report typed output + artifacts son suficientes para continuar.

## P2 — Observation integration

Tras OBS-D/E del worktree de observación, validar filtros `--kind assurance.*`, jsonl y report refs. No introducir channel-specific hacks.

## P3 — Artifact/read ports

Si falta un modo genérico de adjuntar artifacts al Step output/event, cualquier evolución debe ser genérica y certificarse primero en PipelineK.

## Prohibiciones

- `when(stepKey == "assurance...")` en coordinator;
- assurance-specific `StepSpec` subtype;
- direct write al journal del plugin;
- plugin constructing OpIds;
- plugin traversing children directly.
