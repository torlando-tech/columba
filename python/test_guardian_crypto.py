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
        self.assertIn("pairing_token", result)
        self.assertIn("qr_string", result)

        # Check QR string format (now 5 parts)
        self.assertTrue(result["qr_string"].startswith("lxmf-guardian://"))
        parts = result["qr_string"].split("lxmf-guardian://")[1].split(":")
        self.assertEqual(len(parts), 5, f"Expected 5 QR parts, got {len(parts)}")

        # Check pairing token is 16 bytes
        self.assertEqual(len(result["pairing_token"]), 16)

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
        self.assertEqual(parsed["pairing_token"], gen_result["pairing_token"])

    def test_parse_invalid_qr_prefix(self):
        """Test parsing QR with wrong prefix"""
        result = guardian_crypto.parse_pairing_qr_data("wrong-prefix://abc:def:123:456")
        self.assertIsNone(result)

    def test_parse_invalid_qr_parts(self):
        """Test parsing QR with wrong number of parts (expects 5)"""
        result = guardian_crypto.parse_pairing_qr_data("lxmf-guardian://abc:def:123")
        self.assertIsNone(result)
        # Also reject old 4-part format
        result = guardian_crypto.parse_pairing_qr_data("lxmf-guardian://abc:def:123:456")
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
        self.assertIn("pairing_token", result)
        self.assertTrue(result["qr_string"].startswith("lxmf-guardian://"))
        # Token should be a 32-char hex string (16 bytes)
        self.assertEqual(len(result["pairing_token"]), 32)

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
        self.assertIn("pairing_token", result)
        self.assertEqual(len(result["pairing_token"]), 32)  # 16 bytes as hex

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


# ========== Error Branch Tests for guardian_crypto.py ==========


class TestSignDataErrors(unittest.TestCase):
    """Test sign_data error branches."""

    def test_none_identity_returns_none(self):
        result = guardian_crypto.sign_data(None, b"test data")
        self.assertIsNone(result)

    def test_identity_without_sign_method_returns_none(self):
        class NoSignIdentity:
            def get_public_key(self):
                return os.urandom(32)
        result = guardian_crypto.sign_data(NoSignIdentity(), b"test data")
        self.assertIsNone(result)

    def test_sign_raises_exception_returns_none(self):
        class RaisingIdentity:
            def get_public_key(self):
                return os.urandom(32)
            def sign(self, data):
                raise RuntimeError("Hardware signing failure")
        result = guardian_crypto.sign_data(RaisingIdentity(), b"test data")
        self.assertIsNone(result)


class TestVerifySignatureErrors(unittest.TestCase):
    """Test verify_signature error branches."""

    def test_none_public_key_returns_false(self):
        result = guardian_crypto.verify_signature(None, b'\x00' * 64, b"data")
        self.assertFalse(result)

    def test_empty_public_key_returns_false(self):
        result = guardian_crypto.verify_signature(b'', b'\x00' * 64, b"data")
        self.assertFalse(result)

    def test_none_signature_returns_false(self):
        result = guardian_crypto.verify_signature(b'\x00' * 64, None, b"data")
        self.assertFalse(result)

    def test_empty_signature_returns_false(self):
        result = guardian_crypto.verify_signature(b'\x00' * 64, b'', b"data")
        self.assertFalse(result)

    def test_none_data_returns_false(self):
        result = guardian_crypto.verify_signature(b'\x00' * 64, b'\x00' * 64, None)
        self.assertFalse(result)

    def test_pub_none_after_load_public_key_returns_false(self):
        """identity.pub is None after load_public_key should return False."""
        with patch('guardian_crypto.RNS') as mock_rns_local:
            mock_identity_instance = MagicMock()
            mock_identity_instance.pub = None
            mock_rns_local.Identity.return_value = mock_identity_instance
            result = guardian_crypto.verify_signature(b'\x00' * 64, b'\x00' * 64, b"data")
            self.assertFalse(result)

    def test_rns_exception_returns_false(self):
        """Exception raised by RNS should be caught and return False."""
        with patch('guardian_crypto.RNS') as mock_rns_local:
            mock_rns_local.Identity.side_effect = Exception("RNS unavailable")
            result = guardian_crypto.verify_signature(b'\x00' * 64, b'\x00' * 64, b"data")
            self.assertFalse(result)


