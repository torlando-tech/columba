"""
Test suite for telemetry stream unpacking (FIELD_TELEMETRY_STREAM).

Tests the unpack_telemetry_stream function that processes bulk telemetry
from collectors like Sideband/Reticulum Telemetry Hub.
"""

import sys
import os
import unittest
from unittest.mock import MagicMock

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Try to import u-msgpack-python
try:
    import umsgpack
except ImportError:
    # Skip all tests if umsgpack not available (will be installed in CI)
    umsgpack = None

# Skip all tests if umsgpack is not available
if umsgpack is None:
    raise unittest.SkipTest("umsgpack not available - skipping telemetry stream tests")

# Make umsgpack available BEFORE importing reticulum_wrapper
sys.modules['umsgpack'] = umsgpack

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper
import importlib
reticulum_wrapper.umsgpack = umsgpack
importlib.reload(reticulum_wrapper)

from reticulum_wrapper import (
    unpack_telemetry_stream,
    pack_location_telemetry,
    MARKER_SYMBOL_REGISTRY,
    appearance_from_marker_symbol,
    _color_from_symbol_key,
)


def create_packed_telemetry(lat, lon, accuracy=10.0, timestamp_ms=1703980800000):
    """Helper to create packed telemetry data."""
    return pack_location_telemetry(
        lat=lat,
        lon=lon,
        accuracy=accuracy,
        timestamp_ms=timestamp_ms,
    )


