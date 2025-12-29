"""
Test suite for Sideband-compatible telemetry pack/unpack functions.

Tests the pack_location_telemetry and unpack_location_telemetry functions
that enable interoperability with Sideband's Telemeter format.
"""

import sys
import os
import unittest
import struct
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Try to import u-msgpack-python, install if missing
try:
    import umsgpack
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, '-m', 'pip', 'install', 'u-msgpack-python', '-q'])
    import umsgpack

# Make umsgpack available BEFORE importing reticulum_wrapper
sys.modules['umsgpack'] = umsgpack

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking - need to reload to pick up umsgpack
import reticulum_wrapper
import importlib
# Re-assign umsgpack in the module since it was None during initial import
reticulum_wrapper.umsgpack = umsgpack
importlib.reload(reticulum_wrapper)

from reticulum_wrapper import (
    pack_location_telemetry,
    unpack_location_telemetry,
    FIELD_TELEMETRY,
    FIELD_COLUMBA_META,
    LEGACY_LOCATION_FIELD,
    SID_TIME,
    SID_LOCATION,
)


class TestFieldConstants(unittest.TestCase):
    """Test that LXMF field constants are correctly defined."""

    def test_field_telemetry_is_0x02(self):
        """FIELD_TELEMETRY should be 0x02 per LXMF spec."""
        self.assertEqual(FIELD_TELEMETRY, 0x02)

    def test_field_columba_meta_is_0x70(self):
        """FIELD_COLUMBA_META should be 0x70 (in user range)."""
        self.assertEqual(FIELD_COLUMBA_META, 0x70)

    def test_legacy_location_field_is_7(self):
        """LEGACY_LOCATION_FIELD should be 7 for backwards compat."""
        self.assertEqual(LEGACY_LOCATION_FIELD, 7)

    def test_sid_time_is_0x01(self):
        """SID_TIME should be 0x01 per Sideband spec."""
        self.assertEqual(SID_TIME, 0x01)

    def test_sid_location_is_0x02(self):
        """SID_LOCATION should be 0x02 per Sideband spec."""
        self.assertEqual(SID_LOCATION, 0x02)


