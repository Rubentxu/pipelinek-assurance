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

## Estado (observado 2026-10-10, SHA `125304b`)

**Bloqueado por externo.** C1 requiere la release firmada de
CogniCode v0.101.10 con el export `assurance-evidence/v1`
consumible por nuestro codec.

## Sub-tareas

### C1 — Consumir productor real de CogniCode (BLOQUEADO)

- [ ] Ejecutar productor real v0.101.10.
- [ ] Consumir sus bytes en Kotlin.
- [ ] Verificar digest y compatibilidad byte a byte Rust↔Kotlin.
- [ ] NO usar fixture recreado manualmente.
- [ ] Workstream upstream: exponer grafo real con capabilities
      arquitectónicas implementadas.

### C2 — Endurecer adapters (en-repo)

- [ ] JaCoCo y PIT: XML seguro, modelos tipados.
- [ ] Colisiones de IDs por línea/clase/fichero resueltas.
- [ ] Preservar identidad de mutantes y ubicación de mediciones.
- [ ] Verificar entradas vacías, inválidas, enormes, parciales.

### C3 — Baselines y diff (en-repo)

- [ ] Semántica completa: NEW, EXISTING, RESOLVED, REGRESSED,
      CHANGED.
- [ ] Definir formalmente las transiciones que justifican
      REGRESSED y CHANGED.
- [ ] Fingerprints a partir de identidad y contenido semántico
      estructurado, no de textos explicativos.
- [ ] Canonicalizar el digest de baseline.
- [ ] Exigir fecha explícita en expiración.

### C4 — Ratchets (en-repo)

- [ ] `noNewViolations`, `noNewCycles`, conteo que no aumenta.
- [ ] Políticas de mutation strength sobre evidencia válida.
- [ ] Complejidad sólo con protocolo de medición estable.
- [ ] Excepciones con owner, rationale, expiry.

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
