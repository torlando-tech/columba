package com.lxmf.messenger.service.manager

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

    /**
     * Acquire all locks for reliable network operation.
     * Safe to call multiple times - will not double-acquire.
     * Thread-safe: synchronized on lockMutex.
     */
    fun acquireAll() {
        synchronized(lockMutex) {
            try {
                acquireMulticastLock()
                acquireWakeLock()
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring locks", e)
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

    /**
     * Data class representing current lock status.
     */
    data class LockStatus(
        val multicastHeld: Boolean,
        val wakeHeld: Boolean,
    )
}
