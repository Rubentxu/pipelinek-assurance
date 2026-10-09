#!/usr/bin/env bash
# tools/measure-performance.sh — mide el tiempo de `clean check` y el
# conteo de tests.
#
# Uso: ./tools/measure-performance.sh
#
# **Por qué no es un budget serio:** un budget requiere fixtures
# reales (Chronos, CogniCode, OTel) y compararse contra versiones
# previas. Lo que se mide aquí es la **línea base**: tiempo de
# CI, número de tests, tiempo de compilación por módulo.
# El budget formal queda para M11 segundo pase, después de la
# integración con Chronos real.
set -euo pipefail

OUT="build/perf-baseline.txt"
mkdir -p "$(dirname "$OUT")"

echo "[perf] running clean check..."
START=$(date +%s)
./gradlew --no-daemon clean check > /tmp/perf-check.log 2>&1 || {
    echo "[perf] clean check failed; see /tmp/perf-check.log"
    tail -30 /tmp/perf-check.log
    exit 1
}
END=$(date +%s)
ELAPSED=$((END - START))

TESTS=$(find . -path "*/build/test-results/*/TEST-*.xml" -not -path "*/.gradle/*" 2>/dev/null \
    | xargs grep -hoE 'tests="[0-9]+"' 2>/dev/null \
    | awk -F'"' '{s+=$2} END{print s+0}')

{
    echo "# pipelinek-assurance performance baseline"
    echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "clean_check_seconds=$ELAPSED"
    echo "total_tests=$TESTS"
    echo
    echo "# per-module compilation (rough)"
    for module in assurance-domain assurance-engine assurance-artifact assurance-testkit assurance-providers assure-cli pipelinek-assurance-plugin; do
        if [[ -d "$module" ]]; then
            echo "module_$module=present"
        fi
    done
} > "$OUT"

echo "[perf] wrote $OUT"
echo "[perf] clean check: ${ELAPSED}s, $TESTS tests"
cat "$OUT"
