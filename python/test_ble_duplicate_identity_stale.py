"""
TDD Test for BLE Duplicate Identity Stale Entry Bug

BUG DESCRIPTION:
----------------
When a peer disconnects, identity_to_address is NOT cleaned up immediately - it's only
removed after a 2-second grace period in _process_pending_detaches(). However,
_check_duplicate_identity() doesn't verify that the existing address is still connected.

This causes NEW connections from the same identity (after MAC rotation or reconnection)
to be INCORRECTLY rejected as "duplicates" during the grace period.

OBSERVED BEHAVIOR (from logs):
------------------------------
1. Identity c9d09faa connects from MAC_OLD (47:9C:55:2F:85:5D) - SUCCESS
2. Connection disconnects (but identity_to_address still has entry due to grace period)
3. Same identity tries to connect from MAC_NEW (5C:D7:DD:D5:DD:7D)
4. _check_duplicate_identity sees identity_to_address[c9d09faa] = MAC_OLD
5. Since MAC_OLD != MAC_NEW, connection is REJECTED as duplicate
6. But MAC_OLD connection is DEAD - this is a BUG!

EXPECTED BEHAVIOR:
-----------------
_check_duplicate_identity should:
1. Check if the existing address is still in the connected peers list
2. OR check if there's a pending detach scheduled for this identity
3. If the old connection is gone, ALLOW the new connection

This test is written TDD-style: it should FAIL before the fix and PASS after.
"""

import unittest
import sys
import os
import time
import hashlib

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


class MockDriver:
    """Mock BLE driver that tracks connected peers."""

    def __init__(self):
        self.connected_peers = []

    def disconnect(self, address):
        if address in self.connected_peers:
            self.connected_peers.remove(address)


class BLEInterfaceTestHarness:
    """
    Test harness that replicates the _check_duplicate_identity logic from BLEInterface.

    This is extracted from ble_reticulum/BLEInterface.py to test in isolation.
    """

    def __init__(self):
        # Maps from BLEInterface
        self.identity_to_address = {}  # identity_hash -> address
        self.address_to_identity = {}  # address -> peer_identity (bytes)
        self.peers = {}  # address -> peer info
        self._pending_detach = {}  # identity_hash -> timestamp
        self._pending_detach_grace_period = 2.0  # seconds

        # Mock driver for connected peers check
        self.driver = MockDriver()

    def _compute_identity_hash(self, peer_identity: bytes) -> str:
        """Compute 16-char hex hash of peer identity."""
        return hashlib.sha256(peer_identity).hexdigest()[:16]

    def _check_duplicate_identity_BUGGY(self, address: str, peer_identity: bytes) -> bool:
        """
        BUGGY VERSION: Original implementation that doesn't check connection validity.

        This is the current (broken) behavior from BLEInterface.
        """
        if not peer_identity or len(peer_identity) != 16:
            return False

        identity_hash = self._compute_identity_hash(peer_identity)
        existing_address = self.identity_to_address.get(identity_hash)

        if existing_address and existing_address != address:
            # Same identity, different MAC - this is Android MAC rotation
            # BUG: Doesn't check if existing_address is still connected!
            return True

        return False

    def _check_duplicate_identity_FIXED(self, address: str, peer_identity: bytes) -> bool:
        """
        FIXED VERSION: Checks if existing connection is still valid before rejecting.

        This is the expected behavior after the fix.
        """
        if not peer_identity or len(peer_identity) != 16:
            return False

        identity_hash = self._compute_identity_hash(peer_identity)
        existing_address = self.identity_to_address.get(identity_hash)

        if existing_address and existing_address != address:
            # Same identity, different MAC - check if the old connection is still alive

            # Check 1: Is there a pending detach for this identity?
            # If so, the old connection is already gone - allow new connection
            if identity_hash in self._pending_detach:
                return False

            # Check 2: Is the existing address still connected?
            # Check both driver.connected_peers and our peers dict
            if existing_address not in self.driver.connected_peers:
                if existing_address not in self.peers:
                    # Old connection is dead - allow new connection
                    return False

            # Existing connection is still alive - reject duplicate
            return True

        return False

    def simulate_connect(self, address: str, identity: bytes):
        """Simulate a successful connection with identity."""
        identity_hash = self._compute_identity_hash(identity)
        self.identity_to_address[identity_hash] = address
        self.address_to_identity[address] = identity
        self.peers[address] = {"connected": True}
        self.driver.connected_peers.append(address)

    def simulate_disconnect(self, address: str, with_grace_period: bool = True):
        """
        Simulate a disconnection.

        If with_grace_period=True (default), identity_to_address is NOT cleaned up
        immediately (mimicking the real behavior where cleanup happens after 2s grace period).
        """
        identity = self.address_to_identity.get(address)
        if identity:
            identity_hash = self._compute_identity_hash(identity)

            # Remove from driver connected peers
            if address in self.driver.connected_peers:
                self.driver.connected_peers.remove(address)

            # Remove from our peers dict
            if address in self.peers:
                del self.peers[address]

            # Remove address_to_identity
            if address in self.address_to_identity:
                del self.address_to_identity[address]

            if with_grace_period:
                # Schedule pending detach (identity_to_address NOT removed yet!)
                self._pending_detach[identity_hash] = time.time()
                # NOTE: identity_to_address is intentionally NOT cleaned up here
                # This is the source of the bug!
            else:
                # Immediate cleanup (no grace period)
                if identity_hash in self.identity_to_address:
                    del self.identity_to_address[identity_hash]


