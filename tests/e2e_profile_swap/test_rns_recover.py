"""columba#1127  - RNS must recover with interfaces intact after an
identity/profile-swap process restart during a network gap.

Reporter: after switching Android user profiles (which reaps the app's
background processes while the default network briefly drops), Columba shows
Reticulum READY but the interface set is gone; nothing is sent/received until
the user taps Restart. That is the python-backend transport-filter + no-op
reloadInterfaces dead state.

This is a permanent, device-driven regression guard. It is RED on current
code: the deterministic trigger below makes the cold start happen during a
transport-NONE window, the startup filter drops every IP interface, RNS comes
up READY with an empty live set, and (bug) nothing re-applies the interface
set when the network returns  - so the "recovered with interfaces intact"
assertion fails.

The invariant ("intact") is: every ENABLED configured interface name is
present in the live RNS transport set. AutoDiscovery spawns peer
sub-interfaces, so the live set is a SUPERSET of the configured set when
healthy (e.g. db=3, live=10)  - the subset check is the correct invariant.

Trigger == the profile switch, made deterministic with adb:
    airplane ON (=> CurrentTransport.NONE) -> force-stop -> cold start in the
    NONE window -> airplane OFF (network restored) -> expect auto-recovery.
"""
from __future__ import annotations

import time

import pytest

from conftest import RECOVERY_S, RESTART_S, SETTLE_S, intact


def _attempt_trigger(device):
    """One kill->force-stop->cold-start attempt (single adb shell batch).
    Returns 'down' (network confirmed gone), 'dead' (IP interfaces dropped),
    or 'live' (cold start saw a live transport - the carrier won the race)."""
    out = device.trigger_profile_swap()
    print(f"[#1127] trigger batch: {' | '.join(l.strip() for l in out.splitlines() if l.strip())}")
    if "down=yes" not in out or "launched" not in out:
        return "down"
    state = device.wait_live_state(
        lambda s: s is not None and s["status"] == "READY" and s["initialized"],
        timeout=90)
    if state is None or not state["initialized"]:
        return "down"  # backend slow to READY; treat as attempt-not-formed
    # The dead state only bakes in once RNS commits the transport-filtered set
    # at loadConfig. READY + enabled IP interfaces missing from the live set
    # means the NONE window held through loadConfig (trigger succeeded).
    if state["enabled"] - state["live_set"]:
        print(f"[#1127] dead state baked in: {state}; missing="
              f"{sorted(state['enabled'] - state['live_set'])}")
        return "dead"
    print(f"[#1127] cold start saw a live transport (dead state not created): "
          f"{state}")
    return "live"


def _trigger_profile_swap(device, max_attempts=4):
    """Deterministic identity-swap repro: process death during a transport-NONE
    window, exactly the lifecycle the OS performs on a user-profile switch.

    Sequence per attempt (all in ONE adb shell batch - see
    Device.trigger_profile_swap):
      1. kill the guest network (=> ConnectivityManager.activeNetwork null
         => CurrentTransport.NONE)
      2. confirm the default network is actually gone
      3. force-stop the app (the profile-switch reap)
      4. cold start DURING the NONE window (startup filters IP interfaces out)

    The emulated carrier re-registers ~5-10s after the kill, so the NONE
    window sometimes does NOT hold through `loadConfig` - the app then starts
    on a live transport and no dead state forms. That is a trigger miss, not a
    product result, so retry (bounded) until the dead state actually forms.
    A single batch per attempt is required: per-step `adb shell` calls add a
    host round-trip each and push the cold start far past re-registration.

    Returns once the dead state is confirmed, and fails loudly if it cannot be
    formed within `max_attempts` (never silently passing).
    """
    last = "down"
    for attempt in range(1, max_attempts + 1):
        last = _attempt_trigger(device)
        if last == "dead":
            break
        if attempt == max_attempts:
            break
        print(f"[#1127] attempt {attempt}/{max_attempts}: no dead state "
              f"({last}); restoring + retrying")
        device.restore_network()
        device.wait_network_up(timeout=45)
        time.sleep(3)
    if last != "dead":
        device.restore_network()
        device.wait_network_up(timeout=45)
        pytest.fail(
            "#1127 TRIGGER FAILED after "
            f"{max_attempts} attempts (last={last}): could not bake the dead "
            "state. The NONE window did not hold through loadConfig, or the "
            "network would not go down. This is NOT a product result - re-run "
            "or investigate the trigger.")
    # Network restored; the app must re-apply the interface set itself.
    device.restore_network()
    device.wait_network_up(timeout=45)
    time.sleep(3)


