package network.columba.app.rns.host.manager

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Manages Multicast and Wake locks for the Reticulum service.
 *
 * These locks ensure reliable network operation:
 * - MulticastLock: Required for receiving multicast packets (network discovery)
 * - WakeLock: Prevents CPU from sleeping during Doze mode
 *
 * WiFi lock (WIFI_MODE_FULL_HIGH_PERF) was intentionally removed — it prevents
 * 802.11 power-save mode (PSP), consuming ~10x more WiFi radio power for latency
 * savings (~100-300ms) that are imperceptible for a messenger. The battery whitelist
 * already ensures network access during Doze.
 *
 * The persistent PARTIAL_WAKE_LOCK is optional ([persistentWakeLockEnabled], user
 * setting "Persistent Wake Lock"). When disabled (duty-cycle mode) the CPU is free
 * to suspend between mesh traffic; [acquireTrafficWakeLock] then holds a short
 * timed lock around active RX/TX bursts so in-flight processing (link
 * establishment, propagation syncs) completes without interruption.
 *
 * All locks use setReferenceCounted(false) to prevent double-release issues.
 */
class LockManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "LockManager"
        private const val MULTICAST_LOCK_TAG = "ReticulumMulticast"
        private const val WAKE_LOCK_TAG = "Columba::ReticulumService"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L // 10 hours
        private const val TRAFFIC_WAKE_LOCK_TAG = "Columba::ReticulumTraffic"

        /**
         * Hold time for the duty-cycle traffic lock. Long enough to carry an
         * in-flight RX burst through link establishment and the follow-on
         * packets of the same exchange; short enough that a quiet mesh lets
         * the CPU suspend within ~1.5 min of the last packet.
         */
        private const val TRAFFIC_WAKE_LOCK_TIMEOUT_MS = 90 * 1000L
    }

    // Mutex for thread-safe lock operations
    private val lockMutex = Any()

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val powerManager: PowerManager by lazy {
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var trafficWakeLock: PowerManager.WakeLock? = null

    /**
     * Acquire all locks for reliable network operation.
     * Safe to call multiple times - will not double-acquire.
     * Thread-safe: synchronized on lockMutex.
     */
    fun acquireAll() {
        synchronized(lockMutex) {
            try {
                if (!skipMulticastLock) acquireMulticastLock()
                if (persistentWakeLockEnabled) acquireWakeLock()
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring locks", e)
            }
        }
    }

    /**
     * When true, acquireAll() skips the multicast lock.
     * Set by the native stack (NativeInterfaceFactory manages it per-AutoInterface).
     */
    var skipMulticastLock: Boolean = false

    /**
     * When false (duty-cycle mode), the persistent wake lock is not held and
     * [acquireTrafficWakeLock] provides short timed holds around mesh traffic
     * instead. Flipping at runtime releases/acquires the persistent lock
     * immediately. Read by [acquireAll] so network-change re-acquisition
     * honors the mode too.
     */
    @Volatile
    var persistentWakeLockEnabled: Boolean = true
        private set

    /**
     * Switch persistent-wake-lock mode at runtime (user toggle).
     * Enabling acquires the persistent lock; disabling releases it and lets
     * the CPU suspend between traffic bursts. Sole mutation path for the flag.
     */
    fun setPersistentWakeLockEnabled(enabled: Boolean) {
        synchronized(lockMutex) {
            persistentWakeLockEnabled = enabled
            if (enabled) {
                acquireWakeLock()
            } else {
                releaseWakeLock()
            }
        }
    }

    /**
     * Extend a short timed wake lock around mesh RX/TX activity (duty-cycle
     * mode). No-op while the persistent lock is held — it already covers this.
     * Safe to call at high frequency: re-acquiring a non-reference-counted
     * lock just refreshes its timeout.
     */
    fun acquireTrafficWakeLock() {
        if (!persistentWakeLockEnabled) {
            synchronized(lockMutex) {
                if (persistentWakeLockEnabled) return
                try {
                    if (trafficWakeLock == null) {
                        trafficWakeLock =
                            powerManager
                                .newWakeLock(
                                    PowerManager.PARTIAL_WAKE_LOCK,
                                    TRAFFIC_WAKE_LOCK_TAG,
                                ).apply { setReferenceCounted(false) }
                    }
                    @Suppress("DEPRECATION")
                    trafficWakeLock?.acquire(TRAFFIC_WAKE_LOCK_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring traffic wake lock", e)
                }
            }
        }
    }

    /**
     * Release all held locks.
     * Safe to call multiple times - checks isHeld before releasing.
     * Thread-safe: synchronized on lockMutex.
     */
    fun releaseAll() {
        synchronized(lockMutex) {
            try {
                releaseMulticastLock()
                releaseWakeLock()
                releaseTrafficWakeLock()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing locks", e)
            }
        }
    }

    /**
     * Get current lock status for debugging.
     * Thread-safe: synchronized on lockMutex.
     */
    fun getLockStatus(): LockStatus {
        synchronized(lockMutex) {
            return LockStatus(
                multicastHeld = multicastLock?.isHeld == true,
                wakeHeld = wakeLock?.isHeld == true,
                trafficHeld = trafficWakeLock?.isHeld == true,
            )
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null || multicastLock?.isHeld != true) {
            multicastLock =
                wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            Log.i(TAG, "MulticastLock acquired")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null || wakeLock?.isHeld != true) {
            val wasExpired = wakeLock != null && wakeLock?.isHeld != true
            wakeLock =
                powerManager
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG,
                    ).apply {
                        setReferenceCounted(false)
                        @Suppress("DEPRECATION")
                        acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
            if (wasExpired) {
                Log.i(TAG, "WakeLock expired, re-acquired")
            } else {
                Log.i(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK for Doze mode protection)")
            }
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
            Log.i(TAG, "MulticastLock released")
        }
        multicastLock = null
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun releaseTrafficWakeLock() {
        if (trafficWakeLock?.isHeld == true) {
            trafficWakeLock?.release()
            Log.i(TAG, "Traffic wake lock released")
        }
        trafficWakeLock = null
    }

    /**
     * Data class representing current lock status.
     */
    data class LockStatus(
        val multicastHeld: Boolean,
        val wakeHeld: Boolean,
        val trafficHeld: Boolean = false,
    )
}
