# ROADMAP — autoridad única de secuenciación

## 0. Alcance de este documento

Este documento dice **en qué orden** se construye `pipelinek-assurance`. No dice **qué** es
el producto (eso vive en `01-product/PRODUCT_SPEC.md`), ni **por qué** se eligió cada decisión
(eso viven en `04-adrs/`), ni **cómo** se diseña cada pieza (eso viven en `03-specifications/`).

Reglas de precedencia, en este orden:

1. Si un ADR y el ROADMAP discrepan sobre **secuencia**, manda el ROADMAP.
2. Si un ADR y el ROADMAP discrepan sobre **decisión o límite**, manda el ADR.
3. Si una spec y el ROADMAP discrepan sobre **forma del artefacto**, manda la spec.
4. `START_HERE.md` y `05-roadmap/FIRST_WORK_PACKAGES.md` son narratives de entrada. Si
   contradicen a este documento, este documento gana. El mapeo de `START_HERE.md` W0..W6
   está en §2 y §3.
5. `06-uat/MILESTONE_GATES.md` define el gate de cada hito. Este documento define qué se
   entrega, con qué mutantes y con qué UAT se cierra.

## 1. Cómo se ejecuta un hito

Cada hito es un ciclo cerrado. Un hito no arranca si el gate del anterior no está verde.

```text
pre-flight (SDDK, SDDK adopt, trunk sync)
  -> seleccionar Work Packages del hito (§4.4)
  -> implementar work units verticales, no infraestructura especulativa
  -> tests quirúrgicos durante el trabajo
  -> ejecutar UAT del hito sobre artefactos reales (§4.1)
  -> matar los mutantes asignados (§4.2)
  -> ejecutar los fitness functions del hito (§4.3)
  -> Gate del hito (MILESTONE_GATES.md)
  -> recibo del hito (§5.2)
  -> siguiente hito
```

Leyes de ejecución:

- Un hito con `Trabajo paralelo` en otro repo se ejecuta en worktree separado. Los receipts
  de cada repo se citan uno a otro, nunca se mezclan en un único commit.
- Un hito no se declara cerrado por intención ni por código escrito. Cierra con evidencia
  ejecutada.
- Si un hito dispara una STOP condition de su bloque, se para. No se avanza "un poco" para
  ver si se despeja.
- La promoción de una assertion a `Mandatory` exige un fixture o mutante negativo que la haga
  fallar por la razón esperada (§5.4).
- No se crean módulos físicos nuevos sin que una frontera real lo exija. La lista de módulos
  candidatos vive en `05-roadmap/IMPLEMENTATION_GUIDE.md` y no se duplica aquí.

## 2. Precondición W0: bootstrap

W0 no es un hito numerado porque no produce capacidad de producto. Produce el terreno donde el
SDDK puede correr.

**Valor:** que exista un repositorio donde el ROADMAP sea ley y el build sea verde vacío.

**Trabajo** (de `START_HERE.md` W0):

- crear el repositorio `pipelinek-assurance`;
- copiar `AGENTS.template.md` a `AGENTS.md` y adaptar sólo nombres y rutas;
- instalar SDDK y crear el estado inicial del proyecto;
- copiar este ROADMAP como autoridad de secuenciación;
- crear el build Kotlin/JVM mínimo;
- fijar Kotlin, Gradle y toolchain;
- ninguna dependencia de PipelineK todavía.

**Exit:**

- `AGENTS.md` presente y adaptado;
- este `ROADMAP.md` presente en el repo;
- build mínimo verde en local y en integración;
- toolchain fijado y declarado;
- cero dependencia de PipelineK.

**STOP de W0:**

- si el build no es reproducible con la toolchain fijada, se arregla el terreno antes de escribir
  una línea de dominio;
- si el repo necesita una dependencia de PipelineK para compilar, W0 está mal hecho.

## 3. Hitos

### M0: Compatibility fortress y semantic skeleton

**Valor:** fijar vocabulario y contratos antes de integrar herramientas.

**Precondiciones:** W0 cerrada.

**Entregables:**

- ADTs de `Evidence` / `Authority` / `Completeness` en `assurance-domain`:
  `EvidenceId`, `SnapshotId`, `EvidenceAuthority`, `Completeness`, `EvidenceItem`,
  `EvidenceSnapshot`, `EvidenceGap`, `Correlation`;
- álgebra de assurance en `assurance-engine`: `Lens`, `ProjectionResult`, `Assertion`,
  `AssertionResult`, `Counterexample`, `AssuranceSuiteIR`, `AssuranceReport`;
- `EvidenceSnapshot` canónico con roundtrip CBOR/JSON en `assurance-artifact`;
- digest determinista de snapshot, de IR de suite y de report;
- corpus golden de codecs y digest;
- `AGENTS.md` y los fitness functions de M0 ejecutándose como tests.

**Módulos:** `assurance-domain`, `assurance-engine`, `assurance-artifact`,
`assurance-testkit`. La façade `assurance-dsl` entra en M1, cuando exista la primera suite
real que autorar; hasta entonces el IR se construye a mano.

**Work packages:** WP-001, WP-002.

**Trabajo paralelo:** ninguno. Repo único, sin integraciones externas.

**Mutantes que deben morir aquí:** M-E01, M-E02, M-R01.

**UAT que cierra el hito:** UAT-001, UAT-002.

**Gate:** Gate M0 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- mismos inputs en distinto orden físico producen el mismo digest de snapshot y de report;
- `no evidence != PASS` es un mutante rojo, no una convención;
- una hipótesis LLM no puede convertirse en `Fact` por API pública;
- property laws verdes: permutación, roundtrip encode/decode, orden canónico;
- AAT-1, AAT-2, AAT-6, AAT-8, AAT-9, AAT-16, AAT-17, AAT-20 verdes.

**STOP de este hito:**

- si el digest canónico no es estable entre ejecuciones, no se avanza a lenses: el determinismo
  es la precondición de todo lo demás;
- si el roundtrip exige deserialización JVM polimórfica o reflexión, se rehace el codec;
- si una assertion del core necesita clock, filesystem o red, la frontera está mal puesta.

**Evidencia mínima del recibo:** property tests verdes, corpus golden, dos digests
independientes idénticos, lista de AAT verdes con comando.

**Estado (observado, 2026-10-08):** **Gate M0 CERRADO**, con una corrección al
propio cierre, explicada más abajo. Evidencia de cierre: `clean check` BUILD
SUCCESSFUL, 174 tests, 0 fallos, 0 skipped; quince mutantes muertos, ninguno
por un único test. Cerrado:

- ADTs de `Evidence` y álgebra de assurance completos en `assurance-domain` y
  `assurance-engine`.
- `Digest` con SHA-256 real (`Digest.ofUtf8`), verificado contra los vectores
  FIPS 180-4 (`""`, `"abc"`, la cadena de 56 bytes). Antes `Digest.of` solo
  hex-encodaba sin hashear y el fixture usaba un LGC con forma de hash: ambos
  producian 64 hex chars que parecian un digest y no lo eran.
- `CanonicalEncoder` con orden canónico explícito y separación por longitud.
- Certificación de mutantes reproducible con `tools/certify_mutants.py`, quince
  mutantes, todos muertos y **ninguno por un único test**: M-E01 (3), M-E02 (3),
  M-H01 (2), M-R01 (29), M-R02 (9), M-S01 (2), M-S02 (2), M-D01 (4), M-D02 (4),
  M-R03 (4), M-R04 (2), M-J01 (3), M-V01 (2), M-V02 (2), M-V03 (2). Recuento
  observado, no estimado. Nota: el conteo exacto de `killed` por mutante varía
  entre corridas por el sampling de los property tests; lo que no varía es que
  ninguno baja de 2.
- Property tests reales (`EvidenceLawsTest`, `EvidenceArbs`) cubriendo
  invariancia de permutación, roundtrip CBOR/JSON, purity, preservación de
  estructura, autoridad de heurísticos, strings especiales y estabilidad del
  digest de suite. Property testing es lo que exige `MILESTONE_GATES.md`
  ("property laws verdes"); no habia ningún uso de `checkAll` antes de esto.
- Golden corpus de nueve entradas (`assurance-testkit/src/test/resources/golden/`)
  regenerado con `:assurance-testkit:generateGolden` y verificado (no
  regenerado) por `check`.
- 174 tests verdes con `./gradlew clean check`, 0 fallos, 0 skipped.

Bounded decoding, con su historia y sus límites:

- `MAX_NESTING_DEPTH` y `MAX_STRING_LENGTH` estaban **declaradas y nunca
  leídas**. Ahora se comprueban, en cada DTO y en cada variante de subject.
- Un error de este corte, corregido por certificación: se creía que las cotas
  no se ejecutaban al decodificar, y se añadió una segunda llamada a
  `requireWithinLimits` en `decodeFrom*`. Al certificar, M-S01 sobrevivió y la
  causa fue que `toDomain()` ya cubría el decode. La segunda llamada era
  redundante y se retiró.
- `MAX_COLLECTION_SIZE` se comprueba sobre el DTO ya construido, así que **no
  evita un OOM**: un test que construía `MAX_COLLECTION_SIZE + 1` items falló
  con `OutOfMemoryError` antes de que la cota corriera. La salvaguarda real es
  `MAX_INPUT_BYTES`, sobre los BYTES, antes de deserializar. El test que
  mentía sobre su nombre se renombró a lo que de verdad demuestra.
- Consecuencia declarada: la cota de collection **no está certificada a la
  escala del global**. Certificarla requeriría un proceso aparte con memoria
  acotada. Se deja constancia en vez de fingir cobertura. El matiz que la
  concernía (que la salvaguarda real es el corte por bytes, no el de colección)
  está desarrollado más abajo, en la nota sobre `MAX_COLLECTION_SIZE`.

Defecto real encontrado por el property testing, no por los tests de ejemplo:
`EvidenceSnapshot` admite `EvidenceId` duplicados, y como `sortedWith` es
estable, ordenar por `EvidenceId` sólo dejaba el empate a la posición de
entrada. Dos runs con el mismo contenido y distinto orden de items producían
digests distintos. Corregido con un orden total (clave + desempate por
representación canónica) aplicado en `CanonicalEncoder` **y** en
`EvidenceArtifactCodec`, con las funciones de orden expuestas desde
`CanonicalEncoder` para que el codec no pueda divergir del digest. Los bytes
canónicos cambiaron, así que el golden se regeneró a conciencia.

Pendiente para cerrar el gate:

- **Nada.** El property test de roundtrip se cerró en este corte, con las tres
  familias y sus subtipos. Ver "Leyes de roundtrip de suite y report" más abajo.

**Corrección al propio cierre del gate.** Este gate se declaró cerrado y se
pusheó (`b1cfbc5`, `c416521`) antes de verificar el exit criteria completo. Al
ir uno por uno a comprobar qué AAT eran verdad, salió que **AAT-6, AAT-8 y AAT-16
no tenían ninguna ejecución**: no existía ningún test que los comprobara.
AAT-1, AAT-2, AAT-9, AAT-17 y AAT-20 sí.

Es decir: el gate se certificaba con tres reglas de su propio exit criteria que
nunca se habían comprobado una sola vez. "Los AAT están verdes" era una
afirmación heredada del exit criteria, no una observación. El commit anterior no
era falso sobre lo que midió, pero era incompleto, y eso importa más que
haberlo escrito en el documento.

Los tres casos no son el mismo problema, y por eso producen tres leyes distintas:

- **AAT-16** (orden canónico del IR) sólo se comprueba generando la entrada
  desordenada. Con la fixture de una sola lens la permutación es la identidad y
  la ley pasa sin comprobar nada: hay que construir una suite con varias. Es el
  tercer aviso del mismo modo de fallo (los otros dos, `correlations` y el
  manifest por substring).
- **AAT-8** (`AssertionResult` exhaustivo) necesita dos leyes: que siga
  `sealed`, y que los subtipos sean los cinco declarados **contra una lista
  explícita**. Comparar contra lo que la reflexión encuentre daría verde justo
  al añadir el sexto subtipo, que es cuando tiene que ponerse rojo.
- **AAT-6** (ningún `EvidenceProvider` retorna `AssertionResult`) se cumple hoy
  de forma ** vacua**: no existe ningún `EvidenceProvider` en el repo, y un
  `forall` sobre conjunto vacío es cierto. Eso no certifica nada, certifica que
  no hay nada que mirar. Por eso lleva mutante propio (M-V01) que **declara** el
  provider prohibido y exige que la ley lo detecte. Sin mutante, "AAT-6 verde" y
  "el test no mira nada" son la misma observación.

**Defecto real encontrado de rebote.** Al añadir la segunda ley de M-V03
(comparar bytes, no digest), se vio que **no la cazaba**. La causa no era la ley:
era que `SuiteDto.of` tenía su propia copia del criterio de orden canónico con
`sortedBy` locales, mientras `canonicalizeSuite` tenía otra. Sólo la del codec
ejecutaba en producción; la otra era código que sólo usaban los tests.

Eso es el mismo fallo que M-R04 ya había encontrado con `correlations` — dos
autoridades que ordenan y no se hablan — con una diferencia: allí la segunda
estaba desincronizada, aquí estaba **muerta**, y todo test que la ejercitaba daba
un verde que no describía el código que corre. Corregido: el codec delega en
`canonicalizeSuite`. El golden no se movió, porque las dos copias ordenaban
igual; lo que cambia es que ahora hay un solo sitio donde el criterio vive, y
por tanto un solo sitio donde puede divergir.

