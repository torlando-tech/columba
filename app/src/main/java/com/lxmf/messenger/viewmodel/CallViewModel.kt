package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.call.bridge.CallBridge
import com.lxmf.messenger.reticulum.call.bridge.CallState
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for voice call screens.
 *
 * Observes CallBridge state and provides UI-friendly state for
 * VoiceCallScreen and IncomingCallScreen.
 *
 * Uses ReticulumProtocol for call actions (IPC to service process)
 * and CallBridge for local state management.
 */
@HiltViewModel
class CallViewModel
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
        private val announceRepository: AnnounceRepository,
        private val protocol: ReticulumProtocol,
    ) : ViewModel() {
        companion object {
            private const val TAG = "CallViewModel"
        }

        private val callBridge = CallBridge.getInstance()

        // Expose call state from bridge
        val callState: StateFlow<CallState> = callBridge.callState
        val isMuted: StateFlow<Boolean> = callBridge.isMuted
        val isSpeakerOn: StateFlow<Boolean> = callBridge.isSpeakerOn
        val remoteIdentity: StateFlow<String?> = callBridge.remoteIdentity

        // Call duration (updated every second during active call)
        private val _callDuration = MutableStateFlow(0L)
        val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

        // Peer display name (resolved from contacts)
        private val _peerName = MutableStateFlow<String?>(null)
        val peerName: StateFlow<String?> = _peerName.asStateFlow()

        // Loading state for call initiation
        private val _isConnecting = MutableStateFlow(false)
        val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

        init {
            // Track call duration when active
            viewModelScope.launch {
                callState.collect { state ->
                    when (state) {
                        is CallState.Active -> {
                            startDurationTimer()
                        }
                        is CallState.Ended, CallState.Idle -> {
                            _callDuration.value = 0L
                            _isConnecting.value = false
                        }
                        is CallState.Connecting -> {
                            _isConnecting.value = true
                        }
                        else -> {
                            _isConnecting.value = false
                        }
                    }
                }
            }

            // Resolve peer name from identity
            viewModelScope.launch {
                callBridge.remoteIdentity.filterNotNull().collect { hash ->
                    resolvePeerName(hash)
                }
            }
        }

        private fun startDurationTimer() {
            viewModelScope.launch {
                _callDuration.value = 0L
                while (callState.value is CallState.Active) {
                    delay(1000)
                    _callDuration.value += 1
                }
            }
        }

        /**
         * Resolve display name for a peer with priority:
         * 1. Contact's custom nickname (user-set)
         * 2. Announce peer name (from network) - by destination hash
         * 3. Announce peer name (from network) - by identity hash (for LXST calls)
         * 4. Formatted identity hash (fallback)
         */
        private suspend fun resolvePeerName(identityHash: String) {
            try {
                // Check contact for custom nickname first
                val contact = contactRepository.getContact(identityHash)
                if (!contact?.customNickname.isNullOrBlank()) {
                    _peerName.value = contact!!.customNickname
                    return
                }

                // Check announce for peer name by destination hash
                val announce = announceRepository.getAnnounce(identityHash)
                if (!announce?.peerName.isNullOrBlank()) {
                    _peerName.value = announce!!.peerName
                    return
                }

                // For LXST calls, the hash might be an identity hash rather than destination hash
                // (different aspects produce different destination hashes for the same identity)
                val announceByIdentity = announceRepository.findByIdentityHash(identityHash)
                if (!announceByIdentity?.peerName.isNullOrBlank()) {
                    Log.d(TAG, "Found peer name via identity hash: ${announceByIdentity!!.peerName}")
                    _peerName.value = announceByIdentity.peerName
                    return
                }

                // Fallback to formatted hash
                _peerName.value = formatIdentityHash(identityHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving peer name", e)
                _peerName.value = formatIdentityHash(identityHash)
            }
        }

        private fun formatIdentityHash(hash: String): String {
            return if (hash.length > 12) {
                "${hash.take(6)}...${hash.takeLast(6)}"
            } else {
                hash
            }
        }

        /**
         * Initiate an outgoing call.
         * Uses ReticulumProtocol for IPC to service process where Python runs.
         * Retries if CallManager not yet initialized (can take time after app install).
         *
         * @param destinationHash Hex string of destination identity hash
         * @param profileCode LXST codec profile code (0x10-0x80), or null to use default
         */
        fun initiateCall(
            destinationHash: String,
            profileCode: Int? = null,
        ) {
            Log.w(TAG, "📞📞📞 initiateCall() CALLED - destHash=${destinationHash.take(16)}, profile=${profileCode ?: "default"}...")
            Log.w(TAG, "📞 Current callState=${callState.value}")
            _isConnecting.value = true
            resolvePeerNameSync(destinationHash)

            // Update local state
            callBridge.setConnecting(destinationHash)

            // Initiate call via service IPC with retry for CallManager initialization
            viewModelScope.launch {
                var retryCount = 0
                val maxRetries = 10
                val retryDelayMs = 1000L

                while (retryCount < maxRetries) {
                    Log.w(TAG, "📞 Calling protocol.initiateCall() (attempt ${retryCount + 1}/$maxRetries)...")
                    val result = protocol.initiateCall(destinationHash, profileCode)
                    Log.w(TAG, "📞 protocol.initiateCall() returned: success=${result.isSuccess}")

                    if (result.isSuccess) {
                        Log.w(TAG, "📞✅ Call initiated successfully!")
                        return@launch
                    }

                    val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"

                    // Retry if CallManager not initialized yet
                    if (errorMsg.contains("not initialized", ignoreCase = true)) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            Log.w(TAG, "📞 CallManager not ready, retrying in ${retryDelayMs}ms...")
                            kotlinx.coroutines.delay(retryDelayMs)
                            continue
                        }
                    }

                    // Non-retryable error or max retries reached
                    Log.e(TAG, "📞❌ Failed to initiate call: $errorMsg")
                    _isConnecting.value = false
                    callBridge.setEnded()
                    return@launch
                }
            }
        }

        private fun resolvePeerNameSync(identityHash: String) {
            viewModelScope.launch {
                resolvePeerName(identityHash)
            }
        }

        /**
         * Answer an incoming call.
         * Uses ReticulumProtocol for IPC to service process.
         */
        fun answerCall() {
            Log.d(TAG, "Answering call")
            viewModelScope.launch {
                val result = protocol.answerCall()
                if (result.isFailure) {
                    Log.e(TAG, "Failed to answer call: ${result.exceptionOrNull()?.message}")
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
         * Uses ReticulumProtocol for IPC to service process.
         */
        fun endCall() {
            Log.d(TAG, "Ending call")
            viewModelScope.launch {
                protocol.hangupCall()
                callBridge.setEnded()
            }
        }

        /**
         * Toggle microphone mute.
         */
        fun toggleMute() {
            val newMuted = !callBridge.isMuted.value
            callBridge.setMutedLocally(newMuted)
            viewModelScope.launch {
                protocol.setCallMuted(newMuted)
            }
        }

        /**
         * Toggle speaker/earpiece.
         */
        fun toggleSpeaker() {
            val newSpeaker = !callBridge.isSpeakerOn.value
            callBridge.setSpeakerLocally(newSpeaker)
            viewModelScope.launch {
                protocol.setCallSpeaker(newSpeaker)
            }
        }

        /**
         * Check if there's an active call.
         */
        fun hasActiveCall(): Boolean = callBridge.hasActiveCall()

        /**
         * Format duration as MM:SS string.
         */
        fun formatDuration(seconds: Long): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        /**
         * Get call status text for UI display.
         */
        fun getStatusText(state: CallState): String {
            return when (state) {
                is CallState.Idle -> ""
                is CallState.Connecting -> "Connecting..."
                is CallState.Ringing -> "Ringing..."
                is CallState.Incoming -> "Incoming Call"
                is CallState.Active -> "Connected"
                is CallState.Busy -> "Line Busy"
                is CallState.Rejected -> "Call Rejected"
                is CallState.Ended -> "Call Ended"
            }
        }
    }
