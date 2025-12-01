package com.lxmf.messenger.service.manager

import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumServiceCallback

/**
 * Thread-safe manager for IPC callback broadcasting.
 *
 * Handles registration, unregistration, and broadcasting of events to all
 * registered clients via RemoteCallbackList.
 *
 * IMPORTANT: RemoteCallbackList's beginBroadcast()/finishBroadcast() are not
 * re-entrant. The broadcastLock ensures only one broadcast happens at a time.
 */
class CallbackBroadcaster {
    companion object {
        private const val TAG = "CallbackBroadcaster"
    }

    private val callbacks = RemoteCallbackList<IReticulumServiceCallback>()
    private val broadcastLock = Any()

    // Readiness callback for service binding notification
    // Protected by readinessLock for thread-safe access
    private val readinessLock = Any()
    private var readinessCallback: IReadinessCallback? = null
    private var isServiceBound = false

    /**
     * Register a callback for receiving events.
     */
    fun register(callback: IReticulumServiceCallback) {
        callbacks.register(callback)
        Log.d(TAG, "Callback registered")
    }

    /**
     * Unregister a previously registered callback.
     */
    fun unregister(callback: IReticulumServiceCallback) {
        callbacks.unregister(callback)
        Log.d(TAG, "Callback unregistered")
    }

    /**
     * Register callback for service readiness notification.
     * If service is already bound, notifies immediately.
     * Thread-safe: synchronized on readinessLock.
     */
    fun registerReadinessCallback(callback: IReadinessCallback) {
        synchronized(readinessLock) {
            Log.d(TAG, "Readiness callback registered")
            readinessCallback = callback

            // If service is already bound, notify immediately
            if (isServiceBound) {
                Log.d(TAG, "Already bound, notifying readiness immediately")
                try {
                    callback.onServiceReady()
                } catch (e: RemoteException) {
                    Log.e(TAG, "Error notifying readiness callback", e)
                }
            }
        }
    }

    /**
     * Mark service as bound and notify readiness callback.
     * Thread-safe: synchronized on readinessLock.
     */
    fun setServiceBound(bound: Boolean) {
        synchronized(readinessLock) {
            isServiceBound = bound
            if (bound) {
                readinessCallback?.let { callback ->
                    Log.d(TAG, "Notifying readiness callback after binding")
                    try {
                        callback.onServiceReady()
                    } catch (e: RemoteException) {
                        Log.e(TAG, "Error notifying readiness callback", e)
                    }
                }
            }
        }
    }

    /**
     * Kill all registered callbacks.
     * Call during service destruction.
     */
    fun kill() {
        callbacks.kill()
    }

    /**
     * Broadcast incoming message to all registered callbacks.
     */
    fun broadcastMessage(messageJson: String) {
        broadcast { it.onMessage(messageJson) }
    }

    /**
     * Broadcast announce event to all registered callbacks.
     */
    fun broadcastAnnounce(announceJson: String) {
        broadcast { it.onAnnounce(announceJson) }
    }

    /**
     * Broadcast network status change to all registered callbacks.
     */
    fun broadcastStatusChange(status: String) {
        broadcast { it.onStatusChanged(status) }
    }

    /**
     * Broadcast delivery status update to all registered callbacks.
     */
    fun broadcastDeliveryStatus(statusJson: String) {
        broadcast { it.onDeliveryStatus(statusJson) }
    }

    /**
     * Broadcast packet event to all registered callbacks.
     */
    fun broadcastPacket(packetJson: String) {
        broadcast { it.onPacket(packetJson) }
    }

    /**
     * Broadcast link event to all registered callbacks.
     */
    fun broadcastLinkEvent(linkEventJson: String) {
        broadcast { it.onLinkEvent(linkEventJson) }
    }

    /**
     * Thread-safe broadcast helper.
     * Ensures only one broadcast happens at a time (RemoteCallbackList is not re-entrant).
     */
    private inline fun broadcast(crossinline action: (IReticulumServiceCallback) -> Unit) {
        synchronized(broadcastLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        action(callbacks.getBroadcastItem(i))
                    } catch (e: RemoteException) {
                        Log.e(TAG, "Error broadcasting to callback", e)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }
}