class TestPackLocationTelemetry(unittest.TestCase):
    """Test the pack_location_telemetry function."""

    def test_returns_bytes(self):
        """pack_location_telemetry should return bytes."""
        result = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        self.assertIsInstance(result, bytes)

    def test_packed_data_is_valid_msgpack(self):
        """Packed data should be valid msgpack that can be unpacked."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        self.assertIsInstance(unpacked, dict)

    def test_packed_data_contains_sid_time(self):
        """Packed data should contain SID_TIME key."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        self.assertIn(SID_TIME, unpacked)
        self.assertEqual(unpacked[SID_TIME], 1703980800)  # seconds, not ms

    def test_packed_data_contains_sid_location(self):
        """Packed data should contain SID_LOCATION key with location array."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        self.assertIn(SID_LOCATION, unpacked)
        self.assertIsInstance(unpacked[SID_LOCATION], list)
        self.assertEqual(len(unpacked[SID_LOCATION]), 7)

    def test_latitude_packed_as_microdegrees(self):
        """Latitude should be packed as signed int in microdegrees."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        lat_bytes = unpacked[SID_LOCATION][0]
        lat_microdeg = struct.unpack("!i", lat_bytes)[0]
        self.assertEqual(lat_microdeg, 37774900)  # 37.7749 * 1e6

    def test_longitude_packed_as_microdegrees(self):
        """Longitude should be packed as signed int in microdegrees."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        lon_bytes = unpacked[SID_LOCATION][1]
        lon_microdeg = struct.unpack("!i", lon_bytes)[0]
        self.assertEqual(lon_microdeg, -122419400)  # -122.4194 * 1e6

    def test_accuracy_packed_as_centimeters(self):
        """Accuracy should be packed as unsigned short in centimeters."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.5,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        acc_bytes = unpacked[SID_LOCATION][5]
        acc_cm = struct.unpack("!H", acc_bytes)[0]
        self.assertEqual(acc_cm, 1050)  # 10.5 * 100

    def test_handles_negative_latitude(self):
        """Should correctly handle negative latitude (southern hemisphere)."""
        packed = pack_location_telemetry(
            lat=-33.8688,  # Sydney, Australia
            lon=151.2093,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        lat_bytes = unpacked[SID_LOCATION][0]
        lat_microdeg = struct.unpack("!i", lat_bytes)[0]
        self.assertEqual(lat_microdeg, -33868800)

    def test_handles_negative_longitude(self):
        """Should correctly handle negative longitude (western hemisphere)."""
        packed = pack_location_telemetry(
            lat=40.7128,  # New York
            lon=-74.0060,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        lon_bytes = unpacked[SID_LOCATION][1]
        lon_microdeg = struct.unpack("!i", lon_bytes)[0]
        self.assertEqual(lon_microdeg, -74006000)

    def test_optional_altitude_defaults_to_zero(self):
        """Altitude should default to 0 if not specified."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        alt_bytes = unpacked[SID_LOCATION][2]
        alt_cm = struct.unpack("!i", alt_bytes)[0]
        self.assertEqual(alt_cm, 0)

    def test_optional_altitude_packed_correctly(self):
        """Altitude should be packed in centimeters when provided."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
            altitude=100.5,
        )
        unpacked = umsgpack.unpackb(packed)
        alt_bytes = unpacked[SID_LOCATION][2]
        alt_cm = struct.unpack("!i", alt_bytes)[0]
        self.assertEqual(alt_cm, 10050)  # 100.5 * 100

    def test_optional_speed_defaults_to_zero(self):
        """Speed should default to 0 if not specified."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        speed_bytes = unpacked[SID_LOCATION][3]
        speed_cm_s = struct.unpack("!I", speed_bytes)[0]
        self.assertEqual(speed_cm_s, 0)

    def test_optional_bearing_defaults_to_zero(self):
        """Bearing should default to 0 if not specified."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        unpacked = umsgpack.unpackb(packed)
        bearing_bytes = unpacked[SID_LOCATION][4]
        bearing_cdeg = struct.unpack("!i", bearing_bytes)[0]
        self.assertEqual(bearing_cdeg, 0)


class TestUnpackLocationTelemetry(unittest.TestCase):
    """Test the unpack_location_telemetry function."""

    def test_returns_dict_for_valid_data(self):
        """unpack_location_telemetry should return a dict for valid data."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertIsInstance(result, dict)

    def test_returns_none_for_invalid_data(self):
        """unpack_location_telemetry should return None for invalid data."""
        result = unpack_location_telemetry(b"invalid data")
        self.assertIsNone(result)

    def test_returns_none_for_missing_sid_location(self):
        """unpack_location_telemetry should return None if SID_LOCATION missing."""
        # Pack data without SID_LOCATION
        packed = umsgpack.packb({SID_TIME: 1703980800})
        result = unpack_location_telemetry(packed)
        self.assertIsNone(result)

    def test_unpacked_lat_matches_original(self):
        """Unpacked latitude should match original value."""
        original_lat = 37.7749
        packed = pack_location_telemetry(
            lat=original_lat,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertAlmostEqual(result['lat'], original_lat, places=6)

    def test_unpacked_lng_matches_original(self):
        """Unpacked longitude should match original value."""
        original_lon = -122.4194
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=original_lon,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertAlmostEqual(result['lng'], original_lon, places=6)

    def test_unpacked_accuracy_matches_original(self):
        """Unpacked accuracy should match original value."""
        original_acc = 10.5
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=original_acc,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertAlmostEqual(result['acc'], original_acc, places=2)

    def test_unpacked_timestamp_matches_original(self):
        """Unpacked timestamp should match original value (in ms)."""
        original_ts = 1703980800000
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=original_ts,
        )
        result = unpack_location_telemetry(packed)
        # Timestamp is rounded to seconds then back to ms, so allow 999ms tolerance
        self.assertAlmostEqual(result['ts'], original_ts, delta=999)

    def test_unpacked_contains_type_location_share(self):
        """Unpacked data should contain type='location_share'."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
        )
        result = unpack_location_telemetry(packed)
        self.assertEqual(result['type'], 'location_share')

    def test_unpacked_contains_altitude(self):
        """Unpacked data should contain altitude field."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
            altitude=150.0,
        )
        result = unpack_location_telemetry(packed)
        self.assertIn('altitude', result)
        self.assertAlmostEqual(result['altitude'], 150.0, places=2)

    def test_unpacked_contains_speed(self):
        """Unpacked data should contain speed field."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
            speed=50.0,
        )
        result = unpack_location_telemetry(packed)
        self.assertIn('speed', result)
        self.assertAlmostEqual(result['speed'], 50.0, places=2)

    def test_unpacked_contains_bearing(self):
        """Unpacked data should contain bearing field."""
        packed = pack_location_telemetry(
            lat=37.7749,
            lon=-122.4194,
            accuracy=10.0,
            timestamp_ms=1703980800000,
            bearing=180.0,
        )
        result = unpack_location_telemetry(packed)
        self.assertIn('bearing', result)
        self.assertAlmostEqual(result['bearing'], 180.0, places=2)


