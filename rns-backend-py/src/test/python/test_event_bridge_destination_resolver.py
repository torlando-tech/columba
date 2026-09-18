import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class FakeMessage:
    DIRECT = 1

    def __init__(self, destination, source, content, fields, desired_method):
        self.destination = destination
        self.source = source
        self.content = content
        self.fields = fields
        self.desired_method = desired_method


class FakeResolver:
    def __init__(self, destination):
        self.destination = destination
        self.identities = []

    def resolve(self, identity):
        self.identities.append(identity)
        return self.destination


class FakeRouter:
    def __init__(self):
        self.messages = []

    def handle_outbound(self, message):
        self.messages.append(message)


class DestinationResolverBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        rns.LOG_DEBUG = 0
        rns.LOG_WARNING = 1
        rns.LOG_ERROR = 3
        rns.log = lambda *args, **kwargs: None
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        lxmf.FIELD_TELEMETRY_STREAM = 23
        lxmf.LXMessage = FakeMessage
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_destination_resolver_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self.router = FakeRouter()
        self.module._lxmf_router = self.router
        self.module._collected_telemetry.clear()
        self.module._local_lxmf_destination = lambda: "local"
        self.module.uninstall_destination_resolver()

    def test_telemetry_response_uses_installed_kotlin_destination_resolver(self):
        identity = object()
        resolver = FakeResolver("coordinated-destination")
        self.module.install_destination_resolver(resolver)

        self.module._send_telemetry_stream_response(b"requester", identity, 0)

        self.assertEqual([identity], resolver.identities)
        self.assertEqual(1, len(self.router.messages))
        self.assertEqual("coordinated-destination", self.router.messages[0].destination)

    def test_response_is_skipped_when_resolver_is_unavailable(self):
        self.module._send_telemetry_stream_response(b"requester", object(), 0)

        self.assertEqual([], self.router.messages)


if __name__ == "__main__":
    unittest.main()
