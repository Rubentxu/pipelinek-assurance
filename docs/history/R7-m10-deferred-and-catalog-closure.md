# Recibo R7 — Cierre de M10 diferido, M-P03, M-I01 y v0.8.0

**Bloque:** R7 — cierre de la deuda M10 explícita + mutantes M-P03/M-I01 + consolidación M11.
**Release propuesta:** `v0.8.0`.
**Fecha:** 2026-10-10.
**SHA integrado:** `bd775fa` (HEAD tras R7.8 PackArtifactCodec + M-CODEC01 + fix del init de Fact).

## Trabajo ejecutado

### R7.1 — R5.5 RequiredAssurancePlan (era "diferido")

Implementación del plan como lista justificada de suites, **nunca como
risk score**. La API solo expone `selections: List<SuiteSelection>`
donde cada selección tiene un `Reason` sellado.

- `RequiredAssurancePlan.build(input: Input): Plan` con tres reglas
  de inclusion en orden de prioridad:
  1. `MandatoryBaseline` — suites con `metadata["mandatory"]="true"`.
  2. `TouchedByChange(path)` — suites cuyo `suiteId` aparece como
     prefijo en `changedPaths`.
  3. `NewFindingsPresent` — suites con al menos un finding
     `DiffState.New` cuyo `assertionId` empieza con el `kind` de
     alguna lens de la suite.
- `selections` siempre ordenado por `suiteId.value` para que el
  digest no dependa del orden de iteración.
- `init { require(... size == size.toSet()) }` rechaza duplicados
  `(suiteId, reason)`.

### R7.2 — R5.6 AssurancePack versionado (era "diferido")

`data class AssurancePack` con `name`, `packVersion`, `description`,
`suites: List<SuiteRef>` y `rules: List<Rule>`. Cada `Rule` es un
sealed interface con tres variantes que se mapean 1:1 a las tres
`Reason` de `RequiredAssurancePlan`:

- `Rule.Mandatory(suiteId)` → `Reason.MandatoryBaseline`
- `Rule.Touched(suiteId, prefix?)` → `Reason.TouchedByChange(prefix)`
- `Rule.NewFindings(suiteId)` → `Reason.NewFindingsPresent`

Validación en `init`:
- `name` y `packVersion` no vacíos.
- `suuites` no vacías.
- `suiteId` únicos.

### R7.7 — DSL `assurancePack { suite { ... } }` (M10 "façade assurance-dsl")

El ROADMAP §3 M0/M1/M10 menciona una "façade `assurance-dsl`" que
nunca aterrizó como módulo ni como API. R7 la entrega como una
**builder DSL Kotlin** dentro de `assure-cli/src/main/.../dsl/`,
expuesta por la función top-level `assurancePack(name, version) { ... }`:

- Bloques anidados: `suite(id) { lens { ... }; assertion { ... } }`.
- Reglas: `mandatory(id)`, `touched(id, prefix?)`, `newFindings(id)`.
- `mandatory(id)` bridgea la regla a `metadata["mandatory"]=true` en
  la `AssuranceSuiteIR` para que `RequiredAssurancePlan.build` la
  detecte como `MandatoryBaseline` sin que el caller duplique el flag.
- `@DslMarker` evita que un `lens {...}` accidental de una suite
  externa entre en el bloque de la suite equivocada.
- 4 tests cubren: pack básico, IR canonizable, integración con
  `RequiredAssurancePlan`, y exposición de las 3 reglas.

El DSL es opcional: el `AssurancePack` data class sigue siendo la API
pública estable; la DSL es una conveniencia. Cuando se materialice
el codec JSON del pack (pendiente en la deuda R7), el DSL también
podrá serializarse a un manifiesto versionado.

### R7.8 — `PackArtifactCodec` (familia 4 + 5 del wire contract)

CBOR codec para `AssurancePack` y `RequiredAssurancePlan.Plan` en
`assurance-artifact`, siguiendo el mismo patrón de los codecs
anteriores (`EvidenceArtifactCodec`, `SuiteArtifactCodec`):

- `encodeToCbor`/`decodeFromCbor` para pack (media type
  `application/vnd.pipelinek.assurance.pack+cbor;version=1`).
- `encodeToCbor`/`decodeFromCborPlan` para plan (media type
  `application/vnd.pipelinek.assurance.plan+cbor;version=1`).
- Cada DTO tiene `of()` y `toDomain()`; el decoder del plan verifica
  el digest canónico con `CanonicalEncoder.digestPlan` (mismo
  contrato que `SuiteArtifactCodec` y `EvidenceArtifactCodec`).
- `when` exhaustivo en `Rule.kind` y `Reason.kind`: cualquier valor
  fuera de la unión produce `ArtifactDecodeException` tipado.
- Mismas cotas de bounded decoding: `MAX_INPUT_BYTES` antes de
  deserializar, `MAX_COLLECTION_SIZE` sobre los DTOs ya
  deserializados.
- 6 tests cubren roundtrip de pack (3 reglas + digest en SuiteRef),
  roundtrip de plan con verificación de digest, rechazo de plan
  alterado, y rechazo de `Reason.kind` desconocido.

### R7.3 — `CanonicalEncoder.digestPlan(...)`

Nuevo método canónico para digerir un `Plan`. El encoder serializa
`engineVersion` y `selections` (suiteId + reason canónico). El
`schema` es `assurance-plan/v1`. Mismo criterio que `digestReport`:
dos planes con las mismas selecciones en el mismo orden producen el
mismo digest.

### R7.4 — M-P03: handler itera children fuera de BodyContinuation

El step `assurance.verify` declara en su contrato (paso 4) que el
`BodyContinuation` se invoca **exactamente una vez** (V1). El
catálogo M-P03 atacaba este invariante sin tener todavía el código
del step en V1.

