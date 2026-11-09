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
    from types import SimpleNamespace
    import RNS
    import LXMF

    sys.path.insert(0, str(PYTHON_ROOT))
    adapter = importlib.import_module("columba_rnode_interface")
    bridge = importlib.import_module("event_bridge")
    router_module = importlib.import_module("LXMF.LXMRouter")

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
        def __init__(self, frame_bytes, interface):
            self.frame_bytes = frame_bytes
            self.interface = interface

        def read(self):
            if self.frame_bytes:
                result, self.frame_bytes = self.frame_bytes, b""
                return result
            self.interface._running.clear()
            return b""

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

        def make_interface(self):
            # Suppress hardware startup only, not the adapter constructor or
            # KISS parsing. This catches a missing reports_phy_stats declaration.
            with mock.patch.object(adapter.ColumbaRNodeInterface, "_get_kotlin_bridge"), \
                    mock.patch.object(threading.Thread, "start"):
                interface = adapter.ColumbaRNodeInterface(RNS.Transport, {"name": "radio-test"})
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
            kiss = adapter.KISS
            return bytes([kiss.FEND, command]) + kiss.escape(data) + bytes([kiss.FEND])

        def test_signed_opportunistic_bursts_keep_packet_local_radio_metrics(self):
            for usb in (False, True):
                with self.subTest(usb=usb):
                    self.setUp()
                    interface = self.make_interface()
                    self.assertTrue(interface.reports_phy_stats)
                    source = RNS.Destination(RNS.Identity(), RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")
                    destination = RNS.Destination(RNS.Identity(), RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")
                    RNS.Identity.remember(None, source.hash, source.identity.get_public_key())
                    router = self.make_router(destination)
                    packets = [
                        self.signed_packet(destination, source, "negative signal", 3),
                        self.signed_packet(destination, source, "zero signal", 0),
                    ]
                    kiss = adapter.KISS
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
                    hardware = ByteBridge(frames, interface)
                    if usb:
                        interface.usb_bridge = hardware
                    else:
                        interface.kotlin_bridge = hardware
                    interface._running.set()
                    (interface._read_loop_usb if usb else interface._read_loop)()
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

        def test_missing_message_phy_is_not_filled_from_stale_interface(self):
            interface = self.make_interface()
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
