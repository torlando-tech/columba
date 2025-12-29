"""
Test suite for ReticulumWrapper heartbeat and process health monitoring

Tests the heartbeat thread that updates a timestamp every second for
Kotlin health checks, and the global exception handler for crash logging.
"""

import sys
import os
import unittest
import time
import tempfile
import shutil
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestHeartbeatInitialization(unittest.TestCase):
    """Test heartbeat state initialization"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_heartbeat_timestamp_initialized_to_zero(self):
        """Test that heartbeat timestamp is initialized to 0.0"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.assertEqual(wrapper._heartbeat_timestamp, 0.0)

    def test_heartbeat_thread_initialized_to_none(self):
        """Test that heartbeat thread is initialized to None"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        self.assertIsNone(wrapper._heartbeat_thread)


class TestGetHeartbeat(unittest.TestCase):
    """Test get_heartbeat method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_heartbeat_returns_zero_initially(self):
        """Test that get_heartbeat returns 0.0 before heartbeat thread starts"""
        result = self.wrapper.get_heartbeat()
        self.assertEqual(result, 0.0)

    def test_get_heartbeat_returns_timestamp(self):
        """Test that get_heartbeat returns the internal timestamp"""
        test_time = 1234567890.123
        self.wrapper._heartbeat_timestamp = test_time

        result = self.wrapper.get_heartbeat()

        self.assertEqual(result, test_time)

    def test_get_heartbeat_returns_float(self):
        """Test that get_heartbeat returns a float"""
        result = self.wrapper.get_heartbeat()
        self.assertIsInstance(result, float)


class TestStartHeartbeatThread(unittest.TestCase):
    """Test _start_heartbeat_thread method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        # Stop heartbeat thread if running
        self.wrapper.initialized = False
        if self.wrapper._heartbeat_thread and self.wrapper._heartbeat_thread.is_alive():
            self.wrapper._heartbeat_thread.join(timeout=2)
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_start_heartbeat_thread_creates_thread(self):
        """Test that _start_heartbeat_thread creates a thread"""
        self.wrapper.initialized = True

        self.wrapper._start_heartbeat_thread()

        self.assertIsNotNone(self.wrapper._heartbeat_thread)

    def test_start_heartbeat_thread_starts_daemon(self):
        """Test that the heartbeat thread is a daemon thread"""
        self.wrapper.initialized = True

        self.wrapper._start_heartbeat_thread()

        self.assertTrue(self.wrapper._heartbeat_thread.daemon)

    def test_start_heartbeat_thread_is_alive(self):
        """Test that the heartbeat thread is running after start"""
        self.wrapper.initialized = True

        self.wrapper._start_heartbeat_thread()

        self.assertTrue(self.wrapper._heartbeat_thread.is_alive())

    def test_start_heartbeat_thread_updates_timestamp(self):
        """Test that the heartbeat thread updates the timestamp"""
        self.wrapper.initialized = True
        initial_timestamp = self.wrapper._heartbeat_timestamp

        self.wrapper._start_heartbeat_thread()
        # Wait a bit for the thread to update
        time.sleep(0.1)

        self.assertGreater(self.wrapper._heartbeat_timestamp, initial_timestamp)

    def test_start_heartbeat_thread_does_not_duplicate(self):
        """Test that calling _start_heartbeat_thread twice doesn't create duplicate threads"""
        self.wrapper.initialized = True

        self.wrapper._start_heartbeat_thread()
        first_thread = self.wrapper._heartbeat_thread

        self.wrapper._start_heartbeat_thread()
        second_thread = self.wrapper._heartbeat_thread

        # Should be the same thread (not replaced)
        self.assertIs(first_thread, second_thread)


