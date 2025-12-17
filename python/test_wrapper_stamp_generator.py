"""
Test suite for ReticulumWrapper stamp generator callback functionality.

Tests the set_stamp_generator_callback method which allows native Kotlin
stamp generation to bypass Python multiprocessing issues on Android.
"""

import sys
import os
import unittest
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


class TestSetStampGeneratorCallback(unittest.TestCase):
    """Test set_stamp_generator_callback method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()
        self.wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Enable Reticulum
        reticulum_wrapper.RETICULUM_AVAILABLE = True
        self.wrapper.initialized = True

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_set_stamp_generator_callback_stores_callback(self):
        """Test that callback is stored in instance variable"""
        mock_callback = Mock()

        self.wrapper.set_stamp_generator_callback(mock_callback)

        self.assertEqual(self.wrapper.kotlin_stamp_generator_callback, mock_callback)

    def test_set_stamp_generator_callback_registers_with_lxstamper(self):
        """Test that callback is registered with LXMF LXStamper"""
        mock_callback = Mock()

        # Get the mocked LXMF module
        mock_lxmf = sys.modules['LXMF']
        mock_lxstamper = MagicMock()
        mock_lxmf.LXStamper = mock_lxstamper

        self.wrapper.set_stamp_generator_callback(mock_callback)

        # Verify LXStamper.set_external_generator was called with our callback
        mock_lxstamper.set_external_generator.assert_called_once_with(mock_callback)

    def test_set_stamp_generator_callback_handles_import_error(self):
        """Test graceful handling when LXMF import fails"""
        mock_callback = Mock()

        # Make LXMF import raise an exception
        with patch.dict(sys.modules, {'LXMF': None}):
            # This should not raise, just log the error
            try:
                self.wrapper.set_stamp_generator_callback(mock_callback)
            except Exception:
                self.fail("set_stamp_generator_callback raised exception on import error")

        # Callback should still be stored locally
        self.assertEqual(self.wrapper.kotlin_stamp_generator_callback, mock_callback)

    def test_set_stamp_generator_callback_handles_registration_error(self):
        """Test graceful handling when set_external_generator fails"""
        mock_callback = Mock()

        # Get the mocked LXMF module and make set_external_generator raise
        mock_lxmf = sys.modules['LXMF']
        mock_lxstamper = MagicMock()
        mock_lxstamper.set_external_generator.side_effect = Exception("Registration failed")
        mock_lxmf.LXStamper = mock_lxstamper

        # This should not raise, just log the error
        try:
            self.wrapper.set_stamp_generator_callback(mock_callback)
        except Exception:
            self.fail("set_stamp_generator_callback raised exception on registration error")

        # Callback should still be stored locally
        self.assertEqual(self.wrapper.kotlin_stamp_generator_callback, mock_callback)

    def test_set_stamp_generator_callback_with_none(self):
        """Test setting callback to None (clearing)"""
        # First set a callback
        self.wrapper.kotlin_stamp_generator_callback = Mock()

        # Get the mocked LXMF module
        mock_lxmf = sys.modules['LXMF']
        mock_lxstamper = MagicMock()
        mock_lxmf.LXStamper = mock_lxstamper

        self.wrapper.set_stamp_generator_callback(None)

        # Verify callback is cleared
        self.assertIsNone(self.wrapper.kotlin_stamp_generator_callback)

        # Verify LXStamper was called with None
        mock_lxstamper.set_external_generator.assert_called_once_with(None)


if __name__ == '__main__':
    unittest.main()
