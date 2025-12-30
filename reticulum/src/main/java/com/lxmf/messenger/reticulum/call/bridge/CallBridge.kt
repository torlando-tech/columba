package com.lxmf.messenger.reticulum.call.bridge

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Call state sealed class for type-safe call state management.
 */
sealed class CallState {
    /** No active call */
    data object Idle : CallState()

    /** Initiating outgoing call, waiting for link establishment */
    data class Connecting(val identityHash: String) : CallState()

    /** Remote is ringing (outgoing call) */
    data class Ringing(val identityHash: String) : CallState()

    /** Incoming call from remote peer */
    data class Incoming(val identityHash: String) : CallState()

    /** Call is active and audio is flowing */
    data class Active(val identityHash: String) : CallState()

    /** Remote rejected the call or is busy */
    data object Busy : CallState()

    /** Call was rejected by us or remote */
    data object Rejected : CallState()

    /** Call ended normally */
    data object Ended : CallState()
}

/**
 * Interface for Python call manager interactions.
 * Allows for mocking in unit tests.
 */
interface PythonCallManagerInterface {
    fun call(destinationHash: String)
    fun answer()
    fun hangup()
    fun muteMicrophone(muted: Boolean)
    fun setSpeaker(enabled: Boolean)
}

/**
 * Default implementation that wraps PyObject.
 */
internal class PyObjectCallManager(private val pyObject: PyObject) : PythonCallManagerInterface {
    override fun call(destinationHash: String) {
        pyObject.callAttr("call", destinationHash)
    }

    override fun answer() {
        pyObject.callAttr("answer")
    }

    override fun hangup() {
        pyObject.callAttr("hangup")
    }

    override fun muteMicrophone(muted: Boolean) {
        pyObject.callAttr("mute_microphone", muted)
    }

    override fun setSpeaker(enabled: Boolean) {
        pyObject.callAttr("set_speaker", enabled)
    }
}

/**
 * Bridge for call state between Python LXST and Kotlin UI.
 *
 * Manages bidirectional communication:
 * - Python → Kotlin: Call state changes, incoming calls, call ended
 * - Kotlin → Python: Initiate call, answer, decline, end call, mute, speaker
 *
 * **Thread Safety**: All state flows are thread-safe. Python callbacks are
 * invoked on the bridge's coroutine scope.
 */
