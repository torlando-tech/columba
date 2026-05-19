#!/usr/bin/env bash
# Convenience runner for the Columba ↔ Sideband interop suite.
#
# Defaults match the existing dev setup on this machine. Override via env:
#
#   COLUMBA_EMULATOR_SERIAL   adb serial (default: auto-detect first emulator)
#   COLUMBA_RNSD_HOST         host rnsd IP (default 192.0.2.10)
#   COLUMBA_RNSD_PORT         host rnsd port (default 4242)
#   COLUMBA_PROP_NODE_HEX     lxmd hash for propagation tests
#                             (default 33f2621f135146ce30f0767d811af2b6)
#   SIDEBAND_SRC              Sideband checkout (default ~/repos/Sideband)
#
# Usage:
#   ./run.sh                       # full suite (auto-skips PROPAGATED if no lxmd)
#   ./run.sh -k test_text          # filter by name
#   ./run.sh --no-prop             # explicitly skip propagation tests
#   ./run.sh -m 'not slow'         # marker filter
#
# CI shape:
#   pytest emits junit XML by default; override with --junit-xml=path
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Pick the Python that already has RNS/LXMF wheels installed. The host
# venv used by rnsd / lxmd / nomadnet is the obvious default; CI runners
# can override with PYTHON.
PYTHON="${PYTHON:-$HOME/.reticulum-host/venv/bin/python3}"

if [[ ! -x "$PYTHON" ]]; then
    echo "ERROR: Python not found at $PYTHON. Set PYTHON env var." >&2
    exit 1
fi

# pytest may be in the same venv. If it's not, install on-the-fly so a
# bare CI image can `./run.sh` without pre-staging.
if ! "$PYTHON" -c "import pytest" 2>/dev/null; then
    "$PYTHON" -m pip install --quiet -r "$HERE/requirements.txt"
fi

exec "$PYTHON" -m pytest -v --junit-xml="$HERE/interop-results.xml" "$@" "$HERE"
