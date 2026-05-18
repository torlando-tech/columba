#!/bin/bash
# Run one Maestro flow with logcat + screenshot capture.
# Usage: ./run-flow.sh <emulator-serial> <flow-file.yaml> <result-name>
set -euo pipefail
DEVICE="$1"
# Resolve flow path to absolute BEFORE any cd — the subshell cd's into
# the screenshots dir for `takeScreenshot:` output capture, which would
# break a relative flow path.
FLOW="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
NAME="$3"
RESULTS="/tmp/verify-py-backend/results/${NAME}"
mkdir -p "$RESULTS/screenshots"

echo "=== Running ${NAME} on ${DEVICE} ==="
adb -s "$DEVICE" exec-out screencap -p > "$RESULTS/app-state-before.png"
adb -s "$DEVICE" logcat -c
echo "logcat cleared at $(date)" > "$RESULTS/logcat-before.txt"

# Maestro writes `takeScreenshot: name` outputs to the current working dir
# at maestro launch time, so cd into the screenshots dir for this run.
set +e
( cd "$RESULTS/screenshots" && maestro --device "$DEVICE" test "$FLOW" 2>&1 ) | tee "$RESULTS/flow-stdout.log"
EXIT=${PIPESTATUS[0]}
set -e

adb -s "$DEVICE" logcat -d \
  PythonRnsCore:V PythonRnsTransportAdmin:V PythonRnsNomadnet:V \
  PythonEventBridge:V PythonRnsRuntime:V \
  ConversationLinkManager:V MessagingViewModel:V \
  DiscoveredInterfacesViewModel:V InterfaceConfigManager:V \
  BackendInitializer:V ReticulumService:V \
  ChaquopyRnsBackend:V '*:W' > "$RESULTS/logcat-after.txt" 2>&1 || true

adb -s "$DEVICE" exec-out screencap -p > "$RESULTS/app-state-after.png"

LATEST=$(ls -t ~/.maestro/tests/ 2>/dev/null | head -1)
if [ -n "$LATEST" ]; then
  cp -r "$HOME/.maestro/tests/$LATEST" "$RESULTS/maestro-test-output" 2>/dev/null || true
fi

echo "=== ${NAME} exit=$EXIT (results in $RESULTS) ==="
exit $EXIT
