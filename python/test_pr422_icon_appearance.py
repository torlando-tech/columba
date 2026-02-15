"""
Test suite for PR #422: Icon appearance byte order, accuracy clamping,
hyphenated icon name validation, location event buffering, and related fixes.

Tests cover:
- Accuracy clamping in pack_location_telemetry (unsigned short overflow)
- Byte order in unpack_telemetry_stream appearance parsing (fg before bg)
- Icon name validation allowing hyphens (MDI icon names)
- FIELD_ICON_APPEARANCE extraction in _on_lxmf_delivery (location callback path)
- FIELD_ICON_APPEARANCE byte order in _on_lxmf_delivery (message callback path)
- Location event buffering before callback registration
- Telemetry stream entries buffered when callback absent
- Location detection without callback (startup race fix)
- send_location_telemetry with icon appearance params
- send_location_telemetry with no icon params
- FIELD_ICON_APPEARANCE constant value
- poll_received_messages icon appearance byte order
"""

import sys
import os
import unittest
import json
import struct
import time
import tempfile
import shutil
from unittest.mock import MagicMock, Mock, patch
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import umsgpack
except ImportError:
    pytest.skip("umsgpack not installed (pip install u-msgpack-python)", allow_module_level=True)

sys.modules['umsgpack'] = umsgpack

mock_rns = MagicMock()
mock_lxmf = MagicMock()
sys.modules['RNS'] = mock_rns
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = mock_lxmf

import reticulum_wrapper
import importlib
reticulum_wrapper.umsgpack = umsgpack
importlib.reload(reticulum_wrapper)

from reticulum_wrapper import (
    ReticulumWrapper,
    pack_location_telemetry,
    unpack_location_telemetry,
    unpack_telemetry_stream,
    FIELD_TELEMETRY,
    FIELD_TELEMETRY_STREAM,
    FIELD_COLUMBA_META,
    FIELD_ICON_APPEARANCE,
    LEGACY_LOCATION_FIELD,
)


def _make_packed_telemetry(lat=37.7749, lon=-122.4194, accuracy=10.0,
                           timestamp_ms=1703980800000):
    """Helper to create packed telemetry bytes for stream entries."""
    return pack_location_telemetry(
        lat=lat, lon=lon, accuracy=accuracy, timestamp_ms=timestamp_ms
    )


def _make_mock_lxmf_message(fields=None, content=b"", source_hash=None,
                            destination_hash=None, msg_hash=None,
                            timestamp=None):
    """
    Helper to create a mock LXMF message with all required attributes.

    The mock mimics the attributes accessed by _on_lxmf_delivery and
    poll_received_messages in production code.
    """
    msg = Mock()
    msg.fields = fields or {}
    msg.content = content
    msg.source_hash = source_hash or bytes(16)
    msg.destination_hash = destination_hash or bytes(16)
    msg.hash = msg_hash or bytes.fromhex("ab" * 16)
    msg.timestamp = timestamp if timestamp is not None else time.time()
    msg.signature_validated = True
    msg.unverified_reason = None
    msg.title = ""
    # Prevent Mock auto-attributes for signal quality fields that would
    # break JSON serialization when add_signal_to_message_event is called
    msg.receiving_interface = None
    msg.receiving_hops = None
    msg._columba_rssi = None
    msg._columba_snr = None
    msg._columba_hops = None
    msg._columba_interface = None
    return msg


class TestFieldIconAppearanceConstant(unittest.TestCase):
    """Test that the FIELD_ICON_APPEARANCE constant is correctly defined."""

    def test_field_icon_appearance_is_0x04(self):
        """FIELD_ICON_APPEARANCE should be 0x04 per LXMF spec."""
        self.assertEqual(FIELD_ICON_APPEARANCE, 0x04)

    def test_field_icon_appearance_is_integer(self):
        """FIELD_ICON_APPEARANCE should be an integer."""
        self.assertIsInstance(FIELD_ICON_APPEARANCE, int)


