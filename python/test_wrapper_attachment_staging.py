"""
Test suite for ReticulumWrapper attachment staging functionality.

Tests the staging file pipeline for large attachments:
- _write_attachment_staging() direct method tests
- Field 5 (file attachments) large → staging path serialization
- Field 6/7 (image/audio) large → staging path serialization
- Small attachment inline hex serialization (control cases)
"""

import sys
import os
import json
import time
import unittest
import tempfile
import shutil
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock LXMF with message state constants before importing
mock_lxmf_module = MagicMock()
mock_lxmf_module.LXMessage.OPPORTUNISTIC = 0x01
mock_lxmf_module.LXMessage.DIRECT = 0x02
mock_lxmf_module.LXMessage.PROPAGATED = 0x03
mock_lxmf_module.LXMessage.SENT = 0x04
mock_lxmf_module.LXMessage.DELIVERED = 0x05
mock_lxmf_module.LXMessage.FAILED = 0x06

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = mock_lxmf_module

# Now import after mocking
import reticulum_wrapper


def _make_lxmf_message(fields=None, content=b"Hello test"):
    """Create a minimal mock LXMF message that passes all guards in _on_lxmf_delivery."""
    msg = Mock()
    msg.source_hash = bytes.fromhex('aabbccdd' * 4)
    msg.destination_hash = bytes.fromhex('11223344' * 4)
    msg.hash = bytes.fromhex('deadbeef' * 4)
    msg.content = content
    msg.timestamp = time.time()
    msg.fields = fields if fields is not None else {}
    # Avoid MagicMock auto-attributes being misinterpreted
    msg.receiving_interface = None
    msg.receiving_hops = None
    msg._columba_hops = None
    msg._columba_interface = None
    msg._columba_rssi = None
    msg._columba_snr = None
    return msg


class TestWriteAttachmentStaging(unittest.TestCase):
    """Test _write_attachment_staging() directly."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    def test_write_staging_creates_file_with_correct_content(self):
        """_write_attachment_staging writes bytes to staging dir and returns path."""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        data = b'\x89PNG\r\n\x1a\n' + b'\x00' * 100  # fake PNG header + padding

        result_path = wrapper._write_attachment_staging("abc123", "f5_0", data)

        self.assertTrue(os.path.isfile(result_path))
        with open(result_path, 'rb') as f:
            self.assertEqual(f.read(), data)

    def test_write_staging_file_name_format(self):
        """Staging file is named {msg_hash}_{field_id}.bin inside cache/attachment_staging."""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        result_path = wrapper._write_attachment_staging("msghash", "f6", b'\x00' * 10)

        self.assertTrue(result_path.endswith("msghash_f6.bin"))
        self.assertIn("attachment_staging", result_path)

    def test_write_staging_creates_directory(self):
        """Staging directory is created if it doesn't exist."""
        # Use a unique nested dir to avoid cross-test contamination
        nested_dir = os.path.join(self.temp_dir, "unique_subdir")
        os.makedirs(nested_dir)
        wrapper = reticulum_wrapper.ReticulumWrapper(nested_dir)
        expected_dir = os.path.join(self.temp_dir, "cache", "attachment_staging")
        self.assertFalse(os.path.exists(expected_dir))

        wrapper._write_attachment_staging("hash1", "f5_0", b'\x01\x02\x03')

        self.assertTrue(os.path.isdir(expected_dir))