class TestHeartbeatLoop(unittest.TestCase):
    """Test _heartbeat_loop method"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        self.wrapper.initialized = False
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_heartbeat_loop_exits_when_not_initialized(self):
        """Test that _heartbeat_loop exits immediately when not initialized"""
        self.wrapper.initialized = False

        # Should return immediately without updating timestamp
        self.wrapper._heartbeat_loop()

        # Timestamp should still be 0 (no updates)
        self.assertEqual(self.wrapper._heartbeat_timestamp, 0.0)

    def test_heartbeat_loop_updates_timestamp_when_initialized(self):
        """Test that _heartbeat_loop updates timestamp when initialized"""
        self.wrapper.initialized = True
        initial_time = time.time()

        # Run in a thread that we'll stop
        import threading
        def run_loop():
            self.wrapper._heartbeat_loop()

        thread = threading.Thread(target=run_loop, daemon=True)
        thread.start()

        # Wait for at least one update
        time.sleep(0.1)

        # Stop the loop
        self.wrapper.initialized = False
        thread.join(timeout=2)

        # Timestamp should have been updated
        self.assertGreater(self.wrapper._heartbeat_timestamp, initial_time - 1)


class TestHeartbeatIntegration(unittest.TestCase):
    """Integration tests for heartbeat functionality"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

    def tearDown(self):
        """Clean up test fixtures"""
        self.wrapper.initialized = False
        if self.wrapper._heartbeat_thread and self.wrapper._heartbeat_thread.is_alive():
            self.wrapper._heartbeat_thread.join(timeout=2)
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_heartbeat_age_calculation(self):
        """Test that heartbeat age can be calculated correctly"""
        self.wrapper.initialized = True
        self.wrapper._start_heartbeat_thread()

        # Wait a bit
        time.sleep(0.1)

        heartbeat = self.wrapper.get_heartbeat()
        now = time.time()
        age = now - heartbeat

        # Age should be small (less than 1 second since heartbeat updates every second)
        self.assertLess(age, 1.0)

    def test_heartbeat_stops_when_initialized_false(self):
        """Test that heartbeat thread stops when initialized is set to False"""
        self.wrapper.initialized = True
        self.wrapper._start_heartbeat_thread()

        # Verify thread is running
        self.assertTrue(self.wrapper._heartbeat_thread.is_alive())

        # Stop the wrapper
        self.wrapper.initialized = False

        # Wait for thread to stop
        self.wrapper._heartbeat_thread.join(timeout=3)

        # Thread should have stopped
        self.assertFalse(self.wrapper._heartbeat_thread.is_alive())


class TestGlobalExceptionHandler(unittest.TestCase):
    """Test global exception handler"""

    def test_exception_handler_is_installed(self):
        """Test that the global exception handler is installed"""
        self.assertEqual(sys.excepthook, reticulum_wrapper._global_exception_handler)

    def test_exception_handler_passes_keyboard_interrupt(self):
        """Test that KeyboardInterrupt passes through to default handler"""
        with patch('sys.__excepthook__') as mock_default_hook:
            exc_type = KeyboardInterrupt
            exc_value = KeyboardInterrupt("Test")
            exc_tb = None

            reticulum_wrapper._global_exception_handler(exc_type, exc_value, exc_tb)

            mock_default_hook.assert_called_once_with(exc_type, exc_value, exc_tb)

    def test_exception_handler_passes_system_exit(self):
        """Test that SystemExit passes through to default handler"""
        with patch('sys.__excepthook__') as mock_default_hook:
            exc_type = SystemExit
            exc_value = SystemExit(0)
            exc_tb = None

            reticulum_wrapper._global_exception_handler(exc_type, exc_value, exc_tb)

            mock_default_hook.assert_called_once_with(exc_type, exc_value, exc_tb)

    @patch('reticulum_wrapper.log_error')
    def test_exception_handler_logs_other_exceptions(self, mock_log_error):
        """Test that other exceptions are logged"""
        exc_type = ValueError
        exc_value = ValueError("Test error")

        try:
            raise exc_value
        except ValueError:
            import traceback
            exc_tb = sys.exc_info()[2]

            reticulum_wrapper._global_exception_handler(exc_type, exc_value, exc_tb)

            # Should have logged the exception
            self.assertTrue(mock_log_error.called)
            # Should have called with GLOBAL tag
            self.assertEqual(mock_log_error.call_args_list[0][0][0], "GLOBAL")

    @patch('builtins.print')
    @patch('reticulum_wrapper.log_error')
    def test_exception_handler_prints_to_stderr(self, mock_log_error, mock_print):
        """Test that exceptions are also printed to stderr"""
        exc_type = RuntimeError
        exc_value = RuntimeError("Test runtime error")

        try:
            raise exc_value
        except RuntimeError:
            exc_tb = sys.exc_info()[2]

            reticulum_wrapper._global_exception_handler(exc_type, exc_value, exc_tb)

            # Should have printed to stderr
            self.assertTrue(mock_print.called)
            # Check stderr was used
            call_kwargs = mock_print.call_args_list[0][1]
            self.assertEqual(call_kwargs.get('file'), sys.stderr)


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