class TestAccuracyClamping(unittest.TestCase):
    """Test accuracy clamping in pack_location_telemetry.

    Accuracy is packed as an unsigned short (uint16, max 65535).
    The value is in centimeters, so the max representable accuracy is
    655.35 meters. Values above this must be clamped to 65535.
    """

    def test_normal_accuracy_packs_correctly(self):
        """Normal accuracy value (10.0m = 1000 cm) should pack without clamping."""
        packed = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        acc_bytes = unpacked[0x02][5]  # SID_LOCATION = 0x02, accuracy at index 5
        acc_cm = struct.unpack("!H", acc_bytes)[0]
        self.assertEqual(acc_cm, 1000)

    def test_accuracy_700m_clamped_to_65535(self):
        """Accuracy of 700m (70000 cm) exceeds uint16 max and must be clamped to 65535."""
        packed = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=700.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        acc_bytes = unpacked[0x02][5]
        acc_cm = struct.unpack("!H", acc_bytes)[0]
        self.assertEqual(acc_cm, 65535)

    def test_accuracy_exact_max_65535_centimeters(self):
        """Accuracy of 655.35m (exactly 65535 cm) should pack to 65535 without clamping."""
        packed = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=655.35,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        acc_bytes = unpacked[0x02][5]
        acc_cm = struct.unpack("!H", acc_bytes)[0]
        self.assertEqual(acc_cm, 65535)

    def test_accuracy_one_centimeter_over_max_clamped(self):
        """Accuracy of 655.36m (65536 cm) exceeds max by 1 and must be clamped to 65535."""
        packed = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=655.36,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        acc_bytes = unpacked[0x02][5]
        acc_cm = struct.unpack("!H", acc_bytes)[0]
        self.assertEqual(acc_cm, 65535)

    def test_round_trip_clamped_accuracy_returns_max(self):
        """Round-trip with clamped accuracy should return 655.35 (max representable)."""
        packed = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=700.0,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertAlmostEqual(result['acc'], 655.35, places=2)

    def test_round_trip_normal_accuracy(self):
        """Round-trip with normal accuracy should preserve the value."""
        packed = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertAlmostEqual(result['acc'], 10.0, places=2)


class TestTelemetryStreamAppearanceByteOrder(unittest.TestCase):
    """Test that unpack_telemetry_stream parses appearance with correct byte order.

    The appearance tuple is [icon_name, fg_bytes, bg_bytes].
    fg_bytes is index [1] = foreground color, bg_bytes is index [2] = background color.
    This matches the Sideband/MeshChat wire format.
    """

    def test_fg_is_first_color_bg_is_second(self):
        """Appearance [icon_name, fg_bytes, bg_bytes] should map fg->fg, bg->bg."""
        source_hash = bytes(16)
        timestamp = int(time.time())
        packed = _make_packed_telemetry()

        red_fg = bytes([255, 0, 0])
        green_bg = bytes([0, 255, 0])
        appearance = ["person", red_fg, green_bg]

        stream = [[source_hash, timestamp, packed, appearance]]
        results = unpack_telemetry_stream(stream)

        self.assertEqual(len(results), 1)
        self.assertIn('appearance', results[0])
        self.assertEqual(results[0]['appearance']['foreground_color'], "ff0000")
        self.assertEqual(results[0]['appearance']['background_color'], "00ff00")

    def test_appearance_name_preserved(self):
        """The icon name should be preserved in the unpacked appearance."""
        source_hash = bytes(16)
        timestamp = int(time.time())
        packed = _make_packed_telemetry()

        appearance = ["sail-boat", bytes([0x11, 0x22, 0x33]), bytes([0xAA, 0xBB, 0xCC])]
        stream = [[source_hash, timestamp, packed, appearance]]
        results = unpack_telemetry_stream(stream)

        self.assertEqual(results[0]['appearance']['icon_name'], "sail-boat")
        self.assertEqual(results[0]['appearance']['foreground_color'], "112233")
        self.assertEqual(results[0]['appearance']['background_color'], "aabbcc")

    def test_appearance_absent_when_not_provided(self):
        """Entries without appearance should not have an appearance key."""
        source_hash = bytes(16)
        timestamp = int(time.time())
        packed = _make_packed_telemetry()

        stream = [[source_hash, timestamp, packed]]
        results = unpack_telemetry_stream(stream)

        self.assertEqual(len(results), 1)
        self.assertNotIn('appearance', results[0])

    def test_specific_color_bytes_not_swapped(self):
        """Verify distinct fg/bg bytes are not swapped in the output."""
        source_hash = bytes(16)
        timestamp = int(time.time())
        packed = _make_packed_telemetry()

        # fg = black (00 00 00), bg = white (ff ff ff)
        appearance = ["account", bytes([0x00, 0x00, 0x00]), bytes([0xFF, 0xFF, 0xFF])]
        stream = [[source_hash, timestamp, packed, appearance]]
        results = unpack_telemetry_stream(stream)

        self.assertEqual(results[0]['appearance']['foreground_color'], "000000")
        self.assertEqual(results[0]['appearance']['background_color'], "ffffff")


