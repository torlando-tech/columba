"""Text-only round-trip across all three LXMF delivery methods.

Each test sends a distinctive content string from one peer to the other,
then asserts the recipient saw an inbound LXMessage with matching content
and source-hash.
"""

from __future__ import annotations

import time

import pytest


@pytest.mark.timeout(60)
def test_text_direct_columba_to_sideband(interop):
    """A → B via DIRECT (link-based) — the most common path."""
    content = f"columba_to_sideband_direct_{int(time.time() * 1000)}"
    interop.columba.send_text(interop.sideband_hex, content, method="DIRECT")

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: m.content_text == content,
        timeout=45,
    )
    assert msg.content_text == content
    assert msg.source_hash.hex() == interop.columba_hex


@pytest.mark.timeout(60)
def test_text_direct_sideband_to_columba(interop):
    """B → A via DIRECT — reverse direction, same path."""
    content = f"sideband_to_columba_direct_{int(time.time() * 1000)}"
    assert interop.sideband.send_text(interop.columba_hex, content)

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == content,
        timeout=45,
    )
    assert msg.content == content
    assert msg.source_hex == interop.sideband_hex


@pytest.mark.timeout(60)
def test_text_opportunistic_columba_to_sideband(interop):
    """A → B via OPPORTUNISTIC (single packet, no link). Used by Columba
    for short live messages when a ratchet is available."""
    content = f"columba_to_sideband_opp_{int(time.time() * 1000)}"
    interop.columba.send_text(interop.sideband_hex, content, method="OPPORTUNISTIC")

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: m.content_text == content,
        timeout=45,
    )
    assert msg.content_text == content


@pytest.mark.timeout(60)
def test_text_opportunistic_sideband_to_columba(interop):
    """Sideband picks OPPORTUNISTIC automatically when no live link is
    available and a ratchet exists — we don't choose, but the test asserts
    the message arrives regardless of which low-level method got selected.
    """
    content = f"sideband_to_columba_opp_{int(time.time() * 1000)}"
    # Sideband's `send_message(propagation=False)` selects between
    # OPPORTUNISTIC and DIRECT internally based on link availability.
    # That selection IS the test — we just assert the bytes survive.
    assert interop.sideband.send_text(interop.columba_hex, content)

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == content,
        timeout=45,
    )
    assert msg.content == content


def _retry_sync_until(predicate, sync_fn, timeout: float, retry_every: float = 15.0):
    """Trigger `sync_fn()` periodically until `predicate()` returns true
    or `timeout` elapses. Used by PROPAGATED tests because:
      - Columba's outbound PROPAGATED queue waits ~2 min before its
        first upload attempt (LXMRouter's `defer_propagation_stamp`
        backoff), so the recipient's first sync usually returns nothing.
      - LXMF doesn't push — recipients must poll lxmd. We re-trigger
        the sync at a cadence rather than blocking on a single fetch.

    Returns True on predicate match, False on timeout."""
    deadline = time.time() + timeout
    last_sync = 0.0
    while time.time() < deadline:
        if time.time() - last_sync > retry_every:
            try:
                sync_fn()
            except Exception:  # noqa: BLE001
                # Sync failures (link not yet up, etc.) are tolerated;
                # the deadline catches a stuck state eventually.
                pass
            last_sync = time.time()
        if predicate():
            return True
        time.sleep(2.0)
    return False


@pytest.mark.slow
@pytest.mark.timeout(420)
def test_text_propagated_columba_to_sideband(interop, request):
    """A → B via PROPAGATED (lxmd-mediated). Columba uploads to lxmd;
    Sideband fetches via `request_messages_from_propagation_node`.

    Slow by design: Columba's outbound PROPAGATED queue defers the
    first upload attempt by ~2 minutes (LXMRouter `defer_propagation_stamp`
    backoff), and resource transfer + sync poll add another minute.
    7-minute test budget. Skipped by default — opt in with
    `pytest -m slow` once lxmd is healthy."""
    if request.config.getoption("--no-prop"):
        pytest.skip("--no-prop: propagation tests disabled")

    content = f"columba_to_sideband_prop_{int(time.time() * 1000)}"
    interop.columba.send_text(interop.sideband_hex, content, method="PROPAGATED")

    received = []

    def _check():
        try:
            msg = interop.sideband.wait_for_message(
                from_hex=interop.columba_hex,
                content_predicate=lambda m: m.content_text == content,
                timeout=2,  # short — _retry_sync_until owns the outer deadline
            )
            received.append(msg)
            return True
        except AssertionError:
            return False

    assert _retry_sync_until(
        _check,
        interop.sideband.sync_propagation_messages,
        timeout=270,
        retry_every=20,
    ), f"Sideband never received propagated content {content!r}"
    assert received[0].content_text == content


@pytest.mark.slow
@pytest.mark.timeout(420)
def test_text_propagated_sideband_to_columba(interop, request):
    """B → A via PROPAGATED. Sideband uploads to lxmd; Columba fetches
    via its `SYNC_PROP` TestReceiver action. Skipped by default — opt
    in with `pytest -m slow`."""
    if request.config.getoption("--no-prop"):
        pytest.skip("--no-prop: propagation tests disabled")

    content = f"sideband_to_columba_prop_{int(time.time() * 1000)}"
    assert interop.sideband.send_text(
        interop.columba_hex, content, propagation=True
    )

    received = []

    def _check():
        try:
            msg = interop.columba.wait_for_message(
                from_hex=interop.sideband_hex,
                content_predicate=lambda m: m.content == content,
                timeout=2,
            )
            received.append(msg)
            return True
        except AssertionError:
            return False

    def _sync():
        interop.columba.broadcast("SYNC_PROP")

    assert _retry_sync_until(
        _check, _sync, timeout=270, retry_every=20,
    ), f"Columba never received propagated content {content!r}"
    assert received[0].content == content
