#!/usr/bin/env bash
# One-time emulator prep for the Columba ↔ Sideband interop suite.
#
# What this does (and why it lives outside of conftest.py):
#   - Re-adding the TCP client interface requires Reticulum's foreground
#     service to restart. Android's `mAllowStartForeground` gate fails
#     this when the broadcast originates from a backgrounded sender
#     (and pytest fixtures count as background).
#   - Run this once per AVD lifetime (until you wipe the AVD or
#     reinstall the APK). After it succeeds, the suite is happy to
#     foreground the app via monkey + read the existing config.
#
# Env vars (all optional):
#   COLUMBA_EMULATOR_SERIAL   adb serial (default: first emulator)
#   COLUMBA_RNSD_HOST         host rnsd IP (default 192.0.2.10)
#   COLUMBA_RNSD_PORT         host rnsd port (default 4242)
#   COLUMBA_PROP_NODE_HEX     lxmd hash (default 33f2621f135146ce30f0767d811af2b6)
#
# Re-run safely: the configure broadcasts are idempotent on Columba's
# side beyond the foreground-service gate.
set -euo pipefail

SERIAL="${COLUMBA_EMULATOR_SERIAL:-}"
HOST="${COLUMBA_RNSD_HOST:-192.0.2.10}"
PORT="${COLUMBA_RNSD_PORT:-4242}"
PROP_NODE="${COLUMBA_PROP_NODE_HEX:-33f2621f135146ce30f0767d811af2b6}"
PKG="network.columba.app.debug"
RECEIVER="$PKG/network.columba.app.test.TestReceiver"

if [[ -z "$SERIAL" ]]; then
    SERIAL="$(adb devices | awk '/emulator-/{print $1; exit}')"
    if [[ -z "$SERIAL" ]]; then
        echo "ERROR: No emulator detected. Boot one or set COLUMBA_EMULATOR_SERIAL." >&2
        exit 1
    fi
fi
echo "Using emulator: $SERIAL"
echo "rnsd target: $HOST:$PORT"
echo "lxmd prop node: $PROP_NODE"

# 1) Bring app to foreground. monkey is sufficient — we don't actually
#    need a sticky foreground state because the next step's
#    ADD_TCP_CLIENT writes to the config file regardless of whether the
#    foreground service restart succeeds.
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 6

# 2) Persist the TCP client interface entry. The Reticulum service-
#    restart step that would normally apply this *fails* with
#    `mAllowStartForeground false` when the broadcast comes from shell
#    UID (it's not a user-initiated action from the app's Activity).
#    That's fine — the data layer still writes the interface to
#    `files/reticulum/reticulum/config`, and step 3's force-stop +
#    relaunch picks it up on next boot.
adb -s "$SERIAL" shell am broadcast \
    -a network.columba.test.ADD_TCP_CLIENT \
    --es name 'interop_host' \
    --es host "$HOST" \
    --es port "$PORT" \
    -n "$RECEIVER" >/dev/null
sleep 4

# 3) Set propagation node hex for PROPAGATED-method tests.
adb -s "$SERIAL" shell am broadcast \
    -a network.columba.test.SET_PROP_NODE \
    --es hex "$PROP_NODE" \
    -n "$RECEIVER" >/dev/null
sleep 3

# 4) Force-stop + relaunch so the new interface entry actually
#    becomes a live RNS interface (Reticulum reads its config on
#    init, and a clean relaunch is the cheapest way to apply config
#    changes when we can't legally start the foreground service from
#    a broadcast handler).
adb -s "$SERIAL" shell am force-stop "$PKG"
sleep 3
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

# 5) Cold-start budget — Reticulum + LXMF need ~60-90s to come up
#    on a fresh launch under the Chaquopy interpreter. Poll until
#    identity is ready.
echo "Waiting for backend bootstrap (cold-start ~60s)..."
deadline=$(($(date +%s) + 120))
while [[ $(date +%s) -lt $deadline ]]; do
    adb -s "$SERIAL" logcat -c
    adb -s "$SERIAL" shell am broadcast -a network.columba.test.GET_DEST -n "$RECEIVER" >/dev/null
    sleep 3
    result="$(adb -s "$SERIAL" logcat -d -s 'COLUMBA_TEST:*' | tail -3)"
    if echo "$result" | grep -q 'dest=[0-9a-f]'; then
        echo "$result" | grep 'dest='
        echo "Setup OK."
        exit 0
    fi
    sleep 2
done

echo "Setup FAILED — backend never reached ready state. Last reply:" >&2
adb -s "$SERIAL" logcat -d -s 'COLUMBA_TEST:*' | tail -5 >&2
exit 1
