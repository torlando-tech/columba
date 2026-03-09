package com.lxmf.messenger.service.manager

import android.os.Debug
import android.util.Log
import com.lxmf.messenger.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages Python memory profiling and Android native heap monitoring.
 *
 * Coordinates:
 * - Python tracemalloc profiling (via PythonWrapperManager)
 * - Android native heap monitoring (dumpsys meminfo alternative)
 * - Periodic logging at synchronized 5-minute intervals
 * - Sentry breadcrumbs for crash correlation
 *
 * Native heap monitoring (Sentry breadcrumbs) runs in all builds — it's lightweight.
 * Python tracemalloc profiling is gated by BuildConfig.ENABLE_MEMORY_PROFILING (debug only).
 *
 * Usage:
 *   val profiler = MemoryProfilerManager(wrapperManager, scope)
 *   profiler.startProfiling()  // Auto-checks build flag
 *   // ... wait 5 minutes ...
 *   // Check logcat: adb logcat -s MemoryProfilerManager:I
 *   // Or view Sentry breadcrumbs on crash reports
 *   profiler.stopProfiling()
 */
class MemoryProfilerManager(
    private val wrapperManager: PythonWrapperManager,
    private val scope: CoroutineScope,
) {
    private var nativeHeapMonitorJob: Job? = null

    companion object {
        private const val TAG = "MemoryProfilerManager"
    }

    /**
     * Start memory profiling with periodic snapshots.
     *
     * Native heap monitoring (Sentry breadcrumbs) always runs — it's cheap and
     * provides OOM crash correlation data in release builds.
     *
     * Python tracemalloc only runs when ENABLE_MEMORY_PROFILING is true (debug builds).
     * tracemalloc instruments every Python allocation and is too expensive for production.
     *
     * @param intervalSeconds Snapshot interval (default 300 = 5 minutes)
     */
    fun startProfiling(intervalSeconds: Int = 300) {
        // Always start lightweight native heap monitoring for Sentry breadcrumbs
        startNativeHeapMonitoring(intervalSeconds)

        if (!BuildConfig.ENABLE_MEMORY_PROFILING) {
            Log.d(TAG, "Python memory profiling disabled in this build")
            return
        }

        try {
            // Start Python tracemalloc profiling (debug builds only)
            wrapperManager.withWrapper { wrapper ->
                wrapper.callAttr("enable_memory_profiling", intervalSeconds)
                Log.i(TAG, "Python memory profiling started with ${intervalSeconds}s interval")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Python profiling", e)
        }
    }

    /**
     * Stop memory profiling and cleanup resources.
     *
     * Safe to call even if profiling was not enabled.
     */
    fun stopProfiling() {
        // Always stop native heap monitoring
        nativeHeapMonitorJob?.cancel()
        nativeHeapMonitorJob = null

        if (!BuildConfig.ENABLE_MEMORY_PROFILING) {
            return
        }

        try {
            // Stop Python profiling (debug builds only)
            wrapperManager.withWrapper { wrapper ->
                wrapper.callAttr("disable_memory_profiling")
                Log.i(TAG, "Python memory profiling stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Python profiling", e)
        }
    }

    /**
     * Get current memory profiling statistics from Python.
     *
     * @return Map with profiling_active, current_mb, peak_mb, overhead_kb
     *         Returns null if profiling not available or Python wrapper not initialized
     */
    fun getMemoryStats(): Map<String, Any>? {
        if (!BuildConfig.ENABLE_MEMORY_PROFILING) {
            return null
        }

        return try {
            wrapperManager.withWrapper { wrapper ->
                val result = wrapper.callAttr("get_memory_profile")
                result?.asMap()?.mapKeys { it.key.toString() }?.mapValues {
                    when (val value = it.value) {
                        is Boolean -> value
                        is Double -> value
                        is Float -> value.toDouble()
                        else -> value.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get memory stats", e)
            null
        }
    }

    /**
     * Start periodic native heap monitoring.
     *
     * Logs JVM and native heap stats at the same interval as Python snapshots
     * for correlation analysis.
     *
     * @param intervalSeconds Monitoring interval
     */
    private fun startNativeHeapMonitoring(intervalSeconds: Int) {
        nativeHeapMonitorJob?.cancel()

        nativeHeapMonitorJob =
            scope.launch {
                logNativeHeapInfo() // Baseline before first interval
                while (isActive) {
                    delay(intervalSeconds * 1000L)
                    logNativeHeapInfo()
                }
            }
    }

    /**
     * Log Android native heap and JVM memory info.
     *
     * Provides correlation data for Python heap growth analysis.
     * Also records as Sentry breadcrumb for OOM crash investigation.
     * Format: "Memory: JVM=45MB/512MB, Native=120MB"
     */
    private fun logNativeHeapInfo() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024

            // Native heap includes Python interpreter + Chaquopy JNI allocations
            val nativeHeap = Debug.getNativeHeapAllocatedSize() / 1024 / 1024

            Log.i(TAG, "Memory: JVM=${usedMemory}MB/${maxMemory}MB, Native=${nativeHeap}MB")

            // Record as Sentry breadcrumb for OOM investigation
            val breadcrumb =
                io.sentry.Breadcrumb().apply {
                    category = "memory"
                    message = "JVM=${usedMemory}MB/${maxMemory}MB, Native=${nativeHeap}MB"
                    level = io.sentry.SentryLevel.INFO
                    setData("jvm_used_mb", usedMemory)
                    setData("jvm_max_mb", maxMemory)
                    setData("native_mb", nativeHeap)
                }
            io.sentry.Sentry.addBreadcrumb(breadcrumb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get memory info: ${e.message}")
        }
    }
}
