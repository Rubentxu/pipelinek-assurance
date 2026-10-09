# Recibo R5 — Lenses avanzadas, gobierno de cambios y self-hosting

**Bloque:** R5 — M10.
**Release propuesta:** `v0.6.0-beta.1`.
**Fecha:** 2026-10-10.
**SHA integrado:** `af83e73`.

## Trabajo ejecutado

### R5.1 — ConnascenceLens
- `ConnascenceLens` con `ConnascenceProjection(countByKind, findings)`.
- Reusa `HexagonalArchitectureLens` para el grafo (no hay un
  segundo parser).
- `strength` validado en `0..5` por `init` (M-10-01).
- Tipos sustentados por análisis estático:
  - `ConnascenceKind.Name` — mismo nombre en módulos.
  - `ConnascenceKind.Position` — orden posicional.
  - `ConnascenceKind.Meaning` — semántica compartida.
- UAT-029 registrado: ConnascenceLens stable output.
- M-10-01 certificado con redundancia 3.

### R5.2 — SolidLens
- DIP: assertion fuerte con `DipViolation` cuando un módulo
  de capa interna depende de una capa más externa
  (`fromLayer.rank < toLayer.rank`).
- ISP: señal heurística (`HeuristicAnalyzer`) de distribución
  de dependientes entrantes.
- SRP/OCP: signals heurísticas, no assertions.
- LSP: enriquecible con evidencia runtime (M9/M10).
- UAT-030 registrado: SolidLens detecta DIP violado.
- M-10-02 certificado con redundancia 3.

### R5.3 — SeamLens
- Clasifica un módulo como seam si es de Adapters/Infrastructure
  y tiene al menos un dependiente en Application/Domain.
- Authority: `HeuristicAnalyzer` (señal, no fact).
- UAT-032 registrado.
- M-10-04 certificado con redundancia 3.

### R5.4 — ConsistencyLens
- Compara `DeclaredArchitecture` (estática) con
  `ObservedArchitecture` (runtime). Aristas observadas no
  declaradas → `ConsistencyViolation`.
- Una observación incompleta no acusa al declarado de falso.
- UAT-031 registrado.
- M-10-03 certificado con redundancia 3.

### R5.5 — RequiredAssurancePlan
- **No implementado en este ciclo.** Diferido a un pase
  posterior donde la lista justificada de comprobaciones pueda
  asociarse a un cambio. El plan requiere
  `componente afectado + frontera + evidence + assertion +
  suite + capabilities + condiciones que impiden conclusión`,
  que no es cerrable sin un DSL de cambios concreto.

### R5.6 — Assurance packs
- **No implementado en este ciclo.** El ROADMAP lo lista como
  "M10, paquetes versionados" sin entregar; el trabajo
  precedente (M1..M9) tampoco lo exige. Diferido.

### R5.7 — Self-hosting completo (S6)
- `assure report 08-testing/self-model.graph` corre sobre el
  propio repo y produce veredicto `AssertionPass` (2/2).
- `assure report <self-model-con-domain-rogante>` produce
  `AssertionFailure` con witness path reproducible:
  `domain -> testkit`, `fromLayer=Domain`, `toLayer=Adapters`.
- Self-hosting S2 (CogniCode) cubierto por
  `SelfHostingS2Test` (extractor in-test, codifica el export,
  el `CogniCodeArtifactProvider` lo consume, la lens proyecta).
- Argumento de release: arquitectura hexagonal (HexagonalLens)
  + test topology (TestTopologyLens) + diff (DiffEngine) +
  observabilidad OTel (con shapes sintéticos) +
  self-hosting.

### R5.8 — Tests negativos por lens

| Lens | Mutante | Redundancia |
|---|---|---|
| Connascence | M-10-01 (strength fuera de rango) | 3 |
| Solid | M-10-02 (DIP rank check desactivado) | 3 |
| Consistency | M-10-03 (filter contradicciones desactivado) | 3 |
| Seam | M-10-04 (clasifica todos como seam) | 3 |

Cada mutante ataca una rama de la lens y muere por al menos
dos tests (uno ataca la rama, el otro verifica la rama
opuesta).

## Mutantes M10 certificados con el harness

- M-10-01: killed=3 (no AVISO).
- M-10-02: killed=3 (no AVISO).
- M-10-03: killed=3 (no AVISO).
- M-10-04: killed=3 (no AVISO).

## Acceptance

- UAT-022, UAT-023: recertificadas sobre evidencia real con
  `assure report`.
- UAT-029, UAT-030, UAT-031, UAT-032: registradas y cubiertas
  por los tests de cada lens.
- AAT-19: verde (heurística no satisface assertion que exige
  determinista).
- Mutantes negativos propios de M10: 4 registrados y
  certificados.

## Build

- `./gradlew --no-daemon clean check` → `BUILD SUCCESSFUL in 33s`.
- 360 tests, 0 failures, 0 skipped.

## Riesgos y deuda

- RequiredAssurancePlan y assurance packs (R5.5, R5.6):
  diferidos. No son bloqueadores de R5 — son funcionalidades
  adicionales que el ROADMAP lista como "M10" pero no como
  requisito de cierre del bloque.
