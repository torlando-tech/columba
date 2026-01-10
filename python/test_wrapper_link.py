"""
Test suite for ReticulumWrapper link management methods.

Tests the following link-related methods:
- establish_link: Establish a direct link to a destination
- close_link: Close an active link
- get_link_status: Check if a link is active
- probe_link_speed: Probe link speed to a destination
"""

import sys
import os
import unittest
from unittest.mock import Mock, MagicMock, patch, PropertyMock

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF before importing reticulum_wrapper
sys.modules['RNS'] = MagicMock()
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = MagicMock()

# Now import after mocking
import reticulum_wrapper


class TestEstablishLink(unittest.TestCase):
    """Tests for the establish_link method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_establish_link_returns_error_when_not_initialized(self):
        """Test that establish_link returns error when not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = None

        result = wrapper.establish_link(b'test_dest_hash')

        self.assertFalse(result['success'])
        self.assertFalse(result['link_active'])
        self.assertEqual(result['error'], "Not initialized")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_returns_existing_active_link(self, mock_rns):
        """Test that establish_link returns existing active link from direct_links"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Set up mock router with existing active link
        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        result = wrapper.establish_link(dest_hash)

        self.assertTrue(result['success'])
        self.assertTrue(result['link_active'])
        self.assertEqual(result['establishment_rate_bps'], 10000)
        self.assertTrue(result['already_existed'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_returns_existing_backchannel_link(self, mock_rns):
        """Test that establish_link finds links in backchannel_links"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 5000

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {}
        mock_router.backchannel_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        result = wrapper.establish_link(dest_hash)

        self.assertTrue(result['success'])
        self.assertTrue(result['link_active'])
        self.assertTrue(result['already_existed'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_returns_error_when_identity_not_known(self, mock_rns):
        """Test that establish_link returns error when identity cannot be recalled"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router
        wrapper.identities = {}

        # Mock Identity.recall to return None
        mock_rns.Identity.recall.return_value = None

        dest_hash = b'0123456789abcdef'
        result = wrapper.establish_link(dest_hash)

        self.assertFalse(result['success'])
        self.assertFalse(result['link_active'])
        self.assertEqual(result['error'], "Identity not known")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_uses_local_identity_cache(self, mock_rns):
        """Test that establish_link falls back to local identity cache"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        # Set up local identity cache
        dest_hash = b'0123456789abcdef'
        dest_hash_hex = dest_hash.hex()
        mock_identity = Mock()
        wrapper.identities = {dest_hash_hex: mock_identity}

        # Mock Identity.recall to return None (force fallback to cache)
        mock_rns.Identity.recall.return_value = None

        # Mock destination creation
        mock_dest = Mock()
        mock_dest.hash = dest_hash
        mock_rns.Destination.return_value = mock_dest

        # Mock Transport - no path available
        mock_rns.Transport.has_path.return_value = False

        result = wrapper.establish_link(dest_hash, timeout_seconds=0.1)

        # Should have tried to use the cached identity
        mock_rns.Destination.assert_called()

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_returns_error_when_no_path(self, mock_rns):
        """Test that establish_link returns error when no path is available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        dest_hash = b'0123456789abcdef'
        mock_identity = Mock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = Mock()
        mock_dest.hash = dest_hash
        mock_rns.Destination.return_value = mock_dest

        # No path available
        mock_rns.Transport.has_path.return_value = False

        result = wrapper.establish_link(dest_hash, timeout_seconds=0.1)

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], "No path available")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.time')
    def test_establish_link_creates_new_link_successfully(self, mock_time, mock_rns):
        """Test that establish_link creates a new link when none exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        dest_hash = b'0123456789abcdef'
        mock_identity = Mock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = Mock()
        mock_dest.hash = dest_hash
        mock_rns.Destination.return_value = mock_dest

        # Path available
        mock_rns.Transport.has_path.return_value = True

        # Create mock link that becomes active
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 15000
        mock_rns.Link.return_value = mock_link

        # Mock time to not actually wait
        mock_time.time.side_effect = [0, 0, 0.1]
        mock_time.sleep = Mock()

        result = wrapper.establish_link(dest_hash, timeout_seconds=5)

        self.assertTrue(result['success'])
        self.assertTrue(result['link_active'])
        self.assertEqual(result['establishment_rate_bps'], 15000)
        self.assertFalse(result.get('already_existed', False))

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.time')
    def test_establish_link_timeout(self, mock_time, mock_rns):
        """Test that establish_link returns timeout when link doesn't establish"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        dest_hash = b'0123456789abcdef'
        mock_identity = Mock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = Mock()
        mock_dest.hash = dest_hash
        mock_rns.Destination.return_value = mock_dest
        mock_rns.Transport.has_path.return_value = True

        # Link never becomes active
        mock_link = Mock()
        mock_link.status = Mock()  # Not ACTIVE or CLOSED
        mock_rns.Link.return_value = mock_link

        # Mock time to simulate timeout
        mock_time.time.side_effect = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
        mock_time.sleep = Mock()

        result = wrapper.establish_link(dest_hash, timeout_seconds=5)

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], "Timeout")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    @patch('reticulum_wrapper.time')
    def test_establish_link_handles_link_closed_during_establishment(self, mock_time, mock_rns):
        """Test that establish_link handles link being closed during establishment"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        dest_hash = b'0123456789abcdef'
        mock_identity = Mock()
        mock_rns.Identity.recall.return_value = mock_identity

        mock_dest = Mock()
        mock_dest.hash = dest_hash
        mock_rns.Destination.return_value = mock_dest
        mock_rns.Transport.has_path.return_value = True

        # Link becomes CLOSED
        mock_link = Mock()
        mock_link.status = mock_rns.Link.CLOSED
        mock_rns.Link.return_value = mock_link

        mock_time.time.side_effect = [0, 0.1]
        mock_time.sleep = Mock()

        result = wrapper.establish_link(dest_hash, timeout_seconds=5)

        self.assertFalse(result['success'])
        self.assertEqual(result['error'], "Link closed")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_handles_identity_recall_exception(self, mock_rns):
        """Test that establish_link handles identity recall exceptions gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router
        wrapper.identities = {}

        # Raise exception when recalling identity
        mock_rns.Identity.recall.side_effect = Exception("Test error")

        dest_hash = b'0123456789abcdef'
        result = wrapper.establish_link(dest_hash)

        # Exception is caught, falls back to identity not known
        self.assertFalse(result['success'])
        self.assertEqual(result['error'], "Identity not known")

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_establish_link_handles_destination_exception(self, mock_rns):
        """Test that establish_link handles destination creation exceptions"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_identity = Mock()
        mock_rns.Identity.recall.return_value = mock_identity

        # Raise exception when creating destination
        mock_rns.Destination.side_effect = Exception("Destination error")

        dest_hash = b'0123456789abcdef'
        result = wrapper.establish_link(dest_hash)

        self.assertFalse(result['success'])
        self.assertIn("Destination error", result['error'])


class TestCloseLink(unittest.TestCase):
    """Tests for the close_link method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_close_link_returns_success_when_not_initialized(self):
        """Test that close_link returns success when not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = None

        result = wrapper.close_link(b'test_dest_hash')

        self.assertTrue(result['success'])
        self.assertFalse(result['was_active'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_close_link_returns_success_when_no_link_exists(self, mock_rns):
        """Test that close_link returns success when no link exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        wrapper.router = mock_router
        wrapper.identities = {}

        mock_rns.Identity.recall.return_value = None

        dest_hash = b'0123456789abcdef'
        result = wrapper.close_link(dest_hash)

        self.assertTrue(result['success'])
        self.assertFalse(result['was_active'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_close_link_closes_active_link(self, mock_rns):
        """Test that close_link closes an active link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        result = wrapper.close_link(dest_hash)

        self.assertTrue(result['success'])
        self.assertTrue(result['was_active'])
        mock_link.teardown.assert_called_once()

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_close_link_removes_link_from_direct_links(self, mock_rns):
        """Test that close_link removes link from direct_links dict"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        result = wrapper.close_link(dest_hash)

        self.assertNotIn(dest_hash, mock_router.direct_links)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_close_link_finds_link_via_hex_key(self, mock_rns):
        """Test that close_link finds link when stored with hex string key"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE

        dest_hash = b'0123456789abcdef'
        dest_hash_hex = dest_hash.hex()
        mock_router.direct_links = {dest_hash_hex: mock_link}
        wrapper.router = mock_router

        result = wrapper.close_link(dest_hash)

        self.assertTrue(result['success'])
        self.assertTrue(result['was_active'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_close_link_does_not_teardown_inactive_link(self, mock_rns):
        """Test that close_link doesn't call teardown on inactive link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = Mock()  # Not ACTIVE

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        result = wrapper.close_link(dest_hash)

        self.assertTrue(result['success'])
        self.assertFalse(result['was_active'])
        mock_link.teardown.assert_not_called()

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_close_link_handles_exception(self, mock_rns):
        """Test that close_link handles exceptions gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.teardown.side_effect = Exception("Teardown failed")

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        result = wrapper.close_link(dest_hash)

        self.assertFalse(result['success'])
        self.assertIn("Teardown failed", result['error'])


class TestGetLinkStatus(unittest.TestCase):
    """Tests for the get_link_status method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_get_link_status_returns_inactive_when_not_initialized(self):
        """Test that get_link_status returns inactive when not initialized"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = None

        result = wrapper.get_link_status(b'test_dest_hash')

        self.assertFalse(result['active'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_link_status_finds_active_link_in_direct_links(self, mock_rns):
        """Test that get_link_status finds active link in direct_links"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        result = wrapper.get_link_status(dest_hash)

        self.assertTrue(result['active'])
        self.assertEqual(result['establishment_rate_bps'], 10000)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_link_status_finds_active_link_in_backchannel_links(self, mock_rns):
        """Test that get_link_status finds active link in backchannel_links"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 8000

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {}
        mock_router.backchannel_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        result = wrapper.get_link_status(dest_hash)

        self.assertTrue(result['active'])
        self.assertEqual(result['establishment_rate_bps'], 8000)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_link_status_returns_inactive_when_no_link_exists(self, mock_rns):
        """Test that get_link_status returns inactive when no link exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router
        wrapper.identities = {}

        mock_rns.Identity.recall.return_value = None

        dest_hash = b'0123456789abcdef'
        result = wrapper.get_link_status(dest_hash)

        self.assertFalse(result['active'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_link_status_returns_inactive_for_non_active_link(self, mock_rns):
        """Test that get_link_status returns inactive for non-active link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.CLOSED  # Not ACTIVE

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        result = wrapper.get_link_status(dest_hash)

        self.assertFalse(result['active'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_get_link_status_finds_link_via_hex_key(self, mock_rns):
        """Test that get_link_status finds link when stored with hex string key"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 12000

        dest_hash = b'0123456789abcdef'
        dest_hash_hex = dest_hash.hex()
        mock_router.direct_links = {dest_hash_hex: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        result = wrapper.get_link_status(dest_hash)

        self.assertTrue(result['active'])


class TestProbeLinkSpeed(unittest.TestCase):
    """Tests for the probe_link_speed method"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_probe_link_speed_returns_not_initialized(self):
        """Test that probe_link_speed returns not_initialized when not ready"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)
        wrapper.router = None

        result = wrapper.probe_link_speed(b'test_dest_hash')

        self.assertEqual(result['status'], 'not_initialized')
        self.assertIsNone(result['establishment_rate_bps'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_uses_existing_direct_link(self, mock_rns):
        """Test that probe_link_speed uses existing active link from direct_links"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000
        mock_link.get_expected_rate.return_value = 12000
        mock_link.rtt = 0.5
        mock_link.get_mtu.return_value = 1196  # AutoInterface MTU

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=100000)

        result = wrapper.probe_link_speed(dest_hash)

        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['establishment_rate_bps'], 10000)
        self.assertEqual(result['expected_rate_bps'], 12000)
        self.assertEqual(result['rtt_seconds'], 0.5)
        self.assertTrue(result['link_reused'])
        self.assertEqual(result['link_mtu'], 1196)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_uses_existing_backchannel_link(self, mock_rns):
        """Test that probe_link_speed uses existing active link from backchannel_links"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 8000
        mock_link.get_expected_rate.return_value = 9000
        mock_link.rtt = 0.3

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {}
        mock_router.backchannel_links = {dest_hash: mock_link}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 1
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=50000)

        result = wrapper.probe_link_speed(dest_hash)

        self.assertEqual(result['status'], 'success')
        self.assertTrue(result['link_reused'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_propagated_uses_propagation_link(self, mock_rns):
        """Test that probe_link_speed uses propagation link when method is propagated"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 5000
        mock_link.get_expected_rate.return_value = 6000
        mock_link.rtt = 1.0

        mock_router.outbound_propagation_link = mock_link
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        dest_hash = b'0123456789abcdef'
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 3
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=30000)

        result = wrapper.probe_link_speed(dest_hash, delivery_method="propagated")

        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['delivery_method'], 'propagated')
        self.assertTrue(result['link_reused'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_propagated_no_link_returns_success_with_heuristics(self, mock_rns):
        """Test that probe_link_speed returns success with heuristics when propagated but no propagation link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.outbound_propagation_link = None
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = False

        dest_hash = b'0123456789abcdef'
        result = wrapper.probe_link_speed(dest_hash, delivery_method="propagated")

        # Returns success since heuristic data is still valid for compression recommendations
        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['delivery_method'], 'propagated')

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_returns_hops(self, mock_rns):
        """Test that probe_link_speed returns hop count"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000
        mock_link.get_expected_rate.return_value = 12000
        mock_link.rtt = 0.5

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 4
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=100000)

        result = wrapper.probe_link_speed(dest_hash)

        self.assertEqual(result['hops'], 4)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_returns_next_hop_bitrate(self, mock_rns):
        """Test that probe_link_speed returns next hop interface bitrate"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000
        mock_link.get_expected_rate.return_value = 12000
        mock_link.rtt = 0.5

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_interface = Mock()
        mock_interface.bitrate = 115200
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = mock_interface

        result = wrapper.probe_link_speed(dest_hash)

        self.assertEqual(result['next_hop_bitrate_bps'], 115200)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_handles_exception(self, mock_rns):
        """Test that probe_link_speed handles exceptions gracefully"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        # Raise exception
        type(mock_router).direct_links = PropertyMock(side_effect=Exception("Test error"))

        dest_hash = b'0123456789abcdef'
        result = wrapper.probe_link_speed(dest_hash)

        self.assertEqual(result['status'], 'error')
        self.assertIn("Test error", result['error'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_handles_no_identity(self, mock_rns):
        """Test probe_link_speed handles no_identity status from establish_link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router
        wrapper.identities = {}

        mock_rns.Identity.recall.return_value = None
        # Ensure no heuristic data is available so we get true failure status
        mock_rns.Transport.has_path.return_value = False

        dest_hash = b'0123456789abcdef'
        result = wrapper.probe_link_speed(dest_hash, timeout_seconds=0.1)

        # Should get no_identity status when establish_link fails and no heuristics available
        self.assertIn(result['status'], ['no_identity', 'failed', 'error'])


class TestNextHopBitrate(unittest.TestCase):
    """Tests for next_hop_bitrate functionality in probe_link_speed"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

    def tearDown(self):
        """Clean up test fixtures"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_next_hop_bitrate_returns_none_when_no_path(self, mock_rns):
        """Test that next_hop_bitrate returns None when no path exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000
        mock_link.get_expected_rate.return_value = 12000
        mock_link.rtt = 0.5

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = False

        result = wrapper.probe_link_speed(dest_hash)

        self.assertIsNone(result['next_hop_bitrate_bps'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_next_hop_bitrate_handles_missing_bitrate_attr(self, mock_rns):
        """Test that next_hop_bitrate handles interface without bitrate attribute"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 10000
        mock_link.get_expected_rate.return_value = 12000
        mock_link.rtt = 0.5

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        # Interface without bitrate attribute
        mock_interface = Mock(spec=[])  # Empty spec = no attributes
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = mock_interface

        result = wrapper.probe_link_speed(dest_hash)

        self.assertIsNone(result['next_hop_bitrate_bps'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_propagated_uses_backchannel_expected_rate(self, mock_rns):
        """Test that probe_link_speed in propagated mode uses backchannel expected_rate when available"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Propagation link (to relay)
        mock_prop_link = Mock()
        mock_prop_link.status = mock_rns.Link.ACTIVE
        mock_prop_link.get_establishment_rate.return_value = 50000  # 50 kbps to relay
        mock_prop_link.get_expected_rate.return_value = None  # No transfers to relay yet
        mock_prop_link.rtt = 0.5

        # Backchannel link from recipient with measured expected_rate
        mock_backchannel_link = Mock()
        mock_backchannel_link.status = mock_rns.Link.ACTIVE
        mock_backchannel_link.get_expected_rate.return_value = 14711025  # 14.7 Mbps from prior transfer

        mock_router = Mock()
        mock_router.outbound_propagation_link = mock_prop_link
        mock_router.direct_links = {}
        dest_hash = b'0123456789abcdef'
        mock_router.backchannel_links = {dest_hash: mock_backchannel_link}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 3
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=10000000)

        result = wrapper.probe_link_speed(dest_hash, delivery_method="propagated")

        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['delivery_method'], 'propagated')
        # Should use backchannel's expected_rate (14.7 Mbps), not propagation link's None
        self.assertEqual(result['expected_rate_bps'], 14711025)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_propagated_no_prop_link_uses_backchannel_expected_rate(self, mock_rns):
        """Test that probe_link_speed in propagated mode uses backchannel expected_rate when no propagation link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # No propagation link
        mock_router = Mock()
        mock_router.outbound_propagation_link = None
        mock_router.direct_links = {}

        # Backchannel link from recipient with measured expected_rate
        mock_backchannel_link = Mock()
        mock_backchannel_link.status = mock_rns.Link.ACTIVE
        mock_backchannel_link.get_expected_rate.return_value = 8000000  # 8 Mbps from prior transfer

        dest_hash = b'0123456789abcdef'
        mock_router.backchannel_links = {dest_hash: mock_backchannel_link}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = False

        result = wrapper.probe_link_speed(dest_hash, delivery_method="propagated")

        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['delivery_method'], 'propagated')
        # Should include backchannel's expected_rate even without propagation link
        self.assertEqual(result['expected_rate_bps'], 8000000)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_propagated_prefers_backchannel_over_prop_link_expected_rate(self, mock_rns):
        """Test that propagated mode prefers backchannel expected_rate over propagation link expected_rate"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Propagation link with its own expected_rate (transfers to relay)
        mock_prop_link = Mock()
        mock_prop_link.status = mock_rns.Link.ACTIVE
        mock_prop_link.get_establishment_rate.return_value = 50000
        mock_prop_link.get_expected_rate.return_value = 1000000  # 1 Mbps to relay
        mock_prop_link.rtt = 0.5

        # Backchannel link with different expected_rate (actual peer throughput)
        mock_backchannel_link = Mock()
        mock_backchannel_link.status = mock_rns.Link.ACTIVE
        mock_backchannel_link.get_expected_rate.return_value = 10000000  # 10 Mbps with peer

        mock_router = Mock()
        mock_router.outbound_propagation_link = mock_prop_link
        mock_router.direct_links = {}
        dest_hash = b'0123456789abcdef'
        mock_router.backchannel_links = {dest_hash: mock_backchannel_link}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=10000000)

        result = wrapper.probe_link_speed(dest_hash, delivery_method="propagated")

        # Should use backchannel's 10 Mbps, not prop link's 1 Mbps
        self.assertEqual(result['expected_rate_bps'], 10000000)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_propagated_no_backchannel_uses_prop_link_expected_rate(self, mock_rns):
        """Test that propagated mode uses propagation link expected_rate when no backchannel"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Propagation link with expected_rate
        mock_prop_link = Mock()
        mock_prop_link.status = mock_rns.Link.ACTIVE
        mock_prop_link.get_establishment_rate.return_value = 50000
        mock_prop_link.get_expected_rate.return_value = 2000000  # 2 Mbps to relay
        mock_prop_link.rtt = 0.5

        mock_router = Mock()
        mock_router.outbound_propagation_link = mock_prop_link
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}  # No backchannel
        wrapper.router = mock_router

        dest_hash = b'0123456789abcdef'
        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=10000000)

        result = wrapper.probe_link_speed(dest_hash, delivery_method="propagated")

        # Should use prop link's expected_rate when no backchannel
        self.assertEqual(result['expected_rate_bps'], 2000000)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_direct_uses_backchannel_expected_rate(self, mock_rns):
        """Test that direct mode uses backchannel link's expected_rate"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        # Backchannel link from sender with measured expected_rate
        mock_backchannel_link = Mock()
        mock_backchannel_link.status = mock_rns.Link.ACTIVE
        mock_backchannel_link.get_establishment_rate.return_value = 97000  # Low establishment
        mock_backchannel_link.get_expected_rate.return_value = 14711025  # High measured rate
        mock_backchannel_link.rtt = 0.017

        mock_router = Mock()
        mock_router.direct_links = {}  # No direct link (haven't sent yet)
        dest_hash = b'0123456789abcdef'
        mock_router.backchannel_links = {dest_hash: mock_backchannel_link}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=10000000)

        result = wrapper.probe_link_speed(dest_hash, delivery_method="direct")

        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['establishment_rate_bps'], 97000)
        self.assertEqual(result['expected_rate_bps'], 14711025)
        self.assertTrue(result['link_reused'])

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_returns_link_mtu(self, mock_rns):
        """Test that probe_link_speed returns link_mtu from the link"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_link = Mock()
        mock_link.status = mock_rns.Link.ACTIVE
        mock_link.get_establishment_rate.return_value = 50000
        mock_link.get_expected_rate.return_value = None
        mock_link.rtt = 0.1
        mock_link.get_mtu.return_value = 1196  # AutoInterface MTU (WiFi/LAN)

        dest_hash = b'0123456789abcdef'
        mock_router.direct_links = {dest_hash: mock_link}
        mock_router.backchannel_links = {}
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 1
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=10000000)

        result = wrapper.probe_link_speed(dest_hash)

        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['link_mtu'], 1196)

    @patch('reticulum_wrapper.RETICULUM_AVAILABLE', True)
    @patch('reticulum_wrapper.RNS')
    def test_probe_link_speed_link_mtu_none_when_no_link(self, mock_rns):
        """Test that link_mtu is None when no active link exists"""
        wrapper = reticulum_wrapper.ReticulumWrapper(self.temp_dir)

        mock_router = Mock()
        mock_router.direct_links = {}
        mock_router.backchannel_links = {}
        mock_router.propagation_node_link = None
        wrapper.router = mock_router

        mock_rns.Transport.has_path.return_value = True
        mock_rns.Transport.hops_to.return_value = 2
        mock_rns.Transport.next_hop_interface.return_value = Mock(bitrate=50000)

        # Mock establish_link to return success but link not found
        with patch.object(wrapper, 'establish_link') as mock_establish:
            mock_establish.return_value = {'success': True, 'establishment_rate_bps': 10000}

            result = wrapper.probe_link_speed(b'0123456789abcdef')

            # Should still succeed (with heuristics) but link_mtu should be None
            self.assertEqual(result['status'], 'success')
            self.assertIsNone(result['link_mtu'])


if __name__ == '__main__':
    unittest.main()
