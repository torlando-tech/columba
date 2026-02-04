package com.lxmf.messenger.reticulum.audio.bridge

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Low-level coordination layer for packet transfer between Kotlin and Python.
 *
 * Provides the foundation for Phase 10 network bridge. All packet and signalling
 * traffic flows through this single point.
 *
 * **Threading Model:**
 * - Outbound (Kotlin -> Python): Uses [Dispatchers.IO] coroutine scope to avoid blocking audio thread
 * - Inbound (Python -> Kotlin): Fast callback invocation (Python GIL held, must be quick)
 *
 * **Critical Performance Note:**
 * No synchronous logging in [sendPacket] or [onPythonPacketReceived] methods.
 * Logging blocks the audio thread and causes choppiness.
 *
 * @see KotlinAudioBridge Reference implementation for singleton pattern
 * @see CallBridge Reference implementation for Python callback pattern
 */
@Suppress("TooManyFunctions")
class NetworkPacketBridge private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {
    companion object {
        private const val TAG = "Columba:NetBridge"

        @Volatile
        private var instance: NetworkPacketBridge? = null

        /**
         * Get or create singleton instance.
         *
         * @param context Application context (used for consistency with other bridges)
         */
        fun getInstance(context: Context): NetworkPacketBridge {
            return instance ?: synchronized(this) {
                instance ?: NetworkPacketBridge(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Reset singleton instance (for testing).
         */
        internal fun resetInstance() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }
    }

    // Dedicated bridge thread for non-blocking Python calls (avoids GIL contention)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Python network handler reference (set by PythonWrapperManager)
    // Provides: send_audio_packet(ByteArray), send_signal(Int)
    @Volatile
    private var pythonNetworkHandler: PyObject? = null

    // Kotlin callback for incoming packets (set by LinkSource)
    @Volatile
    private var onPacketReceived: ((ByteArray) -> Unit)? = null

    // Kotlin callback for incoming signals (set by SignallingReceiver)
    @Volatile
    private var onSignalReceived: ((Int) -> Unit)? = null

    // ===== Outbound Methods (Kotlin -> Python) =====

    /**
     * Send encoded audio packet to Python Reticulum.
     *
     * Called by Packetizer (RemoteSink) when encoded audio frame is ready.
     * Non-blocking: launches coroutine on [Dispatchers.IO] to avoid blocking audio thread.
     *
     * **CRITICAL:** No Log.d() calls in this method - blocks audio thread.
     *
     * @param encodedFrame Encoded audio data (Opus/Codec2/Null bytes with codec header)
     */
    fun sendPacket(encodedFrame: ByteArray) {
        scope.launch {
            try {
                pythonNetworkHandler?.callAttr("send_audio_packet", encodedFrame)
            } catch (_: Exception) {
                // Silent failure - logging would block audio thread
                // Packet loss is acceptable (fire-and-forget)
            }
        }
    }

    /**
     * Send signalling to Python Reticulum.
     *
     * Called by SignallingReceiver when signal needs to be sent to remote peer.
     * Non-blocking: launches coroutine on [Dispatchers.IO] to avoid blocking audio thread.
     *
     * @param signal Signalling value (see Signalling constants: STATUS_BUSY, STATUS_RINGING, etc.)
     */
    fun sendSignal(signal: Int) {
        scope.launch {
            try {
                pythonNetworkHandler?.callAttr("send_signal", signal)
            } catch (_: Exception) {
                // Silent failure - signalling is fire-and-forget
            }
        }
    }

    // ===== Inbound Methods (Python -> Kotlin) =====

    /**
     * Receive encoded packet from Python Reticulum.
     *
     * Called by Python via Chaquopy callback when packet arrives from remote peer.
     * **MUST BE FAST** - Python GIL is held during this call.
     *
     * Simply invokes the registered callback; no processing, no logging.
     * Decoding and mixing happen on the Kotlin audio thread via the callback.
     *
     * **CRITICAL:** No Log.d() calls in this method - GIL held, blocks Python.
     *
     * @param packetData Encoded packet data (codec header byte + encoded frame)
     */
    fun onPythonPacketReceived(packetData: ByteArray) {
        onPacketReceived?.invoke(packetData)
    }

    /**
     * Receive signalling from Python Reticulum.
     *
     * Called by Python via Chaquopy callback when signal arrives from remote peer.
     * **MUST BE FAST** - Python GIL is held during this call.
     *
     * @param signal Signalling value received from remote
     */
    fun onPythonSignalReceived(signal: Int) {
        onSignalReceived?.invoke(signal)
    }

    // ===== Setup Methods =====

    /**
     * Set the Python network handler.
     *
     * Called by PythonWrapperManager after initializing the network handler.
     * The handler must provide:
     * - send_audio_packet(bytes): Send encoded audio to remote peer
     * - send_signal(int): Send signalling to remote peer
     *
     * @param handler Python PyObject with send_audio_packet and send_signal methods
     */
    fun setPythonNetworkHandler(handler: PyObject) {
        pythonNetworkHandler = handler
        Log.i(TAG, "Python network handler set")
    }

    /**
     * Set callback for incoming packets.
     *
     * Called by LinkSource (RemoteSource) to receive packets from remote peer.
     * The callback receives raw packet data (codec header byte + encoded frame).
     *
     * @param callback Function to invoke when packet received from Python
     */
    fun setPacketCallback(callback: (ByteArray) -> Unit) {
        onPacketReceived = callback
        Log.i(TAG, "Packet callback set")
    }

    /**
     * Set callback for incoming signals.
     *
     * Called by SignallingReceiver to receive signals from remote peer.
     *
     * @param callback Function to invoke when signal received from Python
     */
    fun setSignalCallback(callback: (Int) -> Unit) {
        onSignalReceived = callback
        Log.i(TAG, "Signal callback set")
    }

    // ===== Lifecycle =====

    /**
     * Check if Python handler is set.
     */
    fun isHandlerSet(): Boolean = pythonNetworkHandler != null

    /**
     * Check if packet callback is registered.
     */
    fun hasPacketCallback(): Boolean = onPacketReceived != null

    /**
     * Check if signal callback is registered.
     */
    fun hasSignalCallback(): Boolean = onSignalReceived != null

    /**
     * Shutdown the network bridge.
     *
     * Cancels the coroutine scope and clears all references.
     * Called during app shutdown or when call ends.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down network bridge")
        scope.cancel()
        pythonNetworkHandler = null
        onPacketReceived = null
        onSignalReceived = null
    }
}
