package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.nomadnet.NomadNetSavedSite
import network.columba.app.repository.SettingsRepository
import javax.inject.Inject

/**
 * How a NomadNet node is named and drawn: announce data (name, node type,
 * profile icon) with the user's contact nickname taking precedence over the
 * announce name — the same precedence Contacts uses. Null when the node has
 * never announced and is not a contact, so callers fall back to the hash.
 */
data class NomadNetNodeSummary(
    val displayName: String?,
    val nodeType: String?,
    val iconName: String?,
    val iconForegroundColor: String?,
    val iconBackgroundColor: String?,
)

/**
 * Saved NomadNet pages on the NomadNet tab home.
 *
 * Storage lives in [SettingsRepository] (DataStore), so the tab home and the
 * browser's bookmark button — two separate view-model scopes — always agree:
 * both observe the same flow, both write through the same atomic
 * read-modify-write.
 */
@HiltViewModel
class NomadNetSitesViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val contactRepository: ContactRepository,
        private val announceRepository: AnnounceRepository,
    ) : ViewModel() {
        /** Saved pages, most recently saved first. */
        val savedSites: StateFlow<List<NomadNetSavedSite>> =
            settingsRepository.nomadNetSavedSitesFlow
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
                    initialValue = emptyList(),
                )

        /**
         * [NomadNetSavedSite.key]s of everything saved, for the browser's
         * bookmark toggle without walking the list on every recomposition.
         */
        val savedKeys: StateFlow<Set<String>> =
            savedSites
                .map { sites -> sites.mapTo(mutableSetOf()) { it.key } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
                    initialValue = emptySet(),
                )

        /**
         * Live label/icon data for one node. Per-hash queries rather than the
         * full announce table: the tab home asks about the handful of saved
         * nodes plus the "Continue" node only.
         */
        fun nodeSummary(nodeHash: String): Flow<NomadNetNodeSummary?> {
            if (nodeHash.isBlank()) return flowOf(null)
            return combine(
                announceRepository.getAnnounceFlow(nodeHash),
                contactRepository.getContactFlow(nodeHash),
            ) { announce, contact ->
                val name =
                    contact?.customNickname?.takeIf { it.isNotBlank() }
                        ?: announce?.peerName?.takeIf { it.isNotBlank() }
                if (name == null && announce == null) {
                    null
                } else {
                    NomadNetNodeSummary(
                        displayName = name,
                        nodeType = announce?.nodeType,
                        iconName = announce?.iconName,
                        iconForegroundColor = announce?.iconForegroundColor,
                        iconBackgroundColor = announce?.iconBackgroundColor,
                    )
                }
            }
        }

        /** Saves [nodeHash]/[persistPath], or removes it when already saved. */
        fun toggleSaved(
            nodeHash: String,
            persistPath: String,
            label: String? = null,
            requestData: String? = null,
        ) {
            viewModelScope.launch {
                settingsRepository.toggleNomadNetSavedSite(nodeHash, persistPath, label, requestData)
            }
        }

        fun removeSaved(
            nodeHash: String,
            persistPath: String,
        ) {
            viewModelScope.launch { settingsRepository.removeNomadNetSavedSite(nodeHash, persistPath) }
        }

        /** Puts a removed page back exactly where it was (snackbar Undo). */
        fun restoreSaved(site: NomadNetSavedSite) {
            viewModelScope.launch { settingsRepository.restoreNomadNetSavedSite(site) }
        }

        private companion object {
            /**
             * Keeps the DataStore subscription alive across a tab switch so
             * returning to the tab does not flash an empty list, and lets go
             * once the home is gone for good.
             */
            const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
        }
    }
