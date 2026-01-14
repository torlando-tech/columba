"""
Guardian (parental control) cryptographic utilities for Columba.

This module provides cryptographic functions for:
- Signing and verifying parental control commands
- Generating and validating guardian pairing QR codes
- Ed25519 signature operations using RNS Identity

Separated from reticulum_wrapper.py to keep that module from growing too large.
"""

import os
import time
from typing import Dict, Optional, Tuple

import RNS
from logging_utils import log_debug, log_error, log_info

CLASS_NAME = "GuardianCrypto"


def sign_data(identity: RNS.Identity, data: bytes) -> Optional[bytes]:
    """
    Sign data using an RNS Identity's Ed25519 private key.

    Args:
        identity: RNS Identity object with private key loaded
        data: Bytes to sign

    Returns:
        64-byte Ed25519 signature, or None on error
    """
    try:
        if identity is None:
            log_error(CLASS_NAME, "sign_data", "Identity is None")
            return None
        if not hasattr(identity, "sign"):
            log_error(CLASS_NAME, "sign_data", "Identity has no sign method")
            return None

        signature = identity.sign(data)
        log_debug(CLASS_NAME, "sign_data", f"Signed {len(data)} bytes, signature: {len(signature)} bytes")
        return signature
    except Exception as e:
        log_error(CLASS_NAME, "sign_data", f"Signing failed: {e}")
        return None


def verify_signature(public_key: bytes, signature: bytes, data: bytes) -> bool:
    """
    Verify an Ed25519 signature using a public key.

    Args:
        public_key: 64-byte RNS public key (32-byte X25519 + 32-byte Ed25519)
        signature: 64-byte Ed25519 signature
        data: The signed data

    Returns:
        True if signature is valid, False otherwise
    """
    try:
        if public_key is None or len(public_key) == 0:
            log_error(CLASS_NAME, "verify_signature", "Public key is empty")
            return False
        if signature is None or len(signature) == 0:
            log_error(CLASS_NAME, "verify_signature", "Signature is empty")
            return False
        if data is None:
            log_error(CLASS_NAME, "verify_signature", "Data is None")
            return False

        log_debug(CLASS_NAME, "verify_signature", f"Public key length: {len(public_key)} bytes")

        # Create an identity instance and load the public key
        # RNS Identity expects 64-byte public key (32-byte X25519 + 32-byte Ed25519)
        identity = RNS.Identity(create_keys=False)
        identity.load_public_key(public_key)

        if identity.pub is None:
            log_error(CLASS_NAME, "verify_signature", "Failed to load public key into identity")
            return False

        # Use the identity's validate method
        result = identity.validate(signature, data)
        log_debug(CLASS_NAME, "verify_signature", f"Verification result: {result}")
        return result

    except Exception as e:
        log_error(CLASS_NAME, "verify_signature", f"Verification failed: {e}")
        return False


def generate_pairing_qr_data(identity: RNS.Identity, destination_hash: bytes) -> Optional[Dict]:
    """
    Generate data for a guardian pairing QR code.

    The QR contains:
    - Guardian's destination hash (for messaging)
    - Guardian's public key (for signature verification)
    - Timestamp (for freshness/anti-replay)
    - Signature (to prove ownership of the identity)

    Args:
        identity: RNS Identity of the guardian (parent)
        destination_hash: LXMF destination hash of the guardian

    Returns:
        Dictionary with QR data fields, or None on error:
        {
            "destination_hash": bytes,
            "public_key": bytes,
            "timestamp": int,
            "signature": bytes,
            "qr_string": str  # Formatted for QR code
        }
    """
    try:
        if identity is None:
            log_error(CLASS_NAME, "generate_pairing_qr_data", "Identity is None")
            return None

        timestamp = int(time.time() * 1000)  # milliseconds
        public_key = identity.get_public_key()

        # Sign: destination_hash + timestamp_bytes
        timestamp_bytes = timestamp.to_bytes(8, byteorder="big")
        data_to_sign = destination_hash + timestamp_bytes
        signature = sign_data(identity, data_to_sign)

        if signature is None:
            log_error(CLASS_NAME, "generate_pairing_qr_data", "Failed to sign pairing data")
            return None

        # Format for QR code: lxmf-guardian://<dest_hash>:<pubkey>:<timestamp>:<signature>
        qr_string = (
            f"lxmf-guardian://"
            f"{destination_hash.hex()}:"
            f"{public_key.hex()}:"
            f"{timestamp_bytes.hex()}:"
            f"{signature.hex()}"
        )

        result = {
            "destination_hash": destination_hash,
            "public_key": public_key,
            "timestamp": timestamp,
            "signature": signature,
            "qr_string": qr_string,
        }

        log_info(
            CLASS_NAME,
            "generate_pairing_qr_data",
            f"Generated pairing QR for destination {destination_hash.hex()[:16]}...",
        )
        return result

    except Exception as e:
        log_error(CLASS_NAME, "generate_pairing_qr_data", f"Failed to generate pairing data: {e}")
        return None


