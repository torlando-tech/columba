#!/usr/bin/env python3
"""Run the rns-backend-py Python test suite against the REAL pinned RNS/LXMF.

The unit tests under ``src/test/python/`` are stub-based (they inject a fake
``RNS`` into ``sys.modules``) and run anywhere. The
``test_rns_interface_contract.py`` contract test needs the real, pinned RNS to
be importable so it can drive traffic through RNS's own transport.

Because the stub-based tests *replace* ``sys.modules["RNS"]``, they must not
share a process with the contract test. So this runner executes **each test
file in its own subprocess**:

  1. Reads the exact RNS / LXMF SHAs from ``rns-backend-py/build.gradle.kts``.
  2. Creates one temp venv and installs those exact git+SHA wheels (+ cryptography).
  3. For each ``test_*.py`` in ``src/test/python`` (or the names given as args),
     launches the venv interpreter with ``src/main/python`` on ``PYTHONPATH``
     so the real ``columba_rnode_interface`` / ``event_bridge`` modules resolve.
     Stub-based tests still work (they override the import themselves); the
     contract test sees the real pinned RNS and runs (it skips cleanly if RNS
     is somehow unavailable).
  4. Aggregates the exit codes. Non-zero if any file failed.

Usage:
  python3 run_python_tests.py                 # run every test_*.py
  python3 run_python_tests.py test_rns_interface_contract.py
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

MODULE_DIR = Path(__file__).resolve().parent
MAIN_PY = MODULE_DIR / "src" / "main" / "python"
BUILD_GRADLE = MODULE_DIR / "build.gradle.kts"
TEST_DIR = MODULE_DIR / "src" / "test" / "python"


def pinned_shas() -> dict[str, str]:
    """Extract the RNS / LXMF / ble-reticulum SHAs from build.gradle.kts."""
    text = BUILD_GRADLE.read_text()

    def grab(repo: str) -> str:
        m = re.search(
            r'install\("git\+https://github\.com/[^"]*?/' + re.escape(repo)
            + r'(?:\.git)?@([0-9a-f]{40})"\)',
            text,
        )
        if not m:
            raise SystemExit(f"could not find {repo} pin in {BUILD_GRADLE}")
        return m.group(1)

    return {
        "rns": grab("Reticulum"),
        "lxmf": grab("LXMF"),
        "ble": grab("ble-reticulum"),
    }


def build_venv() -> Path:
    shas = pinned_shas()
    print(f"[run_python_tests] pinned RNS={shas['rns'][:12]} "
          f"LXMF={shas['lxmf'][:12]} ble={shas['ble'][:12]}", file=sys.stderr)
    venv = Path(tempfile.mkdtemp(prefix="rns_contract_venv_"))
    py = venv / "bin" / "python"
    subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
    r = subprocess.run([str(py), "-m", "pip", "install", "-q", "--upgrade", "pip"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit("pip self-upgrade failed")
    r = subprocess.run(
        [str(py), "-m", "pip", "install", "-q",
         f"git+https://github.com/torlando-tech/Reticulum@{shas['rns']}",
         f"git+https://github.com/torlando-tech/LXMF@{shas['lxmf']}",
         "cryptography>=42.0.0"],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        sys.stderr.write(r.stdout + "\n" + r.stderr + "\n")
        raise SystemExit("pinned RNS/LXMF install failed")
    return py


def run_one(py: Path, test_file: Path) -> int:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(MAIN_PY)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    r = subprocess.run([str(py), "-m", "unittest", "-v", test_file.name],
                       env=env, cwd=str(TEST_DIR))
    return r.returncode


def main() -> int:
    if len(sys.argv) > 1:
        test_files = [TEST_DIR / a for a in sys.argv[1:]]
        for t in test_files:
            if not t.exists():
                raise SystemExit(f"no such test: {t}")
    else:
        test_files = sorted(TEST_DIR.glob("test_*.py"))
    if not test_files:
        raise SystemExit(f"no test files found under {TEST_DIR}")

    py = build_venv()
    failures = 0
    for t in test_files:
        print(f"\n[run_python_tests] === {t.name} ===", file=sys.stderr)
        rc = run_one(py, t)
        status = "PASS" if rc == 0 else "FAIL"
        print(f"[run_python_tests] {t.name}: {status} (rc={rc})", file=sys.stderr)
        failures += 1 if rc != 0 else 0

    print(f"\n[run_python_tests] {len(test_files) - failures}/{len(test_files)} "
          f"files passed", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
