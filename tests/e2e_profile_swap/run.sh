#!/usr/bin/env bash
# run.sh  - build + install + run the columba#1127 permanent e2e.
#
# Proves (guards RED until fixed) that after an identity/profile-swap process
# restart during a network gap, RNS recovers with the configured interface set
# INTACT, instead of the reported READY-but-0-interfaces dead state.
#
# Usage:
#   ./run.sh                          # build pythonBackend debug, install, run
#   COLUMBA_SERIAL=<adb-serial> ./run.sh
#   SKIP_BUILD=1 ./run.sh             # reuse the already-built APK
#   SKIP_INSTALL=1 ./run.sh           # app already on the device (standalone box)
#   ./run.sh -k test_rns_reclaims...  # pass-through to pytest
#
# Prereqs: a connected adb device (phone or emulator), an onboarded Columba
# with >=1 configured interface. The debug build is required (LIVE_STATE /
# RESTART_SERVICE hooks live in app/src/debug and are compiled out of release).
#
# Env:
#   COLUMBA_SERIAL     adb serial (default: first `adb devices` entry)
#   COLUMBA_APP_ID     default network.columba.app.debug
#   PYTHON             python with pytest installed (default: $HOME/.reticulum-host/venv)
#   SKIP_BUILD=1       don't rebuild the APK
#   SKIP_INSTALL=1     don't build or install (app already on the device)
#   APK                explicit APK path to install
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO="$(cd "$HERE/../../" && pwd)"
APP_ID="${COLUMBA_APP_ID:-network.columba.app.debug}"
APK="${APK:-$REPO/app/build/outputs/apk/noSentryPythonBackend/debug/app-noSentry-pythonBackend-universal-debug.apk}"

# JDK 21 (matches the project's toolchain).
if [[ -z "${JAVA_HOME:-}" ]]; then
  for cand in /usr/lib/jvm/java-21-openjdk-amd64 "$HOME/.sdkman/candidates/java/current"; do
    [[ -x "$cand/bin/java" ]] && export JAVA_HOME="$cand" && break
  done
fi
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"

# 1. Build (debug, python backend = the backend the reporter runs).
#    SKIP_INSTALL=1 skips build+install entirely (the app is already on the
#    device - e.g. running the suite standalone on a box without a repo).
if [[ "${SKIP_INSTALL:-0}" != "1" ]]; then
  if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo "=== building pythonBackend debug APK ==="
    (cd "$REPO" && ./gradlew :app:assembleNoSentryPythonBackendDebug -x lint)
  fi
  if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at $APK (set APK=... or SKIP_BUILD=1 after building)"; exit 2
  fi

  # 2. Device.
  SERIAL="${COLUMBA_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
  if [[ -z "${SERIAL:-}" ]]; then
    echo "ERROR: no adb device (set COLUMBA_SERIAL)"; exit 3
  fi
  echo "=== device: $SERIAL ==="
  adb -s "$SERIAL" shell getprop ro.product.model
  adb -s "$SERIAL" shell getprop ro.build.version.release

  # 3. Install.
  echo "=== installing $APP_ID ==="
  adb -s "$SERIAL" install -r -g "$APK"
else
  echo "SKIP_INSTALL=1 - assuming $APP_ID is already on the device"
  SERIAL="${COLUMBA_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
  if [[ -z "${SERIAL:-}" ]]; then
    echo "ERROR: no adb device (set COLUMBA_SERIAL)"; exit 3
  fi
  echo "=== device: $SERIAL ==="
fi

# 4. Onboarding pre-check: the app must have an active identity + >=1 interface.
#    (If a fresh install has no data, run onboarding once on the device first.)
#    Restore the network first: a prior run's trigger can leave the guest down,
#    and this pre-check's launch would then bake in the #1127 dead state (which
#    never auto-recovers). The suite's precondition self-heals a dead baseline
#    anyway, but starting healthy is cheaper than recovering.
echo "=== pre-check: active LXMF destination ==="
adb -s "$SERIAL" shell 'settings put global airplane_mode_on 0
am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
svc wifi enable
svc data enable' >/dev/null 2>&1 || true
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 15
# GET_DEST must be BROADCAST (the launch alone does not emit it), and the
# grep-no-match pipeline must not trip `set -o pipefail` (empty DEST is handled
# below, not a script error).
adb -s "$SERIAL" shell "am broadcast -n $APP_ID/network.columba.app.test.TestReceiver -a network.columba.test.GET_DEST" >/dev/null 2>&1 || true
sleep 2
DEST=$(adb -s "$SERIAL" logcat -d -s COLUMBA_TEST:V | grep -oE 'dest=[0-9a-f]{32,64}' | tail -1 | cut -d= -f2 || true)
if [[ -z "$DEST" ]]; then
  echo "ERROR: no active LXMF destination. Onboard the app (or point COLUMBA_SERIAL at"
  echo "       a device that already has an identity + an interface configured)."; exit 4
fi
echo "dest=$DEST"

# 5. Run the suite.
# -s disables output capture: the [#1127] trigger prints (dead-state evidence,
# trigger-failure guards) are only visible on PASSING tests when capture is off.
PYTHON="${PYTHON:-$HOME/.reticulum-host/venv/bin/python3}"
[[ -x "$PYTHON" ]] || PYTHON="$(command -v python3)"
"$PYTHON" -m pytest --version >/dev/null 2>&1 || "$PYTHON" -m pip install -q pytest pytest-timeout
export COLUMBA_SERIAL="$SERIAL" COLUMBA_APP_ID="$APP_ID"
echo "=== running columba#1127 e2e ==="
cd "$HERE"
"$PYTHON" -m pytest -s -v --tb=short --junit-xml="$HERE/1127-results.xml" "$@" "$HERE/test_rns_recover.py"
