package com.lxmf.messenger.reticulum.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridge for general Python-to-Kotlin callbacks.
 *
 * This bridge handles non-BLE specific events from the Python Reticulum layer,
 * such as announce notifications, link events, and other protocol-level callbacks.
 *
 * Unlike KotlinBLEBridge which handles BLE-specific operations, this bridge
 * provides event notifications that work regardless of the underlying interface
 * (BLE, WiFi, TCP, Serial, etc.).
 *
 * **Thread Safety**: All public methods are thread-safe and can be called from
 * Python threads via Chaquopy.
 */
class KotlinReticulumBridge private constructor() {
    companion object {
        private const val TAG = "Columba:Kotlin:ReticulumBridge"

        @Volatile
        private var instance: KotlinReticulumBridge? = null

        /**
         * Get or create singleton instance.
         */
        fun getInstance(): KotlinReticulumBridge {
            return instance ?: synchronized(this) {
                instance ?: KotlinReticulumBridge().also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Announce callback - triggered when Python receives a new announce
    @Volatile
    private var onAnnounceReceived: (() -> Unit)? = null

    /**
     * Called from Python when a new announce arrives.
     * Triggers immediate announce processing in Kotlin.
     *
     * This is called from Python's _announce_handler() whenever a new
     * announce is received from ANY interface (BLE, WiFi, TCP, etc.).
     */
    fun notifyAnnounceReceived() {
        Log.d(TAG, "Announce notification received from Python")
        scope.launch {
            try {
                onAnnounceReceived?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Error in announce callback", e)
            }
        }
    }

    /**
     * Set callback for announce notifications.
     *
     * @param callback Function to call when an announce is received.
     *                 Called on IO dispatcher, so safe for blocking operations.
     */
    fun setOnAnnounceReceived(callback: () -> Unit) {
        onAnnounceReceived = callback
        Log.d(TAG, "Announce callback registered")
    }

    // Future: Add more callbacks here (link events, message delivery status, etc.)
}
