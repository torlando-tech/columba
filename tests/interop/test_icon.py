"""`FIELD_ICON_APPEARANCE` (0x04) round-trip.

The triple `[icon_name, fg_rgb_bytes, bg_rgb_bytes]` is what Sideband
and Columba both attach for sender chrome. Tests assert byte-equality
on the wire.

Sideband normally attaches FIELD_ICON_APPEARANCE via `get_message_fields()`
based on the user's appearance config, which has side-effects on the
telemetry pipeline. For deterministic interop testing we bypass that
and construct the LXMessage directly via `send_with_fields()`.
"""

from __future__ import annotations

import time

import pytest

from verify import icon_appearance, FIELD_ICON_APPEARANCE


ICON_NAME = "person-circle"
FG = b"\xFF\xFF\xFF"  # white
BG = b"\x1E\x88\xE5"  # mid blue (matches Columba's default avatar palette)


@pytest.mark.timeout(90)
def test_icon_columba_to_sideband(interop):
    text = f"icon_from_columba_{int(time.time() * 1000)}"
    interop.columba.broadcast(
        "SEND_ICON",
        to=interop.sideband_hex,
        text=text,
        icon=ICON_NAME,
        fg=FG.hex(),
        bg=BG.hex(),
    )

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: (
            m.content_text == text and FIELD_ICON_APPEARANCE in m.fields
        ),
        timeout=60,
    )
    name, fg, bg = icon_appearance(msg.fields)
    assert name == ICON_NAME
    assert fg == FG
    assert bg == BG


@pytest.mark.timeout(90)
def test_icon_sideband_to_columba(interop):
    """Send a B→A message with the icon field explicit. Columba's
    rx_msg line surfaces content only — the icon appearance lives in
    Columba's local DB and is rendered into the chat UI, but isn't in
    the structured `rx_msg` log line. Wire arrival is confirmed by the
    rx_msg firing (LXMF drops messages with malformed fields before
    rx_msg fires)."""
    text = f"icon_from_sideband_{int(time.time() * 1000)}"
    fields = {
        FIELD_ICON_APPEARANCE: [
            ICON_NAME.encode("utf-8"),
            FG,
            BG,
        ],
    }
    assert interop.sideband.send_with_fields(
        interop.columba_hex, text, fields=fields,
    )

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == text,
        timeout=60,
    )
    assert msg.content == text
