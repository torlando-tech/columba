"""
The user's "incoming message size" setting must reach LXMF's own
pre-transfer gate (LXMRouter.delivery_per_transfer_limit), otherwise
upstream LXMF's built-in default (DELIVERY_LIMIT = 1000, i.e. 1,000,000
bytes) rejects every inbound link-based (DIRECT) resource above ~1 MB at
advertisement time, before any bytes transfer — making the setting
ineffective for direct delivery (columba#1106).
"""
import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class FakeRouter:
    """Mimics the LXMF defaults the app starts out with."""

    def __init__(self):
        self.delivery_per_transfer_limit = 1000  # LXMRouter.DELIVERY_LIMIT
        self.delivery_callbacks = []

    def register_delivery_callback(self, callback):
        self.delivery_callbacks.append(callback)


def _noop(*args, **kwargs):
    pass


class IncomingMessageSizeLimitBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_WARNING", 2)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(rns, "Destination", types.SimpleNamespace(
            hash_from_name_and_identity=lambda aspect, identity: b"destination"
        ))
        setattr(rns, "Transport", types.SimpleNamespace(
            PATHFINDER_M=128,
            hops_to=lambda destination_hash: 1,
            path_table={},
            register_announce_handler=_noop,
        ))
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", types.SimpleNamespace(
            set_external_generator=lambda *args: None,
        ))
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_incoming_size_limit_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self.module._lxmf_router = None
        self.module._incoming_message_size_limit_kb = 0
        self.module._incoming_message_size_limit_configured = False

    def test_set_without_router_does_not_crash_and_stores_cap(self):
        self.module.set_incoming_message_size_limit(25600)
        self.assertEqual(25600, self.module._incoming_message_size_limit_kb)
        self.assertIsNone(self.module._lxmf_router)

    def test_unlimited_maps_to_large_sentinel_not_none(self):
        # None would crash the pinned LXMF: delivery_resource_advertised()
        # computes delivery_per_transfer_limit * 1000 before its None check.
        router = FakeRouter()
        self.module._lxmf_router = router
        self.module.set_incoming_message_size_limit(0)
        self.assertIsNotNone(router.delivery_per_transfer_limit)
        self.assertGreater(router.delivery_per_transfer_limit, 1_000_000_000)

    def test_binary_kb_converted_to_lxmf_decimal_kb_with_ceiling(self):
        router = FakeRouter()
        self.module._lxmf_router = router

        # 1024 KiB cap -> must allow >= 1024*1024 bytes (1049 decimal KB)
        self.module.set_incoming_message_size_limit(1024)
        self.assertEqual(1049, router.delivery_per_transfer_limit)
        self.assertGreaterEqual(router.delivery_per_transfer_limit * 1000, 1024 * 1024)

        # UI "Unlimited" chip value (131072 KiB)
        self.module.set_incoming_message_size_limit(131072)
        self.assertEqual(134218, router.delivery_per_transfer_limit)
        self.assertGreaterEqual(router.delivery_per_transfer_limit * 1000, 131072 * 1024)

    def test_register_callbacks_reapplies_stored_cap_to_fresh_router(self):
        # A backend restart replaces the router; the stored cap must follow
        # it, or the built-in 1000 KB default silently returns (columba#1106).
        first = FakeRouter()
        self.module._lxmf_router = first
        self.module.set_incoming_message_size_limit(25600)

        fresh = FakeRouter()  # LXMF default: 1000 KB
        self.module.register_callbacks(
            object(), fresh, _noop, _noop, _noop, _noop, _noop
        )
        self.assertEqual(26215, fresh.delivery_per_transfer_limit)

    def test_default_cap_keeps_roughly_one_mb_gate(self):
        # The app default is 1024 KiB; the pre-transfer gate should stay in
        # the same ~1 MB neighbourhood it has always been at, not silently
        # open up.
        router = FakeRouter()
        self.module._lxmf_router = router
        self.module.set_incoming_message_size_limit(1024)
        gate_bytes = router.delivery_per_transfer_limit * 1000
        self.assertGreaterEqual(gate_bytes, 1024 * 1024)
        self.assertLess(gate_bytes, 2 * 1024 * 1024)

    def test_fresh_process_keeps_conservative_gate_until_host_pushes_cap(self):
        # The host pushes the persisted cap asynchronously after backend
        # init (ColumbaApplication, IO coroutine). Until that push lands,
        # the pre-transfer gate must keep LXMF's built-in conservative
        # default (1000 decimal KB) - not the "unlimited" sentinel - so an
        # oversized direct delivery arriving in the init window is refused
        # at advertisement time instead of transferred and dropped after
        # reassembly.
        router = FakeRouter()
        self.module.register_callbacks(
            object(), router, _noop, _noop, _noop, _noop, _noop
        )
        self.assertEqual(1000, router.delivery_per_transfer_limit)

        # Once the host pushes the persisted cap, the gate follows it.
        self.module.set_incoming_message_size_limit(131072)
        self.assertEqual(134218, router.delivery_per_transfer_limit)


if __name__ == "__main__":
    unittest.main()