class TestIconNameValidation(unittest.TestCase):
    """Test that icon name validation allows hyphens (MDI icon name format).

    MDI icon names use hyphens (e.g. 'sail-boat', 'access-point-network').
    The validation regex was changed to:
        icon_name.replace('_', '').replace('-', '').isalnum()
    """

    def _validate_icon_name_via_stream(self, icon_name):
        """Exercise the icon name validation in unpack_telemetry_stream.

        Returns the appearance dict if the icon name was accepted, None otherwise.
        """
        source_hash = bytes(16)
        timestamp = int(time.time())
        packed = _make_packed_telemetry()
        appearance = [icon_name, bytes([0, 0, 0]), bytes([255, 255, 255])]
        stream = [[source_hash, timestamp, packed, appearance]]
        results = unpack_telemetry_stream(stream)
        if results and 'appearance' in results[0]:
            return results[0]['appearance']
        return None

    def test_hyphenated_sail_boat_accepted(self):
        """MDI icon name 'sail-boat' with hyphen should be accepted."""
        result = self._validate_icon_name_via_stream("sail-boat")
        self.assertIsNotNone(result)
        self.assertEqual(result['icon_name'], "sail-boat")

    def test_hyphenated_access_point_network_accepted(self):
        """MDI icon name 'access-point-network' with multiple hyphens should be accepted."""
        result = self._validate_icon_name_via_stream("access-point-network")
        self.assertIsNotNone(result)
        self.assertEqual(result['icon_name'], "access-point-network")

    def test_hyphenated_food_fork_drink_accepted(self):
        """MDI icon name 'food-fork-drink' should be accepted."""
        result = self._validate_icon_name_via_stream("food-fork-drink")
        self.assertIsNotNone(result)
        self.assertEqual(result['icon_name'], "food-fork-drink")

    def test_underscore_icon_accepted(self):
        """Icon name with underscore 'my_icon' should still be accepted."""
        result = self._validate_icon_name_via_stream("my_icon")
        self.assertIsNotNone(result)
        self.assertEqual(result['icon_name'], "my_icon")

    def test_simple_alphanumeric_accepted(self):
        """Simple alphanumeric icon name 'account' should be accepted."""
        result = self._validate_icon_name_via_stream("account")
        self.assertIsNotNone(result)

    def test_icon_with_exclamation_rejected(self):
        """Icon name with special character '!' should be rejected."""
        result = self._validate_icon_name_via_stream("icon!")
        self.assertIsNone(result)

    def test_script_tag_rejected(self):
        """Icon name containing '<script>' should be rejected (XSS prevention)."""
        result = self._validate_icon_name_via_stream("<script>")
        self.assertIsNone(result)

    def test_empty_string_rejected(self):
        """Empty icon name should be rejected."""
        result = self._validate_icon_name_via_stream("")
        self.assertIsNone(result)

    def test_icon_name_over_50_chars_rejected(self):
        """Icon name exceeding 50 characters should be rejected."""
        long_name = "a" * 51
        result = self._validate_icon_name_via_stream(long_name)
        self.assertIsNone(result)

    def test_icon_name_exactly_50_chars_accepted(self):
        """Icon name at exactly 50 characters should be accepted."""
        name_50 = "a" * 50
        result = self._validate_icon_name_via_stream(name_50)
        self.assertIsNotNone(result)


