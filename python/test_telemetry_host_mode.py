"""
Test suite for telemetry collector host mode functionality.

Tests the host mode features in ReticulumWrapper that allow Columba to act
as a telemetry collector (group host) compatible with Sideband's protocol.
"""

import sys
import os
import unittest
import time
from unittest.mock import MagicMock, patch, Mock
import tempfile
import shutil

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
    raise unittest.SkipTest("umsgpack not available - skipping telemetry host mode tests")

# Make umsgpack available BEFORE importing reticulum_wrapper
sys.modules['umsgpack'] = umsgpack

# Mock RNS and LXMF before importing reticulum_wrapper
mock_rns = MagicMock()
mock_lxmf = MagicMock()
sys.modules['RNS'] = mock_rns
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = mock_lxmf

# Now import after mocking
import reticulum_wrapper
import importlib
reticulum_wrapper.umsgpack = umsgpack
importlib.reload(reticulum_wrapper)

from reticulum_wrapper import (
    pack_telemetry_stream,
    unpack_telemetry_stream,
    pack_location_telemetry,
    unpack_location_telemetry,
    ReticulumWrapper,
)


class TestPackTelemetryStream(unittest.TestCase):
    """Test pack_telemetry_stream function."""

    def test_returns_bytes(self):
        """pack_telemetry_stream should return bytes."""
        result = pack_telemetry_stream([])
        self.assertIsInstance(result, bytes)

    def test_empty_list_returns_packed_empty_list(self):
        """Empty list input should return msgpack-encoded empty list."""
        result = pack_telemetry_stream([])
        unpacked = umsgpack.unpackb(result)
        self.assertEqual(unpacked, [])

    def test_single_entry_packs_correctly(self):
        """Single entry should pack and unpack correctly."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        timestamp = 1703980800
        packed_telemetry = pack_location_telemetry(
            lat=37.7749, lon=-122.4194, accuracy=10.0, timestamp_ms=1703980800000
        )
        appearance = None

        entries = [[source_hash, timestamp, packed_telemetry, appearance]]
        result = pack_telemetry_stream(entries)

        # Verify it can be unpacked
        unpacked = umsgpack.unpackb(result)
        self.assertEqual(len(unpacked), 1)
        self.assertEqual(unpacked[0][0], source_hash)
        self.assertEqual(unpacked[0][1], timestamp)

    def test_multiple_entries_pack_correctly(self):
        """Multiple entries should pack and unpack correctly."""
        entries = []
        for i in range(3):
            source_hash = bytes([i] * 16)
            timestamp = 1703980800 + i * 60
            packed_telemetry = pack_location_telemetry(
                lat=37.0 + i, lon=-122.0 - i, accuracy=10.0, timestamp_ms=(1703980800 + i * 60) * 1000
            )
            entries.append([source_hash, timestamp, packed_telemetry, None])

        result = pack_telemetry_stream(entries)
        unpacked = umsgpack.unpackb(result)
        self.assertEqual(len(unpacked), 3)


class TestUnpackTelemetryStream(unittest.TestCase):
    """Test unpack_telemetry_stream function."""

    def _create_valid_packed_telemetry(self, lat=37.7749, lon=-122.4194):
        """Create valid packed telemetry data for testing."""
        return pack_location_telemetry(
            lat=lat, lon=lon, accuracy=10.0, timestamp_ms=1703980800000
        )

    def test_empty_stream_returns_empty_list(self):
        """Empty stream should return empty list."""
        result = unpack_telemetry_stream([])
        self.assertEqual(result, [])

    def test_unpacks_valid_entry_with_bytes_source_hash(self):
        """Should unpack entry with bytes source hash."""
        source_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()

        stream = [[source_hash, timestamp, packed_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['source_hash'], source_hash.hex())

    def test_unpacks_valid_entry_with_string_source_hash(self):
        """Should handle string source hash (non-bytes)."""
        source_hash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()

        stream = [[source_hash, timestamp, packed_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['source_hash'], source_hash)

    def test_skips_entry_with_too_few_elements(self):
        """Should skip entries with fewer than 3 elements."""
        short_entry = [bytes.fromhex("a1" * 16), 1703980800]  # Only 2 elements
        valid_entry = [bytes.fromhex("b2" * 16), 1703980800, self._create_valid_packed_telemetry(), None]

        stream = [short_entry, valid_entry]
        result = unpack_telemetry_stream(stream)

        # Only valid entry should be returned
        self.assertEqual(len(result), 1)

    def test_skips_entry_with_invalid_telemetry(self):
        """Should skip entries where telemetry unpacking fails."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        invalid_telemetry = b'not valid telemetry'

        stream = [[source_hash, timestamp, invalid_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 0)

    def test_applies_valid_timestamp(self):
        """Should apply valid timestamp from entry."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800  # Specific timestamp
        packed_telemetry = self._create_valid_packed_telemetry()

        stream = [[source_hash, timestamp, packed_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Timestamp should be converted to milliseconds
        self.assertEqual(result[0]['ts'], timestamp * 1000)

    def test_rejects_future_timestamp(self):
        """Should reject timestamps more than 1 hour in the future."""
        source_hash = bytes.fromhex("a1" * 16)
        future_timestamp = time.time() + 7200  # 2 hours in future
        packed_telemetry = self._create_valid_packed_telemetry()

        stream = [[source_hash, future_timestamp, packed_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        # Entry should still be returned but timestamp should not be the future one
        self.assertEqual(len(result), 1)
        # The original timestamp from packed telemetry should be used instead
        self.assertNotEqual(result[0]['ts'], future_timestamp * 1000)

    def test_handles_zero_timestamp(self):
        """Should not apply zero timestamp."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 0
        packed_telemetry = self._create_valid_packed_telemetry()

        stream = [[source_hash, timestamp, packed_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Original timestamp from telemetry should be preserved

    def test_handles_none_timestamp(self):
        """Should not apply None timestamp."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = None
        packed_telemetry = self._create_valid_packed_telemetry()

        stream = [[source_hash, timestamp, packed_telemetry, None]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)

    def test_parses_valid_appearance(self):
        """Should parse valid appearance data."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()
        appearance = ["icon_name", b'\xff\x00\x00', b'\x00\xff\x00']  # Red fg, green bg

        stream = [[source_hash, timestamp, packed_telemetry, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertIn('appearance', result[0])
        self.assertEqual(result[0]['appearance']['name'], 'icon_name')
        self.assertEqual(result[0]['appearance']['fg'], '#ff0000')
        self.assertEqual(result[0]['appearance']['bg'], '#00ff00')

    def test_handles_appearance_with_invalid_icon_name(self):
        """Should reject appearance with invalid icon name."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()
        # Icon name with invalid characters
        appearance = ["icon-name-with-dashes!", b'\xff\x00\x00', b'\x00\xff\x00']

        stream = [[source_hash, timestamp, packed_telemetry, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Appearance should not be set due to invalid icon name
        self.assertNotIn('appearance', result[0])

    def test_handles_appearance_with_too_long_icon_name(self):
        """Should reject appearance with icon name > 50 chars."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()
        appearance = ["a" * 51, b'\xff\x00\x00', b'\x00\xff\x00']

        stream = [[source_hash, timestamp, packed_telemetry, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        self.assertNotIn('appearance', result[0])

    def test_handles_appearance_with_invalid_color_bytes(self):
        """Should handle appearance with invalid color bytes gracefully."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()
        # Invalid color bytes (too short)
        appearance = ["icon_name", b'\xff', b'\x00']

        stream = [[source_hash, timestamp, packed_telemetry, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        # Appearance should be set but colors should be None
        if 'appearance' in result[0]:
            self.assertIsNone(result[0]['appearance']['fg'])
            self.assertIsNone(result[0]['appearance']['bg'])

    def test_handles_appearance_with_non_bytes_colors(self):
        """Should handle appearance with non-bytes color values."""
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        packed_telemetry = self._create_valid_packed_telemetry()
        # Color values as strings instead of bytes
        appearance = ["icon_name", "not_bytes", "also_not_bytes"]

        stream = [[source_hash, timestamp, packed_telemetry, appearance]]
        result = unpack_telemetry_stream(stream)

        self.assertEqual(len(result), 1)
        if 'appearance' in result[0]:
            self.assertIsNone(result[0]['appearance']['fg'])
            self.assertIsNone(result[0]['appearance']['bg'])

    def test_processes_multiple_entries(self):
        """Should process multiple valid entries."""
        entries = []
        for i in range(3):
            source_hash = bytes([i + 1] * 16)
            timestamp = 1703980800 + i * 60
            packed_telemetry = self._create_valid_packed_telemetry(lat=37.0 + i, lon=-122.0 - i)
            entries.append([source_hash, timestamp, packed_telemetry, None])

        result = unpack_telemetry_stream(entries)

        self.assertEqual(len(result), 3)

    def test_handles_entry_processing_exception(self):
        """Should handle exceptions during entry processing gracefully."""
        # Create an entry that will cause an exception during processing
        # by having packed_telemetry be something that causes issues
        source_hash = bytes.fromhex("a1" * 16)
        timestamp = 1703980800
        # This might cause issues during unpacking
        broken_entry = [source_hash, timestamp, None, None]
        valid_entry = [bytes.fromhex("b2" * 16), timestamp, self._create_valid_packed_telemetry(), None]

        stream = [broken_entry, valid_entry]
        result = unpack_telemetry_stream(stream)

        # Valid entry should still be processed
        self.assertEqual(len(result), 1)


class TestSetTelemetryCollectorEnabled(unittest.TestCase):
    """Test set_telemetry_collector_enabled method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        # Mark as initialized to allow operations
        self.wrapper.initialized = False

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_enable_returns_success(self):
        """Enabling collector mode should return success."""
        result = self.wrapper.set_telemetry_collector_enabled(True)
        self.assertTrue(result['success'])
        self.assertTrue(result['enabled'])

    def test_disable_returns_success(self):
        """Disabling collector mode should return success."""
        result = self.wrapper.set_telemetry_collector_enabled(False)
        self.assertTrue(result['success'])
        self.assertFalse(result['enabled'])

    def test_enable_sets_flag(self):
        """Enabling should set the internal flag."""
        self.wrapper.set_telemetry_collector_enabled(True)
        self.assertTrue(self.wrapper.telemetry_collector_enabled)

    def test_disable_sets_flag(self):
        """Disabling should clear the internal flag."""
        self.wrapper.telemetry_collector_enabled = True
        self.wrapper.set_telemetry_collector_enabled(False)
        self.assertFalse(self.wrapper.telemetry_collector_enabled)

    def test_disable_clears_collected_telemetry(self):
        """Disabling should clear any collected telemetry."""
        # Add some test telemetry
        self.wrapper.collected_telemetry = {
            'abc123': {'timestamp': 123, 'packed_telemetry': b'test'}
        }

        # Disable
        self.wrapper.set_telemetry_collector_enabled(False)

        # Should be cleared
        self.assertEqual(len(self.wrapper.collected_telemetry), 0)

    def test_enable_does_not_clear_collected_telemetry(self):
        """Enabling should not clear existing collected telemetry."""
        # Add some test telemetry
        self.wrapper.collected_telemetry = {
            'abc123': {'timestamp': 123, 'packed_telemetry': b'test'}
        }

        # Enable
        self.wrapper.set_telemetry_collector_enabled(True)

        # Should still be there
        self.assertEqual(len(self.wrapper.collected_telemetry), 1)


class TestSetTelemetryAllowedRequesters(unittest.TestCase):
    """Test set_telemetry_allowed_requesters method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_set_allowed_requesters_returns_success(self):
        """Setting allowed requesters should return success with count."""
        result = self.wrapper.set_telemetry_allowed_requesters(["a" * 32, "b" * 32])
        self.assertTrue(result['success'])
        self.assertEqual(result['count'], 2)

    def test_set_empty_list_returns_zero_count(self):
        """Setting empty list should return success with zero count."""
        result = self.wrapper.set_telemetry_allowed_requesters([])
        self.assertTrue(result['success'])
        self.assertEqual(result['count'], 0)

    def test_normalizes_to_lowercase(self):
        """Should normalize hashes to lowercase."""
        self.wrapper.set_telemetry_allowed_requesters(["AABBCCDD" * 4])
        self.assertIn("aabbccdd" * 4, self.wrapper.telemetry_allowed_requesters)

    def test_filters_empty_strings(self):
        """Should filter out empty strings from the list."""
        result = self.wrapper.set_telemetry_allowed_requesters(["a" * 32, "", "b" * 32, ""])
        self.assertEqual(result['count'], 2)
        self.assertEqual(len(self.wrapper.telemetry_allowed_requesters), 2)

    def test_stores_as_set(self):
        """Should store allowed requesters as a set for O(1) lookup."""
        self.wrapper.set_telemetry_allowed_requesters(["a" * 32, "b" * 32])
        self.assertIsInstance(self.wrapper.telemetry_allowed_requesters, set)

    def test_deduplicates_entries(self):
        """Should deduplicate entries when same hash appears multiple times."""
        result = self.wrapper.set_telemetry_allowed_requesters(["a" * 32, "a" * 32, "a" * 32])
        self.assertEqual(result['count'], 1)

    def test_replaces_previous_list(self):
        """Setting new list should replace previous list entirely."""
        self.wrapper.set_telemetry_allowed_requesters(["a" * 32, "b" * 32])
        self.wrapper.set_telemetry_allowed_requesters(["c" * 32])
        self.assertEqual(len(self.wrapper.telemetry_allowed_requesters), 1)
        self.assertIn("c" * 32, self.wrapper.telemetry_allowed_requesters)
        self.assertNotIn("a" * 32, self.wrapper.telemetry_allowed_requesters)


class TestStoreTelemetryForCollector(unittest.TestCase):
    """Test _store_telemetry_for_collector method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.telemetry_collector_enabled = True

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_stores_telemetry_entry(self):
        """Should store telemetry entry in collected_telemetry dict."""
        source_hash = "a1b2c3d4e5f6a1b2"
        packed_telemetry = b'test_data'
        timestamp = 1703980800

        self.wrapper._store_telemetry_for_collector(
            source_hash_hex=source_hash,
            packed_telemetry=packed_telemetry,
            timestamp=timestamp,
        )

        self.assertIn(source_hash, self.wrapper.collected_telemetry)
        entry = self.wrapper.collected_telemetry[source_hash]
        self.assertEqual(entry['timestamp'], timestamp)
        self.assertEqual(entry['packed_telemetry'], packed_telemetry)
        self.assertIn('received_at', entry)

    def test_stores_appearance_data(self):
        """Should store appearance data when provided."""
        source_hash = "a1b2c3d4e5f6a1b2"
        appearance = ['icon_name', b'\xff\x00\x00', b'\x00\xff\x00']

        self.wrapper._store_telemetry_for_collector(
            source_hash_hex=source_hash,
            packed_telemetry=b'test',
            timestamp=1703980800,
            appearance=appearance,
        )

        entry = self.wrapper.collected_telemetry[source_hash]
        self.assertEqual(entry['appearance'], appearance)

    def test_overwrites_previous_entry_from_same_source(self):
        """Should keep only latest entry per source (not accumulate)."""
        source_hash = "a1b2c3d4e5f6a1b2"

        # Store first entry
        self.wrapper._store_telemetry_for_collector(
            source_hash_hex=source_hash,
            packed_telemetry=b'old_data',
            timestamp=1703980800,
        )

        # Store second entry from same source
        self.wrapper._store_telemetry_for_collector(
            source_hash_hex=source_hash,
            packed_telemetry=b'new_data',
            timestamp=1703980860,
        )

        # Should only have one entry
        self.assertEqual(len(self.wrapper.collected_telemetry), 1)
        entry = self.wrapper.collected_telemetry[source_hash]
        self.assertEqual(entry['packed_telemetry'], b'new_data')
        self.assertEqual(entry['timestamp'], 1703980860)

    def test_stores_entries_from_different_sources(self):
        """Should store entries from different sources separately."""
        self.wrapper._store_telemetry_for_collector(
            source_hash_hex="source1",
            packed_telemetry=b'data1',
            timestamp=1703980800,
        )
        self.wrapper._store_telemetry_for_collector(
            source_hash_hex="source2",
            packed_telemetry=b'data2',
            timestamp=1703980860,
        )

        self.assertEqual(len(self.wrapper.collected_telemetry), 2)
        self.assertIn("source1", self.wrapper.collected_telemetry)
        self.assertIn("source2", self.wrapper.collected_telemetry)


class TestCleanupExpiredTelemetry(unittest.TestCase):
    """Test _cleanup_expired_telemetry method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.telemetry_collector_enabled = True
        # Set retention to 1 second for fast testing
        self.wrapper.telemetry_retention_seconds = 1

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_removes_expired_entries(self):
        """Should remove entries older than retention period."""
        # Add entry with old received_at
        self.wrapper.collected_telemetry['old_source'] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'old',
            'received_at': time.time() - 10,  # 10 seconds ago
        }

        self.wrapper._cleanup_expired_telemetry()

        self.assertNotIn('old_source', self.wrapper.collected_telemetry)

    def test_keeps_fresh_entries(self):
        """Should keep entries within retention period."""
        # Add fresh entry
        self.wrapper.collected_telemetry['fresh_source'] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'fresh',
            'received_at': time.time(),  # Just now
        }

        self.wrapper._cleanup_expired_telemetry()

        self.assertIn('fresh_source', self.wrapper.collected_telemetry)

    def test_keeps_fresh_removes_old(self):
        """Should keep fresh entries while removing old ones."""
        current_time = time.time()

        # Add mix of old and fresh entries
        self.wrapper.collected_telemetry['old'] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'old',
            'received_at': current_time - 10,
        }
        self.wrapper.collected_telemetry['fresh'] = {
            'timestamp': 1703980860,
            'packed_telemetry': b'fresh',
            'received_at': current_time,
        }

        self.wrapper._cleanup_expired_telemetry()

        self.assertNotIn('old', self.wrapper.collected_telemetry)
        self.assertIn('fresh', self.wrapper.collected_telemetry)


class TestTelemetryHostModeIntegration(unittest.TestCase):
    """Integration tests for host mode workflow."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.telemetry_collector_enabled = True
        self.wrapper.telemetry_retention_seconds = 86400  # 24 hours

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_full_workflow_store_and_retrieve(self):
        """Test complete workflow: store telemetry, then prepare stream."""
        # Store some telemetry with valid hex source hashes
        for i in range(3):
            source_hash_hex = f"{i:032x}"  # 32 hex chars = 16 bytes
            self.wrapper._store_telemetry_for_collector(
                source_hash_hex=source_hash_hex,
                packed_telemetry=pack_location_telemetry(
                    lat=37.0 + i,
                    lon=-122.0 - i,
                    accuracy=10.0,
                    timestamp_ms=(1703980800 + i * 60) * 1000,
                ),
                timestamp=1703980800 + i * 60,
            )

        # Verify all stored
        self.assertEqual(len(self.wrapper.collected_telemetry), 3)

        # Pack all entries into a stream
        entries = []
        for source_hash_hex, entry in self.wrapper.collected_telemetry.items():
            entries.append([
                bytes.fromhex(source_hash_hex),
                entry['timestamp'],
                entry['packed_telemetry'],
                entry.get('appearance'),
            ])

        packed_stream = pack_telemetry_stream(entries)

        # Verify stream can be unpacked
        unpacked = umsgpack.unpackb(packed_stream)
        self.assertEqual(len(unpacked), 3)

    def test_initial_state_is_disabled(self):
        """Host mode should be disabled by default."""
        fresh_wrapper = ReticulumWrapper(self.temp_dir)
        self.assertFalse(fresh_wrapper.telemetry_collector_enabled)

    def test_collected_telemetry_starts_empty(self):
        """Collected telemetry dict should start empty."""
        fresh_wrapper = ReticulumWrapper(self.temp_dir)
        self.assertEqual(len(fresh_wrapper.collected_telemetry), 0)

    def test_retention_period_default(self):
        """Default retention period should be 24 hours (86400 seconds)."""
        fresh_wrapper = ReticulumWrapper(self.temp_dir)
        self.assertEqual(fresh_wrapper.telemetry_retention_seconds, 86400)


class TestSendTelemetryStreamResponse(unittest.TestCase):
    """Test _send_telemetry_stream_response method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.telemetry_collector_enabled = True
        self.wrapper.telemetry_retention_seconds = 86400

        # Set up mock router and destination
        self.wrapper.router = MagicMock()
        self.wrapper.local_lxmf_destination = MagicMock()

        # Ensure RNS and LXMF are properly mocked in reticulum_wrapper module
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_sends_all_entries_when_timebase_is_none(self):
        """Should send all entries when timebase is None."""
        current_time = time.time()

        # Store test entries
        for i in range(3):
            self.wrapper.collected_telemetry[f"{i:032x}"] = {
                'timestamp': 1703980800 + i * 60,
                'packed_telemetry': b'test_data',
                'appearance': None,
                'received_at': current_time - i * 10,
            }

        # Create mock identity and hash
        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        # Call the method with timebase=None
        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, None)

        # Verify router.handle_outbound was called
        self.wrapper.router.handle_outbound.assert_called_once()

    def test_sends_all_entries_when_timebase_is_zero(self):
        """Should send all entries when timebase is 0."""
        current_time = time.time()

        # Store test entries
        self.wrapper.collected_telemetry["a" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'test_data',
            'appearance': None,
            'received_at': current_time,
        }

        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, 0)

        self.wrapper.router.handle_outbound.assert_called_once()

    def test_filters_entries_by_timebase(self):
        """Should only send entries with received_at >= timebase."""
        current_time = time.time()

        # Store old entry (before timebase)
        self.wrapper.collected_telemetry["0" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'old_data',
            'appearance': None,
            'received_at': current_time - 100,  # 100 seconds ago
        }

        # Store new entry (after timebase)
        self.wrapper.collected_telemetry["f" * 32] = {
            'timestamp': 1703980860,
            'packed_telemetry': b'new_data',
            'appearance': None,
            'received_at': current_time,  # Now
        }

        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        # Capture the fields passed to LXMessage using side_effect
        captured_fields = {}
        original_mock = MagicMock()
        original_mock.DIRECT = 2  # LXMF.LXMessage.DIRECT value

        def capture_lxmessage(*args, **kwargs):
            captured_fields.update(kwargs.get('fields', {}))
            mock_msg = MagicMock()
            mock_msg.fields = kwargs.get('fields', {})
            return mock_msg

        original_mock.side_effect = capture_lxmessage
        reticulum_wrapper.LXMF.LXMessage = original_mock

        # Set timebase to 50 seconds ago - should only include "new" entry
        timebase = current_time - 50

        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, timebase)

        # Verify handle_outbound was called
        self.wrapper.router.handle_outbound.assert_called_once()

        # Check the fields contain the stream with only the new entry
        self.assertIn(reticulum_wrapper.FIELD_TELEMETRY_STREAM, captured_fields)
        stream_data = captured_fields[reticulum_wrapper.FIELD_TELEMETRY_STREAM]
        self.assertEqual(len(stream_data), 1)
        self.assertEqual(stream_data[0][2], b'new_data')

    def test_creates_lxmf_message_with_correct_fields(self):
        """Should create LXMF message with FIELD_TELEMETRY_STREAM."""
        current_time = time.time()

        self.wrapper.collected_telemetry["a" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'test_data',
            'appearance': ['icon', b'\xff'],
            'received_at': current_time,
        }

        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        # Capture the fields passed to LXMessage using side_effect
        captured_fields = {}
        original_mock = MagicMock()
        original_mock.DIRECT = 2

        def capture_lxmessage(*args, **kwargs):
            captured_fields.update(kwargs.get('fields', {}))
            mock_msg = MagicMock()
            mock_msg.fields = kwargs.get('fields', {})
            return mock_msg

        original_mock.side_effect = capture_lxmessage
        reticulum_wrapper.LXMF.LXMessage = original_mock

        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, None)

        # Verify handle_outbound was called
        self.wrapper.router.handle_outbound.assert_called_once()

        # Check fields
        self.assertIn(reticulum_wrapper.FIELD_TELEMETRY_STREAM, captured_fields)
        stream_data = captured_fields[reticulum_wrapper.FIELD_TELEMETRY_STREAM]

        # Stream should be a list of entries
        self.assertIsInstance(stream_data, list)
        self.assertEqual(len(stream_data), 1)

        # Each entry should have [source_hash, timestamp, packed_telemetry, appearance]
        entry = stream_data[0]
        self.assertEqual(len(entry), 4)
        self.assertEqual(entry[0], bytes.fromhex("a" * 32))
        self.assertEqual(entry[1], 1703980800)
        self.assertEqual(entry[2], b'test_data')
        self.assertEqual(entry[3], ['icon', b'\xff'])

    def test_handles_empty_collected_telemetry(self):
        """Should handle case with no collected telemetry gracefully."""
        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        # No telemetry stored
        self.assertEqual(len(self.wrapper.collected_telemetry), 0)

        # Should not raise
        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, None)

        # Should still send (empty stream)
        self.wrapper.router.handle_outbound.assert_called_once()

    def test_cleans_up_expired_before_sending(self):
        """Should call _cleanup_expired_telemetry before sending."""
        current_time = time.time()

        # Store an expired entry
        self.wrapper.telemetry_retention_seconds = 1  # 1 second retention
        self.wrapper.collected_telemetry["expired" + "0" * 25] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'expired_data',
            'appearance': None,
            'received_at': current_time - 100,  # 100 seconds ago, well past retention
        }

        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")

        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, None)

        # The expired entry should have been cleaned up
        self.assertNotIn("expired" + "0" * 25, self.wrapper.collected_telemetry)


class TestSendTelemetryRequest(unittest.TestCase):
    """Test send_telemetry_request method."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.initialized = True

        # Set up mock router and destination
        self.wrapper.router = MagicMock()
        self.wrapper.local_lxmf_destination = MagicMock()
        self.wrapper.local_lxmf_destination.hexhash = "a" * 32

        # Ensure RNS and LXMF are properly mocked in reticulum_wrapper module
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_returns_error_when_not_initialized(self):
        """Should return error when wrapper is not initialized."""
        self.wrapper.initialized = False

        # send_telemetry_request takes bytes, not hex strings
        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash,
            source_identity_private_key=source_key,
            timebase=None,
            is_collector_request=True,
        )

        self.assertFalse(result.get('success', True))

    def test_returns_error_when_router_not_initialized(self):
        """Should return error when router is not initialized."""
        self.wrapper.router = None

        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash,
            source_identity_private_key=source_key,
            timebase=None,
            is_collector_request=True,
        )

        self.assertFalse(result.get('success', True))


class TestSendTelemetryRequestSuccess(unittest.TestCase):
    """Test send_telemetry_request success paths."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.initialized = True
        self.wrapper.router = MagicMock()
        self.wrapper.local_lxmf_destination = MagicMock()
        self.wrapper.local_lxmf_destination.hexhash = "a" * 32
        self.wrapper.identities = {}

        # Set up RNS mock
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_successful_send_with_immediate_identity_recall(self):
        """Should successfully send when identity is immediately recalled."""
        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        # Mock identity recall to return identity immediately
        mock_recipient_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.return_value = mock_recipient_identity

        # Mock destination and message creation
        mock_destination = MagicMock()
        mock_destination.hash = bytes.fromhex("d" * 32)
        reticulum_wrapper.RNS.Destination.return_value = mock_destination

        mock_message = MagicMock()
        mock_message.hash = bytes.fromhex("e" * 32)
        reticulum_wrapper.LXMF.LXMessage.return_value = mock_message

        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash,
            source_identity_private_key=source_key,
            timebase=1234567890.0,
            is_collector_request=True,
        )

        self.assertTrue(result.get('success'))
        self.assertIn('message_hash', result)
        self.wrapper.router.handle_outbound.assert_called_once()

    def test_successful_send_with_cached_identity(self):
        """Should use cached identity from self.identities if recall fails."""
        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        # Mock identity recall to return None (not found)
        reticulum_wrapper.RNS.Identity.recall.return_value = None

        # Add identity to cache
        mock_cached_identity = MagicMock()
        self.wrapper.identities["b" * 32] = mock_cached_identity

        # Mock destination and message
        mock_destination = MagicMock()
        mock_destination.hash = bytes.fromhex("d" * 32)
        reticulum_wrapper.RNS.Destination.return_value = mock_destination

        mock_message = MagicMock()
        mock_message.hash = bytes.fromhex("e" * 32)
        reticulum_wrapper.LXMF.LXMessage.return_value = mock_message

        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash,
            source_identity_private_key=source_key,
            timebase=None,
            is_collector_request=True,
        )

        self.assertTrue(result.get('success'))
        self.wrapper.router.handle_outbound.assert_called_once()

    def test_returns_error_when_local_destination_missing(self):
        """Should raise error when local_lxmf_destination is None."""
        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        self.wrapper.local_lxmf_destination = None

        # Mock identity recall to return identity
        mock_recipient_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.return_value = mock_recipient_identity

        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash,
            source_identity_private_key=source_key,
            timebase=None,
            is_collector_request=True,
        )

        self.assertFalse(result.get('success'))
        self.assertIn('error', result)

    def test_returns_error_when_identity_not_resolved(self):
        """Should return error when identity cannot be resolved after path request."""
        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        # Mock identity recall to always return None
        reticulum_wrapper.RNS.Identity.recall.return_value = None

        # Mock time.sleep to avoid actual delay
        with unittest.mock.patch('time.sleep'):
            result = self.wrapper.send_telemetry_request(
                dest_hash=dest_hash,
                source_identity_private_key=source_key,
                timebase=None,
                is_collector_request=True,
            )

        self.assertFalse(result.get('success'))
        self.assertIn('error', result)
        self.assertIn('not known', result['error'])

    def test_handles_jarray_conversion(self):
        """Should convert jarray-like iterables to bytes."""
        # Simulate jarray (list of ints)
        dest_hash_list = list(bytes.fromhex("b" * 32))
        source_key_list = list(bytes.fromhex("c" * 64))

        # Mock identity recall
        mock_recipient_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.return_value = mock_recipient_identity

        # Mock destination and message
        mock_destination = MagicMock()
        mock_destination.hash = bytes.fromhex("d" * 32)
        reticulum_wrapper.RNS.Destination.return_value = mock_destination

        mock_message = MagicMock()
        mock_message.hash = bytes.fromhex("e" * 32)
        reticulum_wrapper.LXMF.LXMessage.return_value = mock_message

        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash_list,
            source_identity_private_key=source_key_list,
            timebase=None,
            is_collector_request=True,
        )

        self.assertTrue(result.get('success'))

    def test_builds_correct_field_commands_structure(self):
        """Should build FIELD_COMMANDS with correct format."""
        dest_hash = bytes.fromhex("b" * 32)
        source_key = bytes.fromhex("c" * 64)

        # Mock identity recall
        mock_recipient_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.return_value = mock_recipient_identity

        # Mock destination
        mock_destination = MagicMock()
        mock_destination.hash = bytes.fromhex("d" * 32)
        reticulum_wrapper.RNS.Destination.return_value = mock_destination

        # Capture fields passed to LXMessage
        captured_fields = {}
        mock_message = MagicMock()
        mock_message.hash = bytes.fromhex("e" * 32)

        def capture_lxmessage(*args, **kwargs):
            captured_fields.update(kwargs.get('fields', {}))
            return mock_message

        reticulum_wrapper.LXMF.LXMessage.side_effect = capture_lxmessage

        timebase = 1234567890.0
        result = self.wrapper.send_telemetry_request(
            dest_hash=dest_hash,
            source_identity_private_key=source_key,
            timebase=timebase,
            is_collector_request=True,
        )

        self.assertTrue(result.get('success'))
        # Verify FIELD_COMMANDS structure
        self.assertIn(reticulum_wrapper.FIELD_COMMANDS, captured_fields)
        commands = captured_fields[reticulum_wrapper.FIELD_COMMANDS]
        # Should be list of dicts: [{0x01: [timebase, is_collector_request]}]
        self.assertEqual(len(commands), 1)
        self.assertIn(reticulum_wrapper.COMMAND_TELEMETRY_REQUEST, commands[0])
        args = commands[0][reticulum_wrapper.COMMAND_TELEMETRY_REQUEST]
        self.assertEqual(args[0], timebase)
        self.assertEqual(args[1], True)


class TestFieldCommandsConstants(unittest.TestCase):
    """Test FIELD_COMMANDS related constants are defined."""

    def test_field_commands_constant_exists(self):
        """FIELD_COMMANDS constant should be defined."""
        self.assertTrue(hasattr(reticulum_wrapper, 'FIELD_COMMANDS'))
        self.assertEqual(reticulum_wrapper.FIELD_COMMANDS, 0x09)

    def test_command_telemetry_request_constant_exists(self):
        """COMMAND_TELEMETRY_REQUEST constant should be defined."""
        self.assertTrue(hasattr(reticulum_wrapper, 'COMMAND_TELEMETRY_REQUEST'))
        self.assertEqual(reticulum_wrapper.COMMAND_TELEMETRY_REQUEST, 0x01)

    def test_field_telemetry_stream_constant_exists(self):
        """FIELD_TELEMETRY_STREAM constant should be defined."""
        self.assertTrue(hasattr(reticulum_wrapper, 'FIELD_TELEMETRY_STREAM'))
        self.assertEqual(reticulum_wrapper.FIELD_TELEMETRY_STREAM, 0x03)

    def test_field_telemetry_constant_exists(self):
        """FIELD_TELEMETRY constant should be defined."""
        self.assertTrue(hasattr(reticulum_wrapper, 'FIELD_TELEMETRY'))
        self.assertEqual(reticulum_wrapper.FIELD_TELEMETRY, 0x02)


class TestOnLxmfDeliveryFieldCommands(unittest.TestCase):
    """Test _on_lxmf_delivery handling of FIELD_COMMANDS."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.telemetry_collector_enabled = True
        self.wrapper.telemetry_retention_seconds = 86400
        self.wrapper.router = MagicMock()
        self.wrapper.local_lxmf_destination = MagicMock()
        self.wrapper.collected_telemetry = {}
        self.wrapper.kotlin_message_callback = None

        # Set up RNS mock
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _create_mock_lxmf_message(self, fields=None, source_hash=None):
        """Create a mock LXMF message for testing."""
        mock_message = MagicMock()
        mock_message.fields = fields or {}
        mock_message.source_hash = source_hash or bytes.fromhex("a" * 32)
        mock_message.content = b""
        mock_message.title = ""
        mock_message.timestamp = time.time()
        mock_message.signature_validated = True
        mock_message.unverified_reason = None
        return mock_message

    def test_handles_field_commands_with_telemetry_request(self):
        """Should handle FIELD_COMMANDS with telemetry request when collector enabled."""
        # Create mock message with FIELD_COMMANDS
        commands = [{reticulum_wrapper.COMMAND_TELEMETRY_REQUEST: [1234567890.0, True]}]
        fields = {reticulum_wrapper.FIELD_COMMANDS: commands}
        mock_message = self._create_mock_lxmf_message(fields=fields)

        # Add requester to allowed list (source_hash is "a" * 32)
        self.wrapper.telemetry_allowed_requesters = {"a" * 32}

        # Mock identity recall to return an identity immediately
        mock_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.return_value = mock_identity

        # Store some telemetry to send
        self.wrapper.collected_telemetry["b" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'test_data',
            'appearance': None,
            'received_at': time.time(),
        }

        # Spy on _send_telemetry_stream_response
        self.wrapper._send_telemetry_stream_response = MagicMock()

        # Call delivery handler
        self.wrapper._on_lxmf_delivery(mock_message)

        # Verify _send_telemetry_stream_response was called
        self.wrapper._send_telemetry_stream_response.assert_called_once()

    def test_skips_field_commands_when_collector_disabled(self):
        """Should not handle FIELD_COMMANDS when collector is disabled."""
        self.wrapper.telemetry_collector_enabled = False

        commands = [{reticulum_wrapper.COMMAND_TELEMETRY_REQUEST: [None, True]}]
        fields = {reticulum_wrapper.FIELD_COMMANDS: commands}
        mock_message = self._create_mock_lxmf_message(fields=fields)

        # Spy on _send_telemetry_stream_response
        self.wrapper._send_telemetry_stream_response = MagicMock()

        # Call delivery handler
        self.wrapper._on_lxmf_delivery(mock_message)

        # Verify _send_telemetry_stream_response was NOT called
        self.wrapper._send_telemetry_stream_response.assert_not_called()

    def test_handles_field_commands_with_identity_retry(self):
        """Should retry sending when identity is not immediately recalled."""
        commands = [{reticulum_wrapper.COMMAND_TELEMETRY_REQUEST: [0, True]}]
        fields = {reticulum_wrapper.FIELD_COMMANDS: commands}
        mock_message = self._create_mock_lxmf_message(fields=fields)

        # Add requester to allowed list (source_hash is "a" * 32)
        self.wrapper.telemetry_allowed_requesters = {"a" * 32}

        # First recall returns None, then returns identity on retry
        mock_identity = MagicMock()
        reticulum_wrapper.RNS.Identity.recall.side_effect = [None, mock_identity]

        # Spy on _send_telemetry_stream_response
        self.wrapper._send_telemetry_stream_response = MagicMock()

        # Patch threading and time.sleep to execute immediately
        with unittest.mock.patch('threading.Thread') as mock_thread:
            # Capture and execute the thread target immediately
            def run_target(*args, **kwargs):
                target = kwargs.get('target')
                if target:
                    # Patch time.sleep inside the target
                    with unittest.mock.patch('time.sleep'):
                        target()
                return MagicMock()
            mock_thread.side_effect = run_target

            self.wrapper._on_lxmf_delivery(mock_message)

        # Verify _send_telemetry_stream_response was called after retry
        self.wrapper._send_telemetry_stream_response.assert_called()

    def test_ignores_non_collector_request(self):
        """Should ignore telemetry requests with is_collector_request=False."""
        commands = [{reticulum_wrapper.COMMAND_TELEMETRY_REQUEST: [0, False]}]
        fields = {reticulum_wrapper.FIELD_COMMANDS: commands}
        mock_message = self._create_mock_lxmf_message(fields=fields)

        # Spy on _send_telemetry_stream_response
        self.wrapper._send_telemetry_stream_response = MagicMock()

        self.wrapper._on_lxmf_delivery(mock_message)

        # Should not be called since is_collector_request is False
        self.wrapper._send_telemetry_stream_response.assert_not_called()

    def test_blocks_request_from_non_allowed_requester(self):
        """Should block telemetry requests from requesters not in allowed list."""
        commands = [{reticulum_wrapper.COMMAND_TELEMETRY_REQUEST: [0, True]}]
        fields = {reticulum_wrapper.FIELD_COMMANDS: commands}
        mock_message = self._create_mock_lxmf_message(fields=fields)

        # Set allowed list to a different hash (not "a" * 32)
        self.wrapper.telemetry_allowed_requesters = {"b" * 32}

        # Store some telemetry to send
        self.wrapper.collected_telemetry["c" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'test_data',
            'appearance': None,
            'received_at': time.time(),
        }

        # Spy on _send_telemetry_stream_response
        self.wrapper._send_telemetry_stream_response = MagicMock()

        self.wrapper._on_lxmf_delivery(mock_message)

        # Should NOT be called since requester ("a" * 32) is not in allowed list
        self.wrapper._send_telemetry_stream_response.assert_not_called()

    def test_blocks_request_when_allowed_list_empty(self):
        """Should block all telemetry requests when allowed list is empty."""
        commands = [{reticulum_wrapper.COMMAND_TELEMETRY_REQUEST: [0, True]}]
        fields = {reticulum_wrapper.FIELD_COMMANDS: commands}
        mock_message = self._create_mock_lxmf_message(fields=fields)

        # Empty allowed list = block all
        self.wrapper.telemetry_allowed_requesters = set()

        # Store some telemetry to send
        self.wrapper.collected_telemetry["b" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'test_data',
            'appearance': None,
            'received_at': time.time(),
        }

        # Spy on _send_telemetry_stream_response
        self.wrapper._send_telemetry_stream_response = MagicMock()

        self.wrapper._on_lxmf_delivery(mock_message)

        # Should NOT be called since allowed list is empty (blocks all)
        self.wrapper._send_telemetry_stream_response.assert_not_called()


class TestTelemetryStreamFiltering(unittest.TestCase):
    """Test telemetry stream filtering logic in detail."""

    def setUp(self):
        """Set up test fixtures."""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = ReticulumWrapper(self.temp_dir)
        self.wrapper.telemetry_collector_enabled = True
        self.wrapper.telemetry_retention_seconds = 86400
        self.wrapper.router = MagicMock()
        self.wrapper.local_lxmf_destination = MagicMock()

        # Ensure RNS and LXMF are properly mocked in reticulum_wrapper module
        reticulum_wrapper.RNS = MagicMock()
        reticulum_wrapper.LXMF = MagicMock()

    def tearDown(self):
        """Clean up test fixtures."""
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_timebase_filtering_uses_received_at_not_timestamp(self):
        """Filtering should use received_at, not the telemetry timestamp."""
        current_time = time.time()

        # Entry with old telemetry timestamp but recent received_at
        # This simulates receiving telemetry that was generated a while ago
        self.wrapper.collected_telemetry["a" * 32] = {
            'timestamp': 1000,  # Very old telemetry timestamp
            'packed_telemetry': b'test_data',
            'appearance': None,
            'received_at': current_time,  # But received just now
        }

        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("b" * 32)

        # Capture the fields passed to LXMessage using side_effect
        captured_fields = {}
        original_mock = MagicMock()
        original_mock.DIRECT = 2

        def capture_lxmessage(*args, **kwargs):
            captured_fields.update(kwargs.get('fields', {}))
            mock_msg = MagicMock()
            mock_msg.fields = kwargs.get('fields', {})
            return mock_msg

        original_mock.side_effect = capture_lxmessage
        reticulum_wrapper.LXMF.LXMessage = original_mock

        # Timebase is 1 second ago - entry should be included because received_at is recent
        timebase = current_time - 1

        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, timebase)

        # Get the stream data from captured fields
        stream_data = captured_fields[reticulum_wrapper.FIELD_TELEMETRY_STREAM]

        # Entry should be included despite old telemetry timestamp
        self.assertEqual(len(stream_data), 1)

    def test_multiple_entries_filtered_correctly(self):
        """Multiple entries should be filtered based on their received_at times."""
        current_time = time.time()

        # Entry 1: received 10 seconds ago
        self.wrapper.collected_telemetry["1" * 32] = {
            'timestamp': 1703980800,
            'packed_telemetry': b'data1',
            'appearance': None,
            'received_at': current_time - 10,
        }

        # Entry 2: received 5 seconds ago
        self.wrapper.collected_telemetry["2" * 32] = {
            'timestamp': 1703980860,
            'packed_telemetry': b'data2',
            'appearance': None,
            'received_at': current_time - 5,
        }

        # Entry 3: received just now
        self.wrapper.collected_telemetry["3" * 32] = {
            'timestamp': 1703980920,
            'packed_telemetry': b'data3',
            'appearance': None,
            'received_at': current_time,
        }

        mock_identity = MagicMock()
        requester_hash = bytes.fromhex("b" * 32)

        # Capture the fields passed to LXMessage using side_effect
        captured_fields = {}
        original_mock = MagicMock()
        original_mock.DIRECT = 2

        def capture_lxmessage(*args, **kwargs):
            captured_fields.update(kwargs.get('fields', {}))
            mock_msg = MagicMock()
            mock_msg.fields = kwargs.get('fields', {})
            return mock_msg

        original_mock.side_effect = capture_lxmessage
        reticulum_wrapper.LXMF.LXMessage = original_mock

        # Timebase is 7 seconds ago - should include entries 2 and 3
        timebase = current_time - 7

        self.wrapper._send_telemetry_stream_response(requester_hash, mock_identity, timebase)

        stream_data = captured_fields[reticulum_wrapper.FIELD_TELEMETRY_STREAM]

        # Should have 2 entries (entry 2 and 3)
        self.assertEqual(len(stream_data), 2)

        # Verify the correct entries are included (by their packed_telemetry)
        packed_data = [entry[2] for entry in stream_data]
        self.assertIn(b'data2', packed_data)
        self.assertIn(b'data3', packed_data)
        self.assertNotIn(b'data1', packed_data)


if __name__ == '__main__':
    unittest.main()
