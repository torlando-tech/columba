package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.util.HexUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
            private const val LINK_STATUS_REFRESH_INTERVAL_MS = 5 * 1000L // Refresh link status every 5 seconds
            private const val LINK_ESTABLISHMENT_TIMEOUT_SECONDS = 5.0f // Quick timeout - peer should respond fast if online

            // Bandwidth thresholds for preset recommendations (bits per second)
            private const val THRESHOLD_LOW_BPS = 5_000L // < 5 kbps -> LOW
            private const val THRESHOLD_MEDIUM_BPS = 50_000L // < 50 kbps -> MEDIUM
            private const val THRESHOLD_HIGH_BPS = 500_000L // < 500 kbps -> HIGH

            // Interface type detection thresholds
            private const val THRESHOLD_SLOW_INTERFACE_BPS = 50_000L // < 50 kbps = likely LoRa/BLE
            private const val THRESHOLD_FAST_INTERFACE_BPS = 500_000L // >= 500 kbps = likely WiFi/TCP

            /**
             * Convert a bitrate to a compression preset based on thresholds.
             */
            fun presetFromBitrate(bps: Long): ImageCompressionPreset =
                when {
                    bps < THRESHOLD_LOW_BPS -> ImageCompressionPreset.LOW
                    bps < THRESHOLD_MEDIUM_BPS -> ImageCompressionPreset.MEDIUM
                    bps < THRESHOLD_HIGH_BPS -> ImageCompressionPreset.HIGH
                    else -> ImageCompressionPreset.ORIGINAL
                }

            /**
             * Format transfer time in seconds to a human-readable string.
             */
            fun formatTransferTime(seconds: Double): String {
                if (seconds < 0) return "Unknown"
                val totalSeconds = seconds.toInt()
                return when {
                    totalSeconds < 1 -> "< 1s"
                    totalSeconds < 60 -> "~${totalSeconds}s"
                    totalSeconds < 3600 -> {
                        val minutes = totalSeconds / 60
                        val remainingSeconds = totalSeconds % 60
                        if (remainingSeconds > 0) "~${minutes}m ${remainingSeconds}s" else "~${minutes}m"
                    }
                    else -> {
                        val hours = totalSeconds / 3600
                        val remainingMinutes = (totalSeconds % 3600) / 60
                        if (remainingMinutes > 0) "~${hours}h ${remainingMinutes}m" else "~${hours}h"
                    }
                }
            }
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Active link states per conversation (keyed by destination hash hex).
         */
        private val _linkStates = MutableStateFlow<Map<String, LinkState>>(emptyMap())
        val linkStates: StateFlow<Map<String, LinkState>> = _linkStates.asStateFlow()

        /**
         * Last message sent time per conversation (for inactivity tracking).
         * Uses ConcurrentHashMap for thread-safe access from multiple coroutines.
         */
        private val lastMessageSentTime = ConcurrentHashMap<String, Long>()

        /**
         * Background job for checking inactive links.
         */
        private var inactivityCheckJob: Job? = null

        /**
         * Background job for refreshing link status (detects incoming links).
         */
        private var linkStatusRefreshJob: Job? = null

        /**
         * State of a conversation link.
         *
         * Contains all metrics needed for connection quality assessment and
         * image transfer time estimation.
         */
        data class LinkState(
            /** Whether link is currently active */
            val isActive: Boolean,
            /** Link establishment rate in bits/sec (if active) */
            val establishmentRateBps: Long? = null,
            /** Actual measured throughput from prior transfers (most accurate) */
            val expectedRateBps: Long? = null,
            /** First hop interface bitrate (for fast links like WiFi) */
            val nextHopBitrateBps: Long? = null,
            /** Round-trip time in seconds */
            val rttSeconds: Double? = null,
            /** Number of hops to destination */
            val hops: Int? = null,
            /** Link MTU in bytes (higher = faster connection) */
            val linkMtu: Int? = null,
            /** Whether link establishment is in progress */
            val isEstablishing: Boolean = false,
            /** Error message if establishment failed */
            val error: String? = null,
        ) {
            /**
             * Get the best available rate estimate in bits per second.
             *
             * Uses conservative estimation for mesh networks where slow intermediate
             * hops can bottleneck the path even if the first hop is fast.
             *
             * Preference order:
             * 1. expectedRateBps - Actual measured throughput (most accurate)
             * 2. establishmentRateBps - Measured during link handshake (path quality)
             * 3. nextHopBitrateBps - First hop only (least reliable for multi-hop)
             */
            val bestRateBps: Long?
                get() {
                    val expected = expectedRateBps
                    if (expected != null && expected > 0) {
                        return expected
                    }
                    val establishment = establishmentRateBps?.takeIf { it > 0 }
                    if (establishment != null) {
                        return establishment
                    }
                    return nextHopBitrateBps?.takeIf { it > 0 }
                }

            /**
             * Calculate estimated transfer time for a given size in bytes.
             * Returns time in seconds, or null if no rate is available.
             */
            fun estimateTransferTimeSeconds(sizeBytes: Long): Double? {
                val rateBps = bestRateBps ?: return null
                if (rateBps <= 0) return null
                val sizeBits = sizeBytes * 8
                return sizeBits.toDouble() / rateBps
            }

            /**
             * Estimate transfer time formatted as human-readable string.
             */
            fun estimateTransferTimeFormatted(sizeBytes: Long): String? {
                val seconds = estimateTransferTimeSeconds(sizeBytes) ?: return null
                return formatTransferTime(seconds)
            }

            /**
             * Recommend a compression preset based on link metrics.
             *
             * Algorithm prioritizes actual measurements over interface speed:
             * 1. Use link quality metrics if available (actual measured performance)
             * 2. If next hop is slow (< 50 kbps, likely LoRa/BLE) → use interface speed
             * 3. If single hop with fast interface and no measurements → trust interface
             * 4. Fall back to hop count heuristics if no rate data available
             */
            @Suppress("ReturnCount")
            fun recommendPreset(): ImageCompressionPreset {
                val interfaceBitrate = nextHopBitrateBps?.takeIf { it > 0 }
                val linkRate = expectedRateBps ?: establishmentRateBps
                val hopCount = hops

                // 1. Always prefer actual link measurements over interface speed
                if (linkRate != null && linkRate > 0) {
                    return presetFromBitrate(linkRate)
                }

                // 2. Use interface bitrate for various scenarios
                if (interfaceBitrate != null) {
                    return recommendFromInterfaceBitrate(interfaceBitrate, hopCount)
                }

                // 3. Last resort: hop count or error-based heuristics
                return recommendFromHopCountOrError(hopCount)
            }

            private fun recommendFromInterfaceBitrate(
                interfaceBitrate: Long,
                hopCount: Int?,
            ): ImageCompressionPreset =
                when {
                    // Slow interface (< 50 kbps, likely LoRa/BLE) - use it directly
                    interfaceBitrate < THRESHOLD_SLOW_INTERFACE_BPS -> presetFromBitrate(interfaceBitrate)
                    // Single hop with fast interface - trust it
                    hopCount == 1 && interfaceBitrate >= THRESHOLD_FAST_INTERFACE_BPS -> presetFromBitrate(interfaceBitrate)
                    // Multi-hop with fast first hop - be conservative (can't trust fast first hop)
                    hopCount != null && hopCount > 1 -> ImageCompressionPreset.MEDIUM
                    // Fallback: use interface bitrate
                    else -> presetFromBitrate(interfaceBitrate)
                }

            private fun recommendFromHopCountOrError(hopCount: Int?): ImageCompressionPreset =
                when {
                    hopCount != null && hopCount <= 1 -> ImageCompressionPreset.HIGH
                    hopCount != null && hopCount <= 3 -> ImageCompressionPreset.MEDIUM
                    hopCount != null -> ImageCompressionPreset.LOW
                    error != null -> ImageCompressionPreset.LOW // No connection
                    else -> ImageCompressionPreset.MEDIUM // Unknown state
                }
        }

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
                // Check if already establishing or active to prevent duplicate concurrent calls
                val currentState = _linkStates.value[destHashHex]
                if (currentState?.isEstablishing == true) {
                    Log.d(TAG, "Link to ${destHashHex.take(16)} already establishing, skipping")
                    return@launch
                }
                if (currentState?.isActive == true) {
                    Log.d(TAG, "Link to ${destHashHex.take(16)} already active, skipping")
                    return@launch
                }

                Log.d(TAG, "Opening conversation link to ${destHashHex.take(16)}...")

                // Mark as establishing
                updateLinkState(destHashHex, LinkState(isActive = false, isEstablishing = true))

                try {
                    val destHashBytes = HexUtils.hexToBytes(destHashHex)
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
                                    "active=${linkResult.isActive}, rate=${linkResult.establishmentRateBps}, " +
                                    "expected=${linkResult.expectedRateBps}, mtu=${linkResult.linkMtu}",
                            )
                            updateLinkState(
                                destHashHex,
                                LinkState(
                                    isActive = linkResult.isActive,
                                    establishmentRateBps = linkResult.establishmentRateBps,
                                    expectedRateBps = linkResult.expectedRateBps,
                                    nextHopBitrateBps = linkResult.nextHopBitrateBps,
                                    rttSeconds = linkResult.rttSeconds,
                                    hops = linkResult.hops,
                                    linkMtu = linkResult.linkMtu,
                                    isEstablishing = false,
                                ),
                            )
                            // Start inactivity timer only for NEW link establishments
                            // Reused links keep their existing timer to prevent indefinite keep-alive
                            if (!linkResult.alreadyExisted) {
                                lastMessageSentTime[destHashHex] = System.currentTimeMillis()
                            }
                            startInactivityChecker()
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

                // Start periodic refresh to detect incoming links from peer
                startLinkStatusRefresh()
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
                    val destHashBytes = HexUtils.hexToBytes(destHashHex)
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
                val destHashBytes = HexUtils.hexToBytes(destHashHex)
                val result = reticulumProtocol.getConversationLinkStatus(destHashBytes)

                val state =
                    LinkState(
                        isActive = result.isActive,
                        establishmentRateBps = result.establishmentRateBps,
                        expectedRateBps = result.expectedRateBps,
                        nextHopBitrateBps = result.nextHopBitrateBps,
                        rttSeconds = result.rttSeconds,
                        hops = result.hops,
                        linkMtu = result.linkMtu,
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
            synchronized(this) {
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
        }

        /**
         * Start periodic link status refresh to detect incoming links.
         *
         * This is needed because when a peer establishes a link TO us, our initial
         * outgoing link attempt may have failed. This periodic check will detect
         * when the peer's incoming link becomes available.
         */
        private fun startLinkStatusRefresh() {
            synchronized(this) {
                if (linkStatusRefreshJob?.isActive == true) return

                linkStatusRefreshJob =
                    scope.launch {
                        Log.d(TAG, "Starting link status refresh")
                        while (true) {
                            delay(LINK_STATUS_REFRESH_INTERVAL_MS)
                            refreshNonActiveLinks()
                        }
                    }
            }
        }

        /**
         * Refresh status for links that are not currently active.
         * This detects incoming links that the peer established to us.
         */
        private suspend fun refreshNonActiveLinks() {
            val currentStates = _linkStates.value

            currentStates.forEach { (destHashHex, state) ->
                // Only refresh non-active, non-establishing links
                if (!state.isActive && !state.isEstablishing) {
                    try {
                        val destHashBytes = HexUtils.hexToBytes(destHashHex)
                        val result = reticulumProtocol.getConversationLinkStatus(destHashBytes)

                        if (result.isActive) {
                            Log.d(
                                TAG,
                                "Detected incoming link from ${destHashHex.take(16)}: " +
                                    "rate=${result.establishmentRateBps}, mtu=${result.linkMtu}",
                            )
                            updateLinkState(
                                destHashHex,
                                LinkState(
                                    isActive = true,
                                    establishmentRateBps = result.establishmentRateBps,
                                    expectedRateBps = result.expectedRateBps,
                                    nextHopBitrateBps = result.nextHopBitrateBps,
                                    rttSeconds = result.rttSeconds,
                                    hops = result.hops,
                                    linkMtu = result.linkMtu,
                                    isEstablishing = false,
                                ),
                            )
                            // Don't start inactivity timer for incoming links
                            // The peer owns this link and should manage its lifecycle
                            // We only track inactivity for links WE established
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error refreshing link status for ${destHashHex.take(16)}: ${e.message}")
                    }
                }
            }

            // Stop refresh job if no tracked conversations
            if (_linkStates.value.isEmpty()) {
                Log.d(TAG, "No tracked conversations, stopping link status refresh")
                synchronized(this@ConversationLinkManager) {
                    linkStatusRefreshJob?.cancel()
                    linkStatusRefreshJob = null
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
                synchronized(this@ConversationLinkManager) {
                    inactivityCheckJob?.cancel()
                    inactivityCheckJob = null
                }
            }
        }
    }
