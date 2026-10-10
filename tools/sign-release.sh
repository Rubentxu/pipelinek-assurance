#!/usr/bin/env bash
# tools/sign-release.sh — firma GPG de los artefactos de release.
#
# Uso: ./tools/sign-release.sh [directorio]
#
# M11.3 (GPG signing): firma detached del tarball de distribución
# Y del SBOM con la clave GPG del firmante. Los artefactos
# firmados son:
#   - <directorio>/<tarball>.tar.gz        (binario del CLI)
#   - <directorio>/<tarball>.tar.gz.sha256 (suma de verificación)
#   - <directorio>/bom.json                 (SBOM CycloneDX JSON)
#
# Salidas (con `--detach-sign`):
#   - <directorio>/<tarball>.tar.gz.asc
#   - <directorio>/<tarball>.tar.gz.sha256.asc
#   - <directorio>/bom.json.asc
#
# Requisitos:
#   - `gpg` instalado.
#   - Una clave privada disponible (idealmente vía `gpg-agent`
#     con cached passphrase; el script NO prompta).
#   - La clave por defecto es la primera secret key del keyring.
#     Para sobreescribir: `GPG_KEY=<fingerprint> $0 <dir>`.
#
# **Por qué SHA-256 también se firma:** la spec de CycloneDX y
# los scanners de supply-chain (sigstore, osv-scanner) verifican
# el SBOM Y su hash. Firmar sólo el tarball dejaría la suma
# modificable sin que la verificación lo detecte.
#
# **Modo sin clave:** si no hay claves secretas en el keyring,
# el script imprime las instrucciones para crear una y termina
# con exit 0 (no es un STOP en máquinas de desarrollo que aún
# no han configurado GPG). En CI, el pipeline aborta con exit 1
# si pasa `--strict` como segundo argumento.
set -euo pipefail

DIST_DIR="${1:-dist}"
STRICT="${2:-}"

cd "$DIST_DIR"

# 1. Detectar clave GPG secret por defecto.
if ! command -v gpg >/dev/null 2>&1; then
    echo "[sign] gpg no instalado; no se firma" >&2
    [[ "$STRICT" == "--strict" ]] && exit 1 || exit 0
fi

GPG_KEY="${GPG_KEY:-}"
if [[ -z "$GPG_KEY" ]]; then
    GPG_KEY=$(gpg --list-secret-keys --keyid-format=long 2>/dev/null \
        | grep -E '^sec' | head -1 | awk '{print $2}' | cut -d/ -f2 || true)
fi

if [[ -z "$GPG_KEY" ]]; then
    echo "[sign] no hay claves GPG secret en el keyring" >&2
    echo "[sign] Para crear una:" >&2
    echo "[sign]   gpg --full-generate-key   (RSA 4096, nombre y email)" >&2
    echo "[sign]   export GPG_KEY=\$(gpg --list-secret-keys --keyid-format=long | grep '^sec' | head -1 | awk '{print \$2}' | cut -d/ -f2)" >&2
    echo "[sign]   $0 $DIST_DIR" >&2
    [[ "$STRICT" == "--strict" ]] && exit 1 || exit 0
fi

echo "[sign] usando clave GPG: ${GPG_KEY}" >&2

# 2. Localizar artefactos a firmar.
TARBALL=$(ls -1 *.tar.gz 2>/dev/null | head -1 || true)
SHA256=$(ls -1 *.sha256 2>/dev/null | head -1 || true)
SBOM_JSON="bom.json"

SIGNED=0
for ARTIFACT in "$TARBALL" "$SHA256" "$SBOM_JSON"; do
    if [[ -z "$ARTIFACT" || ! -f "$ARTIFACT" ]]; then
        echo "[sign] skip (no existe): $ARTIFACT" >&2
        continue
    fi
    gpg --batch --yes --armor --local-user "$GPG_KEY" \
        --output "${ARTIFACT}.asc" \
        --detach-sign "$ARTIFACT"
    echo "[sign] ${ARTIFACT}.asc" >&2
    SIGNED=$((SIGNED + 1))
done

if [[ $SIGNED -eq 0 ]]; then
    echo "[sign] no se firmó ningún artefacto" >&2
    exit 1
fi

echo "[sign] OK: $SIGNED artefactos firmados con ${GPG_KEY}" >&2