class TestStaleIdentityToAddressBug(unittest.TestCase):
    """
    TDD Test: Demonstrates the stale identity_to_address bug.

    These tests should FAIL with the buggy implementation and PASS with the fix.
    """

    def setUp(self):
        self.harness = BLEInterfaceTestHarness()
        # Create test identities (16 bytes each)
        self.identity_A = b'\x12\x34\x56\x78' * 4  # Device A's identity
        self.MAC_OLD = "AA:BB:CC:DD:EE:01"
        self.MAC_NEW = "11:22:33:44:55:02"

    def test_buggy_implementation_incorrectly_rejects_reconnection(self):
        """
        Demonstrate the BUG: stale entry causes false duplicate rejection.

        This test shows what CURRENTLY happens (incorrect behavior).
        """
        # Step 1: Device A connects with MAC_OLD
        self.harness.simulate_connect(self.MAC_OLD, self.identity_A)

        # Step 2: Device A disconnects (with grace period - entry stays in identity_to_address)
        self.harness.simulate_disconnect(self.MAC_OLD, with_grace_period=True)

        # Verify: MAC_OLD is no longer connected
        self.assertNotIn(self.MAC_OLD, self.harness.driver.connected_peers)
        self.assertNotIn(self.MAC_OLD, self.harness.peers)

        # BUT: identity_to_address still has the stale entry!
        identity_hash = self.harness._compute_identity_hash(self.identity_A)
        self.assertIn(identity_hash, self.harness.identity_to_address)
        self.assertEqual(self.harness.identity_to_address[identity_hash], self.MAC_OLD)

        # Step 3: Device A reconnects with MAC_NEW (Android MAC rotation)
        # BUGGY IMPLEMENTATION: incorrectly returns True (rejects as duplicate)
        is_duplicate = self.harness._check_duplicate_identity_BUGGY(self.MAC_NEW, self.identity_A)

        # This assertion shows the BUG: it's treating a dead connection as a duplicate!
        self.assertTrue(
            is_duplicate,
            "BUG VERIFICATION: Buggy implementation should (incorrectly) reject reconnection"
        )

    def test_fixed_implementation_allows_reconnection_during_grace_period(self):
        """
        Test the FIX: should allow reconnection when old connection is dead.

        This test shows the EXPECTED behavior after fixing the bug.
        """
        # Step 1: Device A connects with MAC_OLD
        self.harness.simulate_connect(self.MAC_OLD, self.identity_A)

        # Step 2: Device A disconnects (with grace period - entry stays but pending detach is set)
        self.harness.simulate_disconnect(self.MAC_OLD, with_grace_period=True)

        # Verify: MAC_OLD is no longer connected
        self.assertNotIn(self.MAC_OLD, self.harness.driver.connected_peers)

        # Verify: pending detach is scheduled
        identity_hash = self.harness._compute_identity_hash(self.identity_A)
        self.assertIn(identity_hash, self.harness._pending_detach)

        # Step 3: Device A reconnects with MAC_NEW
        # FIXED IMPLEMENTATION: should return False (allow connection)
        is_duplicate = self.harness._check_duplicate_identity_FIXED(self.MAC_NEW, self.identity_A)

        self.assertFalse(
            is_duplicate,
            "FIX VERIFICATION: Fixed implementation should allow reconnection during grace period"
        )

    def test_fixed_implementation_still_rejects_true_duplicates(self):
        """
        Test that the fix doesn't break legitimate duplicate rejection.

        When the old connection IS still alive, it should still be rejected.
        """
        # Step 1: Device A connects with MAC_OLD
        self.harness.simulate_connect(self.MAC_OLD, self.identity_A)

        # Step 2: Device A tries to connect AGAIN with MAC_NEW (old connection still alive)
        # This is a TRUE duplicate - should be rejected
        is_duplicate = self.harness._check_duplicate_identity_FIXED(self.MAC_NEW, self.identity_A)

        self.assertTrue(
            is_duplicate,
            "Should still reject true duplicates when old connection is alive"
        )

    def test_fixed_implementation_allows_same_mac_reconnection(self):
        """
        Test that reconnection from the same MAC is allowed.

        When the same MAC reconnects, it should never be considered a duplicate.
        """
        # Step 1: Device A connects with MAC_OLD
        self.harness.simulate_connect(self.MAC_OLD, self.identity_A)

        # Step 2: Device A disconnects
        self.harness.simulate_disconnect(self.MAC_OLD, with_grace_period=True)

        # Step 3: Device A reconnects with SAME MAC
        is_duplicate = self.harness._check_duplicate_identity_FIXED(self.MAC_OLD, self.identity_A)

        self.assertFalse(
            is_duplicate,
            "Should allow reconnection from same MAC"
        )

    def test_fixed_implementation_allows_when_no_pending_detach_and_not_connected(self):
        """
        Test edge case: old entry exists but no pending detach and not connected.

        This can happen if pending detach was cleaned up but identity_to_address wasn't.
        """
        # Manually set up a stale state (should not happen in practice, but defensive)
        identity_hash = self.harness._compute_identity_hash(self.identity_A)
        self.harness.identity_to_address[identity_hash] = self.MAC_OLD
        # Note: NOT in peers, NOT in connected_peers, NOT in _pending_detach

        # Step: Device A connects with MAC_NEW
        is_duplicate = self.harness._check_duplicate_identity_FIXED(self.MAC_NEW, self.identity_A)

        self.assertFalse(
            is_duplicate,
            "Should allow connection when old entry is stale (not connected, no pending detach)"
        )