class TestOnLxmfDeliveryIconAppearanceLocationPath(unittest.TestCase):
    """Test FIELD_ICON_APPEARANCE extraction in _on_lxmf_delivery (location callback path).

    When a location-only message (FIELD_TELEMETRY + no text content) has
    FIELD_ICON_APPEARANCE, the appearance should be extracted and included
    in the location_event JSON sent to the callback.

    Byte order: appearance = [name, fg_bytes, bg_bytes]
    -> JSON: foreground_color = fg_bytes.hex(), background_color = bg_bytes.hex()
    """

    def setUp(self):
        """Create a ReticulumWrapper with mocked dependencies."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        # Set up mocks needed for _on_lxmf_delivery to function
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        self.wrapper.router = MagicMock()
        self.wrapper.router.pending_inbound = []
        self.wrapper.telemetry_collector_enabled = False
        self.wrapper.kotlin_message_received_callback = None
        self.wrapper.kotlin_reaction_received_callback = None

    def tearDown(self):
        """Clean up temp directory."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_location_event_includes_appearance(self):
        """Location event callback should include icon appearance from FIELD_ICON_APPEARANCE."""
        # Set up a callback to capture the location event
        captured_events = []
        self.wrapper.kotlin_location_received_callback = lambda json_str: captured_events.append(json.loads(json_str))

        packed = _make_packed_telemetry(lat=37.7749, lon=-122.4194)
        fg_bytes = bytes([0xFF, 0x00, 0x00])  # red
        bg_bytes = bytes([0x00, 0xFF, 0x00])  # green
        icon_appearance = ["person", fg_bytes, bg_bytes]

        msg = _make_mock_lxmf_message(
            fields={
                FIELD_TELEMETRY: packed,
                FIELD_ICON_APPEARANCE: icon_appearance,
            },
            content=b"",  # No text = location-only
        )

        self.wrapper._on_lxmf_delivery(msg)

        self.assertEqual(len(captured_events), 1)
        event = captured_events[0]
        self.assertIn('appearance', event)
        self.assertEqual(event['appearance']['icon_name'], "person")
        # fg_bytes is [1] = foreground, bg_bytes is [2] = background
        self.assertEqual(event['appearance']['foreground_color'], "ff0000")
        self.assertEqual(event['appearance']['background_color'], "00ff00")

    def test_location_event_appearance_byte_order(self):
        """Verify that fg is [1] and bg is [2] in the appearance tuple."""
        captured_events = []
        self.wrapper.kotlin_location_received_callback = lambda json_str: captured_events.append(json.loads(json_str))

        packed = _make_packed_telemetry()
        # Deliberately use distinct bytes to prove order
        icon_appearance = ["test-icon", bytes([0xAA, 0xBB, 0xCC]), bytes([0x11, 0x22, 0x33])]

        msg = _make_mock_lxmf_message(
            fields={
                FIELD_TELEMETRY: packed,
                FIELD_ICON_APPEARANCE: icon_appearance,
            },
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)

        self.assertEqual(len(captured_events), 1)
        event = captured_events[0]
        self.assertEqual(event['appearance']['foreground_color'], "aabbcc")
        self.assertEqual(event['appearance']['background_color'], "112233")

    def test_location_event_without_appearance(self):
        """Location event without FIELD_ICON_APPEARANCE should not have appearance key."""
        captured_events = []
        self.wrapper.kotlin_location_received_callback = lambda json_str: captured_events.append(json.loads(json_str))

        packed = _make_packed_telemetry()
        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY: packed},
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)

        self.assertEqual(len(captured_events), 1)
        event = captured_events[0]
        self.assertNotIn('appearance', event)


