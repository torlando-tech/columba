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
    pack_location_telemetry,
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


if __name__ == '__main__':
    unittest.main()
