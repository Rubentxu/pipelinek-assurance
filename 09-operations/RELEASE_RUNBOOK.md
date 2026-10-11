# Release runbook

Cómo promover `consolidation/A-F` (o una nueva rama de release)
a `main` y `v0.10.0-rc1` (o el siguiente RC) sin saltarse
ningún gate. Este runbook se escribió el 2026-10-11,
exactamente después de ejecutar el flujo de A→F; los
detalles que se documentan aquí son los que
`odd/tasks/block-pending-external.md` deja abiertos
entre ciclos.

## Cuándo se usa

Cuando el tramo de consolidación A–F (o el siguiente
`v0.x.0-rc1` que lo suceda) tiene todas sus sub-tareas
en-repo cerradas y la `clean check` corre verde en local.

NO se usa para promover un hotfix: para esos hay un
flujo más corto descrito en el README (no en este runbook).

## Pre-flight (orden importa)

1. **Árbol limpio.** `git status --short` debe estar vacío.
   Los recibos de mutantes viven en `build/`, que está
   gitignored, así que la presencia de archivos ahí no
   bloquea. El test de "limpio" es sobre el índice.
2. **Tag actual conocido.** `git tag -l 'v0.*' | tail -5`
   y `git log --oneline v0.9.5-rc1..HEAD`. Si la rama
   no tiene al menos 1 commit sobre el último tag
   publicado en `origin`, no es momento de promover.
3. **SHA del RC local conocido.** `git rev-parse HEAD`
   y `git rev-list -n1 v0.10.0-rc1` deben coincidir
   (mismo SHA que el tag local). Si el tag está
   retrasado respecto al HEAD, primero re-tagea el
   RC sobre el HEAD actual (`git tag -d v0.10.0-rc1 &&
   git tag v0.10.0-rc1`).

## Gate 1 — suite verde

```
JAVA_HOME=$HOME/.asdf/installs/java/temurin-24.0.2+12 \
    ./gradlew --no-daemon clean check
```

Criterio:
- `BUILD SUCCESSFUL`
- 0 failures, 0 errors, 0 skipped
- `total tests` ≥ cifra del RC anterior (no regresión)

Si gradle muere con *"build daemon disappeared"*:
reintentar con `--no-daemon` (suele ser contención del
registry `~/.gradle` con otro build en la misma máquina).
Si vuelve a fallar, `pkill -f GradleDaemon` y reintentar.

Si gradle aborta por **OOM**: aumentar
`org.gradle.jvmargs=-Xmx6g` en `gradle.properties`
y reintentar.

## Gate 2 — perf dentro de budget

```
JAVA_HOME=$HOME/.asdf/installs/java/temurin-24.0.2+12 \
    ./tools/measure-performance.sh --budget 90
```

Criterio:
- exit 0
- `build/perf-baseline.txt` con `over_budget=false`
- `clean_check_seconds` ≤ 90 (budget)

Si exit 1: o el budget se quedó corto, o el script
detectó menos de 300 tests (umbral mínimo, A5 lo
convirtió en error de sub-set). Mirar `/tmp/perf-check.log`.

## Gate 3 — cert completa de mutantes

```
for m in $(python3 -c "import re; print('\n'.join(sorted(set(re.match(r'^\|\\s*(M-[A-Z0-9\\-_]+)\\s*\\|',ln).group(1) for ln in open('08-testing/MUTATION_CATALOG.md') if re.match(r'^\|\\s*M-',ln)))))"); do
    python3 tools/certify_mutants.py "$m" || break
done
```

Criterio:
- Todos los mutantes del catálogo con recibo JSON
  en `build/mutant-receipts/<name>.json`.
- Cada recibo tiene `verdict ∈ {DEAD, UNDER_KILLED,
  SURVIVED, INFRA_FAILED}`. El gate **no** es "todo
  DEAD" — un `SURVIVED` legítimo se documenta como
  deficiencia del catálogo, no como bug a ocultar.
- El número de certificados se publica en RELEASE_NOTES
  con la fórmula `N/M` donde N es el conteo real y M
  es el tamaño del catálogo, no como una cifra
  histórica que pudo ser cierta una vez.