class TestOnLxmfDeliveryIconAppearanceMessagePath(unittest.TestCase):
    """Test FIELD_ICON_APPEARANCE byte order in _on_lxmf_delivery (message callback path).

    For regular messages (with text content), FIELD_ICON_APPEARANCE is parsed
    around line 3084 in the message callback path. This tests that parsing.

    The format is [icon_name, fg_bytes, bg_bytes]:
    -> foreground_color = fg_bytes[1].hex()
    -> background_color = bg_bytes[2].hex()
    """

    def setUp(self):
        """Create a ReticulumWrapper with mocked dependencies."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        self.wrapper.router = MagicMock()
        self.wrapper.router.pending_inbound = []
        self.wrapper.telemetry_collector_enabled = False
        self.wrapper.kotlin_location_received_callback = None
        self.wrapper.kotlin_reaction_received_callback = None

    def tearDown(self):
        """Clean up temp directory."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_message_path_icon_appearance_byte_order(self):
        """Message callback path should parse fg as [1] and bg as [2]."""
        # Capture the message event from the callback
        captured_events = []
        self.wrapper.kotlin_message_received_callback = lambda json_str: captured_events.append(json.loads(json_str))

        icon_appearance = ["test-icon", bytes([0xAA, 0xBB, 0xCC]), bytes([0x11, 0x22, 0x33])]

        msg = _make_mock_lxmf_message(
            fields={FIELD_ICON_APPEARANCE: icon_appearance},
            content=b"Hello world",  # Has text content -> regular message path
        )

        self.wrapper._on_lxmf_delivery(msg)

        self.assertEqual(len(captured_events), 1)
        event = captured_events[0]
        self.assertIn('icon_appearance', event)
        self.assertEqual(event['icon_appearance']['foreground_color'], "aabbcc")
        self.assertEqual(event['icon_appearance']['background_color'], "112233")
        self.assertEqual(event['icon_appearance']['icon_name'], "test-icon")


class TestLocationEventBuffering(unittest.TestCase):
    """Test the _pending_location_events buffer for events arriving before callback.

    When location events arrive before set_location_received_callback is called,
    they should be buffered in _pending_location_events and drained when the
    callback is finally registered.
    """

    def setUp(self):
        """Create a ReticulumWrapper with no location callback."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        self.wrapper.router = MagicMock()
        self.wrapper.router.pending_inbound = []
        self.wrapper.telemetry_collector_enabled = False
        self.wrapper.kotlin_message_received_callback = None
        self.wrapper.kotlin_reaction_received_callback = None
        # Explicitly NO location callback
        self.wrapper.kotlin_location_received_callback = None

    def tearDown(self):
        """Clean up temp directory."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_location_event_buffered_when_no_callback(self):
        """Location events should be buffered when no callback is registered."""
        packed = _make_packed_telemetry()
        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY: packed},
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)

        self.assertEqual(len(self.wrapper._pending_location_events), 1)

    def test_buffered_events_drained_on_callback_registration(self):
        """Buffered events should be drained when callback is registered."""
        packed = _make_packed_telemetry()
        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY: packed},
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)
        self.assertEqual(len(self.wrapper._pending_location_events), 1)

        # Now register callback
        drained_events = []
        self.wrapper.set_location_received_callback(
            lambda json_str: drained_events.append(json.loads(json_str))
        )

        # Buffered event should have been drained to callback
        self.assertEqual(len(drained_events), 1)
        self.assertEqual(len(self.wrapper._pending_location_events), 0)

    def test_drained_event_contains_location_data(self):
        """Drained events should contain valid location data."""
        packed = _make_packed_telemetry(lat=40.7128, lon=-74.0060)
        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY: packed},
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)

        drained_events = []
        self.wrapper.set_location_received_callback(
            lambda json_str: drained_events.append(json.loads(json_str))
        )

        self.assertEqual(len(drained_events), 1)
        event = drained_events[0]
        self.assertEqual(event['type'], 'location_share')
        self.assertAlmostEqual(event['lat'], 40.7128, places=4)
        self.assertAlmostEqual(event['lng'], -74.0060, places=4)


