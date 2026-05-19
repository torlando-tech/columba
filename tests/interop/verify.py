"""Shared helpers for cross-peer message verification.

Test files import the predicates / assertions here instead of repeating
the field-decoding boilerplate. `FIELD_*` constants mirror upstream
LXMF — re-asserted as local consts so the test code reads obviously
without an `import LXMF` everywhere.
"""

from __future__ import annotations

from typing import Iterable

# Mirrors LXMF.FIELD_* (upstream `LXMF/__init__.py`). Asserted-rather-than-
# imported on purpose: keeps tests parseable even if the LXMF wheel is
# absent from a CI image that just runs static checks.
FIELD_EMBEDDED_LXMS = 0x01
FIELD_TELEMETRY = 0x02
FIELD_TELEMETRY_STREAM = 0x03
FIELD_ICON_APPEARANCE = 0x04
FIELD_FILE_ATTACHMENTS = 0x05
FIELD_IMAGE = 0x06
FIELD_AUDIO = 0x07
FIELD_THREAD = 0x08
FIELD_COMMANDS = 0x09
FIELD_RESULTS = 0x0A
FIELD_GROUP = 0x0B
FIELD_TICKET = 0x0C
FIELD_EVENT = 0x0D
FIELD_RNR_REFS = 0x0E
FIELD_RENDERER = 0x0F


def has_image(fields: dict) -> bool:
    return FIELD_IMAGE in fields and fields[FIELD_IMAGE] is not None


def image_bytes(fields: dict) -> bytes:
    """`FIELD_IMAGE` is `[format_str, bytes]`."""
    val = fields[FIELD_IMAGE]
    assert isinstance(val, (list, tuple)) and len(val) == 2, (
        f"FIELD_IMAGE shape mismatch: {type(val)} len={len(val) if hasattr(val, '__len__') else '?'}"
    )
    return val[1] if isinstance(val[1], (bytes, bytearray)) else bytes(val[1])


def image_format(fields: dict) -> str:
    val = fields[FIELD_IMAGE]
    fmt = val[0]
    return fmt.decode("utf-8") if isinstance(fmt, (bytes, bytearray)) else fmt


def file_attachments(fields: dict) -> list[tuple[bytes, bytes]]:
    """`FIELD_FILE_ATTACHMENTS` is `[[name_bytes, data_bytes], …]`. Coerce
    both elements to `bytes` so test asserts don't have to care about
    Chaquopy jarray vs python bytes."""
    raw = fields[FIELD_FILE_ATTACHMENTS]
    out = []
    for entry in raw:
        if isinstance(entry, (list, tuple)) and len(entry) >= 2:
            n, d = entry[0], entry[1]
            out.append((bytes(n), bytes(d)))
    return out


def audio_payload(fields: dict) -> tuple[int, bytes]:
    val = fields[FIELD_AUDIO]
    return int(val[0]), bytes(val[1])


def icon_appearance(fields: dict) -> tuple[str, bytes, bytes]:
    """`FIELD_ICON_APPEARANCE` is `[name_str, fg_rgb_bytes, bg_rgb_bytes]`."""
    val = fields[FIELD_ICON_APPEARANCE]
    name = val[0]
    if isinstance(name, (bytes, bytearray)):
        name = name.decode("utf-8")
    return name, bytes(val[1]), bytes(val[2])


def telemetry_blob(fields: dict) -> bytes:
    """`FIELD_TELEMETRY` carries opaque bytes — but apps that wrote it
    sometimes pass through a `str` (Columba's `sendLocationTelemetry`
    hands the JSON in as a string and LXMF's msgpacker keeps it that
    way). Return bytes uniformly so tests assert against a normalized
    shape."""
    val = fields[FIELD_TELEMETRY]
    if isinstance(val, (bytes, bytearray)):
        return bytes(val)
    if isinstance(val, str):
        return val.encode("utf-8")
    # Defensive: anything else, repr to bytes so tests fail loudly
    # rather than silently coerce.
    return bytes(repr(val), "utf-8")


def field_keys(fields: dict) -> Iterable[int]:
    return sorted(fields.keys())
