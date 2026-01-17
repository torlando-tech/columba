"""
Unit tests for signal_quality.py

Tests signal quality extraction from Reticulum interfaces.
"""
import pytest
from unittest.mock import Mock, MagicMock


class TestExtractSignalMetrics:
    """Tests for extract_signal_metrics()"""

    def test_returns_none_for_none_interface(self):
        """When interface is None, should return (None, None)"""
        from signal_quality import extract_signal_metrics

        rssi, snr = extract_signal_metrics(None)

        assert rssi is None
        assert snr is None

    def test_extracts_rssi_from_rnode_interface(self):
        """RNode interface with get_rssi() should return RSSI value"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -75

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi == -75
        mock_interface.get_rssi.assert_called_once()

    def test_extracts_snr_from_rnode_interface(self):
        """RNode interface with get_snr() should return SNR value"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -70
        mock_interface.get_snr.return_value = 8.5

        rssi, snr = extract_signal_metrics(mock_interface)

        assert snr == 8.5
        mock_interface.get_snr.assert_called_once()

    def test_extracts_both_rssi_and_snr(self):
        """RNode interface should return both RSSI and SNR"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -85
        mock_interface.get_snr.return_value = 5.0

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi == -85
        assert snr == 5.0

    def test_handles_interface_without_get_rssi(self):
        """Interface without get_rssi attribute should return None for RSSI"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock(spec=[])  # No attributes

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi is None
        assert snr is None

    def test_handles_interface_without_get_snr(self):
        """Interface with get_rssi but without get_snr should return RSSI only"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -60
        # Remove get_snr attribute
        del mock_interface.get_snr

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi == -60
        assert snr is None

    def test_handles_get_rssi_returning_none(self):
        """When get_rssi() returns None, should return None for RSSI"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = None
        mock_interface.get_snr.return_value = 10.0

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi is None
        assert snr == 10.0

    def test_handles_get_snr_returning_none(self):
        """When get_snr() returns None, should return None for SNR"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -80
        mock_interface.get_snr.return_value = None

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi == -80
        assert snr is None

    def test_handles_exception_in_get_rssi(self):
        """When get_rssi() throws exception, should return None for RSSI"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.side_effect = Exception("Radio error")
        mock_interface.get_snr.return_value = 5.0

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi is None
        assert snr == 5.0

    def test_handles_exception_in_get_snr(self):
        """When get_snr() throws exception, should return None for SNR"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -70
        mock_interface.get_snr.side_effect = Exception("Radio error")

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi == -70
        assert snr is None

    def test_converts_rssi_to_int(self):
        """RSSI should be converted to int"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -75.5  # Float from hardware

        rssi, snr = extract_signal_metrics(mock_interface)

        assert rssi == -75
        assert isinstance(rssi, int)

    def test_converts_snr_to_float(self):
        """SNR should be converted to float"""
        from signal_quality import extract_signal_metrics

        mock_interface = Mock()
        mock_interface.get_rssi.return_value = -70
        mock_interface.get_snr.return_value = 8  # Int from hardware

        rssi, snr = extract_signal_metrics(mock_interface)

        assert snr == 8.0
        assert isinstance(snr, float)


class TestAddSignalToMessageEvent:
    """Tests for add_signal_to_message_event()"""

    def test_adds_rssi_when_available(self):
        """When RSSI is provided, should add it to message event"""
        from signal_quality import add_signal_to_message_event

        event = {"message_hash": "abc123"}
        add_signal_to_message_event(event, rssi=-75, snr=None)

        assert event["rssi"] == -75
        assert "snr" not in event

    def test_adds_snr_when_available(self):
        """When SNR is provided, should add it to message event"""
        from signal_quality import add_signal_to_message_event

        event = {"message_hash": "abc123"}
        add_signal_to_message_event(event, rssi=None, snr=8.5)

        assert "rssi" not in event
        assert event["snr"] == 8.5

    def test_adds_both_when_available(self):
        """When both RSSI and SNR are provided, should add both"""
        from signal_quality import add_signal_to_message_event

        event = {"message_hash": "abc123"}
        add_signal_to_message_event(event, rssi=-80, snr=5.0)

        assert event["rssi"] == -80
        assert event["snr"] == 5.0

    def test_does_not_modify_when_both_none(self):
        """When both values are None, should not modify event"""
        from signal_quality import add_signal_to_message_event

        event = {"message_hash": "abc123"}
        add_signal_to_message_event(event, rssi=None, snr=None)

        assert "rssi" not in event
        assert "snr" not in event

    def test_preserves_existing_event_fields(self):
        """Should not remove existing fields from event"""
        from signal_quality import add_signal_to_message_event

        event = {
            "message_hash": "abc123",
            "content": "Hello",
            "timestamp": 1234567890,
        }
        add_signal_to_message_event(event, rssi=-75, snr=10.0)

        assert event["message_hash"] == "abc123"
        assert event["content"] == "Hello"
        assert event["timestamp"] == 1234567890
        assert event["rssi"] == -75
        assert event["snr"] == 10.0