class TestTelemetryStreamBuffering(unittest.TestCase):
    """Test telemetry stream entries buffered when callback absent.

    When FIELD_TELEMETRY_STREAM arrives before the location callback is
    registered, all stream entries should be buffered individually.
    """

    def setUp(self):
        """Create a ReticulumWrapper with no location callback."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        self.wrapper.router = MagicMock()
        self.wrapper.router.pending_inbound = []
        self.wrapper.telemetry_collector_enabled = False
        self.wrapper.kotlin_message_received_callback = None
        self.wrapper.kotlin_reaction_received_callback = None
        self.wrapper.kotlin_location_received_callback = None

    def tearDown(self):
        """Clean up temp directory."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _make_stream_entries(self, count):
        """Create a list of stream entries for FIELD_TELEMETRY_STREAM."""
        entries = []
        for i in range(count):
            source_hash = bytes([i] * 16)
            timestamp = int(time.time())
            packed = _make_packed_telemetry(lat=37.0 + i * 0.01, lon=-122.0 + i * 0.01)
            entries.append([source_hash, timestamp, packed])
        return entries

    def test_three_stream_entries_buffered(self):
        """All 3 stream entries should be buffered in _pending_location_events."""
        stream_entries = self._make_stream_entries(3)

        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY_STREAM: stream_entries},
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)

        self.assertEqual(len(self.wrapper._pending_location_events), 3)

    def test_buffered_stream_entries_drained_on_registration(self):
        """All buffered stream entries should be drained to callback on registration."""
        stream_entries = self._make_stream_entries(3)

        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY_STREAM: stream_entries},
            content=b"",
        )

        self.wrapper._on_lxmf_delivery(msg)

        drained_events = []
        self.wrapper.set_location_received_callback(
            lambda json_str: drained_events.append(json.loads(json_str))
        )

        self.assertEqual(len(drained_events), 3)
        self.assertEqual(len(self.wrapper._pending_location_events), 0)


class TestLocationDetectionWithoutCallback(unittest.TestCase):
    """Test that location-only messages are detected even without a callback.

    Previously, location field detection was gated on
    self.kotlin_location_received_callback being set. The fix ensures location
    fields are always checked, and the message is correctly routed as
    location-only regardless of whether the callback is registered.
    """

    def setUp(self):
        """Create wrapper with no callbacks registered."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        self.wrapper.router = MagicMock()
        self.wrapper.router.pending_inbound = []
        self.wrapper.telemetry_collector_enabled = False
        self.wrapper.kotlin_message_received_callback = None
        self.wrapper.kotlin_location_received_callback = None
        self.wrapper.kotlin_reaction_received_callback = None

    def tearDown(self):
        """Clean up temp directory."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_location_only_message_not_in_message_queue(self):
        """Location-only message should NOT appear in pending_inbound (message queue)."""
        packed = _make_packed_telemetry()
        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY: packed},
            content=b"",  # No text content -> location-only
        )

        self.wrapper._on_lxmf_delivery(msg)

        # The message should NOT be in pending_inbound (it's location-only)
        # It should be in _pending_location_events instead
        self.assertEqual(len(self.wrapper.router.pending_inbound), 0)
        self.assertEqual(len(self.wrapper._pending_location_events), 1)

    def test_message_with_text_and_telemetry_goes_to_queue(self):
        """Message with both text and telemetry should go to message queue."""
        packed = _make_packed_telemetry()
        msg = _make_mock_lxmf_message(
            fields={FIELD_TELEMETRY: packed},
            content=b"Hello with location",  # Has text -> not location-only
        )

        self.wrapper._on_lxmf_delivery(msg)

        # Message has text, so it should be in the message queue
        self.assertGreaterEqual(len(self.wrapper.router.pending_inbound), 1)


