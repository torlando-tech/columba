package network.columba.app.service

import android.util.Log
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.di.ApplicationScope
import network.columba.app.di.DefaultDispatcher
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.model.NetworkStatus
import network.columba.app.reticulum.protocol.PropagationState
import network.columba.app.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a manual sync operation.
 */
sealed class SyncResult {
    /** Sync completed successfully */
    data class Success(
        val messagesReceived: Int,
    ) : SyncResult()

    /** Sync failed with an error */
    data class Error(
        val message: String,
        val errorCode: Int? = null,
    ) : SyncResult()

    /** No relay is configured */
    data object NoRelay : SyncResult()

    /** Sync timed out */
    data object Timeout : SyncResult()
}

/**
 * Progress of sync operation for UI updates.
 */
sealed class SyncProgress {
    data object Idle : SyncProgress()

    data object Starting : SyncProgress()

    data class InProgress(
        val stateName: String,
        val progress: Float,
    ) : SyncProgress()

    data object Complete : SyncProgress()
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
    data class Loaded(
        val relay: RelayInfo?,
    ) : RelayLoadState()
}

/**
 * Represents the loading state of available relays list.
 * Used to distinguish between "not yet loaded from DB" and "loaded, no relays available".
 */
sealed class AvailableRelaysState {
    /** Relays are being loaded from database */
    data object Loading : AvailableRelaysState()

    /** Relays have been loaded from database */
    data class Loaded(
        val relays: List<RelayInfo>,
    ) : AvailableRelaysState()
}

/**
 * Manages propagation node (relay) selection for LXMF message delivery.
 *
 * Auto-selection is one-shot: "pick the best relay when asked, then stop."
 * This eliminates the feedback loop category by design — no continuous observer
 * means no Room invalidation loop (COLUMBA-3).
 *
 * Users can also manually select a specific propagation node, which
 * disables auto-selection until they re-enable it.
 */
