"""`FIELD_AUDIO` (0x07) round-trip — codec_tag + bytes pair.

The audio fixture isn't real audio — `FIELD_AUDIO` interop is a
wire-format test only. Real audio decoding lives in `LXST` /
`Codec2`/`Opus`, exercised separately.
"""

from __future__ import annotations

import time
from pathlib import Path

import pytest

from verify import audio_payload


FIXTURES = Path(__file__).parent / "fixtures"
CODEC_OPUS_TAG = 0x01  # Sideband uses 1 for opus, 0 for codec2 — value
                       # is opaque to interop; we just round-trip it.


@pytest.fixture(scope="session")
def audio_bytes_fixture() -> bytes:
    return (FIXTURES / "tone.opus").read_bytes()


@pytest.mark.timeout(90)
def test_audio_columba_to_sideband(interop, audio_bytes_fixture):
    text = f"audio_from_columba_{int(time.time() * 1000)}"
    interop.columba.send_audio(
        interop.sideband_hex,
        text=text,
        audio_bytes=audio_bytes_fixture,
        codec_tag=CODEC_OPUS_TAG,
    )

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: m.content_text == text,
        timeout=60,
    )
    codec_tag, data = audio_payload(msg.fields)
    assert codec_tag == CODEC_OPUS_TAG
    assert data == audio_bytes_fixture


@pytest.mark.timeout(90)
def test_audio_sideband_to_columba(interop, audio_bytes_fixture):
    text = f"audio_from_sideband_{int(time.time() * 1000)}"
    assert interop.sideband.send_audio(
        interop.columba_hex,
        content=text,
        audio_bytes=audio_bytes_fixture,
        codec_tag=CODEC_OPUS_TAG,
    )

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == text,
        timeout=60,
    )
    assert msg.content == text
