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


if __name__ == '__main__':
    unittest.main()
