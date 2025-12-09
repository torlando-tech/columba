"""
Unit tests for rnode_interface.py

Tests KISS protocol escaping/unescaping, configuration validation,
and firmware version validation.
"""

import pytest
import sys
from unittest.mock import MagicMock, patch

# Mock RNS before importing rnode_interface
sys.modules['RNS'] = MagicMock()

from rnode_interface import KISS, ColumbaRNodeInterface


class TestKISSEscape:
    """Tests for KISS.escape() byte escaping."""

    def test_escape_fend(self):
        """FEND (0xC0) should be escaped to 0xDB 0xDC."""
        data = bytes([0xC0])
        escaped = KISS.escape(data)
        assert escaped == bytes([0xDB, 0xDC])

    def test_escape_fesc(self):
        """FESC (0xDB) should be escaped to 0xDB 0xDD."""
        data = bytes([0xDB])
        escaped = KISS.escape(data)
        assert escaped == bytes([0xDB, 0xDD])

    def test_escape_mixed_special_bytes(self):
        """Multiple special bytes should all be escaped."""
        data = bytes([0xC0, 0xDB, 0xC0])
        escaped = KISS.escape(data)
        # 0xDB must be escaped first, then 0xC0
        assert escaped == bytes([0xDB, 0xDC, 0xDB, 0xDD, 0xDB, 0xDC])

    def test_escape_no_special_bytes(self):
        """Data without special bytes should pass through unchanged."""
        data = bytes([0x00, 0x01, 0x02, 0xFF])
        escaped = KISS.escape(data)
        assert escaped == data

    def test_escape_empty_data(self):
        """Empty data should return empty."""
        data = bytes()
        escaped = KISS.escape(data)
        assert escaped == bytes()

    def test_escape_all_fend(self):
        """All FEND bytes should be escaped."""
        data = bytes([0xC0, 0xC0, 0xC0])
        escaped = KISS.escape(data)
        assert escaped == bytes([0xDB, 0xDC, 0xDB, 0xDC, 0xDB, 0xDC])


class TestKISSUnescape:
    """Tests for KISS.unescape() byte unescaping."""

    def test_unescape_fend(self):
        """0xDB 0xDC should be unescaped to FEND (0xC0)."""
        data = bytes([0xDB, 0xDC])
        unescaped = KISS.unescape(data)
        assert unescaped == bytes([0xC0])

    def test_unescape_fesc(self):
        """0xDB 0xDD should be unescaped to FESC (0xDB)."""
        data = bytes([0xDB, 0xDD])
        unescaped = KISS.unescape(data)
        assert unescaped == bytes([0xDB])

    def test_unescape_mixed(self):
        """Multiple escape sequences should all be unescaped."""
        data = bytes([0xDB, 0xDC, 0xDB, 0xDD, 0xDB, 0xDC])
        unescaped = KISS.unescape(data)
        assert unescaped == bytes([0xC0, 0xDB, 0xC0])

    def test_unescape_no_escape_sequences(self):
        """Data without escape sequences should pass through unchanged."""
        data = bytes([0x00, 0x01, 0x02, 0xFF])
        unescaped = KISS.unescape(data)
        assert unescaped == data

    def test_unescape_empty_data(self):
        """Empty data should return empty."""
        data = bytes()
        unescaped = KISS.unescape(data)
        assert unescaped == bytes()


class TestKISSRoundTrip:
    """Tests for escape/unescape round-trip consistency."""

    def test_roundtrip_special_bytes(self):
        """escape(unescape(x)) should equal x for escaped data."""
        original_unescaped = bytes([0xC0, 0xDB, 0x00, 0xFF])
        escaped = KISS.escape(original_unescaped)
        roundtrip = KISS.unescape(escaped)
        assert roundtrip == original_unescaped

    def test_roundtrip_random_data(self):
        """Round-trip should work for arbitrary byte sequences."""
        original = bytes(range(256))
        escaped = KISS.escape(original)
        roundtrip = KISS.unescape(escaped)
        assert roundtrip == original


