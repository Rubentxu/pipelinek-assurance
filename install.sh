#!/usr/bin/env bash
# install.sh — pipelinek-assurance plugin
#
# Construye el plugin y lo deja listo para que un PipelineK host lo
# descubra via ServiceLoader.
#
# Uso: ./install.sh [--prefix /path/to/install]
#
# El JAR se copia a `<prefix>/lib/` y los recursos META-INF/services/
# al classpath del host. El host descubre el plugin
# `dev.pipelinek.assurance.plugin.AssurancePluginContributor` sin
# configuración adicional.
set -euo pipefail

PREFIX="${PREFIX:-/usr/local/share/pipelinek-assurance}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --prefix)
            PREFIX="$2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--prefix /path/to/install]" >&2
            exit 1
            ;;
    esac
done

echo "[install] building plugin..."
./gradlew --no-daemon :pipelinek-assurance-plugin:assemble

JAR=$(find pipelinek-assurance-plugin/build/libs -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)
if [[ -z "$JAR" ]]; then
    echo "[install] no JAR found in pipelinek-assurance-plugin/build/libs" >&2
    exit 1
fi

echo "[install] installing to ${PREFIX}"
mkdir -p "${PREFIX}/lib"
cp "${JAR}" "${PREFIX}/lib/"

# El ServiceLoader del SDK busca los archivos
# `META-INF/services/<fully-qualified-interface>`. El plugin los
# incluye en el JAR, así que se copian automáticamente. Si el host
# requiere un layout distinto, este script no aplica; usar el
# layout del SDK del host.
echo "[install] verifying ServiceLoader file in JAR"
unzip -l "${PREFIX}/lib/$(basename "${JAR}")" | grep -q "META-INF/services/.*StepDefinitionContributor" \
    || { echo "[install] missing META-INF/services entry"; exit 1; }

echo "[install] done. JAR at ${PREFIX}/lib/$(basename "${JAR}")"
