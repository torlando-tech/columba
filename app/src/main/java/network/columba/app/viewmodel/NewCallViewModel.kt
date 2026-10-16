package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.CallHistoryRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.util.LxstDestination
import javax.inject.Inject

/**
 * Input mode for the "New call" flow.
 */
enum class NewCallInputMode {
    /** User enters a Reticulum identity hash; the lxst.telephony destination is derived. */
    IDENTITY_HASH,

    /** User enters the lxst.telephony destination hash directly. */
    LXST_DESTINATION_HASH,
}

/** Why the entered hash could not be used; the UI resolves these to string resources. */
enum class NewCallInvalidReason {
    INVALID_IDENTITY_HASH,
    INVALID_DESTINATION_HASH,
}

/**
 * Resolution state for the entered hash.
 */
sealed interface NewCallLookupState {
    data object Idle : NewCallLookupState

    /** Looking up matching announces in the local database. */
    data object Searching : NewCallLookupState

    /** The input could not be parsed as a hash for the selected mode. */
    data class InvalidInput(val reason: NewCallInvalidReason) : NewCallLookupState

    /**
     * Lookup finished.
     *
     * @param telephonyDestinationHash the resolved lxst.telephony destination hash
     * @param announce the locally known announce for that destination, or null if
     *                 we have never seen it announced.
     */
    data class Resolved(
        val telephonyDestinationHash: String,
        val announce: Announce?,
    ) : NewCallLookupState

    /** Network path request in flight. */
    data class RequestingNetwork(val telephonyDestinationHash: String) : NewCallLookupState

    /**
     * Network path request failed.
     *
     * @param message the transport error message, or null; the UI supplies a
     *                localized fallback when absent.
     */
    data class NetworkRequestFailed(
        val telephonyDestinationHash: String,
        val message: String?,
    ) : NewCallLookupState
}

/**
 * Backing state for the "New call" destination-entry screens.
 *
 * Takes a raw hash string, either uses it directly (LXST destination mode) or
 * derives the `lxst.telephony` destination from an identity hash, then looks
 * the destination up in the local announce database. When no local announce
 * exists the UI offers a "Search Network" action that dispatches a Reticulum path
 * request and waits (bounded) for the announce to land.
 */
@HiltViewModel
class NewCallViewModel
    @Inject
    constructor(
        private val announceRepository: AnnounceRepository,
        private val rnsCore: RnsCore,
    ) : ViewModel() {
        private val _state = MutableStateFlow<NewCallLookupState>(NewCallLookupState.Idle)
        val state: StateFlow<NewCallLookupState> = _state.asStateFlow()

        /** In-flight lookup; cancelled when a newer submit or search supersedes it. */
        private var activeJob: Job? = null

        /**
         * Validate the raw input for [mode] and search the local announce database
         * for an announce whose destination hash matches the resolved
         * lxst.telephony destination.
         */
        fun submit(
            rawHash: String,
            mode: NewCallInputMode,
        ) {
            // Supersede any in-flight lookup/search first: an invalid new
            // submission must not leave an older job alive to overwrite the
            // error state when it completes.
            activeJob?.cancel()

            val normalized = LxstDestination.normalizeHash(rawHash)
            if (normalized == null) {
                _state.value =
                    NewCallLookupState.InvalidInput(
                        if (mode == NewCallInputMode.LXST_DESTINATION_HASH) {
                            NewCallInvalidReason.INVALID_DESTINATION_HASH
                        } else {
                            NewCallInvalidReason.INVALID_IDENTITY_HASH
                        },
                    )
                return
            }

            val telephonyHash: String =
                when (mode) {
                    NewCallInputMode.LXST_DESTINATION_HASH -> normalized
                    NewCallInputMode.IDENTITY_HASH ->
                        LxstDestination.telephonyDestinationHashFromNormalizedIdentity(normalized)
                }

            activeJob?.cancel()
            _state.value = NewCallLookupState.Searching
            activeJob = viewModelScope.launch {
                val announce = announceRepository.getAnnounce(telephonyHash)
                _state.value = NewCallLookupState.Resolved(telephonyHash, announce)
            }
        }

        /**
         * Dispatch a path request for the resolved destination and wait (bounded)
         * for the announce to land on the announce stream before resolving.
         */
        fun searchNetwork(telephonyDestinationHash: String) {
            activeJob?.cancel()
            _state.value = NewCallLookupState.RequestingNetwork(telephonyDestinationHash)
            activeJob = viewModelScope.launch {
                val telephonyBytes = LxstDestination.hexToBytes(telephonyDestinationHash)
                rnsCore
                    .requestPath(telephonyBytes)
                    .onSuccess {
                        // request_path only dispatches the request; the announce that makes the
                        // destination callable (identity + hop info) can land seconds later on the
                        // announce stream. Wait for it, bounded, then resolve with whatever the
                        // local database has.
                        val announce =
                            withTimeoutOrNull(ANNOUNCE_WAIT_MS) {
                                announceRepository
                                    .getAnnounceFlow(telephonyDestinationHash)
                                    .filterNotNull()
                                    .firstOrNull()
                            } ?: announceRepository.getAnnounce(telephonyDestinationHash)
                        _state.value = NewCallLookupState.Resolved(telephonyDestinationHash, announce)
                    }.onFailure { error ->
                        // ClientRnsCore.requestPath converts cancellation into a
                        // failed Result; a superseded job must not publish its
                        // error over the newer lookup's state.
                        if (isActive) {
                            _state.value =
                                NewCallLookupState.NetworkRequestFailed(
                                    telephonyDestinationHash,
                                    error.message,
                                )
                        }
                    }
            }
        }

        private companion object {
            /** Upper bound on waiting for an announce to land after dispatching a path request. */
            const val ANNOUNCE_WAIT_MS = 20_000L
        }
    }