@pytest.mark.timeout(600)
def test_rns_reclaims_interfaces_after_profile_swap(device, precondition):
    """After a process restart that happens during a network gap, RNS must
    return to the configured interface set INTACT once the network is back -
    without a manual Restart. Currently RED: the live set stays empty."""
    assert precondition, "precondition fixture should have skipped if unmet"

    base = device.wait_live_state(intact, timeout=SETTLE_S * 2)
    assert base is not None and intact(base), f"no intact baseline: {base}"
    enabled = set(base["enabled"])
    assert base["db"] >= 1 and enabled, f"baseline has no enabled interface: {base}"
    print(f"[#1127] baseline intact: db={base['db']} enabled={sorted(enabled)} "
          f"live={base['live']} live_set={sorted(base['live_set'])}")

    try:
        _trigger_profile_swap(device)

        # Capture the immediate post-swap state as evidence of the bug.
        dead = device.live_state()

        # Recovery window: poll until the live set is intact again.
        recovered = device.wait_live_state(intact, timeout=RECOVERY_S)

        if not (recovered is not None and intact(recovered)):
            last = device.live_state()
            missing = enabled - (last["live_set"] if last else set())
            log = device.a.logcat_all()
            dead_send = "No interfaces could process the outbound packet"
            pytest.fail(
                "#1127: RNS did NOT recover with interfaces intact after an "
                f"identity/profile-swap restart during a network gap. "
                f"baseline enabled={sorted(enabled)}; "
                f"post-swap (bug) state={dead}; "
                f"after {RECOVERY_S}s recovery window: status="
                f"{last and last['status']} live={last and last['live']} "
                f"live_set={last and sorted(last['live_set'])} "
                f"missing_enabled={sorted(missing)}; "
                f"'{dead_send}' in logcat={'YES' if dead_send in log else 'no'}. "
                "Auto-recovery is broken (python reloadInterfaces is a no-op); "
                "only the manual Restart path recovers."
            )

        print(f"[#1127] recovered intact: status={recovered['status']} "
              f"live={recovered['live']} live_set={sorted(recovered['live_set'])}")
    finally:
        # Leave the device healthy regardless of outcome.
        device.restore_healthy()


@pytest.mark.timeout(420)
def test_restart_button_recovers_interfaces(device, precondition):
    """Positive control: the reporter's manual workaround (Restart) DOES
    restore the interface set. Must be GREEN now and after the fix, proving
    the harness distinguishes the broken auto-recovery path from the working
    manual one.

    The Restart path re-reads the DB and re-filters against the CURRENT
    transport (`applyInterfaceChanges`). If it is pressed while the restored
    network is still mid-transition, it can bake in a PARTIAL set (e.g. the
    hub back but Auto Discovery not yet). The reporter taps Restart from the
    UI with a healthy-looking network, so this control settles the network
    first and retries once if the result is still partial."""
    assert precondition, "precondition fixture should have skipped if unmet"

    base = device.wait_live_state(intact, timeout=SETTLE_S * 2)
    assert base is not None and intact(base)
    enabled = set(base["enabled"])

    try:
        _trigger_profile_swap(device)

        recovered = None
        for attempt in (1, 2):
            # Let the restored network settle before restarting: Restart
            # re-filters on the live transport, and a mid-transition transport
            # drops IP interfaces again (a partial, not full, recovery).
            time.sleep(5)
            device.restart_service()
            recovered = device.wait_live_state(intact, timeout=RESTART_S)
            last = device.live_state()
            if recovered is not None and intact(recovered):
                break
            print(f"[#1127 control] Restart attempt {attempt} partial "
                  f"(live_set={sorted(last and last['live_set'])}); "
                  "settling network and retrying")
            device.restore_network()
            device.wait_network_up(timeout=45)
        assert recovered is not None and intact(recovered), (
            f"Restart button did not restore interfaces: enabled={sorted(enabled)}, "
            f"got {last}"
        )
        print(f"[#1127 control] Restart restored intact live set "
              f"(live={recovered['live']})")
    finally:
        # Leave the device healthy regardless of outcome.
        device.restore_healthy()
