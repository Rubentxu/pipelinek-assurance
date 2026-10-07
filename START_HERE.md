# START HERE — primer ciclo de ejecución

## Objetivo del primer ciclo

No comenzar creando integraciones con todas las herramientas. El primer ciclo debe demostrar la proposición fundamental del producto con un vertical completo y falsable:

> Un snapshot determinista de dependencias puede proyectarse mediante una lens hexagonal y una assertion puede producir un contraejemplo reproducible; esa misma suite puede ejecutarse después como un Step externo de PipelineK sin cambiar su semántica.

## Secuencia inmediata

### W0 — Bootstrap

- crear repo `pipelinek-assurance`;
- copiar `AGENTS.template.md` -> `AGENTS.md` y adaptar sólo nombres/path;
- instalar SDDK y crear estado inicial;
- copiar `ROADMAP.md` como autoridad;
- crear build Kotlin/JVM mínimo;
- fijar Kotlin/Gradle/toolchain;
- sin PipelineK dependency todavía.

### W1 — Domain kernel

Implementar exclusivamente:

```text
EvidenceId
SnapshotId
EvidenceAuthority
Completeness
EvidenceItem
EvidenceSnapshot
EvidenceGap
Correlation
```

Gate: roundtrip + canonical digest + property tests.

### W2 — Assurance algebra

```text
Lens
ProjectionResult
Assertion
AssertionResult
Counterexample
AssuranceSuiteIR
AssuranceReport
```

Gate: no provider/IO imports.

### W3 — Hexagonal vertical sintético

Fixture graph propio -> HexagonalLens -> `domain-purity` -> witness.

Crear mutantes A01/A02 y demostrar que la suite los mata.

### W4 — Primer self-host

Representar manualmente la arquitectura real del repo y ejecutar la suite contra sí mismo. Esto aún no es autoridad release: es shadow proof.

### W5 — CogniCode contract spike

En worktree separado de CogniCode, diseñar/exportar un fixture `assurance-evidence/v1` equivalente al synthetic graph. No modificar todavía el evaluator de assurance para aceptar internals de CogniCode.

### W6 — Differential evidence proof

El mismo fixture proyectado desde synthetic provider y CogniCode export debe dar la misma `HexagonalProjection` canónica para el subset común.

Sólo después se promueve CogniCode como evidence provider real.

## STOP conditions

- Si no puede lograrse stable canonical snapshot digest, no avanzar a PipelineK.
- Si la misma lens necesita conocer el provider, rediseñar el Evidence IR.
- Si un signal heurístico es necesario para que el primer architecture gate funcione, el modelo está mezclando categorías.
- Si CogniCode requiere importar clases Kotlin o PipelineK, parar y volver al artifact seam.
