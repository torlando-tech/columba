#!/usr/bin/env bash
#
# run-large-attachment.sh — single-device regression run for the LXMF
# attachment TransactionTooLargeException fix (PR #967).
#
# Stages a multi-MB file in the device's Downloads, looks up the device's own
# LXMF destination via the debug TestReceiver (so the send resolves locally and
# reaches handleSendSuccess), then runs send-large-attachment.yaml via the
# shared run-flow.sh (logcat + before/after screenshots captured).
#
# Usage:
#   run-large-attachment.sh <device-serial> [APP_ID] [SIZE_BYTES]
#
# Defaults: APP_ID=network.columba.app.debug (a DEBUG build is required for the
# TestReceiver self-dest lookup; the attachment fix under test is identical in
# release). SIZE_BYTES=5528860 (the size from the original crash report).
#
# Exit code is the Maestro flow's exit code.
set -euo pipefail

DEVICE="${1:?usage: run-large-attachment.sh <device-serial> [APP_ID] [SIZE_BYTES]}"
APP_ID="${2:-network.columba.app.debug}"
SIZE_BYTES="${3:-5528860}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_TAG="COLUMBA_TEST"                                   # TestController.LOGCAT_TAG
RECEIVER="$APP_ID/network.columba.app.test.TestReceiver"  # debug-only broadcast surface
FILE_NAME="columba-large-attach-${SIZE_BYTES}.bin"
DEST_DOWNLOAD="/sdcard/Download/$FILE_NAME"
MSG_TOKEN="bigfile-$(date +%s)"
PEER_NAME="Self-LargeAttach"

echo "=== staging ${SIZE_BYTES}-byte file -> $DEVICE:$DEST_DOWNLOAD ==="
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
head -c "$SIZE_BYTES" /dev/urandom > "$TMP/$FILE_NAME"
adb -s "$DEVICE" push "$TMP/$FILE_NAME" "$DEST_DOWNLOAD"
# Best-effort: make the file visible to the document picker promptly.
adb -s "$DEVICE" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d "file://$DEST_DOWNLOAD" >/dev/null 2>&1 || true

echo "=== launching app + resolving own LXMF dest via TestReceiver ==="
adb -s "$DEVICE" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

SELF_HASH=""
for attempt in $(seq 1 12); do
  adb -s "$DEVICE" logcat -c 2>/dev/null || true
  adb -s "$DEVICE" shell am broadcast -a network.columba.test.GET_DEST -n "$RECEIVER" >/dev/null 2>&1 || true
  sleep 3
  # Reply line under COLUMBA_TEST is `dest=<hex>` (or `dest_err reason=not_ready`).
  SELF_HASH="$(adb -s "$DEVICE" logcat -d -s "$TEST_TAG" 2>/dev/null \
    | grep -oE 'dest=[0-9a-f]{16,}' | tail -1 | cut -d= -f2 || true)"
  [ -n "$SELF_HASH" ] && break
  echo "  ...backend not ready yet (attempt $attempt/12)"
done

if [ -z "$SELF_HASH" ]; then
  echo "ERROR: could not resolve own LXMF dest (is this a DEBUG build with a created identity?)" >&2
  exit 2
fi
echo "=== self dest: $SELF_HASH ==="

echo "=== running Maestro flow ==="
exec "$HERE/../run-flow.sh" "$DEVICE" "$HERE/send-large-attachment.yaml" large-attachment \
  -e APP_ID="$APP_ID" \
  -e PEER_HASH="$SELF_HASH" \
  -e PEER_NAME="$PEER_NAME" \
  -e FILE_NAME="$FILE_NAME" \
  -e MSG_TOKEN="$MSG_TOKEN"
