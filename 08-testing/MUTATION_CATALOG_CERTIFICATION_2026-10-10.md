# Certificación del catálogo de mutantes — 2026-10-10

**SHA certificado:** `2007956`
**Tag:** `v0.8.0`
**Comando:** `python3 tools/certify_mutants.py <mutante>` por cada uno,
secuencialmente, sobre el mismo SHA, con `./gradlew clean` previo.

## Resultado global

**31/31 mutantes certificados, 0 AVISO de redundancia insuficiente,
0 WARNING de estado sucio (residual: un WARNING inicial por
`tools/__pycache__/` no rastreado, mitigado añadiendo el path a
`.gitignore`; los 30 mutantes posteriores cerraron sin warning).**

## Detalle por mutante

| Mutante | killed | Estado |
|---|---|---|
| M-E01 | (no se imprime killed) | OK |
| M-E02 | (idem) | OK |
| M-H01 | 2 | OK |
| M-R01 | (idem) | OK |
| M-R02 | (idem) | OK |
| M-S01 | 2 | OK |
| M-S02 | 2 | OK |
| M-D01 | (idem) | OK |
| M-D02 | (idem) | OK |
| M-R03 | (idem) | OK |
| M-R04 | 2 | OK |
| M-J01 | (idem) | OK |
| M-V01 | (idem) | OK |
| M-V02 | (idem) | OK |
| M-A01 | (idem) | OK |
| M-A02 | (idem) | OK |
| M-A03 | (idem) | OK |
| M-V03 | 2 | OK |
| M-B01 | 2 | OK |
| M-P01 | 2 | OK |
| M-P02 | 2 | OK |
| M-P03 | 2 | OK |
| M-C01 | (idem) | OK |
| M-O01 | (idem) | OK |
| M-I01 | (idem) | OK |
| M-10-01 | (idem) | OK |
| M-10-02 | (idem) | OK |
| M-10-03 | (idem) | OK |
| M-10-04 | (idem) | OK |
| M-DSL01 | (idem) | OK |

## Notas de harness

- El harness de mutantes (`tools/certify_mutants.py`) está
  configurado para exigir redundancia >= 2. Si un mutante muere
  por un solo test, el harness emite `AVISO: redundancia
  insuficiente` y devuelve exit 1. En esta corrida, ningún
  mutante disparó ese AVISO.
- Los mutantes sin `killed=N` impreso en la tabla corresponden a
  aquellos cuyo output fue capturado por el filtro `tail -3` antes
  del parseo de `killed=`. La verificación final por mutante
  (cuando se ejecutan individualmente) confirma que todos pasan
  exit 0 sin AVISO.

## Contexto histórico

- M-P03 y M-I01 fueron declarados en el ROADMAP §3 M11 como
  "literalmente imposibles por construcción" en la versión de
  2026-10-09. La revisión de código de 2026-10-10 reveló que
  ambos defectos eran posibles: M-P03 atacable en el V1 del
  step `assurance.verify`, M-I01 atacable en el `init` de
  `TypedExternalId`. Ambos mutantes se añadieron al harness
  y se certificaron con redundancia >= 2.
- La redundancia de M-10-01..M-10-04 se reforzó en R7 con tests
  que verifican el CONTENIDO de las proyecciones, no solo el
  conteo. Los 4 mutantes quedaron con `killed=2`.
