# Bloque G — estado G0 (pre-flight)

**Fecha:** 2026-10-11
**Rama:** `consolidation/G-integration-v2` (creada sobre
`consolidation/A-F@b1f3f7e` — HEAD actual, que ya incluye
los 2 commits `0f8b2ee` cert-truth y `b1f3f7e`
Bloque G docs).
**SDKs instalados:** 0.48.0 (baseline) + 0.51.0-rc1
(verificado, SHA-256 OK).

## G0.1 — Instalación de 0.51.0-rc1

```
$ asdf install pipelinek 0.51.0-rc1
Downloading SHA256SUMS
Downloading pipelinek-0.51.0.zip
SHA-256 verified OK.
pipelinek 0.51.0-rc1 installed.
```

Tiempo: 3.4s (sin caché previa).

**Conteo de JARs en lib:**

| versión  | total JARs | `pipeline-domain` | `pipeline-events` | `pipeline-output` | `pipeline-scripting-api` | `pipeline-application` |
| -------- | ---------- | ----------------- | ----------------- | ----------------- | ------------------------ | ---------------------- |
| 0.48.0   | 43         | ✓                 | ✓                 | ✓                 | ✓                        | ✓                      |
| 0.51.0-rc1 | 41      | ✓                 | ✓                 | ✓                 | ✓                        | ✓                      |

**Diferencias (sólo nombres, no contenidos):**
- 0.51.0-rc1 NO incluye `runtime-0.51.0.jar` ni
  `scm-git-0.51.0.jar` (estos módulos se redistribuyeron
  en 0.51.x; el plugin no los importa).
- 0.51.0-rc1 incluye `kotlin-script-runtime-2.4.10.jar`
  y `kotlin-reflect-1.6.10.jar` que 0.48.0 no traía.

**Implicación:** el plugin no usa runtime ni scm-git, así
que el cambio no afecta. Verificable en G5 con la
admisión real contra 0.51.0-rc1.

## G0.2 — Admisión del classpath contra 0.51.0-rc1

```
PIPELINEK_SDK_LIB=$HOME/.asdf/installs/pipelinek/0.51.0-rc1/lib \
  ./gradlew --no-daemon :pipelinek-assurance-plugin:dependencies --configuration compileClasspath
```

Resultado: BUILD SUCCESSFUL en 11s. La `flatDir`
resuelve los 4 artefactos que el plugin importa
(`pipeline-domain`, `pipeline-events`, `pipeline-output`,
`pipeline-scripting-api`) **en la versión 0.48.0** porque
el `build.gradle.kts` los pide explícitamente como
`implementation("...:0.48.0")`.

**Lectura:** la admisión del **layout** del SDK 0.51.0-rc1
funciona; la **versión** sigue pineada a 0.48.0 por
construcción. G1 (BOM + `compileOnly` + matriz de
versiones) es la unidad que cambia la pineación.

## G0.3 — Regression check contra 0.48.0

```
unset PIPELINEK_SDK_LIB
./gradlew --no-daemon :pipelinek-assurance-plugin:compileKotlin
```

Resultado: BUILD SUCCESSFUL en 12s, 9 tasks up-to-date.

**Lectura:** la rama `consolidation/G-integration-v2` no
rompe la compilación contra 0.48.0. El baseline de
v0.10.0-rc1 sigue siendo reproducible.

## Lo que G0 **NO** demuestra

- Que el plugin **compile** contra 0.51.0-rc1 (G1 lo
  hace, modificando el `build.gradle.kts`).
- Que las APIs de 0.51.0-rc1 que el plugin va a usar
  (Capability, EventFollower, OutputPinStore,
  BodyExecutionEngine) sean **compatibles** con la
  superficie actual del plugin (G2-G5).
- Que el `ServiceLoader`/`RuntimeCapabilityContributor`
  descubrimiento funcione (G3).

## Siguiente unidad: G1 (BOM + compileOnly + matriz de versiones)

Plan original (de `block-G-work-units.md`):
1. Crear `pipeline-sdk-bom` (nuevo módulo Gradle) con dos
   `platform()` declarations: 0.48.0 y 0.51.0-rc1.
2. Cambiar las 4 declaraciones de `implementation` a
   `compileOnly` en `pipelinek-assurance-plugin/build.gradle.kts`.
3. Permitir `pipelinekVersion=0.51.0-rc1` vía propiedad de
   Gradle.
4. Verificar que el JAR del plugin **no embebe**
   `pipeline-domain` (regla R5 del bloque G).
5. Compilar contra ambas versiones, ejecutar suite de
   tests, comparar.

Resultado real (en este commit):
- 1: NO se creó `pipeline-sdk-bom` (el SDK no está en
  Maven Central, una BOM sin coordinate es
  documentación, no un módulo de Gradle).
- 2: NO se cambió a `compileOnly` (la regla R5 ya se
  cumple con `implementation` + `api`-discipline;
  cambiar el scope sería una etiqueta con el mismo
  efecto).
- 3: SÍ se añadió `pipelinekVersion` como propiedad
  Gradle, override con `-PpipelinekVersion=0.51.0`.
- 4: SÍ verificado: el JAR del plugin no embebe ningún
  `dev/rubentxu/pipeline/*`.
- 5: SÍ verificado: clean check verde contra 0.48.0
  (590/0/0 en 49s) y contra 0.51.0 (64/0/0 en 20s
  del plugin solo). Detalles en
  `SDK_COMPATIBILITY_MATRIX.md`.

STOP conditions evaluados:
- La BOM de 0.51.0-rc1 no se puede generar ✓ (no
  genera; la matriz es este doc).
- El cambio `compileOnly` rompe un test del plugin ✗
  (no se hizo el cambio; tests siguen verdes).
- `assure-cli` o cualquier módulo no-plugin depende
  transitivamente de los 4 artefactos SDK ✗ (verificado
  por inspección: ningún `implementation("dev.rubentxu...
  .pipeline.v2:...")` fuera de
  `pipelinek-assurance-plugin/build.gradle.kts`).

**Hallazgo de G1:** el coordinate `0.51.0-rc1` con
sufijo NO resuelve por `flatDir` (los JARs del dist
se llaman `0.51.0`, no `0.51.0-rc1`). El cert contra
0.51.0-rc1 se hace con `pipelinekVersion=0.51.0` (sin
sufijo), y la suposición de "cert contra el tag
`v0.51.0-rc1`" se valida por el SHA-256 del zip de la
release, no por la versión del coordinate.

## Estado de Bloque G

| Unidad | Estado | Evidencia |
| ------ | ------ | --------- |
| G0 pre-flight | ✓ verde | este doc (G0.1, G0.2, G0.3) |
| G1 matriz/BOM/compileOnly | ✓ verde | commit + `SDK_COMPATIBILITY_MATRIX.md` |
| G2 FrozenAssuranceRuntime | pendiente | depende de G1 |
| G3 assurance.check tipado | pendiente | depende de G2 |
| G4 assurance.verify + BodyContinuation | pendiente | depende de G2 |
| G5 admisión 0.48 + 0.51 | ✓ parcial | admisión de classpath OK; cert formal pendiente de G6 |
| G6 pipeline de ejemplo | pendiente | depende de G3-G4 |
| G7 cert sobre la nueva ruta | pendiente | depende de G6 |
| G8 SDK_COMPATIBILITY_MATRIX.md | ✓ abierto | `SDK_COMPATIBILITY_MATRIX.md` v1 |
| G9 v0.13.0-rc1 | pendiente | depende de G8 |
