"""Regression tests for event_bridge.reset_reticulum_for_restart stamp queues.

RNS 1.6.1 emits network stamps by default, so announces and path requests are
queued on the class-level `Transport.stamp_queues` worker queue. Columba
restarts the RNS stack in-process ("Apply & Restart"); queued jobs still hold
Destination objects from the previous run, and the next run's stamp worker
would emit announces for a dead instance. The reset must drop queued jobs and
forget an exited worker.
"""
import importlib.util
import sys
import threading
import types
from collections import deque
from pathlib import Path
import unittest

EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class _FakeStampQueues:
    def __init__(self):
        self._cond = threading.RLock()
        self._queues = [deque(), deque()]


class _FakeThread:
    def __init__(self, alive):
        self._alive = alive

    def is_alive(self):
        return self._alive


class _FakeBackbone:
    @staticmethod
    def deregister_listeners():
        pass


class RestartStampQueueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        for name, level in (
            ("LOG_DEBUG", 1),
            ("LOG_WARNING", 2),
            ("LOG_ERROR", 3),
            ("LOG_NOTICE", 4),
        ):
            setattr(rns, name, level)
        rns.log = lambda *args, **kwargs: None
        rns.Reticulum = type("Reticulum", (), {})
        rns.Transport = type("Transport", (), {})

        interfaces_pkg = types.ModuleType("RNS.Interfaces")
        backbone_mod = types.ModuleType("RNS.Interfaces.BackboneInterface")
        backbone_mod.BackboneInterface = _FakeBackbone
        sys.modules["RNS"] = rns
        sys.modules["RNS.Interfaces"] = interfaces_pkg
        sys.modules["RNS.Interfaces.BackboneInterface"] = backbone_mod
        sys.modules["LXMF"] = types.ModuleType("LXMF")

        spec = importlib.util.spec_from_file_location(
            "event_bridge_restart_stamp_queue_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)
        cls.rns = rns

    def setUp(self):
        transport = self.rns.Transport
        transport.owner = object()
        transport.interfaces = []
        transport.stamp_queues = _FakeStampQueues()
        transport.stamp_queues._queues[0].append(("stale-pr", ("pr", b"x", None, None, False)))
        transport.stamp_queues._queues[1].append(("stale-announce", ("announce", object(), None, False, None, None)))
        transport.stamper_thread = _FakeThread(alive=False)

    def test_queued_stamp_jobs_from_the_previous_run_are_dropped(self):
        self.module.reset_reticulum_for_restart()

        self.assertEqual([], list(self.rns.Transport.stamp_queues._queues[0]))
        self.assertEqual([], list(self.rns.Transport.stamp_queues._queues[1]))

    def test_exited_stamp_worker_is_forgotten(self):
        self.module.reset_reticulum_for_restart()

        self.assertIsNone(self.rns.Transport.stamper_thread)

    def test_running_stamp_worker_is_kept_for_reuse(self):
        live = _FakeThread(alive=True)
        self.rns.Transport.stamper_thread = live

        self.module.reset_reticulum_for_restart()

        self.assertIs(live, self.rns.Transport.stamper_thread)
