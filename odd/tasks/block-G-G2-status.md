# Bloque G — estado G2 (FrozenAssuranceRuntime + un solo orquestador)

**Fecha:** 2026-10-11
**Rama:** `consolidation/G-integration-v2`
**Source-of-truth:** `odd/tasks/block-G-work-units.md §G2` y
`odd/tasks/block-G-pipelinek-real-integration.md §Reglas`.

## Lo que ya estaba (pre-G2)

- `FrozenAssuranceRuntime` ya existía como `data class` en
  `assurance-engine/.../Assurance.kt:368` con dos campos
  (`engineVersion`, `lenses`).
- `AssuranceOrchestrator.orchestrate(...)` ya aceptaba el
  runtime como parámetro y delegaba en
  `AssuranceEngine.evaluateSuite(snapshot, suite, runtime)`.
- El `assure-cli` opera sobre evidencia sintética
  (dependency graphs) y NO llama a `AssuranceOrchestrator`
  ni a `AssuranceEngine.evaluateSuite`. Es un command
  surface distinto, no un orquestador paralelo.

**Lectura:** la regla 0 (ServiceLoader del SDK, no
Service Locator propio) y la regla 7 (un solo orquestador)
ya estaban implementadas en la práctica. La capa G2 que
faltaba era: (a) la **inmutabilidad real** del runtime, y
(b) la **cerradura executable** de las invariantes.

## Cambios en este commit

1. **`FrozenAssuranceRuntime.freeze()`** en
   `assurance-engine/.../Assurance.kt`: método que
   materializa una copia inmutable del mapa de lenses
   mediante `lenses.toMap()`. La `data class` por sí
   sola no clona el `Map` que recibe — si el caller pasa
   un `HashMap`, el campo del runtime lo referencia. Con
   `freeze()`, el caller obtiene un runtime cuyas
   colecciones son independientes del caller.

2. **`FrozenAssuranceRuntimeTest`** en
   `assurance-testkit/.../engine/FrozenAssuranceRuntimeTest.kt`:
   3 tests de contrato (no de comportamiento).
   - `G2_runtime_rechaza_engineVersion_blank`.
   - `G2_freeze_aísla_mutaciones_del_caller`: caller
     muta su `HashMap` post-`freeze()`, el runtime no se
     entera.
   - `G2_runtime_acepta_lenses_por_mapOf_literal`: caso
     normal con `mapOf(...)` literal.

3. **`G2SingleOrchestratorFitnessTest`** en
   `assurance-testkit/.../fitness/`: 2 fitness tests
   que leen el árbol de fuentes.
   - `G2_un_solo_clase_AssuranceOrchestrator`: cuenta
     definiciones de `class/object AssuranceOrchestrator`
     en `*/src/main/kotlin`. Debe ser 1, en
     `pipelinek-assurance-plugin/`.
   - `G2_assure_cli_no_define_orquestador_propio`:
     verifica que `assure-cli/` no llama a
     `AssuranceEngine.evaluateSuite`. Si lo hace, falla
     con un mensaje que apunta al caller que tendría que
     delegar a `AssuranceOrchestrator.orchestrate()`.

## Lo que **NO** se hace en G2 (deferido a G3-G5)

- **Ampliar `FrozenAssuranceRuntime` con
  `evidenceProviders`, `assertions`,
  `operatorRegistry`.** El plan original listaba 4
  colecciones; el código convergido a 1 (`lenses`). Los
  otros 3 son argumentos sueltos del `orchestrate(...)`
  (`providers: ProviderRegistry`,
  `assertionsById: Map<...>`). Esto es válido: el
  runtime congelado es el catálogo de evaluación
  (lenses), no el catálogo de captura (providers) ni el
  de reglas (assertions). G3 (assurance.check tipado)
  puede consolidar esto si conviene.
- **Cerrar `G2_cert_no_recompila_lenses_por_evaluacion`**
  (fingerprint estable). El digest del report ya excluye
  el orden interno del catálogo (lo construye vía DTO
  ordenado), pero no hay un test específico que afirme
  la propiedad. Diferido a G7 (cert sobre la nueva
  ruta).
- **Eliminar `MockSdkHost`.** Sigue siendo un shim de
  tests, decisión 4 del bloque G. No se borra en G2.

## Evidencia

```
$ ./gradlew --no-daemon clean check
BUILD SUCCESSFUL in 49s
595 tests, 0 failures, 0 errors, 0 skipped
```

Los 5 tests añadidos por G2 (3 contract + 2 fitness) son
parte de los 595.

## Estado de Bloque G

| Unidad | Estado | Evidencia |
| ------ | ------ | --------- |
| G0 pre-flight | ✓ verde | `block-G-G0-status.md` (G0.1, G0.2, G0.3) |
| G1 matriz/BOM/compileOnly | ✓ verde | `fa69f26` + `SDK_COMPATIBILITY_MATRIX.md` |
| G2 FrozenAssuranceRuntime | ✓ verde | este doc + 5 tests nuevos |
| G3 assurance.check tipado | pendiente | depende de G2 |
| G4 assurance.verify + BodyContinuation | pendiente | depende de G2 |
| G5 admisión 0.48 + 0.51 | ✓ parcial | admisión de classpath OK; cert formal pendiente de G6 |
| G6 pipeline de ejemplo | pendiente | depende de G3-G4 |
| G7 cert sobre la nueva ruta | pendiente | depende de G6 |
| G8 SDK_COMPATIBILITY_MATRIX.md | ✓ abierto | `SDK_COMPATIBILITY_MATRIX.md` v1 |
| G9 v0.13.0-rc1 | pendiente | depende de G8 |
