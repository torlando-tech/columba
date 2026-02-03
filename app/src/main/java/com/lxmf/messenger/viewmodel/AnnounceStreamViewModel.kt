package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.lxmf.messenger.data.model.InterfaceType
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.PropagationNodeManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@Suppress("TooManyFunctions") // ViewModels naturally have many public functions for UI interactions
@HiltViewModel
class AnnounceStreamViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: com.lxmf.messenger.data.repository.ContactRepository,
        private val propagationNodeManager: PropagationNodeManager,
        private val identityRepository: IdentityRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AnnounceStreamViewModel"

            // Made var for testing
            internal var updateIntervalMs = 30_000L
        }

        // Search query state
        val searchQuery = MutableStateFlow("")

        // Filter state - default to only PEER selected
        private val _selectedNodeTypes = MutableStateFlow<Set<NodeType>>(setOf(NodeType.PEER))
        val selectedNodeTypes: StateFlow<Set<NodeType>> = _selectedNodeTypes.asStateFlow()

        // Audio filter state - default to false (don't show audio announces)
        private val _showAudioAnnounces = MutableStateFlow(false)
        val showAudioAnnounces: StateFlow<Boolean> = _showAudioAnnounces.asStateFlow()

        // Total announce count for tab label
        val announceCount: StateFlow<Int> =
            announceRepository
                .getAnnounceCountFlow()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = 0,
                )

        // Announces with pagination support, filtered by node types, audio filter, AND search query
        val announces: Flow<PagingData<com.lxmf.messenger.data.repository.Announce>> =
            combine(
                searchQuery,
                _selectedNodeTypes,
                _showAudioAnnounces,
            ) { query, selectedTypes, showAudio ->
                Triple(query, selectedTypes, showAudio)
            }.flatMapLatest { (query, selectedTypes, showAudio) ->
                // Build type list for database query
                // If showAudio is true but PEER is not selected, still include PEER in DB query
                // (because audio announces have nodeType=PEER), then filter by aspect in memory
                val typesForQuery =
                    if (showAudio && !selectedTypes.contains(NodeType.PEER)) {
                        selectedTypes + NodeType.PEER
                    } else {
                        selectedTypes
                    }
                val typeStrings = typesForQuery.map { it.name }

                if (typeStrings.isEmpty()) {
                    // If no types selected, return empty flow
                    flowOf(PagingData.empty())
                } else {
                    // Get paginated announces from repository
                    announceRepository
                        .getAnnouncesPaged(
                            nodeTypes = typeStrings,
                            searchQuery = query.trim(),
                        ).map { pagingData ->
                            // Apply in-memory filters for nodeType and audio aspect
                            pagingData.filter { announce ->
                                // Filter by nodeType
                                // (exclude PEER if user didn't select it and we only added it for audio)
                                val matchesNodeType =
                                    selectedTypes.map { it.name }.contains(announce.nodeType)
                                val isAudioAnnounce = announce.aspect == "call.audio"

                                // Show announce if:
                                // - It matches selected nodeType AND (showAudio OR not audio announce)
                                // - OR it's audio announce AND showAudio is true
                                (matchesNodeType && (showAudio || !isAudioAnnounce)) || (isAudioAnnounce && showAudio)
                            }
                        }
                }
            }.cachedIn(viewModelScope)

        // Count of reachable announces (nodes with active paths in RNS path table)
        private val _reachableAnnounceCount = MutableStateFlow(0)
        val reachableAnnounceCount: StateFlow<Int> = _reachableAnnounceCount.asStateFlow()

        private val _initializationStatus = MutableStateFlow<String>("Initializing...")
        val initializationStatus: StateFlow<String> = _initializationStatus.asStateFlow()

        // Manual announce state
        private val _isAnnouncing = MutableStateFlow(false)
        val isAnnouncing: StateFlow<Boolean> = _isAnnouncing.asStateFlow()

        private val _announceSuccess = MutableStateFlow(false)
        val announceSuccess: StateFlow<Boolean> = _announceSuccess.asStateFlow()

        private val _announceError = MutableStateFlow<String?>(null)
        val announceError: StateFlow<String?> = _announceError.asStateFlow()

        init {
            // Reticulum is now initialized by ColumbaApplication with config from database
            // No need to initialize here
            Log.d(TAG, "AnnounceStreamViewModel initialized - using RNS from ColumbaApplication")
            _initializationStatus.value = "Reticulum managed by app"

            // Start collecting announces - but only if service is ready
            startCollectingAnnouncesWhenReady()

            // Update reachable count periodically
            if (updateIntervalMs > 0) {
                viewModelScope.launch {
                    while (true) {
                        updateReachableCount()
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
            if (reticulumProtocol.networkStatus.value !is NetworkStatus.READY) {
                return
            }

            try {
                // Get path table hashes from RNS (Python call - must be off main thread)
                val pathTableHashes =
                    withContext(Dispatchers.IO) {
                        reticulumProtocol.getPathTableHashes()
                    }

                // Count announces that match the path table (database query - already on IO dispatcher in repository)
                val count = announceRepository.countReachableAnnounces(pathTableHashes)

                _reachableAnnounceCount.value = count
                Log.d(TAG, "Updated reachable announce count: $count (from ${pathTableHashes.size} paths)")
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
                            reticulumProtocol.networkStatus.first { status ->
                                when (status) {
                                    is com.lxmf.messenger.reticulum.model.NetworkStatus.READY -> {
                                        Log.d(TAG, "Service is READY, starting announce collection")
                                        _initializationStatus.value = "Ready"
                                        true
                                    }
                                    is com.lxmf.messenger.reticulum.model.NetworkStatus.ERROR -> {
                                        Log.e(TAG, "Service entered ERROR state: $status, not starting announce collection")
                                        _initializationStatus.value = "Error: ${status.message}"
                                        throw RuntimeException("Service error: ${status.message}")
                                    }
                                    is com.lxmf.messenger.reticulum.model.NetworkStatus.CONNECTING -> {
                                        Log.d(TAG, "Service is CONNECTING, waiting...")
                                        false
                                    }
                                    is com.lxmf.messenger.reticulum.model.NetworkStatus.SHUTDOWN -> {
                                        Log.d(TAG, "Service is SHUTDOWN, waiting...")
                                        false
                                    }
                                    is com.lxmf.messenger.reticulum.model.NetworkStatus.INITIALIZING -> {
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
                        _initializationStatus.value = "Timeout waiting for service"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for service readiness", e)
                    _initializationStatus.value = "Error: ${e.message}"
                }
            }
        }

        private fun startCollectingAnnounces() {
            viewModelScope.launch {
                reticulumProtocol.observeAnnounces().collect { announce ->
                    val hashHex = announce.destinationHash.joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Received announce: ${hashHex.take(16)}")

                    // Extract peer name from app_data using smart parser
                    // Prefers displayName from Python's LXMF.display_name_from_app_data()
                    val peerName =
                        com.lxmf.messenger.reticulum.util.AppDataParser.extractPeerName(
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
                            receivingInterfaceType = InterfaceType.fromInterfaceName(announce.receivingInterface).name,
                            aspect = announce.aspect,
                            stampCost = announce.stampCost,
                            stampCostFlexibility = announce.stampCostFlexibility,
                            peeringCost = announce.peeringCost,
                        )
                        Log.d(TAG, "Saved/updated announce in database: ${hashHex.take(16)}")

                        // Update reachable count after new announce
                        updateReachableCount()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save announce to database", e)
                    }
                }
            }
        }

        /**
         * Toggle contact status for an announce (replaces toggleFavorite)
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
                            Log.d(TAG, "Added contact from announce: $destinationHash")
                        } else {
                            Log.e(TAG, "Cannot add contact: announce not found for $destinationHash")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle contact status for $destinationHash", e)
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

        /**
         * Observe contact status reactively
         */
        fun isContactFlow(destinationHash: String): Flow<Boolean> = contactRepository.hasContactFlow(destinationHash)

        /**
         * Update the selected node types filter
         */
        fun updateSelectedNodeTypes(types: Set<NodeType>) {
            _selectedNodeTypes.value = types
        }

        fun updateShowAudioAnnounces(show: Boolean) {
            _showAudioAnnounces.value = show
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

                    // Get display name from active identity
                    val displayName = identityRepository.getActiveIdentitySync()?.displayName ?: "Unknown"

                    // Trigger announce if using ServiceReticulumProtocol
                    val protocol = reticulumProtocol
                    if (protocol is ServiceReticulumProtocol) {
                        val result = protocol.triggerAutoAnnounce(displayName)

                        if (result.isSuccess) {
                            _isAnnouncing.value = false
                            _announceSuccess.value = true
                            Log.d(TAG, "Manual announce successful")

                            // Auto-dismiss success message after 3 seconds
                            delay(3000)
                            clearAnnounceStatus()
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "Unknown error"
                            _isAnnouncing.value = false
                            _announceError.value = error
                            Log.e(TAG, "Manual announce failed: $error")

                            // Auto-dismiss error message after 5 seconds
                            delay(5000)
                            clearAnnounceStatus()
                        }
                    } else {
                        _isAnnouncing.value = false
                        _announceError.value = "Service not available"
                        Log.w(TAG, "Manual announce skipped: ReticulumProtocol is not ServiceReticulumProtocol")

                        // Auto-dismiss error message after 5 seconds
                        delay(5000)
                        clearAnnounceStatus()
                    }
                } catch (e: Exception) {
                    _isAnnouncing.value = false
                    _announceError.value = e.message ?: "Error triggering announce"
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
         * Set a propagation node as the user's relay.
         * This will add it to contacts if not already present and mark it as the relay.
         */
        fun setAsMyRelay(
            destinationHash: String,
            peerName: String,
        ) {
            viewModelScope.launch {
                try {
                    propagationNodeManager.setManualRelay(destinationHash, peerName)
                    Log.d(TAG, "Set ${destinationHash.take(16)} as my relay")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set relay: $destinationHash", e)
                }
            }
        }

        /**
         * Unset the current relay and delete it from contacts, then trigger auto-selection.
         */
        fun unsetRelayAndDelete(destinationHash: String) {
            viewModelScope.launch {
                try {
                    // IMPORTANT: Set exclusion BEFORE delete to prevent immediate re-selection
                    propagationNodeManager.excludeFromAutoSelect(destinationHash)
                    contactRepository.deleteContact(destinationHash)
                    propagationNodeManager.onRelayDeleted()
                    Log.d(TAG, "Unset relay and deleted: $destinationHash")
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
                    Log.d(TAG, "Deleted announce: $destinationHash")
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete announces", e)
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            // Shutdown Reticulum when ViewModel is cleared
            viewModelScope.launch {
                reticulumProtocol.shutdown()
            }
        }
    }