Tiempo esperado en el host actual: ~3h para 43
mutantes en serie. Planificar en consecuencia. Si
interrumpe, los `build/mutant-receipts/*.json`
sobreviven al `git clean` (no están en git) y al
re-arrancar la corrida los mutantes ya certificados
no se re-correrá automáticamente — borrarlos a mano
o usar el script de wrapper con `--force`.

## Gate 4 — integridad de blueprints

```
python3 - <<'PY'
import re,hashlib
for ln in open('PACKAGE_MANIFEST.md'):
    m=re.match(r'^- `([^`]+)` — `([0-9a-f]{64})`',ln.strip())
    if not m: continue
    p,d=m.group(1),m.group(2)
    a=hashlib.sha256(open(p,'rb').read()).hexdigest()
    assert a==d, f'DRIFT {p}: {d[:10]} != {a[:10]}'
print('OK')
PY
```

Criterio: imprime `OK`. Si no, regenerar con
`tools/regen-manifest.py` (o el equivalente que el
repo tenga) y commit en el mismo paso.

> **Regla de AGENTS.md:** el digest se actualiza
> **en el mismo commit** que toca el documento. Si
> un commit toca un documento listado en
> `PACKAGE_MANIFEST.md` sin regenerar su digest, el
> gate queda en rojo.

## Gate 5 — push del RC tag

```
git push origin v0.10.0-rc1
```

NO se usa `--force` ni `--tags` global. El tag ya
existe local sobre el SHA correcto.

Si el push falla por "tag already exists" en
`origin`, el tag remoto está retrasado: verificar
con `git ls-remote --tags origin v0.10.0-rc1`. Si
apunta a un SHA distinto al local, **no** se hace
`--force`; se aborta y se investiga quién movió
el tag remoto.

## Gate 6 — merge a main

```
git checkout main
git merge --no-ff consolidation/A-F
git push origin main
```

Criterio del merge:
- `git log main..consolidation/A-F` lista al menos
  los commits que el ROADMAP §10 del tramo declara.
- El merge debe ser **fast-forward-disabled** (`--no-ff`)
  para preservar la topología de la rama de release.
- Si `main` tiene commits que la rama no contiene,
  rebase `consolidation/A-F` sobre `main` antes del
  merge (no usar `git pull --rebase` desde dentro
  de la rama de release: rebase explícito, controlado).

## Post-merge

1. **Verificación remota.** `git ls-remote --tags origin
   v0.10.0-rc1` debe apuntar al mismo SHA local.
2. **Receipts regenerados.** `build/mutant-receipts/`
   se borra (es local) y se re-genera con la corrida
   del Gate 3 sobre el SHA de main, no sobre el de
   la rama. Esto evita certificados contra un SHA
   que ya no es HEAD.
3. **Memoria.** actualizar engram con el nuevo tag,
   el nuevo SHA, y la cifra final `N/M` de cert.
4. **ROADMAP / CHANGELOG.** ya están commiteadas
   antes del push, pero el SHA final en el changelog
   debe ser el del merge, no el del RC.

## Lo que este runbook NO cubre

- Hotfixes (ramas cortas sobre main, una vez
  estabilizado el release).
- Publicación del JAR a un repo remoto (F2.4 del
  roadmap; depende de CI wiring que no está en-repo).
- Promoción de v1.0.0-rc1: requiere C1/D1/D3/F1
  resueltos. Ese flujo será un runbook separado
  cuando se acerque v1.0.0-rc1.
- Re-tagging destructivo (`git tag -d` y volver a
  crear) sobre tags que ya están en `origin`. Si
  necesitas eso, abre un issue primero.

## Historial

- 2026-10-11 — escrito durante el ciclo de
  consolidación A–F, después de ejecutar el
  flujo completo contra `v0.10.0-rc1` (33 commits
  sobre `v0.9.5-rc1`, 590 tests, 22/43 cert
  parcial + 5/5 spot-checks). Captura el estado
  *anterior* a la cert completa.
