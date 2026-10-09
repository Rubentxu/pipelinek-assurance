#!/usr/bin/env bash
# tools/build-cli-dist.sh — empaqueta la CLI assure en un tarball
# distribuible con SHA-256 y README de compatibilidad.
#
# Uso:
#   ./tools/build-cli-dist.sh                 # build + tarball
#   ./tools/build-cli-dist.sh --skip-build   # solo reempaqueta el installDist existente
#
# Output:
#   build/dist/assure-cli-vX.Y.Z-<sha>.tar.gz
#   build/dist/assure-cli-vX.Y.Z-<sha>.tar.gz.sha256
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="${VERSION:-0.7.0}"
SHA=$(git rev-parse --short HEAD)
DIST_DIR="$REPO_ROOT/build/dist"
TARBALL_NAME="assure-cli-v${VERSION}-${SHA}"

if [[ "${1:-}" != "--skip-build" ]]; then
    echo "[dist] building assure-cli installDist..."
    ./gradlew --no-daemon :assure-cli:installDist -q
fi

mkdir -p "$DIST_DIR"
rm -f "$DIST_DIR/${TARBALL_NAME}.tar.gz" "$DIST_DIR/${TARBALL_NAME}.tar.gz.sha256"

SRC="$REPO_ROOT/assure-cli/build/install/assure-cli"
STAGE="$REPO_ROOT/build/dist/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/${TARBALL_NAME}"
cp -r "$SRC"/* "$STAGE/${TARBALL_NAME}/"

# Manpage breve (1 linea) y README.
cat > "$STAGE/${TARBALL_NAME}/README.txt" << EOF
assure-cli v${VERSION} (${SHA})

CLI del proyecto pipelinek-assurance. Ejecuta la suite de
assurance contra un self-model o snapshot, y produce un
envelope estructurado con acciones HATEOAS para que un agente
pueda navegar del fallo al contraejemplo sin parsear stdout
humano.

Uso:
  bin/assure-cli report <self-model.graph>
  bin/assure-cli explain <finding-id>
  bin/assure-cli evidence path <finding-id>

Compatibilidad:
  - PipelineK SDK v2 (StepDefinitionContributor).
  - Assurance IR v1 (assurance-evidence/v1).
  - JDK 17+, Kotlin 2.0+.

SHA-256: ver ${TARBALL_NAME}.tar.gz.sha256
EOF

tar -C "$STAGE" -czf "$DIST_DIR/${TARBALL_NAME}.tar.gz" "${TARBALL_NAME}"
rm -rf "$STAGE"

cd "$DIST_DIR"
sha256sum "${TARBALL_NAME}.tar.gz" > "${TARBALL_NAME}.tar.gz.sha256"

echo "[dist] wrote $DIST_DIR/${TARBALL_NAME}.tar.gz"
echo "[dist] sha256: $(cat "${TARBALL_NAME}.tar.gz.sha256" | cut -d' ' -f1)"
echo "[dist] size: $(du -h "${TARBALL_NAME}.tar.gz" | cut -f1)"
ls -la "$DIST_DIR/"
