import importlib.util
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


class AnnounceProvenanceBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(rns, "Destination", types.SimpleNamespace(
            hash_from_name_and_identity=lambda aspect, identity: b"destination"
            if aspect == "lxmf.delivery"
            else b"other"
        ))
        setattr(rns, "Transport", types.SimpleNamespace(
            PATHFINDER_M=128,
            hops_to=lambda destination_hash: 1,
            path_table={},
        ))
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(
            lxmf,
            "LXStamper",
            types.SimpleNamespace(set_external_generator=lambda *args: None),
        )
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_announce_provenance_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_announce_callback_forwards_packet_hash_and_path_response(self):
        callback = FakeCallback()
        setattr(self.module, "_on_announce", callback)

        self.module._AnnounceHandler().received_announce(
            b"destination",
            FakeIdentity(),
            b"app-data",
            b"packet-hash",
            True,
        )

        self.assertEqual(1, len(callback.payloads))
        self.assertEqual(
            "7061636b65742d68617368",
            callback.payloads[0]["announce_packet_hash"],
        )
        self.assertTrue(callback.payloads[0]["is_path_response"])


if __name__ == "__main__":
    unittest.main()
