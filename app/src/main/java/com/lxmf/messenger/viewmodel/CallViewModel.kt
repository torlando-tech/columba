package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.call.bridge.CallBridge
import com.lxmf.messenger.reticulum.call.bridge.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for voice call screens.
 *
 * Observes CallBridge state and provides UI-friendly state for
 * VoiceCallScreen and IncomingCallScreen.
 */
@HiltViewModel
class CallViewModel
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
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

        private suspend fun resolvePeerName(identityHash: String) {
            try {
                val contact = contactRepository.getContact(identityHash)
                _peerName.value = contact?.customNickname ?: formatIdentityHash(identityHash)
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
         */
        fun initiateCall(destinationHash: String) {
            Log.i(TAG, "Initiating call to ${destinationHash.take(16)}...")
            _isConnecting.value = true
            resolvePeerNameSync(destinationHash)
            callBridge.initiateCall(destinationHash)
        }

        private fun resolvePeerNameSync(identityHash: String) {
            viewModelScope.launch {
                resolvePeerName(identityHash)
            }
        }

        /**
         * Answer an incoming call.
         */
        fun answerCall() {
            Log.d(TAG, "Answering call")
            callBridge.answerCall()
        }

        /**
         * Decline an incoming call.
         */
        fun declineCall() {
            Log.d(TAG, "Declining call")
            callBridge.declineCall()
        }

        /**
         * End the current call.
         */
        fun endCall() {
            Log.d(TAG, "Ending call")
            callBridge.endCall()
        }

        /**
         * Toggle microphone mute.
         */
        fun toggleMute() {
            callBridge.toggleMute()
        }

        /**
         * Toggle speaker/earpiece.
         */
        fun toggleSpeaker() {
            callBridge.toggleSpeaker()
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
            return String.format("%02d:%02d", mins, secs)
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
