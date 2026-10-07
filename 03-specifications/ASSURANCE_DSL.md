# SPEC — Assurance DSL Kotlin

## Objetivo

Un DSL Kotlin declarativo, reutilizable fuera de PipelineK, que compile a `AssuranceSuiteIR` serializable y reproducible.

## Ejemplo

```kotlin
val architectureSuite = assuranceSuite("architecture") {
    requires {
        evidence(SymbolGraph)
        evidence(ModuleDependencies)
    }

    val hex = lens(
        hexagonal("hex") {
            domain("..domain..")
            application("..application..")
            adapters("..adapter..", "..infrastructure..")
        }
    )

    invariant("domain-purity") {
        hex.domain shouldNot dependOn hex.adapters
    }

    invariant("bounded-contexts-acyclic") {
        hex.contexts shouldBe acyclic
    }
}
```

## Propiedades

- suite construction es pura;
- no filesystem/network/process/clock;
- los helpers deben construir IR, no ejecutar análisis;
- stable namespaced ids para suite/lens/assertion;
- serialización canónica;
- IR digest independiente de orden no semántico de `Map`.

## Enforcement

Una assertion declara:

```kotlin
enforcement = Advisory | Mandatory | Ratchet
```

No mezclar con severidad:

```kotlin
severity = Info | Warning | Error | Critical
```

## Completeness

Cada assertion puede declarar requisitos:

```kotlin
requiresComplete(ModuleDependencies)
```

Si falta evidence:

- Mandatory + requireComplete -> Inconclusive -> gate fail-closed;
- Advisory -> Inconclusive reportable;
- nunca PASS.

## Heurísticas

Una assertion sobre `Signal` debe declarar explícitamente que acepta `HeuristicAnalyzer` como evidence authority. No se hereda authority por defecto.

## Reutilización

La misma `AssuranceSuiteIR` debe ser ejecutable por:

- pure testkit;
- JUnit adapter;
- Kotest adapter;
- `assurance.check`;
- `assurance.verify`;
- `assurance.diff`.
