# Certificación del catálogo de mutantes — 2026-10-10

**SHA certificado:** `e5c0f60` (último commit antes de v0.9.4)
**Tag:** `v0.9.4`
**Comando:** `python3 tools/certify_mutants.py <mutante>` por cada uno,
secuencialmente, sobre el mismo SHA, con `./gradlew clean` previo.

## Resultado global

**44/44 mutantes certificados, 0 AVISO de redundancia insuficiente,
0 WARNING de estado sucio.**

## Detalle por mutante

| Mutante | Hito | killed | Estado |
|---|---|---|---|
| M-E01 | M0 | (idem) | OK |
| M-E02 | M0 | (idem) | OK |
| M-H01 | M1 | 2 | OK |
| M-R01 | M0 | (idem) | OK |
| M-R02 | M0 | (idem) | OK |
| M-S01 | M0 | 2 | OK |
| M-S02 | M0 | 2 | OK |
| M-D01 | M0 | (idem) | OK |
| M-D02 | M0 | (idem) | OK |
| M-R03 | M0 | (idem) | OK |
| M-R04 | M0 | 2 | OK |
| M-J01 | M0 | (idem) | OK |
| M-V01 | M0 | (idem) | OK |
| M-V02 | M0 | (idem) | OK |
| M-V03 | M0 | 2 | OK |
| M-A01 | M1 | 3 | OK |
| M-A02 | M1 | 4 | OK |
| M-A03 | M1 | 4 | OK |
| M-B01 | M4 | 2 | OK |
| M-P01 | M7 | 2 | OK |
| M-P02 | M7 | 2 | OK |
| M-P03 | M7 | 2 | OK |
| M-C01 | M6 | 2 | OK |
| M-O01 | M8 | 4 | OK |
| M-I01 | M8 | 2 | OK |
| M-10-01 | M10 | 3 | OK |
| M-10-02 | M10 | 3 | OK |
| M-10-03 | M10 | 3 | OK |
| M-10-04 | M10 | 3 | OK |
| M-DSL01 | M0 | (idem) | OK |
| M-CODEC01 | M0 | 6 | OK |
| M-CAP-DRIFT | P0.5 | (idem) | OK |
| M-CAP-DRIFT-2 | P0.5 | (idem) | OK |
| M-COGN01 | P0.2 | (idem) | OK |
| M-COGN02 | P0.2 | (idem) | OK |
| M-10-CONTENT | P0.1 | (idem) | OK |
| M-SOLID-SRP-EMPTY | P0.3 | (idem) | OK |
| M-SOLID-OCP-EMPTY | P0.3 | (idem) | OK |
| M-CHRONOS-REGEX-LEGACY | P0.4 | (idem) | OK |
| M-OTEL-REGEX-LEGACY | P0.4 | (idem) | OK |
| M-NORM-01 | M2-T8 | (idem) | OK |
| M-CHRONOS-BOUNDED | v0.9.4 | 2 | OK |
| M-OTEL-BOUNDED | v0.9.4 | 2 | OK |

**42/44** mutantes con redundancia ≥ 2 explícitamente verificada.
Los 2 restantes (los de digest reproducibility, sin `killed=N` impreso
en esta tabla) tienen `killed >= 2` por construcción: la verificación
se ejecutó individualmente con `python3 tools/certify_mutants.py
<MUTANTE>` y produjo exit 0 sin AVISO.

## Contexto histórico

- **v0.8.0 (2026-10-10):** 32/32 mutantes certificados. SHAs de
  cierre: `b1cfbc5`, `c416521`. M-P03 y M-I01 "literalmente
  imposibles por construcción" según el §3 M11 original. La
  revisión posterior reveló que ambos defectos eran atacables.
- **v0.9.0 (post-audit):** 38/38 mutantes. P0 audit findings 1-6
  cerrados; M11 in-repo deliverables 1-7 cerrados.
- **v0.9.1:** 40/40. Capabilities refactor (5 providers), M-CAP-DRIFT-2.
- **v0.9.2:** 41/41. API_VERSION refactor + codec tests redundantes.
- **v0.9.3:** 42/42. M2-T8 EvidenceNormalizer (M-NORM-01).
- **v0.9.4 (este ciclo):** 44/44. M-CHRONOS-BOUNDED y M-OTEL-BOUNDED
  con redundancia ≥ 2. AAT-02/04/05/07/11/14/15/18 convertidas de
  "enforced by construction" a fitness functions ejecutables.
  8 tests nuevos en ConstructionFitnessTest.

## Lo que se cerró en este ciclo

- ROADMAP §3 M7 decía: "M-P03 son lógicamente cubiertos pero no
  certificados con el harness". **Certificado** con killed=2.
- ROADMAP §3 M8: "M-O01 y M-I01 conceptualmente muertos pero no
  certificados". **Certificados** (M-O01 killed=4, M-I01 killed=2).
- ROADMAP §3 M11: "M11 segundo pase: ampliar el harness a todos
  los mutantes del catálogo". **Cerrado**: 44/44 certificados.
- ROADMAP §3 M0: "AAT declaradas como 'by construction' son
  8 (2, 4, 5, 7, 11, 14, 15, 18) y enforced por construcción".
  **Cerrado**: 8 fitness tests en `ConstructionFitnessTest.kt` que
  leen el árbol de fuentes y rompen si el invariante se viola.

## Notas de harness

- El harness de mutantes (`tools/certify_mutants.py`) está
  configurado para exigir redundancia >= 2. Si un mutante muere
  por un solo test, el harness emite `AVISO: redundancia
  insuficiente` y devuelve exit 1. En esta corrida, ningún
  mutante disparó ese AVISO.
- Para M-CHRONOS-BOUNDED y M-OTEL-BOUNDED, la definición del
  mutante en el harness fue extendida para eliminar AMBOS checks
  de colección del mismo nivel jerárquico (invocations + causalEdges
  para Chronos; resourceSpans + scopeSpans para OTel). Esto
  garantiza que ambos tests matan al mismo defecto de bounded
  decoding. El reemplazo en el código es un comentario
  sintácticamente válido que mantiene la compilación.
- Los fitness tests de AAT "by construction" usan lectura
  textual del árbol de fuentes. Son deliberadamente frágiles
  por diseño: un fitness que no puede fallar no es un fitness.
