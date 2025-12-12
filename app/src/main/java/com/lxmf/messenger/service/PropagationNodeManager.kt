package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Information about the current relay (propagation node).
 */
data class RelayInfo(
    val destinationHash: String,
    val displayName: String,
    val hops: Int,
    val isAutoSelected: Boolean,
    val lastSeenTimestamp: Long,
)

/**
 * Manages propagation node (relay) selection for LXMF message delivery.
 *
 * This class implements Sideband's auto-selection algorithm:
 * - Listen for propagation node announces
 * - Automatically select the nearest node (by hop count)
 * - Only switch to a new node if it has fewer or equal hops
 * - Fall back to last known propagation node on restart
 *
 * Users can also manually select a specific propagation node, which
 * disables auto-selection until they re-enable it.
 */
@Singleton
class PropagationNodeManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val contactRepository: ContactRepository,
        private val announceRepository: AnnounceRepository,
        private val reticulumProtocol: ReticulumProtocol,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "PropagationNodeManager"
        }

        private val _currentRelay = MutableStateFlow<RelayInfo?>(null)
        val currentRelay: StateFlow<RelayInfo?> = _currentRelay.asStateFlow()

        private var announceObserverJob: Job? = null

        /**
         * Initialize the manager - restore last relay and start observing announces.
         * Call this when the Reticulum service becomes ready.
         */
        fun start() {
            Log.d(TAG, "Starting PropagationNodeManager")

            // Restore last relay from settings
            scope.launch {
                restoreLastRelay()
            }

            // Start observing propagation node announces for auto-selection
            announceObserverJob = scope.launch {
                observePropagationNodeAnnounces()
            }
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping PropagationNodeManager")
            announceObserverJob?.cancel()
            announceObserverJob = null
        }

        /**
         * Called when a propagation node announce is received.
         * Implements Sideband's algorithm: select nearest by hop count, only switch if new node has <= hops.
         */
        suspend fun onPropagationNodeAnnounce(
            destinationHash: String,
            displayName: String,
            hops: Int,
            publicKey: ByteArray,
        ) {
            val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
            val manualNode = settingsRepository.getManualPropagationNode()

            // If user has manually selected a node, don't auto-switch
            if (!isAutoSelect && manualNode != null) {
                Log.d(TAG, "Manual relay selected, ignoring announce from $displayName")
                return
            }

            val current = _currentRelay.value

            // Auto-selection logic (from Sideband)
            val shouldSwitch = when {
                current == null -> true  // No relay yet
                hops < current.hops -> true  // New node is closer
                hops == current.hops && destinationHash == current.destinationHash -> true  // Same node, update info
                else -> false  // Current node is closer, keep it
            }

            if (shouldSwitch) {
                Log.i(TAG, "Switching to relay $displayName ($destinationHash) at $hops hops")

                // Update settings
                settingsRepository.saveLastPropagationNode(destinationHash)

                // Update Python layer
                val destHashBytes = destinationHash.hexToByteArray()
                reticulumProtocol.setOutboundPropagationNode(destHashBytes)

                // Auto-add to contacts if not already present
                val contactExists = contactRepository.hasContact(destinationHash)
                Log.d(TAG, "Contact exists for $destinationHash: $contactExists")
                if (!contactExists) {
                    val result = contactRepository.addContactFromAnnounce(destinationHash, publicKey)
                    Log.d(TAG, "Added contact from announce: ${result.isSuccess}, error: ${result.exceptionOrNull()?.message}")
                }

                // Mark as relay in contacts (clears other relays first)
                Log.d(TAG, "Setting as my relay: $destinationHash")
                contactRepository.setAsMyRelay(destinationHash, clearOther = true)
                Log.d(TAG, "Set as my relay complete")

                // Update current relay state
                _currentRelay.value = RelayInfo(
                    destinationHash = destinationHash,
                    displayName = displayName,
                    hops = hops,
                    isAutoSelected = isAutoSelect,
                    lastSeenTimestamp = System.currentTimeMillis(),
                )
            }
        }

        /**
         * Manually set a specific propagation node as relay.
         * This disables auto-selection.
         */
        suspend fun setManualRelay(destinationHash: String, displayName: String) {
            Log.i(TAG, "User manually selected relay: $displayName")

            // Disable auto-select
            settingsRepository.saveAutoSelectPropagationNode(false)
            settingsRepository.saveManualPropagationNode(destinationHash)
            settingsRepository.saveLastPropagationNode(destinationHash)

            // Get announce info for hop count
            val announce = announceRepository.getAnnounce(destinationHash)
            val hops = announce?.hops ?: 0

            // Update Python layer
            val destHashBytes = destinationHash.hexToByteArray()
            reticulumProtocol.setOutboundPropagationNode(destHashBytes)

            // Update contacts - add if needed and mark as relay
            if (!contactRepository.hasContact(destinationHash) && announce != null) {
                contactRepository.addContactFromAnnounce(destinationHash, announce.publicKey)
            }
            contactRepository.setAsMyRelay(destinationHash, clearOther = true)

            // Update state
            _currentRelay.value = RelayInfo(
                destinationHash = destinationHash,
                displayName = displayName,
                hops = hops,
                isAutoSelected = false,
                lastSeenTimestamp = System.currentTimeMillis(),
            )
        }

        /**
         * Switch back to auto-selection mode.
         */
        suspend fun enableAutoSelect() {
            Log.i(TAG, "Enabling auto-select for propagation node")

            settingsRepository.saveAutoSelectPropagationNode(true)
            settingsRepository.saveManualPropagationNode(null)

            // Try to find nearest known propagation node
            val propagationNodes = announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).first()
            val nearest = propagationNodes.minByOrNull { it.hops }

            if (nearest != null) {
                onPropagationNodeAnnounce(
                    nearest.destinationHash,
                    nearest.peerName,
                    nearest.hops,
                    nearest.publicKey,
                )
            } else {
                // No known propagation nodes, clear current and wait for announces
                _currentRelay.value = null
                reticulumProtocol.setOutboundPropagationNode(null)
                contactRepository.clearMyRelay()
            }
        }

        /**
         * Clear the current relay (no propagation node selected).
         */
        suspend fun clearRelay() {
            Log.i(TAG, "Clearing relay selection")

            settingsRepository.saveManualPropagationNode(null)
            _currentRelay.value = null
            reticulumProtocol.setOutboundPropagationNode(null)
            contactRepository.clearMyRelay()
        }

        /**
         * Called when the current relay contact is deleted by the user.
         * Clears current state and triggers auto-selection of a new relay if enabled.
         */
        suspend fun onRelayDeleted() {
            Log.i(TAG, "Relay contact was deleted, selecting new relay")

            // Clear current relay state
            _currentRelay.value = null

            // If manual node was deleted, switch to auto-select
            val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
            if (!isAutoSelect) {
                Log.d(TAG, "Manual relay deleted, enabling auto-select")
                settingsRepository.saveAutoSelectPropagationNode(true)
            }
            settingsRepository.saveManualPropagationNode(null)

            // Find the best available propagation node
            val propagationNodes = announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).first()
            val nearest = propagationNodes.minByOrNull { it.hops }

            if (nearest != null) {
                Log.i(TAG, "Auto-selecting new relay: ${nearest.peerName} at ${nearest.hops} hops")
                onPropagationNodeAnnounce(
                    nearest.destinationHash,
                    nearest.peerName,
                    nearest.hops,
                    nearest.publicKey,
                )
            } else {
                Log.d(TAG, "No propagation nodes available for auto-selection")
                reticulumProtocol.setOutboundPropagationNode(null)
            }
        }

        /**
         * Restore the last used relay on app start.
         */
        private suspend fun restoreLastRelay() {
            val lastRelay = settingsRepository.getLastPropagationNode()
            val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()

            if (lastRelay != null) {
                val announce = announceRepository.getAnnounce(lastRelay)
                if (announce != null && announce.nodeType == "PROPAGATION_NODE") {
                    Log.i(TAG, "Restoring last relay: ${announce.peerName}")

                    // Update Python layer
                    val destHashBytes = lastRelay.hexToByteArray()
                    reticulumProtocol.setOutboundPropagationNode(destHashBytes)

                    _currentRelay.value = RelayInfo(
                        destinationHash = lastRelay,
                        displayName = announce.peerName,
                        hops = announce.hops,
                        isAutoSelected = isAutoSelect,
                        lastSeenTimestamp = announce.lastSeenTimestamp,
                    )
                } else {
                    Log.d(TAG, "Last relay not found in announces, waiting for new announce")
                }
            } else {
                Log.d(TAG, "No last relay saved")
            }
        }

        /**
         * Observe propagation node announces for auto-selection.
         */
        private suspend fun observePropagationNodeAnnounces() {
            announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).collect { propagationNodes ->
                val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()

                if (isAutoSelect && propagationNodes.isNotEmpty()) {
                    // Find the nearest propagation node
                    val nearest = propagationNodes.minByOrNull { it.hops }
                    if (nearest != null) {
                        onPropagationNodeAnnounce(
                            nearest.destinationHash,
                            nearest.peerName,
                            nearest.hops,
                            nearest.publicKey,
                        )
                    }
                }
            }
        }

        /**
         * Convert hex string to ByteArray.
         */
        private fun String.hexToByteArray(): ByteArray {
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
