"""`FIELD_AUDIO` (0x07) round-trip using standard Ogg/Opus audio.

The fixture is independently decodable Ogg/Opus. This suite verifies the
standard LXMF field shape and byte preservation; Android playback and
recording are exercised separately.
"""

from __future__ import annotations

import hashlib
import time
from pathlib import Path

import pytest

from verify import audio_payload
from peer_columba import ColumbaRxAudio


FIXTURES = Path(__file__).parent / "fixtures"
AM_OPUS_OGG = 0x10


@pytest.fixture(scope="session")
def audio_bytes_fixture() -> bytes:
    return (FIXTURES / "tone.opus").read_bytes()


def test_audio_fixture_is_ogg_opus(audio_bytes_fixture):
    assert audio_bytes_fixture.startswith(b"OggS")
    assert b"OpusHead" in audio_bytes_fixture[:256]


def test_columba_audio_log_parser():
    digest = "ab" * 32
    parsed = ColumbaRxAudio.parse(
        f"I/COLUMBA_TEST: rx_audio id=deadbeef mode=16 bytes=5171 sha256={digest}"
    )
    assert parsed is not None
    assert parsed.msg_id_hex == "deadbeef"
    assert parsed.mode == AM_OPUS_OGG
    assert parsed.byte_count == 5171
    assert parsed.sha256 == digest


@pytest.mark.timeout(90)
def test_audio_columba_to_sideband(interop, audio_bytes_fixture):
    text = f"audio_from_columba_{int(time.time() * 1000)}"
    interop.columba.send_audio(
        interop.sideband_hex,
        text=text,
        audio_bytes=audio_bytes_fixture,
        codec_tag=AM_OPUS_OGG,
    )

    msg = interop.sideband.wait_for_message(
        from_hex=interop.columba_hex,
        content_predicate=lambda m: m.content_text == text,
        timeout=60,
    )
    codec_tag, data = audio_payload(msg.fields)
    assert codec_tag == AM_OPUS_OGG
    assert data == audio_bytes_fixture


@pytest.mark.timeout(90)
def test_audio_sideband_to_columba(interop, audio_bytes_fixture):
    text = f"audio_from_sideband_{int(time.time() * 1000)}"
    assert interop.sideband.send_audio(
        interop.columba_hex,
        content=text,
        audio_bytes=audio_bytes_fixture,
        codec_tag=AM_OPUS_OGG,
    )

    msg = interop.columba.wait_for_message(
        from_hex=interop.sideband_hex,
        content_predicate=lambda m: m.content == text,
        timeout=60,
    )
    assert msg.content == text
    audio = interop.columba.wait_for_audio(msg.msg_id_hex, timeout=30)
    assert audio.mode == AM_OPUS_OGG
    assert audio.byte_count == len(audio_bytes_fixture)
    assert audio.sha256 == hashlib.sha256(audio_bytes_fixture).hexdigest()
