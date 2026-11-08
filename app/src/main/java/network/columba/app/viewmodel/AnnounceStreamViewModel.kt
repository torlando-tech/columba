package network.columba.app.viewmodel

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import network.columba.app.R
import network.columba.app.data.model.InterfaceType
import network.columba.app.data.db.dao.AnnounceArrivalMarker
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.NodeType
import network.columba.app.rns.api.RnsCore
import network.columba.app.service.IdentityResolutionManager
import network.columba.app.service.PropagationNodeManager
import network.columba.app.util.displayNameForAnnounce
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Why a manual announce failed; the UI resolves the reason to a string
 * resource. [AnnounceError.detail] (raw exception text) takes precedence
 * when present.
 */
enum class AnnounceErrorReason {
    UNKNOWN,
    TRIGGER_FAILED,
}

@StringRes
fun AnnounceErrorReason.messageResource(): Int =
    when (this) {
        AnnounceErrorReason.UNKNOWN -> R.string.error_unknown
        AnnounceErrorReason.TRIGGER_FAILED -> R.string.announce_error_trigger_failed
    }

/** Manual announce failure surfaced to the UI (AnnounceStream + Settings). */
data class AnnounceError(
    val reason: AnnounceErrorReason,
    val detail: String? = null,
)

/**
 * Optimistic edit applied to a loaded announce row. The frozen stream does not reload on
 * database writes, so user actions (favorite, delete) are reflected locally until the next
 * paging generation re-reads the database.
 */
data class AnnounceRowEdit(
    val isFavorite: Boolean? = null,
    val isDeleted: Boolean = false,
)

internal fun matchesRecentInterfaceFilter(
    announce: Announce,
    selectedInterfaces: Set<InterfaceType>,
): Boolean {
    if (selectedInterfaces.isEmpty()) return true
    val recentInterfaces =
        announce.recentInterfaceTypes.ifEmpty {
            setOf(InterfaceType.fromName(announce.receivingInterface))
        }
    return recentInterfaces.any(selectedInterfaces::contains)
}

