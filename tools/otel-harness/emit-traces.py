#!/usr/bin/env python3
"""
Emite trazas OTel al collector en :4318 usando el formato OTLP/HTTP
JSON v1.

Modos:
  - normal: 1 traza con 3 spans (root + 2 children) con traceId + parentSpanId.
  - partial: 1 span sin traceId (propagacion parcial).
  - empty:   no emite nada (para probar el caso vacio del provider).
  - all:     emite los tres en orden.

Uso:
  python3 tools/otel-harness/emit-traces.py --url http://localhost:4318 --mode normal
"""
import argparse
import json
import random
import struct
import time
import urllib.request


def gen_trace_id():
    return "".join(random.choice("0123456789abcdef") for _ in range(32))


def gen_span_id():
    return "".join(random.choice("0123456789abcdef") for _ in range(16))


def now_ns():
    return int(time.time() * 1_000_000_000)


def make_span(span_id, name, trace_id, parent_span_id=None, status_ok=True):
    return {
        "traceId": trace_id,
        "spanId": span_id,
        "parentSpanId": parent_span_id or "",
        "name": name,
        "kind": 1,  # INTERNAL
        "startTimeUnixNano": str(now_ns()),
        "endTimeUnixNano": str(now_ns() + 1_000_000),
        "attributes": [
            {"key": "service.name", "value": {"stringValue": "pipelinek-assurance-harness"}},
        ],
        "status": {"code": 1 if status_ok else 2},  # OK or ERROR
    }


def emit_normal():
    """Traza completa con root + 2 children."""
    trace_id = gen_trace_id()
    root = make_span(gen_span_id(), "root-handler", trace_id)
    child1 = make_span(gen_span_id(), "child-db", trace_id, parent_span_id=root["spanId"])
    child2 = make_span(gen_span_id(), "child-cache", trace_id, parent_span_id=root["spanId"])
    return [root, child1, child2]


def emit_partial():
    """Un span sin traceId (propagacion parcial: el collector no
    recibio el trace del padre). El provider debe reportar un gap.
    """
    return [make_span(gen_span_id(), "orphan-child", trace_id="")]


def post_traces(url, spans):
    payload = {
        "resourceSpans": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "harness"}},
                    ],
                },
                "scopeSpans": [
                    {
                        "scope": {"name": "harness", "version": "0.1.0"},
                        "spans": spans,
                    },
                ],
            },
        ],
    }
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url + "/v1/traces",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=5) as resp:
        return resp.status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:4318")
    ap.add_argument("--mode", choices=["normal", "partial", "empty", "all"],
                    default="normal")
    args = ap.parse_args()

    if args.mode == "empty":
        print("empty mode: no se emiten trazas")
        return 0

    if args.mode in ("normal", "all"):
        status = post_traces(args.url, emit_normal())
        print(f"normal: 3 spans posted, status={status}")
    if args.mode in ("partial", "all"):
        status = post_traces(args.url, emit_partial())
        print(f"partial: 1 span posted, status={status}")
    if args.mode == "all":
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
