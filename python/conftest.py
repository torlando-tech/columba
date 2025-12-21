"""
Shared pytest fixtures for Python tests.

This module provides common test fixtures and mocks used across all Python test files.
CRITICAL: RNS and LXMF modules MUST be mocked BEFORE importing reticulum_wrapper.
"""

import sys
import os
import tempfile
import shutil
from unittest.mock import Mock, MagicMock
import pytest

# Add parent directory to path to import reticulum_wrapper
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Mock RNS and LXMF BEFORE importing reticulum_wrapper
# This prevents ImportError when the actual modules are not available
mock_rns = MagicMock()
mock_lxmf = MagicMock()

# Define LXMF message type constants
# These match the values in the actual LXMF library
mock_lxmf.LXMessage.OPPORTUNISTIC = 0x01
mock_lxmf.LXMessage.DIRECT = 0x02
mock_lxmf.LXMessage.PROPAGATED = 0x03
mock_lxmf.LXMessage.SENT = 0x04

# Install the mocks in sys.modules
sys.modules['RNS'] = mock_rns
sys.modules['RNS.vendor'] = MagicMock()
sys.modules['RNS.vendor.platformutils'] = MagicMock()
sys.modules['LXMF'] = mock_lxmf

# Now safe to import reticulum_wrapper
import reticulum_wrapper


@pytest.fixture
def temp_dir():
    """
    Provides a temporary directory for test storage.

    Automatically creates a temporary directory before the test
    and cleans it up after the test completes.

    Yields:
        str: Path to temporary directory
    """
    temp_path = tempfile.mkdtemp()
    yield temp_path
    # Cleanup
    if os.path.exists(temp_path):
        shutil.rmtree(temp_path)


@pytest.fixture
def wrapper(temp_dir):
    """
    Provides an uninitialized ReticulumWrapper instance.

    Args:
        temp_dir: Temporary directory fixture

    Returns:
        ReticulumWrapper: Uninitialized wrapper instance
    """
    return reticulum_wrapper.ReticulumWrapper(temp_dir)


@pytest.fixture
def initialized_wrapper(temp_dir):
    """
    Provides an initialized ReticulumWrapper with mocked router and reticulum.

    This fixture sets up a wrapper that appears to be fully initialized,
    with mocked router and reticulum objects.

    Args:
        temp_dir: Temporary directory fixture

    Returns:
        ReticulumWrapper: Initialized wrapper with mocked dependencies
    """
    wrapper = reticulum_wrapper.ReticulumWrapper(temp_dir)
    wrapper.initialized = True
    wrapper.router = MagicMock()
    wrapper.reticulum = MagicMock()
    return wrapper


@pytest.fixture
def mock_identity():
    """
    Provides a mock RNS.Identity object.

    Returns:
        Mock: Mock identity with common attributes
    """
    identity = Mock()
    identity.get_public_key = Mock(return_value=b'test_public_key_32_bytes_long__')
    identity.hash = b'test_identity_hash_16_b'
    identity.hexhash = b'test_identity_hash_16_b'.hex()
    return identity


@pytest.fixture
def mock_lxmf_message():
    """
    Provides a mock LXMF message object.

    Returns:
        Mock: Mock LXMF message with common attributes
    """
    message = MagicMock()
    message.hash = b'test_message_hash_16'
    message.desired_method = mock_lxmf.LXMessage.OPPORTUNISTIC
    message.delivery_attempts = 0
    message.try_propagation_on_fail = False
    message.packed = None
    message.propagation_packed = None
    message.propagation_stamp = None
    message.defer_propagation_stamp = False
    return message


# Export mock modules for use in tests
@pytest.fixture
def mock_rns_module():
    """
    Provides the mocked RNS module.

    Returns:
        MagicMock: The mocked RNS module
    """
    return mock_rns


@pytest.fixture
def mock_lxmf_module():
    """
    Provides the mocked LXMF module.

    Returns:
        MagicMock: The mocked LXMF module
    """
    return mock_lxmf