class TestOnLxmfDeliveryFieldStagingLargeAttachments(unittest.TestCase):
    """Test field serialization with large attachments in _on_lxmf_delivery."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

    def _create_wrapper_with_callback(self):
        """Create a wrapper wired for _on_lxmf_delivery with a capturing callback."""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.initialized = True
        wrapper.router = MagicMock()
        wrapper.router.pending_inbound = []
        wrapper.kotlin_message_received_callback = MagicMock()
        return wrapper

    def _get_callback_fields(self, wrapper):
        """Extract the 'fields' from the JSON passed to kotlin_message_received_callback."""
        call_args = wrapper.kotlin_message_received_callback.call_args
        self.assertIsNotNone(call_args, "Kotlin callback was not invoked")
        event_json = json.loads(call_args[0][0])
        fields_str = event_json.get('fields')
        if fields_str:
            return json.loads(fields_str)
        return None

    @patch('reticulum_wrapper.RNS')
    def test_field5_large_file_uses_staging_path(self, mock_rns):
        """Field 5 file > 2MB gets staging file_path instead of inline hex data."""
        wrapper = self._create_wrapper_with_callback()
        mock_rns.Transport.has_path.return_value = False

        # 3MB file exceeds _MAX_INLINE_ATTACHMENT_BYTES (2MB)
        large_data = b'\xAB' * (3 * 1024 * 1024)
        fields = {5: [("big_photo.jpg", large_data)]}
        msg = _make_lxmf_message(fields=fields)

        wrapper._on_lxmf_delivery(msg)

        fields_parsed = self._get_callback_fields(wrapper)
        self.assertIsNotNone(fields_parsed)
        self.assertIn('5', fields_parsed)
        attachment = fields_parsed['5'][0]
        self.assertIn('file_path', attachment)
        self.assertNotIn('data', attachment)
        self.assertEqual(attachment['filename'], 'big_photo.jpg')
        self.assertEqual(attachment['size'], len(large_data))
        # Verify the staging file actually exists
        self.assertTrue(os.path.isfile(attachment['file_path']))

    @patch('reticulum_wrapper.RNS')
    def test_field5_small_file_uses_inline_hex(self, mock_rns):
        """Field 5 file <= 2MB gets inline hex data (no staging)."""
        wrapper = self._create_wrapper_with_callback()
        mock_rns.Transport.has_path.return_value = False

        small_data = b'\xCD' * 1024  # 1KB — well under threshold
        fields = {5: [("small.txt", small_data)]}
        msg = _make_lxmf_message(fields=fields)

        wrapper._on_lxmf_delivery(msg)

        fields_parsed = self._get_callback_fields(wrapper)
        self.assertIsNotNone(fields_parsed)
        attachment = fields_parsed['5'][0]
        self.assertIn('data', attachment)
        self.assertNotIn('file_path', attachment)
        self.assertEqual(attachment['data'], small_data.hex())

    @patch('reticulum_wrapper.RNS')
    def test_field6_large_image_uses_staging_path(self, mock_rns):
        """Field 6 image > 2MB gets [mime, None, staging_path] instead of [mime, hex]."""
        wrapper = self._create_wrapper_with_callback()
        mock_rns.Transport.has_path.return_value = False

        large_image = b'\xFF\xD8\xFF\xE0' + b'\x00' * (3 * 1024 * 1024)
        fields = {6: ["image/jpeg", large_image]}
        msg = _make_lxmf_message(fields=fields)

        wrapper._on_lxmf_delivery(msg)

        fields_parsed = self._get_callback_fields(wrapper)
        self.assertIsNotNone(fields_parsed)
        self.assertIn('6', fields_parsed)
        field6 = fields_parsed['6']
        self.assertEqual(len(field6), 3)
        self.assertEqual(field6[0], "image/jpeg")
        self.assertIsNone(field6[1])
        # field6[2] is the staging file path
        self.assertTrue(os.path.isfile(field6[2]))

    @patch('reticulum_wrapper.RNS')
    def test_field6_small_image_uses_inline_hex(self, mock_rns):
        """Field 6 image <= 2MB gets [mime, hex_data] (no staging)."""
        wrapper = self._create_wrapper_with_callback()
        mock_rns.Transport.has_path.return_value = False

        small_image = b'\xFF\xD8\xFF\xE0' + b'\x00' * 512
        fields = {6: ["image/jpeg", small_image]}
        msg = _make_lxmf_message(fields=fields)

        wrapper._on_lxmf_delivery(msg)

        fields_parsed = self._get_callback_fields(wrapper)
        self.assertIsNotNone(fields_parsed)
        field6 = fields_parsed['6']
        self.assertEqual(len(field6), 2)
        self.assertEqual(field6[0], "image/jpeg")
        self.assertEqual(field6[1], small_image.hex())

    @patch('reticulum_wrapper.RNS')
    def test_field7_large_audio_uses_staging_path(self, mock_rns):
        """Field 7 audio > 2MB gets [mime, None, staging_path]."""
        wrapper = self._create_wrapper_with_callback()
        mock_rns.Transport.has_path.return_value = False

        large_audio = b'\x00' * (3 * 1024 * 1024)
        fields = {7: ["audio/opus", large_audio]}
        msg = _make_lxmf_message(fields=fields)

        wrapper._on_lxmf_delivery(msg)

        fields_parsed = self._get_callback_fields(wrapper)
        self.assertIsNotNone(fields_parsed)
        self.assertIn('7', fields_parsed)
        field7 = fields_parsed['7']
        self.assertEqual(len(field7), 3)
        self.assertEqual(field7[0], "audio/opus")
        self.assertIsNone(field7[1])
        self.assertTrue(os.path.isfile(field7[2]))


if __name__ == '__main__':
    unittest.main()