class TestUnpackTelemetryStreamBasic(unittest.TestCase):
    """Test basic functionality of unpack_telemetry_stream."""

    def test_returns_list(self):
        """unpack_telemetry_stream should return a list."""
        result = unpack_telemetry_stream([])
        self.assertIsInstance(result, list)

    def test_empty_stream_returns_empty_list(self):
        """Empty stream input should return empty list."""
        result = unpack_telemetry_stream([])
        self.assertEqual(len(result), 0)

    def test_single_entry_unpacks_correctly(self):
        """Single valid entry should unpack to one result."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        timestamp = 1703980800
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        stream = [[source_hash, timestamp, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)

    def test_multiple_entries_unpack_correctly(self):
        """Multiple valid entries should all be unpacked."""
        entries = []
        for i in range(5):
            source_hash = bytes([i] * 16)
            timestamp = 1703980800 + i * 60
            packed = create_packed_telemetry(lat=37.0 + i, lon=-122.0 - i)
            entries.append([source_hash, timestamp, packed])

        result = unpack_telemetry_stream(entries)
        self.assertEqual(len(result), 5)


class TestUnpackTelemetryStreamTimestamp(unittest.TestCase):
    """Test timestamp handling in unpack_telemetry_stream."""

    def test_valid_timestamp_overrides_packed_timestamp(self):
        """When timestamp > 0, it should override the packed timestamp."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        stream_timestamp = 1700000000  # Different from packed
        packed = create_packed_telemetry(
            lat=37.7749,
            lon=-122.4194,
            timestamp_ms=1703980800000,  # This should be overridden
        )

        stream = [[source_hash, stream_timestamp, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Stream timestamp (in seconds) * 1000 = milliseconds
        self.assertEqual(result[0]['ts'], stream_timestamp * 1000)

    def test_none_timestamp_preserves_packed_timestamp(self):
        """When timestamp is None, packed timestamp should be preserved."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed_ts_ms = 1703980800000
        packed = create_packed_telemetry(
            lat=37.7749,
            lon=-122.4194,
            timestamp_ms=packed_ts_ms,
        )

        stream = [[source_hash, None, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Should preserve the timestamp from packed telemetry (within 999ms due to rounding)
        self.assertAlmostEqual(result[0]['ts'], packed_ts_ms, delta=999)

    def test_zero_timestamp_preserves_packed_timestamp(self):
        """When timestamp is 0, packed timestamp should be preserved."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed_ts_ms = 1703980800000
        packed = create_packed_telemetry(
            lat=37.7749,
            lon=-122.4194,
            timestamp_ms=packed_ts_ms,
        )

        stream = [[source_hash, 0, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Should preserve the timestamp from packed telemetry
        self.assertAlmostEqual(result[0]['ts'], packed_ts_ms, delta=999)

    def test_negative_timestamp_preserves_packed_timestamp(self):
        """When timestamp is negative, packed timestamp should be preserved."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed_ts_ms = 1703980800000
        packed = create_packed_telemetry(
            lat=37.7749,
            lon=-122.4194,
            timestamp_ms=packed_ts_ms,
        )

        stream = [[source_hash, -1, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Should preserve the timestamp from packed telemetry
        self.assertAlmostEqual(result[0]['ts'], packed_ts_ms, delta=999)

    def test_future_timestamp_rejected(self):
        """Timestamps more than 1 hour in the future should be rejected."""
        import time
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed_ts_ms = 1703980800000
        packed = create_packed_telemetry(
            lat=37.7749,
            lon=-122.4194,
            timestamp_ms=packed_ts_ms,
        )

        # Timestamp 2 hours in the future
        future_timestamp = int(time.time()) + 7200
        stream = [[source_hash, future_timestamp, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Should preserve packed timestamp, not the future override
        self.assertAlmostEqual(result[0]['ts'], packed_ts_ms, delta=999)

    def test_near_future_timestamp_accepted(self):
        """Timestamps less than 1 hour in the future should be accepted."""
        import time
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed_ts_ms = 1703980800000
        packed = create_packed_telemetry(
            lat=37.7749,
            lon=-122.4194,
            timestamp_ms=packed_ts_ms,
        )

        # Timestamp 30 minutes in the future (within tolerance)
        near_future_timestamp = int(time.time()) + 1800
        stream = [[source_hash, near_future_timestamp, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Should use the override timestamp (converted to ms)
        self.assertAlmostEqual(result[0]['ts'], near_future_timestamp * 1000, delta=999)


class TestUnpackTelemetryStreamSourceHash(unittest.TestCase):
    """Test source hash handling in unpack_telemetry_stream."""

    def test_bytes_source_hash_converted_to_hex(self):
        """Bytes source hash should be converted to hex string."""
        source_hash = bytes.fromhex("deadbeefcafebabe1234567890abcdef")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        stream = [[source_hash, 1703980800, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['source_hash'], "deadbeefcafebabe1234567890abcdef")

    def test_string_source_hash_preserved(self):
        """String source hash should be preserved as-is."""
        source_hash = "already_a_string_hash"
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        stream = [[source_hash, 1703980800, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['source_hash'], "already_a_string_hash")


class TestUnpackTelemetryStreamAppearance(unittest.TestCase):
    """Test appearance data handling in unpack_telemetry_stream."""

    def test_no_appearance_field_absent(self):
        """Entry without appearance should not have appearance field."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        stream = [[source_hash, 1703980800, packed]]  # No 4th element
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertNotIn('appearance', result[0])

    def test_valid_appearance_parsed_correctly(self):
        """Valid appearance data should be parsed to Columba format.

        Sideband format: [icon_name, fg_rgb_bytes, bg_rgb_bytes]
        """
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        appearance = ["person", bytes([255, 0, 0]), bytes([0, 255, 0])]  # red fg, green bg

        stream = [[source_hash, 1703980800, packed, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertIn('appearance', result[0])
        self.assertEqual(result[0]['appearance']['icon_name'], "person")
        self.assertEqual(result[0]['appearance']['foreground_color'], "ff0000")
        self.assertEqual(result[0]['appearance']['background_color'], "00ff00")

    def test_appearance_with_different_colors(self):
        """Test appearance with various color values.

        Sideband format: [icon_name, fg_rgb_bytes, bg_rgb_bytes]
        """
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        appearance = ["car", bytes([0, 0, 255]), bytes([128, 128, 128])]  # blue fg, gray bg

        stream = [[source_hash, 1703980800, packed, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(result[0]['appearance']['foreground_color'], "0000ff")
        self.assertEqual(result[0]['appearance']['background_color'], "808080")

    def test_invalid_appearance_ignored(self):
        """Invalid appearance data should be ignored (not crash)."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        # Test cases that should be handled gracefully
        invalid_appearances = [
            None,
            [],
            [1, 2],  # Too few elements
            "not a list",
            123,
        ]

        for appearance in invalid_appearances:
            stream = [[source_hash, 1703980800, packed, appearance]]
            result = unpack_telemetry_stream(stream)
            self.assertEqual(len(result), 1)
            # Should still have location data, just no appearance
            self.assertIn('lat', result[0])

    def test_appearance_with_non_bytes_colors_handles_gracefully(self):
        """Non-bytes color values should result in None colors.

        Sideband format: [icon_name, fg_rgb_bytes, bg_rgb_bytes]
        """
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        appearance = ["valid_icon", "not_bytes", [1, 2, 3]]  # Invalid color formats

        stream = [[source_hash, 1703980800, packed, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertIn('appearance', result[0])
        self.assertEqual(result[0]['appearance']['icon_name'], "valid_icon")
        self.assertIsNone(result[0]['appearance']['background_color'])
        self.assertIsNone(result[0]['appearance']['foreground_color'])

    def test_icon_name_with_special_chars_rejected(self):
        """Icon names with special characters should be rejected."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        fg = bytes([255, 0, 0])
        bg = bytes([0, 0, 255])

        invalid_names = [
            "<script>alert(1)</script>",
            "icon\x00null",
            "icon\njson",
            "../../etc/passwd",
            '{"injection": true}',
        ]

        for name in invalid_names:
            appearance = [name, fg, bg]
            stream = [[source_hash, 1703980800, packed, appearance]]
            result = unpack_telemetry_stream(stream)
            self.assertEqual(len(result), 1)
            self.assertNotIn('appearance', result[0], f"Icon name '{repr(name)}' should be rejected")

    def test_icon_name_too_long_rejected(self):
        """Icon names longer than 50 chars should be rejected."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        appearance = ["a" * 51, bytes([255, 0, 0]), bytes([0, 0, 255])]

        stream = [[source_hash, 1703980800, packed, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertNotIn('appearance', result[0])

    def test_valid_icon_names_accepted(self):
        """Valid icon names with alphanumeric, underscores, and hyphens should be accepted."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        fg = bytes([255, 0, 0])
        bg = bytes([0, 0, 255])

        valid_names = [
            "icon",
            "my_icon",
            "Icon123",
            "LOCATION_PIN",
            "sail-boat",           # MDI names use hyphens
            "access-point-network",
            "food-fork-drink",
            "a" * 50,  # Exactly 50 chars
        ]

        for name in valid_names:
            appearance = [name, fg, bg]
            stream = [[source_hash, 1703980800, packed, appearance]]
            result = unpack_telemetry_stream(stream)
            self.assertEqual(len(result), 1)
            self.assertIn('appearance', result[0], f"Icon name '{name}' should be accepted")
            self.assertEqual(result[0]['appearance']['icon_name'], name)


class TestUnpackTelemetryStreamErrorHandling(unittest.TestCase):
    """Test error handling in unpack_telemetry_stream."""

    def test_entry_with_less_than_3_elements_skipped(self):
        """Entries with < 3 elements should be skipped."""
        stream = [
            [bytes([1] * 16), 1703980800],  # Only 2 elements
            [bytes([2] * 16)],  # Only 1 element
        ]
        result = unpack_telemetry_stream(stream)
        self.assertEqual(len(result), 0)

    def test_invalid_packed_telemetry_skipped(self):
        """Entries with invalid packed telemetry should be skipped."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        stream = [
            [source_hash, 1703980800, b"invalid_msgpack_data"],
            [source_hash, 1703980800, None],
            [source_hash, 1703980800, "not bytes"],
        ]
        result = unpack_telemetry_stream(stream)
        self.assertEqual(len(result), 0)

    def test_mixed_valid_invalid_entries(self):
        """Valid entries should be returned, invalid ones skipped."""
        valid_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        valid_packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        stream = [
            [valid_hash, 1703980800, valid_packed],  # Valid
            [valid_hash],  # Invalid - too few elements
            [valid_hash, 1703980800, b"bad_data"],  # Invalid - bad telemetry
            [valid_hash, 1703980800, valid_packed],  # Valid
        ]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 2)  # Only 2 valid entries

    def test_exception_in_entry_doesnt_stop_processing(self):
        """Exception processing one entry should not stop other entries."""
        valid_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        valid_packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        # First entry causes exception (None in unexpected place)
        stream = [
            None,  # Will cause exception when iterating
            [valid_hash, 1703980800, valid_packed],
        ]

        # Should not raise, and should process valid entry
        result = unpack_telemetry_stream(stream)
        # The None entry will be skipped due to exception handling
        self.assertEqual(len(result), 1)


class TestUnpackTelemetryStreamLocationData(unittest.TestCase):
    """Test that location data is correctly extracted from stream."""

    def test_latitude_extracted_correctly(self):
        """Latitude should be correctly extracted from packed telemetry."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        lat = 37.7749
        packed = create_packed_telemetry(lat=lat, lon=-122.4194)

        stream = [[source_hash, 1703980800, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertAlmostEqual(result[0]['lat'], lat, places=6)

    def test_longitude_extracted_correctly(self):
        """Longitude should be correctly extracted from packed telemetry."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        lon = -122.4194
        packed = create_packed_telemetry(lat=37.7749, lon=lon)

        stream = [[source_hash, 1703980800, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertAlmostEqual(result[0]['lng'], lon, places=6)

    def test_accuracy_extracted_correctly(self):
        """Accuracy should be correctly extracted from packed telemetry."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        accuracy = 15.5
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194, accuracy=accuracy)

        stream = [[source_hash, 1703980800, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertAlmostEqual(result[0]['acc'], accuracy, places=2)

    def test_type_is_location_share(self):
        """Result should have type='location_share'."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)

        stream = [[source_hash, 1703980800, packed]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(result[0]['type'], 'location_share')


class TestUnpackTelemetryStreamSidebandFormat(unittest.TestCase):
    """Test compatibility with Sideband's telemetry stream format."""

    def test_sideband_style_stream_entry(self):
        """Test unpacking a Sideband-style stream entry.

        Sideband format: [icon_name, fg_rgb_bytes, bg_rgb_bytes]
        """
        source_hash = bytes.fromhex("deadbeefcafebabe1234567890abcdef")
        timestamp = 1703980800
        packed = create_packed_telemetry(lat=40.7128, lon=-74.0060, accuracy=10.0)
        # Sideband appearance: [icon_name, fg_bytes, bg_bytes]
        appearance = ["runner", bytes([255, 128, 0]), bytes([0, 64, 128])]

        stream = [[source_hash, timestamp, packed, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['source_hash'], "deadbeefcafebabe1234567890abcdef")
        self.assertEqual(result[0]['ts'], timestamp * 1000)
        self.assertAlmostEqual(result[0]['lat'], 40.7128, places=6)
        self.assertAlmostEqual(result[0]['lng'], -74.0060, places=6)
        self.assertEqual(result[0]['appearance']['icon_name'], "runner")
        self.assertEqual(result[0]['appearance']['foreground_color'], "ff8000")
        self.assertEqual(result[0]['appearance']['background_color'], "004080")

    def test_multiple_peers_in_stream(self):
        """Test unpacking stream with multiple peers (real collector scenario)."""
        stream = []
        expected_locations = []

        # Simulate 10 different peers reporting locations
        for i in range(10):
            source_hash = bytes([i] * 16)
            timestamp = 1703980800 + i * 300  # 5 min intervals
            lat = 35.0 + (i * 0.1)
            lon = 139.0 + (i * 0.1)
            packed = create_packed_telemetry(lat=lat, lon=lon, accuracy=5.0 + i)

            stream.append([source_hash, timestamp, packed])
            expected_locations.append({
                'source_hash': source_hash.hex(),
                'lat': lat,
                'lon': lon,
                'ts': timestamp * 1000,
            })

        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 10)

        for i, r in enumerate(result):
            self.assertEqual(r['source_hash'], expected_locations[i]['source_hash'])
            self.assertAlmostEqual(r['lat'], expected_locations[i]['lat'], places=6)
            self.assertAlmostEqual(r['lng'], expected_locations[i]['lon'], places=6)
            self.assertEqual(r['ts'], expected_locations[i]['ts'])


class TestUnpackTelemetryStreamIntegration(unittest.TestCase):
    """Integration tests for telemetry stream processing."""

    def test_full_roundtrip_pack_stream_unpack(self):
        """Test full roundtrip: pack -> stream format -> unpack."""
        # Original location data
        original = {
            'lat': 51.5074,
            'lon': -0.1278,
            'accuracy': 8.5,
            'timestamp_ms': 1703980800000,
        }

        # Pack as Columba would
        packed = pack_location_telemetry(
            lat=original['lat'],
            lon=original['lon'],
            accuracy=original['accuracy'],
            timestamp_ms=original['timestamp_ms'],
        )

        # Put in stream format as collector would
        source_hash = bytes.fromhex("1234567890abcdef1234567890abcdef")
        stream_timestamp = 1703980800  # Same as packed, in seconds
        stream = [[source_hash, stream_timestamp, packed]]

        # Unpack as receiver would
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertAlmostEqual(result[0]['lat'], original['lat'], places=6)
        self.assertAlmostEqual(result[0]['lng'], original['lon'], places=6)
        self.assertAlmostEqual(result[0]['acc'], original['accuracy'], places=2)
        self.assertEqual(result[0]['ts'], stream_timestamp * 1000)


class TestMarkerSymbolRegistry(unittest.TestCase):
    """Test the marker symbol registry and colour derivation."""

    def test_registry_contains_common_symbols(self):
        """Registry should contain common marker symbols."""
        for sym in ("person", "car", "bike", "home", "flag", "rectangle"):
            self.assertIn(sym, MARKER_SYMBOL_REGISTRY,
                          f"Symbol '{sym}' missing from MARKER_SYMBOL_REGISTRY")

    def test_registry_values_are_strings(self):
        """All registry values should be MDI icon name strings."""
        for key, value in MARKER_SYMBOL_REGISTRY.items():
            self.assertIsInstance(value, str, f"Value for '{key}' is not a string")

    def test_color_from_symbol_key_returns_3_bytes(self):
        """_color_from_symbol_key should return exactly 3 bytes."""
        result = _color_from_symbol_key("person")
        self.assertIsInstance(result, bytes)
        self.assertEqual(len(result), 3)

    def test_color_from_symbol_key_is_deterministic(self):
        """Same symbol key should always produce the same colour."""
        a = _color_from_symbol_key("car")
        b = _color_from_symbol_key("car")
        self.assertEqual(a, b)

    def test_color_from_symbol_key_differs_across_keys(self):
        """Different symbol keys should produce different colours."""
        c1 = _color_from_symbol_key("car")
        c2 = _color_from_symbol_key("person")
        self.assertNotEqual(c1, c2)

    def test_color_from_symbol_key_in_readable_range(self):
        """Colour components should be in the 40-200 range for readability."""
        for sym in ("person", "car", "rectangle", "node"):
            color = _color_from_symbol_key(sym)
            for i, component in enumerate(color):
                self.assertGreaterEqual(component, 40,
                    f"Component {i} of '{sym}' too dark: {component}")
                self.assertLessEqual(component, 200,
                    f"Component {i} of '{sym}' too bright: {component}")


class TestAppearanceFromMarkerSymbol(unittest.TestCase):
    """Test appearance_from_marker_symbol helper."""

    def test_known_symbol_returns_list(self):
        """Known symbol should return a 3-element appearance list."""
        result = appearance_from_marker_symbol("person")
        self.assertIsNotNone(result)
        self.assertIsInstance(result, list)
        self.assertEqual(len(result), 3)

    def test_known_symbol_has_mdi_icon_name(self):
        """First element should be the MDI icon name from the registry."""
        result = appearance_from_marker_symbol("car")
        self.assertEqual(result[0], "car")  # MARKER_SYMBOL_REGISTRY["car"] == "car"

    def test_known_symbol_has_fg_bytes(self):
        """Second element should be 3-byte foreground (white)."""
        result = appearance_from_marker_symbol("person")
        self.assertIsInstance(result[1], bytes)
        self.assertEqual(result[1], b"\xff\xff\xff")

    def test_known_symbol_has_bg_bytes(self):
        """Third element should be 3-byte background colour."""
        result = appearance_from_marker_symbol("rectangle")
        self.assertIsInstance(result[2], bytes)
        self.assertEqual(len(result[2]), 3)

    def test_unknown_symbol_returns_none(self):
        """Unknown symbol key should return None."""
        result = appearance_from_marker_symbol("nonexistent_symbol_xyz")
        self.assertIsNone(result)

    def test_appearance_compatible_with_unpack(self):
        """Appearance from marker symbol should unpack correctly in stream."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        packed = create_packed_telemetry(lat=37.7749, lon=-122.4194)
        appearance = appearance_from_marker_symbol("person")

        stream = [[source_hash, 1703980800, packed, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertIn('appearance', result[0])
        self.assertEqual(result[0]['appearance']['icon_name'], "account")  # person -> account
        self.assertIsNotNone(result[0]['appearance']['background_color'])
        self.assertEqual(result[0]['appearance']['foreground_color'], "ffffff")


if __name__ == '__main__':
    unittest.main()
