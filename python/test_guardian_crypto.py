"""
Test suite for Guardian (parental control) cryptographic utilities.

Tests QR code generation/parsing, command signing/verification,
and the reticulum_wrapper guardian methods.
"""

import sys
import os
import unittest
import time
from unittest.mock import Mock, MagicMock, patch

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS before importing guardian_crypto
mock_rns = MagicMock()

# Create a mock Identity class with realistic behavior
class MockIdentity:
    """Mock RNS Identity with signing capability"""

    def __init__(self, private_key=None):
        self._private_key = private_key or os.urandom(32)
        self._public_key = os.urandom(32)  # Simulated public key
        self.hash = os.urandom(16)  # 16-byte identity hash

    def get_public_key(self):
        return self._public_key

    def sign(self, data):
        # Return a 64-byte "signature" (mock)
        import hashlib
        h = hashlib.sha512(self._private_key + data).digest()
        return h  # 64 bytes

    @classmethod
    def from_file(cls, path):
        return cls()

    def to_file(self, path):
        pass

# Setup mock validation function
def mock_validate(signature, data, public_key):
    """Mock signature validation - always returns True for tests"""
    return True

mock_rns.Identity = MockIdentity
mock_rns.Identity.validate = mock_validate
mock_rns.Destination = MagicMock

sys.modules['RNS'] = mock_rns

# Now import after mocking
import guardian_crypto


class TestGuardianCryptoQR(unittest.TestCase):
    """Test QR code generation and parsing"""

    def test_generate_pairing_qr_data(self):
        """Test generating a pairing QR code"""
        identity = MockIdentity()
        dest_hash = os.urandom(16)

        result = guardian_crypto.generate_pairing_qr_data(identity, dest_hash)

        self.assertIsNotNone(result)
        self.assertIn("destination_hash", result)
        self.assertIn("public_key", result)
        self.assertIn("timestamp", result)
        self.assertIn("signature", result)
        self.assertIn("qr_string", result)

        # Check QR string format
        self.assertTrue(result["qr_string"].startswith("lxmf-guardian://"))

        # Check data matches
        self.assertEqual(result["destination_hash"], dest_hash)
        self.assertEqual(result["public_key"], identity.get_public_key())

    def test_parse_pairing_qr_data(self):
        """Test parsing a pairing QR code"""
        # Generate a QR first
        identity = MockIdentity()
        dest_hash = os.urandom(16)
        gen_result = guardian_crypto.generate_pairing_qr_data(identity, dest_hash)

        # Parse it back
        parsed = guardian_crypto.parse_pairing_qr_data(gen_result["qr_string"])

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["destination_hash"], dest_hash)
        self.assertEqual(parsed["public_key"], identity.get_public_key())
        self.assertEqual(parsed["timestamp"], gen_result["timestamp"])
        self.assertEqual(parsed["signature"], gen_result["signature"])

    def test_parse_invalid_qr_prefix(self):
        """Test parsing QR with wrong prefix"""
        result = guardian_crypto.parse_pairing_qr_data("wrong-prefix://abc:def:123:456")
        self.assertIsNone(result)

    def test_parse_invalid_qr_parts(self):
        """Test parsing QR with wrong number of parts"""
        result = guardian_crypto.parse_pairing_qr_data("lxmf-guardian://abc:def:123")
        self.assertIsNone(result)

    def test_validate_pairing_qr_fresh(self):
        """Test validating a fresh QR code"""
        identity = MockIdentity()
        dest_hash = os.urandom(16)

        gen_result = guardian_crypto.generate_pairing_qr_data(identity, dest_hash)
        parsed = guardian_crypto.parse_pairing_qr_data(gen_result["qr_string"])

        is_valid, error = guardian_crypto.validate_pairing_qr(parsed)
        self.assertTrue(is_valid)
        self.assertEqual(error, "")

    def test_validate_pairing_qr_expired(self):
        """Test validating an expired QR code"""
        identity = MockIdentity()
        dest_hash = os.urandom(16)

        gen_result = guardian_crypto.generate_pairing_qr_data(identity, dest_hash)
        parsed = guardian_crypto.parse_pairing_qr_data(gen_result["qr_string"])

        # Set timestamp to 10 minutes ago
        parsed["timestamp"] = int(time.time() * 1000) - (10 * 60 * 1000)

        is_valid, error = guardian_crypto.validate_pairing_qr(parsed)
        self.assertFalse(is_valid)
        self.assertIn("expired", error.lower())


class TestGuardianCryptoCommands(unittest.TestCase):
    """Test command signing and verification"""

    def test_sign_command(self):
        """Test signing a parental control command"""
        identity = MockIdentity()
        cmd = "LOCK"
        nonce = guardian_crypto.generate_nonce()
        timestamp = int(time.time() * 1000)
        payload = b'{"reason": "bedtime"}'

        signature = guardian_crypto.sign_command(identity, cmd, nonce, timestamp, payload)

        self.assertIsNotNone(signature)
        self.assertEqual(len(signature), 64)  # Ed25519 signature length

    def test_generate_nonce(self):
        """Test nonce generation"""
        nonce1 = guardian_crypto.generate_nonce()
        nonce2 = guardian_crypto.generate_nonce()

        self.assertEqual(len(nonce1), 16)
        self.assertEqual(len(nonce2), 16)
        self.assertNotEqual(nonce1, nonce2)  # Should be random

    def test_verify_command(self):
        """Test command verification"""
        identity = MockIdentity()
        cmd = "UNLOCK"
        nonce = guardian_crypto.generate_nonce()
        timestamp = int(time.time() * 1000)
        payload = b'{}'

        signature = guardian_crypto.sign_command(identity, cmd, nonce, timestamp, payload)

        is_valid = guardian_crypto.verify_command(
            identity.get_public_key(),
            signature,
            cmd,
            nonce,
            timestamp,
            payload
        )
        self.assertTrue(is_valid)

    def test_sign_data(self):
        """Test raw data signing"""
        identity = MockIdentity()
        data = b"test data to sign"

        signature = guardian_crypto.sign_data(identity, data)

        self.assertIsNotNone(signature)
        self.assertEqual(len(signature), 64)