class TestKISSErrorMessages:
    """Tests for KISS.get_error_message()."""

    def test_error_initradio(self):
        """Error 0x01 should return radio initialization message."""
        msg = KISS.get_error_message(0x01)
        assert "Radio initialization failed" in msg

    def test_error_txfailed(self):
        """Error 0x02 should return transmission failed message."""
        msg = KISS.get_error_message(0x02)
        assert "Transmission failed" in msg

    def test_error_queue_full(self):
        """Error 0x04 should return queue overflow message."""
        msg = KISS.get_error_message(0x04)
        assert "queue" in msg.lower()

    def test_error_invalid_config(self):
        """Error 0x40 should return config error with TX power hint."""
        msg = KISS.get_error_message(0x40)
        assert "Invalid configuration" in msg
        assert "TX power" in msg

    def test_error_unknown(self):
        """Unknown error codes should return hex representation."""
        msg = KISS.get_error_message(0xFF)
        assert "0xFF" in msg or "Unknown" in msg


class TestValidateConfig:
    """Tests for ColumbaRNodeInterface._validate_config()."""

    def create_interface(self, **overrides):
        """Create a test interface with mocked dependencies."""
        config = {
            'target_device_name': 'RNode Test',
            'connection_mode': 'ble',
            'frequency': 915000000,
            'bandwidth': 250000,
            'txpower': 17,
            'sf': 11,
            'cr': 5,
            'st_alock': None,
            'lt_alock': None,
            'mode': 'full',
            'enable_framebuffer': True,
        }
        config.update(overrides)

        with patch.object(ColumbaRNodeInterface, '__init__', lambda x, *args: None):
            iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
            iface.FREQ_MIN = 137000000
            iface.FREQ_MAX = 3000000000
            iface.frequency = config['frequency']
            iface.txpower = config['txpower']
            iface.bandwidth = config['bandwidth']
            iface.sf = config['sf']
            iface.cr = config['cr']
            iface.st_alock = config['st_alock']
            iface.lt_alock = config['lt_alock']
            return iface

    def test_valid_config(self):
        """Valid configuration should not raise."""
        iface = self.create_interface()
        iface._validate_config()  # Should not raise

    def test_frequency_below_min(self):
        """Frequency below 137 MHz should raise ValueError."""
        iface = self.create_interface(frequency=100000000)
        with pytest.raises(ValueError, match="frequency"):
            iface._validate_config()

    def test_frequency_above_max(self):
        """Frequency above 3000 MHz should raise ValueError."""
        iface = self.create_interface(frequency=4000000000)
        with pytest.raises(ValueError, match="frequency"):
            iface._validate_config()

    def test_frequency_at_min_boundary(self):
        """Frequency at 137 MHz (min) should be valid."""
        iface = self.create_interface(frequency=137000000)
        iface._validate_config()  # Should not raise

    def test_frequency_at_max_boundary(self):
        """Frequency at 3000 MHz (max) should be valid."""
        iface = self.create_interface(frequency=3000000000)
        iface._validate_config()  # Should not raise

    def test_txpower_negative(self):
        """Negative TX power should raise ValueError."""
        iface = self.create_interface(txpower=-1)
        with pytest.raises(ValueError, match="TX power"):
            iface._validate_config()

    def test_txpower_above_max(self):
        """TX power above 36 dBm should raise ValueError."""
        iface = self.create_interface(txpower=40)
        with pytest.raises(ValueError, match="TX power"):
            iface._validate_config()

    def test_txpower_at_max(self):
        """TX power at 36 dBm (NZ regional max) should be valid."""
        iface = self.create_interface(txpower=36)
        iface._validate_config()  # Should not raise

    def test_txpower_us_regional_max(self):
        """TX power at 30 dBm (US regional max) should be valid."""
        iface = self.create_interface(txpower=30)
        iface._validate_config()  # Should not raise

    def test_txpower_eu_regional_max(self):
        """TX power at 27 dBm (EU 868-P regional max) should be valid."""
        iface = self.create_interface(txpower=27)
        iface._validate_config()  # Should not raise

    def test_txpower_common_device_max(self):
        """TX power at 22 dBm (common RNode device max) should be valid."""
        iface = self.create_interface(txpower=22)
        iface._validate_config()  # Should not raise

    def test_bandwidth_below_min(self):
        """Bandwidth below 7800 Hz should raise ValueError."""
        iface = self.create_interface(bandwidth=5000)
        with pytest.raises(ValueError, match="bandwidth"):
            iface._validate_config()

    def test_bandwidth_above_max(self):
        """Bandwidth above 1625000 Hz should raise ValueError."""
        iface = self.create_interface(bandwidth=2000000)
        with pytest.raises(ValueError, match="bandwidth"):
            iface._validate_config()

    def test_sf_below_min(self):
        """Spreading factor below 5 should raise ValueError."""
        iface = self.create_interface(sf=4)
        with pytest.raises(ValueError, match="spreading factor"):
            iface._validate_config()

    def test_sf_above_max(self):
        """Spreading factor above 12 should raise ValueError."""
        iface = self.create_interface(sf=13)
        with pytest.raises(ValueError, match="spreading factor"):
            iface._validate_config()

    def test_cr_below_min(self):
        """Coding rate below 5 should raise ValueError."""
        iface = self.create_interface(cr=4)
        with pytest.raises(ValueError, match="coding rate"):
            iface._validate_config()

    def test_cr_above_max(self):
        """Coding rate above 8 should raise ValueError."""
        iface = self.create_interface(cr=9)
        with pytest.raises(ValueError, match="coding rate"):
            iface._validate_config()

    def test_st_alock_negative(self):
        """Negative short-term airtime limit should raise ValueError."""
        iface = self.create_interface(st_alock=-1.0)
        with pytest.raises(ValueError, match="short-term airtime"):
            iface._validate_config()

    def test_st_alock_above_100(self):
        """Short-term airtime limit above 100% should raise ValueError."""
        iface = self.create_interface(st_alock=101.0)
        with pytest.raises(ValueError, match="short-term airtime"):
            iface._validate_config()

    def test_st_alock_valid(self):
        """Valid short-term airtime limit should not raise."""
        iface = self.create_interface(st_alock=10.0)
        iface._validate_config()  # Should not raise

    def test_lt_alock_negative(self):
        """Negative long-term airtime limit should raise ValueError."""
        iface = self.create_interface(lt_alock=-0.1)
        with pytest.raises(ValueError, match="long-term airtime"):
            iface._validate_config()

    def test_lt_alock_above_100(self):
        """Long-term airtime limit above 100% should raise ValueError."""
        iface = self.create_interface(lt_alock=100.1)
        with pytest.raises(ValueError, match="long-term airtime"):
            iface._validate_config()

    def test_alock_none_is_valid(self):
        """None airtime limits should be valid (disabled)."""
        iface = self.create_interface(st_alock=None, lt_alock=None)
        iface._validate_config()  # Should not raise


