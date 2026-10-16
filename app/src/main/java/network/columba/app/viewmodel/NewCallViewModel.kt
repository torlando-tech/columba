package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
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

/**
 * Resolution state for the entered hash.
 */
sealed interface NewCallLookupState {
    data object Idle : NewCallLookupState

    /** Looking up matching announces in the local database. */
    data object Searching : NewCallLookupState

    /** Input could not be parsed as a hash (or derivation failed). */
    data class InvalidInput(val message: String) : NewCallLookupState

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

    /** Network path request failed. */
    data class NetworkRequestFailed(
        val telephonyDestinationHash: String,
        val message: String,
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
            val normalized = LxstDestination.normalizeHash(rawHash)
            if (normalized == null) {
                _state.value =
                    NewCallLookupState.InvalidInput(
                        if (mode == NewCallInputMode.LXST_DESTINATION_HASH) {
                            "Enter a valid 32-character destination hash"
                        } else {
                            "Enter a valid 32-character identity hash"
                        },
                    )
                return
            }

            val telephonyHash: String =
                when (mode) {
                    NewCallInputMode.LXST_DESTINATION_HASH -> normalized
                    NewCallInputMode.IDENTITY_HASH ->
                        LxstDestination.telephonyDestinationHashFromIdentity(normalized)
                            ?: run {
                                _state.value =
                                    NewCallLookupState.InvalidInput("Could not derive LXST destination from this identity hash")
                                return
                            }
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
                        _state.value =
                            NewCallLookupState.NetworkRequestFailed(
                                telephonyDestinationHash,
                                error.message ?: "Network path request failed",
                            )
                    }
            }
        }

        private companion object {
            /** Upper bound on waiting for an announce to land after dispatching a path request. */
            const val ANNOUNCE_WAIT_MS = 20_000L
        }
    }