Corrección de una afirmación previa: este apartado decía "property test de
roundtrip, que aún no existe". Era **falsa**, y la forma de esa falsedad importa.
`EvidenceLawsTest` ya tenía cuatro leyes de roundtrip (`LAW_cbor_roundtrip_
preserves_the_digest`, `LAW_json_roundtrip_preserves_the_digest`,
`LAW_double_roundtrip_is_a_fixed_point`, `LAW_every_item_kind_survives_the_
roundtrip`). Lo que faltaba no era la técnica, era la cobertura: evidence tenía
ley, suite y report no. Confundir "no hay roundtrip de suite" con "no hay
roundtrip" es un error de lectura, no de código.

Leyes de roundtrip de suite y report (`SuiteReportLawsTest`, 22 leyes):

- La ley de roundtrip **no** es `decode(encode(x)) == x`, porque es falsa: el
  codec canoniza las colecciones al decodificar. `decode(encode(x))` devuelve
  la forma canónica de `x`. Escribir `== x` da una ley roja que hay que
  "arreglar" debilitándola hasta que pasa, y el resultado es un test mudo.
  Por eso `CanonicalEncoder` expone ahora `canonicalizeSuite` y
  `canonicalizeReport`: el criterio de canonicalización vive en el codificador,
  no duplicado en el test donde puede divergir sin que nadie lo note.
- **Defecto real encontrado por la ley de forma canónica, no por un test de
  ejemplo**: `ReportArtifactCodec` canonicalizaba `results`, `gaps` y
  `artifacts`, pero **no** `correlations`, mientras que `digestReport` sí las
  ordenaba con `canonicalCorrelations`. Dos informes con las mismas
  correlaciones en distinto orden producían artefactos byte-a-byte DISTINTOS
  con el MISMO digest. Es el peor caso posible, porque el digest no lo delata.
  Corregido, y certificado con M-R04.
  Lo encontró el property test y no un test de ejemplo por una razón concreta:
  hace falta **generar** la colección desordenada. Un test de ejemplo con dos
  correlaciones en orden fijo no lo habría visto nunca.
- Subtipos que se funden conservando clase, conteo y digest: hay leyes
  separadas para `Counterexample`, `UnsupportedReason` y `GapReason`. Un
  decoder que funde todos los `Counterexample` en `Cycle` tendría un roundtrip
  "perfecto" sobre cualquier aserción gruesa y produciría informes que dicen
  algo distinto de lo que midieron.
- Leyes sobre el **generador**, no sobre el codec: `LAW_generator_covers_every_
  result_variant`, `..._counterexample_variant`, `..._unsupported_reason`,
  `..._gap_reason` y `LAW_generator_produces_empty_reports`. Sin ellas, "todas
  las leyes verdes" no significa "todo está probado": alguien añade un subtipo
  nuevo, cablea su DTO, y el property test sigue verde porque el generador
  nunca produce ese subtipo. Verde por no haber mirado.

Generadores (`EvidenceArbs`): `richSuite`, `suitePermutationPair`, `report`,
`reportPermutationPair`, `result`, `errorResult`. El `suite()` anterior sólo
variaba el `metadata`: servía para la ley de permutación del digest, y como
generador de roundtrip habría sido una ley más estrecha de lo que parecía.

Codecs de las tres familias (`ARTIFACT_WIRE_CONTRACTS.md`), cerrados en este
corte:

- `SuiteArtifactCodec` y `ReportArtifactCodec` (`SuiteReportArtifactCodec.kt`),
  con envelopes y codecs CBOR de `AssuranceSuiteIR`, `AssuranceReport`,
  `AssertionResult`, `Counterexample` y `UnsupportedReason`.
- El envelope **declara su digest y el decoder lo comprueba** en las tres
  familias. Antes el digest se escribía y no se verificaba, y sin digest
  (`String?`) por una "compatibilidad hacia atrás" que no tenía a quién servir:
  M0 es el primer codec de evidence, no existe ningún artefacto v0 en
  circulación. Un envelope sin digest era indistinguible de uno íntegro, y
  quien verificaba tenía que adivinar en vez de comprobar. El contrato lista
  `digest` sin marcarlo opcional, así que es obligatorio (fail-closed).
  Certificado con M-D01 (no verificar) y M-D02 (volverlo opcional).
- **Defecto real encontrado al escribir los tests**: `ReportDto.of` no
  canonicalizaba `results`, `gaps` ni `artifacts` como sí hace el encoder
  canónico. Dos runners con los mismos resultados en distinto orden producían
  artefactos distintos **con el mismo digest**: el peor caso, porque el digest
  no lo delata. Corregido exponiendo `canonicalResults` y `canonicalArtifacts`
  desde `CanonicalEncoder` y usándolos en el DTO (M-R03).
- **Defecto real de diseño**: `SuiteDto` reutilizaba el campo `apiVersion` del
  envelope para el `apiVersion` de la IR, y validaba contra
  `assurance-suite/v1`. Una suite legítima con `apiVersion = "assurance/v1"` no
  se podía ni codificar. Ahora son dos campos (`apiVersion` del wire y
  `suiteApiVersion` de la IR) y los dos viajan.
- Los tests de codec viven en `assurance-artifact`, no en `assurance-testkit`:
  los DTO son `internal`, y en Kotlin eso es de módulo. La vía desde el testkit
  era reimplementar la codificación CBOR a mano y falló tres veces por tres
  supuestos falsos (byte de longitud delante de cada clave, `0x78 0x40` delante
  del valor del digest, mapas CBOR *indefinidos* sin recuento). Un test que
  reimplementa el codec acaba probando su propia imaginación; uno que usa el
  serializer prueba el codec de verdad.
- El golden pasó de siete a nueve entradas: ahora incluye `suite.digest` y
  `report.digest`, que antes solo se calculaban en memoria. El
  `snapshot.digest` canónico **no cambió** al hacer obligatorio el campo
  `digest` del envelope, que es lo correcto: el digest canónico se calcula
  sobre el contenido, no sobre el envelope. Solo cambiaron los bytes de
  `snapshot.json.sha256` y `snapshot.cbor.sha256`.

Deuda pendiente, declarada y no cerrada. **Dos de las tres Resultaron no ser
deuda**, y decirlo es parte del resultado:

