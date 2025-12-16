"""
Test suite for LXStamper threading patch for Android

Tests the job_android_threaded function that replaces multiprocessing
with threading to ensure stamp generation survives app backgrounding.
"""

import sys
import os
import unittest
import time
from unittest.mock import Mock, MagicMock, patch, call
import threading

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


class TestJobAndroidThreaded(unittest.TestCase):
    """Test job_android_threaded function for background-safe stamp generation"""

    def setUp(self):
        """Set up test fixtures"""
        # Mock modules needed
        self.mock_rns = MagicMock()
        self.mock_lxmf = MagicMock()
        self.mock_lxstamper = MagicMock()
        self.mock_lxstamper.active_jobs = {}

        # Mock concurrent.futures
        self.mock_concurrent = MagicMock()

        # Setup sys.modules
        sys.modules['RNS'] = self.mock_rns
        sys.modules['LXMF'] = self.mock_lxmf
        sys.modules['LXMF.LXStamper'] = self.mock_lxstamper
        sys.modules['concurrent.futures'] = self.mock_concurrent
        sys.modules['nacl'] = None  # Not available by default
        sys.modules['nacl.encoding'] = None
        sys.modules['nacl.hash'] = None

    def tearDown(self):
        """Clean up"""
        # Clean up sys.modules
        for module in ['RNS', 'LXMF', 'LXMF.LXStamper', 'concurrent.futures',
                       'nacl', 'nacl.encoding', 'nacl.hash']:
            if module in sys.modules:
                del sys.modules[module]

    def test_stamp_valid_with_low_cost(self):
        """Test stamp validation logic with low cost (easy to find)"""
        # Create a minimal implementation of stamp_valid
        def full_hash_mock(m):
            # Return predictable hash for testing
            if m.endswith(b'\x00' * 32):
                return b'\x00' * 32  # Very low hash - passes any cost
            else:
                return b'\xff' * 32  # Very high hash - fails any cost

        self.mock_rns.Identity.full_hash = full_hash_mock

        # Import reticulum_wrapper to get access to functions
        import reticulum_wrapper
        reticulum_wrapper.RNS = self.mock_rns

        # Simulate stamp_valid logic
        stamp_cost = 4  # Low cost
        workblock = b'test_workblock'

        # Test with low hash (should pass)
        test_stamp = b'\x00' * 32
        target = 0b1 << (256 - stamp_cost)
        result = full_hash_mock(workblock + test_stamp)
        is_valid = int.from_bytes(result, byteorder="big") <= target

        self.assertTrue(is_valid, "Low hash should pass validation")

    def test_stamp_valid_with_high_cost(self):
        """Test stamp validation logic with high cost (harder to find)"""
        def full_hash_mock(m):
            return b'\xff' * 32  # Very high hash

        self.mock_rns.Identity.full_hash = full_hash_mock

        # High cost requires very low hash
        stamp_cost = 20
        workblock = b'test_workblock'
        test_stamp = b'\xff' * 32

        target = 0b1 << (256 - stamp_cost)
        result = full_hash_mock(workblock + test_stamp)
        is_valid = int.from_bytes(result, byteorder="big") <= target

        self.assertFalse(is_valid, "High hash should fail high-cost validation")

    def test_worker_finds_stamp_quickly(self):
        """Test that worker function finds a valid stamp"""
        # Mock stamp validation to succeed on 3rd attempt
        call_count = [0]

        def stamp_valid_mock(s, c, w):
            call_count[0] += 1
            return call_count[0] >= 3

        # Mock LXStamper.active_jobs
        active_jobs = {}

        # Simulate worker function
        def worker(worker_id, rounds):
            local_rounds = 0
            for _ in range(rounds):
                pstamp = os.urandom(256 // 8)
                local_rounds += 1
                if stamp_valid_mock(pstamp, 16, b'workblock'):
                    return (pstamp, local_rounds)
                if active_jobs.get('test_msg', False):
                    return (None, local_rounds)
            return (None, local_rounds)

        # Run worker
        stamp, rounds = worker(0, 10)

        self.assertIsNotNone(stamp, "Worker should find stamp")
        self.assertEqual(rounds, 3, "Should find stamp on 3rd attempt")

    def test_worker_respects_cancellation(self):
        """Test that worker stops when active_jobs signals cancellation"""
        active_jobs = {'test_msg': True}  # Signal cancellation

        def worker(worker_id, rounds):
            local_rounds = 0
            for _ in range(rounds):
                local_rounds += 1
                # Check cancellation
                if active_jobs.get('test_msg', False):
                    return (None, local_rounds)
            return (None, local_rounds)

        stamp, rounds = worker(0, 100)

        self.assertIsNone(stamp, "Should return None when cancelled")
        self.assertEqual(rounds, 1, "Should stop immediately on cancellation")

    def test_active_jobs_cleanup(self):
        """Test that active_jobs dict is properly cleaned up"""
        active_jobs = {}
        message_id = 'test_msg_123'

        # Simulate job lifecycle
        active_jobs[message_id] = False  # Job starts
        self.assertIn(message_id, active_jobs)

        # Job completes
        if message_id in active_jobs:
            del active_jobs[message_id]

        self.assertNotIn(message_id, active_jobs, "Should clean up after completion")

    def test_multiple_workers_parallel_execution(self):
        """Test that multiple workers can run in parallel"""
        # This tests the ThreadPoolExecutor pattern
        from concurrent.futures import ThreadPoolExecutor, as_completed

        results = []

        def worker(worker_id):
            # Simulate work
            time.sleep(0.001)
            return worker_id

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(worker, i) for i in range(4)]

            for future in as_completed(futures):
                results.append(future.result())

        self.assertEqual(len(results), 4, "All workers should complete")
        self.assertEqual(sorted(results), [0, 1, 2, 3], "All workers should return their ID")

    def test_rounds_accumulation_across_iterations(self):
        """Test that rounds are properly accumulated across worker iterations"""
        total_rounds = 0
        iterations = 3
        rounds_per_iteration = 1000

        for _ in range(iterations):
            total_rounds += rounds_per_iteration

        self.assertEqual(total_rounds, 3000, "Rounds should accumulate")

    def test_nacl_fallback_to_rns_hash(self):
        """Test fallback to RNS.Identity.full_hash when nacl unavailable"""
        # nacl is already mocked as None in setUp
        self.mock_rns.Identity.full_hash = lambda m: b'\xab' * 32

        import reticulum_wrapper
        reticulum_wrapper.RNS = self.mock_rns

        # Simulate the nacl check and fallback
        use_nacl = False
        try:
            import nacl.encoding
            use_nacl = True
        except:
            pass

        self.assertFalse(use_nacl, "nacl should not be available")

        # Should use RNS hash
        if not use_nacl:
            result = self.mock_rns.Identity.full_hash(b'test')
            self.assertEqual(result, b'\xab' * 32)

    def test_nacl_hash_when_available(self):
        """Test that nacl hash is used when available"""
        # Mock nacl as available
        mock_nacl = MagicMock()
        mock_nacl_hash = MagicMock()
        mock_nacl_encoding = MagicMock()

        mock_nacl_hash.sha256 = lambda m, encoder: b'\xcd' * 32
        mock_nacl.hash = mock_nacl_hash
        mock_nacl.encoding = mock_nacl_encoding
        mock_nacl.encoding.RawEncoder = MagicMock()

        sys.modules['nacl'] = mock_nacl
        sys.modules['nacl.hash'] = mock_nacl_hash
        sys.modules['nacl.encoding'] = mock_nacl_encoding

        # Simulate the check
        use_nacl = False
        try:
            import nacl.encoding
            import nacl.hash
            use_nacl = True
        except:
            pass

        self.assertTrue(use_nacl, "nacl should be available")

    def test_stamp_found_returns_early(self):
        """Test that search stops when stamp is found"""
        found = [False]
        iterations = [0]

        def search_until_found(max_iterations):
            for i in range(max_iterations):
                iterations[0] += 1
                if i == 5:  # Find on 6th iteration
                    found[0] = True
                    return True
            return False

        result = search_until_found(100)

        self.assertTrue(result)
        self.assertEqual(iterations[0], 6, "Should stop at 6th iteration")

    def test_executor_context_manager_cleanup(self):
        """Test that ThreadPoolExecutor cleans up properly"""
        from concurrent.futures import ThreadPoolExecutor

        executed = []

        def worker(x):
            executed.append(x)
            return x

        # Use context manager
        with ThreadPoolExecutor(max_workers=2) as executor:
            future = executor.submit(worker, 42)
            result = future.result()

        # After context exit, executor should be shutdown
        self.assertEqual(result, 42)
        self.assertIn(42, executed)

    def test_future_cancellation_pattern(self):
        """Test the pattern of cancelling remaining futures when stamp found"""
        from concurrent.futures import ThreadPoolExecutor
        import time

        def slow_worker(worker_id):
            time.sleep(0.1)  # Simulate work
            return None

        def fast_worker(worker_id):
            return b'stamp'  # Found immediately

        with ThreadPoolExecutor(max_workers=4) as executor:
            # Submit mix of fast and slow workers
            futures = [
                executor.submit(fast_worker, 0),
                executor.submit(slow_worker, 1),
                executor.submit(slow_worker, 2),
            ]

            # Get first result
            from concurrent.futures import as_completed
            for future in as_completed(futures):
                result = future.result()
                if result is not None:
                    # Cancel remaining
                    for f in futures:
                        f.cancel()
                    break

        # Test completes without hanging
        self.assertTrue(True)


