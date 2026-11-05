"""
Inbound LXMF signature state must reach Kotlin so the UI can warn on messages
whose sender signature could not be verified (columba's "unverified sender —
may be forged" banner). Python LXMF delivers both SOURCE_UNKNOWN and
SIGNATURE_INVALID to the delivery callback and annotates the LXMessage with
`signature_validated`; `_lxmf_delivery_callback` must forward it as a definite
boolean — never omit it, never default a real `False` to something truthy.
"""
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


def _fresh_message(**overrides):
    message = types.SimpleNamespace(
        hash=b"message-hash",
        source_hash=b"source-hash",
        destination_hash=b"destination-hash",
        title=b"",
        content=b"hello",
        timestamp=1700000000.0,
        fields=None,
        method=None,
    )
    for key, value in overrides.items():
        setattr(message, key, value)
    return message


class SignatureValidatedBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_WARNING", 2)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(
            rns,
            "Destination",
            types.SimpleNamespace(
                hash_from_name_and_identity=lambda aspect, identity: b"destination"
            ),
        )
        setattr(
            rns,
            "Transport",
            types.SimpleNamespace(
                PATHFINDER_M=128,
                hops_to=lambda destination_hash: 1,
                path_table={},
                register_announce_handler=lambda *args: None,
            ),
        )
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "FIELD_TELEMETRY", 0x02)
        setattr(lxmf, "FIELD_COMMANDS", 0x01)
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_signature_validated_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def _deliver(self, message):
        callback = FakeCallback()
        setattr(self.module, "_on_lxmf_delivery", callback)
        self.module._lxmf_delivery_callback(message)
        self.assertEqual(
            1, len(callback.payloads), "delivery must emit exactly one payload"
        )
        return callback.payloads[0]

    def test_unverified_sender_forwards_false(self):
        payload = self._deliver(_fresh_message(signature_validated=False))
        self.assertIs(False, payload["signature_validated"])

    def test_verified_sender_forwards_true(self):
        payload = self._deliver(_fresh_message(signature_validated=True))
        self.assertIs(True, payload["signature_validated"])

    def test_missing_attribute_falls_back_to_false_not_omitted(self):
        message = _fresh_message()
        self.assertFalse(hasattr(message, "signature_validated"))
        payload = self._deliver(message)
        self.assertIs(
            False,
            payload["signature_validated"],
            "a missing signature_validated must surface as False so the UI warns",
        )


if __name__ == "__main__":
    unittest.main()
