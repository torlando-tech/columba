"""Device fixtures + adb driver for the columba#1127 e2e.

This suite proves (and, until fixed, guards RED) that after an
identity/profile-transition process restart during a network gap, RNS recovers
with the configured interface set INTACT  - instead of the reported dead state
(Reticulum READY but 0 live interfaces, every send dying with "No interfaces
could process the outbound packet").

The reporter's trigger is a GrapheneOS user-profile switch, which the OS uses
to reap background processes while the default network briefly drops. That is a
plain Android lifecycle event (process death + a transport-NONE window), NOT a
Graphene-specific one, so we reproduce it deterministically with adb:

    1. airplane mode ON  -> default network lost -> CurrentTransport.NONE
    2. force-stop        -> kills :reticulum + app (the profile-switch reap)
    3. cold start (still in the NONE window) -> startup filters enabled
       interfaces to ZERO -> RNS comes up READY with 0 interfaces
    4. airplane mode OFF -> network restored; the app SHOULD re-apply the
       interface set. (Bug: python `reloadInterfaces` is a no-op, so it does
       not.)
    5. assert: RNS is READY and live interface count == configured (DB) count.

All device reads go through the debug TestReceiver surface (COLUMBA_TEST tag):
  - GET_DEST        -> active LXMF destination (onboarding pre-check)
  - LIVE_STATE      -> status=… db=<n> live=<n> online=<n> names=<…>
  - RESTART_SERVICE -> drives the UI "Restart" path (recovery confirmation)

Run with tests/e2e_profile_swap/run.sh (builds + installs the python-backend
debug APK if needed, then runs pytest). Override the device with COLUMBA_SERIAL.
"""
from __future__ import annotations

import os
import re
import subprocess
import time

import pytest

APP_ID = os.environ.get("COLUMBA_APP_ID", "network.columba.app.debug")
APP_ID_PREFIX = "network.columba.app"
RECEIVER = f"{APP_ID}/network.columba.app.test.TestReceiver"
LOGCAT_TAG = "COLUMBA_TEST"

# Polling / recovery windows (seconds).
# A clean python-backend cold start takes ~20-30s to reach the intact live
# set (measured 21s on the KVM emulator); the baseline/finally waits below use
# SETTLE_S*2, so SETTLE_S must leave ~2x headroom or the post-cold-start wait
# times out mid-initialization (transient live=1) and a later test false-FAILS
# at its baseline assertion. 25 -> 50s of headroom.
SETTLE_S = 25          # after a cold start, let RNS finish INITIALIZING
RECOVERY_S = int(os.environ.get("COLUMBA_RECOVERY_S", "30"))  # auto-recovery window
RESTART_S = 35         # UI Restart path settle time (full RNS re-init + apply)


class Adb:
    """Thin, logcat-parse-aware adb wrapper. No retry logic here  - the test
    asserts on explicit windows so failures are attributable, not masked."""

    def __init__(self, serial: str):
        self.serial = serial

    def shell(self, cmd: str, timeout: int = 20) -> str:
        p = subprocess.run(
            ["adb", "-s", self.serial, "shell", cmd],
            capture_output=True, text=True, timeout=timeout,
        )
        return p.stdout

    def shell_batch(self, body: str, timeout: int = 90) -> str:
        """Run a multi-statement script in ONE adb shell session.

        The kill/force-stop/cold-start trigger MUST run this way: each separate
        `adb shell` call adds a host round-trip, and the emulated carrier
        re-registers within ~5-10s of the kill. Per-call overhead pushed the
        cold start past the re-registration, so `loadConfig` saw a live
        transport and the trigger false-passed (the #1127 trigger race).
        One shell collapses the whole sequence to ~1s.
        """
        p = subprocess.run(
            ["adb", "-s", self.serial, "shell", body],
            capture_output=True, text=True, timeout=timeout,
        )
        return p.stdout + p.stderr

    def raw(self, *args: str, timeout: int = 30) -> str:
        p = subprocess.run(
            ["adb", "-s", self.serial, *args],
            capture_output=True, text=True, timeout=timeout,
        )
        return p.stdout + p.stderr

    def broadcast(self, action: str, extra: list[str] | None = None) -> str:
        args = ["shell", "am", "broadcast", "-n", RECEIVER, "-a", action]
        for token in (extra or []):
            args.append(token)
        return self.raw(*args, timeout=20)

    def logcat(self, clear: bool = False) -> str:
        args = ["logcat", "-d", "-s", f"{LOGCAT_TAG}:V", "*:S"]
        out = self.raw(*args, timeout=20)
        if clear:
            self.raw("logcat", "-c")
        return out

    def logcat_all(self) -> str:
        return self.raw("logcat", "-d", "-v", "time", timeout=30)


