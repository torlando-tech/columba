"""`FIELD_TELEMETRY` (0x02) round-trip — canonical Telemeter format.

`FIELD_TELEMETRY` carries `umsgpack.packb({sid: sensor_pack(), ...})` —
the format Sideband's `Telemeter.from_packed` consumes. Columba writes
the same shape via the `event_bridge.pack_telemetry_location` helper.

Tests assert both:
  - Bytes survive the round trip (wire-format check).
  - The decoded location data matches what was sent (semantic check —
    only meaningful if both peers speak the same encoding, which after
    the Telemeter-format migration they do).
"""

from __future__ import annotations

import json
import struct
import time

import pytest

from verify import FIELD_TELEMETRY


def _decode_location_from_packed(packed_bytes: bytes) -> dict:
    """Reference-decode via Sideband's own `Telemeter.from_packed` so
    the test catches drift between what canonical Sideband decoders
    expect and what Columba's `TelemeterCodec` produces. Relies on
    `conftest.py`'s `sideband_src` fixture having put the Sideband
    checkout on `sys.path` already — no explicit path insertion here
    so the suite stays env-portable."""
    from sbapp.sideband.sense import Telemeter
    t = Telemeter.from_packed(packed_bytes)
    assert t is not None, "Telemeter.from_packed rejected the blob"
    readings = t.read_all()
    assert "location" in readings, f"no location in telemeter: {readings.keys()}"
    return readings["location"]


@pytest.mark.timeout(60)
def test_telemetry_columba_to_sideband(interop):
    """Columba's `SEND_LOCATION` now packs the JSON via
    `event_bridge.pack_telemetry_location` into Telemeter wire format.
    Sideband's strict `Telemeter.from_packed` parser MUST accept the
    bytes — that's the whole point of the interop fix."""
    payload = json.dumps({
        "lat": 37.7749,
        "lon": -122.4194,
        "alt": 16.0,
        "speed": 0.0,
        "bearing": 0.0,
        "accuracy": 5.0,
        "label": "columba-to-sideband-interop",
    })
    interop.columba.send_location(interop.sideband_hex, payload)

    lxm = interop.sideband.wait_for_tapped_message(
        from_hex=interop.columba_hex,
        field_id=FIELD_TELEMETRY,
        timeout=45,
    )
    # Reference-decode via Sideband's own Telemeter — proves the bytes
    # are canonical Telemeter format, not Columba-specific JSON.
    decoded = _decode_location_from_packed(bytes(lxm.fields[FIELD_TELEMETRY]))
    assert abs(decoded["latitude"] - 37.7749) < 1e-5
    assert abs(decoded["longitude"] - -122.4194) < 1e-5
    assert abs(decoded["altitude"] - 16.0) < 0.01
    assert abs(decoded["accuracy"] - 5.0) < 0.01


@pytest.mark.timeout(180)
def test_telemetry_sideband_to_columba(interop):
    """Sideband sends a canonical Telemeter-packed `FIELD_TELEMETRY`.
    Columba decodes it via `event_bridge.unpack_telemetry_location` and
    surfaces a JSON location dict on `locationTelemetryFlow` →
    `rx_location source=stream json=…` line in logcat.

    Slower than other tests (~60-90s budget): telemetry-only messages
    go via DIRECT when no ratchet is cached, and the first link
    establishment from Sideband to Columba in a fresh session takes
    20-60s. Other tests that run before this one don't necessarily
    establish a ratchet in this direction."""
    assert interop.sideband.send_location_telemetry(
        interop.columba_hex,
        lat=51.5074,
        lon=-0.1278,
        alt=11.0,
        speed=0.0,
        bearing=42.0,
        accuracy=3.5,
    )

    received = interop.columba.wait_for_location(
        from_hex=interop.sideband_hex,
        timeout=150,
    )
    # Columba's Telemeter-decoded JSON uses the Columba schema (`lng`/
    # `acc`/`altitude` — NOT Telemeter's `lon`/`accuracy`/`alt`) because
    # `event_bridge._assemble_location_telemetry_json` translates field
    # names at the boundary so `LocationSharingManager` keeps consuming
    # the JSON shape it already expects. Tolerance accounts for the
    # fixed-point quantization in Telemeter's `struct.pack(int(lat*1e6))`.
    assert abs(received["lat"] - 51.5074) < 1e-5
    assert abs(received["lng"] - -0.1278) < 1e-5
    assert abs(received["altitude"] - 11.0) < 0.01
    assert abs(received["bearing"] - 42.0) < 0.01
    assert abs(received["acc"] - 3.5) < 0.01
    # source_hash must be injected from the LXMessage envelope.
    assert received["source_hash"].lower() == interop.sideband_hex.lower()
