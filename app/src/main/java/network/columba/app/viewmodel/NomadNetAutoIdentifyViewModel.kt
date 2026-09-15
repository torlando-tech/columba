package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.columba.app.repository.SettingsRepository
import javax.inject.Inject

/**
 * Single source of truth for the per-node NomadNet auto-identification opt-in
 * ("Always identify to this node"), backed by DataStore.
 *
 * Both the NomadNet browser (the identify dialog's switch + the on-load
 * auto-trigger) and the Node Details card observe this so the toggle stays
 * in sync across surfaces. The persisted set is the source of truth; this
 * ViewModel mirrors it reactively and writes toggles back.
 */
@HiltViewModel
class NomadNetAutoIdentifyViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _autoIdentifyNodes = MutableStateFlow<Set<String>>(emptySet())
        val autoIdentifyNodes: StateFlow<Set<String>> = _autoIdentifyNodes.asStateFlow()

        init {
            viewModelScope.launch {
                settingsRepository.nomadNetAutoIdentifyNodesFlow.collect { nodes ->
                    _autoIdentifyNodes.value = nodes
                }
            }
        }

        /**
         * Toggle the "always identify" opt-in for [nodeHash] and persist it.
         * [enabled] is the desired new state for the node.
         */
        fun setAutoIdentifyForNode(nodeHash: String, enabled: Boolean) {
            if (nodeHash.isBlank()) return
            val normalized = nodeHash.lowercase()
            val next =
                if (enabled) {
                    _autoIdentifyNodes.value + normalized
                } else {
                    _autoIdentifyNodes.value - normalized
                }
            _autoIdentifyNodes.value = next
            viewModelScope.launch {
                settingsRepository.saveNomadNetAutoIdentifyNodes(next)
            }
        }
    }