def intact(state: dict | None) -> bool:
    """#1127 invariant: every enabled-configured interface is present in the
    live transport set. (live may hold extra AutoDiscovery peers, so it is a
    SUPERSET, not an exact match.)"""
    if state is None or state["status"] != "READY" or not state["enabled"]:
        return False
    return state["enabled"].issubset(state["live_set"])


def parse_live_state(logcat_text: str) -> dict | None:
    """Return the most recent `live_state …` read parsed, or None if absent.

    Format (see TestController.handleLiveState):
        live_state status=<S> initialized=<bool> db=<n> live=<n> online=<n>
                 enabled=<name|name|…|->
        live_iface <name>            (one line per live interface)
        live_state_err reason=<...>

    Returns the summary fields plus two sets:
        `enabled`: the enabled-configured interface names (the invariant set)
        `live`:    the live transport interface names (may include AutoDiscovery
                   peers, so it is expected to be a SUPERSET of `enabled` when
                   healthy)
    `live_state_err` is returned with `status="ERR"` so callers can distinguish
    "hook errored" from "no read yet".
    """
    result = None
    live_names: list[str] = []
    err = None
    for line in logcat_text.splitlines():
        if "live_state_err" in line:
            m = re.search(r"live_state_err reason=(\S+)", line)
            err = m.group(1) if m else ""
            continue
        m = re.search(
            r"live_state status=(\S+) initialized=(\S+) db=(-?\d+) "
            r"live=(-?\d+) online=(-?\d+) enabled=(\S+)", line)
        if m:
            enabled_raw = m.group(6)
            enabled = set() if enabled_raw == "-" else \
                set(enabled_raw.split("|"))
            result = {
                "status": m.group(1),
                "initialized": m.group(2) == "true",
                "db": int(m.group(3)),
                "live": int(m.group(4)),
                "online": int(m.group(5)),
                "enabled": enabled,
                "live_set": set(),   # filled below from live_iface lines
            }
            continue
        im = re.search(r"live_iface (.+)$", line)
        if im:
            live_names.append(im.group(1))
    if result is not None:
        result["live_set"] = set(live_names)
        if err:
            result["err"] = err
    elif err:
        result = {"status": "ERR", "db": -1, "live": -1, "online": -1,
                  "enabled": set(), "live_set": set(), "err": err}
    if result is not None:
        # Undo TestController.escape() sentinels so name comparisons work on
        # the real interface names (spaces are encoded as U+2423 in logs).
        result["enabled"] = {_unescape(n) for n in result["enabled"]}
        result["live_set"] = {_unescape(n) for n in result["live_set"]}
    return result


def _unescape(name: str) -> str:
    return (name.replace("\u2423", " ")
               .replace("\u23ce", "\n")
               .replace("\u240d", "\r")
               .replace("\u2409", "\t"))