class TestReticulumWrapperGuardian(unittest.TestCase):
    """Test the reticulum_wrapper guardian methods"""

    def setUp(self):
        """Set up test fixtures"""
        import tempfile
        self.temp_dir = tempfile.mkdtemp()

        # Mock LXMF before importing
        mock_lxmf = MagicMock()
        mock_lxmf.LXMRouter = MagicMock
        mock_lxmf.LXMessage = MagicMock
        mock_lxmf.LXMessage.OPPORTUNISTIC = 0x01
        mock_lxmf.LXMessage.DIRECT = 0x02
        mock_lxmf.LXMessage.PROPAGATED = 0x03
        sys.modules['LXMF'] = mock_lxmf

        # Enable RETICULUM_AVAILABLE
        import reticulum_wrapper
        self.original_available = reticulum_wrapper.RETICULUM_AVAILABLE
        reticulum_wrapper.RETICULUM_AVAILABLE = True

    def tearDown(self):
        """Clean up"""
        import reticulum_wrapper
        reticulum_wrapper.RETICULUM_AVAILABLE = self.original_available

        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_guardian_generate_pairing_qr_no_destination(self):
        """Test QR generation when no destination is set"""
        import reticulum_wrapper

        wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)
        wrapper.local_lxmf_destination = None

        result = wrapper.guardian_generate_pairing_qr()

        self.assertFalse(result.get("success", True))
        self.assertIn("error", result)

    def test_guardian_generate_pairing_qr_success(self):
        """Test successful QR generation"""
        import reticulum_wrapper

        # Create wrapper with mocked destination
        wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)

        # Mock destination with identity
        mock_dest = MagicMock()
        mock_dest.identity = MockIdentity()
        mock_dest.hash = os.urandom(16)
        wrapper.local_lxmf_destination = mock_dest

        result = wrapper.guardian_generate_pairing_qr()

        self.assertTrue(result.get("success", False), f"Expected success, got: {result}")
        self.assertIn("qr_string", result)
        self.assertTrue(result["qr_string"].startswith("lxmf-guardian://"))

    def test_guardian_parse_pairing_qr_valid(self):
        """Test parsing a valid QR code"""
        import reticulum_wrapper

        wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)

        # Generate a valid QR string first
        identity = MockIdentity()
        dest_hash = os.urandom(16)
        gen_result = guardian_crypto.generate_pairing_qr_data(identity, dest_hash)

        result = wrapper.guardian_parse_pairing_qr(gen_result["qr_string"])

        self.assertTrue(result.get("success", False))
        self.assertEqual(result["destination_hash"], dest_hash.hex())

    def test_guardian_parse_pairing_qr_invalid(self):
        """Test parsing an invalid QR code"""
        import reticulum_wrapper

        wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)

        result = wrapper.guardian_parse_pairing_qr("invalid-qr-code")

        self.assertFalse(result.get("success", True))


class TestIntegration(unittest.TestCase):
    """Integration tests for the full guardian flow"""

    def test_full_qr_roundtrip(self):
        """Test complete QR generation -> parse -> validate flow"""
        # Simulate parent generating QR
        parent_identity = MockIdentity()
        parent_dest_hash = os.urandom(16)

        qr_result = guardian_crypto.generate_pairing_qr_data(parent_identity, parent_dest_hash)
        self.assertIsNotNone(qr_result)

        # Simulate child scanning QR
        parsed = guardian_crypto.parse_pairing_qr_data(qr_result["qr_string"])
        self.assertIsNotNone(parsed)

        # Child validates QR
        is_valid, error = guardian_crypto.validate_pairing_qr(parsed)
        self.assertTrue(is_valid, f"Validation failed: {error}")

        # Child stores parent's destination hash and public key
        stored_parent_dest = parsed["destination_hash"]
        stored_parent_pubkey = parsed["public_key"]

        self.assertEqual(stored_parent_dest, parent_dest_hash)
        self.assertEqual(stored_parent_pubkey, parent_identity.get_public_key())

    def test_full_command_roundtrip(self):
        """Test complete command sign -> send -> verify flow"""
        # Parent creates and signs a LOCK command
        parent_identity = MockIdentity()
        cmd = "LOCK"
        nonce = guardian_crypto.generate_nonce()
        timestamp = int(time.time() * 1000)
        payload = b'{"contacts": ["abc123"]}'

        signature = guardian_crypto.sign_command(
            parent_identity, cmd, nonce, timestamp, payload
        )
        self.assertIsNotNone(signature)

        # Child receives and verifies command
        parent_pubkey = parent_identity.get_public_key()
        is_valid = guardian_crypto.verify_command(
            parent_pubkey, signature, cmd, nonce, timestamp, payload
        )
        self.assertTrue(is_valid)


if __name__ == "__main__":
    unittest.main()