class CallBridge private constructor(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        private const val TAG = "Columba:CallBridge"

        @Volatile
        private var instance: CallBridge? = null

        /**
         * Get singleton instance.
         */
        fun getInstance(): CallBridge {
            return instance ?: synchronized(this) {
                instance ?: CallBridge().also { instance = it }
            }
        }

        /**
         * Get singleton instance with custom dispatcher (for testing).
         */
        internal fun getInstance(dispatcher: CoroutineDispatcher): CallBridge {
            return instance ?: synchronized(this) {
                instance ?: CallBridge(dispatcher).also { instance = it }
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

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // Call state flows for UI observation
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _remoteIdentity = MutableStateFlow<String?>(null)
    val remoteIdentity: StateFlow<String?> = _remoteIdentity.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val _callStartTime = MutableStateFlow<Long?>(null)
    val callStartTime: StateFlow<Long?> = _callStartTime.asStateFlow()

    // Python call manager reference (set by PythonWrapperManager)
    @Volatile
    private var pythonCallManager: PythonCallManagerInterface? = null

    // Python callbacks for state changes (set by Python call_manager)
    @Volatile
    private var onCallStateChanged: PyObject? = null

    // ===== Python Call Manager Setup =====

    /**
     * Set the Python CallManager instance.
     *
     * Called by PythonWrapperManager after initializing the call manager.
     */
    fun setPythonCallManager(manager: PyObject) {
        pythonCallManager = PyObjectCallManager(manager)
        Log.i(TAG, "Python CallManager set")
    }

    /**
     * Set a custom call manager interface (for testing).
     */
    internal fun setCallManagerInterface(manager: PythonCallManagerInterface?) {
        pythonCallManager = manager
    }

    /**
     * Set callback for state changes (called by Python).
     */
    fun setOnCallStateChanged(callback: PyObject) {
        onCallStateChanged = callback
    }

    // ===== Called by Python (via Chaquopy callbacks) =====

    /**
     * Notify of incoming call.
     *
     * Called by Python when LXST receives an incoming call.
     */
    fun onIncomingCall(identityHash: String) {
        Log.i(TAG, "Incoming call from: ${identityHash.take(16)}...")
        scope.launch {
            _remoteIdentity.value = identityHash
            _callState.value = CallState.Incoming(identityHash)
        }
    }

    /**
     * Notify that remote is ringing.
     *
     * Called by Python when outgoing call reaches the remote and they're ringing.
     */
    fun onCallRinging(identityHash: String) {
        Log.d(TAG, "Call ringing: ${identityHash.take(16)}...")
        scope.launch {
            _callState.value = CallState.Ringing(identityHash)
        }
    }

    /**
     * Notify that call is established.
     *
     * Called by Python when the call is answered and audio is flowing.
     */
    fun onCallEstablished(identityHash: String) {
        Log.i(TAG, "Call established with: ${identityHash.take(16)}...")
        scope.launch {
            _callState.value = CallState.Active(identityHash)
            _callStartTime.value = System.currentTimeMillis()
        }
    }

    /**
     * Notify that call has ended.
     *
     * Called by Python when the call ends (either side hangs up).
     */
    fun onCallEnded(identityHash: String?) {
        Log.i(TAG, "Call ended: ${identityHash?.take(16) ?: "unknown"}")
        scope.launch {
            // Calculate final duration before resetting
            _callStartTime.value?.let { startTime ->
                _callDuration.value = (System.currentTimeMillis() - startTime) / 1000
            }

            _callState.value = CallState.Ended
            // Reset after a short delay to allow UI to show "Call Ended"
            kotlinx.coroutines.delay(2000)
            resetState()
        }
    }

    /**
     * Notify that remote is busy.
     *
     * Called by Python when the remote party is already on a call.
     */
    fun onCallBusy() {
        Log.d(TAG, "Remote is busy")
        scope.launch {
            _callState.value = CallState.Busy
            kotlinx.coroutines.delay(3000)
            resetState()
        }
    }

    /**
     * Notify that call was rejected.
     *
     * Called by Python when the remote party rejects the call.
     */
    fun onCallRejected() {
        Log.d(TAG, "Call rejected")
        scope.launch {
            _callState.value = CallState.Rejected
            kotlinx.coroutines.delay(2000)
            resetState()
        }
    }

    // ===== Called by Kotlin UI =====

    /**
     * Initiate an outgoing call.
     *
     * @param destinationHash 32-character hex hash of the destination identity
     */
    fun initiateCall(destinationHash: String) {
        Log.i(TAG, "Initiating call to: ${destinationHash.take(16)}...")

        scope.launch {
            _remoteIdentity.value = destinationHash
            _callState.value = CallState.Connecting(destinationHash)

            try {
                pythonCallManager?.call(destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating call", e)
                _callState.value = CallState.Ended
                kotlinx.coroutines.delay(1000)
                resetState()
            }
        }
    }

    /**
     * Answer an incoming call.
     */
    fun answerCall() {
        Log.d(TAG, "Answering call")
        scope.launch {
            try {
                pythonCallManager?.answer()
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call", e)
            }
        }
    }

    /**
     * Decline an incoming call.
     */
    fun declineCall() {
        Log.d(TAG, "Declining call")
        endCall()
    }

    /**
     * End the current call.
     */
    fun endCall() {
        Log.d(TAG, "Ending call")
        scope.launch {
            try {
                pythonCallManager?.hangup()
            } catch (e: Exception) {
                Log.e(TAG, "Error ending call", e)
            }
            // Python will notify us via onCallEnded
        }
    }

    /**
     * Toggle microphone mute.
     */
    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        Log.d(TAG, "Mute toggled: $newMuted")

        scope.launch {
            try {
                pythonCallManager?.muteMicrophone(newMuted)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling mute", e)
            }
        }
    }

    /**
     * Set microphone mute state.
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        scope.launch {
            try {
                pythonCallManager?.muteMicrophone(muted)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting mute", e)
            }
        }
    }

    /**
     * Toggle speaker/earpiece.
     */
    fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        Log.d(TAG, "Speaker toggled: $newSpeaker")

        scope.launch {
            try {
                pythonCallManager?.setSpeaker(newSpeaker)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling speaker", e)
            }
        }
    }

    /**
     * Set speaker state.
     */
    fun setSpeaker(enabled: Boolean) {
        _isSpeakerOn.value = enabled
        scope.launch {
            try {
                pythonCallManager?.setSpeaker(enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting speaker", e)
            }
        }
    }

    // ===== Helper Methods =====

    /**
     * Check if there's an active or pending call.
     */
    fun hasActiveCall(): Boolean {
        return when (_callState.value) {
            is CallState.Connecting,
            is CallState.Ringing,
            is CallState.Incoming,
            is CallState.Active,
            -> true
            else -> false
        }
    }

    /**
     * Get current call duration in seconds.
     */
    fun getCurrentDuration(): Long {
        val startTime = _callStartTime.value ?: return 0
        return (System.currentTimeMillis() - startTime) / 1000
    }

    /**
     * Reset all state to idle.
     */
    private fun resetState() {
        _callState.value = CallState.Idle
        _remoteIdentity.value = null
        _isMuted.value = false
        _isSpeakerOn.value = false
        _callDuration.value = 0L
        _callStartTime.value = null
    }

    /**
     * Cleanup resources.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down CallBridge")
        endCall()
        resetState()
    }
}
