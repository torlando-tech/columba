package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages conversation links for real-time connectivity status and speed probing.
 *
 * When a conversation is opened, establishes a link to the peer. The link provides:
 * 1. Real-time "Online" status (active link = reachable)
 * 2. Instant link speed data for transfer time estimates
 *
 * Links are automatically closed after [INACTIVITY_TIMEOUT_MS] without sending a message.
 */
@Singleton
class ConversationLinkManager
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
    ) {
        companion object {
            private const val TAG = "ConversationLinkManager"
            private const val INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
            private const val INACTIVITY_CHECK_INTERVAL_MS = 30 * 1000L // Check every 30 seconds
            private const val LINK_ESTABLISHMENT_TIMEOUT_SECONDS = 5.0f // Quick timeout - peer should respond fast if online
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Active link states per conversation (keyed by destination hash hex).
         */
        private val _linkStates = MutableStateFlow<Map<String, LinkState>>(emptyMap())
        val linkStates: StateFlow<Map<String, LinkState>> = _linkStates.asStateFlow()

        /**
         * Last message sent time per conversation (for inactivity tracking).
         */
        private val lastMessageSentTime = mutableMapOf<String, Long>()

        /**
         * Background job for checking inactive links.
         */
        private var inactivityCheckJob: Job? = null

        /**
         * State of a conversation link.
         */
        data class LinkState(
            /** Whether link is currently active */
            val isActive: Boolean,
            /** Link establishment rate in bits/sec (if active) */
            val establishmentRateBps: Long? = null,
            /** Whether link establishment is in progress */
            val isEstablishing: Boolean = false,
            /** Error message if establishment failed */
            val error: String? = null,
        )

        /**
         * Open a link to a conversation peer.
         *
         * Called when entering a conversation screen. The link provides real-time
         * connectivity status and enables instant speed probing.
         *
         * @param destHashHex Destination hash as hex string
         */
        fun openConversationLink(destHashHex: String) {
            scope.launch {
                Log.d(TAG, "Opening conversation link to ${destHashHex.take(16)}...")

                // Mark as establishing
                updateLinkState(destHashHex, LinkState(isActive = false, isEstablishing = true))

                // Reset inactivity timer
                lastMessageSentTime[destHashHex] = System.currentTimeMillis()

                // Ensure inactivity checker is running
                startInactivityChecker()

                try {
                    val destHashBytes = hexStringToBytes(destHashHex)
                    val result =
                        reticulumProtocol.establishConversationLink(
                            destHashBytes,
                            LINK_ESTABLISHMENT_TIMEOUT_SECONDS,
                        )

                    result.fold(
                        onSuccess = { linkResult ->
                            Log.d(
                                TAG,
                                "Link established to ${destHashHex.take(16)}: " +
                                    "active=${linkResult.isActive}, rate=${linkResult.establishmentRateBps}",
                            )
                            updateLinkState(
                                destHashHex,
                                LinkState(
                                    isActive = linkResult.isActive,
                                    establishmentRateBps = linkResult.establishmentRateBps,
                                    isEstablishing = false,
                                ),
                            )
                        },
                        onFailure = { e ->
                            Log.w(TAG, "Failed to establish link to ${destHashHex.take(16)}: ${e.message}")
                            updateLinkState(
                                destHashHex,
                                LinkState(
                                    isActive = false,
                                    isEstablishing = false,
                                    error = e.message,
                                ),
                            )
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening conversation link", e)
                    updateLinkState(
                        destHashHex,
                        LinkState(isActive = false, isEstablishing = false, error = e.message),
                    )
                }
            }
        }

        /**
         * Record that a message was sent to reset the inactivity timer.
         *
         * @param destHashHex Destination hash as hex string
         */
        fun onMessageSent(destHashHex: String) {
            lastMessageSentTime[destHashHex] = System.currentTimeMillis()
            Log.d(TAG, "Message sent to ${destHashHex.take(16)}, resetting inactivity timer")
        }

        /**
         * Close a conversation link.
         *
         * @param destHashHex Destination hash as hex string
         */
        fun closeConversationLink(destHashHex: String) {
            scope.launch {
                try {
                    val destHashBytes = hexStringToBytes(destHashHex)
                    val result = reticulumProtocol.closeConversationLink(destHashBytes)

                    result.fold(
                        onSuccess = { wasActive ->
                            Log.d(TAG, "Closed link to ${destHashHex.take(16)}, wasActive=$wasActive")
                        },
                        onFailure = { e ->
                            Log.w(TAG, "Error closing link: ${e.message}")
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing conversation link", e)
                }

                // Remove from tracked states
                _linkStates.value = _linkStates.value - destHashHex
                lastMessageSentTime.remove(destHashHex)
            }
        }

        /**
         * Get the current link state for a conversation.
         *
         * @param destHashHex Destination hash as hex string
         * @return LinkState or null if no link tracked
         */
        fun getLinkState(destHashHex: String): LinkState? {
            return _linkStates.value[destHashHex]
        }

        /**
         * Refresh link status from Python layer.
         */
        suspend fun refreshLinkStatus(destHashHex: String): LinkState {
            return try {
                val destHashBytes = hexStringToBytes(destHashHex)
                val result = reticulumProtocol.getConversationLinkStatus(destHashBytes)

                val state =
                    LinkState(
                        isActive = result.isActive,
                        establishmentRateBps = result.establishmentRateBps,
                    )

                updateLinkState(destHashHex, state)
                state
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing link status", e)
                LinkState(isActive = false, error = e.message)
            }
        }

        private fun updateLinkState(
            destHashHex: String,
            state: LinkState,
        ) {
            _linkStates.value = _linkStates.value + (destHashHex to state)
            Log.d(TAG, "Updated linkStates: key=$destHashHex, active=${state.isActive}, mapSize=${_linkStates.value.size}")
        }

        private fun startInactivityChecker() {
            if (inactivityCheckJob?.isActive == true) return

            inactivityCheckJob =
                scope.launch {
                    Log.d(TAG, "Starting inactivity checker")
                    while (true) {
                        delay(INACTIVITY_CHECK_INTERVAL_MS)
                        checkInactiveLinks()
                    }
                }
        }

        private suspend fun checkInactiveLinks() {
            val now = System.currentTimeMillis()
            val currentStates = _linkStates.value

            currentStates.forEach { (destHashHex, state) ->
                if (state.isActive) {
                    val lastSent = lastMessageSentTime[destHashHex] ?: 0L
                    val inactiveFor = now - lastSent

                    if (inactiveFor > INACTIVITY_TIMEOUT_MS) {
                        Log.d(
                            TAG,
                            "Link to ${destHashHex.take(16)} inactive for ${inactiveFor / 1000}s, closing",
                        )
                        closeConversationLink(destHashHex)
                    }
                }
            }

            // Stop checker if no active links
            if (_linkStates.value.isEmpty()) {
                Log.d(TAG, "No active links, stopping inactivity checker")
                inactivityCheckJob?.cancel()
                inactivityCheckJob = null
            }
        }

        private fun hexStringToBytes(hex: String): ByteArray {
            val cleanHex = hex.replace(" ", "").lowercase()
            require(cleanHex.length % 2 == 0) { "Hex string must have even length" }
            return ByteArray(cleanHex.length / 2) { i ->
                cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
