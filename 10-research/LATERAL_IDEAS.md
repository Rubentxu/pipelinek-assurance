# Pensamiento lateral — extensiones con utilidad potencial

Estas ideas no forman parte del critical path salvo promoción explícita por evidencia.

## 1. RequiredAssurancePlan

Git diff + CogniCode impact + TestTopologyLens produce una lista justificada de suites necesarias para un cambio, no un risk score.

## 2. Architecture triangulation

Comparar:

- DeclaredArchitecture;
- StructuralArchitecture;
- ObservedArchitecture.

Las contradicciones pueden revelar reflection, generated code, FFI o hidden transports.

## 3. Evidence dependency graph

Cada assertion declara read-set de evidence capabilities. Un cambio en un provider puede invalidar únicamente reports dependientes.

## 4. Incremental assurance

Content-addressed entities/relations permiten reevaluar sólo lenses afectadas por delta de evidence.

## 5. Assurance packs

Versionar conjuntos de lenses/helpers/assertions, pero un pack no obtiene gating authority automáticamente.

## 6. SARIF output

Adapter de report a SARIF para IDE/code-scanning, sin convertir SARIF en modelo canónico.

## 7. WASM evaluator

Sólo si aparece necesidad real de ejecutar suites fuera JVM. Policy IR suficientemente puro podría habilitarlo.

## 8. Counterexample shrinking

Delegar a Chronos/Property framework cuando exista domain-specific shrinker; assurance conserva modelo común de counterexample.

## 9. Software seams proof

Lens que combina static dependency injection + mutation/substitution tests para demostrar que un seam no sólo existe sintácticamente sino que realmente puede sustituirse.

## 10. Quality claim graph

Representar release claims como proposiciones con evidence refs y assertion results. Útil para auditoría sin score global.
