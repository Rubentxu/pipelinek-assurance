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
| M-D01 | codec decode no verifica digest (digest != canonical) | AAT-1 / fail-closed |
| M-D02 | codec digest field relaja a nullable | M5 / digest schema |
| M-V01 | un EvidenceProvider retorna AssertionResult | AAT-6 |
| M-V02 | AssertionResult deja de ser sealed | AAT-8 |
| M-V03 | canonicalizeSuite deja de ordenar lenses | AAT-16 |
| M-10-01 | ConnascenceLens: strength 6 aceptado (rango 0..5) | M10 Connascence |
| M-10-02 | SolidLens: DIP violation no detectada (rank check desactivado) | M10 DIP |
| M-10-03 | ConsistencyLens: contradicción no detectada (filter false) | M10 Consistency |
| M-10-04 | SeamLens: TODOS los módulos clasificados como seam | M10 Seam |
| M-DSL01 | assurancePack DSL `mandatory(id)` no bridgea a `metadata["mandatory"]` | M10 DSL bridge |
| M-CODEC01 | PackArtifactCodec no verifica digest del plan al decodificar | M10 plan integrity |
| M-CAP-DRIFT | Capabilities cambia valor canónico sin migrar | AAT-13 / namespacing |
| M-CAP-DRIFT-2 | Capabilities.SIGNALS_DETEKT cambia valor canónico sin migrar | AAT-13 / signals namespace |
| M-COGN01 | CogniCodeProvider: authority desconocida re-clasificada como DeterministicAnalyzer | AAT-19 / authority law |
| M-COGN02 | CogniCodeProvider: gap reason desconocido re-clasificado como PartialProduced | AAT-13 / drift visible |
| M-10-CONTENT | ConnascenceLens `findConnascenceOfName` neutralizado (vuelve a `emptyList()`) | M10 / lens content |
| M-SOLID-SRP-EMPTY | SolidLens `findSrpSignals` neutralizado a `emptyList()` | M10 / SRP heuristic |
| M-SOLID-OCP-EMPTY | SolidLens `findOcpSignals` neutralizado a `emptyList()` | M10 / OCP heuristic |
| M-CHRONOS-REGEX-LEGACY | ChronosArtifactProvider usa regex legacy en vez del codec | P0.4 / Chronos codec |
| M-OTEL-REGEX-LEGACY | OtelArtifactProvider usa regex legacy en vez del codec | P0.4 / OTel codec |
| M-NORM-01 | EvidenceNormalizer remueve chequeo AAT-13 (id debe contener '/') | AAT-13 / namespacing |

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

## M-A01: un mutante que sobrevivió porque el código era muerto

M-A01 mataba "quitar el recorrido de infracciones de `noDependency`". Sobrevivió
al catálogo entero: 0 de 193 tests lo mataban.

La primera hipótesis, la fácil, es que el test es débil. Es la hipótesis que
hay que descartar antes de tocar nada, porque "añadir un test que mate al
mutante" y "arreglar el código" dan el mismo resultado verde y un relato distinto.

La hipótesis real era otra: **el código que el mutante atacaba no decidía
nada**. La assertion iteraba sobre las aristas prohibidas y pedía el camino
más corto de `edge.from` a `edge.to`. Como `(from, to)` era una arista del
propio grafo, el BFS la veía en la primera expansión y devolvía siempre
`[from, to]`. Veinte líneas de búsqueda para calcular su propia entrada.

El síntoma delator fue el `sortedBy { camino.size }` que elegía el testigo:
ordenaba una lista donde todas las longitudes eran 2. No era un selector de
"la infracción mínima", era un no-op con apariencia de selector.

**La regla que sale de aquí:** un mutante que sobrevive a todo el catálogo
no se cura añadiendo tests. Se pregunta primero si el código que ataca está
vivo, y un buen detector de código muerto es el propio selector que no
selecciona nada: un `sortedBy`, un `minOf`, un `firstOrNull` sobre una
colección donde todos los elementos empatan. Si un comparator tiene un solo
valor posible, el comparator es decoración.

Mutantes **equivalentes** (el código cambia pero el comportamiento
observable no) sí son un caso legítimo y distinto: no se pueden matar y no
deben registrarlos como supervivientes sin decirlo. La forma honesta de
tratarlos es ponerlos en una lista de "equivalentes conocidos" con su
razón, no declararlos muertos por la vía de que el harness no los cuenta.
M-A02 fue uno de ellos en su primera forma (convertir el BFS del ciclo en
DFS no cambiaba ningún resultado en los grafos del catálogo) y se resolvió
reescribiendo el mutante para atacar el comportamiento que el catálogo ya
describía: no la búsqueda, sino perder una arista antes de calcular el
ciclo.

## `clean check` de M1, y lo que todavía no cierra el gate

`clean check`: `BUILD SUCCESSFUL`, 193 tests, 0 fallos, 0 skipped. Los tres
mutantes arquitectónicos mueren y ninguno por un único test: M-A01 por 2,
M-A02 por 4, M-A03 por 4.

M1 **no** está cerrado. Faltan la lens, el fixture en disco, el CLI mínimo,
UAT-005, UAT-022, el self-model sintético, AAT-7, AAT-19 y M-H01. La lista
está en `ROADMAP.md`; aquí sólo se deja constancia de que el trabajo hecho
hasta ahora es el núcleo de las assertions, no el hito entero.