- Añadido `AssuranceVerifyStep.runBodyOnce(handler, processor)`:
  el handler se ejecuta UNA vez; el processor recibe el outcome
  para iterar children. La firma separa `handler` (entrada) de
  `processor: (BodyOutcome) -> Unit` (iteración), haciendo
  explícito que re-invocar el handler dentro del processor es V1
  violada.
- 2 tests:
  - `run_body_once_invoca_el_handler_exactamente_una_vez`: con
    un processor que simula iterar 5 children, el counter del
    handler se queda en 1.
  - `run_body_once_processor_recibe_el_outcome_no_el_handler`:
    el processor ve el outcome, no el handler; el counter se
    queda en 1 incluso si el processor es complejo.
- Mutante M-P03 añadido al `tools/certify_mutants.py`: añade un
  `handler.run()` antes del `processor(outcome)`. Certificado con
  `killed=2` (sin AVISO de redundancia).

### R7.5 — M-I01: TraceId e InvocationId sin tipo (en el dominio)

El ROADMAP §M11 declaraba M-I01 "literalmente imposible por
construcción" porque la separación de namespaces ya estaba
garantizada por `ExternalNamespace` (enum de 10 miembros) y
`TypedExternalId(namespace, value)`. La revisión reveló que:

- `TypedExternalId` es data class con `init { require(value.isNotBlank()) }`.
- El test AAT-13 verde (`correlation.from.namespace shouldBe ...`)
  no atacaba el init require.
- El `value: String` no permite TypedExternalId con value en blanco,
  pero el ROADMAP no tenía un test redundante para esa cota.

- Añadidos 2 tests redundantes en `EpistemicLawsTest`:
  - `M_I01_mismo_value_en_namespaces_distintos_no_colisiona`:
    el mismo `value` en dos namespaces distintos produce dos
    `TypedExternalId` desiguales.
  - `M_I01_value_vacio_rechazado_por_init`: el wrapper rechaza
    value en blanco con el mensaje "TypedExternalId no puede
    estar vacio".
- Mutante M-I01 añadido al harness: vacía el `init` de
  `TypedExternalId`. Certificado con `killed=3` (sin AVISO).

### R7.6 — Redundancia de mutantes M-10-01..M-10-04

Los 4 mutantes de lenses del catálogo (M-10-01 strength 0..5,
M-10-02 DIP rank, M-10-03 consistency filter, M-10-04 seam filter)
tenían `killed=1` (AVISO de redundancia insuficiente en la
certificación inicial de R5). Se añadieron 5 tests redundantes
(uno por cada mutante excepto M-10-02 que ya tenía 2) que
atacan el CONTENIDO, no solo el conteo:

- `M_10_01`: el mensaje de error incluye el valor concreto `6`.
- `M_10_02`: DIP se detecta también con `domain → infrastructure`,
  no solo `domain → adapters`.
- `M_10_03`: la lista de contradicciones incluye el edge observado
  que falta, no solo "no está vacía".
- `M_10_04`: en un grafo `app → domain`, `seamCount == 0`
  (Domain NO es seam aunque tenga dependiente interno).

Tras la redundancia:
- M-10-01: `killed=2`
- M-10-02: `killed=2`
- M-10-03: `killed=2`
- M-10-04: `killed=2`

## Acceptance

- R5.5 (RequiredAssurancePlan) implementado: API sellada sin risk
  score, plan determinista con digest, 6 tests de selección + 2
  tests de pack.
- R5.6 (AssurancePack) implementado: data class versionada con
  reglas que se mapean 1:1 a las razones del plan.
- M10 DSL `assurancePack { suite { ... } }`: builder Kotlin con
  `@DslMarker`; 4 tests de composicion + bridge a `RequiredAssurancePlan`.
- M-P03 certificado con `killed=2` en el harness de mutantes.
- M-I01 certificado con `killed=3` en el harness de mutantes.
- M-DSL01 certificado con `killed=2` (ataque al bridge
  `mandatory(id) → metadata["mandatory"]=true`).
- M-CODEC01 certificado con `killed=6` (ataque a la verificación
  de digest del plan en el decoder).
- M-10-01..M-10-04: redundancia >= 2 (sin AVISO en el harness).
- AAT-22: el Plan no expone nigún risk score. Cubierto por test
  `plan_no_expone_ningun_risk_score` (compila, no en runtime).

## Build

- `./gradlew --no-daemon clean check` → `BUILD SUCCESSFUL in 1m`.
- 398 tests, 0 failures, 0 skipped (incremento de 36 vs R6).
- 32/32 mutantes del catálogo certificados, 0 AVISO.
- SHA de cierre: `bd775fa`.

## Riesgos y deuda

- **DSL de cambios para R5.5**: el plan acepta `changedPaths:
  List<String>`. La forma en que un caller produce esa lista
  (e.g., `git diff --name-only`) queda fuera del scope del plan
  mismo. Es un buen lugar para un PR futuro con un
  `GitChangedPathExtractor` que el plan consuma.
- **Mutante de `runBodyOnce` con handler mutante**: el mutante
  actual añade `handler.run()` antes del processor. Un mutante
  más sutil sería re-invocar dentro de una rama `try-catch`
  (catching `Throwable` para silenciar la segunda excepción).
  Ese refinamiento se aplaza hasta que el step se conecte al
  SDK real.
- **Serialización JSON del pack**: por ahora `AssurancePack` es
  data class sin codec. La carga desde un archivo YAML/JSON
  requiere un `kotlinx.serialization` plugin que no está
  configurado en `assurance-engine` (donde el pack vive). Cuando
  el catálogo de packs se materialice, se añadirá el codec
  correspondiente.
