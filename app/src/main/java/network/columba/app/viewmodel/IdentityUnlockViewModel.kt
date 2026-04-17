package network.columba.app.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.protocol.ReticulumProtocol
import javax.inject.Inject

private const val TAG = "IdentityUnlockVM"

/**
 * ViewModel for the screen shown after an Auto Backup restore when the active
 * identity row is present but its Keystore-wrapped `encryptedKeyData` can't be
 * decrypted by the new device's Keystore. Drives two recovery paths: import the
 * identity `.identity` file the user had saved, or start fresh with a new one.
 */
@HiltViewModel
class IdentityUnlockViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityRepository: IdentityRepository,
        private val settingsRepository: SettingsRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<IdentityUnlockUiState>(IdentityUnlockUiState.Idle)
        val uiState: StateFlow<IdentityUnlockUiState> = _uiState.asStateFlow()

        val activeIdentity: StateFlow<LocalIdentityEntity?> =
            identityRepository.activeIdentity.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )

        /**
         * Parse the imported identity file and, if its hash matches the active
         * identity, re-wrap the key with the new device's Keystore and clear
         * the `needs_identity_unlock` flag. Hash mismatch surfaces a confirm
         * prompt via [IdentityUnlockUiState.HashMismatch]; the user can confirm
         * (replaces the active row) or cancel.
         */
        fun importIdentityFile(fileUri: Uri) {
            viewModelScope.launch {
                _uiState.value = IdentityUnlockUiState.Loading("Reading identity file...")

                val active = identityRepository.getActiveIdentitySync()
                if (active == null) {
                    _uiState.value = IdentityUnlockUiState.Error("No active identity to restore into")
                    return@launch
                }

                val fileData =
                    try {
                        context.contentResolver
                            .openInputStream(fileUri)
                            ?.use { it.readBytes() }
                            ?: run {
                                _uiState.value =
                                    IdentityUnlockUiState.Error("Couldn't open file")
                                return@launch
                            }
                    } catch (e: Exception) {
                        _uiState.value =
                            IdentityUnlockUiState.Error("Couldn't read file: ${e.message}")
                        return@launch
                    }

                val parse = reticulumProtocol.importIdentityFile(fileData, active.displayName)
                if (parse["success"] != true) {
                    _uiState.value =
                        IdentityUnlockUiState.Error(
                            parse["error"] as? String ?: "File is not a valid identity",
                        )
                    return@launch
                }
                val importedHash =
                    parse["identity_hash"] as? String
                        ?: run {
                            _uiState.value = IdentityUnlockUiState.Error("No hash in parse result")
                            return@launch
                        }
                val keyData =
                    parse["key_data"] as? ByteArray
                        ?: run {
                            _uiState.value = IdentityUnlockUiState.Error("No key material in file")
                            return@launch
                        }

                if (importedHash != active.identityHash) {
                    Log.w(
                        TAG,
                        "Imported identity hash ${importedHash.take(8)}... doesn't match active " +
                            "${active.identityHash.take(8)}...",
                    )
                    _uiState.value =
                        IdentityUnlockUiState.HashMismatch(
                            importedHash = importedHash,
                            activeHash = active.identityHash,
                            keyData = keyData,
                        )
                    return@launch
                }

                completeRewrap(active.identityHash, keyData)
            }
        }

        /**
         * Called after the user explicitly confirms importing an identity whose
         * hash doesn't match the existing active row. We delete the orphaned
         * row, then create a fresh one from the imported bytes and set it
         * active. Conversations tied to the old hash are left in Room but will
         * appear dormant (no active identity can decrypt them); a future PR
         * could offer to purge them.
         */
        fun confirmReplaceMismatched() {
            val current = _uiState.value
            if (current !is IdentityUnlockUiState.HashMismatch) return
            viewModelScope.launch {
                _uiState.value = IdentityUnlockUiState.Loading("Replacing identity...")

                // Derive destination hash fresh from the imported identity — the
                // protocol already computed it during parse, but we re-parse here
                // so we don't need to carry it through HashMismatch state.
                val active = identityRepository.getActiveIdentitySync()
                if (active != null) {
                    identityRepository
                        .deleteIdentity(active.identityHash)
                        .onFailure {
                            _uiState.value =
                                IdentityUnlockUiState.Error(
                                    "Couldn't remove old identity row: ${it.message}",
                                )
                            return@launch
                        }
                }

                val parse =
                    reticulumProtocol.importIdentityFile(current.keyData, active?.displayName ?: "")
                val destHash =
                    parse["destination_hash"] as? String
                        ?: run {
                            _uiState.value =
                                IdentityUnlockUiState.Error("Couldn't compute destination hash")
                            return@launch
                        }

                val result =
                    identityRepository.createIdentity(
                        identityHash = current.importedHash,
                        displayName = active?.displayName ?: "Imported Identity",
                        destinationHash = destHash,
                        filePath = "",
                        keyData = current.keyData,
                    )
                result
                    .onSuccess {
                        identityRepository.switchActiveIdentity(current.importedHash)
                        settingsRepository.setNeedsIdentityUnlock(false)
                        _uiState.value = IdentityUnlockUiState.Restored
                    }.onFailure { e ->
                        _uiState.value =
                            IdentityUnlockUiState.Error(
                                "Couldn't save imported identity: ${e.message}",
                            )
                    }
            }
        }

        fun cancelHashMismatch() {
            if (_uiState.value is IdentityUnlockUiState.HashMismatch) {
                _uiState.value = IdentityUnlockUiState.Idle
            }
        }

        /**
         * Delete the undecryptable identity and send the user back through
         * onboarding to create a new one. Messages and contacts tied to the old
         * identity hash remain in Room but are effectively orphaned — the user
         * can see their history but won't be able to continue any conversation.
         */
        fun startFresh() {
            viewModelScope.launch {
                _uiState.value = IdentityUnlockUiState.Loading("Starting fresh...")
                val active = identityRepository.getActiveIdentitySync()
                active?.let { identityRepository.deleteIdentity(it.identityHash) }
                settingsRepository.clearOnboardingCompleted()
                settingsRepository.setNeedsIdentityUnlock(false)
                _uiState.value = IdentityUnlockUiState.StartedFresh
            }
        }

        private suspend fun completeRewrap(
            identityHash: String,
            keyData: ByteArray,
        ) {
            _uiState.value = IdentityUnlockUiState.Loading("Unlocking identity...")
            identityRepository
                .rewrapKeyWithDeviceKey(identityHash, keyData)
                .onSuccess {
                    settingsRepository.setNeedsIdentityUnlock(false)
                    _uiState.value = IdentityUnlockUiState.Restored
                }.onFailure { e ->
                    _uiState.value =
                        IdentityUnlockUiState.Error("Couldn't save identity key: ${e.message}")
                }
        }
    }

sealed class IdentityUnlockUiState {
    object Idle : IdentityUnlockUiState()

    data class Loading(
        val message: String,
    ) : IdentityUnlockUiState()

    data class Error(
        val message: String,
    ) : IdentityUnlockUiState()

    data class HashMismatch(
        val importedHash: String,
        val activeHash: String,
        val keyData: ByteArray,
    ) : IdentityUnlockUiState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HashMismatch) return false
            return importedHash == other.importedHash &&
                activeHash == other.activeHash &&
                keyData.contentEquals(other.keyData)
        }

        override fun hashCode(): Int = importedHash.hashCode()
    }

    object Restored : IdentityUnlockUiState()

    object StartedFresh : IdentityUnlockUiState()
}
