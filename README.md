# pipelinek-assurance — Blueprint de producto y ejecución

**Estado:** propuesta refinada lista para bootstrap de repositorio.  
**Rol:** plugin externo de PipelineK para software assurance determinista.  
**Principio rector:** `Evidence -> Lens -> Assertion -> Counterexample`, ejecutado y gobernado por PipelineK.

## 1. Qué es

`pipelinek-assurance` no es otro linter, otro Sonar, otro motor de CI ni otro framework de tests. Es un plugin externo de PipelineK que permite convertir evidencias estáticas y dinámicas en propiedades ejecutables y reproducibles de calidad de software.

PipelineK conserva la autoridad sobre:

- identidad de run/stage/step;
- lifecycle y journal durable;
- replay, retry, timeout y cancelación;
- capability admission;
- execution effects;
- Output Plane y Event Plane;
- `BodyContinuation` para blocks externos;
- `StepOutcome` y propagación de fallos.

`pipelinek-assurance` conserva la autoridad sobre:

- modelo de evidencia;
- snapshots y provenance;
- lenses puras;
- assertions puras;
- contraejemplos;
- suites reutilizables;
- diff/baselines/ratchets;
- informes agent-first.

## 2. Tres Steps iniciales

La superficie inicial se congela a tres familias:

1. `assurance.check` — **atomic Step**. Evalúa una `AssuranceSuiteIR` sobre evidencia ya disponible.
2. `assurance.verify` — **body-owning external Step**. Ejecuta un cuerpo mediante `BodyContinuation`, delimita una ventana de evidencia y verifica propiedades sobre lo que acaba de ocurrir.
3. `assurance.diff` — **atomic Step**. Compara baseline/current y evalúa ratchets semánticos.

No se modela cada rule, assertion, lens o detector como PipelineK Step.

## 3. DSLs separados

Hay dos lenguajes Kotlin deliberadamente distintos:

- **Pipeline DSL:** decide cuándo/dónde/con qué lifecycle se evalúa assurance.
- **Assurance DSL:** describe qué evidencia se requiere, qué lens se proyecta y qué assertion debe cumplirse.

Ejemplo:

```kotlin
val architectureSuite = assuranceSuite("architecture") {
    requires {
        evidence(ModuleDependencies)
        evidence(SymbolGraph)
    }

    lens(hexagonal {
        domain("..domain..")
        application("..application..")
        adapters("..adapter..", "..infrastructure..")
    })

    invariant("domain-purity") {
        architecture.domain shouldNot dependOn architecture.adapters
    }
}

pipeline {
    stages {
        stage("Architecture") {
            sh("cognicode export assurance -o build/cognicode.cbor")
            assuranceCheck(
                name = "architecture",
                suite = architectureSuite,
            ) {
                evidence {
                    cognicode("build/cognicode.cbor")
                }
            }
        }
    }
}
```

## 4. Runtime assurance

PipelineK aporta una capacidad diferencial: un plugin externo body-owning puede ejecutar hijos por el engine canónico mediante `BodyContinuation`.

```kotlin
verifyExecution(
    name = "runtime-contracts",
    suite = runtimeSuite,
) {
    sh("./gradlew integrationTest")
}
```

Semántica:

```text
assurance.verify
  -> prepare evidence window
  -> BodyContinuation.invoke()
  -> children run through canonical PipelineK engine
  -> seal evidence window
  -> collect / normalize evidence
  -> lenses
  -> assertions
  -> typed AssuranceReport + StepOutcome
```

## 5. Límites

- LLM output nunca se transforma silenciosamente en Fact.
- MCP no es seam de producto entre repositorios.
- OTel IDs, Chronos InvocationIds, PipelineK OpIds y CogniCode SymbolIds nunca se fusionan.
- `no evidence` jamás equivale a PASS.
- Heurísticas no bloquean por defecto.
- No existe un `quality score` autoritativo.
- No se añade un segundo orchestrator dentro del plugin.
- No se introduce una base de grafos propia si CogniCode ya puede exportar la evidencia.

## 6. Orden de lectura

1. `ROADMAP.md`
2. `00-overview/DECISIONS.md`
3. `02-architecture/REFERENCE_ARCHITECTURE.md`
4. `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md`
5. `03-specifications/ASSURANCE_DSL.md`
6. `07-integrations/COGNICODE_WORKSTREAM.md`
7. `07-integrations/CHRONOS_WORKSTREAM.md`
8. `06-uat/UAT_CATALOG.md`
9. `08-testing/SELF_HOSTING_STRATEGY.md`

## 7. Regla de evolución

`ROADMAP.md` es la única autoridad de secuenciación. Los ADR contienen decisiones, no orden de ejecución. Los documentos históricos se moverán a `docs/history/` cuando queden superseded.
