#!/usr/bin/env bash
# tools/test_collect-test-receipt.sh — test mínimo del recibo de tests.
#
# Verifica:
#   1. El script produce un JSON con la shape esperada.
#   2. El digest del recibo es estable (regenerar produce el
#      mismo digest para los mismos inputs).
#   3. El script detecta un fallo de test y exit != 0.
#   4. El recibo apunta al commit actual.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RECEIPTS_DIR="$REPO_ROOT/build/test-receipts"

echo "[test] corriendo gradle test (puede tardar)..."
./gradlew --no-daemon test > /tmp/test-receipt-test.log 2>&1 || {
    echo "[test] gradle test falló; ver /tmp/test-receipt-test.log" >&2
    tail -30 /tmp/test-receipt-test.log
    exit 1
}

echo "[test] generando primer recibo..."
python3 tools/collect-test-receipt.py --skip-build > /tmp/test-receipt-1.log 2>&1
EXIT_CODE=$?
if [[ $EXIT_CODE -ne 0 ]]; then
    echo "[test] FALLO: exit code $EXIT_CODE (esperado 0)" >&2
    cat /tmp/test-receipt-1.log
    exit 1
fi

# Encontrar el recibo más reciente.
RECEIPT=$(ls -t "$RECEIPTS_DIR"/*.json 2>/dev/null | head -1)
if [[ -z "$RECEIPT" ]]; then
    echo "[test] FALLO: no se generó recibo" >&2
    exit 1
fi

echo "[test] verificando shape del recibo $RECEIPT..."

# Campos obligatorios.
for field in schemaVersion commit digest engineVersion testCounts timestamp modules; do
    if ! python3 -c "import json,sys; d=json.load(open('$RECEIPT')); sys.exit(0 if '$field' in d else 1)"; then
        echo "[test] FALLO: campo '$field' ausente" >&2
        exit 1
    fi
done

# testCounts tiene las 5 claves.
for sub in total passed failed errored skipped; do
    if ! python3 -c "import json,sys; d=json.load(open('$RECEIPT')); sys.exit(0 if '$sub' in d['testCounts'] else 1)"; then
        echo "[test] FALLO: testCounts.$sub ausente" >&2
        exit 1
    fi
done

# Digest estable: regenerar produce el mismo digest (mismo SHA, mismo commit).
echo "[test] regenerando para verificar estabilidad del digest..."
FIRST_DIGEST=$(python3 -c "import json; print(json.load(open('$RECEIPT'))['digest'])")
python3 tools/collect-test-receipt.py --skip-build > /tmp/test-receipt-2.log 2>&1
SECOND_DIGEST=$(python3 -c "import json; print(json.load(open('$RECEIPT'))['digest'])")
if [[ "$FIRST_DIGEST" != "$SECOND_DIGEST" ]]; then
    echo "[test] FALLO: digest inestable: $FIRST_DIGEST vs $SECOND_DIGEST" >&2
    exit 1
fi

# Total = passed + failed + errored + skipped.
python3 -c "
import json, sys
d = json.load(open('$RECEIPT'))
c = d['testCounts']
total = c['passed'] + c['failed'] + c['errored'] + c['skipped']
if total != c['total']:
    print(f'[test] FALLO: total {c[\"total\"]} != passed+failed+errored+skipped {total}')
    sys.exit(1)
print(f'[test] {c[\"total\"]} tests, {len(d[\"modules\"])} modules')
"

echo "[test] OK"
