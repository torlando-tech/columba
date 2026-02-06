package com.lxmf.messenger.reticulum.call.telephone

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.torlando.lxst.bridge.NetworkPacketBridge
import tech.torlando.lxst.telephone.NetworkTransport

/**
 * NetworkTransport implementation that wraps Python call_manager via Chaquopy.
 *
 * This is the ONLY place PyObject appears in telephony code (CONTEXT.md decision).
 * The Telephone class uses NetworkTransport interface, not PyObject directly.
 * This enables future migration to pure Kotlin Reticulum.
 *
 * **Threading Model:**
 * - All Python calls use Dispatchers.IO to avoid blocking audio thread
 * - Callback registration delegates to NetworkPacketBridge (already handles threading)
 *
 * @param bridge NetworkPacketBridge for sending/receiving packets and signals
 * @param callManager Python call_manager PyObject for link establishment/teardown
 */
class PythonNetworkTransport(
    private val bridge: NetworkPacketBridge,
    private val callManager: PyObject
) : NetworkTransport {

    companion object {
        private const val TAG = "Columba:PyNetTransport"
    }

    // Track link state based on establishLink/teardownLink calls
    @Volatile
    private var linkActive = false

    /**
     * Establish a link to the destination for call setup.
     *
     * Delegates to Python call_manager.call() which handles:
     * - Path discovery (requests path if not known)
     * - Link establishment with timeout
     * - Identity recall from Reticulum
     *
     * Uses Dispatchers.IO to avoid blocking caller on Python GIL.
     *
     * @param destinationHash 16-byte Reticulum destination hash
     * @return true if call initiated successfully, false otherwise
     */
    override suspend fun establishLink(destinationHash: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val destinationHashHex = destinationHash.toHexString()
                Log.i(TAG, "Establishing link to ${destinationHashHex.take(16)}...")

                // Call Python call_manager.call(destination_hash_hex)
                // Returns dict with "success" and optional "error" keys
                val result = callManager.callAttr("call", destinationHashHex)

                // Parse Python dict result using asMap() (Chaquopy pattern)
                @Suppress("UNCHECKED_CAST")
                val resultDict = result?.asMap() as? Map<PyObject, PyObject>
                val success = resultDict
                    ?.entries
                    ?.find { it.key.toString() == "success" }
                    ?.value
                    ?.toBoolean() ?: false

                if (success) {
                    linkActive = true
                    Log.i(TAG, "Call initiated to ${destinationHashHex.take(16)}...")
                } else {
                    val error = resultDict
                        ?.entries
                        ?.find { it.key.toString() == "error" }
                        ?.value
                        ?.toString() ?: "Unknown error"
                    Log.w(TAG, "Call initiation failed: $error")
                }

                success
            } catch (e: Exception) {
                Log.e(TAG, "Error establishing link", e)
                false
            }
        }
    }

    /**
     * Tear down the active link.
     *
     * Delegates to Python call_manager.hangup().
     * Safe to call if no link active (Python handles gracefully).
     *
     * Non-blocking: launches on Dispatchers.IO via bridge pattern.
     */
    override fun teardownLink() {
        Log.i(TAG, "Tearing down link")
        linkActive = false

        // Use runCatching to handle any Python errors silently
        runCatching {
            // Note: This is synchronous but call_manager.hangup() is fast
            // and we're typically on a coroutine scope already
            callManager.callAttr("hangup")
        }.onFailure { e ->
            Log.e(TAG, "Error tearing down link", e)
        }
    }

    /**
     * Send encoded audio packet to remote peer.
     *
     * Delegates to NetworkPacketBridge.sendPacket() which:
     * - Uses Dispatchers.IO for non-blocking send
     * - Handles silent failure (packet loss acceptable)
     *
     * @param encodedFrame Codec-encoded audio frame (includes header)
     */
    override fun sendPacket(encodedFrame: ByteArray) {
        bridge.sendPacket(encodedFrame)
    }

    /**
     * Send signalling message to remote peer.
     *
     * Delegates to NetworkPacketBridge.sendSignal() which:
     * - Uses Dispatchers.IO for non-blocking send
     * - Handles silent failure (fire-and-forget)
     *
     * @param signal Signalling code to send
     */
    override fun sendSignal(signal: Int) {
        bridge.sendSignal(signal)
    }

    /**
     * Register callback for incoming audio packets.
     *
     * Delegates to NetworkPacketBridge.setPacketCallback().
     * Callback is invoked on IO thread - implementations should not block.
     *
     * @param callback Function receiving encoded audio packets
     */
    override fun setPacketCallback(callback: (ByteArray) -> Unit) {
        bridge.setPacketCallback(callback)
    }

    /**
     * Register callback for incoming signalling messages.
     *
     * Delegates to NetworkPacketBridge.setSignalCallback().
     * Callback is invoked on IO thread - implementations should not block.
     *
     * @param callback Function receiving signalling codes
     */
    override fun setSignalCallback(callback: (Int) -> Unit) {
        bridge.setSignalCallback(callback)
    }

    /**
     * Check if link is currently active.
     *
     * Based on internal state tracking. Link may become inactive due to:
     * - teardownLink() call
     * - Remote hangup (detected via signal callback)
     * - Network failure
     */
    override val isLinkActive: Boolean
        get() = linkActive

    /**
     * Mark link as inactive (called by Telephone on hangup signal).
     *
     * Internal helper to update state when remote hangs up.
     */
    internal fun markLinkInactive() {
        linkActive = false
    }

}

/**
 * Convert ByteArray to hex string.
 *
 * Used for destination hash conversion to Python API format.
 */
private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}
