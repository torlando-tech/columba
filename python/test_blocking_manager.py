"""
Test suite for BlockingManager peer blocking and blackhole functionality.

Tests LXMF-level blocking (destination hash) and Reticulum transport-level
blackholing (identity hash).
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

from blocking_manager import BlockingManager


class TestBlockDestination(unittest.TestCase):
    """Test LXMF-level destination blocking."""

    def setUp(self):
        self.mock_router = Mock()
        self.mock_reticulum = Mock()
        self.manager = BlockingManager(self.mock_router, self.mock_reticulum)
        self.test_hash = "a1b2c3d4e5f6a7b8" * 2  # 32 hex chars

    def test_block_destination_success(self):
        result = self.manager.block_destination(self.test_hash)
        self.assertTrue(result["success"])
        self.mock_router.ignore_destination.assert_called_once_with(
            bytes.fromhex(self.test_hash)
        )

    def test_block_destination_router_none(self):
        manager = BlockingManager(None, self.mock_reticulum)
        result = manager.block_destination(self.test_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "Router not initialized")

    def test_block_destination_invalid_hex(self):
        result = self.manager.block_destination("not_valid_hex!")
        self.assertFalse(result["success"])
        self.assertIn("error", result)

    def test_block_destination_router_exception(self):
        self.mock_router.ignore_destination.side_effect = Exception("Router error")
        result = self.manager.block_destination(self.test_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "Router error")


class TestUnblockDestination(unittest.TestCase):
    """Test LXMF-level destination unblocking."""

    def setUp(self):
        self.mock_router = Mock()
        self.mock_reticulum = Mock()
        self.manager = BlockingManager(self.mock_router, self.mock_reticulum)
        self.test_hash = "a1b2c3d4e5f6a7b8" * 2

    def test_unblock_destination_success(self):
        result = self.manager.unblock_destination(self.test_hash)
        self.assertTrue(result["success"])
        self.mock_router.unignore_destination.assert_called_once_with(
            bytes.fromhex(self.test_hash)
        )

    def test_unblock_destination_router_none(self):
        manager = BlockingManager(None, self.mock_reticulum)
        result = manager.unblock_destination(self.test_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "Router not initialized")

    def test_unblock_destination_exception(self):
        self.mock_router.unignore_destination.side_effect = Exception("fail")
        result = self.manager.unblock_destination(self.test_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "fail")


class TestRestoreBlockedDestinations(unittest.TestCase):
    """Test startup restore of LXMF blocked destinations from Kotlin DB."""

    def setUp(self):
        self.mock_router = Mock()
        self.mock_reticulum = Mock()
        self.manager = BlockingManager(self.mock_router, self.mock_reticulum)

    def test_restore_empty_list(self):
        result = self.manager.restore_blocked_destinations([])
        self.assertTrue(result["success"])
        self.assertEqual(result["restored_count"], 0)
        self.mock_router.ignore_destination.assert_not_called()

    def test_restore_multiple_hashes(self):
        hashes = ["a1b2c3d4e5f6a7b8" * 2, "1122334455667788" * 2]
        result = self.manager.restore_blocked_destinations(hashes)
        self.assertTrue(result["success"])
        self.assertEqual(result["restored_count"], 2)
        self.assertEqual(self.mock_router.ignore_destination.call_count, 2)

    def test_restore_router_none(self):
        manager = BlockingManager(None, self.mock_reticulum)
        result = manager.restore_blocked_destinations(["aabb" * 8])
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "Router not initialized")

    def test_restore_invalid_hex_in_list(self):
        """A bad hash should be skipped, not abort the entire restore."""
        result = self.manager.restore_blocked_destinations(["not_hex!"])
        self.assertTrue(result["success"])
        self.assertEqual(result["restored_count"], 0)
        self.assertEqual(result["errors"], 1)

    def test_restore_partial_failure(self):
        """One bad hash should not prevent restoring the rest."""
        good_hash = "a1b2c3d4e5f6a7b8" * 2
        hashes = [good_hash, "not_hex!", "1122334455667788" * 2]
        result = self.manager.restore_blocked_destinations(hashes)
        self.assertTrue(result["success"])
        self.assertEqual(result["restored_count"], 2)
        self.assertEqual(result["errors"], 1)


class TestBlackholeIdentity(unittest.TestCase):
    """Test Reticulum transport-level identity blackholing."""

    def setUp(self):
        self.mock_router = Mock()
        self.mock_reticulum = Mock()
        self.manager = BlockingManager(self.mock_router, self.mock_reticulum)
        self.test_identity_hash = "ff" * 16  # 32 hex chars

    def test_blackhole_identity_success(self):
        self.mock_reticulum.blackhole_identity.return_value = True
        result = self.manager.blackhole_identity(self.test_identity_hash)
        self.assertTrue(result["success"])
        self.mock_reticulum.blackhole_identity.assert_called_once_with(
            bytes.fromhex(self.test_identity_hash)
        )

    def test_blackhole_identity_returns_false(self):
        self.mock_reticulum.blackhole_identity.return_value = False
        result = self.manager.blackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])

    def test_blackhole_identity_returns_none(self):
        """None return should be treated as failure, not success."""
        self.mock_reticulum.blackhole_identity.return_value = None
        result = self.manager.blackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])

    def test_blackhole_identity_reticulum_none(self):
        manager = BlockingManager(self.mock_router, None)
        result = manager.blackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "Reticulum not initialized")

    def test_blackhole_identity_exception(self):
        self.mock_reticulum.blackhole_identity.side_effect = Exception("transport error")
        result = self.manager.blackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "transport error")


class TestUnblackholeIdentity(unittest.TestCase):
    """Test lifting Reticulum transport-level blackhole."""

    def setUp(self):
        self.mock_router = Mock()
        self.mock_reticulum = Mock()
        self.manager = BlockingManager(self.mock_router, self.mock_reticulum)
        self.test_identity_hash = "ff" * 16

    def test_unblackhole_identity_success(self):
        self.mock_reticulum.unblackhole_identity.return_value = True
        result = self.manager.unblackhole_identity(self.test_identity_hash)
        self.assertTrue(result["success"])
        self.mock_reticulum.unblackhole_identity.assert_called_once_with(
            bytes.fromhex(self.test_identity_hash)
        )

    def test_unblackhole_identity_returns_false(self):
        self.mock_reticulum.unblackhole_identity.return_value = False
        result = self.manager.unblackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])

    def test_unblackhole_identity_returns_none(self):
        """None return should be treated as failure."""
        self.mock_reticulum.unblackhole_identity.return_value = None
        result = self.manager.unblackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])

    def test_unblackhole_identity_reticulum_none(self):
        manager = BlockingManager(self.mock_router, None)
        result = manager.unblackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "Reticulum not initialized")

    def test_unblackhole_identity_exception(self):
        self.mock_reticulum.unblackhole_identity.side_effect = Exception("fail")
        result = self.manager.unblackhole_identity(self.test_identity_hash)
        self.assertFalse(result["success"])


class TestIsTransportEnabled(unittest.TestCase):
    """Test transport status check."""

    def setUp(self):
        self.mock_router = Mock()
        self.mock_reticulum = Mock()
        self.manager = BlockingManager(self.mock_router, self.mock_reticulum)

    @patch('blocking_manager.RNS')
    def test_transport_enabled_true(self, mock_rns):
        mock_rns.Reticulum.transport_enabled.return_value = True
        result = self.manager.is_transport_enabled()
        self.assertTrue(result["success"])
        self.assertTrue(result["enabled"])

    @patch('blocking_manager.RNS')
    def test_transport_enabled_false(self, mock_rns):
        mock_rns.Reticulum.transport_enabled.return_value = False
        result = self.manager.is_transport_enabled()
        self.assertTrue(result["success"])
        self.assertFalse(result["enabled"])

    @patch('blocking_manager.RNS_AVAILABLE', False)
    def test_transport_rns_not_available(self):
        result = self.manager.is_transport_enabled()
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "RNS not available")

    @patch('blocking_manager.RNS')
    def test_transport_enabled_exception(self, mock_rns):
        mock_rns.Reticulum.transport_enabled.side_effect = Exception("rns error")
        result = self.manager.is_transport_enabled()
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "rns error")


if __name__ == '__main__':
    unittest.main()
