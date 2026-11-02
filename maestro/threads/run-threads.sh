#!/usr/bin/env bash
#
# run-threads.sh — Threads P2 Maestro suite (threads-spec §6 UI).
#
# Runs the acceptance flows (00-08) against ONE device. Sends go to the device's
# own LXMF destination (resolved via the debug TestReceiver), so the whole
# thread lifecycle exercises locally without a second peer — same single-device
# trick as e2e/single-device/run-large-attachment.sh.
#
# PREREQUISITES:
#   - DEBUG noSentry+pythonBackend build installed, identity created,
#     onboarding done.
#   - `maestro` on PATH.
#
# Usage:
#   run-threads.sh <device-serial> [APP_ID]
#
# Exit code is non-zero if any flow fails. Per-flow JUnit results land in
# ./results/<flow>.xml, screenshots in ./screenshots/.
set -euo pipefail

DEVICE="${1:?usage: run-threads.sh <device-serial> [APP_ID]}"
APP_ID="${2:-network.columba.app.debug}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_TAG="COLUMBA_TEST"
RECEIVER="$APP_ID/network.columba.app.test.TestReceiver"
PEER_NAME="Self-Threads"
STAMP="$(date +%s)"
OUT_DIR="$HERE/results"
mkdir -p "$OUT_DIR"

echo "=== launching app + resolving own LXMF dest via TestReceiver ==="
adb -s "$DEVICE" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

SELF_HASH=""
for attempt in $(seq 1 12); do
  adb -s "$DEVICE" logcat -c 2>/dev/null || true
  adb -s "$DEVICE" shell am broadcast -a network.columba.test.GET_DEST -n "$RECEIVER" >/dev/null 2>&1 || true
  sleep 3
  SELF_HASH="$(adb -s "$DEVICE" logcat -d -s "$TEST_TAG" 2>/dev/null \
    | grep -oE 'dest=[0-9a-f]{16,}' | tail -1 | cut -d= -f2 || true)"
  [ -n "$SELF_HASH" ] && break
  echo "  ...backend not ready yet (attempt $attempt/12)"
done

if [ -z "$SELF_HASH" ]; then
  echo "ERROR: could not resolve own LXMF dest (DEBUG build with a created identity?)" >&2
  exit 2
fi
echo "=== self dest: $SELF_HASH ==="

run_flow() {
  local flow="$1"; shift
  echo "=== maestro: $flow ==="
  maestro test -s "$DEVICE" --format junit --output "$OUT_DIR/$flow.xml" \
    "$HERE/$flow.yaml" \
    -e APP_ID="$APP_ID" \
    -e PEER_HASH="$SELF_HASH" \
    -e PEER_NAME="$PEER_NAME" \
    "$@"
}

FAILED=0
run_flow 00-enable-threads || FAILED=1
run_flow 01-create-from-scratch -e MSG_TOKEN="scratch-$STAMP" || FAILED=1
run_flow 02-thread-from-here-and-hook \
  -e MAIN_TOKEN="root-$STAMP" -e REPLY_TOKEN="offroot-$STAMP" || FAILED=1
run_flow 03-sheet-switch \
  -e MAIN_TOKEN="chipmain-$STAMP" -e THREAD_TOKEN="chipthr-$STAMP" \
  -e THREAD_MSG2="chipmsg2-$STAMP" -e DRAFT_TOKEN="chipdraft-$STAMP" || FAILED=1
run_flow 04-archive-unarchive -e THREAD_TOKEN="arch-$STAMP" || FAILED=1
run_flow 05-chat-card-expand -e CARD_TOKEN="card-$STAMP" || FAILED=1
run_flow 06-swipe-archive-undo -e THREAD_TOKEN="swipe-$STAMP" || FAILED=1
run_flow 07-selection-mode -e THREAD_TOKEN="select-$STAMP" || FAILED=1
run_flow 08-rename-title \
  -e THREAD_TOKEN="rename-$STAMP" -e RENAMED_TITLE="Renamed $STAMP" || FAILED=1

if [ "$FAILED" -ne 0 ]; then
  echo "SUITE FAILED (see $OUT_DIR)" >&2
  exit 1
fi
echo "SUITE PASSED"