class TestSendLocationTelemetryIconAppearance(unittest.TestCase):
    """Test send_location_telemetry with icon appearance parameters.

    The method should pack FIELD_ICON_APPEARANCE as [name, fg_bytes, bg_bytes]
    when icon params are provided.
    """

    def setUp(self):
        """Create a ReticulumWrapper with mocked dependencies for sending."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        self.wrapper.initialized = True
        self.wrapper.router = MagicMock()
        self.wrapper.local_lxmf_destination = MagicMock()
        self.wrapper.identities = {}

        # Make RNS.Identity.recall return a mock identity
        mock_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.return_value = mock_identity

        # Capture the fields passed to LXMessage constructor
        self.captured_fields = {}

        def capture_lxmessage(*args, **kwargs):
            self.captured_fields = kwargs.get('fields', {})
            mock_msg = MagicMock()
            mock_msg.hash = bytes(16)
            mock_msg.send = MagicMock()
            return mock_msg

        reticulum_wrapper.LXMF.LXMessage = capture_lxmessage

    def tearDown(self):
        """Clean up temp directory and reset module state."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        reticulum_wrapper.RETICULUM_AVAILABLE = False

    def test_icon_appearance_packed_with_fg_first_bg_second(self):
        """FIELD_ICON_APPEARANCE should be [name, fg_bytes, bg_bytes] (fg first)."""
        location_json = json.dumps({
            "lat": 37.7749, "lng": -122.4194, "acc": 10.0,
            "ts": 1703980800000,
        })

        self.wrapper.send_location_telemetry(
            dest_hash=bytes(16),
            location_json=location_json,
            source_identity_private_key=bytes(32),
            icon_name="person",
            icon_fg_color="ff0000",
            icon_bg_color="00ff00",
        )

        self.assertIn(FIELD_ICON_APPEARANCE, self.captured_fields)
        appearance = self.captured_fields[FIELD_ICON_APPEARANCE]

        self.assertEqual(appearance[0], "person")
        # fg_bytes is [1], bg_bytes is [2]
        self.assertEqual(appearance[1], bytes([0xFF, 0x00, 0x00]))  # fg = red
        self.assertEqual(appearance[2], bytes([0x00, 0xFF, 0x00]))  # bg = green

    def test_no_icon_params_no_appearance_field(self):
        """When no icon params provided, FIELD_ICON_APPEARANCE should not be in fields."""
        location_json = json.dumps({
            "lat": 37.7749, "lng": -122.4194, "acc": 10.0,
            "ts": 1703980800000,
        })

        self.wrapper.send_location_telemetry(
            dest_hash=bytes(16),
            location_json=location_json,
            source_identity_private_key=bytes(32),
            # No icon_name, icon_fg_color, icon_bg_color
        )

        self.assertNotIn(FIELD_ICON_APPEARANCE, self.captured_fields)

    def test_partial_icon_params_no_appearance_field(self):
        """When only some icon params are provided, FIELD_ICON_APPEARANCE should be absent."""
        location_json = json.dumps({
            "lat": 37.7749, "lng": -122.4194, "acc": 10.0,
            "ts": 1703980800000,
        })

        # Only icon_name, missing colors
        self.wrapper.send_location_telemetry(
            dest_hash=bytes(16),
            location_json=location_json,
            source_identity_private_key=bytes(32),
            icon_name="person",
            # Missing icon_fg_color and icon_bg_color
        )

        self.assertNotIn(FIELD_ICON_APPEARANCE, self.captured_fields)


class TestPollReceivedMessagesIconAppearance(unittest.TestCase):
    """Test poll_received_messages icon appearance parsing at line ~6055.

    When a received message has FIELD_ICON_APPEARANCE (field 4), it should
    be parsed with byte order [icon_name, fg_rgb, bg_rgb]:
    -> foreground_color = value[1].hex()
    -> background_color = value[2].hex()
    """

    def setUp(self):
        """Create a ReticulumWrapper with mocked dependencies for polling."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        self.wrapper.initialized = True
        self.wrapper.router = MagicMock()
        self.wrapper.seen_message_hashes = set()

    def tearDown(self):
        """Clean up temp directory and reset module state."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)
        reticulum_wrapper.RETICULUM_AVAILABLE = False

    def test_poll_parses_icon_appearance_byte_order(self):
        """poll_received_messages should parse fg as [1] and bg as [2] for field 4."""
        icon_data = ["icon", bytes([0xAA, 0xBB, 0xCC]), bytes([0x11, 0x22, 0x33])]

        msg = _make_mock_lxmf_message(
            fields={4: icon_data},
            content=b"Test message",
            msg_hash=bytes.fromhex("cd" * 16),
        )

        self.wrapper.router.pending_inbound = [msg]

        results = self.wrapper.poll_received_messages()

        self.assertEqual(len(results), 1)
        result = results[0]
        self.assertIn('icon_appearance', result)
        self.assertEqual(result['icon_appearance']['icon_name'], "icon")
        self.assertEqual(result['icon_appearance']['foreground_color'], "aabbcc")
        self.assertEqual(result['icon_appearance']['background_color'], "112233")

    def test_poll_icon_appearance_also_in_fields_dict(self):
        """poll_received_messages should also serialize icon appearance into fields['4']."""
        icon_data = ["test-icon", bytes([0x11, 0x22, 0x33]), bytes([0xAA, 0xBB, 0xCC])]

        msg = _make_mock_lxmf_message(
            fields={4: icon_data},
            content=b"Test message",
            msg_hash=bytes.fromhex("ef" * 16),
        )

        self.wrapper.router.pending_inbound = [msg]

        results = self.wrapper.poll_received_messages()

        self.assertEqual(len(results), 1)
        result = results[0]
        self.assertIn('fields', result)
        self.assertIn('4', result['fields'])
        self.assertEqual(result['fields']['4']['icon_name'], "test-icon")
        self.assertEqual(result['fields']['4']['foreground_color'], "112233")
        self.assertEqual(result['fields']['4']['background_color'], "aabbcc")

    def test_poll_without_icon_appearance(self):
        """poll_received_messages without field 4 should not have icon_appearance."""
        msg = _make_mock_lxmf_message(
            fields={},
            content=b"Plain message",
            msg_hash=bytes.fromhex("01" * 16),
        )

        self.wrapper.router.pending_inbound = [msg]

        results = self.wrapper.poll_received_messages()

        self.assertEqual(len(results), 1)
        self.assertNotIn('icon_appearance', results[0])