class TestGeneratePairingQrDataErrors(unittest.TestCase):
    """Test generate_pairing_qr_data error branches."""

    def test_none_identity_returns_none(self):
        result = guardian_crypto.generate_pairing_qr_data(None, os.urandom(16))
        self.assertIsNone(result)

    def test_sign_data_failure_returns_none(self):
        """When sign_data returns None (identity has no sign method), return None."""
        class NoSignIdentity:
            def get_public_key(self):
                return os.urandom(32)
        result = guardian_crypto.generate_pairing_qr_data(NoSignIdentity(), os.urandom(16))
        self.assertIsNone(result)


class TestValidatePairingQrErrors(unittest.TestCase):
    """Test validate_pairing_qr error branches."""

    def test_future_timestamp_returns_false_with_future_message(self):
        qr_data = {
            "destination_hash": os.urandom(16),
            "public_key": b'\x00' * 64,
            "timestamp": int(time.time() * 1000) + (10 * 60 * 1000),  # 10 min in future
            "signature": b'\x00' * 64,
            "pairing_token": os.urandom(16),
        }
        is_valid, error = guardian_crypto.validate_pairing_qr(qr_data)
        self.assertFalse(is_valid)
        self.assertIn("future", error.lower())

    def test_invalid_signature_returns_false_with_signature_message(self):
        """Fresh timestamp but invalid signature should fail."""
        qr_data = {
            "destination_hash": os.urandom(16),
            "public_key": b'\x00' * 64,
            "timestamp": int(time.time() * 1000),  # Fresh
            "signature": b'\x00' * 64,
            "pairing_token": os.urandom(16),
        }
        with patch('guardian_crypto.verify_signature', return_value=False):
            is_valid, error = guardian_crypto.validate_pairing_qr(qr_data)
            self.assertFalse(is_valid)
            self.assertIn("signature", error.lower())


class TestTamperedPairingToken(unittest.TestCase):
    """Test that a QR with tampered pairing token fails validation."""

    def test_tampered_token_fails_signature(self):
        """Changing the pairing_token after signing should fail signature verification."""
        identity = MockIdentity()
        dest_hash = os.urandom(16)

        gen_result = guardian_crypto.generate_pairing_qr_data(identity, dest_hash)
        parsed = guardian_crypto.parse_pairing_qr_data(gen_result["qr_string"])
        self.assertIsNotNone(parsed)

        # Tamper with the token
        parsed["pairing_token"] = os.urandom(16)

        with patch('guardian_crypto.verify_signature', return_value=False):
            is_valid, error = guardian_crypto.validate_pairing_qr(parsed)
            self.assertFalse(is_valid)
            self.assertIn("signature", error.lower())


class TestSignCommandException(unittest.TestCase):
    """Test sign_command exception branch."""

    def test_non_string_cmd_triggers_exception_returns_none(self):
        """Passing a non-string cmd causes encode() to fail, which is caught gracefully."""
        result = guardian_crypto.sign_command(
            MockIdentity(),
            42,  # int has no .encode() → AttributeError, caught → None
            guardian_crypto.generate_nonce(),
            int(time.time() * 1000),
            b'payload',
        )
        self.assertIsNone(result)


# ========== Reticulum Wrapper Guardian Config Tests ==========


def _make_wrapper():
    """Create a ReticulumWrapper instance bypassing __init__, with guardian state set up."""
    import threading
    import reticulum_wrapper
    wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)
    wrapper._guardian_is_locked = False
    wrapper._guardian_hash = None
    wrapper._guardian_allowed_hashes = set()
    wrapper._guardian_lock = threading.Lock()
    return wrapper


class TestUpdateGuardianConfig(unittest.TestCase):
    """Test update_guardian_config updates internal state correctly."""

    def setUp(self):
        self.wrapper = _make_wrapper()

    def test_update_locked_stores_all_fields(self):
        result = self.wrapper.update_guardian_config(
            is_locked=True,
            guardian_hash="abcdef123456789a",
            allowed_hashes=["aabb1122", "ccdd3344"],
        )
        self.assertTrue(result.get("success"))
        self.assertTrue(self.wrapper._guardian_is_locked)
        self.assertEqual(self.wrapper._guardian_hash, "abcdef123456789a")
        self.assertIn("aabb1122", self.wrapper._guardian_allowed_hashes)
        self.assertIn("ccdd3344", self.wrapper._guardian_allowed_hashes)

    def test_update_unlocked_clears_lock_flag(self):
        self.wrapper._guardian_is_locked = True
        result = self.wrapper.update_guardian_config(
            is_locked=False,
            guardian_hash=None,
            allowed_hashes=[],
        )
        self.assertTrue(result.get("success"))
        self.assertFalse(self.wrapper._guardian_is_locked)

    def test_none_allowed_hashes_results_in_empty_set(self):
        result = self.wrapper.update_guardian_config(
            is_locked=True,
            guardian_hash="abc",
            allowed_hashes=None,
        )
        self.assertTrue(result.get("success"))
        self.assertEqual(self.wrapper._guardian_allowed_hashes, set())