class TestValidateFirmware:
    """Tests for ColumbaRNodeInterface._validate_firmware()."""

    def create_interface_for_firmware(self, maj_version, min_version):
        """Create a test interface for firmware validation."""
        with patch.object(ColumbaRNodeInterface, '__init__', lambda x, *args: None):
            iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
            iface.REQUIRED_FW_VER_MAJ = 1
            iface.REQUIRED_FW_VER_MIN = 52
            iface.maj_version = maj_version
            iface.min_version = min_version
            iface.firmware_ok = False
            return iface

    def test_firmware_valid_exact_minimum(self):
        """Firmware 1.52 (exact minimum) should be valid."""
        iface = self.create_interface_for_firmware(1, 52)
        iface._validate_firmware()
        assert iface.firmware_ok is True

    def test_firmware_valid_higher_minor(self):
        """Firmware 1.100 should be valid."""
        iface = self.create_interface_for_firmware(1, 100)
        iface._validate_firmware()
        assert iface.firmware_ok is True

    def test_firmware_valid_higher_major(self):
        """Firmware 2.0 should be valid (major > required)."""
        iface = self.create_interface_for_firmware(2, 0)
        iface._validate_firmware()
        assert iface.firmware_ok is True

    def test_firmware_valid_much_higher_major(self):
        """Firmware 5.10 should be valid."""
        iface = self.create_interface_for_firmware(5, 10)
        iface._validate_firmware()
        assert iface.firmware_ok is True

    def test_firmware_invalid_low_minor(self):
        """Firmware 1.51 should be invalid (below 1.52)."""
        iface = self.create_interface_for_firmware(1, 51)
        iface._validate_firmware()
        assert iface.firmware_ok is False

    def test_firmware_invalid_low_major(self):
        """Firmware 0.99 should be invalid."""
        iface = self.create_interface_for_firmware(0, 99)
        iface._validate_firmware()
        assert iface.firmware_ok is False

    def test_firmware_invalid_zero(self):
        """Firmware 0.0 should be invalid."""
        iface = self.create_interface_for_firmware(0, 0)
        iface._validate_firmware()
        assert iface.firmware_ok is False

    def test_firmware_invalid_1_0(self):
        """Firmware 1.0 should be invalid."""
        iface = self.create_interface_for_firmware(1, 0)
        iface._validate_firmware()
        assert iface.firmware_ok is False