@Singleton
@Suppress("TooManyFunctions") // Relay lifecycle inherently needs many operations
class PropagationNodeManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val contactRepository: ContactRepository,
        private val announceRepository: AnnounceRepository,
        private val reticulumProtocol: ReticulumProtocol,
        @ApplicationScope private val scope: CoroutineScope,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
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
                // -1 = unknown
                hops = announce?.hops ?: -1,
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
            contactRepository
                .getMyRelayFlow()
                .combine(settingsRepository.autoSelectPropagationNodeFlow) { contact, isAutoSelect ->
                    RelayLoadState.Loaded(buildRelayInfo(contact, isAutoSelect))
                }.stateIn(scope, SharingStarted.WhileSubscribed(5000L), RelayLoadState.Loading)

        /**
         * Current relay derived from database (single source of truth).
         * Automatically stays in sync with database changes.
         * Returns null if loading or no relay configured.
         */
        val currentRelay: StateFlow<RelayInfo?> =
            currentRelayState
                .map { state -> (state as? RelayLoadState.Loaded)?.relay }
                .stateIn(scope, SharingStarted.WhileSubscribed(5000L), null)

        /**
         * Available propagation nodes sorted by hop count (ascending), limited to 10.
         * Used for relay selection UI.
         *
         * Uses optimized SQL query with LIMIT to fetch only 10 rows.
         */
        val availableRelaysState: StateFlow<AvailableRelaysState> =
            announceRepository
                .getTopPropagationNodes(limit = 10)
                .map { announces ->
                    Log.d(TAG, "availableRelays: got ${announces.size} top propagation nodes from DB")
                    val relays =
                        announces.map { announce ->
                            RelayInfo(
                                destinationHash = announce.destinationHash,
                                displayName = announce.peerName,
                                hops = announce.hops,
                                isAutoSelected = false,
                                lastSeenTimestamp = announce.lastSeenTimestamp,
                            )
                        }
                    // Deduplicate by destinationHash to prevent LazyColumn duplicate key crash
                    // (issue #542: transient duplicates from data layer race conditions)
                    AvailableRelaysState.Loaded(relays.distinctBy { it.destinationHash })
                }.stateIn(scope, SharingStarted.WhileSubscribed(5000L), AvailableRelaysState.Loading)

        private val _isSyncing = MutableStateFlow(false)
        val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

        private val syncFinalized =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        // Kept so a new sync can cancel a still-delaying poll loop from the previous sync
        // before it wakes up and starts observing the new sync's flags.
        private var activePollJob: kotlinx.coroutines.Job? = null

        private val _lastSyncTimestamp = MutableStateFlow<Long?>(null)
        val lastSyncTimestamp: StateFlow<Long?> = _lastSyncTimestamp.asStateFlow()

        // Emits results only for manually triggered syncs (not periodic syncs)
        private val _manualSyncResult = MutableSharedFlow<SyncResult>()
        val manualSyncResult: SharedFlow<SyncResult> = _manualSyncResult.asSharedFlow()

        // Sync progress for UI updates (loading spinner, status page)
        private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
        val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

        // Track whether current sync was manually triggered (for toast display)
        private var _isManualSync = false

        // Timeout for sync operation (5 minutes for large transfers)
        private val syncTimeoutMs = 5 * 60 * 1000L

        private var syncJob: Job? = null
        private var settingsObserverJob: Job? = null
        private var propagationStateObserverJob: Job? = null

        private var relayObserverJob: Job? = null

        /**
         * Initialize the manager - start observing database for relay changes.
         * Call this when the Reticulum service becomes ready.
         */
        fun start() {
            // Guard against multiple starts - jobs are already running
            if (relayObserverJob != null) {
                Log.d(TAG, "PropagationNodeManager already started, skipping")
                return
            }
            Log.d(TAG, "Starting PropagationNodeManager")

            // Debug: Log nodeType distribution on startup
            scope.launch {
                try {
                    val nodeTypeCounts = announceRepository.getNodeTypeCounts()
                    Log.i(TAG, "📊 Database nodeType distribution:")
                    nodeTypeCounts.forEach { (nodeType, count) ->
                        Log.i(TAG, "   $nodeType: $count")
                    }
                    val propagationCount = nodeTypeCounts.find { it.first == "PROPAGATION_NODE" }?.second ?: 0
                    if (propagationCount == 0) {
                        Log.w(TAG, "⚠️ No PROPAGATION_NODE entries in database! Relay modal will be empty.")
                        Log.w(TAG, "   This is expected if no LXMF propagation nodes have announced with aspect 'lxmf.propagation'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting nodeType counts", e)
                }
            }

            // Load last sync timestamp
            scope.launch {
                _lastSyncTimestamp.value = settingsRepository.getLastSyncTimestamp()
            }

            // Observe relay changes from database and sync to Python layer
            relayObserverJob =
                scope.launch {
                    observeRelayChanges()
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

            // Observe propagation state changes for real-time sync progress
            propagationStateObserverJob =
                scope.launch {
                    observePropagationStateChanges()
                }

            // Start periodic sync with propagation node
            startPeriodicSync()

            // One-shot auto-select on startup (if auto mode + no relay configured)
            scope.launch {
                val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
                val loaded = currentRelayState.first { it is RelayLoadState.Loaded } as RelayLoadState.Loaded
                if (isAutoSelect && loaded.relay == null) {
                    selectBestRelay()
                }
            }
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
                    syncRelayToPython(destinationHash)
                }
        }

        /**
         * Sync relay to Python with retry logic.
         * Python may not be initialized when PropagationNodeManager starts early,
         * so we retry with backoff until successful.
         */
        private suspend fun syncRelayToPython(destinationHash: String?) {
            val maxRetries = 10
            val baseDelayMs = 500L

            for (attempt in 1..maxRetries) {
                val result =
                    if (destinationHash != null) {
                        val destHashBytes = destinationHash.hexToByteArray()
                        reticulumProtocol.setOutboundPropagationNode(destHashBytes)
                    } else {
                        reticulumProtocol.setOutboundPropagationNode(null)
                    }

                if (result.isSuccess) {
                    if (destinationHash != null) {
                        Log.i(TAG, "Python layer synced with relay: $destinationHash")
                    } else {
                        Log.i(TAG, "Python layer cleared - no relay configured")
                    }
                    return
                }

                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                if (attempt < maxRetries) {
                    val delayMs = baseDelayMs * attempt
                    Log.w(TAG, "Failed to sync relay to Python (attempt $attempt/$maxRetries): $error. Retrying in ${delayMs}ms...")
                    kotlinx.coroutines.delay(delayMs)
                } else {
                    Log.e(TAG, "Failed to sync relay to Python after $maxRetries attempts: $error")
                }
            }
        }

        /**
         * Observe propagation state changes from ReticulumProtocol.
         * Updates syncProgress flow for UI and handles completion/error states.
         */
        private suspend fun observePropagationStateChanges() {
            reticulumProtocol.propagationStateFlow.collect { state ->
                Log.d(TAG, "Propagation state update: ${state.stateName} (${state.state})")

                when (state.state) {
                    PropagationState.STATE_IDLE -> {
                        if (_isSyncing.value) {
                            // LXMF returned to IDLE during active sync — sync completed
                            // (possibly with 0 messages, or COMPLETE was missed by heartbeat)
                            Log.d(TAG, "IDLE observed while syncing — treating as completion")
                            handleSyncComplete(state.messagesReceived)
                        } else {
                            _syncProgress.value = SyncProgress.Idle
                        }
                    }
                    PropagationState.STATE_PATH_REQUESTED,
                    PropagationState.STATE_LINK_ESTABLISHING,
                    PropagationState.STATE_LINK_ESTABLISHED,
                    PropagationState.STATE_REQUEST_SENT,
                    -> {
                        _syncProgress.value = SyncProgress.InProgress(state.stateName, state.progress)
                    }
                    PropagationState.STATE_RECEIVING -> {
                        _syncProgress.value = SyncProgress.InProgress("Downloading", state.progress)
                    }
                    PropagationState.STATE_COMPLETE -> {
                        handleSyncComplete(state.messagesReceived)
                    }
                    else -> {
                        // Error states (0xf0 - 0xf4)
                        if (state.state >= 0xf0) {
                            handleSyncError(state)
                        }
                    }
                }
            }
        }

        /**
         * Poll LXMRouter propagation state until it reaches a terminal state.
         * Used on the native path where propagationStateFlow doesn't emit.
         */
        private suspend fun pollForSyncCompletion(timeoutJob: kotlinx.coroutines.Job) {
            val pollInterval = 500L
            // Poll for the full sync timeout rather than a hardcoded 60s. The previous 120-poll
            // cap left up to syncTimeoutMs - 60s of dead time where _isSyncing stayed true and
            // _syncProgress was frozen at the last InProgress snapshot, so the user saw a stuck
            // spinner for minutes before the watchdog fired.
            val maxPolls = (syncTimeoutMs / pollInterval).toInt().coerceAtLeast(1)
            for (i in 0 until maxPolls) {
                kotlinx.coroutines.delay(pollInterval)
                if (!_isSyncing.value) return

                val state = reticulumProtocol.getPropagationState().getOrNull()
                if (state != null) {
                    _syncProgress.value = SyncProgress.InProgress(state.stateName, state.progress)
                    if (handlePollResult(state, timeoutJob)) return
                }
            }
        }

        private suspend fun handlePollResult(
            state: network.columba.app.reticulum.protocol.PropagationState,
            timeoutJob: kotlinx.coroutines.Job,
        ): Boolean =
            when {
                state.stateName == "complete" || state.stateName == "idle" -> {
                    timeoutJob.cancel()
                    handleSyncComplete(state.messagesReceived)
                    true
                }
                state.stateName in listOf("failed", "no_link", "no_path") -> {
                    if (!syncFinalized.compareAndSet(false, true)) return true
                    timeoutJob.cancel()
                    _isSyncing.value = false
                    _syncProgress.value = SyncProgress.Idle
                    if (_isManualSync) {
                        _manualSyncResult.emit(SyncResult.Error("Sync failed: ${state.stateName}"))
                        _isManualSync = false
                    }
                    true
                }
                else -> false
            }

        /**
         * Handle successful sync completion.
         */
        private suspend fun handleSyncComplete(messagesReceived: Int) {
            if (!syncFinalized.compareAndSet(false, true)) return
            if (_isSyncing.value) {
                Log.d(TAG, "Sync complete: $messagesReceived messages received (manual=$_isManualSync)")
                _isSyncing.value = false

                // Only show Complete if messages were actually downloaded
                // Otherwise go straight to Idle (no "Download complete" for empty syncs)
                if (messagesReceived > 0) {
                    _syncProgress.value = SyncProgress.Complete

                    // Delayed reset to Idle - gives Room time to propagate DB changes
                    // so pending file notification doesn't flash "Tap to fetch" before disappearing
                    scope.launch {
                        delay(2000)
                        if (_syncProgress.value == SyncProgress.Complete) {
                            _syncProgress.value = SyncProgress.Idle
                        }
                    }
                } else {
                    _syncProgress.value = SyncProgress.Idle
                }

                // Update last sync timestamp
                val timestamp = System.currentTimeMillis()
                _lastSyncTimestamp.value = timestamp
                settingsRepository.saveLastSyncTimestamp(timestamp)

                // Emit result for UI only if manually triggered
                if (_isManualSync) {
                    _manualSyncResult.emit(SyncResult.Success(messagesReceived))
                    _isManualSync = false
                }
            }
        }

        /**
         * Handle sync error states.
         */
        private suspend fun handleSyncError(state: PropagationState) {
            if (_isSyncing.value) {
                val errorMsg =
                    when (state.state) {
                        0xf0 -> "No path to relay"
                        0xf1 -> "Connection failed"
                        0xf2 -> "Transfer failed"
                        0xf3 -> "Identity not received"
                        0xf4 -> "Access denied"
                        else -> "Unknown error (${state.state})"
                    }
                Log.w(TAG, "Sync error: $errorMsg (manual=$_isManualSync)")
                syncFinalized.set(true)
                _isSyncing.value = false
                _syncProgress.value = SyncProgress.Idle

                // Emit error for UI only if manually triggered
                if (_isManualSync) {
                    _manualSyncResult.emit(SyncResult.Error(errorMsg, state.state))
                    _isManualSync = false
                }
            }
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping PropagationNodeManager")
            relayObserverJob?.cancel()
            syncJob?.cancel()
            settingsObserverJob?.cancel()
            propagationStateObserverJob?.cancel()
            relayObserverJob = null
            syncJob = null
            settingsObserverJob = null
            propagationStateObserverJob = null
        }

        /**
         * One-shot: query DB for best available relay and set it.
         * Called on startup, when user enables auto-select, or after relay deletion.
         *
         * @param excludeHash Optional hash to exclude (e.g., just-deleted relay)
         */
        private suspend fun selectBestRelay(excludeHash: String? = null) {
            val propagationNodes =
                announceRepository
                    .getAnnouncesByTypes(listOf("PROPAGATION_NODE"))
                    .first()

            val candidates =
                if (excludeHash != null) {
                    propagationNodes.filter { it.destinationHash != excludeHash }
                } else {
                    propagationNodes
                }
            val nearest = candidates.minByOrNull { it.hops }

            if (nearest != null) {
                Log.i(TAG, "Auto-selecting relay: ${nearest.destinationHash.take(12)} at ${nearest.hops} hops")
                if (!contactRepository.hasContact(nearest.destinationHash)) {
                    val result = contactRepository.addContactFromAnnounce(nearest.destinationHash, nearest.publicKey)
                    if (result.isFailure) {
                        Log.e(TAG, "Failed to create contact for auto-selected relay: ${result.exceptionOrNull()?.message}")
                        return
                    }
                }
                // Idempotent — skips write if already set (COLUMBA-3 defense-in-depth)
                contactRepository.setAsMyRelay(nearest.destinationHash, clearOther = true)
            } else {
                Log.d(TAG, "No propagation nodes available for auto-selection")
            }
        }

        /**
         * Manually set a specific propagation node as relay.
         * This disables auto-selection.
         *
         * Note: This method only updates settings and database. The currentRelay Flow
         * automatically picks up changes, and observeRelayChanges() syncs to Python layer.
         */
        suspend fun setManualRelay(destinationHash: String) {
            Log.i(TAG, "User manually selected relay: ${destinationHash.take(16)}")

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
         * Immediately picks the best available relay.
         */
        suspend fun enableAutoSelect() {
            Log.i(TAG, "Enabling auto-select for propagation node")

            settingsRepository.saveAutoSelectPropagationNode(true)
            settingsRepository.saveManualPropagationNode(null)

            selectBestRelay()
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
         * Manually set a propagation node by destination hash only.
         * Used when user enters a hash directly without having received an announce.
         * Creates a contact if needed and sets as relay.
         *
         * @param destinationHash 32-character hex destination hash (already validated)
         * @param nickname Optional display name for this relay
         */
        suspend fun setManualRelayByHash(
            destinationHash: String,
            nickname: String?,
        ) {
            Log.i(TAG, "User manually entered relay hash: $destinationHash")

            // Disable auto-select and save manual selection
            settingsRepository.saveAutoSelectPropagationNode(false)
            settingsRepository.saveManualPropagationNode(destinationHash)

            // Add contact if it doesn't exist
            // addPendingContact handles both cases:
            // - If announce exists, creates full contact with public key
            // - If no announce, creates pending contact
            if (!contactRepository.hasContact(destinationHash)) {
                val result = contactRepository.addPendingContact(destinationHash, nickname)
                result
                    .onSuccess { addResult ->
                        Log.d(TAG, "Added contact for manual relay: $addResult")
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to add contact for manual relay: ${error.message}")
                    }
            }

            // This updates the database, which triggers currentRelay Flow,
            // which triggers observeRelayChanges() to sync Python layer
            contactRepository.setAsMyRelay(destinationHash, clearOther = true)
        }

        /**
         * Called when the current relay contact is deleted by the user.
         * Optionally triggers auto-selection of a new relay.
         *
         * @param autoSelectNew If true, auto-select a new relay after deletion
         * @param excludeHash Optional hash to exclude from auto-selection (e.g., just-deleted relay)
         */
        suspend fun onRelayDeleted(
            autoSelectNew: Boolean,
            excludeHash: String? = null,
        ) {
            Log.i(TAG, "Relay deleted, autoSelectNew=$autoSelectNew")
            settingsRepository.saveManualPropagationNode(null)

            if (autoSelectNew) {
                settingsRepository.saveAutoSelectPropagationNode(true)
                selectBestRelay(excludeHash = excludeHash)
            } else {
                // Disable auto-select so periodic sync doesn't re-select a relay,
                // which would contradict the user's "Remove Only" intent
                settingsRepository.saveAutoSelectPropagationNode(false)
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
            val availableNodes =
                propagationNodes.filter { node ->
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
         * Convert hex string to ByteArray.
         */
        private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
         * Sync messages from the propagation node (periodic/automatic sync).
         * This requests any waiting messages from the configured propagation node.
         *
         * If no relay is configured and auto-select is enabled, tries to select one first.
         * This handles the fresh-install case where announces arrive after startup.
         *
         * The actual completion is handled by observePropagationStateChanges() which
         * monitors LXMF propagation states.
         */
        suspend fun syncWithPropagationNode() {
            // Don't sync if network is not ready (e.g., during shutdown)
            if (reticulumProtocol.networkStatus.value !is NetworkStatus.READY) {
                Log.d(TAG, "Network not ready, skipping sync")
                return
            }

            val relay = currentRelay.value
            if (relay == null) {
                // Try auto-select if enabled (handles fresh install where announces arrived after startup)
                val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
                if (isAutoSelect) {
                    selectBestRelay()
                }
                return // next sync cycle will use the newly selected relay
            }

            // Don't start a new sync if one is already in progress
            if (_isSyncing.value) {
                Log.d(TAG, "Sync already in progress, skipping")
                return
            }

            Log.d(TAG, "📡 Periodic sync with propagation node: ${relay.destinationHash.take(16)}")
            // Cancel any still-alive poll loop from a previous sync so it can't wake up
            // mid-delay and start observing this sync's _isSyncing flag.
            activePollJob?.cancel()
            activePollJob = null
            syncFinalized.set(false)
            _isSyncing.value = true
            _syncProgress.value = SyncProgress.Starting

            // Start timeout watchdog to prevent indefinite sync state
            val timeoutJob =
                scope.launch {
                    kotlinx.coroutines.delay(syncTimeoutMs)
                    if (_isSyncing.value) {
                        Log.w(TAG, "Sync timed out after ${syncTimeoutMs / 1000} seconds")
                        // Finalize before clearing _isSyncing so an orphaned pollForSyncCompletion
                        // loop can't race the next startSync and prematurely finish it.
                        syncFinalized.set(true)
                        _isSyncing.value = false
                        _syncProgress.value = SyncProgress.Idle
                    }
                }

            try {
                val result = reticulumProtocol.requestMessagesFromPropagationNode()
                result
                    .onSuccess { state ->
                        Log.d(TAG, "Periodic sync initiated: state=${state.stateName}")
                        when (state.stateName) {
                            "complete" -> {
                                timeoutJob.cancel()
                                // Run completion bookkeeping so _lastSyncTimestamp is updated;
                                // skipping this leaves the Settings "Last synced" display stale
                                // forever whenever the initial response is already complete.
                                handleSyncComplete(state.messagesReceived)
                            }
                            "failed" -> {
                                timeoutJob.cancel()
                                // Match the finalization discipline used everywhere else so a stale
                                // propagationStateFlow "complete" can't later pass the CAS in
                                // handleSyncComplete and consume the next sync's finalizer.
                                syncFinalized.set(true)
                                _isSyncing.value = false
                                _syncProgress.value = SyncProgress.Idle
                            }
                            else -> {
                                // Poll for completion (propagationStateFlow doesn't work on native path)
                                activePollJob =
                                    scope.launch {
                                        pollForSyncCompletion(timeoutJob)
                                    }
                            }
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Periodic sync request failed: ${error.message}")
                        timeoutJob.cancel()
                        syncFinalized.set(true)
                        _isSyncing.value = false
                        _syncProgress.value = SyncProgress.Idle
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting messages from propagation node", e)
                timeoutJob.cancel()
                syncFinalized.set(true)
                _isSyncing.value = false
                _syncProgress.value = SyncProgress.Idle
            }
            // Note: _isSyncing is not reset on success - propagation state observer handles it
        }

        /**
         * Manually trigger a sync with the propagation node.
         * Useful for pull-to-refresh or when user explicitly wants to check for messages.
         * Emits result to manualSyncResult SharedFlow for UI notification (unless silent).
         *
         * The actual completion is handled by observePropagationStateChanges() which
         * monitors LXMF propagation states and emits success/error when complete.
         *
         * @param silent If true, does not emit to manualSyncResult (no toast shown)
         * @param keepSyncingState If true, does not reset isSyncing to false when done
         *        (caller is responsible for calling setSyncingState(false) later)
         */
        suspend fun triggerSync(
            silent: Boolean = false,
            keepSyncingState: Boolean = false,
        ) {
            val relay = awaitRelayOrEmitError(silent) ?: return

            if (_isSyncing.value) {
                Log.d(TAG, "Sync already in progress, skipping manual trigger")
                return
            }

            Log.d(TAG, "📡 Manual sync with propagation node: ${relay.destinationHash.take(16)} (silent=$silent)")
            // Cancel any still-alive poll loop from a previous sync so it can't wake up
            // mid-delay and start observing this sync's _isSyncing flag.
            activePollJob?.cancel()
            activePollJob = null
            syncFinalized.set(false)
            _isSyncing.value = true
            _isManualSync = !silent
            _syncProgress.value = SyncProgress.Starting

            val timeoutJob = launchSyncTimeoutWatchdog()

            try {
                val result = reticulumProtocol.requestMessagesFromPropagationNode()
                result
                    .onSuccess { propState ->
                        handleSyncRequestSuccess(propState, timeoutJob, keepSyncingState)
                    }.onFailure { error ->
                        Log.w(TAG, "Manual sync request failed: ${error.message}")
                        resetSyncOnFailure(timeoutJob, keepSyncingState, error.message ?: "Unknown error")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error during manual sync", e)
                resetSyncOnFailure(timeoutJob, keepSyncingState, e.message ?: "Unknown error")
            }
        }

        private suspend fun awaitRelayOrEmitError(silent: Boolean): RelayInfo? {
            val state =
                withContext(defaultDispatcher) {
                    withTimeoutOrNull(10_000L) {
                        currentRelayState.first { it is RelayLoadState.Loaded }
                    }
                }
            if (state == null) {
                Log.w(TAG, "Timed out waiting for relay state to load from database")
                if (!silent) _manualSyncResult.emit(SyncResult.Error("Relay configuration not available yet"))
                return null
            }
            val relay = (state as RelayLoadState.Loaded).relay
            if (relay == null) {
                Log.d(TAG, "No relay configured, cannot sync")
                if (!silent) _manualSyncResult.emit(SyncResult.NoRelay)
            }
            return relay
        }

        private fun launchSyncTimeoutWatchdog(): Job =
            scope.launch {
                delay(syncTimeoutMs)
                if (_isSyncing.value) {
                    Log.w(TAG, "Sync timed out after ${syncTimeoutMs / 1000} seconds")
                    // Finalize before clearing _isSyncing so an orphaned pollForSyncCompletion
                    // loop can't race the next startSync, observe the new sync's _isSyncing=true,
                    // call handleSyncComplete, and prematurely finish the new sync.
                    syncFinalized.set(true)
                    _isSyncing.value = false
                    _syncProgress.value = SyncProgress.Idle
                    if (_isManualSync) {
                        _manualSyncResult.emit(SyncResult.Timeout)
                        _isManualSync = false
                    }
                }
            }

        private suspend fun handleSyncRequestSuccess(
            propState: PropagationState,
            timeoutJob: Job,
            keepSyncingState: Boolean,
        ) {
            Log.d(TAG, "Manual sync initiated: state=${propState.stateName}")
            when (propState.stateName) {
                "complete" -> {
                    timeoutJob.cancel()
                    // Run completion bookkeeping (updates _lastSyncTimestamp, emits success
                    // to _manualSyncResult when appropriate). Clearing _isSyncing without
                    // calling this left the "Last synced" display stale on instant-complete.
                    handleSyncComplete(propState.messagesReceived)
                }
                "failed" -> {
                    timeoutJob.cancel()
                    // Match the finalization discipline used everywhere else so a stale
                    // propagationStateFlow "complete" can't later pass the CAS in
                    // handleSyncComplete and consume the next sync's finalizer.
                    syncFinalized.set(true)
                    if (!keepSyncingState) {
                        _isSyncing.value = false
                        _syncProgress.value = SyncProgress.Idle
                    }
                    if (_isManualSync) {
                        _manualSyncResult.emit(SyncResult.Error("Propagation node not reachable"))
                        _isManualSync = false
                    }
                }
                else -> {
                    activePollJob =
                        scope.launch {
                            pollForSyncCompletion(timeoutJob)
                        }
                }
            }
        }

        private suspend fun resetSyncOnFailure(
            timeoutJob: Job,
            keepSyncingState: Boolean,
            errorMessage: String,
        ) {
            timeoutJob.cancel()
            syncFinalized.set(true)
            if (!keepSyncingState) {
                _isSyncing.value = false
                _syncProgress.value = SyncProgress.Idle
            }
            if (_isManualSync) {
                _manualSyncResult.emit(SyncResult.Error(errorMessage))
                _isManualSync = false
            }
        }

        /**
         * Manually set the syncing state.
         * Used by callers that use keepSyncingState=true to reset the state when done.
         */
        fun setSyncingState(syncing: Boolean) {
            _isSyncing.value = syncing
        }
    }
