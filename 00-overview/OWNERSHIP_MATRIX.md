# Matriz de ownership por repositorio

| Capacidad | pipelinek-assurance | PipelineK | CogniCode | Chronos |
|---|---|---|---|---|
| Orquestación de stages/steps | consume | **owns** | no | no |
| Journal/replay/recovery | consume | **owns** | no | no |
| Body execution | usa `BodyContinuation` | **owns** | no | no |
| AssuranceSuite DSL | **owns** | no | no | no |
| Evidence canonical IR | **owns** | no | export adapter | export adapter |
| Static symbols/dependencies | consume | no | **owns** | no |
| Static smells/heuristics | consume | no | **owns** | no |
| Runtime invocations/causality | consume | no | opcional | **owns** |
| OTel correlation | modela refs | no | puede consumir | **owns/adapter** según roadmap |
| Assertion verdict | **owns** | transports outcome | no | no |
| Counterexample model | **owns** | artifact/event transport | aporta witnesses | aporta causal slices |
| Run observation | consume | **owns** | no | no |
| Offline assurance CLI | **owns** | no | no | no |
| Agent explanation | deterministic first | run context | optional | optional |

## Regla de core changes en PipelineK

Un cambio en `pipeline-kotlin` sólo se permite si:

1. cierra un seam genérico aplicable a cualquier plugin;
2. tiene UAT independiente de assurance;
3. no introduce `assurance` en core/compiler/coordinator;
4. mantiene el patrón open registry;
5. se integra primero en PipelineK y después se consume desde el plugin.

Si el plugin puede resolverlo con los contratos públicos existentes, no se toca PipelineK.
