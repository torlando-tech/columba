"""Columba ↔ MeshChatX reaction round-trip — wire-format interop.

Both apps share the per-event reaction wire format:

  fields[0x10] = {"reaction_to": <hex>, "emoji": str, "sender": <hex>}

Reference: `meshchat.py:16804-16808` (`send_reaction`).

Tests skip when `MESHCHATX_SRC` env var is unset.
"""

from __future__ import annotations

import time

import pytest


def _columba_has_send_reaction(columba_peer) -> bool:
    """Probe for a SEND_REACTION TestReceiver action — see
    `_columba_has_send_reply` in `test_reply_meshchatx.py` for the
    pattern."""
    try:
        columba_peer.clear_logcat()
        columba_peer.broadcast(
            "SEND_REACTION",
            to="00" * 16,
            target="00" * 16,
            emoji="👍",
        )
        time.sleep(1.5)
        for line in columba_peer._read_logcat_lines():
            if "rx_broadcast action=network.columba.test.SEND_REACTION" in line:
                return True
        return False
    except Exception:  # noqa: BLE001
        return False


@pytest.mark.timeout(120)
def test_reaction_meshchatx_to_columba_wire_format(meshchatx_interop):
    """MeshChatX sends an emoji reaction → Columba's inbound path
    routes it through `reactionReceivedFlow` and `handleIncomingReaction`
    persists the aggregation into the v2 `reactionsJson` column.

    We can't peek at SQLite from the harness easily, but TestController
    re-emits the inbound LXMessage on its rx_msg surface BEFORE the
    backend's reaction-router filter removes it — when a reaction
    message arrives, the rx_msg log line shows `content=` empty and
    the fields blob carries `0x10 = {reaction_to, emoji, sender}`.
    """
    pair = meshchatx_interop

    # Step 1 — anchor message Columba → MeshChatX so MeshChatX has a
    # target message hash. MeshChatX's reactions endpoint needs the
    # target hash; using a Columba-originated anchor keeps hash
    # consistent across both sides.
    anchor_content = f"col_react_anchor_{int(time.time() * 1000)}"
    anchor_send = pair.columba.send_text(
        pair.meshchatx_hex,
        anchor_content,
        method="OPPORTUNISTIC",
    )
    anchor_hash = anchor_send.msg_id_hex

    pair.meshchatx.wait_for_message(
        from_hex=pair.columba_hex,
        predicate=lambda m: m.content == anchor_content,
        timeout=60,
    )

    # Step 2 — MeshChatX reacts.
    pair.columba.clear_logcat()
    pair.meshchatx.send_reaction(
        dest_hex=pair.columba_hex,
        target_message_hash=anchor_hash,
        emoji="👍",
    )

    # Step 3 — Columba surfaces the inbound reaction LXMessage on
    # rx_msg. Reaction messages are otherwise content-empty, so we
    # filter by source rather than content here.
    msg = pair.columba.wait_for_message(
        from_hex=pair.meshchatx_hex,
        content_predicate=lambda m: True,
        timeout=60,
    )
    assert msg.source_hex == pair.meshchatx_hex
    # Reactions carry empty body content + the wire fields payload;
    # the harness doesn't (yet) decode fields off the rx_msg line, so
    # this is just a "did it arrive?" smoke check. The real assertion
    # — that handleIncomingReaction wrote to the new reactionsJson
    # column — needs an SQL probe.
    assert msg.content == "" or anchor_hash[:8] in msg.content


@pytest.mark.timeout(120)
def test_reaction_columba_to_meshchatx_wire_format(meshchatx_interop):
    """Columba sends an emoji reaction → MeshChatX decodes it.

    Gated on the `SEND_REACTION` TestReceiver action; skips until
    that action lands on the Columba debug surface.
    """
    pair = meshchatx_interop

    if not _columba_has_send_reaction(pair.columba):
        pytest.skip(
            "Columba TestReceiver does not expose SEND_REACTION yet. "
            "Wire it via `network.columba.test.SEND_REACTION` to enable."
        )

    anchor_content = f"mcx_react_anchor_{int(time.time() * 1000)}"
    anchor_send = pair.meshchatx.send_text(pair.columba_hex, anchor_content)
    anchor_hash = anchor_send["lxmf_message"]["hash"]

    pair.columba.wait_for_message(
        from_hex=pair.meshchatx_hex,
        content_predicate=lambda m: m.content == anchor_content,
        timeout=60,
    )

    pair.columba.broadcast(
        "SEND_REACTION",
        to=pair.meshchatx_hex,
        target=anchor_hash,
        emoji="❤️",
    )

    # MeshChatX records the reaction against the anchor; the
    # conversation endpoint returns the *reaction message* (empty
    # content, fields[0x10] carrying the reaction blob).
    msg = pair.meshchatx.wait_for_message(
        from_hex=pair.columba_hex,
        predicate=lambda m: m.fields.get("app_extensions") is not None
        or m.fields.get("reaction") is not None,
        timeout=60,
    )

    extensions = msg.fields.get("app_extensions") or msg.fields.get("reaction") or {}
    assert extensions.get("reaction_to", "").lower() == anchor_hash.lower()
    assert extensions.get("emoji") == "❤️"
    assert extensions.get("sender", "").lower() == pair.columba_hex.lower()
