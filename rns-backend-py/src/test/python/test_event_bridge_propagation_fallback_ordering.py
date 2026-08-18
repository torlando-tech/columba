import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class Callback:
    def __init__(self, events, name):
        self.events = events
        self.name = name

    def onEvent(self, payload):
        self.events.append((self.name, payload["hash"]))


class Message:
    def __init__(self):
        self.hash = b"message-hash"
        self.desired_method = 2
        self.try_propagation_on_fail = False
        self.delivery_attempts = 4
        self.packed = b"packed"
        self.propagation_packed = b"propagated"
        self.propagation_stamp = b"stamp"
        self.defer_propagation_stamp = False
        self.delivery_callback = None
        self.failed_callback = None

    def register_delivery_callback(self, callback):
        self.delivery_callback = callback

    def register_failed_callback(self, callback):
        self.failed_callback = callback


class Router:
    outbound_propagation_node = b"relay"

    def __init__(self, events):
        self.events = events

    def handle_outbound(self, message):
        self.events.append(("submit", message.desired_method))


class PropagationFallbackOrderingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_DEBUG", 7)
        setattr(rns, "log", lambda *args, **kwargs: None)
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", type("LXStamper", (), {}))
        setattr(lxmf, "LXMessage", type("LXMessage", (), {"PROPAGATED": 3}))
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location("event_bridge_fallback_test", EVENT_BRIDGE_PATH)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_retry_event_follows_method_selection_and_precedes_submission(self):
        events = []
        message = Message()
        router = Router(events)
        setattr(self.module, "_lxmf_router", router)
        self.module.attach_lxmessage_callbacks(
            message,
            Callback(events, "delivered"),
            Callback(events, "failed"),
            Callback(events, "retrying"),
            try_propagation_on_fail=True,
        )

        assert message.failed_callback is not None
        message.failed_callback(message)

        self.assertEqual(3, message.desired_method)
        self.assertEqual(["retrying", "submit"], [event[0] for event in events])
        self.assertEqual(0, message.delivery_attempts)
        self.assertIsNone(message.packed)
        self.assertIsNone(message.propagation_packed)


if __name__ == "__main__":
    unittest.main()