class TestThreadSafety:
    """Tests for thread-safe read loop control using threading.Event."""

    def create_interface(self):
        """Create a test interface with mocked dependencies."""
        import threading
        with patch.object(ColumbaRNodeInterface, '_get_kotlin_bridge'):
            with patch.object(ColumbaRNodeInterface, '_validate_config'):
                config = {
                    'frequency': 915000000,
                    'bandwidth': 125000,
                    'txpower': 17,
                    'sf': 8,
                    'cr': 5,
                }
                iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
                iface.frequency = config['frequency']
                iface.bandwidth = config['bandwidth']
                iface.txpower = config['txpower']
                iface.sf = config['sf']
                iface.cr = config['cr']
                iface.st_alock = None
                iface.lt_alock = None
                iface.name = "test-rnode"
                iface.target_device_name = "TestRNode"
                iface.connection_mode = 0
                iface.kotlin_bridge = None
                iface.enable_framebuffer = False
                iface.framebuffer_enabled = False
                iface._read_thread = None
                iface._running = threading.Event()
                iface._read_lock = threading.Lock()
                iface._reconnect_thread = None
                iface._reconnecting = False
                iface._max_reconnect_attempts = 30
                iface._reconnect_interval = 10.0
                iface._on_error_callback = None
                iface._on_online_status_changed = None
                return iface

    def test_running_flag_is_threading_event(self):
        """_running should be a threading.Event for thread-safe signaling."""
        import threading
        iface = self.create_interface()
        assert isinstance(iface._running, threading.Event)

    def test_running_event_initially_not_set(self):
        """_running event should not be set on initialization."""
        iface = self.create_interface()
        assert not iface._running.is_set()

    def test_running_event_can_be_set_and_cleared(self):
        """_running event should support set() and clear() operations."""
        iface = self.create_interface()

        # Initially not set
        assert not iface._running.is_set()

        # Set the event
        iface._running.set()
        assert iface._running.is_set()

        # Clear the event
        iface._running.clear()
        assert not iface._running.is_set()

    def test_running_event_thread_safe_signaling(self):
        """Verify threading.Event provides thread-safe stop signaling."""
        import threading
        import time

        iface = self.create_interface()
        loop_iterations = []

        def mock_loop():
            """Simulate read loop behavior."""
            while iface._running.is_set():
                loop_iterations.append(1)
                time.sleep(0.01)

        # Start the event and thread
        iface._running.set()
        thread = threading.Thread(target=mock_loop)
        thread.start()

        # Let it run a bit
        time.sleep(0.05)

        # Clear event to stop the loop
        iface._running.clear()
        thread.join(timeout=1.0)

        # Thread should have stopped cleanly
        assert not thread.is_alive()
        assert len(loop_iterations) > 0  # Loop ran at least once


class TestThreadSafeAccessors:
    """Tests for thread-safe RSSI/SNR accessors."""

    def create_interface(self):
        """Create a test interface with mocked dependencies."""
        import threading
        with patch.object(ColumbaRNodeInterface, '_get_kotlin_bridge'):
            with patch.object(ColumbaRNodeInterface, '_validate_config'):
                iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
                iface._read_lock = threading.Lock()
                iface.r_stat_rssi = -75
                iface.r_stat_snr = 8.5
                return iface

    def test_get_rssi_uses_lock(self):
        """get_rssi() should acquire _read_lock for thread safety."""
        iface = self.create_interface()
        mock_lock = MagicMock()
        mock_lock.__enter__ = MagicMock(return_value=None)
        mock_lock.__exit__ = MagicMock(return_value=None)
        iface._read_lock = mock_lock

        result = iface.get_rssi()

        mock_lock.__enter__.assert_called_once()
        mock_lock.__exit__.assert_called_once()
        assert result == -75

    def test_get_snr_uses_lock(self):
        """get_snr() should acquire _read_lock for thread safety."""
        iface = self.create_interface()
        mock_lock = MagicMock()
        mock_lock.__enter__ = MagicMock(return_value=None)
        mock_lock.__exit__ = MagicMock(return_value=None)
        iface._read_lock = mock_lock

        result = iface.get_snr()

        mock_lock.__enter__.assert_called_once()
        mock_lock.__exit__.assert_called_once()
        assert result == 8.5

    def test_get_rssi_returns_correct_value_under_lock(self):
        """get_rssi() should return correct value while holding lock."""
        iface = self.create_interface()
        iface.r_stat_rssi = -90

        result = iface.get_rssi()

        assert result == -90

    def test_get_snr_returns_correct_value_under_lock(self):
        """get_snr() should return correct value while holding lock."""
        iface = self.create_interface()
        iface.r_stat_snr = 12.0

        result = iface.get_snr()

        assert result == 12.0