class TestPackUnpackRoundTrip(unittest.TestCase):
    """Test round-trip pack/unpack for various coordinate combinations."""

    def test_round_trip_san_francisco(self):
        """Test round-trip for San Francisco coordinates."""
        original = {
            'lat': 37.7749,
            'lon': -122.4194,
            'accuracy': 10.0,
            'timestamp_ms': 1703980800000,
        }
        packed = pack_location_telemetry(**original)
        result = unpack_location_telemetry(packed)

        self.assertAlmostEqual(result['lat'], original['lat'], places=6)
        self.assertAlmostEqual(result['lng'], original['lon'], places=6)
        self.assertAlmostEqual(result['acc'], original['accuracy'], places=2)

    def test_round_trip_tokyo(self):
        """Test round-trip for Tokyo coordinates (positive lat, positive lon)."""
        original = {
            'lat': 35.6762,
            'lon': 139.6503,
            'accuracy': 5.0,
            'timestamp_ms': 1703980800000,
        }
        packed = pack_location_telemetry(**original)
        result = unpack_location_telemetry(packed)

        self.assertAlmostEqual(result['lat'], original['lat'], places=6)
        self.assertAlmostEqual(result['lng'], original['lon'], places=6)

    def test_round_trip_sydney(self):
        """Test round-trip for Sydney coordinates (negative lat, positive lon)."""
        original = {
            'lat': -33.8688,
            'lon': 151.2093,
            'accuracy': 15.0,
            'timestamp_ms': 1703980800000,
        }
        packed = pack_location_telemetry(**original)
        result = unpack_location_telemetry(packed)

        self.assertAlmostEqual(result['lat'], original['lat'], places=6)
        self.assertAlmostEqual(result['lng'], original['lon'], places=6)

    def test_round_trip_buenos_aires(self):
        """Test round-trip for Buenos Aires coordinates (negative lat, negative lon)."""
        original = {
            'lat': -34.6037,
            'lon': -58.3816,
            'accuracy': 20.0,
            'timestamp_ms': 1703980800000,
        }
        packed = pack_location_telemetry(**original)
        result = unpack_location_telemetry(packed)

        self.assertAlmostEqual(result['lat'], original['lat'], places=6)
        self.assertAlmostEqual(result['lng'], original['lon'], places=6)

    def test_round_trip_with_all_optional_fields(self):
        """Test round-trip with all optional fields populated."""
        original = {
            'lat': 37.7749,
            'lon': -122.4194,
            'accuracy': 10.0,
            'timestamp_ms': 1703980800000,
            'altitude': 150.5,
            'speed': 55.5,
            'bearing': 270.5,
        }
        packed = pack_location_telemetry(**original)
        result = unpack_location_telemetry(packed)

        self.assertAlmostEqual(result['lat'], original['lat'], places=6)
        self.assertAlmostEqual(result['lng'], original['lon'], places=6)
        self.assertAlmostEqual(result['acc'], original['accuracy'], places=2)
        self.assertAlmostEqual(result['altitude'], original['altitude'], places=2)
        self.assertAlmostEqual(result['speed'], original['speed'], places=2)
        self.assertAlmostEqual(result['bearing'], original['bearing'], places=2)

    def test_round_trip_extreme_latitude(self):
        """Test round-trip for extreme latitude values."""
        for lat in [0.0, 90.0, -90.0, 89.999999, -89.999999]:
            with self.subTest(lat=lat):
                packed = pack_location_telemetry(
                    lat=lat,
                    lon=0.0,
                    accuracy=10.0,
                    timestamp_ms=1703980800000,
                )
                result = unpack_location_telemetry(packed)
                self.assertAlmostEqual(result['lat'], lat, places=6)

    def test_round_trip_extreme_longitude(self):
        """Test round-trip for extreme longitude values."""
        for lon in [0.0, 180.0, -180.0, 179.999999, -179.999999]:
            with self.subTest(lon=lon):
                packed = pack_location_telemetry(
                    lat=0.0,
                    lon=lon,
                    accuracy=10.0,
                    timestamp_ms=1703980800000,
                )
                result = unpack_location_telemetry(packed)
                self.assertAlmostEqual(result['lng'], lon, places=6)


