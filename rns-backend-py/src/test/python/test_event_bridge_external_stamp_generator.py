import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = (
    Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"
)


class FakeStamper:
    registrations = []

    @classmethod
    def set_external_generator(cls, *args):
        cls.registrations.append(args)


class FakeCallback:
    def __init__(self):
        self.calls = []

    def generate(self, workblock, stamp_cost, cancellation_token):
        self.calls.append((workblock, stamp_cost, cancellation_token))
        return [b"proof", 17]


class ExternalStampGeneratorBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "log", lambda *args, **kwargs: None)
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", FakeStamper)
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_external_stamp_generator_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        FakeStamper.registrations.clear()

    def test_bridge_registers_three_arg_generator_and_forwards_token(self):
        callback = FakeCallback()
        token = object()

        self.module.install_external_stamp_generator(callback)

        wrapper, cancellation_callback, pass_token = FakeStamper.registrations[-1]
        self.assertIsNone(cancellation_callback)
        self.assertTrue(pass_token)
        self.assertEqual((b"proof", 17), wrapper(b"work", 9, token))
        self.assertEqual([(b"work", 9, token)], callback.calls)

    def test_repeated_register_and_stop_leaves_no_stale_callback(self):
        self.module.install_external_stamp_generator(FakeCallback())
        self.module.uninstall_external_stamp_generator()
        self.module.install_external_stamp_generator(FakeCallback())
        self.module.uninstall_external_stamp_generator()

        self.assertEqual((None,), FakeStamper.registrations[-1])
        self.assertEqual(4, len(FakeStamper.registrations))


if __name__ == "__main__":
    unittest.main()