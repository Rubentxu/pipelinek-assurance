# Catálogo inicial de mutaciones

| ID | Mutación | Debe matar |
|---|---|---|
| M-A01 | domain depende de adapter | domain-purity |
| M-A02 | eliminar una arista antes de SCC | cycle UAT |
| M-A03 | ciclo A -> B -> C -> A pasa por NO ser ciclo | cycle UAT |

| M-E01 | Partial se trata como Complete | completeness law |
| M-E02 | Hypothesis se acepta como Fact | epistemic law |
| M-H01 | heuristic signal marcado deterministic | authority law |
| M-B01 | NEW baseline finding clasificado EXISTING | ratchet |
| M-P01 | assurance failure sobrescribe body failure | verify outcome law |
| M-P02 | cancellation capturada como Failure | cancellation UAT |
| M-P03 | handler itera children fuera BodyContinuation | architecture fitness |
| M-C01 | Chronos gap ignorado | runtime incomplete |
| M-O01 | OTel missing span se trata como success | observability |
| M-I01 | TraceId e InvocationId comparten wrapper String sin tipo | identity fitness |
| M-R01 | report serializer no canonicaliza maps | digest reproducibility |
| M-R02 | digest ordena por clave sin desempate por contenido | digest reproducibility |
| M-R03 | report CBOR no canonicaliza results ni artifacts | forma canónica |
| M-R04 | report CBOR no canonicaliza correlations | forma canónica |
| M-J01 | envelope JSON vuelve al orden de declaración de kotlinx | orden canónico JSON |
| M-S01 | decoder no aplica las cotas al construir el dominio | bounded decoding |
| M-S02 | cota de longitud de cadena ausente | bounded decoding |
| M-V01 | un EvidenceProvider retorna AssertionResult | AAT-6 |
| M-V02 | AssertionResult deja de ser sealed | AAT-8 |
| M-V03 | canonicalizeSuite deja de ordenar lenses | AAT-16 |

## Redundancia, y por qué se importa

Un mutante que muere por **un solo test** no está certificado: está conectado por
un hilo. Si ese test se renombra, se borra o se toca, el defecto vuelve y el
certificado sigue diciendo exactamente lo mismo. El harness avisa cuando pasa
(`AVISO: ... Redundancia insuficiente`) y eso es correcto, pero el aviso no
cierra el mutante.

M-R04 nació en esa situación: moría por
`LAW_report_cbor_roundtrip_returns_the_canonical_form` y nada más. Se añadió
`LAW_report_correlation_permutation_yields_the_same_artifact`, que compara los
**bytes** de la pareja permutada en vez del valor decodificado. M-R04 pasó a
morir por las dos, y la segunda no es redundancia inútil: la primera falla si el
codec no ordena al *decodificar*, la segunda si no ordena al *codificar*. Un
mutante que canonicalice en el sitio equivocado pasa una y no la otra.

Regla para el resto: **preferir dos leyes que observen capas distintas antes que
dos que observen la misma con más ejemplos.**

## `M-A03`: por qué se registra antes de existir

De los tres mutantes arquitectónicos del primer vertical (M1), dos tenían ID y
el tercero no. Se registra aquí **antes** de construir el vertical, porque un ID
que se inventa durante la implementación acaba describiendo el código que salió
en vez del defecto que había que cazar. El ciclo completo A -> B -> C -> A es el
caso más icónico de "ciclo que parece no serlo": sin la arista de vuelta, cada
nodo tiene grado de salida 1 y la topología parece un árbol.

## Sobre los prefijos, y una colisión que hubo que corregir

`M-A01` y `M-A02` son arquitectónicos (M1). Los tres AAT de M0 que.resultsaron
sin ejecución se numeraron al principio `M-A01..M-A03`, y **colisionaron** con
los dos primeros. El catálogo llegó a tener dos filas con el mismo ID y
significados distintos, que es peor que un ID ausente: parece que la regla
está cubierta por dos vías y no lo está por ninguna.

Se renombraron a `M-V01..M-V03` (V de *violación de AAT*). Un ID duplicado
habría hecho que "M-A01 muerto" fuera una frase ambigua, y la ambigüedad en un
certificado es exactamente el tipo de cosa que pasa sin que nadie la mire.

Regla: **el ID se asigna una vez y no se reutiliza**. Si dos familias de
mutantes necesitan el mismo prefijo, la segunda cambia de prefijo, no la
primera.
