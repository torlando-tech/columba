"""Columba ↔ MeshChatX reply round-trip — wire-format interop.

Both apps now share the same wire format for replies (MeshChatX
upstream; Columba adopted it in the v2 reply migration):

  - fields[0x30] = bytes.fromhex(reply_to_hash)  # 32-byte target hash
  - fields[0x31] = reply_quoted_content.encode("utf-8")  # inline quote

Tests skip when `MESHCHATX_SRC` env var is unset (no MeshChatX runtime
available). See `peer_meshchatx.py` for the subprocess driver.

The MeshChatX→Columba direction exercises Columba's *receive* path,
which is the more interesting interop boundary (this is what GitHub
issue #926 is asking us to fix). The Columba→MeshChatX direction is
gated on a `SEND_REPLY` TestReceiver action that isn't on the debug
surface yet — those tests skip until it lands.
"""

from __future__ import annotations

import time

import pytest


def _columba_has_send_reply(columba_peer) -> bool:
    """Probe-by-broadcast to see if TestReceiver knows SEND_REPLY.

    There's no introspection endpoint on TestReceiver, so we send a
    no-op probe broadcast and watch for `rx_broadcast action=…`
    confirmation in logcat. If the receiver logs `rx_broadcast` with
    the SEND_REPLY action, it's wired; if we get nothing, the action
    is unknown and the dispatch fell through.
    """
    try:
        columba_peer.clear_logcat()
        columba_peer.broadcast(
            "SEND_REPLY",
            to="00" * 16,
            text="",
            reply_to_hash="",
            reply_quoted_content="",
        )
        time.sleep(1.5)
        for line in columba_peer._read_logcat_lines():
            # We don't care about success — only that the receiver
            # acknowledged the action (i.e. it's in the `when` branch).
            if "rx_broadcast action=network.columba.test.SEND_REPLY" in line:
                # A second confirmation: the dispatch must produce
                # *some* downstream log (msg_sent, error, etc.) within
                # the wait window. If we only see the rx_broadcast and
                # nothing else, the action is on the surface but its
                # handler isn't (treat as not-yet-wired).
                return any(
                    keyword in candidate
                    for candidate in columba_peer._read_logcat_lines()
                    for keyword in ("msg_sent", "reply_send_err")
                )
        return False
    except Exception:  # noqa: BLE001
        return False


@pytest.mark.timeout(120)
def test_reply_meshchatx_to_columba_wire_format(meshchatx_interop):
    """MeshChatX sends a reply with canonical 0x30/0x31 → Columba's
    inbound TestController records the receipt.

    This test verifies the *Columba receive path* for MeshChatX-format
    replies. Columba's `parseReplyToFromFields` and
    `parseReplyQuoteFromFields` decode the fields downstream of this;
    the interop check here is that the bytes arrive intact and aren't
    silently dropped by the lxmf-kt / chaquopy bridge.
    """
    pair = meshchatx_interop

    # Step 1 — anchor message Columba → MeshChatX. We need MeshChatX
    # to have an in-conversation message-hash to reply *to*; sending
    # the anchor from Columba ensures MeshChatX's DB has it.
    anchor_content = f"col_anchor_{int(time.time() * 1000)}"
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

    # Step 2 — MeshChatX sends a reply targeting that anchor hash.
    # `send_reply` puts canonical 0x30 + 0x31 on the wire per
    # `meshchat.py:16697-16699`.
    reply_content = f"mcx_reply_{int(time.time() * 1000)}"
    pair.columba.clear_logcat()
    pair.meshchatx.send_reply(
        dest_hex=pair.columba_hex,
        content=reply_content,
        reply_to_hash=anchor_hash,
        reply_quoted_content=anchor_content,
    )

    # Step 3 — Columba records the inbound reply as a normal rx_msg.
    # The content payload made it; the reply-specific fields are
    # carried in fieldsJson and read by `parseReplyToFromFields` /
    # `parseReplyQuoteFromFields` downstream in MessageMapper. We
    # don't (yet) surface those parsed fields on TestController, so
    # this assertion is content-only — extend once the rx_msg log
    # line carries `reply_to=<hex>`.
    msg = pair.columba.wait_for_message(
        from_hex=pair.meshchatx_hex,
        content_predicate=lambda m: m.content == reply_content,
        timeout=60,
    )
    assert msg.content == reply_content
    assert msg.source_hex == pair.meshchatx_hex


@pytest.mark.timeout(120)
def test_reply_columba_to_meshchatx_wire_format(meshchatx_interop):
    """Columba sends a reply → MeshChatX decodes canonical 0x30/0x31.

    Gated on the `SEND_REPLY` TestReceiver action; skips until that
    action lands on the Columba debug surface.
    """
    pair = meshchatx_interop

    if not _columba_has_send_reply(pair.columba):
        pytest.skip(
            "Columba TestReceiver does not expose SEND_REPLY yet. "
            "Wire it via `network.columba.test.SEND_REPLY` to enable."
        )

    anchor_content = f"mcx_anchor_{int(time.time() * 1000)}"
    anchor_send = pair.meshchatx.send_text(pair.columba_hex, anchor_content)
    anchor_hash = anchor_send["lxmf_message"]["hash"]

    pair.columba.wait_for_message(
        from_hex=pair.meshchatx_hex,
        content_predicate=lambda m: m.content == anchor_content,
        timeout=60,
    )

    reply_content = f"col_reply_{int(time.time() * 1000)}"
    pair.columba.broadcast(
        "SEND_REPLY",
        to=pair.meshchatx_hex,
        text=reply_content,
        reply_to_hash=anchor_hash,
        reply_quoted_content=anchor_content,
    )

    msg = pair.meshchatx.wait_for_message(
        from_hex=pair.columba_hex,
        predicate=lambda m: m.content == reply_content,
        timeout=60,
    )

    # MeshChatX surfaces decoded reply info under `fields.reply_to_hash`
    # + `fields.reply_quoted_content` in its conversation endpoint.
    reply_to = msg.fields.get("reply_to_hash") or msg.fields.get("reply_to")
    assert reply_to, (
        "MeshChatX received the message but did not surface reply data. "
        "The 0x30 field is missing or malformed on the wire."
    )
    assert str(reply_to).lower() == anchor_hash.lower()
    quote = msg.fields.get("reply_quoted_content")
    if quote is not None:
        assert quote == anchor_content
