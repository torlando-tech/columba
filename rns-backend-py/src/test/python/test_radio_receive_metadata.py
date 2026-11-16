"""Real RNS/LXMF receive regressions, isolated from sibling tests' import stubs.

Requires the pinned RNS and LXMF packages on PYTHONPATH. The synthetic hardware
boundary supplies KISS bytes only; encryption, signing, packet unpacking,
Transport queues, destination decryption and LXMF delivery remain real.
"""
import importlib
import subprocess
import sys
import threading
import unittest
from pathlib import Path
from unittest import mock


PYTHON_ROOT = Path(__file__).resolve().parents[2] / "main/python"


class RadioReceiveMetadataTests(unittest.TestCase):
    def test_real_radio_receive_pipeline(self):
        # Other host tests replace RNS/LXMF in sys.modules. A clean interpreter
        # prevents those stand-ins from silently weakening this integration test.
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--real-radio"],
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


def _run_real_radio_tests():
    import tempfile
    import time
    from types import SimpleNamespace
    import RNS
    import LXMF

    sys.path.insert(0, str(PYTHON_ROOT))
    adapter = importlib.import_module("columba_rnode_interface")
    bridge = importlib.import_module("event_bridge")
    router_module = importlib.import_module("LXMF.LXMRouter")
    from RNS.Interfaces.Android.RNodeInterface import RNodeInterface as UpstreamRNodeInterface
    upstream_rnode = importlib.import_module("RNS.Interfaces.Android.RNodeInterface")

    class DeferredThread:
        jobs = []

        def __init__(self, target, args=(), kwargs=None, **unused):
            self.target = target
            self.args = args
            self.kwargs = kwargs or {}

        def start(self):
            self.jobs.append(self)

        def run(self):
            self.target(*self.args, **self.kwargs)

    class ByteBridge:
        """Synthetic transport: hands upstream's read loop the KISS bytes only.

        Mirrors the members columba_rnode_interface.BridgeConnection exposes, so
        the real upstream read loop (framing, stats, packet accounting) runs.
        """

        CONNECT_TIMEOUT = 7.0

        def __init__(self, frame_bytes, link_open=True):
            self.frame_bytes = frame_bytes
            self.link_open = link_open
            self.written = []

        @property
        def is_open(self):
            return self.link_open

        @property
        def connected(self):
            return self.link_open

        @property
        def device_disappeared(self):
            return False

        def read(self):
            if self.frame_bytes:
                result, self.frame_bytes = self.frame_bytes, b""
                return result
            # Drop the link once the queued frames are consumed: the upstream
            # read loop exits on is_open/connected and stops asking for bytes.
            self.link_open = False
            return b""

        def write(self, data):
            self.written.append(bytes(data))
            return len(data)

        def close(self):
            self.link_open = False

        def cleanup(self):
            self.link_open = False

    class RealRadioTests(unittest.TestCase):
        @classmethod
        def setUpClass(cls):
            cls.directory = tempfile.TemporaryDirectory()
            Path(cls.directory.name, "config").write_text(
                "[reticulum]\nshare_instance = no\nenable_transport = no\n"
                "\n[interfaces]\n",
                encoding="utf-8",
            )
            # Keep real Reticulum defaults and setup. Only pause inbound
            # consumption so both KISS packets can queue before either drains.
            with mock.patch.object(RNS.Transport, "inbound_job", lambda: None):
                cls.reticulum = RNS.Reticulum(configdir=cls.directory.name, loglevel=RNS.LOG_ERROR)

        @classmethod
        def tearDownClass(cls):
            RNS.Transport._should_run = False
            RNS.Reticulum.exit_handler()
            cls.directory.cleanup()

        def setUp(self):
            RNS.Transport.destinations = []
            RNS.Transport.destinations_map = {}
            RNS.Transport.path_table = {}
            RNS.Transport.local_client_rssi_cache = []
            RNS.Transport.local_client_snr_cache = []
            RNS.Transport.local_client_q_cache = []
            self.received = []
            bridge._on_lxmf_delivery = SimpleNamespace(onEvent=self.received.append)
            bridge._collector_enabled = False
            bridge._incoming_message_size_limit_kb = 0
            DeferredThread.jobs = []

        def make_interface(self, hardware, mode="ble"):
            # Suppress the hardware handshake only (upstream's detect/validate
            # would need a live radio); the adapter constructor, the inherited
            # KISS parsing and the read loop all stay real. This catches a
            # missing reports_phy_stats declaration.
            with mock.patch.object(adapter.ColumbaRNodeInterface, "_open_bluetooth_port", return_value=hardware), \
                    mock.patch.object(adapter.ColumbaRNodeInterface, "_open_usb_port", return_value=hardware), \
                    mock.patch.object(UpstreamRNodeInterface, "configure_device"), \
                    mock.patch.object(RNS.vendor.platformutils, "is_android", return_value=True), \
                    mock.patch.object(threading.Thread, "start"):
                interface = adapter.ColumbaRNodeInterface(
                    RNS.Transport,
                    {
                        "name": "radio-test",
                        "connection_mode": mode,
                        "target_device_name": "test-node",
                        # Upstream validates the radio config before it talks to
                        # the device, so these must be legal values.
                        "frequency": 915000000,
                        "bandwidth": 125000,
                        "txpower": 7,
                        "spreadingfactor": 7,
                        "codingrate": 5,
                    },
                )
            # Reticulum's loader normally runs interface_post_init(), which is
            # what sets ifac_size for the transport's packet accounting.
            interface.ifac_size = interface.DEFAULT_IFAC_SIZE
            # Upstream's read loop re-detects the device when the port looks
            # idle (last_port_io starts at 0), which would block on the fake
            # transport; mark the port as freshly active instead.
            interface.last_port_io = time.time()
            interface.online = True
            return interface

        def make_router(self, destination):
            router = object.__new__(LXMF.LXMRouter)
            destination.stamp_cost = None
            router.delivery_destinations = {destination.hash: destination}
            router.store = None
            router.ignored_list = []
            router.delivered_transient_ids_lock = threading.Lock()
            router.locally_delivered_transient_ids = {}
            router.has_message = lambda message_hash: message_hash in router.locally_delivered_transient_ids
            router.register_delivery_callback(bridge._lxmf_delivery_callback)
            destination.set_packet_callback(router.delivery_packet)
            return router

        def signed_packet(self, destination, source, content, hops):
            message = LXMF.LXMessage(
                destination, source, content, desired_method=LXMF.LXMessage.OPPORTUNISTIC,
            )
            message.pack()
            packet = RNS.Packet(destination, message.packed[LXMF.LXMessage.DESTINATION_LENGTH:])
            packet.hops = hops
            packet.pack()
            return packet

        def frame(self, command, data):
            kiss = upstream_rnode.KISS
            return bytes([kiss.FEND, command]) + kiss.escape(data) + bytes([kiss.FEND])

        def test_signed_opportunistic_bursts_keep_packet_local_radio_metrics(self):
            for usb in (False, True):
                with self.subTest(usb=usb):
                    self.setUp()
                    source = RNS.Destination(RNS.Identity(), RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")
                    destination = RNS.Destination(RNS.Identity(), RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")
                    RNS.Identity.remember(None, source.hash, source.identity.get_public_key())
                    router = self.make_router(destination)
                    packets = [
                        self.signed_packet(destination, source, "negative signal", 3),
                        self.signed_packet(destination, source, "zero signal", 0),
                    ]
                    kiss = upstream_rnode.KISS
                    frames = (
                        self.frame(kiss.CMD_STAT_RSSI, bytes([85]))  # -72 dBm
                        + self.frame(kiss.CMD_STAT_SNR, bytes([226]))  # signed -30 / 4
                        + self.frame(kiss.CMD_DATA, packets[0].raw)
                        + self.frame(kiss.CMD_STAT_RSSI, bytes([157]))  # 0 dBm
                        + self.frame(kiss.CMD_STAT_SNR, bytes([0]))
                        + self.frame(kiss.CMD_DATA, packets[1].raw)
                        + self.frame(kiss.CMD_STAT_RSSI, bytes([37]))  # unrelated later stats
                        + self.frame(kiss.CMD_STAT_SNR, bytes([36]))
                    )
                    hardware = ByteBridge(frames)
                    interface = self.make_interface(hardware, "usb" if usb else "ble")
                    self.assertTrue(interface.reports_phy_stats)
                    # The loop exits when the fake link drops; the reconnect
                    # supervisor would then retry the (already closed)
                    # transport forever, which is not what this test is about.
                    # Do not patch threading.Thread here: process_incoming()
                    # hands each frame to RNS.Transport on a real thread.
                    with mock.patch.object(interface, "reconnect_port"):
                        interface.readLoop()
                    # Dropping the fake link marks the device offline; the radio
                    # in this scenario is up, and Transport._inbound() ignores
                    # packets from an offline interface.
                    interface.online = True
                    self.assertEqual(2, RNS.Transport.inbound_queues.qsize())
                    self.assertEqual((-120, 9.0), (interface.get_rssi(), interface.get_snr()))
                    queued = [RNS.Transport.inbound_queues.get(), RNS.Transport.inbound_queues.get()]
                    for packet, hops in zip(queued, [4, 1]):
                        self.assertIs(interface, packet.receiving_interface)
                        self.assertEqual(hops, packet.hops)
                    # Deferred delivery threads ensure an interface's latest
                    # readings cannot masquerade as this packet's PHY metadata.
                    with mock.patch.object(router_module.threading, "Thread", DeferredThread), \
                            mock.patch.object(RNS.Transport, "outbound", return_value=False):
                        for packet in queued:
                            RNS.Transport._inbound(packet)
                    self.assertEqual(2, len(DeferredThread.jobs))
                    for job in DeferredThread.jobs:
                        job.run()
                    self.assertEqual(2, len(self.received))
                    for payload, expected, content in zip(
                        self.received, [(-72, -7.5, 4), (0, 0.0, 1)],
                        ["negative signal", "zero signal"],
                    ):
                        self.assertTrue(payload["signature_validated"])
                        self.assertEqual(content, payload["content"])
                        self.assertEqual(LXMF.LXMessage.OPPORTUNISTIC, payload["method"])
                        self.assertEqual(expected, (payload["rssi"], payload["snr"], payload["receiving_hops"]))
                        self.assertEqual("ColumbaRNodeInterface[radio-test]", payload["receiving_interface"])
                    self.assertEqual({}, RNS.Transport.path_table)
                    for packet, expected in zip(queued, [(-72, -7.5), (0, 0.0)]):
                        self.assertEqual(expected[0], RNS.Reticulum.get_instance().get_packet_rssi(packet.packet_hash))
                        self.assertEqual(expected[1], RNS.Reticulum.get_instance().get_packet_snr(packet.packet_hash))
                    self.assertEqual(2, len(RNS.Transport.local_client_rssi_cache))
                    self.assertEqual(2, len(RNS.Transport.local_client_snr_cache))
                    self.assertEqual([], RNS.Transport.local_client_q_cache)

        def test_device_rewritten_tx_power_still_validates_the_radio(self):
            # Firmware rewrites TX power and echoes the achievable value (the
            # PA gain table floor: 11 dBm on a 735D). Upstream's own read loop
            # decodes the echo; validation must adopt it, or a sub-floor
            # request leaves the radio offline (issue #1255).
            kiss = upstream_rnode.KISS
            frames = (
                self.frame(kiss.CMD_FREQUENCY, (915000000).to_bytes(4, "big"))
                + self.frame(kiss.CMD_BANDWIDTH, (125000).to_bytes(4, "big"))
                + self.frame(kiss.CMD_TXPOWER, bytes([11]))
                + self.frame(kiss.CMD_SF, bytes([7]))
                + self.frame(kiss.CMD_RADIO_STATE, bytes([kiss.RADIO_STATE_OFF]))
            )
            hardware = ByteBridge(frames)
            interface = self.make_interface(hardware)
            interface.txpower = 1  # below what the board's PA can emit
            with mock.patch.object(interface, "reconnect_port"):
                interface.readLoop()
            self.assertEqual(11, interface.r_txpower)

            with mock.patch.object(upstream_rnode, "sleep", lambda *_: None):
                self.assertTrue(interface.validateRadioState())
            self.assertEqual(11, interface.txpower)

        def test_missing_message_phy_is_not_filled_from_stale_interface(self):
            interface = self.make_interface(ByteBridge(b""))
            interface.r_stat_rssi, interface.r_stat_snr = -40, 12.0
            source = RNS.Destination(RNS.Identity(), RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")
            destination = RNS.Destination(RNS.Identity(), RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")
            RNS.Identity.remember(None, source.hash, source.identity.get_public_key())
            message = LXMF.LXMessage(destination, source, "no per-packet stats")
            message.pack()
            incoming = LXMF.LXMessage.unpack_from_bytes(message.packed)
            self.assertTrue(incoming.signature_validated)
            incoming.receiving_interface = interface
            incoming.receiving_hops = 1
            bridge._lxmf_delivery_callback(incoming)
            self.assertEqual(1, len(self.received))
            self.assertIsNone(self.received[0]["rssi"])
            self.assertIsNone(self.received[0]["snr"])

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RealRadioTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    if "--real-radio" in sys.argv:
        raise SystemExit(_run_real_radio_tests())
    unittest.main()