@Suppress("TooManyFunctions") // ViewModels naturally have many public functions for UI interactions
@HiltViewModel
class AnnounceStreamViewModel
    @Inject
    constructor(
        private val rnsCore: RnsCore,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: network.columba.app.data.repository.ContactRepository,
        private val propagationNodeManager: PropagationNodeManager,
        private val identityRepository: IdentityRepository,
        private val blockedPeerRepository: network.columba.app.data.repository.BlockedPeerRepository,
        private val identityResolutionManager: IdentityResolutionManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AnnounceStreamViewModel"

            // Made var for testing
            internal var updateIntervalMs = 30_000L
        }

        // Whether Reticulum transport mode is enabled (for blackhole option)
        private val _isTransportEnabled = MutableStateFlow(false)
        val isTransportEnabled: StateFlow<Boolean> = _isTransportEnabled

        // Dirty flag: starts true for initial load, set on each announce, cleared by periodic timer
        private val reachableCountDirty =
            java.util.concurrent.atomic
                .AtomicBoolean(true)

        private data class FilterParams(
            val query: String,
            val selectedTypes: Set<NodeType>,
            val showAudio: Boolean,
            val selectedInterfaces: Set<InterfaceType>,
            val refreshGeneration: Long,
        )

        // Search query state
        val searchQuery = MutableStateFlow("")

        // Filter state - default to only PEER selected
        private val _selectedNodeTypes = MutableStateFlow<Set<NodeType>>(setOf(NodeType.PEER))
        val selectedNodeTypes: StateFlow<Set<NodeType>> = _selectedNodeTypes.asStateFlow()

        // Audio filter state - default to false (don't show audio announces)
        private val _showAudioAnnounces = MutableStateFlow(false)
        val showAudioAnnounces: StateFlow<Boolean> = _showAudioAnnounces.asStateFlow()

        // Interface type filter state - empty set means show all (no interface filter)
        private val _selectedInterfaceTypes = MutableStateFlow<Set<InterfaceType>>(emptySet())
        val selectedInterfaceTypes: StateFlow<Set<InterfaceType>> = _selectedInterfaceTypes.asStateFlow()

        /**
         * Bumped by [refreshAnnounces] to start a new paging generation. The announce stream
         * is frozen while the user is scrolled: Room writes must not reload the list, because
         * a Paging refresh restarts at the first row and replaces every loaded page (GH #1166).
         */
        private val refreshGeneration = MutableStateFlow(0L)

        /**
         * True once the 30-day interface-sighting window may have expired while an interface
         * filter was active. The UI consumes this through [refreshAnnounces] only when the
         * viewport is at the top, so it never yanks a scrolled list.
         */
        private val _windowRefreshPending = MutableStateFlow(false)
        val windowRefreshPending: StateFlow<Boolean> = _windowRefreshPending.asStateFlow()

        /**
         * Optimistic edits for loaded rows, so favorite/delete actions show immediately
         * while the stream is frozen. Cleared whenever a new paging generation arrives.
         */
        private val _rowEdits = MutableStateFlow<Map<String, AnnounceRowEdit>>(emptyMap())
        val rowEdits: StateFlow<Map<String, AnnounceRowEdit>> = _rowEdits.asStateFlow()

        // Total announce count for tab label
        val announceCount: StateFlow<Int> =
            announceRepository
                .getAnnounceCountFlow()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = 0,
                )

        private fun filterParams(): Flow<FilterParams> =
            combine(
                searchQuery,
                _selectedNodeTypes,
                _showAudioAnnounces,
                _selectedInterfaceTypes,
                refreshGeneration,
            ) { query, selectedTypes, showAudio, selectedInterfaces, refreshCount ->
                FilterParams(query, selectedTypes, showAudio, selectedInterfaces, refreshCount)
            }

        /** One paging generation: its filters and the arrival floor. */
        private data class Generation(
            val params: FilterParams,
            val floor: AnnounceArrivalMarker?,
        )

        /**
         * The current paging generation. [Generation.floor] is captured *before* the first page
         * is read: an announce saved between the two reads is past the floor (by receive time
         * or, for a late save of an older receive time, by rowid), so it is both counted as new
         * and shown once reloaded. It
         * is computed once per generation (not per subscription), so leaving the Network tab
         * and returning still counts the peers discovered while away.
         */
        private val generation: StateFlow<Generation?> =
            filterParams()
                .flatMapLatest { params ->
                    val typeStrings = countTypes(params.selectedTypes, params.showAudio)
                    if (typeStrings.isEmpty()) {
                        flowOf(Generation(params, null))
                    } else {
                        flow {
                            emit(
                                Generation(
                                    params = params,
                                    floor =
                                        announceRepository.getArrivalFloor(
                                            nodeTypes = typeStrings,
                                            searchQuery = params.query.trim(),
                                            interfaceTypes = params.selectedInterfaces.map { it.storageName },
                                        ),
                                ),
                            )
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )

        // Announces with pagination support, filtered by node types, audio filter,
        // interface types, AND search query. Each generation waits for [generation] so the
        // arrival floor is captured before the first page is read.
        val announces: Flow<PagingData<network.columba.app.data.repository.Announce>> =
            generation
                .filterNotNull()
                .flatMapLatest { gen ->
                    val params = gen.params
                    val selectedTypes = params.selectedTypes
                    val showAudio = params.showAudio
                    val typeStrings = pagingTypes(selectedTypes, showAudio)

                    if (typeStrings.isEmpty()) {
                        // If no types selected, return empty flow
                        flowOf(PagingData.empty())
                    } else {
                        // Get paginated announces from repository
                        announceRepository
                            .getAnnouncesPaged(
                                nodeTypes = typeStrings,
                                searchQuery = params.query.trim(),
                                interfaceTypes = params.selectedInterfaces.map { it.storageName },
                            ).map { pagingData ->
                                // A new generation re-reads the database for every loaded page,
                                // so optimistic edits are no longer needed.
                                _rowEdits.value = emptyMap()
                                // Interface filtering is handled in SQL so Paging can fill pages correctly.
                                // Keep only the audio/node-type compatibility filter in memory.
                                pagingData.filter { announce ->
                                    // Filter by nodeType
                                    // (exclude PEER if user didn't select it and we only added it for audio)
                                    val matchesNodeType =
                                        selectedTypes.map { it.name }.contains(announce.nodeType)
                                    val isAudioAnnounce = announce.nodeType == NodeType.PHONE.name

                                    val matchesTypeOrAudio =
                                        (matchesNodeType && (showAudio || !isAudioAnnounce)) || (isAudioAnnounce && showAudio)

                                    matchesTypeOrAudio
                                }
                            }
                    }
                }.cachedIn(viewModelScope)

        /**
         * Announces that arrived after the currently loaded stream was started. Re-emits on
         * every announce write; drives the "N new announces" affordance while frozen.
         */
        val newAnnounceCount: StateFlow<Int> =
            combine(filterParams(), generation) { params, gen ->
                // Only count against the floor of this generation; filters may have changed.
                if (gen?.params == params) gen else null
            }.flatMapLatest { gen ->
                if (gen == null) {
                    flowOf(0)
                } else {
                    val params = gen.params
                    val typeStrings = countTypes(params.selectedTypes, params.showAudio)
                    if (typeStrings.isEmpty()) {
                        flowOf(0)
                    } else {
                        // An empty (or not yet loaded) stream has no floor: counting every
                        // matching row lets arrivals make the list fill itself.
                        announceRepository.observeAnnounceCountNewerThan(
                            sinceTimestamp = gen.floor?.lastSeenTimestamp ?: 0L,
                            sinceRowId = gen.floor?.rowId ?: 0L,
                            nodeTypes = typeStrings,
                            searchQuery = params.query.trim(),
                            interfaceTypes = params.selectedInterfaces.map { it.storageName },
                        )
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = 0,
            )

        /**
         * Reload the announce stream from the newest row. Call only when the viewport is at
         * the top (or when the user asks): a reload replaces every loaded page.
         */
        fun refreshAnnounces() {
            _windowRefreshPending.value = false
            refreshGeneration.value += 1
        }

        private fun updateRowFavorite(
            destinationHash: String,
            isFavorite: Boolean,
        ) {
            val hash = destinationHash.lowercase()
            _rowEdits.update { edits ->
                val edit = edits[hash] ?: AnnounceRowEdit()
                edits + (hash to edit.copy(isFavorite = isFavorite))
            }
        }

        private fun markRowDeleted(destinationHash: String) {
            val hash = destinationHash.lowercase()
            _rowEdits.update { edits ->
                val edit = edits[hash] ?: AnnounceRowEdit()
                edits + (hash to edit.copy(isDeleted = true))
            }
        }

        /** Types for the SQL query; audio rows are fetched even when only their chip is on. */
        private fun pagingTypes(
            selectedTypes: Set<NodeType>,
            showAudio: Boolean,
        ): List<String> =
            if (showAudio && !selectedTypes.contains(NodeType.PHONE)) {
                (selectedTypes + NodeType.PHONE).map { it.name }
            } else {
                selectedTypes.map { it.name }
            }

        /** Types whose rows actually stay visible after the in-memory compatibility filter. */
        private fun countTypes(
            selectedTypes: Set<NodeType>,
            showAudio: Boolean,
        ): List<String> =
            if (showAudio) {
                (selectedTypes + NodeType.PHONE).map { it.name }
            } else {
                selectedTypes.filterNot { it == NodeType.PHONE }.map { it.name }
            }

        // Count of reachable announces (nodes with active paths in RNS path table)
        private val _reachableAnnounceCount = MutableStateFlow(0)
        val reachableAnnounceCount: StateFlow<Int> = _reachableAnnounceCount.asStateFlow()

        // Manual announce state
        private val _isAnnouncing = MutableStateFlow(false)
        val isAnnouncing: StateFlow<Boolean> = _isAnnouncing.asStateFlow()

        private val _announceSuccess = MutableStateFlow(false)
        val announceSuccess: StateFlow<Boolean> = _announceSuccess.asStateFlow()

        private val _announceError = MutableStateFlow<AnnounceError?>(null)
        val announceError: StateFlow<AnnounceError?> = _announceError.asStateFlow()

        init {
            viewModelScope.launch {
                try {
                    _isTransportEnabled.value = rnsCore.isTransportEnabled()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to check transport status", e)
                }
            }

            // Reticulum is now initialized by ColumbaApplication with config from database
            // No need to initialize here
            Log.d(TAG, "AnnounceStreamViewModel initialized - using RNS from ColumbaApplication")

            // Start collecting announces - but only if service is ready
            startCollectingAnnouncesWhenReady()

            // Update reachable count periodically, but only when dirty (new announces arrived).
            // Dirty flag starts true so the first iteration loads the initial count.
            if (updateIntervalMs > 0) {
                viewModelScope.launch {
                    while (true) {
                        if (reachableCountDirty.getAndSet(false)) {
                            updateReachableCount()
                        }
                        // Wall-clock advancement does not invalidate the keyset source.
                        // When an interface filter is active, flag the 30-day sighting window
                        // as possibly expired; the UI applies the reload at the top of the
                        // list only, so a scrolled viewport is never yanked (GH #1166).
                        if (_selectedInterfaceTypes.value.isNotEmpty()) {
                            _windowRefreshPending.value = true
                        }
                        kotlinx.coroutines.delay(updateIntervalMs)
                    }
                }
            }
        }

        /**
         * Update the reachable announce count by querying RNS path table.
         * This provides an accurate count of currently reachable nodes.
         */
        private suspend fun updateReachableCount() {
            // Don't query if network is not ready (e.g., during shutdown)
            if (rnsCore.networkStatus.value !is NetworkStatus.READY) {
                return
            }

            try {
                // Get path table hashes from RNS (Python call - must be off main thread)
                val pathTableHashes =
                    withContext(Dispatchers.IO) {
                        rnsCore.getPathTableHashes()
                    }

                // Count announces that match the path table (database query - already on IO dispatcher in repository)
                val count = announceRepository.countReachableAnnounces(pathTableHashes)

                _reachableAnnounceCount.value = count
                Log.d(TAG, "Updated reachable announce count: $count (from ${pathTableHashes.size} paths)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error updating reachable announce count", e)
            }
        }

        private fun startCollectingAnnouncesWhenReady() {
            viewModelScope.launch {
                // Phase 2.1: Event-driven wait using StateFlow (no polling!)
                // Wait for service to be READY before observing announces
                // This prevents trying to observe when handlers aren't registered
                Log.d(TAG, "Waiting for service to become READY...")

                try {
                    val timeout = 10000L // 10 seconds max
                    val ready =
                        withTimeoutOrNull(timeout) {
                            rnsCore.networkStatus.first { status ->
                                when (status) {
                                    is network.columba.app.rns.api.model.NetworkStatus.READY -> {
                                        Log.d(TAG, "Service is READY, starting announce collection")
                                        true
                                    }
                                    is network.columba.app.rns.api.model.NetworkStatus.ERROR -> {
                                        Log.e(TAG, "Service entered ERROR state: $status, not starting announce collection")
                                        throw RuntimeException("Service error: ${status.message}")
                                    }
                                    is network.columba.app.rns.api.model.NetworkStatus.CONNECTING -> {
                                        Log.d(TAG, "Service is CONNECTING, waiting...")
                                        false
                                    }
                                    is network.columba.app.rns.api.model.NetworkStatus.SHUTDOWN -> {
                                        Log.d(TAG, "Service is SHUTDOWN, waiting...")
                                        false
                                    }
                                    is network.columba.app.rns.api.model.NetworkStatus.INITIALIZING -> {
                                        Log.d(TAG, "Service is INITIALIZING, waiting...")
                                        false
                                    }
                                }
                            }
                        }

                    if (ready != null) {
                        startCollectingAnnounces()
                    } else {
                        // Timeout reached
                        Log.e(TAG, "Timeout waiting for service to become READY")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for service readiness", e)
                }
            }
        }

        private fun startCollectingAnnounces() {
            viewModelScope.launch {
                rnsCore.observeAnnounces().collect { announce ->
                    val hashHex = announce.destinationHash.joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Received announce: ${hashHex.take(16)}")

                    // Extract peer name from app_data using smart parser
                    // Prefers displayName from Python's LXMF.display_name_from_app_data()
                    val peerName =
                        network.columba.app.reticulum.util.AppDataParser.extractPeerName(
                            announce.appData,
                            hashHex,
                            announce.displayName,
                        )

                    // Upsert to database - updates timestamp if exists, inserts if new
                    // This automatically moves re-announces to the top due to ORDER BY lastSeenTimestamp DESC
                    try {
                        Log.d(TAG, "Saving announce: ${hashHex.take(16)} with interface: ${announce.receivingInterface}")
                        announceRepository.saveAnnounce(
                            destinationHash = hashHex,
                            peerName = peerName,
                            publicKey = announce.identity.publicKey,
                            appData = announce.appData,
                            hops = announce.hops,
                            timestamp = announce.timestamp,
                            nodeType = announce.nodeType.name,
                            receivingInterface = announce.receivingInterface,
                            receivingInterfaceType = InterfaceType.fromName(announce.receivingInterface).storageName,
                            aspect = announce.aspect,
                            stampCost = announce.stampCost,
                            stampCostFlexibility = announce.stampCostFlexibility,
                            peeringCost = announce.peeringCost,
                        )
                        Log.d(TAG, "Saved/updated announce in database: ${hashHex.take(16)}")

                        // Mark dirty so periodic timer triggers an update
                        reachableCountDirty.set(true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save announce to database", e)
                    }
                }
            }
        }

        /**
         * Toggle contact status for an announce.
         * If announce is not a contact, adds it to contacts.
         * If announce is a contact, removes it from contacts.
         */
        fun toggleContact(destinationHash: String) {
            viewModelScope.launch {
                try {
                    val isContact = contactRepository.hasContact(destinationHash)
                    if (isContact) {
                        // Remove from contacts
                        contactRepository.deleteContact(destinationHash)
                        announceRepository.setFavorite(destinationHash, false)
                        updateRowFavorite(destinationHash, isFavorite = false)
                        Log.d(TAG, "Removed contact: $destinationHash")
                    } else {
                        // Add to contacts - need to get announce data first
                        val announce = announceRepository.getAnnounce(destinationHash)
                        if (announce != null) {
                            contactRepository.addContactFromAnnounce(
                                destinationHash = destinationHash,
                                publicKey = announce.publicKey,
                            )
                            announceRepository.setFavorite(destinationHash, true)
                            updateRowFavorite(destinationHash, isFavorite = true)
                            Log.d(TAG, "Added contact from announce: $destinationHash")
                            // Request path for the newly added contact (with hasPath guard)
                            viewModelScope.launch(Dispatchers.IO) {
                                identityResolutionManager.requestPathForContact(destinationHash)
                            }
                        } else {
                            Log.e(TAG, "Cannot add contact: announce not found for $destinationHash")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle contact status for $destinationHash", e)
                }
            }
        }

        /**
         * Block a peer from the announce detail screen.
         */
        fun blockPeer(
            destinationHash: String,
            peerName: String,
            publicKey: ByteArray?,
            blackholeEnabled: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    val peerIdentityHash =
                        publicKey?.let {
                            network.columba.app.data.util.HashUtils
                                .computeIdentityHash(it)
                        }
                    blockedPeerRepository.blockPeer(destinationHash, peerIdentityHash, peerName, blackholeEnabled)
                    rnsCore.blockDestination(destinationHash)
                    if (blackholeEnabled && peerIdentityHash != null) {
                        rnsCore.blackholeIdentity(peerIdentityHash)
                    }
                    Log.d(TAG, "Blocked peer ${destinationHash.take(16)} (blackhole=$blackholeEnabled)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error blocking peer", e)
                }
            }
        }

        /**
         * Check if an announce is already a contact (for star button state)
         */
        suspend fun isContact(destinationHash: String): Boolean = contactRepository.hasContact(destinationHash)

        /**
         * Observe a specific announce reactively
         */
        fun getAnnounceFlow(destinationHash: String): Flow<Announce?> = announceRepository.getAnnounceFlow(destinationHash)

        fun getRecentInterfaceSightings(destinationHash: String) =
            announceRepository.getRecentInterfaceSightings(destinationHash)

        /**
         * Observe contact status reactively
         */
        fun isContactFlow(destinationHash: String): Flow<Boolean> = contactRepository.hasContactFlow(destinationHash)

        /**
         * Update the selected node types filter
         */
        fun updateSelectedNodeTypes(types: Set<NodeType>) {
            _selectedNodeTypes.value = types
            // A filter change starts a new paging generation, so the sighting window is re-read.
            _windowRefreshPending.value = false
        }

        fun updateShowAudioAnnounces(show: Boolean) {
            _showAudioAnnounces.value = show
            _windowRefreshPending.value = false
        }

        fun updateSelectedInterfaceTypes(types: Set<InterfaceType>) {
            _selectedInterfaceTypes.value = types
            _windowRefreshPending.value = false
        }

        /**
         * Trigger a manual announce immediately.
         */
        fun triggerAnnounce() {
            viewModelScope.launch {
                try {
                    _isAnnouncing.value = true
                    _announceSuccess.value = false
                    _announceError.value = null
                    Log.d(TAG, "Triggering manual announce...")

                    // Get display name from active identity. A cleared (blank)
                    // name is announced as the canonical "Anonymous Peer"
                    // (displayNameForAnnounce) so the peer's visible name matches
                    // the automatic-announce path rather than degrading to a
                    // hash-based "Peer XXXX" fallback on the receiver. The
                    // existing "Unknown" fallback for a *missing* active identity
                    // is preserved: it is nonblank, so it passes through.
                    val displayName = displayNameForAnnounce(
                        identityRepository.getActiveIdentitySync()?.displayName ?: "Unknown"
                    )

                    val result = rnsCore.triggerAutoAnnounce(displayName)

                    if (result.isSuccess) {
                        _isAnnouncing.value = false
                        _announceSuccess.value = true
                        Log.d(TAG, "Manual announce successful")

                        // Auto-dismiss success message after 3 seconds
                        delay(3000)
                        clearAnnounceStatus()
                    } else {
                        val error =
                            AnnounceError(
                                AnnounceErrorReason.UNKNOWN,
                                result.exceptionOrNull()?.message,
                            )
                        _isAnnouncing.value = false
                        _announceError.value = error
                        Log.e(TAG, "Manual announce failed: ${error.detail}")

                        // Auto-dismiss error message after 5 seconds
                        delay(5000)
                        clearAnnounceStatus()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _isAnnouncing.value = false
                    _announceError.value = AnnounceError(AnnounceErrorReason.TRIGGER_FAILED, e.message)
                    Log.e(TAG, "Error triggering manual announce", e)

                    // Auto-dismiss error message after 5 seconds
                    delay(5000)
                    clearAnnounceStatus()
                }
            }
        }

        /**
         * Clear manual announce status messages.
         */
        fun clearAnnounceStatus() {
            _announceSuccess.value = false
            _announceError.value = null
        }

        /**
         * Observe whether a destination is the user's current relay.
         */
        fun isMyRelayFlow(destinationHash: String): Flow<Boolean> = contactRepository.isMyRelayFlow(destinationHash)

        /**
         * Whether the given node is currently in the preferred propagation-nodes (priority) list.
         * Drives the "Add to priority list" / "In priority list" affordance on the Node Details
         * screen for relays.
         */
        fun isPreferredRelayFlow(destinationHash: String): Flow<Boolean> =
            propagationNodeManager.preferredRelayHashes.map { hashes -> destinationHash in hashes }

        /**
         * Add the given propagation node to the preferred propagation-nodes (priority) list,
         * creating a backing contact first. Unlike [setAsMyRelay], this always creates a list
         * row — even when the list is empty — so the user's intent to build a priority list is
         * preserved and auto-select is left to the policy writer. A no-op when already listed.
         */
        fun addToPriorityList(destinationHash: String, nickname: String?) {
            viewModelScope.launch {
                propagationNodeManager.addToPriorityList(destinationHash, nickname)
            }
        }

        /**
         * Set a propagation node as the user's relay.
         *
         * In ordered mode (preferred list non-empty) this appends the node to the
         * preferred list and lets the transport-aware policy writer resolve the active
         * relay (ticket 06). With an empty list it falls back to the legacy direct set,
         * so first-time users still get immediate behavior.
         */
        fun setAsMyRelay(destinationHash: String) {
            viewModelScope.launch {
                try {
                    propagationNodeManager.addPreferredRelay(destinationHash, null)
                    Log.d(TAG, "Set ${destinationHash.take(16)} as my relay")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set relay: $destinationHash", e)
                }
            }
        }

        /**
         * Unset the current relay and delete it from contacts.
         * @param autoSelectNew If true, auto-select a new relay (excluding the deleted one).
         */
        fun unsetRelayAndDelete(
            destinationHash: String,
            autoSelectNew: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    contactRepository.deleteContact(destinationHash)
                    propagationNodeManager.onRelayDeleted(
                        autoSelectNew = autoSelectNew,
                        excludeHash = destinationHash,
                    )
                    Log.d(TAG, "Unset relay and deleted: $destinationHash")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unset relay: $destinationHash", e)
                }
            }
        }

        /**
         * Delete a single announce from the database.
         * The announce will reappear if the peer announces again.
         */
        fun deleteAnnounce(destinationHash: String) {
            viewModelScope.launch {
                try {
                    announceRepository.deleteAnnounce(destinationHash)
                    markRowDeleted(destinationHash)
                    Log.d(TAG, "Deleted announce: $destinationHash")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete announce: $destinationHash", e)
                }
            }
        }

        /**
         * Delete all announces from the database, except those belonging to saved contacts.
         * Preserves announces for contacts so users can still open conversations.
         * Nodes will reappear when they announce again.
         */
        fun deleteAllAnnounces() {
            viewModelScope.launch {
                try {
                    val activeIdentity = identityRepository.getActiveIdentitySync()
                    if (activeIdentity != null) {
                        announceRepository.deleteAllAnnouncesExceptContacts(activeIdentity.identityHash)
                        Log.d(TAG, "Deleted non-contact announces for identity: ${activeIdentity.identityHash}")
                    } else {
                        // Fallback: delete all if no active identity (shouldn't happen in normal use)
                        announceRepository.deleteAllAnnounces()
                        Log.d(TAG, "Deleted all announces (no active identity)")
                    }
                    // Bulk delete invalidates every loaded row: re-read from the top.
                    _rowEdits.value = emptyMap()
                    refreshAnnounces()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete announces", e)
                }
            }
        }

        /**
         * Get all announces sharing the same identity as [destinationHash], excluding [destinationHash] itself.
         * Used to surface cross-links between telephony (lxst.telephony) and messaging (lxmf.delivery)
         * destinations that belong to the same peer identity.
         *
         * Reactive: re-emits whenever the source announce or its linked announces change in the DB.
         */
        fun getLinkedAnnouncesFlow(destinationHash: String): Flow<List<Announce>> =
            announceRepository.getAnnounceFlow(destinationHash).flatMapLatest { announce ->
                if (announce == null) {
                    kotlinx.coroutines.flow.flowOf(emptyList())
                } else {
                    val identityHash =
                        network.columba.app.data.util.HashUtils
                            .computeIdentityHash(announce.publicKey)
                    announceRepository.getLinkedAnnouncesFlow(identityHash, destinationHash)
                }
            }

        // TODO: viewModelScope is cancelled shortly after onCleared() returns (as a
        // registered Closeable), so the coroutine launched below is likely cancelled
        // before rnsCore.shutdown() executes. Shutdown is managed by
        // ColumbaApplication. Consider removing or moving to a separate scope.
        override fun onCleared() {
            super.onCleared()
            // Shutdown Reticulum when ViewModel is cleared
            viewModelScope.launch {
                rnsCore.shutdown()
            }
        }
    }