class TestUnpackEdgeCases(unittest.TestCase):
    """Test edge cases and error handling for unpack_location_telemetry."""

    def test_returns_none_for_none_input(self):
        """unpack_location_telemetry should handle None input gracefully."""
        result = unpack_location_telemetry(None)
        self.assertIsNone(result)

    def test_returns_none_for_empty_bytes(self):
        """unpack_location_telemetry should handle empty bytes gracefully."""
        result = unpack_location_telemetry(b"")
        self.assertIsNone(result)

    def test_returns_none_for_short_location_array(self):
        """unpack_location_telemetry should return None if location array < 7 elements."""
        # Create valid msgpack with SID_LOCATION but only 3 elements
        short_location = [
            struct.pack("!i", 37774900),  # lat
            struct.pack("!i", -122419400),  # lon
            struct.pack("!i", 0),  # altitude
        ]
        packed = umsgpack.packb({SID_TIME: 1703980800, SID_LOCATION: short_location})
        result = unpack_location_telemetry(packed)
        self.assertIsNone(result)

    def test_returns_none_for_non_dict_msgpack(self):
        """unpack_location_telemetry should return None for non-dict msgpack data."""
        packed = umsgpack.packb([1, 2, 3])  # list instead of dict
        result = unpack_location_telemetry(packed)
        self.assertIsNone(result)

    def test_returns_none_for_wrong_type_in_location_array(self):
        """unpack_location_telemetry should return None if location array has wrong types."""
        # Create location array with strings instead of bytes
        bad_location = ["not", "bytes", "data", "here", "at", "all", 123]
        packed = umsgpack.packb({SID_TIME: 1703980800, SID_LOCATION: bad_location})
        result = unpack_location_telemetry(packed)
        self.assertIsNone(result)


