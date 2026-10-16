package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * exists the UI offers a "Search Network" action that submits a Reticulum path
 * request for the destination.
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

            _state.value = NewCallLookupState.Searching
            viewModelScope.launch {
                val announce = announceRepository.getAnnounce(telephonyHash)
                _state.value = NewCallLookupState.Resolved(telephonyHash, announce)
            }
        }

        /**
         * Submit a path request to the network for the resolved destination and
         * re-check the local database when it completes.
         */
        fun searchNetwork(telephonyDestinationHash: String) {
            _state.value = NewCallLookupState.RequestingNetwork(telephonyDestinationHash)
            viewModelScope.launch {
                val destBytes = LxstDestination.hexToBytes(telephonyDestinationHash)
                rnsCore
                    .requestPath(destBytes)
                    .onSuccess {
                        // A successful path request means the network resolved the
                        // destination; re-check whether an announce is now available.
                        val announce = announceRepository.getAnnounce(telephonyDestinationHash)
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
    }
