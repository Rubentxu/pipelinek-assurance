#!/usr/bin/env bash
# tools/measure-performance.sh — mide el tiempo de `clean check` y el
# conteo de tests, y exit 1 si supera el budget.
#
# Uso: ./tools/measure-performance.sh [--budget N]
#
# **Estado del budget:** la línea base se captura en este mismo script.
# M11 lo eleva a "budget formal" exigiendo que el run no supere N
# segundos (default 90s, ajustable con --budget). Si supera, exit 1.
# El budget formal sobre fixtures de carga reales (10k spans Chronos,
# 100k traces OTel) queda para el segundo pase de M11, una vez
# tengamos los exporters reales integrados.
set -euo pipefail

BUDGET="${MAX_CHECK_SECONDS:-90}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --budget)
            BUDGET="$2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--budget N]" >&2
            exit 2
            ;;
    esac
done

OUT="build/perf-baseline.txt"
mkdir -p "$(dirname "$OUT")"

echo "[perf] running clean check (budget ${BUDGET}s)..."
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

# Mínimo absoluto: si tenemos menos de 300 tests, el budget se está
# midiendo sobre un subset. A5 (Bloque A): esto era un WARNING que
# silenciosamente saltaba el budget. Convertido en ERROR porque un
# budget "falsamente verde" (porque no se midió nada) es peor que
# un budget rojo: te dice que la performance está bien cuando no
# la has medido.
MIN_TESTS=300
if [[ "$TESTS" -lt "$MIN_TESTS" ]]; then
    echo "[perf] STOP: only $TESTS tests ran (min expected $MIN_TESTS)." >&2
    echo "[perf] see /tmp/perf-check.log for the test run output" >&2
    echo "[perf] Refusing to report a budget that wasn't measured." >&2
    exit 1
fi

OVER_BUDGET=false
if [[ "$ELAPSED" -gt "$BUDGET" && "$TESTS" -ge "$MIN_TESTS" ]]; then
    OVER_BUDGET=true
fi

# Module compilation time (rough, via gradle build --dry-run timing).
MODULE_TIMES=""
for module in assurance-domain assurance-engine assurance-artifact assurance-testkit assurance-providers assure-cli pipelinek-assurance-plugin; do
    if [[ -d "$module" ]]; then
        MODULE_TIMES="${MODULE_TIMES}module_${module}=present
"
    fi
done

{
    echo "# pipelinek-assurance performance baseline"
    echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "budget_seconds=$BUDGET"
    echo "clean_check_seconds=$ELAPSED"
    echo "over_budget=$OVER_BUDGET"
    echo "total_tests=$TESTS"
    echo
    echo "# per-module presence"
    echo "$MODULE_TIMES"
} > "$OUT"

echo "[perf] wrote $OUT"
echo "[perf] clean check: ${ELAPSED}s, $TESTS tests"

if [[ "$OVER_BUDGET" == "true" ]]; then
    echo "[perf] BUDGET EXCEEDED: ${ELAPSED}s > ${BUDGET}s"
    cat "$OUT"
    exit 1
fi

cat "$OUT"
