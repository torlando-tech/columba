package network.columba.app.service

import android.util.Log
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.PreferredPropagationNodeRepository
import network.columba.app.di.ApplicationScope
import network.columba.app.di.DefaultDispatcher
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.rns.host.manager.PreferredNode
import network.columba.app.rns.host.manager.PreferredNodePolicy
import network.columba.app.rns.host.manager.PropagationNodeSelection
import network.columba.app.service.manager.InterfaceTransportObserver
import network.columba.app.util.HexUtils.toHexString
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.flow
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
    @Suppress("LongParameterList") // Hilt DI: one injected collaborator per parameter; time-based logic is clock-injected for testability.
    constructor(
        private val settingsRepository: SettingsRepository,
        private val contactRepository: ContactRepository,
        private val announceRepository: AnnounceRepository,
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        private val preferredPropagationNodeRepository: PreferredPropagationNodeRepository,
        private val transportObserver: InterfaceTransportObserver,
        @ApplicationScope private val scope: CoroutineScope,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
        private val timeProvider: Clock = Clock.systemUTC(),
    ) {
        companion object {
            private const val TAG = "PropagationNodeManager"
        }

        /** Epoch millis now, via the injectable clock (defaults to real time). */
        private fun nowMs(): Long = timeProvider.millis()

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
         * Set of destination hashes currently in the preferred propagation-nodes list.
         * Used by the Node Details screen to show "In priority list" vs "Add to priority
         * list" for a relay, so the add affordance reflects the live list state.
         *
         * Built with the lazy [flow] builder so that `observeOrdered()` is only invoked when
         * the flow is actually collected (by the Node Details screen), not at manager
         * construction. An eager `stateIn` — or even an eagerly-built pipeline — would call
         * `observeOrdered()` while constructing the manager, which breaks unit tests that
         * build the manager with a minimal repository mock.
         */
        val preferredRelayHashes: Flow<Set<String>> = flow {
            preferredPropagationNodeRepository
                .observeOrdered()
                .map { nodes -> nodes.map { it.destinationHash }.toSet() }
                .collect { emit(it) }
        }

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

        /**
         * How long a preferred relay that LXMF just reported unreachable (no path / no link /
         * failed) stays excluded from the selection policy before it is tried again. This is
         * the loop-avoidance: a down relay is not retried every sync cycle, but is re-attempted
         * once per cooldown (self-healing) — and immediately if a sync to it later succeeds
         * ([clearRelayFailure]).
         */
        private val unreachableCooldownMs = 10 * 60 * 1000L

        private var syncJob: Job? = null
        private var settingsObserverJob: Job? = null
        private var propagationStateObserverJob: Job? = null

        private var relayObserverJob: Job? = null

        private var preferredObserverJob: Job? = null

        // The startup auto-select one-shot. Captured so stop() cancels it — it has
        // suspension points (getAutoSelectPropagationNode / count / currentRelayState.first)
        // where a stale instance could otherwise outlive the manager and fire on the next
        // test's cleared mocks.
        private var startupSelectJob: Job? = null

        // True while the preferred-list writer is authoritative (the enabled list was
        // non-empty). Used to detect the non-empty → empty transition: when the user
        // removes the last preferred node, the writer must actively re-resolve to the
        // legacy nearest relay rather than leaving the (now removed) node active — the
        // startup one-shot already ran and the periodic auto-select only fires when
        // currentRelay is null, so without this the removed node would stay selected.
        private var preferredListActive = false

        /**
         * The destination hash of the relay that *started* the in-flight sync, captured at
         * [executeSync] start. The sync's failure/success terminals must record/clear that
         * relay's state, not whatever [currentRelay] happens to be when the terminal fires:
         * the recheck timer (or a transport flip) can switch the active relay mid-sync, in
         * which case keying off [currentRelay] would attribute B's failure to A. Null when no
         * sync is in flight.
         */
        private var activeSyncRelayHash: String? = null

        /**
         * The operations to re-drive automatically once the writer fails over to a *different*
         * relay after a relay-unreachable failure. Each entry is the relay it failed on
         * (so a stale retry — where the failed relay is re-selected — is dropped rather than
         * re-driven against the relay that just failed) paired with a closure that re-runs the
         * failed action: a sync re-runs [triggerSync], a propagation-method send re-drives its
         * captured message.
         *
         * A *queue*, not a single slot: if two propagation sends fail (or a send and a sync
         * fail) before the replacement relay is pushed, both retries must survive — a single
         * slot would let the second failure overwrite the first, and only the last would ever
         * be re-driven. [observeRelayChanges] drains this on a relay change: every entry whose
         * failed relay is no longer active is re-driven, and entries whose failed relay came
         * back are dropped. Bounded: each relay is excluded by the 10-minute cooldown, so a
         * failing action walks the list at most once rather than hammering a dead relay.
         *
         * Synchronized because a `recordUnreachableOn` DB write emits on the preferred-list
         * observer, which can drive [firePendingRelayRetryIfDue] from another coroutine before
         * the recording coroutine resumes — so entries can be added and drained concurrently.
         * Guarded by [retryQueueLock] (a dedicated lock, since add and drain happen on
         * different coroutines).
         */
        private val retryQueueLock = Any()
        private val pendingRelayRetries = mutableListOf<Pair<String, suspend () -> Unit>>()

        /**
         * Recheck token for the preferred-list writer. The writer's `combine(enabled,
         * transport, recheck)` re-evaluates when the saved list, the transport, OR this token
         * changes. A relay marked unreachable (and thus skipped) recovers when its failure's
         * cooldown elapses — but that writes nothing to the DB, so neither the list nor the
         * transport changes and the writer would never re-evaluate, leaving the user on a
         * lower-priority relay forever. [schedulePreferredRelayRecheck] bumps this token
         * (a new value each time, so [distinctUntilChanged] lets it through) exactly when the
         * soonest-excluded relay's cooldown is about to expire; the writer then re-evaluates
         * with the current list + transport and re-picks the recovered relay.
         *
         * [kotlinx.coroutines.flow.MutableStateFlow] (not a SharedFlow) so [combine] receives
         * an initial value at subscription and the writer emits its first triple immediately.
         */
        private val preferredRelayRecheck = MutableStateFlow(0L)

        private var recheckJob: Job? = null

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

            // Observe preferred-node list + transport, resolving the active relay (Ticket 03).
            // Active only when the list is non-empty; empty list leaves legacy auto in charge.
            preferredObserverJob =
                scope.launch {
                    observePreferredNodes()
                }

            // Start periodic sync with propagation node
            startPeriodicSync()

            // One-shot auto-select on startup (legacy path). Skipped when the preferred list
            // is non-empty — the writer above owns relay selection in ordered mode, so this
            // would fight it. Empty list = legacy behavior byte-for-byte unchanged.
            startupSelectJob =
                scope.launch {
                    val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
                    val listEmpty = preferredPropagationNodeRepository.count() == 0
                    val loaded = currentRelayState.first { it is RelayLoadState.Loaded } as RelayLoadState.Loaded
                    if (isAutoSelect && listEmpty && loaded.relay == null) {
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
                    // Once the new relay is active in the Python layer, re-drive the operation
                    // that failed against the previous (now-unreachable) relay, if there is
                    // one pending. No-op when the relay did not change away from the failed
                    // relay or no retry is pending.
                    firePendingRelayRetryIfDue(destinationHash)
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
                        rnsLxmf.setOutboundPropagationNode(destHashBytes)
                    } else {
                        rnsLxmf.setOutboundPropagationNode(null)
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
         * Observe propagation state changes from the LXMF seam.
         * Updates syncProgress flow for UI and handles completion/error states.
         */
        private suspend fun observePropagationStateChanges() {
            rnsLxmf.propagationStateFlow.collect { state ->
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

                val state = rnsLxmf.getPropagationState().getOrNull()
                if (state != null) {
                    _syncProgress.value = SyncProgress.InProgress(state.stateName, state.progress)
                    if (handlePollResult(state, timeoutJob)) return
                }
            }
        }

        private suspend fun handlePollResult(
            state: network.columba.app.rns.api.model.PropagationState,
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
                    // Record *why* the relay was unreachable so the writer fails over to the
                    // next in the list (no-op when the relay is not a preferred node), and
                    // retry this sync on the new relay automatically.
                    recordSyncFailureAndScheduleRetry(state)
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
            // Capture the relay that started this sync *before* we open the sync gate below
            // (_isSyncing = false). Once the gate is open a concurrent executeSync may start and
            // overwrite the shared activeSyncRelayHash var, and the saveLastSyncTimestamp write
            // suspends in the meantime — so the recovery clear must key off this local, not the
            // live var, or it could clear a different relay's recorded failure.
            val activeHash = activeSyncRelayHash
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

                // A successful sync to the active relay is proof it is reachable again — clear
                // any recorded failure so a previously-failing relay is re-eligible immediately
                // instead of waiting out the unreachable cooldown. Keyed off the hash captured at
                // the top of this handler (not the live var), so a mid-sync relay switch or a
                // concurrent sync can't make us clear a different relay's failure. No-op for
                // non-preferred relays.
                clearRelayFailureOnSuccess(activeHash)

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

                // If the active relay is a *preferred* node and LXMF reported it unreachable,
                // record *why* (code + explanation + timestamp) so the relay writer can fail
                // over to the next in the list, and retry this sync on the new relay
                // automatically. This reuses an event LXMF already emitted — no new network
                // activity.
                recordSyncFailureAndScheduleRetry(state)

                // Emit error for UI only if manually triggered
                if (_isManualSync) {
                    _manualSyncResult.emit(SyncResult.Error(errorMsg, state.state))
                    _isManualSync = false
                }
            }
        }

        /**
         * Record the unreachable relay for a *sync* failure and register a retry of that sync
         * to be re-driven once the writer fails over to a different relay.
         *
         * The retry is registered only when the failure would actually be recorded (the active
         * relay is in the preferred list), so a non-preferred/legacy relay neither records nor
         * retries. The retry re-runs [triggerSync] silently — the user already saw the manual
         * sync's failure toast (if any); the auto-retry should not surface a second toast. The
         * retry fires only when the active relay has changed away from the one that failed
         * (see [firePendingRelayRetryIfDue]).
         */
        private suspend fun recordSyncFailureAndScheduleRetry(state: PropagationState) {
            val activeHash = activeSyncRelayHash ?: return
            // Only retry (and only record) for an *unreachable* relay failure — a generic
            // "failed" that is not a no-path/no-link condition is not a reason to fail over.
            if (!PropagationFailureReason.isUnreachable(state)) return
            if (!preferredPropagationNodeRepository.contains(activeHash)) return
            // Register the retry *before* the DB write: recordUnreachableOn's write can emit on
            // the preferred-list observer and drive firePendingRelayRetryIfDue from another
            // coroutine before this one resumes, so the action must already be queued. Build the
            // suspend lambda outside the critical section, then enqueue it.
            val retry: suspend () -> Unit = { triggerSync(silent = true) }
            synchronized(retryQueueLock) {
                pendingRelayRetries.add(activeHash to retry)
            }
            recordUnreachableOn(activeHash, state)
        }

        /**
         * Record [state] as an unreachable failure on [destinationHash]. Shared by the sync
         * terminals (which key off [activeSyncRelayHash]) and [reportRelayUnreachable] (which
         * keys off whatever relay the caller — a send or a sync — was using). No-op when the
         * relay is not in the preferred list.
         */
        private suspend fun recordUnreachableOn(destinationHash: String, state: PropagationState) {
            if (!PropagationFailureReason.isUnreachable(state)) return
            if (!preferredPropagationNodeRepository.contains(destinationHash)) return
            val reason = PropagationFailureReason.explanation(state)
            Log.i(TAG, "Recording unreachable relay $destinationHash: $reason (code ${state.state})")
            preferredPropagationNodeRepository.recordFailure(
                destinationHash = destinationHash,
                code = state.state,
                explanation = reason,
                timestamp = nowMs(),
            )
        }

        /**
         * Clear a preferred relay's recorded failure after a successful sync to it, so it is
         * re-eligible for selection immediately rather than waiting out the cooldown. No-op for
         * a non-preferred relay.
         *
         * @param destinationHash The relay that started the (now-completed) sync, captured by the
         *   caller before the sync gate opened — so a mid-sync relay switch or a concurrent sync
         *   can't redirect the clear to a different relay.
         */
        private suspend fun clearRelayFailureOnSuccess(destinationHash: String?) {
            val activeHash = destinationHash ?: return
            if (!preferredPropagationNodeRepository.contains(activeHash)) return
            Log.d(TAG, "Clearing recorded failure for recovered relay $activeHash")
            preferredPropagationNodeRepository.clearFailure(activeHash)
        }

        /**
         * Mark the active relay unreachable (recording *why*) and register [retryAction] to be
         * re-driven automatically once the writer fails over to a *different* relay.
         *
         * This is the single entry point for "an operation against the active relay failed
         * because the relay is unreachable, so record the failure, fail over, and retry the
         * operation on the next relay." Both a propagation-method *send* failure (surfaced via
         * the delivery-status callback) and a *sync* failure route here. The DB write drives the
         * immediate failover (the writer re-evaluates on the live `observeOrdered` emission and
         * skips the now-unreachable relay); [retryAction] is fired by [observeRelayChanges] the
         * first time the active relay becomes a relay other than the one that failed.
         *
         * [retryAction] is a closure that re-runs the failed action against whatever relay is
         * active at fire time — a sync re-runs [triggerSync], a send re-drives its captured
         * message. It is enqueued (not replaced) so multiple failed operations survive until the
         * relay changes, and fired only when the active relay actually changed away from the
         * relay each failed against, so a retry never re-runs against the very relay that just
         * failed.
         *
         * [destinationHash] is the relay the operation was actually using. It is required for a
         * *send* because a send must NOT be attributed to [activeSyncRelayHash] — that variable
         * is sync-specific and lingers from the last sync, so after "sync on A, switch to B,
         * send on B fails" it would wrongly blame A. The send path passes the relay it was
         * routing through (the active relay at failure time); a null falls back to the current
         * relay. No-op (no record, no retry) when there is no relay or it is not in the
         * preferred list — a legacy auto-selected relay has no preferred row to record on.
         */
        suspend fun reportRelayUnreachable(
            state: PropagationState,
            retryAction: suspend () -> Unit,
            destinationHash: String? = null,
        ) {
            // A send is attributed to the relay it was routing through (the caller's
            // destinationHash, else the current relay) — never to the stale sync variable.
            val activeHash = destinationHash ?: currentRelay.value?.destinationHash ?: return
            // Only fail over (and only record) for an *unreachable* relay failure.
            if (!PropagationFailureReason.isUnreachable(state)) return
            if (!preferredPropagationNodeRepository.contains(activeHash)) return
            // Enqueue the retry *before* the DB write: recordUnreachableOn's write can emit on
            // the preferred-list observer and drive firePendingRelayRetryIfDue from another
            // coroutine before this one resumes, so the action must already be queued (and it
            // must not displace any earlier failure's retry — this is a queue).
            synchronized(retryQueueLock) {
                pendingRelayRetries.add(activeHash to retryAction)
            }
            recordUnreachableOn(activeHash, state)
            Log.i(
                TAG,
                "Relay $activeHash unreachable (${state.stateName}); scheduling retry of the failed action on the next relay",
            )
        }

        /**
         * Re-drive the queued retries whose failed relay is no longer the active one. Called
         * from [observeRelayChanges] after a relay change. For each pending entry, if the
         * relay it failed on is no longer active, re-drive it; if the same (recovered) relay
         * came back, drop the entry rather than re-driving against the relay that just failed.
         * A transient null is the *middle* of a failover: setAsMyRelay(clearOther = true)
         * clears the old relay before setting the new one, so currentRelay emits null between
         * the failed relay and the replacement — hold the queue through the null; the real
         * next relay arrives right after and fires them.
         */
        private fun firePendingRelayRetryIfDue(newRelayHash: String?) {
            // Hold through a transient null (mid-failover); nothing to fire yet.
            if (newRelayHash == null) return
            // Snapshot + clear under the lock so a concurrent add (a new failure recorded while
            // we drain) is not lost: it stays queued for the next relay change.
            val toFire: List<Pair<String, suspend () -> Unit>>
            synchronized(retryQueueLock) {
                toFire = pendingRelayRetries.filter { (failedHash, _) -> failedHash != newRelayHash }
                pendingRelayRetries.removeAll { (failedHash, _) -> failedHash != newRelayHash }
            }
            toFire.forEach { (failedHash, action) ->
                Log.i(TAG, "Relay changed to $newRelayHash (was $failedHash) — retrying the previously failed action")
                scope.launch {
                    try {
                        action()
                    } catch (e: Exception) {
                        Log.w(TAG, "Pending relay retry failed: ${e.message}", e)
                    }
                }
            }
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping PropagationNodeManager")
            relayObserverJob?.cancel()
            preferredObserverJob?.cancel()
            recheckJob?.cancel()
            recheckJob = null
            syncJob?.cancel()
            settingsObserverJob?.cancel()
            propagationStateObserverJob?.cancel()
            startupSelectJob?.cancel()
            relayObserverJob = null
            preferredObserverJob = null
            syncJob = null
            settingsObserverJob = null
            propagationStateObserverJob = null
            startupSelectJob = null
            // Drop any pending retries so a stale operation is not re-driven after a stop.
            synchronized(retryQueueLock) {
                pendingRelayRetries.clear()
            }
        }

        /**
         * Preferred-list writer. Combines the ordered preferred-node list with the device's
         * current transport and runs the pure [PreferredNodePolicy] ladder. When the policy
         * resolves to a list node, the contact is ensured to exist and marked as the active
         * relay (`isMyRelay`); when it falls through to [PropagationNodeSelection.LegacyAuto],
         * the existing nearest-by-hops auto-selection is used.
         *
         * This is the *only* thing that decides who `isMyRelay` points to when the preferred
         * list is non-empty. The rest of the pipeline (`currentRelay` →
         * `observeRelayChanges` → `syncRelayToPython`) is reused unchanged. When the list is
         * empty the writer stays idle and the legacy startup one-shot + periodic auto-select
         * keep full ownership, exactly as before.
         *
         * The `distinctUntilChanged` on the resolved hash means a transport blip that
         * resolves back to the same node does not re-push `setOutboundPropagationNode`.
         */
        private suspend fun observePreferredNodes() {
            preferredPropagationNodeRepository
                .observeEnabled()
                .combine(transportObserver.currentTransport) { enabled, transport -> enabled to transport }
                // Dedupe on the (list, transport) pair so a redundant re-emission of an
                // unchanged pair does not re-run the policy.
                .distinctUntilChanged()
                // The recheck token is the third source, combined AFTER the dedup on purpose:
                // a token bump (a recovered relay's cooldown about to expire) re-emits the
                // current pair even though the list and transport have not changed, which
                // forces a re-evaluation that the dedup above would otherwise swallow.
                .combine(preferredRelayRecheck) { enabledTransport, _ -> enabledTransport }
                .collect { (enabled, transport) ->
                    if (enabled.isEmpty()) {
                        // Empty list = legacy mode: the startup one-shot + periodic
                        // auto-select own the relay. But if the list *just* became empty
                        // (the user removed the last preferred node), actively re-resolve
                        // to the legacy nearest relay now — otherwise the node that was
                        // active under ordered mode would stay selected, because the
                        // startup one-shot already ran and the periodic auto-select only
                        // fires when currentRelay is null.
                        recheckJob?.cancel()
                        recheckJob = null
                        if (preferredListActive) {
                            Log.i(TAG, "Preferred list emptied — re-resolving to legacy auto-select")
                            selectBestRelay()
                        }
                        preferredListActive = false
                        return@collect
                    }
                    preferredListActive = true
                    // Exclude preferred relays LXMF recently reported unreachable (no path / no
                    // link / failed) so a down relay fails over to the next in the list. The
                    // set is derived purely from each node's most-recent recorded failure that
                    // is still inside the cooldown window — no new network activity.
                    val now = nowMs()
                    val excluded =
                        enabled.filter {
                            preferredPropagationNodeRepository.isCurrentlyUnreachable(it, now, unreachableCooldownMs)
                        }
                    val unreachableHashes = excluded.map { it.destinationHash }.toSet()
                    if (unreachableHashes.isNotEmpty()) {
                        Log.i(TAG, "Preferred relay(s) currently unreachable, failing over: $unreachableHashes")
                    }
                    // A relay that recovers only when its failure's cooldown ages out writes
                    // nothing to the DB, so the combine above would never re-fire and the user
                    // would stay on a lower-priority relay forever. Schedule a re-evaluation for
                    // the moment the soonest-excluded relay's cooldown expires.
                    schedulePreferredRelayRecheck(now, excluded)
                    val selection = PreferredNodePolicy.resolve(
                        enabled.map { it.toPreferredNode() },
                        transport,
                        unreachableHashes,
                    )
                    when (selection) {
                        is PropagationNodeSelection.FromList -> applyPreferredRelay(selection.destinationHash)
                        // Non-empty list but nothing matched the transport (or all are
                        // unreachable) → legacy nearest. Pass the excluded set through so the
                        // nearest-by-hops fallback can't immediately re-pick the relay we just
                        // marked unreachable (that would bypass the cooldown).
                        PropagationNodeSelection.LegacyAuto -> selectBestRelay(excludeHashes = unreachableHashes)
                    }
                }
        }

        /**
         * Schedule a single writer recheck for when the soonest-excluded preferred relay's
         * failure cooldown is about to expire, so a recovered relay is retried. The writer's
         * [observePreferredNodes] combine re-fires only when the list, transport, or the
         * recheck token changes; a failure aging out changes none of the first two (no DB
         * write), so without this the user stays on a lower-priority relay indefinitely.
         *
         * Cancels any in-flight schedule first, so only the soonest pending expiry is kept.
         * No-op when nothing is currently excluded (nothing to retry).
         */
        private fun schedulePreferredRelayRecheck(nowMs: Long, excluded: List<PreferredPropagationNodeEntity>) {
            recheckJob?.cancel()
            recheckJob = null
            // Re-eligible one millisecond past the (half-open) cooldown window, matching
            // [PreferredPropagationNodeRepository.isCurrentlyUnreachable]'s `0..cooldown` test.
            val soonestExpiry =
                excluded
                    .mapNotNull { it.lastFailureAt }
                    .minOrNull()
                    ?.let { it + unreachableCooldownMs + 1L }
                    ?: return
            val delayMs = soonestExpiry - nowMs
            if (delayMs <= 0L) {
                return // already at/past expiry — the current evaluation already handled it
            }
            recheckJob =
                scope.launch {
                    kotlinx.coroutines.delay(delayMs)
                    // A new token value each time so the writer's distinctUntilChanged lets the
                    // re-evaluation through.
                    preferredRelayRecheck.value = soonestExpiry
                }
        }

        /** Map a stored preferred node to the policy's view (enum-restriction by name). */
        private fun PreferredPropagationNodeEntity.toPreferredNode(): PreferredNode =
            PreferredNode(
                destinationHash = destinationHash,
                restriction = networkRestriction.toNetworkRestriction(),
            )

        /**
         * Persist [destinationHash] as the active relay, creating the contact first if it
         * is not already a contact. The `setAsMyRelay(clearOther = true)` write is idempotent
         * and triggers `currentRelay` → `observeRelayChanges` → `syncRelayToPython`.
         */
        private suspend fun applyPreferredRelay(destinationHash: String) {
            if (!contactRepository.hasContact(destinationHash)) {
                val node = preferredPropagationNodeRepository.findByHash(destinationHash)
                val announce = announceRepository.getAnnounce(destinationHash)
                val nodePublicKey = node?.publicKey
                val result =
                    when {
                        announce != null ->
                            contactRepository.addContactFromAnnounce(destinationHash, announce.publicKey)
                        nodePublicKey != null ->
                            contactRepository.addContactFromAnnounce(
                                destinationHash,
                                nodePublicKey.hexToByteArray(),
                            )
                        else -> contactRepository.addPendingContact(destinationHash, node?.displayName)
                    }
                if (result.isFailure) {
                    Log.e(TAG, "Failed to create contact for preferred relay: ${result.exceptionOrNull()?.message}")
                    return
                }
            }
            // Idempotent — skips the write if this hash is already the active relay.
            contactRepository.setAsMyRelay(destinationHash, clearOther = true)
        }

        /**
         * One-shot: query DB for best available relay and set it.
         * Called on startup, when user enables auto-select, or after relay deletion.
         *
         * @param excludeHashes Optional hashes to exclude (e.g., just-deleted relay, or the
         *   relays the preferred-list writer just marked unreachable). The nearest-by-hops
         *   fallback must not re-pick a relay that is currently excluded.
         */
        private suspend fun selectBestRelay(excludeHashes: Set<String> = emptySet()) {
            val propagationNodes =
                announceRepository
                    .getAnnouncesByTypes(listOf("PROPAGATION_NODE"))
                    .first()

            val candidates =
                if (excludeHashes.isNotEmpty()) {
                    propagationNodes.filter { it.destinationHash !in excludeHashes }
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

            // Update contacts - add if needed and mark as relay.
            //
            // Create the contact from an announce when one is available, but fall back to a
            // PENDING contact when the node was added by raw hash with no announce. Without
            // this fallback, setting a hash-only node as active would clear the previous
            // relay (setAsMyRelay) while leaving no contact behind, i.e. no active relay at
            // all — and the relay observer would hex-decode a hash with nothing to back it.
            val announce = announceRepository.getAnnounce(destinationHash)
            if (!contactRepository.hasContact(destinationHash)) {
                if (announce != null) {
                    contactRepository.addContactFromAnnounce(destinationHash, announce.publicKey)
                } else {
                    contactRepository.addPendingContact(destinationHash, null)
                }
            }

            // This updates the database, which triggers currentRelay Flow,
            // which triggers observeRelayChanges() to sync Python layer
            contactRepository.setAsMyRelay(destinationHash, clearOther = true)
        }

        /**
         * Switch back to auto-selection mode.
         *
         * **Ordered mode (non-empty preferred list):** the transport-aware policy writer
         * already owns the active relay and re-resolves from the list + transport on every
         * emission. We must NOT bypass it with `selectBestRelay()` (nearest-by-hops) here —
         * that would replace a working transport-specific preferred node with an unrelated
         * nearest relay (and the writer would then only re-correct on the next list/transport
         * emission, causing a flap). Enabling auto-select in this mode simply restores the
         * writer's authority by leaving it in place.
         *
         * **Legacy mode (empty preferred list):** the writer is idle, so fall back to the
         * classic immediate nearest-by-hops selection.
         */
        suspend fun enableAutoSelect() {
            Log.i(TAG, "Enabling auto-select for propagation node")

            settingsRepository.saveAutoSelectPropagationNode(true)
            settingsRepository.saveManualPropagationNode(null)

            // The writer is authoritative iff the enabled preferred list is non-empty.
            val orderedMode = preferredPropagationNodeRepository.observeEnabled().first().isNotEmpty()
            if (orderedMode) {
                Log.i(TAG, "enableAutoSelect: ordered mode — policy writer owns the relay")
            } else {
                selectBestRelay()
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
         * User "set as relay" intent from the announces screen, contacts list, or the
         * settings card (ticket 06). Branches on the preferred list:
         *
         * - **Non-empty preferred list (ordered mode):** append the node to the list
         *   (denormalizing its public key + nickname for contact creation) and let the
         *   ticket-03 transport-aware policy writer resolve which node is active. We do
         *   NOT bypass the writer with a raw `setAsMyRelay` here, because when the list is
         *   non-empty the writer owns the active relay and a raw write would be reverted on
         *   the next transport emission (flap).
         * - **Empty list (legacy mode):** fall back to the legacy direct set so a first-time
         *   user who hasn't adopted the preferred list yet still gets immediate behavior.
         *
         * The `setActiveNow` action on the propagation-nodes screen uses the raw
         * hard-set path (`setManualRelay`), not this method.
         */
        suspend fun addPreferredRelay(destinationHash: String, nickname: String?) {
            // Ensure a contact exists (the relay observer / writer both expect a contact
            // to back the active relay), mirroring setManualRelay's contact handling.
            if (!contactRepository.hasContact(destinationHash)) {
                val announce = announceRepository.getAnnounce(destinationHash)
                if (announce != null) {
                    contactRepository.addContactFromAnnounce(destinationHash, announce.publicKey)
                } else {
                    contactRepository.addPendingContact(destinationHash, nickname)
                }
            }

            if (preferredPropagationNodeRepository.count() == 0) {
                // Legacy mode: no preferred list yet, so use the existing direct-set path.
                Log.i(TAG, "addPreferredRelay: empty list -> legacy direct set ${destinationHash.take(16)}")
                setManualRelay(destinationHash)
                return
            }

            // Ordered mode: append to the preferred list and let the policy writer resolve.
            Log.i(TAG, "addPreferredRelay: appending to preferred list ${destinationHash.take(16)}")
            val publicKeyHex =
                preferredPropagationNodeRepository
                    .findByHash(destinationHash)
                    ?.publicKey
                    ?: announceRepository.getAnnounce(destinationHash)?.publicKey?.toHexString()
            preferredPropagationNodeRepository.add(
                destinationHash = destinationHash,
                networkRestriction = NetworkRestriction.ANY.name,
                displayName = nickname,
                publicKey = publicKeyHex,
            )
        }

        /**
         * Unconditionally add a node to the preferred propagation-nodes (priority) list,
         * creating a backing contact first if needed. Unlike [addPreferredRelay], this never
         * falls back to the legacy direct-set path: a "Add to priority list" action (e.g. from
         * the Node Details screen) must always create a list row, even when the list is empty,
         * so the user's intent to build a priority list is preserved. Once the row is added the
         * transport-aware policy writer resolves the active relay from the (now non-empty)
         * list — auto-select is left untouched.
         */
        suspend fun addToPriorityList(destinationHash: String, nickname: String?) {
            if (preferredPropagationNodeRepository.contains(destinationHash)) {
                return
            }
            Log.i(TAG, "addToPriorityList: adding to preferred list ${destinationHash.take(16)}")
            if (!contactRepository.hasContact(destinationHash)) {
                val announce = announceRepository.getAnnounce(destinationHash)
                if (announce != null) {
                    contactRepository.addContactFromAnnounce(destinationHash, announce.publicKey)
                } else {
                    contactRepository.addPendingContact(destinationHash, nickname)
                }
            }
            val publicKeyHex =
                announceRepository.getAnnounce(destinationHash)?.publicKey?.toHexString()
            preferredPropagationNodeRepository.add(
                destinationHash = destinationHash,
                networkRestriction = NetworkRestriction.ANY.name,
                displayName = nickname,
                publicKey = publicKeyHex,
            )
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
                selectBestRelay(excludeHashes = excludeHash?.let { setOf(it) } ?: emptySet())
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

        /**
         * Map a stored `networkRestriction` string (the `NetworkRestriction` enum NAME) to the
         * enum, defaulting to [NetworkRestriction.ANY] for any unrecognized/legacy value so a
         * malformed row never drops the node from the policy.
         */
        private fun String.toNetworkRestriction(): NetworkRestriction =
            NetworkRestriction.entries.firstOrNull { it.name == this } ?: NetworkRestriction.ANY

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
         * Periodic/automatic sync trigger. Runs the shared [executeSync] engine with the
         * periodic profile: it is gated on RNS being READY (the periodic path may fire during
         * shutdown) and resolves the relay from the current value rather than blocking on it.
         * If no relay is configured and auto-select is enabled it selects one first (the
         * fresh-install case) and defers to the next cycle.
         *
         * The completion/error bookkeeping lives entirely in the engine, so the periodic and
         * manual triggers share one code path (see [executeSync]).
         */
        suspend fun syncWithPropagationNode() {
            // Don't sync if network is not ready (e.g., during shutdown)
            if (rnsCore.networkStatus.value !is NetworkStatus.READY) {
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

            Log.d(TAG, "📡 Periodic sync with propagation node: ${relay.destinationHash.take(16)}")
            executeSync(manual = false)
        }

        /**
         * Manual sync trigger (pull-to-refresh / explicit user action). Runs the shared
         * [executeSync] engine with the manual profile: it waits (up to a short timeout) for
         * the relay state to load, and emits a [SyncResult] to [manualSyncResult] for UI
         * notification unless [silent].
         *
         * @param silent If true, does not emit to manualSyncResult (no toast shown)
         */
        suspend fun triggerSync(silent: Boolean = false) {
            val relay = awaitRelayOrEmitError(silent) ?: return
            Log.d(TAG, "📡 Manual sync with propagation node: ${relay.destinationHash.take(16)} (silent=$silent)")
            executeSync(manual = !silent)
        }

        /**
         * The single sync engine shared by both the periodic ([syncWithPropagationNode]) and
         * manual ([triggerSync]) triggers. The two triggers only differ in *how* they resolve
         * the relay and whether a UI [SyncResult] is emitted — the request, timeout watchdog,
         * and every completion/error bookkeeping path (including the unreachable-relay failure
         * record and the recovery clear) live here exactly once.
         *
         * @param manual When true, [PropagationNodeManager._isManualSync] is set for this sync so
         *   the terminal handlers emit a UI result; when false (periodic), no result is emitted
         *   and completion is surfaced only through the progress/last-sync state.
         */
        private suspend fun executeSync(manual: Boolean) {
            if (_isSyncing.value) {
                Log.d(TAG, "Sync already in progress, skipping")
                return
            }

            // Cancel any still-alive poll loop from a previous sync so it can't wake up
            // mid-delay and start observing this sync's _isSyncing flag.
            activePollJob?.cancel()
            activePollJob = null
            syncFinalized.set(false)
            _isSyncing.value = true
            _isManualSync = manual
            _syncProgress.value = SyncProgress.Starting
            // Capture the relay that starts this sync. The failure/success terminals key off
            // this (not currentRelay, which can be switched mid-sync by the recheck timer or a
            // transport flip) so a failure is attributed to the relay that actually synced.
            activeSyncRelayHash = currentRelay.value?.destinationHash

            val timeoutJob = launchSyncTimeoutWatchdog()

            try {
                val result = rnsLxmf.requestMessagesFromPropagationNode()
                result
                    .onSuccess { propState ->
                        handleSyncRequestSuccess(propState, timeoutJob)
                    }.onFailure { error ->
                        Log.w(TAG, "Sync request failed: ${error.message}")
                        resetSyncOnFailure(timeoutJob, error.message ?: "Unknown error")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
                resetSyncOnFailure(timeoutJob, e.message ?: "Unknown error")
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
                    // loop can't race the next sync, observe the new sync's _isSyncing=true,
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
        ) {
            Log.d(TAG, "Sync initiated: state=${propState.stateName}")
            when (propState.stateName) {
                "complete" -> {
                    timeoutJob.cancel()
                    // Run completion bookkeeping (updates _lastSyncTimestamp, clears a
                    // recorded failure so a recovered relay is re-eligible, and emits success
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
                    _isSyncing.value = false
                    _syncProgress.value = SyncProgress.Idle
                    // Record *why* the relay was unreachable so the writer fails over to the
                    // next in the list (no-op when the relay is not a preferred node), and
                    // retry this sync on the new relay automatically — the same path as the
                    // polled terminal, so an immediate "failed" response also fails over and
                    // re-drives rather than leaving the user to request retrieval again.
                    recordSyncFailureAndScheduleRetry(propState)
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
            errorMessage: String,
        ) {
            timeoutJob.cancel()
            syncFinalized.set(true)
            _isSyncing.value = false
            _syncProgress.value = SyncProgress.Idle
            if (_isManualSync) {
                _manualSyncResult.emit(SyncResult.Error(errorMessage))
                _isManualSync = false
            }
        }
    }