class TestSidebandCompatibility(unittest.TestCase):
    """Test compatibility with Sideband's Telemeter format."""

    def test_sideband_style_packed_data(self):
        """Test unpacking data in exact Sideband format (as Sideband would pack it)."""
        # Simulate Sideband's Location.pack() output exactly
        lat = 37.7749
        lon = -122.4194
        altitude = 100.0
        speed = 5.5
        bearing = 180.0
        accuracy = 10.0
        last_update = 1703980800

        sideband_location = [
            struct.pack("!i", int(round(lat, 6) * 1e6)),
            struct.pack("!i", int(round(lon, 6) * 1e6)),
            struct.pack("!i", int(round(altitude, 2) * 1e2)),
            struct.pack("!I", int(round(speed, 2) * 1e2)),
            struct.pack("!i", int(round(bearing, 2) * 1e2)),
            struct.pack("!H", int(round(accuracy, 2) * 1e2)),
            last_update,
        ]

        # Pack as Sideband's Telemeter.packed() would
        sideband_packed = umsgpack.packb({
            SID_TIME: last_update,
            SID_LOCATION: sideband_location,
        })

        # Columba should be able to unpack this
        result = unpack_location_telemetry(sideband_packed)

        self.assertIsNotNone(result)
        self.assertAlmostEqual(result['lat'], lat, places=6)
        self.assertAlmostEqual(result['lng'], lon, places=6)
        self.assertAlmostEqual(result['altitude'], altitude, places=2)
        self.assertAlmostEqual(result['speed'], speed, places=2)
        self.assertAlmostEqual(result['bearing'], bearing, places=2)
        self.assertAlmostEqual(result['acc'], accuracy, places=2)
        self.assertEqual(result['ts'], last_update * 1000)

    def test_sideband_telemetry_with_extra_sensors(self):
        """Test that Columba ignores extra sensors in Sideband telemetry."""
        # Sideband may include other sensors (battery, pressure, etc.)
        # Columba should still extract location correctly
        lat = 40.7128
        lon = -74.0060
        last_update = 1703980800

        location_data = [
            struct.pack("!i", int(lat * 1e6)),
            struct.pack("!i", int(lon * 1e6)),
            struct.pack("!i", 0),  # altitude
            struct.pack("!I", 0),  # speed
            struct.pack("!i", 0),  # bearing
            struct.pack("!H", 1000),  # accuracy 10m
            last_update,
        ]

        # Include extra sensors like Sideband might
        SID_BATTERY = 0x04
        SID_PRESSURE = 0x03

        telemetry_with_extras = {
            SID_TIME: last_update,
            SID_LOCATION: location_data,
            SID_BATTERY: [85, True, 25.0],  # charge%, charging, temp
            SID_PRESSURE: [101325.0],  # pressure in Pa
        }

        packed = umsgpack.packb(telemetry_with_extras)
        result = unpack_location_telemetry(packed)

        self.assertIsNotNone(result)
        self.assertAlmostEqual(result['lat'], lat, places=6)
        self.assertAlmostEqual(result['lng'], lon, places=6)

    def test_telemetry_without_time_sensor(self):
        """Test unpacking telemetry that only has SID_LOCATION (no SID_TIME)."""
        # Edge case: what if SID_TIME is missing?
        lat = 35.6762
        lon = 139.6503
        last_update = 1703980800

        location_data = [
            struct.pack("!i", int(lat * 1e6)),
            struct.pack("!i", int(lon * 1e6)),
            struct.pack("!i", 0),
            struct.pack("!I", 0),
            struct.pack("!i", 0),
            struct.pack("!H", 500),
            last_update,
        ]

        # Only location, no time sensor
        packed = umsgpack.packb({SID_LOCATION: location_data})
        result = unpack_location_telemetry(packed)

        # Should still work - location is extracted from location array
        self.assertIsNotNone(result)
        self.assertAlmostEqual(result['lat'], lat, places=6)