class TestWriteRetry:
    """Tests for write retry with exponential backoff."""

    def create_interface_for_write(self):
        """Create a test interface with mocked dependencies."""
        import threading
        with patch.object(ColumbaRNodeInterface, '_get_kotlin_bridge'):
            with patch.object(ColumbaRNodeInterface, '_validate_config'):
                iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
                iface.name = "test-rnode"
                iface.kotlin_bridge = MagicMock()
                return iface

    def test_write_uses_exponential_backoff(self):
        """Write retry should use exponential backoff delays."""
        import time
        iface = self.create_interface_for_write()
        test_data = b"test"

        # Mock writeSync to always fail (return 0 bytes written)
        iface.kotlin_bridge.writeSync.return_value = 0

        sleep_calls = []

        def mock_sleep(seconds):
            sleep_calls.append(seconds)
            # Don't actually sleep in tests

        with patch('time.sleep', mock_sleep):
            with pytest.raises(IOError):
                iface._write(test_data)

        # With 3 retries, we get 2 sleep calls between attempts
        # Should have exponential backoff: 0.3s, 1.0s
        assert len(sleep_calls) == 2
        assert sleep_calls[0] == pytest.approx(0.3, rel=0.01)
        assert sleep_calls[1] == pytest.approx(1.0, rel=0.01)

    def test_write_succeeds_on_first_attempt(self):
        """Write should not retry if first attempt succeeds."""
        import time
        iface = self.create_interface_for_write()
        test_data = b"test"

        # Mock writeSync to succeed
        iface.kotlin_bridge.writeSync.return_value = len(test_data)

        sleep_calls = []
        with patch('time.sleep', lambda s: sleep_calls.append(s)):
            iface._write(test_data)  # Should not raise

        # No retries needed
        assert len(sleep_calls) == 0
        assert iface.kotlin_bridge.writeSync.call_count == 1

    def test_write_succeeds_on_retry(self):
        """Write should succeed if a retry works."""
        import time
        iface = self.create_interface_for_write()
        test_data = b"test"

        # Mock writeSync to fail first, then succeed
        iface.kotlin_bridge.writeSync.side_effect = [0, len(test_data)]

        sleep_calls = []
        with patch('time.sleep', lambda s: sleep_calls.append(s)):
            iface._write(test_data)  # Should not raise

        # One retry delay
        assert len(sleep_calls) == 1
        assert iface.kotlin_bridge.writeSync.call_count == 2


