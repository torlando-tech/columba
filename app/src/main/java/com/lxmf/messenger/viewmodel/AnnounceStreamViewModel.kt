package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class AnnounceStreamViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: com.lxmf.messenger.data.repository.ContactRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AnnounceStreamViewModel"
            internal var UPDATE_INTERVAL_MS = 30_000L // Made var for testing
        }

        // Search query state
        val searchQuery = MutableStateFlow("")

        // Filter state - default to only PEER selected
        private val _selectedNodeTypes = MutableStateFlow<Set<NodeType>>(setOf(NodeType.PEER))
        val selectedNodeTypes: StateFlow<Set<NodeType>> = _selectedNodeTypes.asStateFlow()

        // Audio filter state - default to false (don't show audio announces)
        private val _showAudioAnnounces = MutableStateFlow(false)
        val showAudioAnnounces: StateFlow<Boolean> = _showAudioAnnounces.asStateFlow()

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
                    announceRepository.getAnnouncesPaged(
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

        init {
            // Reticulum is now initialized by ColumbaApplication with config from database
            // No need to initialize here
            Log.d(TAG, "AnnounceStreamViewModel initialized - using RNS from ColumbaApplication")
            _initializationStatus.value = "Reticulum managed by app"

            // Start collecting announces - but only if service is ready
            startCollectingAnnouncesWhenReady()

            // Update reachable count periodically
            if (UPDATE_INTERVAL_MS > 0) {
                viewModelScope.launch {
                    while (true) {
                        updateReachableCount()
                        kotlinx.coroutines.delay(UPDATE_INTERVAL_MS)
                    }
                }
            }
        }

        /**
         * Update the reachable announce count by querying RNS path table.
         * This provides an accurate count of currently reachable nodes.
         */
        private suspend fun updateReachableCount() {
            try {
                // Get path table hashes from RNS
                val pathTableHashes = reticulumProtocol.getPathTableHashes()

                // Count announces that match the path table
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
                    val peerName =
                        com.lxmf.messenger.reticulum.util.AppDataParser.extractPeerName(
                            announce.appData,
                            hashHex,
                        )

                    // Upsert to database - updates timestamp if exists, inserts if new
                    // This automatically moves re-announces to the top due to ORDER BY lastSeenTimestamp DESC
                    try {
                        Log.d(TAG, "Saving announce: $peerName ($hashHex) with interface: ${announce.receivingInterface}")
                        announceRepository.saveAnnounce(
                            destinationHash = hashHex,
                            peerName = peerName,
                            publicKey = announce.identity.publicKey,
                            appData = announce.appData,
                            hops = announce.hops,
                            timestamp = announce.timestamp,
                            nodeType = announce.nodeType.name,
                            receivingInterface = announce.receivingInterface,
                            aspect = announce.aspect,
                        )
                        Log.d(TAG, "Saved/updated announce in database: $peerName ($hashHex)")

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
                                announceName = announce.peerName,
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
        suspend fun isContact(destinationHash: String): Boolean {
            return contactRepository.hasContact(destinationHash)
        }

        /**
         * Observe a specific announce reactively
         */
        fun getAnnounceFlow(destinationHash: String): Flow<Announce?> {
            return announceRepository.getAnnounceFlow(destinationHash)
        }

        /**
         * Observe contact status reactively
         */
        fun isContactFlow(destinationHash: String): Flow<Boolean> {
            return contactRepository.hasContactFlow(destinationHash)
        }

        /**
         * Update the selected node types filter
         */
        fun updateSelectedNodeTypes(types: Set<NodeType>) {
            _selectedNodeTypes.value = types
        }

        fun updateShowAudioAnnounces(show: Boolean) {
            _showAudioAnnounces.value = show
        }

        override fun onCleared() {
            super.onCleared()
            // Shutdown Reticulum when ViewModel is cleared
            viewModelScope.launch {
                reticulumProtocol.shutdown()
            }
        }
    }