class Device:
    """High-level device commands the test scenario is expressed in."""

    def __init__(self, serial: str):
        self.a = Adb(serial)

    def installed(self) -> bool:
        # `pm list packages <id>` prints `package:<id>` when present, empty
        # otherwise. (The old first clause compared the app id against a
        # `grep -c` COUNT string, which is never a substring of a number, so
        # this predicate was always False and the suite always skipped.)
        return self.a.shell(f"pm list packages {APP_ID}").strip() != ""

    def install(self, apk: str, timeout: int = 300) -> bool:
        """Install a debug APK over any existing build (CI self-install).

        `run.sh` normally does the build+install; CI runs pytest directly, so the
        suite installs from `COLUMBA_E2E_APK` when the app is absent. `-g` grants
        all manifest permissions (the suite broadcasts debug hooks and needs them
        without a manual grant step). Returns True when `pm list` confirms it.
        """
        out = self.a.raw("install", "-r", "-g", apk, timeout=timeout)
        ok = "Success" in out
        print(f"[#1127] adb install {apk}: {'OK' if ok else out.strip()[:200]}")
        return ok

    def dest(self) -> str | None:
        self.a.broadcast("network.columba.test.GET_DEST")
        time.sleep(2.0)
        # Reticulum destination hashes are 16 bytes = 32 hex chars (e.g.
        # cf214dee4c2b8362b809062cd4c68fc5). The old {64} never matched, so
        # the precondition always skipped as "not onboarded".
        m = re.search(r"dest=([0-9a-f]{32,64})", self.a.logcat())
        return m.group(1) if m else None

    def onboard(self, timeout: float = 120) -> bool:
        """Programmatically complete onboarding for a fresh install (CI).

        Triggers the debug `ONBOARD` hook, which (mirroring the production
        skip-onboarding path) creates an active identity from the live LXMF
        stack, seeds a single enabled AutoInterface if none exists, and marks
        onboarding complete. The hook reads the live stack, so it must run
        after the python RNS backend is READY; if the first attempt comes
        back before that (onboard_err), wait and retry. Returns True once
        `dest=` is observable.

        Used only on a clean CI emulator; on an already-onboarded device the
        suite's precondition finds a destination and never calls this.
        """
        # Ensure the app is foregrounded and given time to initialize the
        # python RNS backend before the hook reads the live identity.
        self.a.shell(
            "monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1" % APP_ID)
        time.sleep(30)
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.a.broadcast("network.columba.test.ONBOARD")
            time.sleep(8)
            out = self.a.logcat()
            if "onboarded" in out and "done=true" in out:
                # Interface was just inserted; a service restart applies it to
                # the live stack (same path the UI takes after onboarding).
                # Readiness is left to the precondition's subsequent
                # wait_live_state(intact, ...) - python RNS needs ~25-30s.
                self.a.broadcast("network.columba.test.RESTART_SERVICE")
                return True
            time.sleep(5)
        return False

    def live_state(self) -> dict | None:
        self.a.raw("logcat", "-c")
        self.a.broadcast("network.columba.test.LIVE_STATE")
        deadline = time.time() + 15
        last = None
        while time.time() < deadline:
            time.sleep(0.8)
            last = parse_live_state(self.a.logcat())
            if last is not None:
                return last
        return last

    def wait_live_state(self, pred, timeout: float) -> dict | None:
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            last = self.live_state()
            if last is not None and pred(last):
                return last
        return last

    def restart_service(self) -> None:
        self.a.broadcast("network.columba.test.RESTART_SERVICE")

    # --- transport / process lifecycle (the identity-swap trigger) ---
    def trigger_profile_swap(self) -> str:
        """The deterministic #1127 trigger as ONE adb shell batch.

        Kill the network, confirm it is down, force-stop, and cold start -
        with no host round-trips between the steps. Returns the batch output
        (callers check for the `[b1127] down=yes` token).

        Why one batch: the emulated carrier re-registers within ~5-10s of the
        kill. Issuing each step as a separate `adb shell` call added a host
        round-trip per step, which pushed the cold start past the
        re-registration - `loadConfig` then saw a live transport and the
        dead state never baked in (the trigger race: 3/3 false-pass runs on
        2026-09-10, 3/3 dead-state runs in a single batch on 2026-09-13).
        """
        body = f"""
settings put global airplane_mode_on 1 >/dev/null
am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1
svc wifi disable >/dev/null
svc data disable >/dev/null
down=no
for i in $(seq 1 30); do
  if dumpsys connectivity 2>/dev/null | grep -qi "Active default network: none"; then
    down=yes
    echo "[b1127] down=yes i=$i"
    break
  fi
  sleep 0.5
done
[ "$down" = yes ] || echo "[b1127] down=no after 15s"
am force-stop {APP_ID}
sleep 0.5
monkey -p {APP_ID} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "[b1127] launched"
"""
        return self.a.shell_batch(body, timeout=60)

    def restore_network(self) -> None:
        self.a.shell("settings put global airplane_mode_on 0")
        self.a.shell(
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
        self.a.shell("svc wifi enable")
        self.a.shell("svc data enable")

    def wait_network_up(self, timeout: float = 40) -> bool:
        """Poll until `dumpsys connectivity` reports an active default network
        (the `Active default network:` line is no longer "none")."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(1.5)
            out = self.a.shell(
                "dumpsys connectivity | grep -i 'Active default network'")
            if out and "none" not in out.lower():
                return True
        return False

    def force_stop(self) -> None:
        self.a.shell(f"am force-stop {APP_ID}")

    def cold_start(self) -> None:
        self.a.shell(f"monkey -p {APP_ID} -c android.intent.category.LAUNCHER 1")

    def reticulum_pid(self) -> str:
        return self.a.shell(f"pidof '{APP_ID}:reticulum'").strip()

    def restore_healthy(self) -> None:
        """Deterministically leave the device in the intact state between tests.

        A plain cold start is NOT a sufficient guarantee on the python backend:
        if the network is still settling (or the cold start races a transport
        transition), the startup filter can bake in the SAME dead state this
        suite is testing (#1127), and that state never auto-recovers - so the
        device would be handed to the next test in exactly the broken shape
        and its baseline assertion would false-FAIL. Restore the network,
        cold start, and if the bounded wait does not confirm `intact`, fall
        back to the proven manual recovery (RESTART_SERVICE, the UI Restart
        path) which always re-applies the full set. The device is guaranteed
        healthy on return.
        """
        self.restore_network()
        self.wait_network_up(timeout=45)
        self.force_stop()
        self.cold_start()
        st = self.wait_live_state(intact, timeout=SETTLE_S * 2)
        if st is None or not intact(st):
            print(f"[#1127 cleanup] cold start not intact ({st and st['status']} "
                  f"live={st and st['live']}); falling back to manual recovery")
            self.restart_service()
            self.wait_live_state(intact, timeout=RESTART_S * 2)


@pytest.fixture(scope="session")
def serial() -> str:
    s = os.environ.get("COLUMBA_SERIAL")
    if s:
        return s
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    pytest.skip("No adb device found and COLUMBA_SERIAL unset")


@pytest.fixture(scope="session")
def device(serial) -> Device:
    return Device(serial)


@pytest.fixture(scope="session")
def precondition(device) -> bool:
    """The suite needs an onboarded device with >=1 configured, live interface.
    If that's not true we can't meaningfully assert recovery, so skip (setup
    required) rather than produce a misleading failure.

    Self-heals first: a prior run (or run.sh's pre-check launch) can leave the
    app in the #1127 dead state, which never auto-recovers. A dead baseline
    would skip/true-FAIL for the wrong reason, so try the proven manual
    recovery (restore_healthy: cold start, RESTART_SERVICE fallback) before
    deciding the device is unready.
    """
    if not device.installed():
        apk = os.environ.get("COLUMBA_E2E_APK")
        if not apk:
            # Local run.sh mode: the app is expected to already be installed; its
            # absence is a setup condition, not a test failure, so skip.
            pytest.skip(f"{APP_ID} not installed (build+install via run.sh first)")
        # CI mode: COLUMBA_E2E_APK was supplied, so the suite MUST run. A missing
        # APK or a failed install is a hard failure - skipping would let both
        # recovery tests pass vacuously and the lane falsely go green.
        if not os.path.exists(apk):
            pytest.fail(f"COLUMBA_E2E_APK set but {apk} not found - cannot install")
        print(f"[#1127] {APP_ID} not installed - installing {apk}")
        if not device.install(apk):
            pytest.fail(f"{APP_ID} install from COLUMBA_E2E_APK failed")
    if device.dest() is None:
        # Fresh install (CI emulator): programmatically complete onboarding
        # (identity + one AutoInterface), then re-check. If it still can't be
        # reached the suite skips for the same reason as before.
        print("[#1127] no destination - attempting programmatic onboarding (CI)")
        if not device.onboard():
            pytest.skip("no active LXMF destination - onboarding did not complete")
    st = device.wait_live_state(intact, timeout=SETTLE_S * 2)
    if st is None:
        pytest.skip(f"no READY live_state at baseline  - got {st and st['status']}")
    if st["db"] < 1:
        pytest.skip(f"no configured interface (db={st['db']})  - set one up")
    if not intact(st):
        print(f"[#1127] baseline not intact ({st['status']} "
              f"live={st['live']} live_set={sorted(st['live_set'])}); "
              "self-healing via restore_healthy() before re-checking")
        device.restore_healthy()
        st = device.wait_live_state(intact, timeout=SETTLE_S * 2)
        if st is None or not intact(st):
            enabled_names = sorted(st["enabled"]) if st else []
            live_names = sorted(st["live_set"]) if st else []
            pytest.skip(f"baseline still not intact after self-heal "
                       f"(enabled={enabled_names}, live={live_names})  - recheck RNS")
    return True
