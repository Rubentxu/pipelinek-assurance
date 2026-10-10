#!/usr/bin/env python3
"""
tools/collect-test-receipt.py — Recibo JSON de un run de tests.

M11.3 + F2 (Bloque F): "Recibo de tests y mutantes". El lado de
mutantes ya existe (`certify_mutants.py` produce recibos en
`build/mutant-receipts/`). Este script cubre el lado de tests.

Uso:
    ./tools/collect-test-receipt.py                    # corre gradle test, genera recibo
    ./tools/collect-test-receipt.py --skip-build       # solo lee el último resultado

Output:
    build/test-receipts/<sha>.json   (recibo firmado por SHA del commit)

El recibo contiene:
    - schemaVersion
    - commit (sha del HEAD)
    - engine version
    - test counts (total, passed, failed, skipped)
    - duration (segundos)
    - digest (SHA-256 sobre el contenido canónico)
    - timestamp (ISO-8601 UTC)
    - modules (lista de módulos Gradle que aportaron tests)

El recibo es **inmutable y verificable**: si el commit, los counts
o el duration cambian, el digest cambia, y la verificación de
release fallaría. Es la pieza que un RC necesita para certificar
"este tag se construyó sobre estos tests".
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RECEIPTS_DIR = REPO_ROOT / "build" / "test-receipts"


def run(cmd, cwd=None):
    """Run a shell command and return (exit_code, stdout)."""
    result = subprocess.run(
        cmd, shell=True, cwd=cwd or REPO_ROOT,
        capture_output=True, text=True,
    )
    return result.returncode, result.stdout


def git_head_sha():
    rc, out = run("git rev-parse HEAD")
    if rc != 0:
        return "unknown"
    return out.strip()


def engine_version():
    """Lee la version del engine.

    Fuentes, en orden de prioridad:
    1. Variable de entorno `VERSION` (seteable en CI).
    2. `git describe --tags --abbrev=0` (último tag).
    3. `git rev-parse --short HEAD` (fallback: SHA corto).
    """
    env_version = os.environ.get("VERSION", "").strip()
    if env_version:
        return env_version
    rc, out = run("git describe --tags --abbrev=0 2>/dev/null || git rev-parse --short HEAD")
    if rc == 0 and out.strip():
        return out.strip()
    return "unknown"


def collect_modules():
    """Lista los módulos del repo que aportan tests."""
    gradle = REPO_ROOT / "settings.gradle.kts"
    if not gradle.exists():
        return []
    text = gradle.read_text()
    modules = []
    for line in text.split("\n"):
        # `include(":module-name")`
        m = re.match(r'\s*include\("(:[^"]+)"\)', line)
        if m:
            modules.append(m.group(1).lstrip(":"))
    return modules


def parse_test_results():
    """Lee los XML de JUnit en todos los modulos y agrega counts.

    Los XML de Gradle viven en `<modulo>/build/test-results/test/`,
    no en la raiz. Hay que recorrerlos.
    """
    total = 0
    failed = 0
    skipped = 0
    errors = 0
    modules = []
    found = False
    for test_results in REPO_ROOT.glob("*/build/test-results/test"):
        if not test_results.is_dir():
            continue
        # Path: <repo>/<module>/build/test-results/test
        # parts[-1]="test", parts[-2]="test-results",
        # parts[-3]="build", parts[-4]=<module>.
        parts = test_results.parts
        if len(parts) < 4 or parts[-2] != "test-results" or parts[-3] != "build":
            continue
        module_name = parts[-4]
        for xml in test_results.glob("TEST-*.xml"):
            found = True
            text = xml.read_text(errors="replace")
            m = re.search(r'tests="(\d+)"', text)
            if m: total += int(m.group(1))
            m = re.search(r'failures="(\d+)"', text)
            if m: failed += int(m.group(1))
            m = re.search(r'errors="(\d+)"', text)
            if m: errors += int(m.group(1))
            m = re.search(r'skipped="(\d+)"', text)
            if m: skipped += int(m.group(1))
        modules.append(module_name)
    if not found:
        return None
    passed = total - failed - errors - skipped
    return {
        "total": total,
        "passed": passed,
        "failed": failed,
        "skipped": skipped,
        "errored": errors,
        "modules": sorted(set(modules)),
    }


def canonicalize_receipt(receipt):
    """SHA-256 sobre la representacion canonica del recibo (sin el digest)."""
    r = {k: v for k, v in receipt.items() if k != "digest"}
    payload = json.dumps(r, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument(
        "--skip-build", action="store_true",
        help="No corre gradle test; usa el último resultado disponible",
    )
    args = parser.parse_args()

    if not args.skip_build:
        print("[receipt] running gradle test...")
        rc, _ = run("./gradlew --no-daemon test")
        if rc != 0:
            print(f"[receipt] gradle test fallo con exit {rc}; abortando", file=sys.stderr)
            sys.exit(1)

    counts = parse_test_results()
    if counts is None:
        print("[receipt] no se encontraron XML de test en build/test-results/test", file=sys.stderr)
        sys.exit(1)

    sha = git_head_sha()
    receipt = {
        "schemaVersion": "test-receipt/v1",
        "commit": sha,
        "engineVersion": engine_version(),
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "testCounts": {
            "total": counts["total"],
            "passed": counts["passed"],
            "failed": counts["failed"],
            "errored": counts["errored"],
            "skipped": counts["skipped"],
        },
        "modules": counts["modules"],
    }
    receipt["digest"] = canonicalize_receipt(receipt)

    RECEIPTS_DIR.mkdir(parents=True, exist_ok=True)
    out = RECEIPTS_DIR / f"{sha[:12]}.json"
    out.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")

    print(f"[receipt] {counts['total']} tests, {counts['passed']} passed, "
          f"{counts['failed']} failed, {counts['errored']} errored, "
          f"{counts['skipped']} skipped")
    print(f"[receipt] escrito {out}")
    print(f"[receipt] digest={receipt['digest'][:16]}...")

    # Exit 0 si todos los tests pasaron; 1 si hay failures.
    if counts["failed"] > 0 or counts["errored"] > 0:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
