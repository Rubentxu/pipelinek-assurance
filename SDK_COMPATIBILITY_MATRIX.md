# SDK compatibility matrix

**Fecha de apertura:** 2026-10-11
**Bloque G, unidad G1 + G5 + G8.**
**Source-of-truth:** `odd/tasks/block-G-pipelinek-real-integration.md` §Reglas
y §Decisiones; este archivo es la materialización.

## Cómo se consume el SDK hoy

El plugin (`pipelinek-assurance-plugin`) importa 4
artefactos del SDK público:

```
dev.rubentxu.pipeline.v2:pipeline-domain
dev.rubentxu.pipeline.v2:pipeline-events
dev.rubentxu.pipeline.v2:pipeline-output
dev.rubentxu.pipeline.v2:pipeline-scripting-api
```

La resolución Gradle está en `settings.gradle.kts`:
`mavenCentral()` + `mavenLocal()` + `flatDir`. El
`flatDir` apunta a `${PIPELINEK_SDK_LIB}` (default:
`~/.asdf/installs/pipelinek/${pipelinekVersion}/lib`).

**Importante:** el `flatDir` resuelve por **filename**.
El coordinate Maven `dev.rubentxu.pipeline.v2:pipeline-domain:0.51.0-rc1`
NO resuelve contra un fichero
`pipeline-domain-0.51.0-rc1.jar`; Gradle busca
`pipeline-domain-0.51.0-rc1.jar` pero el dist de
0.51.0-rc1 sólo trae `pipeline-domain-0.51.0.jar`. La
resolución de un coordinate con sufijo `-rc1` requiere
que el `.pom` correspondiente exista en `mavenLocal()`
o `mavenCentral()`.

**Implicación práctica:** para cert contra la
distribución `0.51.0-rc1`, el `pipelinekVersion`
**debe ser `0.51.0`**, no `0.51.0-rc1`. El sufijo
`-rc1` aparece en la release, no en los nombres de
los JARs. Esto es decisión del upstream
`rubentxu/pipeline-kotlin` y no algo que el plugin
pueda cambiar.

## Matriz certificada

| `pipelinekVersion` | release tag       | asdf path                                       | resultado de `clean check`            |
| ------------------ | ----------------- | ----------------------------------------------- | -------------------------------------- |
| `0.48.0`           | `v0.48.0`         | `~/.asdf/installs/pipelinek/0.48.0/lib`         | 590/0/0 en 49s (default gate 1 verde)  |
| `0.51.0`           | `v0.51.0-rc1`     | `~/.asdf/installs/pipelinek/0.51.0-rc1/lib`     | 64/0/0 en 20s (sólo el plugin)         |

**El `pipelinekVersion=0.51.0-rc1` (con sufijo) NO
resuelve** — la flatDir no encuentra el JAR con ese
sufijo. Documentado como un hallazgo de G1: la
incompatibilidad entre la versión del coordinate y la
versión del filename es una característica del SDK
distribution, no del plugin.

## API surface

Tipos importados por el plugin (todos en
`pipeline-domain`):

- `dev.rubentxu.pipeline.v2.domain.PluginStepId`
- `dev.rubentxu.pipeline.v2.domain.StepDescriptor`
- `dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue`
- `dev.rubentxu.pipeline.v2.domain.step.StepContract`
- `dev.rubentxu.pipeline.v2.domain.step.StepCodec`
- `dev.rubentxu.pipeline.v2.domain.step.StepDefinition`
- `dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`
- `dev.rubentxu.pipeline.v2.domain.step.StepHandler`
- `dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext`
- `dev.rubentxu.pipeline.v2.domain.step.StepRegistration`

**Verificado en 0.48.0 y 0.51.0:** los 10 tipos existen
en ambos. El diff de `.class` files entre
`pipeline-domain-0.48.0.jar` y `pipeline-domain-0.51.0.jar`
es **un único class añadido**:
`dev.rubentxu.pipeline.v2.domain.Capability` (el nuevo
value class para capability registration, que el plugin
todavía no usa).

**API contract:** estable, retrocompatible, aditiva.

## Cambios en el plugin para G1

1. **`pipelinekVersion` como propiedad Gradle.** Override
   con `-PpipelinekVersion=<ver>`. Default `0.48.0`.
2. **Tres renames en `StepCodec.decode(...)`** para que
   el parámetro se llame `encoded` (igual que el SDK) en
   lugar de `payload`. Esto silencia 3 warnings de
   Kotlin sobre parameter-name mismatch que aparecieron
   al compilar contra 0.51.0.
3. **JAR del plugin sin `pipeline-domain` embebido**
   (verificado con `unzip -l`: 0 archivos
   `dev/rubentxu/pipeline/*` en el JAR del plugin). La
   regla R5 del bloque G se cumple sin necesidad de
   cambiar el scope `implementation` a `compileOnly`.

## Lo que NO se hace en G1 (deferido)

- **Crear un módulo Gradle `pipeline-sdk-bom`.** El SDK
  no está en Maven Central y la release 0.51.0-rc1
  no incluye `.pom`. Una "BOM" sin coordinate resoluble
  es documentación, no un módulo de Gradle. La matriz
  vive en este markdown.
- **Cambiar `implementation` → `compileOnly`.** El scope
  actual ya produce un JAR limpio (regla R5). Cambiar
  el scope sería una etiqueta distinta con el mismo
  efecto.
- **Publicar `.pom` para 0.51.0-rc1 en `mavenLocal()`.**
  Eso es trabajo de release/SDK, no del plugin. Si el
  usuario quiere que el plugin consuma 0.51.0-rc1 con
  coordinate exacto, hay que pedirle al upstream que
  publique la release como Maven artifact (no como zip).