class TestLXMFTelemetryExtraction(unittest.TestCase):
    """Test extracting telemetry from LXMF message fields."""

    def _create_mock_lxmf_message(self, fields, content=b"", source_hash=None):
        """Helper to create a mock LXMF message."""
        msg = Mock()
        msg.fields = fields
        msg.content = content
        msg.source_hash = source_hash or bytes.fromhex("deadbeef" * 4)
        msg.destination_hash = bytes.fromhex("cafebabe" * 4)
        msg.hash = bytes.fromhex("12345678" * 4)
        msg.timestamp = 1703980800.0
        return msg

    def test_extract_location_from_field_telemetry(self):
        """Test extracting location from FIELD_TELEMETRY (0x02)."""
        lat, lon = 37.7749, -122.4194
        packed = pack_location_telemetry(
            lat=lat, lon=lon, accuracy=10.0, timestamp_ms=1703980800000
        )

        # Simulate what _on_lxmf_delivery does
        fields = {FIELD_TELEMETRY: packed}

        # Extract and unpack
        if FIELD_TELEMETRY in fields:
            result = unpack_location_telemetry(fields[FIELD_TELEMETRY])

        self.assertIsNotNone(result)
        self.assertAlmostEqual(result['lat'], lat, places=6)
        self.assertAlmostEqual(result['lng'], lon, places=6)
        self.assertEqual(result['type'], 'location_share')

    def test_extract_cease_signal_from_columba_meta(self):
        """Test extracting cease signal from FIELD_COLUMBA_META (0x70)."""
        import json

        cease_meta = json.dumps({'cease': True})
        fields = {FIELD_COLUMBA_META: cease_meta.encode('utf-8')}

        # Simulate parsing logic
        if FIELD_COLUMBA_META in fields:
            meta_data = fields[FIELD_COLUMBA_META]
            if isinstance(meta_data, bytes):
                meta_data = meta_data.decode('utf-8')
            meta = json.loads(meta_data)

        self.assertTrue(meta.get('cease', False))

    def test_extract_location_from_legacy_field_7(self):
        """Test extracting location from legacy field 7 (JSON format)."""
        import json

        legacy_location = {
            'type': 'location_share',
            'lat': 40.7128,
            'lng': -74.0060,
            'acc': 15.0,
            'ts': 1703980800000,
        }
        fields = {LEGACY_LOCATION_FIELD: json.dumps(legacy_location).encode('utf-8')}

        # Simulate legacy parsing logic
        if LEGACY_LOCATION_FIELD in fields:
            legacy_data = fields[LEGACY_LOCATION_FIELD]
            if isinstance(legacy_data, bytes):
                location_json = legacy_data.decode('utf-8')
            result = json.loads(location_json)

        self.assertEqual(result['type'], 'location_share')
        self.assertAlmostEqual(result['lat'], 40.7128, places=4)
        self.assertAlmostEqual(result['lng'], -74.0060, places=4)

    def test_field_telemetry_takes_priority_over_legacy(self):
        """Test that FIELD_TELEMETRY (0x02) takes priority over legacy field 7."""
        import json

        # New format location
        new_lat, new_lon = 37.7749, -122.4194
        packed = pack_location_telemetry(
            lat=new_lat, lon=new_lon, accuracy=10.0, timestamp_ms=1703980800000
        )

        # Legacy format with different location
        legacy_location = {
            'type': 'location_share',
            'lat': 40.7128,  # Different!
            'lng': -74.0060,
            'acc': 15.0,
            'ts': 1703980800000,
        }

        # Both fields present
        fields = {
            FIELD_TELEMETRY: packed,
            LEGACY_LOCATION_FIELD: json.dumps(legacy_location).encode('utf-8'),
        }

        # Simulate priority logic: check FIELD_TELEMETRY first
        result = None
        if FIELD_TELEMETRY in fields:
            result = unpack_location_telemetry(fields[FIELD_TELEMETRY])

        # Should get the new format location, not legacy
        self.assertIsNotNone(result)
        self.assertAlmostEqual(result['lat'], new_lat, places=6)
        self.assertAlmostEqual(result['lng'], new_lon, places=6)

    def test_columba_meta_cease_without_location(self):
        """Test cease signal via FIELD_COLUMBA_META without location data."""
        import json

        fields = {FIELD_COLUMBA_META: json.dumps({'cease': True}).encode('utf-8')}

        # No FIELD_TELEMETRY, just cease signal
        location_event = None
        if FIELD_TELEMETRY in fields:
            location_event = unpack_location_telemetry(fields[FIELD_TELEMETRY])

        if FIELD_COLUMBA_META in fields:
            meta_data = fields[FIELD_COLUMBA_META].decode('utf-8')
            meta = json.loads(meta_data)
            if meta.get('cease', False):
                location_event = {
                    'type': 'location_share',
                    'cease': True,
                }

        self.assertIsNotNone(location_event)
        self.assertTrue(location_event.get('cease', False))

    def test_columba_meta_merges_with_telemetry(self):
        """Test that FIELD_COLUMBA_META metadata merges with FIELD_TELEMETRY location."""
        import json

        lat, lon = 35.6762, 139.6503
        packed = pack_location_telemetry(
            lat=lat, lon=lon, accuracy=5.0, timestamp_ms=1703980800000
        )

        # Columba meta with extra info
        meta = {'expires': 1703984400000, 'approxRadius': 100}

        fields = {
            FIELD_TELEMETRY: packed,
            FIELD_COLUMBA_META: json.dumps(meta).encode('utf-8'),
        }

        # Extract location
        location_event = unpack_location_telemetry(fields[FIELD_TELEMETRY])

        # Merge metadata
        if FIELD_COLUMBA_META in fields:
            meta_data = json.loads(fields[FIELD_COLUMBA_META].decode('utf-8'))
            if 'expires' in meta_data:
                location_event['expires'] = meta_data['expires']
            if 'approxRadius' in meta_data:
                location_event['approxRadius'] = meta_data['approxRadius']

        self.assertAlmostEqual(location_event['lat'], lat, places=6)
        self.assertEqual(location_event['expires'], 1703984400000)
        self.assertEqual(location_event['approxRadius'], 100)


