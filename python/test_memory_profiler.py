"""
Test suite for Memory Profiler module.

Tests tracemalloc-based memory profiling functionality.
"""

import sys
import os
import unittest
from unittest.mock import Mock, patch, MagicMock
import threading
import time

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock logging_utils before importing memory_profiler
mock_logging = MagicMock()
sys.modules['logging_utils'] = mock_logging
mock_logging.log_info = Mock()
mock_logging.log_warning = Mock()
mock_logging.log_debug = Mock()

import memory_profiler
import importlib

# Reload to pick up mocks
importlib.reload(memory_profiler)


class TestMemoryProfilerStartStop(unittest.TestCase):
    """Test start/stop profiling functionality."""

    def setUp(self):
        """Reset module state before each test."""
        memory_profiler.stop_profiling()
        memory_profiler._profiling_active = False
        memory_profiler._baseline_snapshot = None
        memory_profiler._snapshot_timer = None
        mock_logging.log_info.reset_mock()
        mock_logging.log_warning.reset_mock()
        mock_logging.log_debug.reset_mock()

    def tearDown(self):
        """Clean up after each test."""
        memory_profiler.stop_profiling()

    def test_start_profiling_activates(self):
        """start_profiling should activate profiling and create baseline."""
        memory_profiler.start_profiling(nframes=5)

        self.assertTrue(memory_profiler._profiling_active)
        self.assertIsNotNone(memory_profiler._baseline_snapshot)
        mock_logging.log_info.assert_called()

    def test_start_profiling_twice_warns(self):
        """Starting profiling twice should warn and not restart."""
        memory_profiler.start_profiling()
        memory_profiler.start_profiling()

        mock_logging.log_warning.assert_called()
        self.assertIn("Already profiling", str(mock_logging.log_warning.call_args))

    def test_stop_profiling_deactivates(self):
        """stop_profiling should deactivate and clear state."""
        memory_profiler.start_profiling()
        memory_profiler.stop_profiling()

        self.assertFalse(memory_profiler._profiling_active)
        self.assertIsNone(memory_profiler._baseline_snapshot)

    def test_stop_profiling_when_not_active(self):
        """stop_profiling when not active should log debug."""
        memory_profiler.stop_profiling()

        mock_logging.log_debug.assert_called()


class TestMemoryStats(unittest.TestCase):
    """Test memory statistics retrieval."""

    def setUp(self):
        memory_profiler.stop_profiling()
        mock_logging.log_info.reset_mock()

    def tearDown(self):
        memory_profiler.stop_profiling()

    def test_get_memory_stats_when_inactive(self):
        """get_memory_stats should return inactive state when not profiling."""
        stats = memory_profiler.get_memory_stats()

        self.assertFalse(stats["profiling_active"])
        self.assertEqual(stats["current_mb"], 0.0)
        self.assertEqual(stats["peak_mb"], 0.0)

    def test_get_memory_stats_when_active(self):
        """get_memory_stats should return real values when profiling."""
        memory_profiler.start_profiling()

        # Allocate some memory to ensure non-zero stats
        data = [i for i in range(10000)]

        stats = memory_profiler.get_memory_stats()

        self.assertTrue(stats["profiling_active"])
        self.assertGreaterEqual(stats["current_mb"], 0.0)
        self.assertGreaterEqual(stats["peak_mb"], 0.0)

        del data  # cleanup


class TestSnapshot(unittest.TestCase):
    """Test snapshot functionality."""

    def setUp(self):
        memory_profiler.stop_profiling()
        mock_logging.log_info.reset_mock()
        mock_logging.log_debug.reset_mock()

    def tearDown(self):
        memory_profiler.stop_profiling()

    def test_take_snapshot_when_inactive(self):
        """take_snapshot should skip when not profiling."""
        memory_profiler.take_snapshot()

        mock_logging.log_debug.assert_called()
        self.assertIn("not active", str(mock_logging.log_debug.call_args))

    def test_take_snapshot_logs_growth(self):
        """take_snapshot should log memory growth."""
        memory_profiler.start_profiling()

        # Allocate memory to create growth
        data = [bytearray(1000) for _ in range(100)]

        memory_profiler.take_snapshot()

        # Should have logged something
        self.assertTrue(mock_logging.log_info.called)

        del data


class TestPeriodicSnapshots(unittest.TestCase):
    """Test periodic snapshot scheduling."""

    def setUp(self):
        memory_profiler.stop_profiling()
        mock_logging.log_info.reset_mock()
        mock_logging.log_warning.reset_mock()

    def tearDown(self):
        memory_profiler.stop_profiling()

    def test_schedule_when_not_profiling(self):
        """schedule_periodic_snapshots should warn when not profiling."""
        memory_profiler.schedule_periodic_snapshots(interval_seconds=1)

        mock_logging.log_warning.assert_called()
        self.assertIn("not active", str(mock_logging.log_warning.call_args))

    def test_schedule_creates_timer(self):
        """schedule_periodic_snapshots should create a timer."""
        memory_profiler.start_profiling()
        memory_profiler.schedule_periodic_snapshots(interval_seconds=60)

        self.assertIsNotNone(memory_profiler._snapshot_timer)
        self.assertTrue(memory_profiler._snapshot_timer.daemon)

    def test_timer_callback_reschedules(self):
        """Timer callback should reschedule when profiling active."""
        memory_profiler.start_profiling()

        # Manually call callback
        memory_profiler._snapshot_timer_callback(interval_seconds=60)

        # Should have created new timer
        self.assertIsNotNone(memory_profiler._snapshot_timer)

    def test_timer_callback_stops_when_inactive(self):
        """Timer callback should not reschedule when profiling stopped."""
        memory_profiler.start_profiling()
        memory_profiler._profiling_active = False

        # Manually call callback
        memory_profiler._snapshot_timer_callback(interval_seconds=60)

        # Should NOT have created new timer
        self.assertIsNone(memory_profiler._snapshot_timer)


class TestThreadSafety(unittest.TestCase):
    """Test thread safety of profiler operations."""

    def setUp(self):
        memory_profiler.stop_profiling()

    def tearDown(self):
        memory_profiler.stop_profiling()

    def test_concurrent_start_stop(self):
        """Concurrent start/stop should not raise exceptions."""
        errors = []

        def start_stop():
            try:
                for _ in range(10):
                    memory_profiler.start_profiling()
                    time.sleep(0.001)
                    memory_profiler.stop_profiling()
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=start_stop) for _ in range(3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(errors, [], f"Errors occurred: {errors}")


if __name__ == '__main__':
    unittest.main()
