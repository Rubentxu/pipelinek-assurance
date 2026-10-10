#!/usr/bin/env bash
# tools/verify-signatures.sh — verifica firmas GPG en modo estricto.
#
# Ref: `odd/tasks/block-F-release.md` §F2 ("Verificar firmas
# en modo estricto").
#
# Uso:
#   ./tools/verify-signatures.sh <directorio>            # verifica
#   ./tools/verify-signatures.sh <directorio> --keyring <path>  # con keyring custom
#
# Salidas:
#   - exit 0: todas las firmas son válidas
#   - exit 1: alguna firma es inválida, falta, o el gpg no está disponible
#
# Pieza de F2 que completa la cadena de supply-chain:
#   1. `build-cli-dist.sh` produce tarball + SHA-256
#   2. `sign-release.sh` firma con GPG detached
#   3. `verify-signatures.sh` (este script) verifica esas firmas
#
# El modo estricto se distingue del "modo permisivo" de
# `sign-release.sh` en que aborta si falta CUALQUIER firma. Eso
# refleja la realidad de un RC: un artefacto sin firma no se
# puede confiar, así que la verificación falla cerrado.
set -euo pipefail

DIST_DIR="${1:?usage: $0 <dist-dir> [--keyring <path>]}"
KEYRING=""
shift

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keyring)
            KEYRING="$2"
            shift 2
            ;;
        *)
            echo "[verify] argumento desconocido: $1" >&2
            exit 2
            ;;
    esac
done

if [[ ! -d "$DIST_DIR" ]]; then
    echo "[verify] directorio no existe: $DIST_DIR" >&2
    exit 1
fi

if ! command -v gpg >/dev/null 2>&1; then
    echo "[verify] gpg no instalado; modo estricto aborta" >&2
    exit 1
fi

# Recolecta todos los artefactos que deberían estar firmados.
# La convención de sign-release.sh es: <name>.asc adyacente a
# cada <name> (tarball, sha256, SBOM).
FAILED=0
SIGNED=0
MISSING=0

GPG_OPTS=(--batch --no-tty)
if [[ -n "$KEYRING" ]]; then
    GPG_OPTS+=(--no-default-keyring --keyring "$KEYRING")
fi

# Recolecta todos los archivos que NO son firmas (.asc) ni
# metadata local (.xml). Estos son los que deberían estar firmados.
while IFS= read -r -d '' artifact; do
    # Skip firmas y metadata.
    case "$artifact" in
        *.asc|*.xml|*.sha256|*.sha256sum) continue ;;
    esac
    sig="${artifact}.asc"
    if [[ ! -f "$sig" ]]; then
        echo "[verify] MISSING firma: $artifact (esperaba $sig)" >&2
        MISSING=$((MISSING + 1))
        continue
    fi
    if gpg "${GPG_OPTS[@]}" --verify "$sig" "$artifact" >/dev/null 2>&1; then
        echo "[verify] OK: $artifact"
        SIGNED=$((SIGNED + 1))
    else
        echo "[verify] FAIL firma invalida: $artifact" >&2
        FAILED=$((FAILED + 1))
    fi
done < <(find "$DIST_DIR" -maxdepth 1 -type f -print0)

echo "[verify] firmados=$SIGNED invalidos=$FAILED faltantes=$MISSING"

if [[ "$FAILED" -gt 0 || "$MISSING" -gt 0 ]]; then
    echo "[verify] FALLO: hay artefactos sin firma o con firma invalida" >&2
    exit 1
fi
if [[ "$SIGNED" -eq 0 ]]; then
    echo "[verify] FALLO: ningun artefacto firmado encontrado" >&2
    exit 1
fi

echo "[verify] OK: todas las firmas validas"