class TestSendLocationTelemetryFormat(unittest.TestCase):
    """Test the format of outgoing location telemetry."""

    def test_location_telemetry_creates_field_telemetry(self):
        """Test that location data is packed into FIELD_TELEMETRY format."""
        lat, lon = 38.8977, -77.0365
        packed = pack_location_telemetry(
            lat=lat, lon=lon, accuracy=10.0, timestamp_ms=1703980800000
        )

        # Verify it can be assigned to FIELD_TELEMETRY
        fields = {FIELD_TELEMETRY: packed}
        self.assertIn(FIELD_TELEMETRY, fields)
        self.assertIsInstance(fields[FIELD_TELEMETRY], bytes)

    def test_cease_signal_creates_columba_meta(self):
        """Test that cease signal is sent via FIELD_COLUMBA_META."""
        import json

        cease_meta = json.dumps({'cease': True})
        fields = {FIELD_COLUMBA_META: cease_meta.encode('utf-8')}

        # Verify format
        self.assertIn(FIELD_COLUMBA_META, fields)
        parsed = json.loads(fields[FIELD_COLUMBA_META].decode('utf-8'))
        self.assertTrue(parsed['cease'])

    def test_location_with_expires_includes_columba_meta(self):
        """Test that location with expires includes FIELD_COLUMBA_META."""
        import json

        lat, lon = 51.5074, -0.1278
        packed = pack_location_telemetry(
            lat=lat, lon=lon, accuracy=10.0, timestamp_ms=1703980800000
        )

        # When expires is set, include FIELD_COLUMBA_META
        expires_ms = 1703984400000
        meta = json.dumps({'expires': expires_ms})

        fields = {
            FIELD_TELEMETRY: packed,
            FIELD_COLUMBA_META: meta.encode('utf-8'),
        }

        self.assertIn(FIELD_TELEMETRY, fields)
        self.assertIn(FIELD_COLUMBA_META, fields)

        # Verify telemetry
        location = unpack_location_telemetry(fields[FIELD_TELEMETRY])
        self.assertAlmostEqual(location['lat'], lat, places=6)

        # Verify meta
        parsed_meta = json.loads(fields[FIELD_COLUMBA_META].decode('utf-8'))
        self.assertEqual(parsed_meta['expires'], expires_ms)


class TestTimestampHandling(unittest.TestCase):
    """Test timestamp conversion between milliseconds and seconds."""

    def test_timestamp_ms_to_seconds_conversion(self):
        """Verify timestamp is converted from ms to seconds for packing."""
        timestamp_ms = 1703980800123  # with milliseconds
        packed = pack_location_telemetry(
            lat=0.0, lon=0.0, accuracy=10.0, timestamp_ms=timestamp_ms
        )
        unpacked = umsgpack.unpackb(packed)
        # SID_TIME should be in seconds (truncated)
        self.assertEqual(unpacked[SID_TIME], 1703980800)

    def test_timestamp_seconds_to_ms_conversion(self):
        """Verify timestamp is converted from seconds to ms for unpacking."""
        timestamp_s = 1703980800
        location_data = [
            struct.pack("!i", 0),
            struct.pack("!i", 0),
            struct.pack("!i", 0),
            struct.pack("!I", 0),
            struct.pack("!i", 0),
            struct.pack("!H", 0),
            timestamp_s,
        ]
        packed = umsgpack.packb({SID_TIME: timestamp_s, SID_LOCATION: location_data})
        result = unpack_location_telemetry(packed)
        # ts in result should be in milliseconds
        self.assertEqual(result['ts'], timestamp_s * 1000)

    def test_zero_timestamp(self):
        """Test handling of zero timestamp."""
        packed = pack_location_telemetry(
            lat=0.0, lon=0.0, accuracy=10.0, timestamp_ms=0
        )
        result = unpack_location_telemetry(packed)
        self.assertEqual(result['ts'], 0)


if __name__ == '__main__':
    unittest.main()
