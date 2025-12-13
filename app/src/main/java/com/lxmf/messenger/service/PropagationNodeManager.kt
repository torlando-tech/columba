package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a manual sync operation.
 */
sealed class SyncResult {
    data object Success : SyncResult()

    data class Error(val message: String) : SyncResult()

    data object NoRelay : SyncResult()
}

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
 * Represents the loading state of the relay configuration.
 * Used to distinguish between "not yet loaded from DB" and "loaded, no relay configured".
 */
sealed class RelayLoadState {
    /** Relay state is being loaded from database */
    data object Loading : RelayLoadState()

    /** Relay state has been loaded from database */
    data class Loaded(val relay: RelayInfo?) : RelayLoadState()
}

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
@Suppress("TooManyFunctions") // Relay management requires distinct operations
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

        /**
         * Build RelayInfo from a contact entity and auto-select setting.
         * Enriches with announce data if available.
         */
        private suspend fun buildRelayInfo(
            contact: ContactEntity?,
            isAutoSelect: Boolean,
        ): RelayInfo? {
            if (contact == null) return null
            val announce = announceRepository.getAnnounce(contact.destinationHash)
            return RelayInfo(
                destinationHash = contact.destinationHash,
                displayName =
                    announce?.peerName
                        ?: contact.customNickname
                        ?: contact.destinationHash.take(12),
                hops = announce?.hops ?: -1, // -1 = unknown
                isAutoSelected = isAutoSelect,
                lastSeenTimestamp =
                    announce?.lastSeenTimestamp
                        ?: contact.lastInteractionTimestamp,
            )
        }

        /**
         * Current relay state with loading indicator.
         * Starts as Loading, transitions to Loaded once database query completes.
         * This allows distinguishing "loading" from "no relay configured".
         */
        val currentRelayState: StateFlow<RelayLoadState> =
            contactRepository.getMyRelayFlow()
                .combine(settingsRepository.autoSelectPropagationNodeFlow) { contact, isAutoSelect ->
                    RelayLoadState.Loaded(buildRelayInfo(contact, isAutoSelect))
                }
                .stateIn(scope, SharingStarted.Eagerly, RelayLoadState.Loading)

        /**
         * Current relay derived from database (single source of truth).
         * Automatically stays in sync with database changes.
         * Returns null if loading or no relay configured.
         */
        val currentRelay: StateFlow<RelayInfo?> =
            currentRelayState
                .map { state -> (state as? RelayLoadState.Loaded)?.relay }
                .stateIn(scope, SharingStarted.Eagerly, null)

        private val _isSyncing = MutableStateFlow(false)
        val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

        private val _lastSyncTimestamp = MutableStateFlow<Long?>(null)
        val lastSyncTimestamp: StateFlow<Long?> = _lastSyncTimestamp.asStateFlow()

        // Emits results only for manually triggered syncs (not periodic syncs)
        private val _manualSyncResult = MutableSharedFlow<SyncResult>()
        val manualSyncResult: SharedFlow<SyncResult> = _manualSyncResult.asSharedFlow()

        private var announceObserverJob: Job? = null
        private var syncJob: Job? = null
        private var settingsObserverJob: Job? = null

        private var relayObserverJob: Job? = null

        /**
         * Initialize the manager - start observing database for relay changes.
         * Call this when the Reticulum service becomes ready.
         */
        fun start() {
            Log.d(TAG, "Starting PropagationNodeManager")

            // Load last sync timestamp
            scope.launch {
                _lastSyncTimestamp.value = settingsRepository.getLastSyncTimestamp()
            }

            // Observe relay changes from database and sync to Python layer
            // This replaces the old restoreLastRelay() approach - now we're reactive
            relayObserverJob =
                scope.launch {
                    observeRelayChanges()
                }

            // Start observing propagation node announces for auto-selection
            announceObserverJob =
                scope.launch {
                    observePropagationNodeAnnounces()
                }

            // Observe settings changes to restart sync with new interval
            settingsObserverJob =
                scope.launch {
                    settingsRepository.retrievalIntervalSecondsFlow.collect { _ ->
                        // Restart periodic sync when interval changes
                        Log.d(TAG, "Restarting periodic sync with new settings")
                        startPeriodicSync()
                    }
                }

            // Start periodic sync with propagation node
            startPeriodicSync()
        }

        /**
         * Observe relay changes from database and sync to Python layer.
         * This is the reactive replacement for restoreLastRelay().
         */
        private suspend fun observeRelayChanges() {
            // Use distinctUntilChanged to only update Python when relay actually changes
            currentRelay
                .map { it?.destinationHash }
                .distinctUntilChanged()
                .collect { destinationHash ->
                    if (destinationHash != null) {
                        val destHashBytes = destinationHash.hexToByteArray()
                        reticulumProtocol.setOutboundPropagationNode(destHashBytes)
                        Log.i(TAG, "Python layer synced with relay: $destinationHash")
                    } else {
                        reticulumProtocol.setOutboundPropagationNode(null)
                        Log.i(TAG, "Python layer cleared - no relay configured")
                    }
                }
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping PropagationNodeManager")
            relayObserverJob?.cancel()
            announceObserverJob?.cancel()
            syncJob?.cancel()
            settingsObserverJob?.cancel()
            relayObserverJob = null
            announceObserverJob = null
            syncJob = null
            settingsObserverJob = null
        }

        /**
         * Called when a propagation node announce is received.
         * Implements Sideband's algorithm: select nearest by hop count, only switch if new node has <= hops.
         *
         * Note: This method only updates the database. The currentRelay Flow automatically
         * picks up changes, and observeRelayChanges() syncs to Python layer.
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

            // Get current relay from the database-derived StateFlow
            val current = currentRelay.value

            // Auto-selection logic (from Sideband)
            val shouldSwitch =
                when {
                    current == null -> true // No relay yet
                    hops < current.hops || current.hops == -1 -> true // New node is closer (or current hops unknown)
                    hops == current.hops && destinationHash == current.destinationHash -> true // Same node
                    else -> false // Current node is closer, keep it
                }

            if (shouldSwitch) {
                Log.i(TAG, "Switching to relay $displayName ($destinationHash) at $hops hops")

                // Auto-add to contacts if not already present
                val contactExists = contactRepository.hasContact(destinationHash)
                Log.d(TAG, "Contact exists for $destinationHash: $contactExists")
                if (!contactExists) {
                    val result = contactRepository.addContactFromAnnounce(destinationHash, publicKey)
                    Log.d(TAG, "Added contact from announce: ${result.isSuccess}, error: ${result.exceptionOrNull()?.message}")
                }

                // Mark as relay in contacts (clears other relays first)
                // This updates the database, which triggers currentRelay Flow,
                // which triggers observeRelayChanges() to sync Python layer
                Log.d(TAG, "Setting as my relay: $destinationHash")
                contactRepository.setAsMyRelay(destinationHash, clearOther = true)
                Log.d(TAG, "Set as my relay complete")
            }
        }

        /**
         * Manually set a specific propagation node as relay.
         * This disables auto-selection.
         *
         * Note: This method only updates settings and database. The currentRelay Flow
         * automatically picks up changes, and observeRelayChanges() syncs to Python layer.
         */
        suspend fun setManualRelay(
            destinationHash: String,
            displayName: String,
        ) {
            Log.i(TAG, "User manually selected relay: $displayName")

            // Disable auto-select and save manual selection
            settingsRepository.saveAutoSelectPropagationNode(false)
            settingsRepository.saveManualPropagationNode(destinationHash)

            // Update contacts - add if needed and mark as relay
            val announce = announceRepository.getAnnounce(destinationHash)
            if (!contactRepository.hasContact(destinationHash) && announce != null) {
                contactRepository.addContactFromAnnounce(destinationHash, announce.publicKey)
            }

            // This updates the database, which triggers currentRelay Flow,
            // which triggers observeRelayChanges() to sync Python layer
            contactRepository.setAsMyRelay(destinationHash, clearOther = true)
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
                // No known propagation nodes, clear and wait for announces
                // Database update triggers currentRelay Flow → observeRelayChanges() → Python sync
                contactRepository.clearMyRelay()
            }
        }

        /**
         * Clear the current relay (no propagation node selected).
         */
        suspend fun clearRelay() {
            Log.i(TAG, "Clearing relay selection")

            settingsRepository.saveManualPropagationNode(null)
            // Database update triggers currentRelay Flow → observeRelayChanges() → Python sync
            contactRepository.clearMyRelay()
        }

        /**
         * Called when the current relay contact is deleted by the user.
         * Clears current state and triggers auto-selection of a new relay if enabled.
         */
        suspend fun onRelayDeleted() {
            Log.i(TAG, "Relay contact was deleted, selecting new relay")

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
                // currentRelay Flow will emit null, observeRelayChanges() will sync to Python
            }
        }

        /**
         * Get an alternative propagation node, excluding specified relays.
         *
         * Used when the current relay is unreachable and message needs retry via a different relay.
         * Returns the nearest available relay by hop count that is not in the exclude list.
         *
         * @param excludeHashes List of relay destination hashes to exclude (already tried)
         * @return RelayInfo for nearest available relay, or null if none available
         */
        suspend fun getAlternativeRelay(excludeHashes: List<String>): RelayInfo? {
            val propagationNodes = announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).first()

            // Filter out excluded relays
            val availableNodes = propagationNodes.filter { node ->
                node.destinationHash !in excludeHashes
            }

            // Find the nearest available relay by hop count
            val nearest = availableNodes.minByOrNull { it.hops }

            return if (nearest != null) {
                Log.i(
                    TAG,
                    "Found alternative relay: ${nearest.peerName} (${nearest.destinationHash}) at ${nearest.hops} hops " +
                        "(excluded ${excludeHashes.size} relays)",
                )
                RelayInfo(
                    destinationHash = nearest.destinationHash,
                    displayName = nearest.peerName,
                    hops = nearest.hops,
                    isAutoSelected = true,
                    lastSeenTimestamp = nearest.lastSeenTimestamp,
                )
            } else {
                Log.w(TAG, "No alternative relays available (excluded ${excludeHashes.size} relays)")
                null
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

        // ==================== PROPAGATION NODE SYNC ====================

        /**
         * Start periodic sync with the propagation node.
         * This downloads messages that were sent via propagation.
         * Respects the autoRetrieveEnabled setting and uses configurable interval.
         */
        private fun startPeriodicSync() {
            syncJob?.cancel()
            syncJob =
                scope.launch {
                    // Initial delay to let things settle
                    kotlinx.coroutines.delay(5_000)

                    while (true) {
                        // Check if auto-retrieve is enabled
                        val autoRetrieveEnabled = settingsRepository.getAutoRetrieveEnabled()
                        if (autoRetrieveEnabled) {
                            try {
                                syncWithPropagationNode()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during propagation sync", e)
                            }
                        }

                        // Get configurable interval from settings
                        val intervalSeconds = settingsRepository.getRetrievalIntervalSeconds()
                        val intervalMs = intervalSeconds * 1000L
                        kotlinx.coroutines.delay(intervalMs)
                    }
                }
        }

        /**
         * Sync messages from the propagation node.
         * This requests any waiting messages from the configured propagation node.
         */
        suspend fun syncWithPropagationNode() {
            val relay = currentRelay.value
            if (relay == null) {
                Log.d(TAG, "No relay configured, skipping sync")
                return
            }

            // Don't start a new sync if one is already in progress
            if (_isSyncing.value) {
                Log.d(TAG, "Sync already in progress, skipping")
                return
            }

            Log.d(TAG, "📡 Syncing with propagation node: ${relay.displayName}")
            _isSyncing.value = true

            try {
                val result = reticulumProtocol.requestMessagesFromPropagationNode()
                result.onSuccess { state ->
                    Log.d(TAG, "Propagation sync started: state=${state.stateName}")
                    // Update last sync timestamp
                    val timestamp = System.currentTimeMillis()
                    _lastSyncTimestamp.value = timestamp
                    settingsRepository.saveLastSyncTimestamp(timestamp)
                }.onFailure { error ->
                    Log.w(TAG, "Propagation sync failed: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting messages from propagation node", e)
            } finally {
                _isSyncing.value = false
            }
        }

        /**
         * Manually trigger a sync with the propagation node.
         * Useful for pull-to-refresh or when user explicitly wants to check for messages.
         * Emits result to manualSyncResult SharedFlow for UI notification.
         */
        suspend fun triggerSync() {
            // Wait for relay state to be loaded from database
            // This prevents race conditions where sync is triggered before DB query completes
            val state = currentRelayState.first { it is RelayLoadState.Loaded }
            val relay = (state as RelayLoadState.Loaded).relay

            if (relay == null) {
                Log.d(TAG, "No relay configured, cannot sync")
                _manualSyncResult.emit(SyncResult.NoRelay)
                return
            }

            // Don't start a new sync if one is already in progress
            if (_isSyncing.value) {
                Log.d(TAG, "Sync already in progress, skipping manual trigger")
                return
            }

            Log.d(TAG, "📡 Manual sync with propagation node: ${relay.displayName}")
            _isSyncing.value = true

            try {
                val result = reticulumProtocol.requestMessagesFromPropagationNode()
                result.onSuccess { state ->
                    Log.d(TAG, "Manual sync started: state=${state.stateName}")
                    // Update last sync timestamp
                    val timestamp = System.currentTimeMillis()
                    _lastSyncTimestamp.value = timestamp
                    settingsRepository.saveLastSyncTimestamp(timestamp)
                    _manualSyncResult.emit(SyncResult.Success)
                }.onFailure { error ->
                    Log.w(TAG, "Manual sync failed: ${error.message}")
                    _manualSyncResult.emit(SyncResult.Error(error.message ?: "Unknown error"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during manual sync", e)
                _manualSyncResult.emit(SyncResult.Error(e.message ?: "Unknown error"))
            } finally {
                _isSyncing.value = false
            }
        }
    }
