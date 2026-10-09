#!/usr/bin/env bash
# tools/otel-harness/run-collector.sh — arranca el OTel collector
# via podman con el config y exporta a ./build/otel-exports/.
#
# Uso:
#   ./tools/otel-harness/run-collector.sh start   # arranca en background
#   ./tools/otel-harness/run-collector.sh stop    # para el container
#   ./tools/otel-harness/run-collector.sh status  # estado
#
# Variables:
#   OTEL_IMAGE  default docker.io/otel/opentelemetry-collector:0.96.0
#   OTEL_PORT   default 4318 (OTLP HTTP)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
OTEL_IMAGE="${OTEL_IMAGE:-docker.io/otel/opentelemetry-collector:0.96.0}"
OTEL_PORT="${OTEL_PORT:-4318}"
CONTAINER_NAME="pipelinek-otel-harness"
# El export vive en tools/otel-harness/output/ para que NO se
# borre con `gradle clean check`. El archivo en build/ seria
# volatil y romperia la propiedad "el mismo collector real
# produce el mismo output reproducible entre runs".
EXPORT_DIR="$HERE/output"

mkdir -p "$EXPORT_DIR"

cmd="${1:-start}"

start() {
    if podman ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        echo "collector ya esta corriendo como ${CONTAINER_NAME}"
        return 0
    fi
    echo "arrancando collector en :${OTEL_PORT}, export a ${EXPORT_DIR}"
    podman run --rm -d \
        --name "$CONTAINER_NAME" \
        -p "${OTEL_PORT}:4318" \
        -v "$HERE/collector-config.yaml:/etc/otelcol/config.yaml:Z" \
        -v "$EXPORT_DIR:/exports:Z" \
        "$OTEL_IMAGE" \
        --config=file:/etc/otelcol/config.yaml
    # Esperar a que el puerto este abierto.
    for _ in $(seq 1 20); do
        if curl -s "http://localhost:${OTEL_PORT}/" -o /dev/null -w "%{http_code}" \
            | grep -qE "^(200|404|405)$"; then
            echo "collector listo en :${OTEL_PORT}"
            return 0
        fi
        sleep 0.5
    done
    echo "WARN: collector no respondio en :${OTEL_PORT} tras 10s"
    return 1
}

stop() {
    if podman ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        podman stop "$CONTAINER_NAME" >/dev/null
        echo "collector detenido"
    else
        echo "collector no estaba corriendo"
    fi
}

status() {
    if podman ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        echo "collector: UP (${CONTAINER_NAME})"
        podman ps --filter "name=${CONTAINER_NAME}" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
    else
        echo "collector: DOWN"
    fi
}

case "$cmd" in
    start) start ;;
    stop)  stop ;;
    status) status ;;
    *) echo "uso: $0 {start|stop|status}"; exit 1 ;;
esac
