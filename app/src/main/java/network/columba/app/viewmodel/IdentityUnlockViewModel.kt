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

                val destHash =
                    parse["destination_hash"] as? String
                        ?: run {
                            _uiState.value =
                                IdentityUnlockUiState.Error("No destination hash in parse result")
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
                            destHash = destHash,
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

                // `importedHash`, `keyData`, and `destHash` are all carried
                // through `HashMismatch` from the initial parse — no need to
                // re-invoke the protocol here. Re-parsing with the already-
                // extracted 64-byte `keyData` would fail anyway since
                // `importIdentityFile` expects raw file bytes, not pre-parsed
                // key material.
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

                val result =
                    identityRepository.createIdentity(
                        identityHash = current.importedHash,
                        displayName = active?.displayName ?: "Imported Identity",
                        destinationHash = current.destHash,
                        filePath = "",
                        keyData = current.keyData,
                    )
                result
                    .onSuccess {
                        // If switch fails the new row exists but isActive=0,
                        // so the next boot sees no active identity and Chats
                        // silently breaks. Surface the failure and leave the
                        // unlock flag set so we route back here next launch.
                        val switched =
                            identityRepository.switchActiveIdentity(current.importedHash)
                        switched.onFailure { e ->
                            _uiState.value =
                                IdentityUnlockUiState.Error(
                                    "Couldn't activate imported identity: ${e.message}",
                                )
                            return@launch
                        }
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
         * Reset from a terminal `Error` state back to `Idle` so the user can
         * retry. `cancelHashMismatch` only handles the mismatch-confirm path;
         * without this, the "Try again" button in `ErrorBlock` was a no-op
         * once an import failed — leaving the user stuck on the error message
         * with no way to start over.
         */
        fun dismissError() {
            if (_uiState.value is IdentityUnlockUiState.Error) {
                _uiState.value = IdentityUnlockUiState.Idle
            }
        }

        /**
         * Delete the undecryptable identity and restart the process. The running
         * ReticulumService still holds the old identity in native memory, and
         * the app's auto-create-identity path only fires during cold startup
         * (`ColumbaApplication.onCreate` → `reticulumProtocol.initialize`). If
         * we tried to navigate to onboarding in-process, OnboardingViewModel's
         * `completeOnboarding` would find no active identity in Room and the
         * user's chosen display name would silently drop on the floor. Killing
         * the process gives the next launch a clean slate: no active identity,
         * no onboarding flag, native stack creates a fresh identity, the
         * auto-create path persists it to Room, and onboarding runs normally.
         *
         * Messages and contacts tied to the old identity hash are still in Room
         * after the row is deleted, but the UI filters conversations by active
         * identity — they won't render and the user effectively starts empty.
         */
        fun startFresh() {
            viewModelScope.launch {
                _uiState.value = IdentityUnlockUiState.Loading("Starting fresh...")
                val active = identityRepository.getActiveIdentitySync()
                if (active != null) {
                    identityRepository
                        .deleteIdentity(active.identityHash)
                        .onFailure { e ->
                            // If the row deletion fails and we press on to the
                            // restart, the undecryptable identity stays active
                            // and the next launch routes right back to this
                            // screen — a silent infinite loop for the user.
                            _uiState.value =
                                IdentityUnlockUiState.Error(
                                    "Couldn't remove old identity: ${e.message}",
                                )
                            return@launch
                        }
                }
                settingsRepository.clearOnboardingCompleted()
                settingsRepository.setNeedsIdentityUnlock(false)
                _uiState.value = IdentityUnlockUiState.StartedFresh
                restartApp()
            }
        }

        private fun restartApp() {
            // Target MainActivity by explicit ComponentName, not
            // `getLaunchIntentForPackage`. Debug builds add TestHostActivity as
            // a MAIN/LAUNCHER for instrumented tests, and the package manager
            // is free to resolve either — we'd land on an empty test harness
            // roughly half the time otherwise.
            val launchIntent =
                android.content.Intent().apply {
                    setClassName(context, "network.columba.app.MainActivity")
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                }
            context.startActivity(launchIntent)
            Log.d(TAG, "Killing process to complete Start Fresh clean slate")
            android.os.Process.killProcess(android.os.Process.myPid())
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
        val destHash: String,
    ) : IdentityUnlockUiState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HashMismatch) return false
            return importedHash == other.importedHash &&
                activeHash == other.activeHash &&
                keyData.contentEquals(other.keyData) &&
                destHash == other.destHash
        }

        override fun hashCode(): Int {
            var result = importedHash.hashCode()
            result = 31 * result + activeHash.hashCode()
            result = 31 * result + keyData.contentHashCode()
            result = 31 * result + destHash.hashCode()
            return result
        }
    }

    object Restored : IdentityUnlockUiState()

    object StartedFresh : IdentityUnlockUiState()
}