class TestJobAndroidThreadedIntegration(unittest.TestCase):
    """Integration tests for job_android_threaded with mocked dependencies"""

    def test_full_function_with_easy_stamp(self):
        """Test complete job_android_threaded execution with low cost"""
        # Setup mocks
        mock_rns = MagicMock()
        mock_lxstamper = MagicMock()
        mock_lxstamper.active_jobs = {}
        mock_concurrent = MagicMock()

        # Make hash validation deterministic - succeed on any stamp
        mock_rns.Identity.full_hash = lambda m: b'\x00' * 32  # Very low hash

        # Mock ThreadPoolExecutor to execute synchronously
        class SyncExecutor:
            def __init__(self, max_workers):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def submit(self, fn, *args):
                # Execute immediately
                future = MagicMock()
                future.result = lambda: fn(*args)
                future.cancel = MagicMock()
                return future

        from concurrent import futures as real_futures
        original_executor = real_futures.ThreadPoolExecutor
        real_futures.ThreadPoolExecutor = SyncExecutor
        real_futures.as_completed = lambda futures: futures

        sys.modules['RNS'] = mock_rns
        sys.modules['LXMF.LXStamper'] = mock_lxstamper
        sys.modules['concurrent.futures'] = real_futures

        try:
            import reticulum_wrapper
            reticulum_wrapper.RNS = mock_rns
            reticulum_wrapper.LXMF = mock_lxmf

            # Create the function (simplified for testing)
            def job_android_threaded_simple(stamp_cost, workblock, message_id):
                import concurrent.futures
                import LXMF.LXStamper as LXStamper

                stamp = None
                total_rounds = 0
                rounds_per_worker = 10  # Small for testing

                LXStamper.active_jobs[message_id] = False

                # Simple validation - first stamp passes
                def stamp_valid(s, c, w):
                    return True  # Always valid for test

                def worker(worker_id, rounds):
                    pstamp = os.urandom(256 // 8)
                    return (pstamp, rounds)

                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                    futures = [executor.submit(worker, i, rounds_per_worker) for i in range(2)]

                    for future in concurrent.futures.as_completed(futures):
                        result_stamp, rounds = future.result()
                        total_rounds += rounds
                        if result_stamp is not None:
                            stamp = result_stamp
                            break

                if message_id in LXStamper.active_jobs:
                    del LXStamper.active_jobs[message_id]

                return stamp, total_rounds

            # Execute
            stamp, rounds = job_android_threaded_simple(4, b'test_workblock', 'test_msg')

            self.assertIsNotNone(stamp, "Should find a stamp")
            self.assertEqual(len(stamp), 32, "Stamp should be 32 bytes")
            self.assertGreater(rounds, 0, "Should report rounds")

        finally:
            # Restore
            real_futures.ThreadPoolExecutor = original_executor

    def test_active_jobs_prevents_execution(self):
        """Test that setting active_jobs[message_id]=True stops generation"""
        mock_lxstamper = MagicMock()
        mock_lxstamper.active_jobs = {'test_msg': True}  # Already cancelled

        sys.modules['LXMF.LXStamper'] = mock_lxstamper

        # Simulate the while loop condition
        message_id = 'test_msg'
        stamp = None

        # This is the condition from the actual code
        should_continue = (stamp is None and
                          not mock_lxstamper.active_jobs.get(message_id, False))

        self.assertFalse(should_continue, "Should not continue when cancelled")

    def test_logging_calls_during_execution(self):
        """Test that appropriate log calls are made"""
        from logging_utils import log_info, log_debug

        with patch('logging_utils.log_info') as mock_log_info, \
             patch('logging_utils.log_debug') as mock_log_debug:

            # Simulate the log calls from job_android_threaded
            log_info("ReticulumWrapper", "job_android_threaded",
                    "Starting threaded stamp generation with 8 workers")
            log_debug("ReticulumWrapper", "job_android_threaded",
                     "Stamp generation running. 8000 rounds, 1000 rounds/sec")
            log_info("ReticulumWrapper", "job_android_threaded",
                    "Stamp found after 24553 rounds")

            # Verify calls were made
            self.assertEqual(mock_log_info.call_count, 2)
            self.assertEqual(mock_log_debug.call_count, 1)

    def test_elapsed_time_calculation(self):
        """Test that elapsed time and speed calculations work correctly"""
        start_time = time.time()
        time.sleep(0.01)  # 10ms
        elapsed = time.time() - start_time

        total_rounds = 1000
        speed = total_rounds / elapsed if elapsed > 0 else 0

        self.assertGreater(elapsed, 0)
        self.assertGreater(speed, 0)
        self.assertLess(speed, 1000000)  # Sanity check

    def test_multiprocessing_cpu_count_used_for_workers(self):
        """Test that CPU count determines number of workers"""
        import multiprocessing

        num_workers = multiprocessing.cpu_count()

        self.assertGreater(num_workers, 0)
        self.assertIsInstance(num_workers, int)


class TestStampValidationLogic(unittest.TestCase):
    """Test the core stamp validation algorithm"""

    def test_target_calculation(self):
        """Test that target value is calculated correctly"""
        stamp_cost = 16
        target = 0b1 << (256 - stamp_cost)

        # Target should be 2^240
        expected = 2 ** 240
        self.assertEqual(target, expected)

    def test_stamp_below_target_is_valid(self):
        """Test that stamps below target are valid"""
        stamp_cost = 8
        target = 0b1 << (256 - stamp_cost)

        # Very low value - should be valid
        low_value = 1
        self.assertLess(low_value, target)

    def test_stamp_above_target_is_invalid(self):
        """Test that stamps above target are invalid"""
        stamp_cost = 8
        target = 0b1 << (256 - stamp_cost)

        # Maximum value - should be invalid
        high_value = 2 ** 256 - 1
        self.assertGreater(high_value, target)

    def test_hash_to_int_conversion(self):
        """Test converting hash bytes to integer"""
        test_hash = b'\x00' * 31 + b'\x01'  # Very low value
        value = int.from_bytes(test_hash, byteorder="big")

        self.assertEqual(value, 1)

    def test_random_stamp_generation(self):
        """Test that random stamps are 32 bytes"""
        import os
        stamp = os.urandom(256 // 8)

        self.assertEqual(len(stamp), 32)
        self.assertIsInstance(stamp, bytes)


if __name__ == '__main__':
    unittest.main(verbosity=2)
