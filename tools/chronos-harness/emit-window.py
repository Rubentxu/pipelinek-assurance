#!/usr/bin/env python3
"""
Emite un export Chronos con la forma `assurance-runtime-evidence/v1`
que el `ChronosArtifactProvider` de pipelinek-assurance consume.

Este script NO es un mock: produce el envelope contractual que el
provider espera. Cuando el binario real de Chronos publique su
export con este shape, este script sigue siendo el golden
reproducible para tests de regresion.

Modos:
  - complete: windowToken + 2 invocations + 1 causal edge.
  - partial:  windowToken + 0 invocations + completenessByCapability
              con una capability Partial -> gap.
  - empty:    sin windowToken -> Failed (rechazo por el provider).
  - all:      los tres golden en archivos separados.

Uso:
  python3 tools/chronos-harness/emit-window.py --mode complete
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "output")


def _sha256(obj: dict) -> str:
    """SHA-256 canonico del envelope (orden estable)."""
    return hashlib.sha256(
        json.dumps(obj, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    ).hexdigest()


def build_complete() -> dict:
    return {
        "windowToken": "wt-complete-2026-10-10",
        "sessionRef": "sess-harness-001",
        "schemaVersion": "assurance-runtime-evidence/v1",
        "invocations": [
            {
                "id": "inv-1",
                "subjectRef": "span/handler",
                "outcome": "ok",
                "durationMs": 10,
            },
            {
                "id": "inv-2",
                "subjectRef": "span/db",
                "outcome": "ok",
                "durationMs": 20,
            },
        ],
        "causalEdges": [
            {
                "id": "edge-1",
                "from": "span/handler",
                "to": "span/db",
                "kind": "causal",
            },
        ],
        "completenessByCapability": {
            "runtime.window": {"status": "Complete"},
        },
    }


def build_partial() -> dict:
    # windowToken presente, items = [], completenessByCapability
    # con una capability Partial -> el provider debe reportar un gap.
    return {
        "windowToken": "wt-partial-2026-10-10",
        "sessionRef": "sess-harness-002",
        "schemaVersion": "assurance-runtime-evidence/v1",
        "invocations": [],
        "causalEdges": [],
        "completenessByCapability": {
            "runtime.window": {"status": "Partial"},
        },
    }


def build_invalid_no_token() -> bytes:
    # Sin windowToken -> el provider hace return failed(...).
    return json.dumps({
        "sessionRef": "sess-harness-003",
        "invocations": [],
    }).encode("utf-8")


def write_golden(name: str, payload) -> str:
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"{name}.json")
    if isinstance(payload, (dict, list)):
        with open(path, "w") as f:
            json.dump(payload, f, sort_keys=True, indent=2)
    else:
        with open(path, "wb") as f:
            f.write(payload)
    return path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["complete", "partial", "invalid", "all"],
                    default="all")
    args = ap.parse_args()

    if args.mode in ("complete", "all"):
        env = build_complete()
        path = write_golden("chronos-complete", env)
        print(f"complete: {path} sha256={_sha256(env)}")
    if args.mode in ("partial", "all"):
        env = build_partial()
        path = write_golden("chronos-partial", env)
        print(f"partial:  {path} sha256={_sha256(env)}")
    if args.mode in ("invalid", "all"):
        path = write_golden("chronos-invalid-no-token", build_invalid_no_token())
        print(f"invalid:  {path} (sin windowToken)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
