# SPEC — CLI agent-first (`assure`)

## Propósito

Explorar artifacts de assurance offline. No reemplaza `pipelinek observe`.

## Comandos

```text
assure capabilities
assure providers
assure snapshot inspect <ref>
assure suite inspect <ref>
assure report <ref>
assure findings <ref>
assure explain <finding>
assure evidence <finding>
assure evidence path <finding>
assure reproduce <finding>
assure diff <base> <current>
assure baseline inspect <name>
assure next <resource-ref>
```

## Envelope

JSON machine output:

```json
{
  "apiVersion": "assurance.pipelinek.dev/v1",
  "kind": "AssertionFailure",
  "subject": "...",
  "data": {},
  "completeness": {},
  "provenance": {},
  "actions": [
    {"rel":"counterexample","command":"assure evidence path finding-123"},
    {"rel":"reproduce","command":"assure reproduce finding-123"}
  ]
}
```

## HATEOAS-like law

Toda respuesta machine-readable que represente un recurso actionable incluye relaciones/comandos válidos para seguir explorando, derivados del capability registry, no hardcodeados por el agente.

## Sin MCP

El agente usa CLI/jsonl/exit codes. No se necesita daemon ni transport conversacional para el path crítico.
