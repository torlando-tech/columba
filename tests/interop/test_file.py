"""`FIELD_FILE_ATTACHMENTS` (0x05) round-trip across both directions."""

from __future__ import annotations

import time
from pathlib import Path

import pytest

from verify import file_attachments


FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="session")
def attach_bytes() -> bytes:
    return (FIXTURES / "attach.txt").read_bytes()


@pytest.mark.timeout(90)
def test_file_columba_to_sideband(interop, attach_bytes):
    text = f"file_from_columba_{int(time.time() * 1000)}"
    interop.columba.send_file(
        interop.sideband_hex,
        text=text,
        filename="attach.txt",
        data=attach_bytes,
    )

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: m.content_text == text,
        timeout=60,
    )
    attachments = file_attachments(msg.fields)
    assert len(attachments) == 1
    name, data = attachments[0]
    assert name == "attach.txt"
    assert data == attach_bytes


@pytest.mark.timeout(90)
def test_file_sideband_to_columba(interop, attach_bytes):
    text = f"file_from_sideband_{int(time.time() * 1000)}"
    assert interop.sideband.send_file(
        interop.columba_hex,
        content=text,
        filename="attach.txt",
        data=attach_bytes,
    )

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == text,
        timeout=60,
    )
    assert msg.content == text
