#!/usr/bin/env bash
# tools/generate-sbom.sh — genera un SBOM mínimo (CycloneDX-like) del plugin.
#
# Uso: ./tools/generate-sbom.sh [ruta/salida.json]
#
# **Por qué no es un SBOM completo:** el plugin declara sus
# dependencias en `build.gradle.kts`. Un SBOM real requiere el
# plugin CycloneDX-Gradle, que está fuera del scope actual.
# Esta versión enumera las dependencias declaradas como
# "SBOM funcional" del ROADMAP §M11: cubre lo que se usa, no
# pretende certificar el árbol transitivo.
set -euo pipefail

OUT="${1:-build/sbom.json}"
mkdir -p "$(dirname "$OUT")"

PLUGIN_GRADLE="pipelinek-assurance-plugin/build.gradle.kts"
VERSION="0.1.0-M3"
SHA=$(git -C . rev-parse HEAD 2>/dev/null || echo "uncommitted")
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# Parsear las dependencias del plugin desde su build.gradle.kts.
# Es deliberadamente naive: cualquier cosa que diga `api(...)` o
# `implementation(...)` se enumera. Refinar cuando se tenga un
# catalog versionado.
ENTRIES_FILE=$(mktemp)
trap 'rm -f "$ENTRIES_FILE"' EXIT

grep -oE '(implementation|api|testImplementation)\("[^"]+"' "$PLUGIN_GRADLE" 2>/dev/null \
    | sed -E 's/^[^(]+\("([^"]+)"$/\1/' > "$ENTRIES_FILE" || true
grep -oE '(implementation|api|testImplementation)\(project\("[^"]+"' "$PLUGIN_GRADLE" 2>/dev/null \
    | sed -E 's/^[^(]+\(project\("([^"]+)"$/\1/' >> "$ENTRIES_FILE" || true
sort -u -o "$ENTRIES_FILE" "$ENTRIES_FILE"

# Cabecera del SBOM.
cat > "$OUT" <<EOF
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "version": 1,
  "metadata": {
    "timestamp": "${TS}",
    "component": {
      "type": "library",
      "group": "dev.pipelinek",
      "name": "assurance-plugin",
      "version": "${VERSION}",
      "hash": [{"alg": "SHA-256", "content": "${SHA}"}]
    }
  },
  "components": [
EOF

# Componentes: una entrada por línea, separadas por coma.
FIRST=1
while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    if [[ "$entry" =~ ^:(.+)$ ]]; then
        # project(":assurance-domain") -> :assurance-domain
        GROUP="dev.pipelinek"
        ARTIFACT="${BASH_REMATCH[1]}"
        VER="0.1.0"
    else
        # Format: group:artifact:version o group:artifact
        GROUP=$(echo "$entry" | awk -F':' '{print $1}')
        ARTIFACT=$(echo "$entry" | awk -F':' '{print $2}')
        VER=$(echo "$entry" | awk -F':' '{print $3}')
        [[ -z "$VER" || "$VER" == "$ARTIFACT" ]] && VER="unspecified"
    fi
    if [[ $FIRST -eq 0 ]]; then
        printf ',\n' >> "$OUT"
    fi
    FIRST=0
    {
        printf '    {\n'
        printf '      "type": "library",\n'
        printf '      "group": "%s",\n' "$GROUP"
        printf '      "name": "%s",\n'  "$ARTIFACT"
        printf '      "version": "%s"\n' "$VER"
        printf '    }'
    } >> "$OUT"
done < "$ENTRIES_FILE"

# Cierre.
printf '\n  ]\n}\n' >> "$OUT"

echo "[sbom] wrote ${OUT}" >&2
