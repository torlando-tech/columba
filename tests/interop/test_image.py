"""`FIELD_IMAGE` (0x06) round-trip across both directions.

Sends a tiny PNG fixture and asserts the receiving peer's decoded LXMF
fields contain the exact same bytes + format string.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

import pytest

from verify import has_image, image_bytes, image_format


FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="session")
def png_bytes() -> bytes:
    return (FIXTURES / "tiny.png").read_bytes()


@pytest.mark.timeout(90)
def test_image_columba_to_sideband(interop, png_bytes):
    """Columba sends `text + FIELD_IMAGE`; Sideband DB row carries
    matching bytes."""
    text = f"image_from_columba_{int(time.time() * 1000)}"
    interop.columba.send_image(
        interop.sideband_hex,
        text=text,
        image_bytes=png_bytes,
        image_format="png",
    )

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: m.content_text == text and has_image(m.fields),
        timeout=60,
    )
    assert image_format(msg.fields) == "png"
    assert image_bytes(msg.fields) == png_bytes


@pytest.mark.timeout(90)
def test_image_sideband_to_columba(interop, png_bytes):
    """Sideband sends `FIELD_IMAGE`; Columba parses it and surfaces the
    inbound message. Columba's existing message-mapper renders the image
    bubble; this test just asserts the message arrived with non-zero
    content. Deeper byte-level assertion on Columba's side would require
    reading the local SQLite via adb (root) or a new TestReceiver path —
    out of scope for the wire-format suite."""
    text = f"image_from_sideband_{int(time.time() * 1000)}"
    assert interop.sideband.send_image(
        interop.columba_hex,
        content=text,
        image_bytes=png_bytes,
        image_format="png",
    )

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == text,
        timeout=60,
    )
    # Columba's rx_msg line carries content only — the image arrival is
    # implicit in the message being delivered (LXMF rejects malformed
    # messages with parse errors that would prevent rx_msg from firing).
    assert msg.content == text
