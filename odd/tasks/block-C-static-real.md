---
name: block-C-static-real
description: Feature document for Bloque C — assurance estático real, baselines y ratchets. Source of truth for v0.11.0-rc1.
---

# Bloque C — Assurance estático real, baselines y ratchets

**Goal:** analizar un repositorio real con CogniCode y Detekt,
generar findings, compararlos contra baseline, y bloquear
solamente las regresiones que la política declare relevantes.

**Release objetivo:** `v0.11.0-rc1`

**Precondición:** B cerrado. Productor CogniCode real
disponible con las capabilities que exigen las suites.

## Estado (observado 2026-10-10, post-C4)

**C1 sigue BLOQUEADO** por externo (CogniCode v0.101.10).

**C3 y C4 cerrados (en-repo, sin bloqueador)**:
- C3: semántica completa de los 5 estados (NEW, EXISTING,
  RESOLVED, REGRESSED, CHANGED) con transiciones formales.
  Regressed = excepción caducada; Changed = fingerprint
  distinto con mismo assertionId. 11 tests en
  `M4DiffLawsTest`.
- C4: `RatchetEngine` con `RatchetPolicy` (`forbidNew`,
  `forbidRegressed`, `forbidChanged`, `noNewCycles`,
  `maxUnresolvedCount`, `maxCyclesCount`) + excepciones
  (`owner`, `rationale`, `expires`). 12 tests en
  `C4RatchetEngineTest`.

## Sub-tareas

### C1 — Consumir productor real de CogniCode (BLOQUEADO)

- [ ] Ejecutar productor real v0.101.10.
- [ ] Consumir sus bytes en Kotlin.
- [ ] Verificar digest y compatibilidad byte a byte Rust↔Kotlin.
- [ ] NO usar fixture recreado manualmente.
- [ ] Workstream upstream: exponer grafo real con capabilities
      arquitectónicas implementadas.

### C2 — Endurecer adapters (en-repo)

- [x] JaCoCo y PIT: XML seguro, modelos tipados.
- [x] Colisiones de IDs por línea/clase/fichero resueltas:
      el id del mutante ahora incluye el `mutator`, así
      dos mutaciones en la misma línea (distintos
      operadores) producen ids distintos
      (4 tests nuevos en `PitestMutationProviderTest`).
- [x] Preservar identidad de mutantes y ubicación de
      mediciones: el id es estable a través de
      invocaciones; el payload lleva sourceFile,
      mutatedClass, lineNumber, mutator.
- [x] Verificar entradas vacías, inválidas, enormes,
      parciales: XML sin <mutation> → Failed; XML con
      mutator faltante → "unknown"; XML inválido → Failed.

### C3 — Baselines y diff (en-repo)

- [x] Semántica completa: NEW, EXISTING, RESOLVED, REGRESSED,
      CHANGED (los 5 estados se clasifican en `DiffEngine.diff`).
- [x] Definir formalmente las transiciones que justifican
      REGRESSED y CHANGED:
        - REGRESSED: stableId estaba en baseline con `expires`
          y la fecha de comparación está más allá (excepción
          caducó, finding volvió).
        - CHANGED: mismo `assertionId` que el baseline, pero
          fingerprint semántico del report difiere (la
          violación se movió).
- [x] Fingerprints a partir de identidad y contenido semántico
      estructurado (assertionId + subjectRefs + explanation),
      no de textos explicativos cosméticos.
- [x] Canonicalizar el digest de baseline.
- [x] Exigir fecha explícita en expiración (el `today` se
      pasa como parámetro, no del reloj del sistema).

### C4 — Ratchets (en-repo)

- [x] `noNewViolations` (forbidNew), `noNewCycles` (noNewCycles),
      conteo que no aumenta (maxUnresolvedCount, maxCyclesCount).
- [x] `RatchetEngine.evaluate(policy, diff, isCycleByStableId,
      today)` con detección de cycle vía callback (mantiene
      el engine puro y desacoplado del report original).
- [x] Excepciones con `RatchetException(owner, rationale,
      expires)`. Vigentes exoneran; caducadas NO exoneran.
- [x] Umbrales no negativos (init require).

### C5 — Primer pipeline de calidad real (BLOQUEADO por C1)

- [ ] CogniCode produce evidencia.
- [ ] Detekt produce SARIF.
- [ ] Assurance normaliza.
- [ ] Plugin evalúa suites.
- [ ] Diff compara baseline y revisión.
- [ ] Ratchet bloquea nueva violación.
- [ ] Report muestra hallazgos nuevos, existentes, resueltos.

## Acceptance

- Round-trip real Rust↔Kotlin.
- Dos fixtures de lenguajes distintos.
- Cambiar una línea no altera indebidamente el finding.
- Nueva dependencia prohibida → NEW y rompe ratchet.
- Finding histórico permitido → EXISTING.
- Excepción caducada deja de suprimir.
- Producer parcial → Inconclusive.
- Repetición conserva digests.

**UAT:** 003, 004, 005, 006, 007, 010, 011, 022, 026, 027, 028.
**AAT:** 4, 6, 18, 19, 20.

## STOP

CogniCode declara arquitectura Unsupported y Assurance
presenta un grafo sintético como si fuese análisis real.

## Cierre (objetivo)

`v0.11.0-rc1` con ejemplo de integración estática, baseline
versionada, ratchets y referencia exacta a la release de
CogniCode.
