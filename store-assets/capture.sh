#!/usr/bin/env bash
# Capture store-art screens from the columba-e2e AVD and compose finals.
#
#   ./capture.sh            # full pipeline: boot, install, seed, capture, compose
#   SKIP_INSTALL=1 ./capture.sh   # reuse the installed APK
#
# Requires: built debug APK (./gradlew :app:assembleNoSentryPythonBackendDebug),
# Android SDK at ~/Android/Sdk. See README.md for the demo-mode caveats.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p captures
SDK="$HOME/Android/Sdk"
SERIAL=emulator-5554
PKG=network.columba.app.debug
ADB="$SDK/platform-tools/adb"

# 1. Boot the AVD headless if it isn't running
if ! "$ADB" devices | grep -q "^$SERIAL[[:space:]]device$"; then
    "$SDK/emulator/emulator" -avd columba-e2e -no-window -no-audio \
        -no-boot-anim -gpu swiftshader_indirect >/tmp/columba-avd.log 2>&1 &
fi
"$ADB" -s "$SERIAL" wait-for-device
until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
echo "avd booted"

# 2. Install the current debug build. APK_DIR overrides the location when
# the main tree is mid-WIP and the build ran in a clean worktree.
APK_DIR="${APK_DIR:-../app/build/outputs/apk/noSentryPythonBackend/debug}"
if [ "${SKIP_INSTALL:-0}" != "1" ]; then
    apk=("$APK_DIR"/*x86_64-debug.apk)
    "$ADB" -s "$SERIAL" install -r -d -g "${apk[0]}"   # -d: per-ABI version codes
fi

# 3. Fresh state, then seed the deterministic store-art dataset in-app
"$ADB" -s "$SERIAL" shell pm clear "$PKG" >/dev/null

# Grant location so no permission cards appear; the map centers on the
# seeded default offline region (StoreArtSeeder), no GPS fake needed.
# pm clear resets grants, so this runs after it.
"$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
"$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
"$ADB" -s "$SERIAL" shell am start -n "$PKG/network.columba.app.MainActivity" >/dev/null
sleep 20   # let the :reticulum service come up

# Bring the stack online (ONBOARD inserts an enabled AutoInterface) so the
# "Columba is offline" banner is absent. The emulator's NAT'd WiFi cannot
# see real peers, so the seeded Network tab stays deterministic.
"$ADB" -s "$SERIAL" shell am broadcast -p "$PKG" -a network.columba.test.ONBOARD >/dev/null
for _ in $(seq 1 15); do
    "$ADB" -s "$SERIAL" logcat -d -s COLUMBA_TEST | grep -q 'onboarded' && break
    sleep 2
done
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am broadcast -p "$PKG" \
    -a network.columba.test.SEED_STORE_ART >/dev/null
line=""
for _ in $(seq 1 30); do
    line=$("$ADB" -s "$SERIAL" logcat -d -s COLUMBA_TEST |
        grep -E 'store_art_(seeded|err)' | tail -1 || true)
    [ -n "$line" ] && break
    sleep 2
done
echo "seed: $line"
case "$line" in
    *store_art_seeded*) ;;
    *) echo "seed failed: $line" >&2; exit 1 ;;
esac

# Clean status bar: AOSP demo mode hides notifications; the compositor's
# status_bar overlay in listing.yaml fixes the clock regardless.
"$ADB" -s "$SERIAL" shell settings put global demo_mode 1
"$ADB" -s "$SERIAL" shell am broadcast -p com.android.systemui \
    -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

# Capture each screen. Bottom nav at 1080x2400 (4 tabs): y=2150;
# Chats x=96, Contacts x=311, Map x=685.
"$ADB" -s "$SERIAL" shell am force-stop "$PKG"

# Dark theme to match the approved listing art.
"$ADB" -s "$SERIAL" shell cmd uimode night yes
"$ADB" -s "$SERIAL" shell am start -n "$PKG/network.columba.app.MainActivity" >/dev/null
sleep 10
cap() { "$ADB" -s "$SERIAL" exec-out screencap -p > "captures/$1.png"; echo "captured $1"; }
 "$ADB" -s "$SERIAL" shell input tap 451 2290   # dismiss BLE rationale dialog ("Not Now")
 sleep 1
 cap 02_chats
"$ADB" -s "$SERIAL" shell input tap 535 650; sleep 2; cap 04_conversation
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_BACK; sleep 1
 "$ADB" -s "$SERIAL" shell input tap 311 2150; sleep 2; cap 01_contacts
"$ADB" -s "$SERIAL" shell input tap 685 2150; sleep 10; cap 03_map
"$ADB" -s "$SERIAL" shell settings put global demo_mode 0

# ---------------------------------------------------------------------------
# 05_call: live voice call against the host-side Python RNS+LXST peer.
# call_peer.py runs a TCPServerInterface on :4242; the emulator dials in as
# a TCP client (host = 10.0.2.2). SEED_STORE_ART receives the peer's real
# destination hash + public key so the seeded "Maya Okafor" conversation
# dials an actually-reachable node; the peer auto-answers and the in-call
# UI reaches Active.
tap_text() {   # tap center of first uiautomator node with text/desc $1
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null
    local b
    # `|| true` on the -m1 grep: pipefail would otherwise turn grep's early
    # pipe exit (SIGPIPE on cat) into a script-killing failure.
    b=$("$ADB" -s "$SERIAL" shell cat /sdcard/ui.xml | tr '>' '\n' |
        { grep -m1 -E "text=\"$1\"|content-desc=\"$1\"" || true; } |
        grep -o 'bounds="[^"]*"' | sed -E 's/[^0-9]/ /g' |
        awk '{print int(($1+$3)/2), int(($2+$4)/2)}' || true)
    [ -n "$b" ] || { echo "tap_text: '$1' not found" >&2; return 1; }
    "$ADB" -s "$SERIAL" shell input tap $b
}

PEER_LOG=/tmp/columba-call-peer.log
.venv-call/bin/python call_peer.py --statedir .call-peer --port 4242 >"$PEER_LOG" 2>&1 &
PEER_PID=$!
cleanup() {
    kill "$PEER_PID" 2>/dev/null || true
    # A failed tap or a call that never establishes must not leave the
    # audio flag flipped: later calls would silently take the Phase-2
    # path. Restore whatever was there before we touched it.
    if [ "${AUDIO_FLAG_SET:-0}" = 1 ]; then
        "$ADB" -s "$SERIAL" shell settings put global columba_lxst_native_audio "$PREV_NATIVE_AUDIO" \
            >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
for _ in $(seq 1 30); do grep -q PEER_READY "$PEER_LOG" && break; sleep 1; done
if ! grep -q PEER_READY "$PEER_LOG"; then
    echo "peer did not start (alive=$(kill -0 "$PEER_PID" 2>/dev/null && echo yes || echo no)):" >&2
    tail -5 "$PEER_LOG" >&2
    ss -tlnp 2>/dev/null | grep 4242 >&2 || true
    exit 1
fi
read -r PEER_DEST PEER_PUB < <(grep -o 'dest=[0-9a-f]* pub=[0-9a-f]*' "$PEER_LOG" | sed 's/dest=//;s/pub=//') || true
echo "peer: dest=$PEER_DEST"

# LXST-kt's native Oboe/codec libs ship arm64/armeabi only; on this x86_64
# AVD the first RX audio frame would crash :reticulum with UnsatisfiedLinkError
# (an arm64 .so cannot be dlopened from an x86_64 process - the NDK
# translation bridge only applies to processes *installed* as arm64, and the
# arm64 split can't run Chaquopy, whose bootstrap assets are ABI-selected).
# The columba_lxst_native_audio flag (PythonCallManager) selects LXST-kt's
# Phase-2 pipeline instead: AudioTrack playback + the Kotlin codec path.
# Wire protocol identical; reset to native after the capture.
PREV_NATIVE_AUDIO=$("$ADB" -s "$SERIAL" shell settings get global columba_lxst_native_audio 2>/dev/null | tr -d '\r')
case "$PREV_NATIVE_AUDIO" in ""|null) PREV_NATIVE_AUDIO=1 ;; esac
"$ADB" -s "$SERIAL" shell settings put global columba_lxst_native_audio 0
AUDIO_FLAG_SET=1

# Phase-2's Opus codec still loads native code (NativeOpus: libopus +
# liblxst_opus_jni), arm-only in the AAR. Supply x86_64 builds: libopus (and
# its libc++ dependency, both from the emulator's media APEX), plus the JNI
# glue prebuilt from lxst-kt cpp/lxst_opus_jni.c (tools/x86_64/). Injected
# into the installed APK's lib dir (root; survives pm clear, wiped by
# reinstall - hence done per run).
LIBDIR=$("$ADB" -s "$SERIAL" shell pm path "$PKG" | head -1 | tr -d '\r' |
    sed 's/^package://; s|base.apk$||')lib/x86_64
for lib in libopus.so libc++.so; do
    "$ADB" -s "$SERIAL" shell cp "/apex/com.android.media.swcodec/lib64/$lib" \
        "/data/local/tmp/$lib"
done
"$ADB" -s "$SERIAL" push tools/x86_64/liblxst_opus_jni.so /data/local/tmp/ >/dev/null
"$ADB" -s "$SERIAL" root >/dev/null 2>&1; sleep 1
"$ADB" -s "$SERIAL" shell "for l in libopus.so libc++.so liblxst_opus_jni.so; do cp /data/local/tmp/\$l '$LIBDIR'/ && chmod 644 '$LIBDIR'/\$l && rm /data/local/tmp/\$l; done"

# Fresh state + TCP uplink; the interface config applies on service restart
# (proven pattern from tests/interop/setup_emulator.sh).
"$ADB" -s "$SERIAL" shell pm clear "$PKG" >/dev/null
"$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO
"$ADB" -s "$SERIAL" shell am start -n "$PKG/network.columba.app.MainActivity" >/dev/null
sleep 20
"$ADB" -s "$SERIAL" shell am broadcast -p "$PKG" -a network.columba.test.ONBOARD >/dev/null
sleep 2
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am broadcast -p "$PKG" -a network.columba.test.ADD_TCP_CLIENT \
    --es name hostlink --es host 10.0.2.2 --es port 4242 >/dev/null
# The handler saves the interface asynchronously; killing the process before
# the ack can drop the uplink entirely on next launch.
SAVED=0
for _ in $(seq 1 15); do
    "$ADB" -s "$SERIAL" logcat -d -s COLUMBA_TEST |
        grep -q 'interface_added name=hostlink' && { SAVED=1; break; }
    sleep 2
done
[ "$SAVED" = 1 ] || { echo "ADD_TCP_CLIENT save never acked" >&2; exit 1; }
"$ADB" -s "$SERIAL" shell am force-stop "$PKG"
"$ADB" -s "$SERIAL" shell am start -n "$PKG/network.columba.app.MainActivity" >/dev/null
sleep 20   # :reticulum up, TCP interface connects, peer announce lands
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am broadcast -p "$PKG" -a network.columba.test.SEED_STORE_ART \
    --es maya_hash "$PEER_DEST" --es maya_pub "$PEER_PUB" >/dev/null
for _ in $(seq 1 30); do
    "$ADB" -s "$SERIAL" logcat -d -s COLUMBA_TEST | grep -q store_art_seeded && break
    sleep 2
done

# Demo-mode bar again (reset above); dark theme is still on.
"$ADB" -s "$SERIAL" shell settings put global demo_mode 1
"$ADB" -s "$SERIAL" shell am force-stop "$PKG"
"$ADB" -s "$SERIAL" shell am start -n "$PKG/network.columba.app.MainActivity" >/dev/null
sleep 10
"$ADB" -s "$SERIAL" shell input tap 451 2290   # BLE rationale "Not Now"
sleep 1
"$ADB" -s "$SERIAL" shell input tap 535 650    # open Maya's conversation
sleep 2
tap_text "Voice call"                          # header call icon -> codec dialog
sleep 2
tap_text "Call"                                # confirm dialog (default MQ profile)
for _ in $(seq 1 60); do grep -q CALL_ESTABLISHED "$PEER_LOG" && break; sleep 1; done
grep -q CALL_ESTABLISHED "$PEER_LOG" ||
    { echo "call did not establish: $(tail -3 "$PEER_LOG")" >&2; exit 1; }
sleep 4   # let the duration timer tick past 0:0X
cap 05_call
tap_text "End Call"
sleep 2
kill "$PEER_PID" 2>/dev/null || true
AUDIO_FLAG_SET=0
"$ADB" -s "$SERIAL" shell settings put global columba_lxst_native_audio "$PREV_NATIVE_AUDIO"

# 06: the interface-type modal - Auto / TCP / Bluetooth LE / RNode LoRa in one
# list, i.e. "internet optional" made literal. The + FAB carries no text, so
# its center is a coordinate tap; the dialog always opens at the same height.
# End Call leaves the voice screen mid-animation, so back out until the tab
# bar is actually on screen instead of assuming a fixed number of BACKs.
for _ in 1 2 3 4; do
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell cat /sdcard/ui.xml | grep -q 'text="Settings"' && break
    "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_BACK
    sleep 2
done
tap_text "Settings"
sleep 2
tap_text "Network"                                      # expand the section
sleep 2
tap_text "Manage Interfaces"
sleep 3
"$ADB" -s "$SERIAL" shell input tap 964 2221            # + -> type dialog
sleep 2
cap 06_interfaces

.venv-call/bin/python compose.py