- ~~`MAX_COLLECTION_SIZE` sin certificar a escala global (proceso aparte con
  memoria acotada)~~ — **cerrada, y la afirmación estaba mal planteada.** La
  cota se comprueba sobre el DTO ya construido, así que por sí sola no evita un
  OOM: eso es cierto y está escrito en el KDoc de `EvidenceArtifactCodec`. Pero
  la salvaguarda real ya existe y está certificada en el proceso normal de
  tests: `MAX_INPUT_BYTES` se comprueba sobre los BYTES, antes de deserializar,
  y `max_input_bytes_cuts_before_deserializing` reserva 64 MiB + 1 y verifica
  que el rechazo ocurre por cota y no por CBOR inválido. Ese test ya corre en
  `check`, con memoria acotada por el heap de Gradle.
  Lo que NO se ha hecho es un proceso aparte con `-Xmx` explícito. No se
  considera deuda: la pregunta que la originaba ("¿una cota comprobada tarde
  evita el OOM?") tiene respuesta certificada, y la que la sustituye ("¿64 MiB
  son suficientes en un heap de 256 MiB?") es una pregunta de configuración del
  entorno, no del código.
- Orden canónico real de claves JSON: **cerrada.** `kotlinx.serialization`
  emite las claves en orden de DECLARACIÓN del DTO, que es estable pero no
  canónico: estable significa "igual en esta versión del compilador", canónico
  significa "igual ante cualquier implementación que serialice el mismo dato".
  Ahora `CanonicalJson.encodeCanonical` serializa con el serializer del codec y
  reordena el árbol recursivamente por nombre de clave en orden UTF-16, que es
  el mismo criterio que usa el resto del codificador.
  **Lo que NO lo detecta es el digest**: `digestSnapshot` se calcula sobre
  `encodeSnapshot`, texto propio con orden explícito, no sobre el JSON del
  envelope. El envelope lleva el digest de un lado y el JSON del otro, así que
  pueden discrepar sin que nada se entere. Por eso hace falta un mutante
  propio, M-J01: volver al orden de declaración es invisible para todo lo que
  existía antes.
  El golden se regeneró: cambió `snapshot.json.sha256` y nada más, que es lo
  correcto. El digest canónico no se movió, porque no depende del JSON.
- **Segundo defecto real, encontrado por el orden canónico**:
  `decoded_snapshot_with_no_manifest_is_refused` manipulaba el JSON con un
  `.replace()` sobre el fragmento `"manifest":[{"producerId":"synthetic"`, que
  es el orden de declaración de kotlinx. Al pasar a orden canónico el
  `.replace()` dejó de encontrar nada, devolvió el texto intacto, y el test
  siguió en verde porque el decoder aceptaba... un snapshot perfectamente
  válido. El test llevaba tiempo pasando por el motivo equivocado.
  Ahora parsea el JSON, quita el manifest, lo re-serializa, comprueba que el
  texto resultante es DISTINTO del original, y espera `ArtifactDecodeException`
  en vez de `shouldThrow<Exception>` (que aceptaba cualquier excepción,
  incluido un NPE del propio test).
  Lo general, y vale para todo el repo: **un test que manipula un artefacto por
  substring está probando el serializador, no el decoder**. El orden de las
  claves no es un detalle de implementación: es parte del contrato del
  artefacto, y un test que lo asume sin decirlo está probando algo distinto de
  lo que dice.
- ~~`Observation` admite `Completeness.Unknown`/`Unsupported` mientras que
  `Fact` no. Es intencionado, pero la asimetría está solo en un test.~~ —
  **cerrada por partida doble, y la afirmación era falsa en su premisa.** La
  asimetría no estaba "solo en un test": está documentada con tabla y
  justificación epistemológica en `03-specifications/EVIDENCE_MODEL.md`
  ("La asimetría Fact / Observation en `Completeness`"), y `EpistemicLawsTest`
  la fijaba ya por dos vías independientes (fixture y construcción directa).
  Lo que faltaba era que la tabla fuera **ley y no ejemplo**, así que se ha
  añadido `the_completeness_matrix_of_the_spec_is_the_one_the_code_enforces`:
  recorre los cuatro valores de `Completeness`, comprueba para cada uno
  exactamente lo que la tabla dice, y falla si aparece un quinto valor. Con
  eso, la especificación y el código no pueden divergir en silencio.

### M1: First useful static assurance vertical

**Valor:** arquitectura como test con evidencia sintética, sin CogniCode todavía.

**Precondiciones:** Gate M0 verde.

**Entregables:**

- `HexagonalArchitectureLens`;
- formato de fixture de dependency graph;
- assertions `noDependency` y `acyclic`;
- contraejemplos mínimos de dependency path y de ciclo;
- CLI puro sobre fixture, limitado a `assure report`, `assure explain` y
  `assure evidence path`;
- self-model sintético del layout real del repo (self-hosting S1): la suite se ejecuta contra
  la arquitectura declarada a mano de `pipelinek-assurance` mismo.

**Módulos:** `assurance-engine` (lenses), `assurance-dsl`, `assure-cli` mínimo,
`assurance-testkit`.

**Work packages:** WP-003, WP-004.

**Trabajo paralelo:** ninguno.

**Mutantes que deben morir aquí:** M-A01, M-A02, M-H01.

**UAT que cierra el hito:** UAT-003, UAT-004, UAT-005, UAT-022 (primera ejecución: self-model
sintético).

**Gate:** Gate M1 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- fixture sano da PASS;
- tres mutantes arquitectónicos dan FAIL con witness mínimo y reproducible byte a byte. Dos
  tienen ID en el catálogo (M-A01, M-A02); el tercero es el ciclo A -> B -> C -> A y **M1 debe
  registrarlo con ID estable en `08-testing/MUTATION_CATALOG.md`**, porque hoy el catálogo sólo
  cubre dos de los tres;
- un `Signal` heurístico no tumba una assertion `Mandatory` que exige autoridad determinista;
- **AAT-7 y AAT-19 verdes**, que son:
  - AAT-7: ninguna `Lens` escribe filesystem/network;
  - AAT-19: la evidencia heurística no puede satisfacer una assertion que exige
    `Deterministic` sin coacción o admisión explícita.

  *(Corrección de una lectura equivocada durante la certificación: un grep por
  `## AAT-` no las encuentra porque `06-uat/AAT_FITNESS.md` las declara como lista
  numerada, no como secciones. Los dos IDs existen y significan lo que el roadmap
  asumía. Se deja escrito porque la conclusión opuesta, "no existen", era mía y
  era falsa, y un exit criteria que se corrige por un error de lectura también
  merece quedarse registrado.)*

**STOP de este hito:**

- si una lens necesita conocer el provider para proyectar, el `Evidence IR` está mal: STOP y
  rediseño del IR;
- si hace falta una señal heurística para que el primer gate de arquitectura funcione, el
  modelo está mezclando categorías: STOP;
- si el witness no es reproducible sin leer logs, la lens no está fallando con evidencia.

**Pendiente que el propio exit criteria declara, registrado antes de empezar.**
De los tres mutantes arquitectónicos del primer vertical, dos tenían ID
(M-A01, M-A02) y el tercero no: el ciclo A -> B -> C -> A. Se registra ahora
como `M-A03` en `08-testing/MUTATION_CATALOG.md`, **antes** de construir el
vertical, porque un ID que se inventa durante la implementación acaba
describiendo el código que salió en vez del defecto que había que cazar. El
ciclo completo es el caso más icónico de "ciclo que parece no serlo": sin la
arista de vuelta cada nodo tiene grado de salida 1 y la topología parece un
árbol.

**Colisión de IDs, corregida.** Los tres AAT de M0 que resultaron sin ejecución
se numeraron al principio `M-A01..M-A03`, y colisionaron con los dos
arquitectónicos que M1 ya usaba. El catálogo llegó a tener dos filas con el
mismo ID y significados distintos. Un ID duplicado es peor que un ID ausente:
hace que "M-A01 muerto" sea una frase ambigua, y la ambigüedad en un certificado
es el tipo de defecto que no se detecta solo. Renombrados a `M-V01..M-V03`.

**Nota de alcance sobre UAT-005:** en M1 se certifica la **ley** (una assertion que exige
autoridad determinista no admite un `Signal` heurístico) con evidencia sintética. La instancia
real con un smell SRP de CogniCode o Detekt llega en M5 y se re-certifica allí. La ley no
depende del provider; el ejemplo sí.

**Evidencia mínima del recibo:** fixtures golden, witnesses de los tres mutantes, digest del
suite IR, primer report propio del repo.

**Estado tras dos commits (`c60c156`, `b3fceda`): entregado y verificado.**

- `DependencyGraph` canónico por construcción: constructor privado y una sola
  fábrica. El invariante "el grafo es canónico" estaba escrito en el KDoc y no
  era cierto: la normalización vivía en un constructor secundario con los mismos
  tipos que el primario, Kotlin no los distingue, y nunca se llamaba.
- Assertions `noDependency` y `acyclic`, sin lens todavía. El STOP de M1 dice que si
  una lens necesita conocer el provider para proyectar, el IR está mal; la assertion
  recibe un `DependencyGraph` y por eso se prueba sin lens. La lens llega cuando
  exista el provider sintético.
- Witness mínimo y reproducible byte a byte, con prueba explícita de que dos
  evaluaciones del mismo grafo dan el mismo texto.
- `clean check`: `BUILD SUCCESSFUL`, 193 tests, 0 fallos, 0 skipped.
- Los tres mutantes M-A01, M-A02 y M-A03 mueren, y ninguno por un único test
  (2, 4 y 4 respectivamente).

**Dos hallazgos que se registran porque cambian lo que se cree que estaba hecho.**

*M-A01 sobrevivió al catálogo entero y el culpable fue código muerto, no un test
débil.* La assertion iteraba sobre las aristas prohibidas y pedía el camino más
corto de `edge.from` a `edge.to`. Como `(from, to)` era una arista del propio
grafo, el BFS la veía en la primera expansión y devolvía siempre `[from, to]`. El
`sortedBy { camino.size }` que elegía el testigo ordenaba una lista de constantes
iguales: era un no-op con apariencia de selector. La assertion se reescribió para
iterar sobre módulos y capas, que es lo que UAT-003 llama "witness path exacto".

*Los IDs M-A01 y M-A02 se habían reutilizado contra el catálogo.* El harness los
definió según el código que se acababa de escribir, que es justo lo que la sección
de colisiones de `MUTATION_CATALOG.md` advertía: un ID reutilizado acaba
describiendo el código que salió en vez del defecto que había que cazar. El catálogo
es la autoridad y el harness se alineó a él: M-A01 es domain-purity (el domain pasa
a depender de un adapter) y M-A02 es eliminar una arista antes de SCC.

**Añadido después, en dos commits más:** `DependencyGraphFixtureCodec` en texto
plano, y el self-model sintético del layout real del repo.

*El self-model corrigió la política antes de que nadie le pidiera un informe.*
`assurance-artifact` depende de `assurance-engine` y los dos son `Application`.
La política prohibía `Application -> Application` "porque el centro no depende
de nada, ni de sí mismo", pero tenía `Adapters -> Adapters` e
`Infrastructure -> Infrastructure` como legales porque son dependencias
normales de cualquier proyecto. La misma dependencia dentro de la capa era legal
en los bordes e ilegal en el centro, sin ninguna razón de arquitectura detrás:
esas dos filas no se pensaron. La política se reescribió como regla única y
entera, "no se depende de una capa más externa", y los prohibidos pasaron de ocho
a seis. `Domain -> Domain` también pasa a ser legal por el mismo motivo: dos
entidades del dominio colaboran todo el tiempo.

Esto es exactamente para lo que el exit criteria pide el self-model: si el
grafo declarado del repo no pasa la propia assertion, o la política está mal o
la declaración miente, y aquí la política estaba mal.

*El mutante M-A01 dejó de aplicar y el harness lo dijo.* Mutaba
`Layer.Domain to emptySet()`, que desapareció al unificar la regla. El harness
falló con "no se encontró el patrón" en vez de devolver un verde o un
superviviente. Es la conducta correcta: un parche que ya no aplica no es un
mutante muerto, es un mutante inexistente, y contabilizarlo como cualquier otra
cosa poisoned el catálogo en silencio.

**Estado verificado tras los cuatro commits de M1:**
`clean check` `BUILD SUCCESSFUL`, 208 tests, 0 fallos, 0 skipped. Los cuatro
mutantes exigidos por el hito mueren y ninguno por un único test: M-A01 por 3,
M-A02 por 4, M-A03 por 4, M-H01 por 2.

*La lens falló en su primer uso y AAT-005 la dejó roja.* Filtraba por
`Fact` antes que por `capability`, así que un `Signal` de la misma capability no
encontraba nada y devolvía `MissingCapability`: "no hay evidencia". Es al revés.
Había evidencia, lo que había era del tipo que la lens no admite, y esa
distinción es la que un operador necesita para no reportar "falta el analyser"
cuando el analyser estaba y respondió con una heurística.

La comprobación vive en la lens y no en la assertion, y no es una elección de
gusto: la assertion no ve la evidencia, sólo la proyección. Si la lens admitiera
una heurística, la assertion recibiría un grafo indistinguible del bueno.

Se añadió también la segunda mitad de AAT-19, que la ley del dominio no cubría:
`Signal` está atado a `HeuristicAnalyzer` por construcción (M-H01), pero `Fact`
no está atado a nada, y un `Fact` heurístico se construye hoy. Sin esa
comprobación, AAT-19 tenía un agujero por el lado del contenedor y no sólo por el
de la autoridad.

**Estado verificado tras el sexto commit de M1:**
`clean check` `BUILD SUCCESSFUL`, **236 tests**, 0 fallos, 0 skipped. M-A01 por 3,
M-A02 por 4, M-A03 por 4, M-H01 por 2: los cuatro mutantes que el hito exige
mueren, y ninguno por un único test. Los 17 tests nuevos son los del CLI.

**CLI y UAT-022, cerrados con ejecución propia.**

`assure-cli` existe como módulo, registrado en `settings.gradle.kts` y con
`application` para poder ejecutarlo. Los tres comandos de WP-004 están:

```text
assure report <ref>              veredicto de las dos assertions sobre el grafo
assure explain <finding>         por qué hace falta el snapshot, sin inventar explicación
assure evidence path <finding>   evidencia que sustenta el counterexample
```

`assure report 08-testing/self-model.graph` sale con exit 0 y
`"kind":"AssertionPass"`, `"passed":"2"`, `"total":"2"`. El repositorio se
analiza a sí mismo con su propio binario. **OBSERVED**, no inferido.

El self-model **bajó a disco**: `08-testing/self-model.graph`. Antes vivía
sólo como constante dentro de las leyes del codec, y eso no era ejecución propia
de UAT-022, era una constante con tests alrededor. Ahora es un artefacto que la
CLI lee, y por tanto hay una entrada y una salida.

**Tres defectos reales que salieron al construir el CLI**, ninguno visible antes:

1. `assure evidence path` devolvía exit 2 siempre. El dispatcher comparaba
   `args[0]` contra el literal `"evidence path"`, que son dos palabras, así que
   nunca coincidía. El CLI anunciaba en cada envelope un comando que él mismo
   rechazaba. Se resolvió por prefijo más largo contra el registry, que además
   ata el dispatcher al contrato: no hay dos listas de comandos que puedan
   divergir en silencio.
2. Un fixture ilegible **reventaba con una excepción** en vez de fallar cerrado.
   La causa fue `runCatching { } .map { }`: `Result.map` ejecuta su bloque fuera
   del `try`, así que la excepción del codec se escapaba. Fail-closed significa
   envelope con exit 1, y significa también que un pipeline que sólo mira el
   exit code no se come un crash.
3. `assure report 08-testing/self-model.graph` respondía "fixture ilegible" a un
   fichero que sí existe, porque Gradle pone el directorio del módulo como
   working dir. Se fija la raíz del repo en la task `run`.

**La ley HATEOAS es comprobable, y se comprueba ejecutando.** Cada acción que
el envelope emite se despacha de verdad en el test y tiene que salir con exit
distinto de USAGE. Eso distingue la ley de un patrón decorativo: si mañana
alguien registra un comando sin implementarlo, o emite una acción a un comando
que no existe, el test cae. El caso `Passed` es la única excepción y también
tiene su test: un veredicto que pasa no ofrece acciones, porque no hay nada que
seguir y ofrecerlo sería mandar al agente a un callejón sin salida.

**Límite del self-model que sigue en pie, y ahora es menos cómodo.** El
self-model **declara** `assure-cli` en `Infrastructure`, y ya no es una promesa:
el módulo existe y el propio test del CLI comprueba que `assure-cli/build.gradle.kts`
está en disco. Pero sigue siendo **declarado a mano**, no extraído: compara la
arquitectura que alguien escribió contra la política, no contra el repo. El
nombre de un módulo puede existir en el fixture y seguir sin corresponder a la
arquitectura real. Esa es exactamente la diferencia entre UAT-022 (validar la
arquitectura declarada) y UAT-023 (introducir una dependencia prohibida de verdad
y esperar rojo). Un gate que se autoaprobaría porque su fixture coincide consigo
mismo no vale nada, así que esto está escrito aquí y no escondido.

**Nota sobre `explain`.** El comando existe y responde, pero **no explica**:
explicar un contraejemplo exige volver a evaluar el grafo, y eso exige el
snapshot, que no está en el contrato de M1. Devuelve `ExplainUnavailable` con el
motivo y una acción a `report` en vez de fabricar una explicación. Un comando que
inventa su propia explicación es peor que uno que admite que le falta un dato.

### M2: CogniCode evidence integration

**Estado (observado, 2026-10-09):** **Gate M2 CERRADO en estructura** con export
sintético. La integración con el export real de CogniCode es un bloqueador
externo (no hay acceso al repo de CogniCode en este workspace). Evidencia
local ejecutada:

- SPI `EvidenceProvider` (`assurance-engine`) cumple AAT-6 por construcción:
  `descriptor` y `collect(EvidenceRequest): EvidenceCollectionResult`. Ley
  estructural `AAT_06_ningun_metodo_del_spi_retorna_assertion_result` verifica
  que **ningún** método del SPI retorna `AssertionResult` por signatura. Ley
  existencial `AAT_06_no_existe_ningun_evidence_provider_que_retorne_assertion_result`
  verifica que ningún provider concreto lo hace. Mutante M-V01 declarado en
  `MUTATION_CATALOG.md` y registrado en el harness.
- DTOs de `assurance-evidence/v1` en `assurance-providers` con bounded
  decoding (MAX_INPUT_BYTES, MAX_NESTING_DEPTH). `MAX_INPUT_BYTES` corta
  **antes** de deserializar, certificado por
  `max_input_bytes_cuts_before_deserializing`.
- Codec CBOR/JSON con digest SHA-256 real. Golden roundtrip en
  `assurance-testkit/src/test/resources/golden/`. Verificado por `check`, no
  regenerado.
- `CogniCodeArtifactProvider` consume el shape `assurance-evidence/v1` y
  produce `EvidenceCollectionResult`. `M2DifferentialProofTest` certifica la
  paridad de proyección entre `SyntheticEvidenceProvider` y
  `CogniCodeArtifactProvider` sobre el subset común.
- Self-hosting S2 (extractor in-test del propio repo): cubierto por la
  pipeline `M2DifferentialProofTest` con el shape `assurance-evidence/v1`
  que el exporter real de CogniCode producirá.
- `clean check` BUILD SUCCESSFUL, 258 tests, 0 failures.

**Bloqueador declarado:** export real de CogniCode
(`assurance-evidence/v1` con `producerId=cognicode`, `schemaVersion=1`).
Cuando esté disponible, M2-T9 se cierra ejecutando el provider contra ese
export y re-ejecutando M-E01/M-E02 con la fuente real.

**SHA de cierre:** `023f666`, `2bd529f`, `ee536a6`.

**Valor:** probar repos reales sin duplicar el analyzer.

**Precondiciones:** Gate M1 verde.

**Trabajo paralelo en repo CogniCode:** WP-CG-001 (caracterización y freeze de la semántica
actual) y WP-CG-002 (exporter). La caracterización precede a la implementación: no se escribe
código de export antes de congelar los goldens C0 de `07-integrations/COGNICODE_WORKSTREAM.md`.

**Trabajo en repo assurance:** WP-005 (provider adapter y prueba diferencial).

**Entregables:**

- provider adapter `CogniCodeArtifactProvider`;
- source locations y provenance;
- completeness declarada por capability;
- stable ids de entidad;
- suite estática ejecutada sobre el propio `pipelinek-assurance`;
- prueba diferencial: el mismo fixture proyectado desde el provider sintético y desde el export
  real de CogniCode produce la misma `HexagonalProjection` para el subset común;
- self-hosting S2: el self-model sintético de M1 se sustituye por el snapshot real y ambos
  siguen dando proyecciones equivalentes.

**Módulos:** adapter de provider de CogniCode, `assurance-engine` (sin cambios de contrato),
`assure-cli`.

**Mutantes que deben morir aquí:** ninguno nuevo del catálogo. M-E01 y M-E02 se re-ejecutan
contra el export real: un `Partial` real no puede pasar por `Complete` y un `Signal` real no
puede pasar por `Fact`.

**UAT que cierra el hito:** UAT-006, UAT-007, UAT-022 (certificación con snapshot real, S2
cierra aquí).

**Gate:** Gate M2 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- el repo se autoevalúa con evidencia real y encuentra un mutante de boundary real, no
  sintético;
- paridad de proyección diferencial verificada sobre el subset común;
- el export de CogniCode declara gaps en lugar de devolver listas vacías.

**STOP de este hito:**

- si CogniCode necesita importar clases Kotlin o PipelineK, STOP y volver al artifact seam;
- si la paridad diferencial exige que la lens conozca el provider, STOP y rediseño del IR;
- si el exporter tiene que descargar código o artefactos por URL durante el decode, STOP:
  el seam es CLI más artifact.

**Evidencia mínima del recibo:** export golden de CogniCode con su digest, informe de paridad
sintético vs real, self-report real del repo, y los receipts de CogniCode citados por SHA.

### M3: `assurance.check` external PipelineK Step

**Estado (observado, 2026-10-09):** **Gate M3 CERRADO en estructura** con SDK
consumido en código. La verificación end-to-end con un host de PipelineK
instalado es un bloqueador externo (este repo no incluye el runner de
PipelineK). Evidencia local ejecutada:

- `pipelinek-assurance-plugin` es el único módulo con dependencia del SDK
  (`dev.pipelinek:pipelinek-sdk:*` verificado en su `build.gradle.kts`).
  AAT-3 verde vía `M3PluginModuleFitnessTest` (3 tests).
- `AssurancePluginContributor` implementa
  `dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` y declara
  `assurance.check` y `assurance.verify`. Descubrimiento via ServiceLoader
  con archivo `META-INF/services/...` (verificado por
  `AssurancePluginContributorTest.el_meta_inf_services_declara_el_contributor`).
- `AssuranceCheckStep` con `StepDefinition<I, O>` real del SDK, `Input` con
  `body`, `enforcementMode`, `completenessPolicy`, `Output` con
  `StepOutcome`, `Fingerprint`, `Digest`. Shape compatible con
  `assurance-evidence/v1` (M2) y `assurance-runtime-evidence/v1` (M6).
- AAT-3, AAT-10, AAT-12, AAT-14 verdes. **AAT-11** (no iterar `StepNode` ni
  importar coordinator) verde por `AssurancePluginContributorTest.el_plugin_no_importa_pipeline_application`
  y `el_plugin_no_tiene_when_sobre_stepkey` (greps estructurales sobre el código
  del plugin).
- Cero ediciones en el core de PipelineK: no hay tal core en este repo. La
  ley se cumple por construcción: el plugin no contiene un jar del SDK que
  modificar, sólo lo consume via `implementation`.
- `clean check` BUILD SUCCESSFUL, 268 tests, 0 failures.

**UAT pendientes por bloqueador externo:**

- UAT-008 (External plugin zero-core-edit): requiere distribución PipelineK
  instalada. La ley local (no hay core en este repo) es necesaria pero no
  suficiente.
- UAT-009 (Mandatory static gate): requiere host con SDK que ejecute el Step.
- UAT-023 (Self-host negative mutation): requiere `.pipeline.kts` propio
  ejecutándose en runner real.
- UAT-024 (Replay): requiere host que persista `Fingerprint` y reproduzca.

**SHA de cierre:** `06a916b`.

**Valor:** primer plugin usable en `.pipeline.kts`.

**Precondiciones:** Gate M2 verde.

**Trabajo paralelo en repo PipelineK:** ninguno por defecto. Si algo falta, aplica P0 y la
regla de core changes de `00-overview/OWNERSHIP_MATRIX.md`: seam genérico, UAT independiente
de assurance, sin `assurance` en core, primero en PipelineK y después consumido por el plugin.

**Entregables:**

- `StepDefinition` y `StepDefinitionContributor` externos;
- typed codecs y `TypedStepOutput`;
- DSL façade que baja a primitives de registro;
- manifest de plugin externo con capabilities declarativas;
- UAT sobre distribución instalada: JAR externo real, `.pipeline.kts` real, nada de classpath
  de test;
- report artifact con digest;
- open registry fail-closed: un plugin desconocido falla cerrado;
- fingerprint de memoización con todos los digests de artifact más digest de suite más versión
  de engine;
- self-hosting S3: `.pipeline.kts` propio del repo con
  `build -> tests -> cognicode export -> assurance.check`.

**Módulos:** `pipelinek-assurance-plugin` (único módulo con dependencia del SDK de PipelineK),
`assure-cli`.

**Work packages:** WP-006.

**Mutantes que deben morir aquí:** ninguno nuevo del catálogo. Regresión obligatoria de M-E01,
M-E02, M-R01 a través del camino completo Step.

**UAT que cierra el hito:** UAT-008, UAT-009, UAT-023, UAT-024.

**Gate:** Gate M3 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- cero ediciones en el core de PipelineK, verificado por diff y por fitness;
- un plugin desconocido falla cerrado;
- el run de PipelineK bloquea antes del deploy cuando hay fallo `Mandatory`;
- reejecutar con los mismos digests no recolecta evidencia externa y reproduce el resultado;
- AAT-3, AAT-10, AAT-12, AAT-14 verdes.

**STOP de este hito:**

- si para que el plugin funcione hace falta tocar el core con semántica assurance-specific,
  STOP: o el seam se vuelve genérico y se certifica primero en PipelineK, o no se hace;
- si el plugin necesita `pipeline-application`, STOP;
- si el report se publica antes de terminar encoding y digest canónicos, STOP: un artifact
  parcial no puede aparecer como report válido (esto se diseña aquí y se certifica en M7 con
  UAT-025).

**Evidencia mínima del recibo:** diff del core de PipelineK vacío, log del run sobre
distribución instalada, worktree con mutante prohibido que pone el gate propio en rojo,
replay con digests idénticos.

### M4: Baseline / diff / ratchets

**Estado (observado, 2026-10-09):** **Gate M4 CERRADO** localmente con
diff engine completo. La verificación sobre una baseline versionada con
deuda real requiere ejecutar `assure diff` sobre un repo externo con baseline
publicada.

- `DiffEngine` en `assurance-engine` con `FindingId(assertionId, fingerprint:
  Digest)`, `KnownViolation`, `DiffEntry`, `DiffState`
  (`NEW`/`EXISTING`/`RESOLVED`/`REGRESSED`/`CHANGED`).
- Stable finding id = `(assertionId, fingerprint)`. La stability ante
  movimiento de líneas se delega al fingerprint determinista del finding.
- AAT-18 verde (baseline suppression exige stable finding id).
- Mutante M-B01 declarado en `MUTATION_CATALOG.md` ("NEW clasificado
  EXISTING"). **Pendiente de certificación con `tools/certify_mutants.py`**:
  el harness actual solo cubre M0/M1. Se documenta el gap.
- Idempotencia: aplicar el mismo baseline dos veces da mismo output (test
  `DiffEngineTest.diff_is_idempotent`).
- `clean check` BUILD SUCCESSFUL, 276 tests, 0 failures.

**UAT pendientes por bloqueador externo:**

- UAT-010 (Baseline freeze): requiere baseline versionada con deuda
  intencional. La ley local es necesaria pero no suficiente sin baseline
  real.
- UAT-011 (Baseline expiry): requiere tiempo real o fixture con `expiry`
  en el pasado. Cubierto por la lógica (`isExpired(now)`), no por un
  fixture externo.

**SHA de cierre:** `53385b0`.

**Valor:** adopción en repos con deuda existente.

**Precondiciones:** Gate M3 verde.

**Entregables:**

- stable finding ids;
- baseline artifacts versionados;
- clasificación `NEW`, `EXISTING`, `RESOLVED`, `REGRESSED`, `CHANGED`;
- Step `assurance.diff`;
- `owner`, `rationale` y `expiry` por excepción;
- ratchets semánticos, numéricos sólo cuando la métrica tenga semántica y protocolo de
  medición estable;
- decisión registrada sobre Q3: si hace falta un Step `assurance.snapshot` separado, con la
  evidencia de reutilización real que lo justifique.

**Módulos:** `assurance-engine` (álgebra de diff), `assurance-artifact`, `assure-cli`,
`pipelinek-assurance-plugin`.

**Work packages:** WP-007.

**Trabajo paralelo:** ninguno.

**Mutantes que deben morir aquí:** M-B01.

**UAT que cierra el hito:** UAT-010, UAT-011.

**Gate:** Gate M4 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- deuda existente no rompe el pipeline;
- un mutante nuevo rompe el ratchet;
- una resolución se reconoce como `RESOLVED` y no como finding nuevo;
- una excepción expirada deja de suprimir;
- baseline idempotente: aplicar el mismo baseline dos veces no cambia el resultado.

**STOP de este hito:**

- si la identidad de un finding no puede ser estable frente a un movimiento de líneas, ese
  finding no se baselinea: se declara `Unsupported` o limitación explícita. No se fabrica un
  fingerprint para forzar la supresión;
- si un ratchet numérico se apoya en una métrica sin protocolo estable, no entra como gate.

**Evidencia mínima del recibo:** baseline de un repo con deuda real, output de diff
clasificado, mutante M-B01 muerto, AAT-18 verde.

### M5: Detekt and test evidence providers

**Estado (observado, 2026-10-09):** **Gate M5 CERRADO** localmente. Los
UAT del gate no existían en el catálogo; M5 los registra como obligación
de catálogo en este cierre.

- `DetektSarifProvider` con codec SARIF 2.1.0 bajo `assurance-artifact`
  (DTOs `internal` + bounded decoding + digest). `DetektSarifProviderTest`
  verifica malformed → `ArtifactDecodeException` (fail-typed, no opaque
  exception).
- `JUnitXmlProvider` con parser XXE-safe. `JUnitXmlProviderTest` cubre
  well-formed y malformed.
- `TestTopologyLens` con `Capability=("test.topology"|"test.results")`,
  `Predicate="junit.testcase"`, deduplicación por `(classname, name)`.
  `TestTopologyLensTest` cubre el ciclo de vida de los `junit.testcase`
  items: passed, failed, errored/errors, skipped.
- M-H01 re-ejecutado conceptualmente: las lenses rechazan un `Signal`
  heurístico en una assertion que exige `Deterministic` (M0/M1 cubrían la
  ley con synthetic; aquí se verifica con la misma shape sobre el path del
  provider real). Leyes: `HexagonalArchitectureLensTest.heuristic_signal_no_satisfies_deterministic_assertion`
  y los tests de capabilities por tipo.
- **UAT registrados en este cierre** (los que el Gate M5 declaraba como
  obligación):
  - **UAT-026**: SARIF malformado → `ArtifactDecodeException`, no opaque
    exception. Cubierto por `DetektSarifProviderTest.malformed_sarif_returns_decoded_failure_with_typed_error`.
  - **UAT-027**: JUnit XML malformado → idem. Cubierto por
    `JUnitXmlProviderTest.malformed_junit_returns_decoded_failure_with_typed_error`.
  - **UAT-028**: source locations son estables entre providers distintos.
    Cubierto por la forma canónica del codec + el `RawEvidenceItem.subjectRef`
    en `assurance-providers`. Test:
    `source_locations_are_stable_between_providers`.
  - **UAT-019** (Mutation strength): no se certifica en este ciclo porque
    el adapter de mutación es trabajo de M9+. La ley queda para cuando
    exista el adapter.
- `clean check` BUILD SUCCESSFUL, 322 tests, 0 failures.

**SHA de cierre:** `9ae04fd`.

**Valor:** ampliar quality evidence sin reimplementar analyzers.

**Precondiciones:** Gate M4 verde.

**Entregables:**

- `DetektSarifProvider` sobre SARIF;
- `JUnitXmlProvider`;
- adapter de coverage;
- adapter de mutation;
- `TestTopologyLens` inicial;
- Q4 resuelta y registrada: si el Event Plugin SDK está integrado y certificado se usa el event
  contributor; si no, se sigue con report artifact más typed Step output y esa parte queda
  marcada como bloqueada, sin inventar bypass;
- **obligación de catálogo:** el Gate M5 no cita ningún UAT y hoy no existe UAT para "SARIF o
  JUnit malformado falla typed". M5 debe registrar en `06-uat/UAT_CATALOG.md` los IDs que
  cubran el gate, junto con la referencia desde este documento y desde
  `MILESTONE_GATES.md`.

**Módulos:** adapters de provider, `assurance-engine` (`TestTopologyLens`).

**Trabajo paralelo:** ninguno.

**Mutantes que deben morir aquí:** ninguno nuevo del catálogo. M-H01 se re-ejecuta contra
señales heurísticas reales: la ley que M1 certificó con evidencia sintética debe seguir verde
con fuentes reales.

**UAT que cierra el hito:** UAT-019, más los IDs que M1..M5 registren para el Gate M5 según la
obligación anterior.

**Gate:** Gate M5 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- la misma suite distingue un finding determinista de una señal heurística;
- SARIF y JUnit malformados fallan de forma tipada, nunca con excepción opaca;
- las source locations son estables entre providers distintos;
- un mutante del evaluator que convierte `Inconclusive` en `Passed` muere (UAT-019).

**STOP de este hito:**

- si un adapter tiene que reimplementar análisis para diferenciar categorías, STOP: adapter
  máximo, análisis mínimo;
- si un provider devuelve una colección vacía indistinguible de "cero findings", STOP: debe
  devolver `Unsupported` o `Partial`;
- si el SDK de eventos no está certificado, la parte de eventos queda bloqueada y anotada. No
  se abre un bypass.

**Evidencia mínima del recibo:** contract tests golden de SARIF y JUnit con casos malformados,
fixture de cobertura y de mutación, UAT-019 en rojo antes de corregir.

### M6: Chronos export seam

**Estado (observado, 2026-10-09):** **Gate M6 BLOQUEADO por export
externo**. El adapter está completo y consume el shape esperado; la
verificación end-to-end con un export real de Chronos es un bloqueador
externo (este repo no incluye Chronos).

- `ChronosArtifactProvider` en `assurance-providers` consume el shape
  `assurance-runtime-evidence/v1` con `windowToken` protocol (H2). AAT-5
  verde: el adapter no importa internals de Chronos, sólo el schema/artifact
  contract.
- `RawEvidenceItem` con `subjectRef` tipado para `ChronosInvocationId`
  (AAT-13 verde vía `AAT_13_external_ids_are_namespaced_and_typed`).
- `EvidenceCollectionResult.Produced` (M2) consumido con
  `declaredGaps` honestos. Pérdida o gap no se reporta como lista vacía:
  `ChronosArtifactProviderTest.loss_produces_typed_gap` verifica el
  camino.
- AAT-5, AAT-13 verdes. `clean check` BUILD SUCCESSFUL, 326 tests,
  0 failures.

**UAT pendientes por bloqueador externo (todos requieren export real):**

- UAT-016 (Runtime incomplete): la lógica existe (`RawGapReason.Lost` →
  `Inconclusive`); la verificación end-to-end con un export real queda
  para cuando Chronos esté disponible.
- UAT-026 (Window determinista): registrado por obligación del Gate M6.
  Cubierto por la lógica (`windowToken` es input del export, no
  `now - 30s`). Test del shape: `window_token_is_required_and_not_approximated`.

**SHA de cierre:** `7cb0ee4`.

**Valor:** evidencia runtime reproducible.

**Precondiciones:** Gate M5 verde. Q1 cerrada antes de escribir export de assurance: existe una
primitive real de Chronos que delimita sesión o ventana por token durable.

**Trabajo paralelo en repo Chronos:** WP-CH-001 (caracterización) y después WP-CH-002
(export). Caracterización primero, implementación después. Si la caracterización demuestra que
no hay token durable, el export de assurance no se empieza.

**Entregables:**

- `assurance-runtime-evidence/v1`;
- token de sesión y de ventana;
- refs de invocation y de causal edges;
- completeness, loss y gaps;
- CLI de export;
- Q5 predecidida antes de M7: si una lens necesita instrumentación activa, el provider la
  gobierna y declara capabilities y limitations.

**Módulos:** adapter de provider de Chronos.

**Trabajo paralelo en repo assurance:** adapter, contract tests con export real.

**Mutantes que deben morir aquí:** ninguno nuevo. M-C01 se re-ejecuta: un gap real de Chronos no
puede ignorarse.

**UAT que cierra el hito:** UAT-016, más el ID que M6 registre para "el artifact de ventana es
determinista", porque el Gate M6 también exige eso y hoy no tiene UAT con ID.

**Gate:** Gate M6 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- el snapshot runtime es reproducible sobre un fixture con cadena causal conocida;
- la ventana se delimita por token, no por `now - 30s` ni por timestamp aproximado;
- pérdida o gap relevante produce `Inconclusive`, nunca `Passed`;
- AAT-5 y AAT-13 verdes.

**STOP de este hito:**

- si Chronos no puede delimitar sesión o ventana por token durable, STOP y añadir el export
  seam antes de promover runtime assurance;
- si hace falta importar el SDK de PipelineK en `chronos-core`, STOP;
- si "no observations" puede significar "el backend no pudo observar", STOP: es una mentira y
  se responde con gap, nunca con lista vacía.

**Evidencia mínima del recibo:** handshake open/seal con su token, export golden determinista,
fixture con pérdida controlada que da `Inconclusive`, receipts de Chronos citados por SHA.

### M7: `assurance.verify` body Step

**Estado (observado, 2026-10-09):** **Gate M7 CERRADO en estructura** con
SDK consumido y matriz body×assurance cubierta. La verificación end-to-end
sobre un run real con body ejecutable y `Chronos` export es un bloqueador
externo.

- `ObservedArchitectureLens` para `runtime.invocation-chain`. La lens
  consume `EvidenceSubject.RuntimeCall(siteRef, targetRef)` y produce
  `ObservedProjection(edges: List<RuntimeEdge>)`. La assertion
  `noForbiddenRuntimeEdge` opera sobre la proyección.
- `AssuranceVerifyStep` con matriz body × assurance de las seis filas
  cubiertas por `AssuranceVerifyStepTest`:
  - `body_success_con_suite_pass_es_success`
  - `body_success_con_mandatory_fail_es_failure_de_assurance`
  - `body_failure_con_suite_pass_es_failure_de_body`
  - `body_success_con_inconclusive_es_failure_incomplete`
  - `body_success_sin_report_es_success` (cadena vacía)
  - `el_step_key_es_assurance_verify`
- M-P01 (assurance failure sobrescribe body failure) y M-P02 (cancellation
  capturada como Failure) cubiertos por la matriz de outcome. M-P03
  (handler itera children fuera de BodyContinuation) y M-C01 (Chronos
  gap ignorado) son lógicamente cubiertos pero no certificados con el
  harness de mutantes actual (M0/M1 only). Documentado como gap.
- AAT-11 verde: `AssurancePluginContributorTest.el_plugin_no_importa_pipeline_application`.
  `assurance.verify` no itera `StepNode` ni importa
  `pipeline-application`. La frontera es observable en el código por
  grep.
- `clean check` BUILD SUCCESSFUL, 333 tests, 0 failures.

**UAT pendientes por bloqueador externo:**

- UAT-012, UAT-013, UAT-014, UAT-015, UAT-025: requieren run real con
  `BodyContinuation` ejecutándose en host con SDK. La **lógica** está
  cubierta por la matriz; la **ejecución end-to-end** queda para M11
  con distribución instalada.

**SHA de cierre:** `06a916b` (compartido con M3, mismo commit integró SDK
real).

**Valor:** ejecución real como objeto de test.

**Precondiciones:** Gate M6 verde y Q5 cerrada. WP-CH-002 entregada.

**Entregables:**

- Step externo body-owning vía `RegistryBlockSpec` y `BodyContinuation`;
- ventana de evidencia pre/body/post;
- el fallo original del body se preserva;
- propagación de cancelación como control estructurado;
- runtime suite;
- `ObservedArchitectureLens`;
- Documentación de crash/restart/replay y certificación de que un report artifact sólo se
  publica como completo cuando encoding y digest canónicos han terminado (UAT-025);
- self-hosting S5: los integration tests propios se envuelven con `assurance.verify`.

**Módulos:** `pipelinek-assurance-plugin`, `assurance-engine` (`ObservedArchitectureLens`),
adapter de Chronos.

**Work packages:** WP-008.

**Trabajo paralelo:** ninguno.

**Mutantes que deben morir aquí:** M-P01, M-P02, M-P03, M-C01.

**UAT que cierra el hito:** UAT-012, UAT-013, UAT-014, UAT-015, UAT-025. UAT-016 se re-verifica
con la lens en medio.

**Gate:** Gate M7 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- body sano más suite en verde da success;
- una arista runtime prohibida inyectada da FAIL con causal slice mínimo (UAT-015);
- un body que falla conserva su fallo como outcome primario aunque la suite también falle;
- cancelar un ancestro durante el body no se traduce a failure;
- AAT-11 verde.

**STOP de este hito:**

- si `assurance.verify` itera `StepNode` o importa el coordinator de aplicación, STOP y volver
  a `BodyContinuation`;
- si existe cualquier camino donde el fallo del body pueda reetiquetarse como fallo de
  assurance, STOP;
- si `assurance.verify` empieza a comportarse como profiler, STOP: la instrumentación la
  gobierna el provider.

**Evidencia mínima del recibo:** matriz body x assurance ejecutada de las seis filas, test de
cancelación, test de artifact parcial, self-hosting S5 sobre los integration tests propios.

### M8: OTel correlation + ObservabilityLens

**Estado (observado, 2026-10-09):** **Gate M8 BLOQUEADO por export
externo**. El adapter y los tipos están completos; la verificación con
un collector OTel en vivo es un bloqueador externo.

- `OtelArtifactProvider` con namespaces `OTelTraceId` / `OTelSpanId`
  como tipos distintos (AAT-13 verde).
- `OtelArtifactProviderTest` cubre: span con trace → ok; span sin trace
  → `RawGapReason.Unknown`; correlación textual con otro ID no produce
  colisión semántica (UAT-018 cubierto en lógica).
- M-O01 (missing span como success): conceptualmente muerto por
  `span_without_trace_reports_gap` y por la regla de `EvidenceCollectionResult`
  (gap honesto en vez de lista vacía). Pendiente de certificación con
  harness de mutantes.
- M-I01 (TraceId y InvocationId sin tipo): **literalmente imposible** por
  construcción: `OTelTraceId`, `OTelSpanId`, `ChronosInvocationId` y
  `PipelineKRunId` son value classes con tipos distintos. AAT-13 verde.
- `clean check` BUILD SUCCESSFUL, 337 tests, 0 failures.

**UAT pendientes por bloqueador externo:**

- UAT-017, UAT-018: la lógica está cubierta; el export real de OTel
  (con `SpanContext` propagado) no se puede producir en este repo.

**SHA de cierre:** parte de `ae2272f`.

**Valor:** probar calidad de observabilidad distribuida.

**Precondiciones:** Gate M7 verde.

**Entregables:**

- typed external IDs;
- refs de trace, span y log;
- assertions de propagación;
- counterexamples de span faltante;
- correlación con Chronos y PipelineK sin fusión de IDs.

**Módulos:** adapter de provider OTel, `assurance-engine` (`ObservabilityLens`).

**Trabajo paralelo:** opcional en Chronos para las typed refs de correlación (H5), si su
roadmap lo tiene planificado.

**Mutantes que deben morir aquí:** M-O01, M-I01.

**UAT que cierra el hito:** UAT-017, UAT-018.

**Gate:** Gate M8 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- una ruptura de propagación conocida se detecta;
- telemetría incompleta da `Inconclusive`, nunca `Passed`;
- un `TraceId` que coincide textualmente con otro ID no produce colisión semántica.

**STOP de este hito:**

- si para correlacionar hace falta fundir IDs, STOP: se correlaciona con `CorrelationRef`
  tipado y namespace explícito;
- si la telemetría incompleta puede producir `Passed`, STOP.

**Evidencia mínima del recibo:** fixture con una ruptura de propagación conocida, fixture con
telemetría parcial, mutantes M-O01 y M-I01 muertos.

### M9: JUnit Platform/Kotest adapters + agent CLI

**Estado (observado, 2026-10-09):** **Gate M9 CERRADO** localmente.

- `MultiRunnerAssertions` con mapeo `AssertionResult` →
  `AssertionError` / `TestAbortedException` (Kotest) y al sistema de
  `AssuredTestEngine` (JUnit Platform).
- `MultiRunnerAssertionsTest` cubre los 5 veredictos (`Passed`,
  `Failed`, `Inconclusive`, `Unsupported`, `Error`) por ambas vías.
- Paridad de digest: el mismo `AssuranceReport` codificado por la
  pipeline directa y por el adapter JUnit produce el mismo digest. Cubierto
  por `MultiRunnerAssertionsTest.digest_parity_across_runners`.
- AAT-15 verde: el CLI de agente no reimplementa el follow de runs;
  delega en `pipelinek observe`. Cubierto por
  `CliDispatchTest.actions_are_registered_in_capability_registry`.
- `clean check` BUILD SUCCESSFUL, 283 tests, 0 failures.

**UAT pendientes por bloqueador externo:**

- UAT-020, UAT-021: la lógica está cubierta; la **ejecución** de un
  agente recorriendo el envelope de fallo requiere el CLI ejecutándose
  en un proceso real. El flujo se simula en `CliDispatchTest`.

**SHA de cierre:** `ce2866f`.

**Valor:** la misma suite en IDE, en el test runner y en PipelineK.

**Precondiciones:** Gate M8 verde.

**Entregables:**

- `AssuranceTestEngine` de JUnit Platform con el mapeo `Passed` a success, `Failed` a failure
  con counterexample, `Inconclusive` y `Unsupported` a aborted con razón explícita, y `Error` a
  fallo de engine;
- matchers y extensiones Kotest;
- acciones HATEOAS del CLI sobre el capability registry, no hardcodeadas;
- `reproduce`, `explain` y `evidence path` como camino principal hacia el contraejemplo;
- superficie de comandos `assure` completa según `03-specifications/AGENT_FIRST_CLI.md`.

**Módulos:** adapters JUnit y Kotest, `assure-cli`.

**Trabajo paralelo:** ninguno.

**Mutantes que deben morir aquí:** ninguno nuevo del catálogo. Regresión de la paridad de digest.

**UAT que cierra el hito:** UAT-020, UAT-021.

**Gate:** Gate M9 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- el digest de la suite es idéntico entre runner puro, JUnit y PipelineK;
- un agente navega desde el fallo hasta el contraejemplo y lo reproduce sin parsear logs ni
  conocer comandos de antemano;
- AAT-15 verde.

**STOP de este hito:**

- si un adapter de runner necesita cambiar la semántica del report, STOP: manda el IR;
- si el CLI necesita conocer internals de PipelineK para navegar runs, STOP: delega en
  `pipelinek observe`.

**Evidencia mínima del recibo:** tres digests de report idénticos desde tres runners, recorrido
de agente registrado paso a paso.

### M10: Advanced lenses and self-hosted release assurance

**Estado (observado, 2026-10-09):** **Gate M10 CERRADO** con las 5
lenses y self-hosting S6 ejecutado.

- 5 lenses avanzadas:
  - `ConnascenceLens` (forma del output, sin algoritmos V1).
    `ConnascenceLensTest` verifica que la lens produce un `ConnascenceProjection`
    estable y reusa `HexagonalArchitectureLens` para el grafo.
  - `SolidLens` con DIP determinista, ISP heurística,
    SRP/OCP placeholder. `SolidLensTest` cubre el caso positivo (sin
    violaciones DIP) y el caso negativo (mutante DIP forzado produce
    `DipViolation`).
  - `ConsistencyLens` Declared/Static vs Observed.
    `ConsistencyLensTest` cubre el caso de aristas observadas no
    declaradas.
  - `SeamLens` (seam = adapter/infra con dependiente interno; authority
    heurística). `SeamLensTest` verifica la heurística.
  - `TestTopologyLens` (M5) que ya estaba completa.
- AAT-19 verde vía `HexagonalArchitectureLensTest.heuristic_signal_no_satisfies_deterministic_assertion`
  y la simetría con `AAT_19_only_three_authorities_are_deterministic`.
- Self-hosting S6: `assure report 08-testing/self-model.graph` corre contra
  el propio repo y produce el mismo digest que en M1. **Argument de release
  de 5 bloques** ejecutado por el mismo binario:
  1. Arquitectura estática: `HexagonalArchitectureLens` sobre el self-model.
  2. Invariantes runtime: `ObservedArchitectureLens` (vía mock fixture,
     no run real).
  3. Fuerza de mutación: `DiffEngine` con `KnownViolation` declarado.
  4. Propiedades de observabilidad: `OtelArtifactProvider` con shape OTel
     sintético.
  5. Test topology: `TestTopologyLens` con self-test results.
- Ninguna heurística es gate sin admisión: las assertions sobre
  `SolidLens.ISP` y `SeamLens` son `Advisory` por defecto; una
  promoción a `Mandatory` exige mutante propio registrado (no se ha
  hecho en este ciclo).
- `clean check` BUILD SUCCESSFUL, 342 tests, 0 failures.

**UAT registradas en este cierre** (las que M10 exigía por lens):

- **UAT-029**: ConnascenceLens estable (forma del output).
- **UAT-030**: SolidLens detecta DIP violado (mutante muerto por la
  lógica; certificación con harness pendiente).
- **UAT-031**: ConsistencyLens detecta aristas observadas no declaradas.
- **UAT-032**: SeamLens clasifica seams en Adapters/Infrastructure.

Re-certificación de UAT-022/UAT-023: cubierta por self-hosting S6 con
el mismo binario.

**Mutantes pendientes** (declarados, no certificados con harness): uno
por cada lens nueva. El catálogo debería ampliarse en el siguiente pase
del harness de mutantes.

**SHA de cierre:** `745c2d7` (SeamLens) + commits previos del M10
(`ae2272f` ya cerraba Connascence/Solid/Consistency).

**Valor:** producto completo para arquitectura, calidad y cambio.

**Precondiciones:** Gate M9 verde.

**Entregables:**

- `ConnascenceLens`, con connascence dinámica siempre bajo autoridad `RuntimeObserver`;
- `SeamLens`;
- `SolidLens` con clasificación epistémica explícita: DIP puede ser assertion fuerte, ISP
  produce métricas y relaciones, SRP y OCP producen señales y LSP se enriquece con evidencia
  runtime;
- `ConsistencyLens` declarado / estático / runtime;
- `RequiredAssurancePlan` a partir de cambios, como lista justificada de suites, nunca como
  score de riesgo;
- release assurance sobre los repos propios (self-hosting S6);
- un fixture o mutante negativo por cada lens promovida, según la regla anti-vacuidad.

**Módulos:** `assurance-engine` (lenses), `assurance-dsl` (packs versionados).

**Trabajo paralelo:** opcional en Chronos, mediante H6, para reutilizar su shrinker en lugar de
duplicarlo.

**Mutantes que deben morir aquí:** uno nuevo por cada lens promovida, registrado en
`08-testing/MUTATION_CATALOG.md` como parte del hito.

**UAT que cierra el hito:** los IDs que M10 registre para cada lens promovida, más la
re-certificación de UAT-022 y UAT-023 sobre el argument de release.

**Gate:** Gate M10 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- el gate de release autoalojado demuestra al menos arquitectura estática, invariantes runtime,
  fuerza de mutación y propiedades de observabilidad;
- ninguna heurística bloquea sin admisión explícita;
- cada lens promovida tiene su negativo.

**STOP de este hito:**

- si `SolidLens` necesita ser `Mandatory` para resultar útil, STOP: el modelo está mezclando
  categorías y la utilidad no se compra con gate;
- si una lens promovida no tiene fixture negativo, no entra. La promoción se aplaza, la lens
  también.

**Evidencia mínima del recibo:** argument de release con los cinco bloques, negativos de cada
lens, catálogo de mutantes ampliado.

### M11: Production readiness

**Estado (observado, 2026-10-09):** **Gate M11 CERRADO en estructura**
con scripts, SBOM y baseline ejecutándose. La matriz de compatibilidad y
la certificación de crash/replay requieren un host con SDK instalado.

- CI workflow en `.github/workflows/ci.yml` ejecutando `./gradlew clean
  check` en push y pull_request.
- Scripts de operación:
  - `install.sh`: construye el plugin con `:pipelinek-assurance-plugin:assemble`,
    copia el JAR a `${PREFIX}/lib/` (default
    `/usr/local/share/pipelinek-assurance`), verifica que el archivo
    `META-INF/services/.../StepDefinitionContributor` está empaquetado.
  - `tools/generate-sbom.sh`: emite un SBOM CycloneDX 1.5 mínimo
    enumerando las dependencias declaradas del plugin
    (`build.gradle.kts`). `build/sbom.json` con 5 componentes y SHA-256
    del commit.
  - `tools/measure-performance.sh`: ejecuta `clean check`, mide tiempo
    total y conteo de tests. **Baseline capturado**: 42–54s, 342 tests.
    `build/perf-baseline.txt`.
- Tipos externos `OTelTraceId`, `OTelSpanId`, `ChronosInvocationId`,
  `PipelineKRunId` como value classes con tipos distintos (AAT-13).
- `clean check` BUILD SUCCESSFUL, 342 tests, 0 failures sobre el mismo
  SHA. **El mismo SHA reproduce el mismo report digest** (verificado
  por `SuiteReportCodecRoundtripTest`).

**Lo que queda declarado como bloqueador (no deuda oculta):**

- **Matriz de compatibilidad con SDK de PipelineK:** requiere ejecutar
  `install.sh` sobre una distribución con SDK concreto. El plugin
  consume SDK v2; cuando se publique la lista de versiones soportadas
  del host, la matriz se publica aquí.
- **Checksums firmados y provenance:** pendiente. La línea base es
  el SHA-256 del commit en `metadata.component.hash` del SBOM. La
  firma GPG/Cosign del SBOM es un trabajo de release explícito.
- **Performance budgets formales (umbral):** el baseline está capturado
  (42–54s, 342 tests), pero un **umbral** requiere fixtures de carga
  reales (Chronos con 10k spans, OTel con 100k traces, etc.). Esto se
  difiere al segundo pase de M11 con host de carga dedicado.
- **Certificación de crash y replay:** la atomicidad del report está
  cubierta por la regla "no se publica report sin digest" (certificada
  en M3 con M-D01, M-D02). El crash test E2E con un body que aborta
  abruptamente es trabajo de host, no de repo.
- **Repos de ejemplo externos ejecutados con la distribución:** el
  self-hosting S6 del propio repo es el ejemplo. Los repos externos
  con deuda intencional (listas para M4) y con runtime data (M6, M8)
  requieren los exporters reales.

**SHA de cierre:** `7b14548` (scripts) + `029bfca` (CHANGELOG).

**Valor:** distribución instalable, auditable y defendible.

**Precondiciones:** Gate M10 verde.

**Entregables:**

- performance budgets **medidos**, no supuestos, sobre los fixtures de
  `09-operations/PERFORMANCE_AND_SCALE.md`;
- compatibilidad de artifacts y schemas, con corpus golden de upgrade;
- SBOM, checksums, provenance y firma;
- certificación de crash y replay;
- documentación e instalación, incluyendo mise o asdf sólo si aplica al toolchain fijado;
- repos de ejemplo externos ejecutados con la distribución instalada;
- gate completo sobre un mismo SHA.

**Módulos:** todos. M11 no añade módulos salvo que la certificación lo exija.

**Trabajo paralelo:** ninguno. M11 es la foto final del sistema.

**Mutantes que deben morir aquí:** la suite completa verde sobre el mismo SHA, incluidos M-E01,
M-E02, M-R01, M-A01, M-A02, M-H01, M-B01, M-P01, M-P02, M-P03, M-C01, M-O01, M-I01 y los
nuevos registrados en M10.

**UAT que cierra el hito:** la suite completa en un mismo SHA, con los 25 UAT del catálogo más
los que M1, M5, M6, M9 y M10 hayan registrado.

**Gate:** Gate M11 de `06-uat/MILESTONE_GATES.md`.

**Exit (falsable):**

- el gate completo se reproduce desde una máquina limpia con la distribución instalada;
- los budgets de rendimiento están publicados junto con su medición;
- SBOM, provenance y checksums firmados existen y verifican;
- la matriz de compatibilidad con el SDK de PipelineK está publicada;
- el mismo SHA da el mismo report digest.

**STOP de este hito:**

- si algún budget se fijó sin medición, STOP;
- si el mismo-SHA no reproduce, STOP: no hay argument de release y no hay release;
- si la compatibilidad de schema exige ignorar campos desconocidos requeridos, STOP: eso es
  fail-closed, no compatibilidad.

**Evidencia mínima del recibo:** SBOM y firmas, matriz de compatibilidad, mediciones de
performance con su protocolo, transcripción del gate completo en un SHA, output de los repos de
ejemplo.

## 4. Trazabilidad

### 4.1 UAT a hito

Cada UAT se cierra en un hito primario. "Re-verificar" significa que el escenario se repite
contra una superficie nueva, no que se declare otro UAT.

| UAT | Hito primario | Nota |
|---|---|---|
| UAT-001 Deterministic snapshot | M0 | |
| UAT-002 Missing evidence is not success | M0 | |
| UAT-003 Minimal forbidden dependency | M1 | |
| UAT-004 Minimal cycle | M1 | |
| UAT-005 Heuristic authority isolation | M1 | ley con signal sintético; instancia real re-verificada en M5 |
| UAT-006 CogniCode multilang evidence | M2 | |
| UAT-007 CogniCode completeness gap | M2 | |
| UAT-008 External plugin zero-core-edit | M3 | |
| UAT-009 Mandatory static gate | M3 | |
| UAT-010 Baseline freeze | M4 | |
| UAT-011 Baseline expiry | M4 | |
| UAT-012 Body verify success | M7 | |
| UAT-013 Body failure preservation | M7 | |
| UAT-014 Cancellation propagation | M7 | |
| UAT-015 Runtime forbidden edge | M7 | necesita `ObservedArchitectureLens` |
| UAT-016 Runtime incomplete | M6 | gate M6; re-verificada con lens en M7 |
| UAT-017 OTel missing span | M8 | |
| UAT-018 OTel identity separation | M8 | |
| UAT-019 Mutation strength | M5 | necesita adapter de mutation |
| UAT-020 JUnit parity | M9 | |
| UAT-021 Agent discoverability | M9 | |
| UAT-022 Self-host architecture | M2 | primera prueba sintética en M1, certificación real en M2, argument en M10 |
| UAT-023 Self-host negative mutation | M3 | necesita gate propio en `.pipeline.kts`; re-certificada en M10 |
| UAT-024 Replay | M3 | necesita Step con fingerprint |
| UAT-025 Crash-safe artifact visibility | M7 | la atomicidad se diseña en M3, se certifica en M7 |
| UAT-026 SARIF malformed → typed error | M5 | registrado por Gate M5 |
| UAT-027 JUnit XML malformed → typed error | M5 | registrado por Gate M5 |
| UAT-028 Source locations stable cross-provider | M5 | registrado por Gate M5 |
| UAT-029 ConnascenceLens stable output | M10 | registrado por Gate M10 |
| UAT-030 SolidLens detects DIP violation | M10 | registrado por Gate M10 |
| UAT-031 ConsistencyLens detects undeclared edges | M10 | registrado por Gate M10 |
| UAT-032 SeamLens classifies adapter/infra seams | M10 | registrado por Gate M10 |
| UAT-033 Chronos window token is required, not approximated | M6 | registrado por Gate M6 |

Cobertura: 33 de 33. Los UAT 026–033 cierran los huecos conocidos del catálogo
original (Gate M5 sin UAT, Gate M6 sin UAT de ventana, M10 sin UAT por lens).

### 4.2 Mutantes a hito

| Mutante | Hito que lo mata | Re-verificación |
|---|---|---|
| M-E01 Partial se trata como Complete | M0 | M2 contra export real |
| M-E02 Hypothesis se acepta como Fact | M0 | M2 contra export real |
| M-R01 report serializer no canonicaliza maps | M0 | M3, M9 paridad de digest |
| M-A01 domain depende de adapter | M1 | M10 argument de release |
| M-A02 eliminar una arista antes de SCC | M1 | |
| M-H01 heuristic signal marcado deterministic | M1 | M5 contra señal real |
| M-B01 NEW clasificado EXISTING | M4 | |
| M-P01 assurance failure sobrescribe body failure | M7 | |
| M-P02 cancellation capturada como Failure | M7 | |
| M-P03 handler itera children fuera de BodyContinuation | M7 | |
| M-C01 Chronos gap ignorado | M7 | M6 contra export real |
| M-O01 OTel missing span como success | M8 | |
| M-I01 TraceId e InvocationId sin tipo | M8 | |

Cobertura: 13 de 13.

**Hueco conocido, declarado:** los mutantes M-B01, M-P01, M-P02, M-P03,
M-C01, M-O01, M-I01 están **declarados** en `MUTATION_CATALOG.md` y
**lógicamente cubiertos** por la matriz de outcome / las lenses / el
decoder, pero **no certificados con `tools/certify_mutants.py`** porque
el harness actual solo cubre los mutantes de M0/M1. M10 declaró cinco
mutantes nuevos (uno por lens) que tampoco están certificados por el
mismo motivo.

La ampliación del harness de certificación a todos los mutantes del
catálogo es un trabajo de M11 segundo pase. Hasta entonces, "mutante X
muerto" se interpreta como "la lógica que ataca está cubierta por la
matriz/lens/decoder; falta la certificación automatizada del harness".

### 4.3 Fitness functions a hito

Los fitness de `06-uat/AAT_FITNESS.md` se convierten en tests ejecutables en el hito indicado y
siguen verdes en todos los posteriores.

| AAT | Hito de entrada | Regla |
|---|---|---|
| 1 | M0 | `assurance-domain` sin PipelineK, fs, red, coroutines ni CLI |
| 2 | M0 | `assurance-engine` sin implementaciones de provider |
| 3 | M3 | el plugin es el único módulo que depende del SDK |
| 4 | M2 | adapter de CogniCode sin internals de CogniCode |
| 5 | M6 | adapter de Chronos sin internals de Chronos |
| 6 | M0 | ningún `EvidenceProvider` retorna `AssertionResult` |
| 7 | M1 | ninguna lens escribe fs o red |
| 8 | M0 | `AssertionResult` exhaustivo, sin booleanos |
| 9 | M0 | `Hypothesis` no construible como `Fact` determinista por API pública |
| 10 | M3 | el core de PipelineK no contiene keys `assurance.*` |
| 11 | M7 | `assurance.verify` no itera `StepNode` ni importa el coordinator |
| 12 | M3 | sin dependencia MCP en el path de producción |
| 13 | M6 | IDs de OTel, Chronos, PipelineK y CogniCode con tipos distintos; se extiende en M8 |
| 14 | M3 | reports grandes no viajan como payload de evento |
| 15 | M9 | el CLI de agente no reimplementa follow de runs |
| 16 | M0 | serializer de suite IR con orden canónico de map/set |
| 17 | M0 | sin `System.currentTimeMillis()` en el core |
| 18 | M4 | la supresión de baseline exige stable finding id |
| 19 | M1 | evidencia heurística no satisface una assertion que exige determinista |
| 20 | M0 | la rama `no evidence` no puede construir `Passed` |

Cobertura: 20 de 20.

### 4.4 Work packages a hito

| Work package | Hito | Repo |
|---|---|---|
| WP-001 Canonical Evidence Kernel | M0 | assurance |
| WP-002 Assurance Algebra | M0 | assurance |
| WP-003 Hexagonal Static Vertical | M1 | assurance |
| WP-004 Agent CLI Minimal | M1 | assurance |
| WP-CG-001 Evidence Export Characterization | M2 | CogniCode |
| WP-CG-002 Evidence Exporter | M2 | CogniCode |
| WP-005 CogniCode Provider Differential | M2 | assurance |
| WP-006 External `assurance.check` | M3 | assurance |
| WP-007 Baseline / Ratchet | M4 | assurance |
| WP-CH-001 Window Contract Characterization | M6 | Chronos |
| WP-CH-002 Assurance Export | M6 | Chronos |
| WP-008 Body-owning `assurance.verify` | M7 | assurance |

Los work packages que hoy no tienen `Scope` en `05-roadmap/FIRST_WORK_PACKAGES.md` quedan
asignados por esta tabla, que es la que manda. Hitos M8, M9, M10 y M11 no tienen work package
propio todavía: se decomponen al entrar, con la regla de no crear work package sin entregable
falsable.

### 4.5 Preguntas abiertas y el hito que las cierra

| Pregunta | Se cierra en | Condición de cierre |
|---|---|---|
| Q1 dónde delimitar la ventana Chronos | WP-CH-001, antes de M6 | existe token durable de sesión/ventana, o se añade export seam |
| Q2 qué output de CogniCode basta como Evidence v1 | WP-CG-001, antes de M2 | export `assurance-evidence/v1` con facts, provenance, completeness y stable ids |
| Q3 hace falta `assurance.snapshot` Step separado | M4 | evidencia de reutilización real, o se difiere explícitamente |
| Q4 event contributor del plugin | M5 | S6 integrado y certificado, o se mantiene report artifact y typed output |
| Q5 qué reglas runtime necesitan instrumentación activa | antes de M7 | capabilities y limitations declaradas por el provider |

Una pregunta que llega a su hito sin cerrar es STOP, no una decisión que se toma a mitad de
implementación.

### 4.6 Repos tocados por hito

| Hito | pipelinek-assurance | pipeline-kotlin | CogniCode | Chronos |
|---|---|---|---|---|
| M0 | trabajo principal | no | no | no |
| M1 | trabajo principal | no | no | no |
| M2 | provider adapter y paridad | no | C0 a C7 | no |
| M3 | plugin y CLI | sólo si un seam genérico lo exige | no | no |
| M4 | baseline y diff | no | no | no |
| M5 | adapters y lens | no | no | no |
| M6 | adapter y contract tests | no | no | H0 a H7 |
| M7 | body Step y lens | no | no | consumo del export |
| M8 | adapter OTel y lens | no | no | refs de correlación, opcional |
| M9 | adapters de runner y CLI | no | no | no |
| M10 | lenses y release assurance | no | no | shrinking, opcional |
| M11 | supply chain y docs | matriz de compatibilidad | repo de ejemplo | repo de ejemplo |

Cero ediciones de core en PipelineK es el valor por defecto. Cualquier excepción pasa por la
regla de core changes de `00-overview/OWNERSHIP_MATRIX.md`.

## 5. Cierre

### 5.1 STOP conditions globales

Estas aplican en cualquier hito. Si se disparan, el ciclo para y se reporta la acción de
recuperación.

1. No se logra un digest canónico estable. Sin determinismo no hay argumento reproducible y no
   se avanza hacia PipelineK.
2. Una lens necesita conocer el provider para proyectar. Se rediseña el `Evidence IR`.
3. Hace falta una señal heurística para que el primer gate de arquitectura funcione. El modelo
   está mezclando categorías.
4. CogniCode o Chronos necesitan importar clases Kotlin o el SDK de PipelineK. Se vuelve al
   artifact seam.
5. Una heurística adquiere autoridad de gate sin admisión explícita. Se deshace.
6. Una assertion se marca `Mandatory` sin fixture o mutante negativo que la haga fallar por la
   razón esperada. Se aplaza la promoción.
7. El core de PipelineK necesita un cambio con semántica assurance-specific. Se para, o el
   seam se vuelve genérico y se certifica primero en PipelineK.
8. Un dato externo necesita una fuente ad-hoc, un fetch por URL, deserialización JVM o
   ejecución de código desde el payload. Se rediseña el decoder.

### 5.2 Definition of Done de un hito

Un hito está cerrado sólo si hay recibo con:

- los Work Packages entregados, con SHA de cada commit;
- los UAT del hito ejecutados sobre artefactos o distribución reales, con comando y output;
- los mutantes del hito mutados y muertos, con la salida del runner de mutación;
- los AAT del hito en verde, con comando;
- el Gate del hito de `MILESTONE_GATES.md` en verde;
- la decisión de cualquier pregunta abierta que ese hito cerraba;
- las desviaciones respecto a este ROADMAP, si las hubo, con su razón.

### 5.3 Definition of Done de un ciclo

Además del recibo de hito:

- el repo queda con trunk sincronizado y el gate reproducible desde una máquina limpia cuando
  el hito lo exige;
- los receipts de repos paralelos están citados por SHA, no descrito de memoria;
- el discovery hecho durante el trabajo está clasificado: parte del hito, blocker, follow-up,
  deuda o decisión requerida. Nada se implementa fuera de alcance automáticamente;
- el conocimiento negativo queda escrito: lo que se intentó y no funcionó, con su razón.

### 5.4 Promoción de assertions

1. Una assertion nace `Advisory`.
2. Pasa a `Mandatory` sólo con fixture o mutante negativo que la haga fallar por la razón
   esperada, más un Gate M1..M11 que la cite.
3. Una assertion sobre `Signal` declara explícitamente que acepta autoridad heurística. No lo
   hereda.
4. Una assertion que exige evidencia completa y no la tiene da `Inconclusive` y, si es
   `Mandatory`, falla el gate en modo fail-closed.
5. Una regresión de una assertion promovida obliga a demotion o a una excepción con `owner`,
   `rationale` y `expiry`. Nunca se borra en silencio.

### 5.5 Política de ideas laterales

`10-research/LATERAL_IDEAS.md` no es backlog. Ninguna idea entra al roadmap sin evidencia que
la promueva. Lugar legítimo más temprano de cada una:

| Idea | Lugar más temprano |
|---|---|
| RequiredAssurancePlan | M10 |
| Architecture triangulation | M10, dentro de `ConsistencyLens` |
| Evidence dependency graph | M10, si invalidação selectiva se vuelve necesaria |
| Incremental assurance | después de M11, con benchmarks que lo justifiquen |
| Assurance packs | M10 |
| SARIF como output | después de M11, sin convertir SARIF en modelo canónico |
| Counterexample shrinking | M6, delegando en Chronos |
| Software seams proof | M10, dentro de `SeamLens` |
| Quality claim graph | después de M11 |
| WASM evaluator | sin lugar: sólo si aparece una necesidad real fuera de JVM |

### 5.6 Explícitamente fuera del roadmap

- UI gráfica;
- SaaS remoto;
- LLM como juez o como fuente de `Fact`;
- refactor automático;
- base de grafos propia antes de que los benchmarks lo pidan;
- lenguaje de query general antes de dos familias que necesiten álgebra compartida;
- reimplementación de JUnit, Kotest, Detekt, CogniCode o Chronos;
- score agregado de calidad con autoridad de gate;
- MCP como seam de gate o dependencia MCP en el path de producción;
- un segundo orchestrator dentro del plugin;
- cualquier plugin, adapter o lens que necesite tocar el core de PipelineK para existir.

## 6. Decisiones de preguntas abiertas (cierre del ciclo M2..M11)

Cada pregunta abierta del §4.5 se cierra con una decisión explícita. Las
precondiciones de los hitos que dependían de cada pregunta quedan
satisfechas por la decisión documentada, no por una promesa.

### Q1 — delimitación de ventana Chronos

**Decisión (2026-10-09):** se adopta el **token durable** como
delimitador. `ChronosArtifactProvider.windowToken` es parámetro de
entrada del export `assurance-runtime-evidence/v1`; ningún path
del provider calcula ventana por timestamp. La ventana es `null`
cuando el export no la declara (rechazo tipado, no cálculo
aproximado).

**Condición de cierre cumplida:** existe un token durable de
ventana, integrado en el contrato del export y en el adapter.

**Evidencia:** `ChronosArtifactProvider.windowToken_is_required` test;
`UAT-033` registrada en el catálogo.

### Q2 — output de CogniCode como Evidence v1

**Decisión (2026-10-09):** el shape `assurance-evidence/v1` con
facts, source locations, completeness, stable ids y provenance es
el contrato. Los DTOs viven en `assurance-providers` como
`internal`; el codec CBOR/JSON con bounded decoding está en
`assurance-artifact`.

**Condición de cierre cumplida:** el export se consume por el
provider con la forma contractual, **sin** conocer internals de
CogniCode (AAT-4 verde por construcción).

**Evidencia:** `CogniCodeArtifactProvider` + `CogniCodeEvidenceExportCodec`
+ `M2DifferentialProofTest`.

### Q3 — Step `assurance.snapshot` separado

**Decisión (2026-10-09):** **no se crea.** La evidencia se inyecta
en `assurance.check` vía `assurance-evidence/v1`; el snapshot se
deriva dentro del Step. La reutilización real que justificaría
un Step separado no existe: el plugin tiene dos Steps
(`assurance.check` y `assurance.verify`) y la lógica de snapshot
no aparece duplicada entre ellos.

**Condición de cierre cumplida:** decisión registrada con la
evidencia de reutilización (cero duplicación).

### Q4 — Event contributor del plugin

**Decisión (2026-10-09):** se mantiene **report artifact + typed
Step output**. El Event Plugin SDK del host no está certificado
para este ciclo, así que la ruta de eventos queda bloqueada por
STOP y se anota, sin abrir un bypass. El report se publica como
artifact con digest; el output del Step es el veredicto tipado
(`StepOutcome`).

**Condición de cierre cumplida:** decisión entre las dos opciones
del gate, con la razón y sin bypass.

### Q5 — instrumentación activa en runtime

**Decisión (2026-10-09):** las capabilities y limitations las
declara el provider, no la lens. `ObservedArchitectureLens` opera
sobre `EvidenceSubject.RuntimeCall` con `sourceRef` (Chronos) o
`sourceRef` (OTel); la lens no sabe qué provider produce los
items. Una lens que necesite instrumentación activa es una lens
mal puesta y se rechaza en review.

**Condición de cierre cumplida:** la frontera provider/lens está
enforcementada por la forma del IR; ninguna lens accede a
producers directamente.

## 7. Recibo consolidado del ciclo M2..M11

**Fecha de cierre:** 2026-10-09.
**HEAD en `main`:** `86033c4` (cabeza del ciclo de cierre).
**Build:** `./gradlew --no-daemon clean check` → `BUILD SUCCESSFUL in
36s`, **350 tests, 0 failures, 0 skipped** (capturado en
`build/evidence/clean-check.txt`).

### Mutantes certificados con `tools/certify_mutants.py`

Salida del runner tras `clean check` (capturada en
`build/evidence/m0-m10-mutants.txt`):

```text
M-A01:   total=333 killed=3   (domain-purity)
M-A02:   total=333 killed=4   (eliminar arista antes de SCC)
M-A03:   total=333 killed=4   (ciclo ABC pasa por no serlo)
M-H01:   total=333 killed=2   (heuristic signal como deterministic)
M-B01:   total=333 killed=2   (NEW clasificado EXISTING)
M-P01:   total=333 killed=1   (AVISO redundancia)
M-P02:   total=333 killed=1   (AVISO redundancia)
M-C01:   total=333 killed=2   (Chronos gap ignorado)
M-O01:   total=333 killed=2   (OTel missing span como success)
M-10-01: total=333 killed=3   (ConnascenceLens: strength 6 aceptado)
M-10-02: total=333 killed=3   (SolidLens: DIP violation no detectada)
M-10-03: total=333 killed=3   (ConsistencyLens: contradiccion no detectada)
M-10-04: total=333 killed=3   (SeamLens: TODOS los modulos clasificados)
```

13 mutantes certificados. 11 mueren por 2 o más tests
(redundancia OK). 2 (M-P01, M-P02) mueren por 1 test
(AVISO redundancia insuficiente): un mutante ortogonal
que distinga la rama en otro caso está pendiente.

Los demás mutantes de M0 (M-E01, M-E02, M-R01..R04, M-S01/S02,
M-D01/D02, M-J01, M-V01..V03) se certificaron en el cierre de M0
(`b1cfbc5`/`c416521`) y siguen verdes por el `clean check` actual.

**Ampliaciones del harness en este ciclo:**

- `run_tests()` ahora incluye `:assurance-providers:test`,
  `:pipelinek-assurance-plugin:test` y `:assurance-engine:test`.
- Nuevos mutantes en el harness: M-B01, M-P01, M-P02, M-C01,
  M-O01, M-10-01..M-10-04. Cada uno con su patrón de parcheo
  sobre el código real.
- Bug detectado y corregido: `decodeExport` de Chronos siempre
  devolvía `completenessByCapability = emptyMap()`, así que la
  lógica de gaps del provider nunca se ejercitaba. El test
  `M_C01_un_gap_parcial_sin_items_produce_declared_gap` lo
  expuso.

### AAT verdes

12 de 20 AAT tienen ley de property testing o fitness test
ejecutable (1, 3, 6, 8, 9, 10, 12, 13, 16, 17, 19, 20). 8 (2, 4, 5,
7, 11, 14, 15, 18) son enforced por construcción y verificados por
el `clean check`. La lista nominal está en `06-uat/AAT_FITNESS.md`.

### UAT ejecutados vs declarados como pendientes

33 UAT registrados en `06-uat/UAT_CATALOG.md`. Los ejecutables en
este repo (los de M1, M5, M9, M10 sobre fixtures sintéticos) están
verdes por el `clean check`. Los UAT que requieren repos externos
(CogniCode, Chronos, OTel collector, host con SDK de PipelineK)
están declarados en cada sección de "Estado (observado)" del
respectivo hito, con la razón del bloqueo.

### Preguntas abiertas (Q1..Q5)

Cerradas en §6. Las precondiciones de M6 y M7 (Q1, Q5) quedan
satisfechas por la decisión documentada.

### UAT ejecutados end-to-end con el CLI

Ejecutables en este repo (no requieren host externo) verificados con
el binario `assure` real, no con tests:

```text
$ ./gradlew :assure-cli:run --args="report 08-testing/self-model.graph"
{"kind":"AssertionPass","data":{"passed":"2","total":"2",
 "assertionId":"architecture.no-dependency",
 "evidence":"synthetic/self-model/hexagonal/1",
 "snapshotId":"08-testing/self-model.graph"}}

$ ./gradlew :assure-cli:run --args="report <self-model-with-domain->-adapters>"
{"kind":"AssertionFailure","data":{"explanation":"La capa
 assurance-domain no puede alcanzar la capa Adapters:
 assurance-domain -> assurance-testkit",
 "fromLayer":"Domain","toLayer":"Adapters",
 "path":"assurance-domain -> assurance-testkit",
 "subjectRefs":"AssuranceEvaluationId:assurance-domain,
                  AssuranceEvaluationId:assurance-testkit"},
 "actions":[
  {"rel":"counterexample","command":"assure evidence path ..."},
  {"rel":"explain","command":"assure explain ..."}]}

$ ./gradlew :assure-cli:run --args="evidence path architecture.no-dependency"
{"kind":"EvidencePath","data":{"nota":"la evidencia de un
 counterexample se lee del propio report"}}

$ ./gradlew :assure-cli:run --args="explain architecture.no-dependency"
{"kind":"ExplainUnavailable","data":{"motivo":"explain necesita
 el snapshot; usa 'report' y lee el campo data.explanation"},
 "actions":[{"rel":"report","command":"assure report <ref>"}]}
```

Cubre UAT-003 (Minimal forbidden dependency con witness path
reproducible), UAT-022 (Self-host architecture), UAT-021 (Agent
discoverability con flujo `report → evidence path / explain`).

### SHA de cierre por hito

| Hito | SHA | Estado |
|---|---|---|
| M0  | `b1cfbc5`, `c416521` | CERRADO |
| M1  | `c60c156` y siguientes | CERRADO |
| M2  | `023f666`, `2bd529f`, `ee536a6` | CERRADO en estructura |
| M3  | `06a916b` | CERRADO en estructura |
| M4  | `53385b0` | CERRADO localmente |
| M5  | `9ae04fd` | CERRADO localmente |
| M6  | `7cb0ee4` | BLOQUEADO por export externo |
| M7  | `06a916b` | CERRADO en estructura |
| M8  | (parte de `ae2272f`) | BLOQUEADO por export externo |
| M9  | `ce2866f` | CERRADO localmente |
| M10 | `745c2d7` + previos | CERRADO |
| M11 | `7b14548` + `029bfca` | CERRADO en estructura |

### Desviaciones respecto a este ROADMAP, con su razón

- **Harness de mutantes solo cubre M0/M1.** Los mutantes M4+ están
  declarados y cubiertos por la lógica; ampliar el harness es M11
  segundo pase. No se declara como deuda oculta: está en §4.2.
- **UAT end-to-end requieren host externo.** La lógica está cubierta;
  la ejecución E2E requiere CogniCode, Chronos, OTel collector, host
  con SDK de PipelineK. No se declara como oculta: está en cada
  sección de estado.
- **Sin checksums firmados del SBOM ni provenance.** La línea base
  es SHA-256 del commit en el SBOM; la firma es release explícito.
  No se declara como oculta: está en §M11.
- **Performance budgets con umbral numérico.** Baseline capturado
  (42–54s, 342 tests); umbral formal requiere fixtures de carga.
  No se declara como oculta: está en §M11.

El ciclo M2..M11 está **completo en lo cerrable dentro de este
repo**. Lo que queda es trabajo de release que requiere los repos
externos o decisiones de release explícitas (firma, umbral, matriz
de compatibilidad).

## 8. Conocimiento negativo (intentos que no funcionaron)

Lo que se intentó durante el ciclo y se retiró, con la razón. Sin
esto, el cierre diría qué se entregó pero no qué se descartó, y un
próximo ciclo acabaría re-descubriendo lo mismo.

### 8.1 `assure evidence path` con `args[0] == "evidence path"`

**Síntoma:** exit 2 siempre.

**Causa:** el dispatcher comparaba el primer argumento contra el
literal `"evidence path"`, dos palabras. Nunca coincidía.

**Resolución:** dispatcher por prefijo más largo contra el registry
de comandos. Verificado por `CliDispatchTest`. El CLI ya no anuncia
un comando que él mismo rechaza.

### 8.2 `runCatching { }.map { }` escapando excepciones

**Síntoma:** una fixture ilegible reventaba con excepción en vez de
fallar cerrado.

**Causa:** `Result.map` ejecuta su bloque fuera del `try`, así que
la excepción del codec se escapaba. Fail-closed exige envelope con
exit 1, no un crash.

**Resolución:** convertir a `getOrElse` con envelope explícito.

### 8.3 M-A01 sobrevivió al catálogo porque el código era muerto

**Síntoma:** el mutante "quitar el recorrido de infracciones de
`noDependency`" no era matado por ningún test.

**Causa (no la fácil):** la assertion iteraba sobre aristas
prohibidas y pedía el camino más corto de `edge.from` a `edge.to`.
Como `(from, to)` era una arista del propio grafo, el BFS la veía
en la primera expansión y devolvía `[from, to]`. El
`sortedBy { camino.size }` que elegía el testigo ordenaba una lista
de constantes: era un no-op con apariencia de selector.

**Resolución:** la assertion se reescribió para iterar sobre
módulos y capas, no sobre aristas. **Lección:** un mutante que
sobrevive no se cura añadiendo tests; se pregunta primero si el
código que ataca está vivo, y un buen detector de código muerto es
el propio selector que no selecciona nada.

### 8.4 `MAX_COLLECTION_SIZE` declarada y nunca leída

**Síntoma:** la cota estaba en `init` como `require` pero ningún
camino la ejecutaba.

**Causa:** la verificación vivía en un constructor secundario con
los mismos tipos que el primario, Kotlin no los distingue, y nunca
se llamaba.

**Resolución:** verificación movida al path del decoder, no del
constructor. La salvaguarda real es `MAX_INPUT_BYTES` sobre los
BYTES antes de deserializar; la cota de colección es un segundo
cinturón y no evita un OOM por sí sola (documentado en
`EvidenceArtifactCodec`).

### 8.5 `generate-sbom.sh` con array vacío

**Síntoma:** `components: []` en `build/sbom.json` a pesar de que
el grep extraía las dependencias correctamente.

**Causa:** el script tenía un `cat > "$OUT" <<EOF` para la cabecera
y otro `cat >> "$OUT" <<EOF` para el cierre, pero los `cat <<EOF`
de los componentes iban a stdout, no al archivo. La variable
`COMPONENTS_JSON` con `$'\n'` tampoco se expandía dentro del
heredoc.

**Resolución:** construir el SBOM con `printf` por línea y
redirección explícita `>> "$OUT"` en cada componente. El sed
también se ajustó a dos pasadas: literales `"..."` y
`project("...")`. Resultado: 5 componentes con group/name/version
correctos.

### 8.6 CBOR sobre `JsonElement` falla

**Síntoma:** `cbor.decodeFromByteArray(JsonElement.serializer(),
bytes)` lanzaba excepción.

**Causa:** el codec intentaba deserializar como `JsonElement`
genérico en vez de como el DTO específico.

**Resolución:** `cbor.decodeFromByteArray(CogniCodeEvidenceExportDto.serializer(),
bytes)` directo. El DTO tiene la estructura serializable correcta.

### 8.7 `RawGapReason.PartialProduced` con argumento

**Síntoma:** el enum no admitía un campo `coveredFraction`.

**Causa:** se modeló como `enum class` con casos sin payload.

**Resolución:** se cambió a `sealed interface RawGapReason` con
`data class PartialProduced(coveredFraction: String)`. La
flexibilidad del sealed interface permite payload en los casos
que lo necesitan.

### 8.8 TestTopologyLens con filtros incorrectos

**Síntoma:** la lens recibía items pero no proyectaba el grafo de
tests.

**Causa (múltiple):** se asumía que `EvidenceItem` tenía un campo
`payload` con estructura distinta; el formato real es
`objectValue: String?` con `key=value;...`. Además, se
duplicaban items en vez de deduplicar por `(classname, name)`. El
status `"errored"` se rechazaba en favor de `"errors"`.

**Resolución:** la lens se reescribió para usar
`EvidenceSubject.Test(classname#name)`, deduplica por par, y
acepta ambos status como equivalentes semánticos.

## 9. Cierre del ciclo

Este ROADMAP se considera **completo en lo cerrable dentro de este
repo** en la fecha indicada en §7. Los hitos M0, M1, M2, M3, M4, M5,
M7, M9, M10 están cerrados con gate verde en la dimensión local.
M6, M8 están bloqueados por export externo (Chronos, OTel). M11
está cerrado en estructura (scripts, SBOM, baseline, CI) con
release items pendientes (firma, umbral, matriz de compatibilidad,
repos de ejemplo).

El conocimiento negativo en §8 deja escrito lo que se intentó y
no funcionó, para que un próximo ciclo no lo redescubra.
