# Patrones de referencia a conservar

## Detekt

- extensibilidad de rules;
- type-aware analysis;
- baseline;
- no reimplementar rules existentes.

## ArchUnit

- architecture as tests;
- layers/slices/onion;
- freezing;
- witness de dependency rules.

## CogniCode

- facts/provenance/snapshot;
- graph algorithms;
- executable architecture/admission;
- separar deterministic de heuristic.

## Chronos

- ExecutionLog/session;
- replay/cursor/gaps;
- No Silent Lies;
- causal/runtime evidence;
- counterexamples.

## OpenTelemetry

- trace/span/link/event/resource;
- correlation ids;
- no asumir parent-child como única causalidad.

## Kotest

- Kotlin DSL/BDD ergonomics;
- property testing/shrinking.

## JUnit Platform

- discovery/execution standard;
- IDE integration.

## PIT

- mutation as strength of tests.

## jQAssistant / CodeQL / Semgrep

- scan -> model -> query/constraint;
- witness paths/data flow;
- source/sink/propagator vocabulary.

## Diferenciación buscada

No copiar ninguna herramienta individual. El valor nuevo es la composición tipada y epistemológicamente honesta dentro de un execution engine durable.