def parse_pairing_qr_data(qr_string: str) -> Optional[Dict]:
    """
    Parse a guardian pairing QR code string.

    Args:
        qr_string: QR code content in format:
                   lxmf-guardian://<dest_hash>:<pubkey>:<timestamp>:<signature>

    Returns:
        Dictionary with parsed fields, or None on error:
        {
            "destination_hash": bytes,
            "public_key": bytes,
            "timestamp": int,
            "signature": bytes,
        }
    """
    try:
        if not qr_string.startswith("lxmf-guardian://"):
            log_error(CLASS_NAME, "parse_pairing_qr_data", "Invalid QR prefix")
            return None

        # Remove prefix and split by colon
        data_part = qr_string[len("lxmf-guardian://") :]
        parts = data_part.split(":")

        if len(parts) != 4:
            log_error(CLASS_NAME, "parse_pairing_qr_data", f"Expected 4 parts, got {len(parts)}")
            return None

        dest_hash_hex, pubkey_hex, timestamp_hex, signature_hex = parts

        result = {
            "destination_hash": bytes.fromhex(dest_hash_hex),
            "public_key": bytes.fromhex(pubkey_hex),
            "timestamp": int.from_bytes(bytes.fromhex(timestamp_hex), byteorder="big"),
            "signature": bytes.fromhex(signature_hex),
        }

        log_debug(
            CLASS_NAME,
            "parse_pairing_qr_data",
            f"Parsed pairing QR for destination {dest_hash_hex[:16]}...",
        )
        return result

    except Exception as e:
        log_error(CLASS_NAME, "parse_pairing_qr_data", f"Failed to parse QR: {e}")
        return None


def validate_pairing_qr(qr_data: Dict, max_age_ms: int = 5 * 60 * 1000) -> Tuple[bool, str]:
    """
    Validate a parsed guardian pairing QR code.

    Checks:
    1. Signature is valid (proves guardian owns the identity)
    2. Timestamp is fresh (not older than max_age_ms)

    Args:
        qr_data: Parsed QR data from parse_pairing_qr_data()
        max_age_ms: Maximum age of QR in milliseconds (default 5 minutes)

    Returns:
        Tuple of (is_valid: bool, error_message: str)
    """
    try:
        # Check timestamp freshness
        now_ms = int(time.time() * 1000)
        age_ms = now_ms - qr_data["timestamp"]

        if age_ms < 0:
            return False, "QR code timestamp is in the future"
        if age_ms > max_age_ms:
            return False, f"QR code expired ({age_ms // 1000}s old, max {max_age_ms // 1000}s)"

        # Verify signature
        timestamp_bytes = qr_data["timestamp"].to_bytes(8, byteorder="big")
        data_to_verify = qr_data["destination_hash"] + timestamp_bytes

        is_valid = verify_signature(
            qr_data["public_key"],
            qr_data["signature"],
            data_to_verify,
        )

        if not is_valid:
            return False, "Invalid signature - guardian identity not verified"

        log_info(CLASS_NAME, "validate_pairing_qr", "Pairing QR validated successfully")
        return True, ""

    except Exception as e:
        log_error(CLASS_NAME, "validate_pairing_qr", f"Validation failed: {e}")
        return False, f"Validation error: {e}"


def sign_command(
    identity: RNS.Identity,
    cmd: str,
    nonce: bytes,
    timestamp: int,
    payload: bytes,
) -> Optional[bytes]:
    """
    Sign a parental control command.

    The signature covers: cmd + nonce + timestamp + payload
    This prevents tampering and replay attacks.

    Args:
        identity: RNS Identity of the guardian (parent)
        cmd: Command type (e.g., "LOCK", "UNLOCK", "ALLOW_ADD")
        nonce: Random 16-byte nonce for replay protection
        timestamp: Unix timestamp in milliseconds
        payload: msgpack-encoded command payload

    Returns:
        64-byte Ed25519 signature, or None on error
    """
    try:
        # Build data to sign
        cmd_bytes = cmd.encode("utf-8")
        timestamp_bytes = timestamp.to_bytes(8, byteorder="big")
        data_to_sign = cmd_bytes + nonce + timestamp_bytes + payload

        signature = sign_data(identity, data_to_sign)
        if signature:
            log_debug(
                CLASS_NAME,
                "sign_command",
                f"Signed command {cmd}, nonce: {nonce.hex()[:8]}...",
            )
        return signature

    except Exception as e:
        log_error(CLASS_NAME, "sign_command", f"Failed to sign command: {e}")
        return None


def verify_command(
    public_key: bytes,
    signature: bytes,
    cmd: str,
    nonce: bytes,
    timestamp: int,
    payload: bytes,
) -> bool:
    """
    Verify a parental control command signature.

    Args:
        public_key: Guardian's Ed25519 public key
        signature: 64-byte signature to verify
        cmd: Command type
        nonce: Random nonce from command
        timestamp: Unix timestamp from command
        payload: msgpack-encoded command payload

    Returns:
        True if signature is valid, False otherwise
    """
    try:
        # Reconstruct signed data
        cmd_bytes = cmd.encode("utf-8")
        timestamp_bytes = timestamp.to_bytes(8, byteorder="big")
        data_to_verify = cmd_bytes + nonce + timestamp_bytes + payload

        result = verify_signature(public_key, signature, data_to_verify)
        log_debug(
            CLASS_NAME,
            "verify_command",
            f"Verified command {cmd}: {result}, nonce: {nonce.hex()[:8]}...",
        )
        return result

    except Exception as e:
        log_error(CLASS_NAME, "verify_command", f"Failed to verify command: {e}")
        return False


def generate_nonce() -> bytes:
    """
    Generate a random 16-byte nonce for command replay protection.

    Returns:
        16 random bytes
    """
    return os.urandom(16)
