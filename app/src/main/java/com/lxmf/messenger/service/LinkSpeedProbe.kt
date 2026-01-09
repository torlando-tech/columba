package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.util.HexUtils
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.LinkSpeedProbeResult
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for probing link speed to a destination for adaptive image compression.
 *
 * Probes the link to either:
 * - The direct recipient (if delivery method is "direct")
 * - The propagation node (if delivery method is "propagated")
 *
 * The measured speed is used to recommend an appropriate compression preset
 * and estimate transfer times.
 */
@Singleton
class LinkSpeedProbe
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val settingsRepository: SettingsRepository,
        private val propagationNodeManager: PropagationNodeManager,
        private val conversationLinkManager: ConversationLinkManager,
    ) {
        companion object {
            private const val TAG = "LinkSpeedProbe"
            private const val DEFAULT_TIMEOUT_SECONDS = 10.0f

            // Bandwidth thresholds for preset recommendations (bits per second)
            private const val THRESHOLD_LOW_BPS = 5_000L // < 5 kbps -> LOW
            private const val THRESHOLD_MEDIUM_BPS = 50_000L // < 50 kbps -> MEDIUM
            private const val THRESHOLD_HIGH_BPS = 500_000L // < 500 kbps -> HIGH
        }

        private val _probeState = MutableStateFlow<ProbeState>(ProbeState.Idle)
        val probeState: StateFlow<ProbeState> = _probeState.asStateFlow()

        /**
         * State of a link speed probe operation.
         */
        sealed class ProbeState {
            /** No probe in progress */
            data object Idle : ProbeState()

            /** Probe is in progress */
            data class Probing(
                val targetHash: String,
                val targetType: TargetType,
            ) : ProbeState()

            /** Probe completed successfully */
            data class Complete(
                val result: LinkSpeedProbeResult,
                val targetType: TargetType,
                val recommendedPreset: ImageCompressionPreset,
            ) : ProbeState()

            /** Probe failed but may have path info */
            data class Failed(
                val reason: String,
                val targetType: TargetType?,
                val result: LinkSpeedProbeResult? = null,
            ) : ProbeState()
        }

        /**
         * Type of target being probed.
         */
        enum class TargetType {
            /** Probing direct link to recipient */
            DIRECT,

            /** Probing link to propagation node */
            PROPAGATION_NODE,
        }

        /**
         * Start probing link speed to the appropriate target.
         *
         * If delivery method is "propagated", probes the current propagation node.
         * Otherwise, probes the direct recipient.
         *
         * @param recipientHash The recipient's destination hash (hex string)
         * @return The probe result, or null if probe failed
         */
        suspend fun probe(recipientHash: String): LinkSpeedProbeResult? {
            return withContext(Dispatchers.IO) {
                try {
                    // Guard against concurrent probes - return early if already probing
                    val currentState = _probeState.value
                    if (currentState is ProbeState.Probing) {
                        Log.w(TAG, "Probe already in progress for ${currentState.targetHash.take(16)}, skipping")
                        return@withContext null
                    }

                    val deliveryMethod = settingsRepository.getDefaultDeliveryMethod()
                    Log.d(TAG, "Starting probe, delivery method: $deliveryMethod")

                    val (targetHash, targetType) =
                        when (deliveryMethod) {
                            "propagated" -> {
                                val relay = propagationNodeManager.currentRelay.value
                                if (relay == null) {
                                    Log.w(TAG, "No propagation node selected, falling back to direct")
                                    recipientHash to TargetType.DIRECT
                                } else {
                                    Log.d(TAG, "Probing propagation node: ${relay.destinationHash.take(16)}")
                                    relay.destinationHash to TargetType.PROPAGATION_NODE
                                }
                            }
                            else -> {
                                Log.d(TAG, "Probing direct recipient: ${recipientHash.take(16)}")
                                recipientHash to TargetType.DIRECT
                            }
                        }

                    // Check if ConversationLinkManager already has an active link with speed data
                    val existingLinkState = conversationLinkManager.getLinkState(targetHash)
                    if (existingLinkState?.isActive == true && existingLinkState.establishmentRateBps != null) {
                        Log.d(TAG, "Using existing link from ConversationLinkManager: ${existingLinkState.establishmentRateBps} bps")
                        val result =
                            LinkSpeedProbeResult(
                                status = "success",
                                establishmentRateBps = existingLinkState.establishmentRateBps,
                                expectedRateBps = null,
                                rttSeconds = null,
                                hops = null,
                                linkReused = true,
                            )
                        val recommendedPreset = recommendPreset(result)
                        _probeState.value = ProbeState.Complete(result, targetType, recommendedPreset)
                        return@withContext result
                    }

                    _probeState.value = ProbeState.Probing(targetHash, targetType)

                    // Convert hex string to bytes
                    val targetHashBytes =
                        try {
                            HexUtils.hexToBytes(targetHash)
                        } catch (e: Exception) {
                            Log.e(TAG, "Invalid target hash: $targetHash", e)
                            _probeState.value = ProbeState.Failed("Invalid destination hash", targetType)
                            return@withContext null
                        }

                    // Perform the probe
                    val result = reticulumProtocol.probeLinkSpeed(targetHashBytes, DEFAULT_TIMEOUT_SECONDS, deliveryMethod)

                    if (result.isSuccess) {
                        val recommendedPreset = recommendPreset(result)
                        Log.d(
                            TAG,
                            "Probe complete: ${result.bestRateBps} bps, " +
                                "RTT: ${result.rttSeconds}s, " +
                                "recommended: ${recommendedPreset.name}",
                        )
                        _probeState.value = ProbeState.Complete(result, targetType, recommendedPreset)
                        result
                    } else {
                        Log.w(TAG, "Probe failed: ${result.status}")
                        _probeState.value = ProbeState.Failed(result.status, targetType)
                        // Return the result anyway - it has useful info like hop count
                        result
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during probe", e)
                    _probeState.value = ProbeState.Failed(e.message ?: "Unknown error", null)
                    null
                }
            }
        }

        /**
         * Reset the probe state to idle.
         */
        fun reset() {
            _probeState.value = ProbeState.Idle
        }

        /**
         * Recommend a compression preset based on measured link speed.
         */
        fun recommendPreset(result: LinkSpeedProbeResult): ImageCompressionPreset {
            // First try the actual measured rate from link establishment
            val rateBps = result.bestRateBps
            if (rateBps != null) {
                return when {
                    rateBps < THRESHOLD_LOW_BPS -> ImageCompressionPreset.LOW
                    rateBps < THRESHOLD_MEDIUM_BPS -> ImageCompressionPreset.MEDIUM
                    rateBps < THRESHOLD_HIGH_BPS -> ImageCompressionPreset.HIGH
                    else -> ImageCompressionPreset.ORIGINAL
                }
            }

            // Fall back to next hop interface bitrate (available even on timeout)
            val nextHopBitrate = result.nextHopBitrateBps
            if (nextHopBitrate != null) {
                Log.d(TAG, "Using next hop bitrate for recommendation: $nextHopBitrate bps")
                return when {
                    nextHopBitrate < THRESHOLD_LOW_BPS -> ImageCompressionPreset.LOW
                    nextHopBitrate < THRESHOLD_MEDIUM_BPS -> ImageCompressionPreset.MEDIUM
                    nextHopBitrate < THRESHOLD_HIGH_BPS -> ImageCompressionPreset.HIGH
                    else -> ImageCompressionPreset.ORIGINAL
                }
            }

            // Final fallback: use hop count heuristics
            val hops = result.hops
            Log.d(TAG, "Using hop count heuristics for recommendation: $hops hops")
            return when {
                hops == null -> ImageCompressionPreset.MEDIUM
                hops <= 1 -> ImageCompressionPreset.HIGH
                hops <= 3 -> ImageCompressionPreset.MEDIUM
                else -> ImageCompressionPreset.LOW
            }
        }

        /**
         * Estimate transfer time for a given file size based on probe result.
         *
         * @param sizeBytes File size in bytes
         * @param result The probe result (uses bestRateBps)
         * @return Formatted time string like "~2m 30s" or null if can't estimate
         */
        fun estimateTransferTime(
            sizeBytes: Long,
            result: LinkSpeedProbeResult,
        ): String? {
            val seconds = result.estimateTransferTimeSeconds(sizeBytes) ?: return null
            return formatTransferTime(seconds)
        }

        /**
         * Format transfer time in seconds to a human-readable string.
         */
        private fun formatTransferTime(seconds: Double): String {
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