/** A recently called destination offered for one-tap redial on the mode picker. */
data class NewCallRecentDestination(
    val hash: String,
    val mode: NewCallInputMode,
    val label: String,
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
)

/**
 * Backs the "New call" mode picker: surfaces the most recently called
 * destinations so redialing does not require re-entering a hash.
 *
 * Note the history column `remoteIdentityHash` carries the dialed
 * destination hash for outgoing attempts (lxmf.delivery when dialed from a
 * contact, lxst.telephony from the new-call flow) but the caller's identity
 * hash for incoming ones, so the chip's input [NewCallRecentDestination.mode]
 * is derived from the record direction, and rows are deduplicated per peer by
 * mapping derivable destination hashes back to the incoming identity.
 */
@HiltViewModel
class NewCallPickerViewModel
    @Inject
    constructor(
        private val callHistoryRepository: CallHistoryRepository,
    ) : ViewModel() {
        val recentDestinations: StateFlow<List<NewCallRecentDestination>> =
            callHistoryRepository
                .observeHistory()
                .map { records ->
                    val sorted = records.sortedByDescending { it.attemptedAt }
                    // Incoming rows carry the caller's identity hash; outgoing
                    // rows carry the dialed destination. Map every destination
                    // derivable from a known incoming identity back onto that
                    // identity so both sides of one peer merge into one chip.
                    val identityByDestination = buildMap {
                        sorted.forEach { record ->
                            if (!record.direction.equals("OUTGOING", ignoreCase = true)) {
                                val identity = record.remoteIdentityHash
                                put(identity, identity)
                                LxstDestination.telephonyDestinationHashFromIdentity(identity)
                                    ?.let { put(it, identity) }
                                LxstDestination.deliveryDestinationHashFromIdentity(identity)
                                    ?.let { put(it, identity) }
                            }
                        }
                    }
                    sorted
                        .groupBy {
                            identityByDestination[it.remoteIdentityHash] ?: it.remoteIdentityHash
                        }
                        .values
                        .map { group ->
                            val newest = group.first()
                            // Fused announce icon may be present on only one
                            // side of the peer; take it from one row so the
                            // color triple always matches its icon.
                            val iconRow =
                                group.firstOrNull { record ->
                                    record.iconName != null &&
                                        record.iconForegroundColor != null &&
                                        record.iconBackgroundColor != null
                                }
                            NewCallRecentDestination(
                                hash = newest.remoteIdentityHash,
                                mode =
                                    if (newest.direction.equals("OUTGOING", ignoreCase = true)) {
                                        NewCallInputMode.LXST_DESTINATION_HASH
                                    } else {
                                        NewCallInputMode.IDENTITY_HASH
                                    },
                                label =
                                    newest.displayName
                                        ?: newest.peerDisplayNameSnapshot
                                        ?: "${newest.remoteIdentityHash.take(12)}…",
                                iconName = iconRow?.iconName,
                                iconForegroundColor = iconRow?.iconForegroundColor,
                                iconBackgroundColor = iconRow?.iconBackgroundColor,
                            )
                        }
                        .take(MAX_RECENT_DESTINATIONS)
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        companion object {
            private const val MAX_RECENT_DESTINATIONS = 4
        }
    }
