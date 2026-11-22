"""Real-RNS regression for the Apply & Restart stamp-queue reset.

The stub-RNS sibling (``test_event_bridge_restart_stamp_queue.py``) proves the
module-side contract, but stubbed queues cannot catch a mismatch with the
pinned RNS package's private ``WorkerQueues`` shape or a worker that the reset
leaves unable to process new work. This test drives the real pinned fork:

  1. a real announce is queued on ``Transport.stamp_queues`` before reset,
  2. ``Reticulum.exit_handler()`` + ``reset_reticulum_for_restart()`` drop it
     so the previous run's Destination is never announced, and
  3. after the reset, a fresh announce is queued, stamped and emitted by a new
     stamp worker.

Apply & Restart normally ends the process (the fresh process starts with
``Transport._should_run`` at its class default ``True``), so the test restores
that flag before constructing the second instance. This pins the queue/worker
contract after reset, not the known in-process-liveness edge case.

Run via ``run_python_tests.py`` against the pinned wheels.
"""
import subprocess
import sys
import unittest
from pathlib import Path

PYTHON_ROOT = Path(__file__).resolve().parents[2] / "main/python"


class RestartStampQueueRealRnsTests(unittest.TestCase):
    def test_restart_stamp_queue_with_real_rns(self):
        # Sibling tests replace RNS in sys.modules; a clean interpreter keeps
        # their stand-ins from weakening this one.
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--real-restart"],
            capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


def _run_real_restart_tests():
    import tempfile
    import time

    import RNS

    sys.path.insert(0, str(PYTHON_ROOT))
    from importlib import import_module
    bridge = import_module("event_bridge")

    class RealRestartTests(unittest.TestCase):
        def test_reset_drops_queued_announce_and_new_worker_emits_fresh_one(self):
            directory = tempfile.TemporaryDirectory()
            Path(directory.name, "config").write_text(
                "[reticulum]\nshare_instance = no\nenable_transport = no\n"
                "\n[interfaces]\n",
                encoding="utf-8",
            )
            # Real stamps at the default value cost 0.8-13 s; the contract
            # under test is queue/worker lifecycle, not proof-of-work speed.
            old_destination_value = RNS.Transport.DESTINATION_VALUE
            RNS.Transport.DESTINATION_VALUE = 0.01
            # exit_handler() detaches stdout/stderr; keep the test reporter.
            saved_stdout, saved_stderr = sys.stdout, sys.stderr
            try:
                RNS.Reticulum(configdir=directory.name, loglevel=RNS.LOG_ERROR)

                stale = RNS.Destination(
                    RNS.Identity(), RNS.Destination.IN,
                    RNS.Destination.SINGLE, "columba", "restart-stale",
                )
                # Hold the worker's condition so the announce stays queued
                # through exit_handler() and the reset; WorkerQueues and the
                # reset both use the same re-entrant lock.
                with RNS.Transport.stamp_queues._cond:
                    stale.announce()
                    self.assertEqual(
                        1, RNS.Transport.stamp_queues.qsize(),
                        "real announce was not queued",
                    )

                    RNS.Reticulum.exit_handler()
                    bridge.reset_reticulum_for_restart()

                    self.assertEqual(
                        0, RNS.Transport.stamp_queues.qsize(),
                        "reset left the previous run's announce queued",
                    )
                    self.assertEqual(
                        {}, stale.path_responses,
                        "reset let the previous run's announce be emitted",
                    )

                sys.stdout, sys.stderr = saved_stdout, saved_stderr

                # The old worker exits once _should_run is False; the reset
                # only forgets workers that have already exited.
                stale_worker = RNS.Transport.stamper_thread
                deadline = time.time() + 5.0
                while (
                    stale_worker is not None
                    and stale_worker.is_alive()
                    and time.time() < deadline
                ):
                    time.sleep(0.05)
                self.assertTrue(
                    stale_worker is None or not stale_worker.is_alive(),
                    "old stamp worker did not exit after exit_handler()",
                )

                # A fresh process starts with _should_run True; restore it so
                # the new instance's threads and stamp worker can run.
                RNS.Transport._should_run = True
                RNS.Reticulum(configdir=directory.name, loglevel=RNS.LOG_ERROR)

                fresh = RNS.Destination(
                    RNS.Identity(), RNS.Destination.IN,
                    RNS.Destination.SINGLE, "columba", "restart-fresh",
                )
                fresh.announce()

                deadline = time.time() + 20.0
                while not fresh.path_responses and time.time() < deadline:
                    time.sleep(0.05)
                self.assertTrue(
                    fresh.path_responses,
                    "fresh announce was never stamped and emitted after reset",
                )
                self.assertEqual(
                    0, RNS.Transport.stamp_queues.qsize(),
                    "fresh announce stayed queued",
                )

                RNS.Reticulum.exit_handler()
                bridge.reset_reticulum_for_restart()
                sys.stdout, sys.stderr = saved_stdout, saved_stderr
            finally:
                # Do not let the second reset's __exit_handler_ran=False make
                # the interpreter's atexit handler run against owner=None.
                RNS.Reticulum._Reticulum__exit_handler_ran = True
                RNS.Transport._should_run = False
                RNS.Transport.DESTINATION_VALUE = old_destination_value
                sys.stdout, sys.stderr = saved_stdout, saved_stderr
                directory.cleanup()

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RealRestartTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    if "--real-restart" in sys.argv:
        raise SystemExit(_run_real_restart_tests())
    unittest.main()