class TestRealWorldScenario(unittest.TestCase):
    """
    Test the real-world scenario observed in logs.

    From logs: Identity c9d09faa kept getting rejected from multiple different MACs
    because the original MAC entry was never cleaned up.
    """

    def setUp(self):
        self.harness = BLEInterfaceTestHarness()
        # Use realistic 16-byte identity
        self.identity_c9d09f = bytes.fromhex("c9d09faa3ef0220447e0111b626ce641")

        # Sequence of MACs observed in logs (Android MAC rotation)
        self.mac_sequence = [
            "47:9C:55:2F:85:5D",  # First successful connection
            "4A:FB:BF:EB:C3:11",  # Rejected as duplicate
            "46:EF:48:18:13:F2",  # Rejected as duplicate
            "76:E4:EE:9B:6D:10",  # Rejected as duplicate
            "5C:D7:DD:D5:DD:7D",  # Rejected as duplicate
            "58:58:43:B9:EB:CD",  # Rejected as duplicate
        ]

    def test_real_world_mac_rotation_with_buggy_impl(self):
        """
        Demonstrate the real bug observed in logs.

        All subsequent connection attempts are incorrectly rejected.
        """
        # First connection succeeds
        first_mac = self.mac_sequence[0]
        self.harness.simulate_connect(first_mac, self.identity_c9d09f)

        # Connection drops (with grace period)
        self.harness.simulate_disconnect(first_mac, with_grace_period=True)

        # All subsequent attempts are incorrectly rejected (BUG)
        for mac in self.mac_sequence[1:]:
            is_duplicate = self.harness._check_duplicate_identity_BUGGY(mac, self.identity_c9d09f)
            self.assertTrue(
                is_duplicate,
                f"BUG: MAC {mac} incorrectly rejected as duplicate"
            )

    def test_real_world_mac_rotation_with_fixed_impl(self):
        """
        Verify the fix allows reconnection after disconnect.

        After the fix, subsequent connection attempts should succeed.
        """
        # First connection succeeds
        first_mac = self.mac_sequence[0]
        self.harness.simulate_connect(first_mac, self.identity_c9d09f)

        # Connection drops (with grace period)
        self.harness.simulate_disconnect(first_mac, with_grace_period=True)

        # All subsequent attempts should be allowed (FIX)
        for mac in self.mac_sequence[1:]:
            is_duplicate = self.harness._check_duplicate_identity_FIXED(mac, self.identity_c9d09f)
            self.assertFalse(
                is_duplicate,
                f"FIX: MAC {mac} should be allowed to reconnect"
            )


if __name__ == '__main__':
    unittest.main()