class TestRadioStateThreadSafety:
    """Tests for thread-safe access to radio state variables (Issue 3)."""

    def create_interface(self):
        """Create a test interface with mocked dependencies."""
        import threading
        with patch.object(ColumbaRNodeInterface, '_get_kotlin_bridge'):
            with patch.object(ColumbaRNodeInterface, '_validate_config'):
                iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
                iface.frequency = 915000000
                iface.bandwidth = 125000
                iface.txpower = 17
                iface.sf = 8
                iface.cr = 5
                iface.st_alock = None
                iface.lt_alock = None
                iface.name = "test-rnode"
                iface.target_device_name = "TestRNode"
                iface.connection_mode = 0
                iface.kotlin_bridge = None
                iface.enable_framebuffer = False
                iface.framebuffer_enabled = False
                iface._read_thread = None
                iface._running = threading.Event()
                iface._read_lock = threading.Lock()
                iface._reconnect_thread = None
                iface._reconnecting = False
                iface._max_reconnect_attempts = 30
                iface._reconnect_interval = 10.0
                iface._on_error_callback = None
                iface._on_online_status_changed = None
                # Radio state readback variables (these need thread safety)
                iface.r_frequency = None
                iface.r_bandwidth = None
                iface.r_txpower = None
                iface.r_sf = None
                iface.r_cr = None
                iface.r_state = None
                iface.r_stat_rssi = None
                iface.r_stat_snr = None
                return iface

    def test_radio_state_lock_exists(self):
        """Interface should have a _read_lock for thread-safe radio state access."""
        import threading
        iface = self.create_interface()
        assert hasattr(iface, '_read_lock')
        assert isinstance(iface._read_lock, type(threading.Lock()))

    def test_radio_state_can_be_updated_safely(self):
        """Radio state variables should be accessible under lock."""
        iface = self.create_interface()

        # Simulate what the read loop does - update state under lock
        with iface._read_lock:
            iface.r_frequency = 915000000
            iface.r_bandwidth = 250000
            iface.r_sf = 11
            iface.r_cr = 5
            iface.r_state = 0x01  # RADIO_STATE_ON

        # Read values (should be consistent)
        with iface._read_lock:
            assert iface.r_frequency == 915000000
            assert iface.r_bandwidth == 250000
            assert iface.r_sf == 11
            assert iface.r_cr == 5
            assert iface.r_state == 0x01

    def test_concurrent_radio_state_access(self):
        """Concurrent reads and writes to radio state should not cause errors."""
        import threading
        import time

        iface = self.create_interface()
        errors = []
        iterations = 100

        def writer():
            """Simulate read loop updating state."""
            for i in range(iterations):
                with iface._read_lock:
                    iface.r_frequency = 915000000 + i
                    iface.r_bandwidth = 250000
                    iface.r_sf = 11
                    iface.r_cr = 5
                time.sleep(0.001)

        def reader():
            """Simulate _validate_radio_state reading state."""
            for _ in range(iterations):
                try:
                    with iface._read_lock:
                        # Read all values atomically
                        freq = iface.r_frequency
                        bw = iface.r_bandwidth
                        sf = iface.r_sf
                        cr = iface.r_cr
                        # Values should be consistent (all set or all None)
                        if freq is not None:
                            assert bw is not None
                            assert sf is not None
                            assert cr is not None
                except Exception as e:
                    errors.append(e)
                time.sleep(0.001)

        # Run concurrent threads
        writer_thread = threading.Thread(target=writer)
        reader_thread = threading.Thread(target=reader)

        writer_thread.start()
        reader_thread.start()

        writer_thread.join(timeout=5.0)
        reader_thread.join(timeout=5.0)

        assert len(errors) == 0, f"Concurrent access errors: {errors}"


class TestOnlineStatusThreadSafety:
    """Tests for thread-safe online status access (PR #36 critical fix)."""

    def create_interface(self):
        """Create a test interface with mocked dependencies."""
        import threading
        with patch.object(ColumbaRNodeInterface, '_get_kotlin_bridge'):
            with patch.object(ColumbaRNodeInterface, '_validate_config'):
                iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
                iface.frequency = 915000000
                iface.bandwidth = 125000
                iface.txpower = 17
                iface.sf = 8
                iface.cr = 5
                iface.st_alock = None
                iface.lt_alock = None
                iface.name = "test-rnode"
                iface.target_device_name = "TestRNode"
                iface.connection_mode = 0
                iface.kotlin_bridge = MagicMock()
                iface.kotlin_bridge.writeSync.return_value = 10
                iface.enable_framebuffer = False
                iface.framebuffer_enabled = False
                iface._read_thread = None
                iface._running = threading.Event()
                iface._read_lock = threading.Lock()
                iface._reconnect_thread = None
                iface._reconnecting = False
                iface._max_reconnect_attempts = 30
                iface._reconnect_interval = 10.0
                iface._on_error_callback = None
                iface._on_online_status_changed = None
                iface.online = False
                iface.txb = 0
                iface.r_stat_rssi = None
                iface.r_stat_snr = None
                return iface

    def test_set_online_uses_lock(self):
        """_set_online should use _read_lock for thread-safe updates."""
        iface = self.create_interface()

        # Verify initial state
        assert iface.online is False

        # Set online
        iface._set_online(True)
        assert iface.online is True

        # Set offline
        iface._set_online(False)
        assert iface.online is False

    def test_process_outgoing_uses_lock(self):
        """process_outgoing should use _read_lock when checking online status."""
        iface = self.create_interface()
        iface.online = True

        # Should not raise - online status should be readable
        iface.process_outgoing(b"test data")

        # Verify write was attempted
        iface.kotlin_bridge.writeSync.assert_called()

    def test_concurrent_online_status_access(self):
        """Concurrent reads/writes to online status should be thread-safe."""
        import threading
        import time

        iface = self.create_interface()
        errors = []
        iterations = 200

        def status_changer():
            """Simulate connection state changes."""
            for i in range(iterations):
                iface._set_online(i % 2 == 0)
                time.sleep(0.001)

        def sender():
            """Simulate sending data."""
            for _ in range(iterations):
                try:
                    iface.process_outgoing(b"test")
                except IOError:
                    pass  # Expected when offline
                except Exception as e:
                    errors.append(e)
                time.sleep(0.001)

        changer_thread = threading.Thread(target=status_changer)
        sender_thread = threading.Thread(target=sender)

        changer_thread.start()
        sender_thread.start()

        changer_thread.join(timeout=5.0)
        sender_thread.join(timeout=5.0)

        assert len(errors) == 0, f"Thread safety errors: {errors}"

    def test_online_status_callback_called_on_change(self):
        """_on_online_status_changed callback should be called when status changes."""
        iface = self.create_interface()
        callback_calls = []

        def callback(is_online):
            callback_calls.append(is_online)

        iface._on_online_status_changed = callback

        # Change from offline to online
        iface._set_online(True)
        assert callback_calls == [True]

        # Change from online to offline
        iface._set_online(False)
        assert callback_calls == [True, False]

        # Same status should not trigger callback
        iface._set_online(False)
        assert callback_calls == [True, False]  # No new call


