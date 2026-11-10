import importlib.util
import inspect
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class FakeCallback:
    def __init__(self):
        self.payloads = []

    def onEvent(self, payload):
        self.payloads.append(payload)


class FakeIdentity:
    hash = b"identity"

    def get_public_key(self):
        return b"public-key"


class AnnounceAspectFilterTests(unittest.TestCase):
    """RNS 1.6.0 per-aspect announce handler registration.

    RNS 1.6.0 keys announce handlers by the name hash of `aspect_filter` and
    only invokes a handler for announces whose embedded name hash matches. An
    `aspect_filter = None` catch-all therefore forces every announce on the
    network through the dispatch queue (thread hand-off, JNI crossing, Room
    write) only for the aspect filter to drop it afterwards — and forces the
    worker to re-derive the aspect by hashing. Registration must be one
    handler per tracked aspect with no catch-all, and dispatch must trust the
    receiving handler's aspect.
    """

    def setUp(self):
        self.registered = []
        self.deregistered = []
        self.module = self._load_module()

    def tearDown(self):
        setattr(self.module, "_on_announce", None)

    def _load_module(self):
        rns = types.ModuleType("RNS")
        for level in ("LOG_DEBUG", "LOG_INFO", "LOG_WARNING", "LOG_ERROR", "LOG_EXTREME"):
            setattr(rns, level, 1)
        setattr(rns, "log", lambda *args, **kwargs: None)

        def _no_aspect_resolution(aspect, identity):
            raise AssertionError(
                "announce dispatch must not re-derive the aspect — the "
                "receiving handler's aspect_filter is the aspect"
            )

        setattr(rns, "Destination", types.SimpleNamespace(
            hash_from_name_and_identity=_no_aspect_resolution,
            name_hash=lambda aspect: b"nh-" + aspect.encode(),
        ))
        setattr(rns, "Transport", types.SimpleNamespace(
            PATHFINDER_M=128,
            hops_to=lambda destination_hash: 1,
            path_table={},
            register_announce_handler=self.registered.append,
            deregister_announce_handler=self.deregistered.append,
        ))
        sys.modules["RNS"] = rns
        sys.modules.setdefault("LXMF", types.ModuleType("LXMF"))

        spec = importlib.util.spec_from_file_location(
            f"event_bridge_aspect_filter_test_{id(self)}", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _register(self, callback):
        self.module.register_callbacks(
            sys.modules["RNS"].Transport,  # rns_transport (non-None gates teardown)
            None,  # lxmf_router
            callback,
            FakeCallback(),
            FakeCallback(),
            FakeCallback(),
            FakeCallback(),
        )

    def test_registers_one_handler_per_aspect_and_no_catch_all(self):
        self._register(FakeCallback())

        self.assertEqual(len(self.module._KNOWN_ASPECTS), len(self.registered))
        self.assertEqual(
            sorted(self.module._KNOWN_ASPECTS),
            sorted(handler.aspect_filter for handler in self.registered),
        )
        self.assertTrue(
            all(handler.aspect_filter is not None for handler in self.registered),
            "a None aspect_filter re-admits every announce on the network into "
            "the dispatch queue, undoing the ingress filtering",
        )
        self.assertTrue(
            all(handler.receive_path_responses is True for handler in self.registered),
            "path responses route to the aspect handler; without them contact "
            "identity resolution from a bare destination hash breaks",
        )

    def test_handler_signature_carries_path_response_flag(self):
        # RNS 1.6.0 inspects the callback signature; only 3/4/5-parameter
        # forms are accepted and only the 5-parameter form receives
        # `is_path_response`, which the announce stream persists.
        params = inspect.signature(
            self.module._AnnounceHandler("lxmf.delivery").received_announce
        ).parameters
        self.assertEqual(5, len(params))
        self.assertIn("is_path_response", params)

    def test_dispatch_carries_handler_aspect_without_resolution(self):
        callback = FakeCallback()
        setattr(self.module, "_on_announce", callback)

        handler = self.module._AnnounceHandler("lxmf.propagation")
        handler.received_announce(
            b"destination", FakeIdentity(), b"app-data", b"packet-hash", False
        )
        self.assertTrue(
            getattr(self.module, "_flush_announce_queue", lambda **kw: True)(timeout=5.0)
        )

        # The stubbed RNS.Destination.hash_from_name_and_identity raises, so
        # reaching this assertion already proves the worker did not re-derive
        # the aspect by hashing.
        self.assertEqual(1, len(callback.payloads))
        self.assertEqual("lxmf.propagation", callback.payloads[0]["aspect"])
        self.assertEqual(1, callback.payloads[0]["hops"])

    def test_deregister_deregisters_every_registered_handler(self):
        self._register(FakeCallback())
        registered = list(self.registered)

        self.module.deregister_callbacks()

        self.assertEqual(registered, self.deregistered)
        self.assertEqual([], self.module._announce_handlers)


if __name__ == "__main__":
    unittest.main()
