#!/usr/bin/env bash
#
# run-threads.sh — Threads P2 Maestro suite (threads-spec §6 UI).
#
# Runs the acceptance flows (01-08) against ONE device. The flows drive thread
# UI only (never delivery), so they open an existing conversation by name from
# the Chats tab. The runner enables the threads feature flag through the debug
# TestReceiver: the Settings switch is a Compose node with no text of its own,
# so a UI flow cannot tap it deterministically.
#
# PREREQUISITES:
#   - DEBUG noSentry+pythonBackend build installed, identity created,
#     onboarding done, and a conversation with <peer-name> in Chats (message
#     one peer, or open the device's own lxma:// link and start a chat).
#   - `maestro` on PATH.
#
# Usage:
#   run-threads.sh <device-serial> <peer-name> [APP_ID]
#
# Exit code is non-zero if any flow fails. Per-flow JUnit results land in
# ./results/<flow>.xml, screenshots in ./screenshots/.
set -euo pipefail

DEVICE="${1:?usage: run-threads.sh <device-serial> <peer-name> [APP_ID]}"
PEER_NAME="${2:?usage: run-threads.sh <device-serial> <peer-name> [APP_ID]}"
APP_ID="${3:-network.columba.app.debug}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_TAG="COLUMBA_TEST"
RECEIVER="$APP_ID/network.columba.app.test.TestReceiver"
STAMP="$(date +%s)"
OUT_DIR="$HERE/results"
mkdir -p "$OUT_DIR"

echo "=== launching app ==="
adb -s "$DEVICE" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo "=== enabling the threads feature flag ==="
adb -s "$DEVICE" logcat -c 2>/dev/null || true
FLAG_OK=""
for attempt in $(seq 1 12); do
  adb -s "$DEVICE" shell am broadcast -a network.columba.test.SET_THREADS_FLAG \
    --ez enabled true -n "$RECEIVER" >/dev/null 2>&1 || true
  sleep 1
  FLAG_OK="$(adb -s "$DEVICE" logcat -d -s "$TEST_TAG" 2>/dev/null \
    | grep -o 'threads_flag_ok enabled=true' | tail -1 || true)"
  [ -n "$FLAG_OK" ] && break
  echo "  ...threads flag not acknowledged yet (attempt $attempt/12)"
done

if [ -z "$FLAG_OK" ]; then
  echo "ERROR: could not enable threads_enabled (DEBUG build with the TestReceiver?)" >&2
  exit 2
fi

clear_drafts() {
  adb -s "$DEVICE" logcat -c 2>/dev/null || true
  local ok=""
  for attempt in $(seq 1 10); do
    adb -s "$DEVICE" shell am broadcast -a network.columba.test.CLEAR_DRAFTS \
      -n "$RECEIVER" >/dev/null 2>&1 || true
    sleep 1
    ok="$(adb -s "$DEVICE" logcat -d -s "$TEST_TAG" 2>/dev/null \
      | grep -o 'drafts_clear_ok' | tail -1 || true)"
    if [ -n "$ok" ]; then
      return 0
    fi
  done
  echo "ERROR: could not clear drafts (drafts_clear_ok missing)" >&2
  return 1
}

run_flow() {
  local flow="$1"; shift
  echo "=== maestro: $flow ==="
  # A draft left by the previous flow hides the composer placeholder that the
  # next flow taps; start every flow with the same empty-draft state.
  clear_drafts || return 1
  maestro test --udid "$DEVICE" --format junit --output "$OUT_DIR/$flow.xml" \
    "$HERE/$flow.yaml" \
    -e APP_ID="$APP_ID" \
    -e PEER_NAME="$PEER_NAME" \
    "$@"
}

FAILED=0
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