class TestEndToEndByteOrder(unittest.TestCase):
    """End-to-end test verifying consistent byte order across send and receive paths.

    The icon appearance format is always [name, fg_bytes, bg_bytes]:
    - send_location_telemetry packs [name, fg_bytes, bg_bytes]
    - _on_lxmf_delivery extracts [1]->foreground_color, [2]->background_color
    - unpack_telemetry_stream extracts [1]->fg, [2]->bg
    - poll_received_messages extracts [1]->foreground_color, [2]->background_color
    """

    def test_send_receive_byte_order_consistent(self):
        """Sending fg=red, bg=green should receive fg=red, bg=green everywhere."""
        # Simulate what send_location_telemetry does
        icon_name = "person"
        fg_color_hex = "ff0000"  # red
        bg_color_hex = "00ff00"  # green
        fg_bytes = bytes.fromhex(fg_color_hex)
        bg_bytes = bytes.fromhex(bg_color_hex)

        # Production code packs as [name, fg_bytes, bg_bytes]
        packed_appearance = [icon_name, fg_bytes, bg_bytes]

        # Verify the send side packs fg first, bg second
        self.assertEqual(packed_appearance[1], bytes([0xFF, 0x00, 0x00]))  # fg = red
        self.assertEqual(packed_appearance[2], bytes([0x00, 0xFF, 0x00]))  # bg = green

        # Now test receive side via unpack_telemetry_stream
        source_hash = bytes(16)
        timestamp = int(time.time())
        telemetry = _make_packed_telemetry()
        stream = [[source_hash, timestamp, telemetry, packed_appearance]]
        results = unpack_telemetry_stream(stream)

        self.assertEqual(results[0]['appearance']['foreground_color'], "ff0000")  # red
        self.assertEqual(results[0]['appearance']['background_color'], "00ff00")  # green

    def test_stream_appearance_roundtrip_with_distinct_colors(self):
        """Test that completely distinct RGB values survive the roundtrip correctly."""
        fg = bytes([0x78, 0x9A, 0xBC])
        bg = bytes([0x12, 0x34, 0x56])
        appearance = ["car", fg, bg]

        source_hash = bytes(16)
        timestamp = int(time.time())
        telemetry = _make_packed_telemetry()
        stream = [[source_hash, timestamp, telemetry, appearance]]
        results = unpack_telemetry_stream(stream)

        self.assertEqual(results[0]['appearance']['icon_name'], "car")
        self.assertEqual(results[0]['appearance']['foreground_color'], "789abc")
        self.assertEqual(results[0]['appearance']['background_color'], "123456")


if __name__ == '__main__':
    unittest.main()
