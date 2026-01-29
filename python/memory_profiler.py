"""
Memory Profiling Module for Columba

Provides tracemalloc-based memory profiling to detect Python heap memory leaks.
Designed for developer builds with zero overhead when disabled.

Key features:
- Periodic snapshot comparison to identify growing allocations
- Filtered output (excludes frozen importlib and unknown traces)
- Android logcat integration via logging_utils
- Thread-based scheduling (Chaquopy-compatible, no asyncio)

Usage from reticulum_wrapper.py:
    from memory_profiler import start_profiling, take_snapshot, stop_profiling

    # At wrapper initialization:
    start_profiling(nframes=10)

    # Periodically (every 5 minutes):
    take_snapshot()

    # At shutdown:
    stop_profiling()
"""

import tracemalloc
import threading
from typing import Optional, Dict, Any
from logging_utils import log_info, log_warning, log_debug

# Module state
_baseline_snapshot: Optional[tracemalloc.Snapshot] = None
_profiling_active: bool = False
_snapshot_timer: Optional[threading.Timer] = None
_lock = threading.Lock()  # Synchronizes timer/profiling state access


def start_profiling(nframes: int = 10) -> None:
    """
    Start tracemalloc profiling with baseline snapshot.

    IMPORTANT: Must be called BEFORE any RNS/LXMF imports to capture all allocations.

    Args:
        nframes: Number of stack frames to capture (default 10 for useful tracebacks)
    """
    global _baseline_snapshot, _profiling_active

    if _profiling_active:
        log_warning("MemoryProfiler", "start_profiling", "Already profiling, ignoring duplicate start")
        return

    try:
        tracemalloc.start(nframes)
        _baseline_snapshot = tracemalloc.take_snapshot()
        _profiling_active = True
        log_info("MemoryProfiler", "start_profiling", f"Profiling started with {nframes} frames")
    except Exception as e:
        log_warning("MemoryProfiler", "start_profiling", f"Failed to start profiling: {e}")


def take_snapshot() -> None:
    """
    Take a memory snapshot and compare to baseline.

    Logs top 10 growing allocations to Android logcat.
    Filters out importlib bootstrap and unknown traces to reduce noise.
    """
    global _baseline_snapshot

    if not _profiling_active:
        log_debug("MemoryProfiler", "take_snapshot", "Profiling not active, skipping snapshot")
        return

    if _baseline_snapshot is None:
        log_warning("MemoryProfiler", "take_snapshot", "No baseline snapshot, cannot compare")
        return

    try:
        # Take current snapshot
        current_snapshot = tracemalloc.take_snapshot()

        # Filter out noise (frozen importlib, unknown traces)
        filtered_snapshot = current_snapshot.filter_traces((
            tracemalloc.Filter(False, "<frozen importlib._bootstrap>"),
            tracemalloc.Filter(False, "<frozen importlib._bootstrap_external>"),
            tracemalloc.Filter(False, "<unknown>"),
        ))

        # Compare to baseline
        top_stats = filtered_snapshot.compare_to(_baseline_snapshot, 'lineno')

        # Log top 10 growing allocations
        if top_stats:
            log_info("MemoryProfiler", "take_snapshot", "=== Top 10 Memory Growth ===")
            for i, stat in enumerate(top_stats[:10], start=1):
                size_diff_kb = stat.size_diff / 1024
                # Get first line of traceback (most relevant)
                if stat.traceback:
                    location = stat.traceback.format()[0].strip()
                else:
                    location = "Unknown location"
                log_info("MemoryProfiler", "take_snapshot", f"#{i}: +{size_diff_kb:.1f} KiB at {location}")
        else:
            log_debug("MemoryProfiler", "take_snapshot", "No significant memory growth detected")

    except Exception as e:
        log_warning("MemoryProfiler", "take_snapshot", f"Failed to take snapshot: {e}")


def get_memory_stats() -> Dict[str, Any]:
    """
    Get current memory profiling statistics.

    Returns:
        Dict with:
            - current_mb: Current traced memory in MB
            - peak_mb: Peak traced memory in MB
            - overhead_kb: tracemalloc overhead in KB
            - profiling_active: Whether profiling is active
    """
    stats = {
        "profiling_active": _profiling_active,
        "current_mb": 0.0,
        "peak_mb": 0.0,
        "overhead_kb": 0.0,
    }

    if not _profiling_active:
        return stats

    try:
        if tracemalloc.is_tracing():
            current, peak = tracemalloc.get_traced_memory()
            stats["current_mb"] = current / (1024 * 1024)
            stats["peak_mb"] = peak / (1024 * 1024)
            stats["overhead_kb"] = tracemalloc.get_tracemalloc_memory() / 1024
    except Exception as e:
        log_warning("MemoryProfiler", "get_memory_stats", f"Failed to get stats: {e}")

    return stats


def stop_profiling() -> None:
    """
    Stop tracemalloc profiling and clear snapshots.

    Safe to call even if profiling not active.
    Thread-safe: uses lock to prevent race with timer callback.
    """
    global _baseline_snapshot, _profiling_active, _snapshot_timer

    with _lock:
        # Cancel any pending snapshot timer
        if _snapshot_timer is not None:
            _snapshot_timer.cancel()
            _snapshot_timer = None

        if not _profiling_active:
            log_debug("MemoryProfiler", "stop_profiling", "Profiling not active, nothing to stop")
            return

        # Set flag inside lock to prevent timer callback from rescheduling
        _profiling_active = False

    # These operations don't need the lock
    try:
        if tracemalloc.is_tracing():
            tracemalloc.stop()
        _baseline_snapshot = None
        log_info("MemoryProfiler", "stop_profiling", "Profiling stopped and snapshots cleared")
    except Exception as e:
        log_warning("MemoryProfiler", "stop_profiling", f"Failed to stop profiling: {e}")


def _snapshot_timer_callback(interval_seconds: int) -> None:
    """
    Internal callback for periodic snapshots.

    Takes a snapshot and reschedules the timer.
    Thread-safe: uses lock to prevent race with stop_profiling.

    Args:
        interval_seconds: Snapshot interval
    """
    global _snapshot_timer

    try:
        take_snapshot()
    except Exception as e:
        log_warning("MemoryProfiler", "_snapshot_timer_callback", f"Snapshot failed: {e}")

    # Reschedule if still profiling (check under lock to prevent race)
    with _lock:
        if _profiling_active:
            _snapshot_timer = threading.Timer(interval_seconds, _snapshot_timer_callback, args=(interval_seconds,))
            _snapshot_timer.daemon = True  # Don't block shutdown
            _snapshot_timer.start()


def schedule_periodic_snapshots(interval_seconds: int = 300) -> None:
    """
    Schedule periodic memory snapshots.

    Uses threading.Timer for Chaquopy compatibility (no asyncio).

    Args:
        interval_seconds: Snapshot interval (default 300 = 5 minutes)
    """
    global _snapshot_timer

    if not _profiling_active:
        log_warning("MemoryProfiler", "schedule_periodic_snapshots",
                   "Profiling not active, cannot schedule snapshots")
        return

    # Cancel existing timer if any
    if _snapshot_timer is not None:
        _snapshot_timer.cancel()

    # Start periodic snapshots
    _snapshot_timer = threading.Timer(interval_seconds, _snapshot_timer_callback, args=(interval_seconds,))
    _snapshot_timer.daemon = True  # Don't block shutdown
    _snapshot_timer.start()

    log_info("MemoryProfiler", "schedule_periodic_snapshots",
            f"Scheduled snapshots every {interval_seconds}s")
