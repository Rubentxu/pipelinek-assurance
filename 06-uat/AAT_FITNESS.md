# Architecture Acceptance Tests / Fitness Functions

1. `assurance-domain` no depende de PipelineK, filesystem, network, coroutine runtime ni CLI.
2. `assurance-engine` no depende de provider implementations.
3. `pipelinek-assurance-plugin` es único módulo que depende de PipelineK SDK.
4. CogniCode adapter no importa internals de CogniCode; sólo schema/artifact contract.
5. Chronos adapter idem.
6. Ningún `EvidenceProvider` retorna `AssertionResult`.
7. Ninguna `Lens` escribe filesystem/network.
8. `AssertionResult` exhaustive; no boolean shortcut.
9. `Hypothesis` no puede construirse como deterministic Fact por API pública.
10. PipelineK core no contiene `assurance.*` concrete keys.
11. `assurance.verify` no itera `StepNode` ni importa application coordinator.
12. No existe dependencia MCP en runtime production path.
13. OTel/Chronos/PipelineK/CogniCode IDs tienen tipos diferentes.
14. Reports grandes no se emiten como event payload.
15. Agent CLI no reimplementa run follow; delega navegación de runs a PipelineK.
16. Suite IR serializer has canonical map/set ordering.
17. No production `System.currentTimeMillis()` en functional core.
18. Baseline suppression exige stable finding id.
19. Heuristic evidence no puede satisfacer assertion que requiere Deterministic sin explicit coercion/admission.
20. `no evidence` branch cannot construct `Passed`.
