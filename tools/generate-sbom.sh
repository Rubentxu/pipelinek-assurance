#!/usr/bin/env bash
# tools/generate-sbom.sh — wrapper de la tarea Gradle `cyclonedxBom`.
#
# Uso: ./tools/generate-sbom.sh [modulo]
#
# M11.2 (CycloneDX SBOM real): el SBOM ahora lo genera el plugin
# `org.cyclonedx:cyclonedx-gradle-plugin` v1.4.0, que captura el
# árbol TRANSITIVO completo. Antes de este commit, el script
# sólo enumeraba las dependencias declaradas en el build.gradle.kts
# del plugin, que es exactamente la limitación que M11.2 cierra.
#
# Salidas por módulo:
#   pipelinek-assurance-plugin/build/reports/bom.json (CycloneDX 1.3 JSON)
#   pipelinek-assurance-plugin/build/reports/bom.xml  (CycloneDX 1.3 XML)
#   assure-cli/build/reports/bom.json
#   assure-cli/build/reports/bom.xml
#
# Por qué defaults del plugin y no configuración custom: la API
# 1.4.0 no expone setters públicos para schemaVersion ni
# includeCompileRuntime; el plugin lee de system properties
# (`cyclonedx.schemaVersion`, etc.) y de los defaults. JSON +
# schema 1.3 es lo que `osv-scanner` y la mayoría de scanners
# consumen; el switch a 1.5 se hace cuando el plugin 2.x esté
# disponible sin breaking changes.
set -euo pipefail

MODULE="${1:-pipelinek-assurance-plugin}"

case "$MODULE" in
    plugin|pipelinek-assurance-plugin)
        MODULE=':pipelinek-assurance-plugin'
        OUT_JSON='pipelinek-assurance-plugin/build/reports/bom.json'
        OUT_XML='pipelinek-assurance-plugin/build/reports/bom.xml'
        ;;
    cli|assure-cli)
        MODULE=':assure-cli'
        OUT_JSON='assure-cli/build/reports/bom.json'
        OUT_XML='assure-cli/build/reports/bom.xml'
        ;;
    all)
        echo "[sbom] generating for plugin + cli" >&2
        ./gradlew :pipelinek-assurance-plugin:cyclonedxBom :assure-cli:cyclonedxBom --no-daemon -q
        echo "[sbom] wrote pipelinek-assurance-plugin/build/reports/bom.{json,xml}" >&2
        echo "[sbom] wrote assure-cli/build/reports/bom.{json,xml}" >&2
        exit 0
        ;;
    *)
        echo "uso: $0 [plugin|cli|all]" >&2
        exit 2
        ;;
esac

./gradlew "${MODULE}:cyclonedxBom" --no-daemon -q
echo "[sbom] wrote ${OUT_JSON}" >&2
echo "[sbom] wrote ${OUT_XML}" >&2
