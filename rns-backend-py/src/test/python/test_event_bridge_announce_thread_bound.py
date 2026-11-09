import importlib.util
import sys
import threading
import time
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"

# RNS/Transport.py dispatches every announce to every registered announce
# handler on a *fresh OS thread*. LXMF registers two handlers (delivery +
# propagation) and `event_bridge` one, so a burst of N announces allocates
# ~3N threads, and whatever the handlers do inline is what keeps those
# threads alive. On low-end devices a burst is enough to exhaust the
# process's thread slots and the next `pthread_create` fails with
# `OutOfMemoryError: pthread_create (1040KB max stack) failed: Try again`,
# killing `:reticulum` and every interface with it (columba#1228).
BURST = 40
PEAK_THREAD_BOUND = 16

# Consumer latency observed on-device: aspect resolution, ~10 JNI crossings
# into Kotlin and the Kotlin-side announcement translation, all while several
# announce threads contend for the GIL.
CONSUMER_HOLD_S = 0.01
SLOW_CONSUMER_HOLD_S = 0.2
# A handler that absorbs consumer latency must exceed this by an order of
# magnitude; one that only enqueues stays in the microseconds.
MAX_HANDLER_SECONDS = 0.02


class FakeIdentity:
    hash = b"identity"

    def get_public_key(self):
        return b"public-key"


class RecordingConsumer:
    """Models the Kotlin sink: one `onEvent(payload)` call per announce."""

    def __init__(self, hold_s=0.0):
        self.hold_s = hold_s
        self.payloads = []
        self.in_flight = 0
        self.peak_in_flight = 0
        self._lock = threading.Lock()

    def onEvent(self, payload):
        with self._lock:
            self.in_flight += 1
            self.peak_in_flight = max(self.peak_in_flight, self.in_flight)
        try:
            if self.hold_s:
                time.sleep(self.hold_s)
            with self._lock:
                self.payloads.append(payload)
        finally:
            with self._lock:
                self.in_flight -= 1


class BlockingConsumer:
    """A consumer that parks the dispatch worker until released."""

    def __init__(self, release):
        self.release = release
        self.calls = 0
        self.wait_s = 5.0
        self.payloads = []

    def onEvent(self, payload):
        self.calls += 1
        self.release.wait(self.wait_s)
        self.payloads.append(payload)


class AnnounceDispatchThreadBoundTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        for level in ("LOG_DEBUG", "LOG_INFO", "LOG_WARNING", "LOG_ERROR", "LOG_EXTREME"):
            setattr(rns, level, 1)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(
            rns,
            "Destination",
            types.SimpleNamespace(
                hash_from_name_and_identity=lambda aspect, identity: (
                    b"destination" if aspect == "lxmf.delivery" else b"other"
                )
            ),
        )
        setattr(
            rns,
            "Transport",
            types.SimpleNamespace(
                PATHFINDER_M=128,
                hops_to=lambda destination_hash: 1,
                path_table={},
                register_announce_handler=lambda handler: None,
                deregister_announce_handler=lambda handler: None,
            ),
        )
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_announce_thread_bound_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self._flush()
        setattr(self.module, "_on_announce", None)
        if hasattr(self.module, "_announce_dropped"):
            self.module._announce_dropped = 0

    def tearDown(self):
        setattr(self.module, "_on_announce", None)
        self._flush(timeout=30.0)

    def _flush(self, timeout=5.0):
        """Drain the dispatch queue (absent on the pre-fix module)."""
        flush = getattr(self.module, "_flush_announce_queue", None)
        return flush(timeout=timeout) if flush is not None else True

    def _dispatch_announce(self, handler, index):
        """`RNS.Transport.receive()`'s dispatch shape: one thread per announce."""
        threading.Thread(
            target=handler.received_announce,
            args=(b"destination", FakeIdentity(), b"app-data", bytes([index]), False),
            daemon=True,
        ).start()

    def test_announce_burst_does_not_scale_os_threads(self):
        consumer = RecordingConsumer(hold_s=CONSUMER_HOLD_S)
        setattr(self.module, "_on_announce", consumer)
        handler = self.module._AnnounceHandler()

        peak_threads = 0
        deadline = time.time() + 20.0
        for index in range(BURST):
            peak_threads = max(peak_threads, threading.active_count())
            self._dispatch_announce(handler, index)

        while time.time() < deadline:
            peak_threads = max(peak_threads, threading.active_count())
            if len(consumer.payloads) == BURST:
                break
            time.sleep(0.001)

        self.assertEqual(BURST, len(consumer.payloads))
        self.assertLessEqual(
            peak_threads,
            PEAK_THREAD_BOUND,
            f"an announce burst of {BURST} left {peak_threads} OS threads alive; "
            "RNS allocates one thread per announce per handler, so the handler "
            "must hand the announce off instead of doing the work inline",
        )
        self.assertEqual(
            1,
            consumer.peak_in_flight,
            "announce work ran concurrently inside the RNS-allocated threads",
        )

    def test_received_announce_does_not_absorb_consumer_latency(self):
        consumer = RecordingConsumer(hold_s=SLOW_CONSUMER_HOLD_S)
        setattr(self.module, "_on_announce", consumer)
        handler = self.module._AnnounceHandler()

        worst_seconds = 0.0
        for index in range(5):
            started = time.perf_counter()
            handler.received_announce(b"destination", FakeIdentity(), b"app-data", bytes([index]), False)
            worst_seconds = max(worst_seconds, time.perf_counter() - started)

        self.assertLess(
            worst_seconds,
            MAX_HANDLER_SECONDS,
            f"received_announce blocked for {worst_seconds:.3f}s; it must return "
            "immediately so the thread RNS allocated for it can exit",
        )

    def test_announce_backlog_is_bounded_and_sheds_instead_of_growing(self):
        release = threading.Event()
        consumer = BlockingConsumer(release)
        setattr(self.module, "_on_announce", consumer)
        handler = self.module._AnnounceHandler()

        try:
            # Park the dispatch worker inside the consumer, so everything
            # enqueued below stays in the bounded announce queue.
            handler.received_announce(b"destination", FakeIdentity(), b"app-data", b"p", False)
            deadline = time.time() + 10.0
            while consumer.calls == 0 and time.time() < deadline:
                time.sleep(0.001)
            self.assertGreaterEqual(consumer.calls, 1, "dispatch worker never started")
            # Only the first call needs to park; keep the old inline path from
            # spending a full park per enqueue while the bulk is queued.
            consumer.wait_s = 0.001

            overflow = 100
            for index in range(self.module._ANNOUNCE_QUEUE_MAXSIZE + overflow):
                handler.received_announce(b"destination", FakeIdentity(), b"app-data", bytes([index % 256]), False)

            self.assertEqual(overflow, self.module._announce_dropped)
            self.assertLessEqual(
                self.module._announce_queue.qsize(),
                self.module._ANNOUNCE_QUEUE_MAXSIZE,
            )
        finally:
            release.set()

    def test_teardown_discards_queued_announces(self):
        """A stopped stack's backlog must not surface as fresh traffic.

        `deregister_callbacks()` runs on backend teardown / Apply & Restart.
        Anything still queued then belongs to the stack that just stopped;
        dispatching it after the replacement stack registers would persist old
        announces with the restart's timestamp.
        """
        release = threading.Event()
        consumer = BlockingConsumer(release)
        setattr(self.module, "_on_announce", consumer)
        handler = self.module._AnnounceHandler()

        try:
            # The first announce parks the worker; the rest stay queued.
            handler.received_announce(b"destination", FakeIdentity(), b"app-data", b"p0", False)
            deadline = time.time() + 10.0
            while consumer.calls == 0 and time.time() < deadline:
                time.sleep(0.001)
            self.assertGreaterEqual(consumer.calls, 1, "dispatch worker never started")
            consumer.wait_s = 0.001

            for index in range(3):
                handler.received_announce(b"destination", FakeIdentity(), b"app-data", bytes([index]), False)

            self.module.deregister_callbacks()
            release.set()
            self.assertTrue(self._flush(timeout=10.0))
        finally:
            release.set()

        # Only the announce already in flight when teardown ran is delivered.
        self.assertEqual(1, len(consumer.payloads))

if __name__ == "__main__":
    unittest.main()