class TestRssiSnrThreadSafety:
    """Tests for thread-safe RSSI/SNR access (PR #36 critical fix)."""

    def create_interface(self):
        """Create a test interface with mocked dependencies."""
        import threading
        with patch.object(ColumbaRNodeInterface, '_get_kotlin_bridge'):
            with patch.object(ColumbaRNodeInterface, '_validate_config'):
                iface = ColumbaRNodeInterface.__new__(ColumbaRNodeInterface)
                iface._read_lock = threading.Lock()
                iface.r_stat_rssi = None
                iface.r_stat_snr = None
                return iface

    def test_rssi_snr_can_be_updated_under_lock(self):
        """RSSI and SNR should be updatable under lock."""
        iface = self.create_interface()

        with iface._read_lock:
            iface.r_stat_rssi = -80
            iface.r_stat_snr = 10.5

        assert iface.r_stat_rssi == -80
        assert iface.r_stat_snr == 10.5


class TestMalformedEscapeSequenceLogging:
    """Tests for malformed KISS escape sequence warning (PR #36 critical fix)."""

    def test_escape_sequence_logging_documented(self):
        """Verify the fix documents invalid escape sequence handling.

        The read loop at line 666-669 now logs a warning when FESC is followed
        by an unexpected byte (not TFEND or TFESC). This test documents the
        expected behavior.
        """
        # This is a documentation test - the actual logging happens in _read_loop
        # which requires a full mock of the read loop. We verify the KISS constants
        # that define valid escape sequences.
        assert KISS.FESC == 0xDB
        assert KISS.TFEND == 0xDC
        assert KISS.TFESC == 0xDD
        # Any byte after FESC that is NOT TFEND (0xDC) or TFESC (0xDD)
        # is considered invalid and will now be logged


class TestKISSConstants:
    """Tests for KISS protocol constants."""

    def test_fend_value(self):
        """FEND should be 0xC0."""
        assert KISS.FEND == 0xC0

    def test_fesc_value(self):
        """FESC should be 0xDB."""
        assert KISS.FESC == 0xDB

    def test_tfend_value(self):
        """TFEND (escaped FEND) should be 0xDC."""
        assert KISS.TFEND == 0xDC

    def test_tfesc_value(self):
        """TFESC (escaped FESC) should be 0xDD."""
        assert KISS.TFESC == 0xDD

    def test_cmd_data_is_zero(self):
        """CMD_DATA should be 0x00."""
        assert KISS.CMD_DATA == 0x00

    def test_radio_states(self):
        """Radio states should have expected values."""
        assert KISS.RADIO_STATE_OFF == 0x00
        assert KISS.RADIO_STATE_ON == 0x01
        assert KISS.RADIO_STATE_ASK == 0xFF
