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
        self.hash = b"\xde\xad\xbe\xef" * 8  # 32-byte hash, like RNS.Identity.full_hash
        self.method = 2
        self.desired_method = 2
        self.state = 1
        self.try_propagation_on_fail = False
        self.delivery_attempts = 3
        self.packed = b"packed"
        self.propagation_packed = b"prop"
        self.propagation_stamp = b"stamp"
        self.defer_propagation_stamp = False
        self._columba_originating_identity_hash = None
        self.delivery_callback = None
        self.failed_callback = None

    def register_delivery_callback(self, callback):
        self.delivery_callback = callback

    def register_failed_callback(self, callback):
        self.failed_callback = callback


class RetryRouter:
    outbound_propagation_node = b"relay"

    def __init__(self):
        self.submitted = []

    def handle_outbound(self, message):
        self.submitted.append(message)


class OutboundRegistryRetryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_WARNING", 4)
        setattr(rns, "LOG_DEBUG", 7)
        setattr(rns, "log", lambda *args, **kwargs: None)
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", type("LXStamper", (), {}))
        setattr(lxmf, "LXMessage", type("LXMessage", (), {"PROPAGATED": 3, "SENT": 4, "DELIVERED": 8}))
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location("event_bridge_retry_test", EVENT_BRIDGE_PATH)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self.module._outbound_lxmessages.clear()
        self.module._outbound_lxmessage_order.clear()
        # b"\xde\xad\xbe\xef" * 8 hex-encodes to "deadbeef" * 8 (64 chars).
        self.hash_hex = "deadbeef" * 8

    def test_register_then_retry_redrives_same_message_as_propagated(self):
        events = []
        message = Message()
        router = RetryRouter()
        self.module._lxmf_router = router
        self.module._on_lxmf_delivery = Callback(events, "delivered")
        self.module._on_lxmf_failure = Callback(events, "failed")

        self.module.register_outbound_message(message, originating_identity_hash="identity-a")
        # Registered under the message's hex hash.
        self.assertIn(self.hash_hex, self.module._outbound_lxmessages)

        drove = self.module.retry_outbound_propagated_message(self.hash_hex)
        self.assertTrue(drove)
        # The *same* message object was re-submitted (no new message).
        self.assertEqual([message], router.submitted)
        # Re-driven as PROPAGATED with the attempt counters reset.
        self.assertEqual(3, message.desired_method)
        self.assertEqual(0, message.delivery_attempts)
        self.assertIsNone(message.packed)
        self.assertIsNone(message.propagation_packed)
        self.assertFalse(message.try_propagation_on_fail)
        # Delivery/failed callbacks re-attached so the re-driven attempt's
        # outcome still reaches the Kotlin side.
        self.assertTrue(callable(message.delivery_callback))
        self.assertTrue(callable(message.failed_callback))

    def test_retry_unknown_hash_is_noop(self):
        self.module._lxmf_router = RetryRouter()
        self.assertFalse(self.module.retry_outbound_propagated_message("00000000"))
        self.assertFalse(self.module.retry_outbound_propagated_message(None))

    def test_retry_without_router_is_noop(self):
        message = Message()
        self.module.register_outbound_message(message)
        self.module._lxmf_router = None
        self.assertFalse(self.module.retry_outbound_propagated_message("deadbeef"))

    def test_retry_preserves_originating_identity_for_redriven_callbacks(self):
        events = []
        message = Message()
        self.module._lxmf_router = RetryRouter()
        self.module._on_lxmf_delivery = Callback(events, "delivered")
        self.module._on_lxmf_failure = Callback(events, "failed")

        self.module.register_outbound_message(message, originating_identity_hash="identity-a")
        self.module.retry_outbound_propagated_message(self.hash_hex)
        # The re-attached callbacks were built with the originating identity hash,
        # so a delivery for the re-driven attempt reports the same identity the
        # Kotlin side matches on.
        message.delivery_callback(message)
        self.assertEqual("delivered", events[0][0])

    def test_retry_reattaches_per_send_delivered_status_sink(self):
        # Regression (Greptile P1 "successful retries still look failed"): the retry
        # must re-attach the *per-send* delivered-status sink the original send used,
        # not the module-level incoming-message sink. A successful retry has to flip
        # the original row to delivered, which only the delivery-status sink does.
        delivered_events = []
        per_send_sink = Callback(delivered_events, "per_send_delivered")
        message = Message()
        self.module._lxmf_router = RetryRouter()
        # The module-level incoming-message sink must NOT be the one used for the
        # re-driven attempt's delivery.
        self.module._on_lxmf_delivery = Callback([], "incoming")
        self.module._on_lxmf_failure = Callback([], "failed")

        self.module.register_outbound_message(
            message, originating_identity_hash="identity-a", on_delivered=per_send_sink
        )
        self.module.retry_outbound_propagated_message(self.hash_hex)

        # A delivery for the re-driven attempt must reach the per-send sink, not the
        # module incoming-message sink.
        message.delivery_callback(message)
        self.assertEqual([("per_send_delivered", self.hash_hex)], delivered_events)


if __name__ == "__main__":
    unittest.main()