# ========== Reticulum Wrapper Link Callback Tests ==========


class TestLinkCallbacks(unittest.TestCase):
    """Test _on_lxmf_link_established and _on_link_remote_identified."""

    def setUp(self):
        self.wrapper = _make_wrapper()

    def test_link_established_not_locked_no_identity_callback(self):
        """Not locked: identity callback should not be registered."""
        mock_link = MagicMock()
        self.wrapper._guardian_is_locked = False
        self.wrapper._on_lxmf_link_established(mock_link)
        mock_link.set_remote_identified_callback.assert_not_called()

    def test_link_established_locked_registers_identity_callback(self):
        """Locked: _on_link_remote_identified should be registered as callback."""
        mock_link = MagicMock()
        self.wrapper._guardian_is_locked = True
        self.wrapper._on_lxmf_link_established(mock_link)
        mock_link.set_remote_identified_callback.assert_called_once_with(
            self.wrapper._on_link_remote_identified
        )

    def test_remote_identified_not_locked_allows_link(self):
        """Not locked when identity verified: link not torn down."""
        mock_link = MagicMock()
        mock_identity = MagicMock()
        mock_identity.hash = bytes.fromhex("aabbccdd" * 4)
        self.wrapper._guardian_is_locked = False
        self.wrapper._on_link_remote_identified(mock_link, mock_identity)
        mock_link.teardown.assert_not_called()

    def test_remote_identified_is_guardian_allows_link(self):
        """Remote is the guardian: link should not be torn down."""
        remote_hex = "aabbccdd" * 4
        mock_link = MagicMock()
        mock_identity = MagicMock()
        mock_identity.hash = bytes.fromhex(remote_hex)
        self.wrapper._guardian_is_locked = True
        self.wrapper._guardian_hash = remote_hex
        self.wrapper._on_link_remote_identified(mock_link, mock_identity)
        mock_link.teardown.assert_not_called()

    def test_remote_identified_in_allowed_list_allows_link(self):
        """Remote in allowed contacts list: link should not be torn down."""
        remote_hex = "11223344" * 4
        mock_link = MagicMock()
        mock_identity = MagicMock()
        mock_identity.hash = bytes.fromhex(remote_hex)
        self.wrapper._guardian_is_locked = True
        self.wrapper._guardian_hash = "aabbccdd" * 4
        self.wrapper._guardian_allowed_hashes = {remote_hex}
        self.wrapper._on_link_remote_identified(mock_link, mock_identity)
        mock_link.teardown.assert_not_called()

    def test_remote_identified_not_allowed_tears_down_link(self):
        """Remote not in allowed list: link.teardown() must be called."""
        remote_hex = "deadbeef" * 4
        mock_link = MagicMock()
        mock_identity = MagicMock()
        mock_identity.hash = bytes.fromhex(remote_hex)
        self.wrapper._guardian_is_locked = True
        self.wrapper._guardian_hash = "aabbccdd" * 4
        self.wrapper._guardian_allowed_hashes = {"11223344" * 4}
        self.wrapper._on_link_remote_identified(mock_link, mock_identity)
        mock_link.teardown.assert_called_once()

    def test_remote_identified_teardown_exception_handled_gracefully(self):
        """Teardown exception is caught internally and does not propagate."""
        remote_hex = "deadbeef" * 4
        mock_link = MagicMock()
        mock_link.teardown.side_effect = Exception("Network error during teardown")
        mock_identity = MagicMock()
        mock_identity.hash = bytes.fromhex(remote_hex)
        self.wrapper._guardian_is_locked = True
        self.wrapper._guardian_hash = "aabbccdd" * 4
        self.wrapper._guardian_allowed_hashes = set()
        # Must not raise; the teardown exception is logged and swallowed
        self.wrapper._on_link_remote_identified(mock_link, mock_identity)


