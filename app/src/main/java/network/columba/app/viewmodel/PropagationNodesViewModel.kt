package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.PreferredPropagationNodeRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.service.PropagationNodeManager
import network.columba.app.ui.components.PropagationNodeRow
import network.columba.app.util.HexUtils.toHexString
import javax.inject.Inject

/**
 * Pure UI state for the preferred-propagation-nodes screen.
 *
 * @property list The ordered preferred list, with [PropagationNodeRow.isActive]
 *   set on the entry that currently backs the router's active relay.
 * @property activeHash The destination hash of the currently-active relay (the
 *   one actually pushed to the Python/LXMF layer), or null if none.
 * @property activeName Display name of the active relay, or null.
 * @property canImportCurrent Whether the one-time "import your current relay"
 *   affordance should be shown (list is empty, a relay exists, and the offer
 *   has not already been made).
 */
data class PropagationNodesUiState(
    val list: List<PropagationNodeRow> = emptyList(),
    val activeHash: String? = null,
    val activeName: String? = null,
    val canImportCurrent: Boolean = false,
)

/**
 * Drives [network.columba.app.ui.screens.PropagationNodesScreen]. The screen is a
 * pure render of [state]; every mutation calls the repository and/or the
 * [PropagationNodeManager] so the transport-aware policy writer and the
 * relay-observer pipeline (already wired in ticket 03) stay the single source of
 * truth for who `isMyRelay` points to.
 */
@HiltViewModel
class PropagationNodesViewModel
    @Inject
    constructor(
        private val preferredRepository: PreferredPropagationNodeRepository,
        private val announceRepository: AnnounceRepository,
        private val settingsRepository: SettingsRepository,
        private val propagationNodeManager: PropagationNodeManager,
    ) : ViewModel() {

        val state: StateFlow<PropagationNodesUiState> =
            combine(
                preferredRepository.observeOrdered(),
                propagationNodeManager.currentRelay,
                settingsRepository.preferredNodesImportOfferedFlow,
            ) { nodes, relay, importOffered ->
                val activeHash = relay?.destinationHash
                PropagationNodesUiState(
                    list = nodes.map {
                        PropagationNodeRow(
                            destinationHash = it.destinationHash,
                            displayName = it.displayName ?: it.destinationHash.take(12),
                            networkRestriction = it.networkRestriction,
                            isActive = it.destinationHash == activeHash,
                        )
                    },
                    activeHash = activeHash,
                    activeName = relay?.displayName,
                    canImportCurrent = nodes.isEmpty() && !importOffered && activeHash != null,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = PropagationNodesUiState(),
            )

        /** Add a node from a live announce, denormalizing the public key for later contact creation. */
        fun addFromAnnounce(announce: Announce) {
            viewModelScope.launch {
                preferredRepository.add(
                    destinationHash = announce.destinationHash,
                    networkRestriction = NetworkRestriction.ANY.name,
                    displayName = announce.peerName,
                    publicKey = announce.publicKey.toHexString(),
                )
            }
        }

        /** Add a node by destination hash only (no announce; the contact is created lazily on selection). */
        fun addByHash(destinationHash: String, nickname: String?) {
            viewModelScope.launch {
                preferredRepository.add(
                    destinationHash = destinationHash,
                    networkRestriction = NetworkRestriction.ANY.name,
                    displayName = nickname,
                )
            }
        }

        /** Remove a node; the policy re-resolves on the next emission (ticket 03 writer). */
        fun remove(destinationHash: String) {
            viewModelScope.launch {
                preferredRepository.remove(destinationHash)
            }
        }

        /** Persist a new priority order (committed once per drag-end by the list component). */
        fun reorder(orderedDestinationHashes: List<String>) {
            viewModelScope.launch {
                preferredRepository.reorder(orderedDestinationHashes)
            }
        }

        /** Cycle a node's transport restriction. */
        fun setRestriction(destinationHash: String, networkRestriction: String) {
            viewModelScope.launch {
                preferredRepository.setRestriction(destinationHash, networkRestriction)
            }
        }

        /** Force a node to become the active relay immediately (bypasses transport matching). */
        fun setActiveNow(destinationHash: String) {
            viewModelScope.launch {
                propagationNodeManager.setManualRelay(destinationHash)
            }
        }

        /** One-time "import your current relay": add the active relay to the empty list. */
        fun importCurrentRelay() {
            val activeHash = state.value.activeHash ?: return
            val activeName = state.value.activeName
            viewModelScope.launch {
                preferredRepository.add(
                    destinationHash = activeHash,
                    networkRestriction = NetworkRestriction.ANY.name,
                    displayName = activeName,
                )
                settingsRepository.markPreferredNodesImportOffered()
            }
        }

        /** Load the top announced propagation nodes for the "From announces" add tab (one-shot). */
        suspend fun loadTopCandidates(limit: Int = 10): List<Announce> =
            announceRepository.getTopPropagationNodes(limit).first()
    }