# ========== Reticulum Wrapper Guardian Command Tests ==========


class TestGuardianVerifyCommand(unittest.TestCase):
    """Test guardian_verify_command on the wrapper."""

    def setUp(self):
        import reticulum_wrapper
        self.wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)

    def test_valid_json_returns_success_with_valid_flag(self):
        import json
        nonce = os.urandom(16)
        command_data = {
            "cmd": "LOCK",
            "nonce": nonce.hex(),
            "timestamp": 1234567890000,
            "payload": {"reason": "bedtime"},
        }
        command_json = json.dumps(command_data)
        with patch.object(guardian_crypto, 'verify_command', return_value=False):
            result = self.wrapper.guardian_verify_command(command_json, b'\x00' * 64, b'\x00' * 64)
            self.assertTrue(result.get("success"))
            self.assertFalse(result.get("valid"))

    def test_invalid_json_returns_failure_with_error(self):
        result = self.wrapper.guardian_verify_command("{{not-valid-json", b'\x00' * 64, b'\x00' * 64)
        self.assertFalse(result.get("success"))
        self.assertIn("error", result)


class TestGuardianSignCommand(unittest.TestCase):
    """Test guardian_sign_command on the wrapper."""

    def setUp(self):
        import reticulum_wrapper
        self.wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)

    def test_identity_not_found_returns_failure(self):
        self.wrapper._resolve_identity_file_path = Mock(return_value=None)
        result = self.wrapper.guardian_sign_command("nonexistent_hash", "LOCK", "")
        self.assertFalse(result.get("success"))
        self.assertIn("not found", result.get("error", "").lower())

    def test_identity_load_returns_none_returns_failure(self):
        """When RNS.Identity.from_file returns None, sign should fail."""
        import reticulum_wrapper
        self.wrapper._resolve_identity_file_path = Mock(return_value="/some/identity/path")
        # reticulum_wrapper.RNS is None at module level (set during initialize()).
        # Patch it to mock_rns, then make Identity.from_file return None.
        with patch.object(reticulum_wrapper, 'RNS', mock_rns):
            with patch.object(mock_rns, 'Identity') as mock_id_cls:
                mock_id_cls.from_file.return_value = None
                result = self.wrapper.guardian_sign_command("hash123", "LOCK", "")
                self.assertFalse(result.get("success"))
                self.assertIn("load identity", result.get("error", "").lower())


class TestGuardianSendCommand(unittest.TestCase):
    """Test guardian_send_command on the wrapper."""

    def setUp(self):
        import reticulum_wrapper
        self.wrapper = reticulum_wrapper.ReticulumWrapper.__new__(reticulum_wrapper.ReticulumWrapper)

    def test_no_destination_returns_failure(self):
        self.wrapper.local_lxmf_destination = None
        result = self.wrapper.guardian_send_command("ab" * 16, "LOCK", "{}")
        self.assertFalse(result.get("success"))
        self.assertIn("destination", result.get("error", "").lower())

    def test_no_identity_on_destination_returns_failure(self):
        mock_dest = MagicMock()
        mock_dest.identity = None
        self.wrapper.local_lxmf_destination = mock_dest
        result = self.wrapper.guardian_send_command("ab" * 16, "LOCK", "{}")
        self.assertFalse(result.get("success"))
        self.assertIn("identity", result.get("error", "").lower())

    def test_successful_send_with_mocked_transport(self):
        """With valid destination/identity and mocked send, command succeeds."""
        class FullMockIdentity(MockIdentity):
            def get_private_key(self):
                return os.urandom(64)

        mock_dest = MagicMock()
        mock_dest.identity = FullMockIdentity()
        self.wrapper.local_lxmf_destination = mock_dest
        self.wrapper.send_lxmf_message_with_method = Mock(return_value={"success": True})

        result = self.wrapper.guardian_send_command("ab" * 16, "LOCK", "{}")
        self.assertTrue(result.get("success"))
        self.wrapper.send_lxmf_message_with_method.assert_called_once()
        # Verify guardian commands enforce direct-only delivery (no propagation fallback)
        call_kwargs = self.wrapper.send_lxmf_message_with_method.call_args.kwargs
        self.assertEqual(call_kwargs["delivery_method"], "direct")
        self.assertFalse(call_kwargs["try_propagation_on_fail"])


if __name__ == "__main__":
    unittest.main()
