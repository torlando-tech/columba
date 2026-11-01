package network.columba.app.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.columba.app.R
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.util.Base32
import java.util.zip.GZIPInputStream
import javax.inject.Inject

private const val TAG = "IdentityManagerVM"

/**
 * ViewModel for the Identity Manager screen.
 * Handles creation, deletion, switching, importing, and exporting of identities.
 */
@HiltViewModel
class IdentityManagerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityRepository: IdentityRepository,
        private val identityKeyProvider: network.columba.app.data.crypto.IdentityKeyProvider,
        private val rnsCore: RnsCore,
        private val interfaceConfigManager: InterfaceConfigManager,
    ) : ViewModel() {
        /**
         * All identities, ordered by last used timestamp.
         */
        val identities: StateFlow<List<LocalIdentityEntity>> =
            identityRepository.allIdentities.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

        /**
         * Currently active identity.
         */
        val activeIdentity: StateFlow<LocalIdentityEntity?> =
            identityRepository.activeIdentity.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )

        /**
         * UI state for the Identity Manager screen.
         */
        private val _uiState = MutableStateFlow<IdentityManagerUiState>(IdentityManagerUiState.Idle)
        val uiState: StateFlow<IdentityManagerUiState> = _uiState.asStateFlow()

        /**
         * URI of the exported identity file (cache), used for SAF save.
         */
        private var _exportedIdentityUri: Uri? = null

        /**
         * Create a new identity with the given display name.
         */
        fun createNewIdentity(displayName: String) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "createNewIdentity: Starting creation")
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_creating))

                    // Call Python service to create identity file
                    Log.d(TAG, "createNewIdentity: Calling Python service...")
                    val result = rnsCore.createIdentityWithName(displayName)
                    Log.d(TAG, "createNewIdentity: Python service returned: ${result.keys}")

                    if (result.containsKey("error")) {
                        val error = result["error"] as? String
                        Log.e(TAG, "createNewIdentity: Python service error: $error")
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                error?.let { UiMessage.Raw(it) } ?: UiMessage.Text(R.string.error_unknown),
                            )
                        return@launch
                    }

                    // Extract identity info from Python result
                    val identityHash =
                        result["identity_hash"] as? String
                            ?: throw Exception("No identity_hash in result")
                    val destinationHash =
                        result["destination_hash"] as? String
                            ?: throw Exception("No destination_hash in result")
                    val filePath =
                        result["file_path"] as? String
                            ?: throw Exception("No file_path in result")
                    val keyData =
                        result["key_data"] as? ByteArray

                    Log.d(
                        TAG,
                        "createNewIdentity: Extracted - hash: ${identityHash.take(8)}..., " +
                            "dest: ${destinationHash.take(8)}..., path: $filePath, " +
                            "keyData: ${keyData?.size ?: 0} bytes",
                    )

                    // Save to database
                    Log.d(TAG, "createNewIdentity: Saving to database...")
                    val createResult =
                        identityRepository.createIdentity(
                            identityHash = identityHash,
                            displayName = displayName,
                            destinationHash = destinationHash,
                            filePath = filePath,
                            keyData = keyData,
                        )

                    if (createResult.isSuccess) {
                        Log.d(TAG, "createNewIdentity: Database save SUCCESS")
                        _uiState.value = IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_created))
                    } else {
                        val error =
                            createResult.exceptionOrNull().toUiMessage(R.string.identity_unknown_database_error)
                        Log.e(TAG, "createNewIdentity: Database save FAILED: $error")
                        _uiState.value = IdentityManagerUiState.Error(error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "createNewIdentity: Exception caught", e)
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_create_failed),
                        )
                }
            }
        }

        /**
         * Switch to a different identity.
         * This restarts the service process with the new identity - no app restart needed.
         * The service runs in a separate process, so we reuse InterfaceConfigManager
         * which handles the full service restart lifecycle.
         *
         * If the identity file is missing but keyData is available in the database,
         * it will be recovered automatically.
         */
        fun switchToIdentity(identityHash: String) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_switching))

                    // The identity's private key lives Keystore-wrapped in Room
                    // (LocalIdentityEntity.keyData / encryptedKeyData).
                    // InterfaceConfigManager.applyInterfaceChanges decrypts it
                    // on-demand and passes it to the native stack via
                    // ReticulumConfig.deliveryIdentityKey — no plaintext file
                    // has to exist on disk, so no recovery step here.

                    identityRepository
                        .switchActiveIdentity(identityHash)
                        .onSuccess {
                            // Restart service with new identity (no app restart needed)
                            Log.d(TAG, "Identity switched in database, restarting service...")
                            _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_restarting_service))

                            interfaceConfigManager
                                .applyInterfaceChanges(
                                    onServiceReady = {
                                        // Flip the spinner to Success once RNS is
                                        // up with the new identity (Step 9), not
                                        // when the full apply (Steps 10-12) finishes.
                                        // Step 10 batch-restores peer identities,
                                        // which can take minutes on devices with
                                        // thousands of stored peers — blocking the
                                        // identity-switch UI on that bookkeeping
                                        // makes the spinner look hung even though
                                        // the user can already act on the new
                                        // identity. Same fix pattern as the Manage
                                        // Interfaces Apply button (32e6549f /
                                        // 8b39a66b).
                                        Log.d(TAG, "Service restarted with new identity (Step 9 complete)")
                                        _uiState.value = IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_switched))
                                    },
                                ).onSuccess {
                                    // Defense-in-depth: re-set Success in case
                                    // onServiceReady didn't fire (shouldn't happen
                                    // on the success path but harmless to idempotently
                                    // re-assert).
                                    Log.d(TAG, "Service restart fully complete")
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to restart service", e)
                                    _uiState.value =
                                        IdentityManagerUiState.Error(
                                            UiMessage.Text(
                                                R.string.identity_switch_restart_failed,
                                                listOf(e.toUiMessage(R.string.identity_switch_failed)),
                                            ),
                                        )
                                }
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_switch_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_switch_failed),
                        )
                }
            }
        }

        /**
         * Delete an identity.
         * Cannot delete the active identity.
         */
        fun deleteIdentity(identityHash: String) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_deleting))

                    // Check if this is the active identity
                    val active = activeIdentity.value
                    if (active?.identityHash == identityHash) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                UiMessage.Text(R.string.identity_delete_active_blocked),
                            )
                        return@launch
                    }

                    // Delete from database (cascade delete will remove associated data).
                    // No disk-side cleanup: delivery keys live in Room now, not in
                    // reticulum/identities/ — stale legacy files (if any) are scrubbed
                    // at cold-start in ColumbaApplication.
                    identityRepository
                        .deleteIdentity(identityHash)
                        .onSuccess {
                            _uiState.value = IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_deleted))
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_delete_db_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_delete_failed),
                        )
                }
            }
        }

        /**
         * Rename an identity.
         */
        fun renameIdentity(
            identityHash: String,
            newName: String,
        ) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_renaming))

                    identityRepository
                        .updateDisplayName(identityHash, newName)
                        .onSuccess {
                            _uiState.value = IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_renamed))
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_rename_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_rename_failed),
                        )
                }
            }
        }

        /**
         * Import an identity from a file URI.
         */
        fun importIdentity(
            fileUri: Uri,
            displayName: String,
        ) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_importing))

                    // Read file data
                    val fileData =
                        try {
                            context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                        } catch (e: Exception) {
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    UiMessage.Text(R.string.identity_read_file_error, listOf(e.message ?: "null")),
                                )
                            return@launch
                        } ?: run {
                            _uiState.value =
                                IdentityManagerUiState.Error(UiMessage.Text(R.string.identity_read_file_failed))
                            return@launch
                        }

                    // Import via Python service
                    val result = rnsCore.importIdentityFile(fileData, displayName)

                    if (result.containsKey("error")) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                (result["error"] as? String)?.let { UiMessage.Raw(it) }
                                    ?: UiMessage.Text(R.string.error_unknown),
                            )
                        return@launch
                    }

                    // Extract identity info
                    val identityHash =
                        result["identity_hash"] as? String
                            ?: throw Exception("No identity_hash in result")
                    val destinationHash =
                        result["destination_hash"] as? String
                            ?: throw Exception("No destination_hash in result")
                    val filePath =
                        result["file_path"] as? String
                            ?: throw Exception("No file_path in result")
                    val keyData =
                        result["key_data"] as? ByteArray

                    // Check if identity already exists
                    val existingIdentities = identities.value
                    val existingIdentity = existingIdentities.find { it.identityHash == identityHash }
                    if (existingIdentity != null) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                UiMessage.Text(
                                    R.string.identity_already_exists,
                                    listOf(existingIdentity.displayName),
                                ),
                            )
                        return@launch
                    }

                    // Save to database
                    identityRepository
                        .importIdentity(
                            identityHash = identityHash,
                            displayName = displayName,
                            destinationHash = destinationHash,
                            filePath = filePath,
                            keyData = keyData,
                        ).onSuccess {
                            _uiState.value = IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_imported))
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_save_imported_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_import_failed),
                        )
                }
            }
        }

        /**
         * Export an identity to a shareable file.
         */
        fun exportIdentity(
            identityHash: String,
            filePath: String,
        ) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_exporting))

                    // Decrypt the Keystore-wrapped key, hand it to the native
                    // protocol which just writes it to the user-chosen scratch
                    // path. No plaintext file touches reticulum/identities/.
                    val keyData =
                        identityKeyProvider.getDecryptedKeyData(identityHash).getOrElse { error ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    UiMessage.Text(R.string.identity_decrypt_key_failed, listOf(error.message ?: "null")),
                                )
                            return@launch
                        }
                    val fileData = rnsCore.exportIdentityFile(keyData, filePath)

                    if (fileData.isEmpty()) {
                        _uiState.value = IdentityManagerUiState.Error(UiMessage.Text(R.string.identity_export_failed))
                        return@launch
                    }

                    // Create shareable URI
                    identityRepository
                        .exportIdentity(identityHash, fileData)
                        .onSuccess { uri ->
                            _exportedIdentityUri = uri
                            _uiState.value = IdentityManagerUiState.ExportReady(uri)
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_create_shareable_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_export_failed),
                        )
                }
            }
        }

        /**
         * Import an identity from a Base32-encoded text string.
         *
         * This is the primary import path for Sideband interoperability.
         * Sideband shares identity keys as Base32 text via Android's share sheet.
         */
        fun importIdentityFromBase32(
            base32Text: String,
            displayName: String,
        ) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_importing))

                    // Decode Base32 to raw bytes
                    val fileData =
                        try {
                            Base32.decode(base32Text.trim())
                        } catch (e: IllegalArgumentException) {
                            throw IdentityFileInvalidException(
                                UiMessage.Text(R.string.identity_invalid_base32_key, listOf(e.message ?: "null")),
                                e,
                            )
                        }

                    if (fileData.size != 64) {
                        throw IdentityFileInvalidException(
                            UiMessage.Text(R.string.identity_invalid_key_size, listOf(fileData.size)),
                        )
                    }

                    // Import via Python service (same path as file import)
                    val result = rnsCore.importIdentityFile(fileData, displayName)

                    if (result.containsKey("error")) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                (result["error"] as? String)?.let { UiMessage.Raw(it) }
                                    ?: UiMessage.Text(R.string.error_unknown),
                            )
                        return@launch
                    }

                    // Extract identity info
                    val identityHash = checkNotNull(result["identity_hash"] as? String) { "No identity_hash in result" }
                    val destinationHash = checkNotNull(result["destination_hash"] as? String) { "No destination_hash in result" }
                    val filePath = checkNotNull(result["file_path"] as? String) { "No file_path in result" }
                    val keyData =
                        result["key_data"] as? ByteArray

                    // Check if identity already exists
                    val existingIdentity = identities.value.find { it.identityHash == identityHash }
                    if (existingIdentity != null) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                UiMessage.Text(
                                    R.string.identity_already_exists,
                                    listOf(existingIdentity.displayName),
                                ),
                            )
                        return@launch
                    }

                    // Save to database
                    identityRepository
                        .importIdentity(
                            identityHash = identityHash,
                            displayName = displayName,
                            destinationHash = destinationHash,
                            filePath = filePath,
                            keyData = keyData,
                        ).onSuccess {
                            _uiState.value =
                                IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_imported))
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_save_imported_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            (e as? IdentityFileInvalidException)?.uiMessage
                                ?: e.toUiMessage(R.string.identity_import_text_failed),
                        )
                }
            }
        }

        /**
         * Export an identity as a Base32 text string for sharing with Sideband.
         */
        fun exportIdentityAsText(
            identityHash: String,
            filePath: String,
        ) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_exporting))

                    val keyData =
                        identityKeyProvider.getDecryptedKeyData(identityHash).getOrElse { error ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    UiMessage.Text(R.string.identity_decrypt_key_failed, listOf(error.message ?: "null")),
                                )
                            return@launch
                        }
                    val fileData =
                        withContext(Dispatchers.IO) {
                            rnsCore.exportIdentityFile(keyData, filePath)
                        }

                    if (fileData.isEmpty()) {
                        _uiState.value =
                            IdentityManagerUiState.Error(UiMessage.Text(R.string.identity_export_failed))
                        return@launch
                    }

                    val base32String = Base32.encode(fileData)
                    _uiState.value = IdentityManagerUiState.ExportTextReady(base32String)
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.toUiMessage(R.string.identity_export_text_failed),
                        )
                }
            }
        }

        /**
         * Import an identity from a Sideband tar.gz backup file.
         *
         * Sideband backups contain `Sideband Backup/primary_identity` which is the
         * raw 64-byte identity file.
         */
        @Suppress("NestedBlockDepth") // tar.gz extraction requires nested stream handling
        fun importIdentityFromBackup(
            fileUri: Uri,
            displayName: String,
        ) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_extracting_backup))

                    // Read and extract identity from tar.gz (I/O off main thread)
                    val fileData =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                GZIPInputStream(inputStream).use { gzipStream ->
                                    extractIdentityFromTar(gzipStream)
                                }
                            } ?: throw IdentityFileInvalidException(
                                UiMessage.Text(R.string.identity_open_backup_failed),
                            )
                        }

                    if (fileData.size != 64) {
                        throw IdentityFileInvalidException(
                            UiMessage.Text(R.string.identity_invalid_backup_key_size, listOf(fileData.size)),
                        )
                    }

                    // Import via Python service (same path as file import)
                    _uiState.value = IdentityManagerUiState.Loading(UiMessage.Text(R.string.identity_importing))
                    val result = rnsCore.importIdentityFile(fileData, displayName)

                    if (result.containsKey("error")) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                (result["error"] as? String)?.let { UiMessage.Raw(it) }
                                    ?: UiMessage.Text(R.string.error_unknown),
                            )
                        return@launch
                    }

                    // Extract identity info
                    val identityHash = checkNotNull(result["identity_hash"] as? String) { "No identity_hash in result" }
                    val destinationHash = checkNotNull(result["destination_hash"] as? String) { "No destination_hash in result" }
                    val resultFilePath = checkNotNull(result["file_path"] as? String) { "No file_path in result" }
                    val keyData =
                        result["key_data"] as? ByteArray

                    // Check if identity already exists
                    val existingIdentity = identities.value.find { it.identityHash == identityHash }
                    if (existingIdentity != null) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                UiMessage.Text(
                                    R.string.identity_already_exists,
                                    listOf(existingIdentity.displayName),
                                ),
                            )
                        return@launch
                    }

                    // Save to database
                    identityRepository
                        .importIdentity(
                            identityHash = identityHash,
                            displayName = displayName,
                            destinationHash = destinationHash,
                            filePath = resultFilePath,
                            keyData = keyData,
                        ).onSuccess {
                            _uiState.value =
                                IdentityManagerUiState.Success(
                                    UiMessage.Text(R.string.identity_imported_from_backup),
                                )
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.toUiMessage(R.string.identity_save_imported_failed),
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            (e as? IdentityFileInvalidException)?.uiMessage
                                ?: e.toUiMessage(R.string.identity_import_backup_failed),
                        )
                }
            }
        }

        /**
         * Extract the primary_identity file from a Sideband tar archive.
         *
         * Sideband backup tars use `arcname="Sideband Backup"`, so the identity
         * file is at path `Sideband Backup/primary_identity`.
         */
        private fun extractIdentityFromTar(inputStream: java.io.InputStream): ByteArray = TarIdentityExtractor.extract(inputStream)

        /**
         * Get statistics for an identity (for the delete confirmation dialog).
         */
        fun getIdentityStats(identityHash: String): IdentityStats {
            // Future: Query conversation/contact/message counts for this identity
            return IdentityStats(
                conversationCount = 0,
                contactCount = 0,
                messageCount = 0,
            )
        }

        /**
         * Save the exported identity file to a user-chosen destination via SAF.
         */
        fun saveExportedIdentityToFile(destinationUri: Uri) {
            val sourceUri = _exportedIdentityUri ?: return
            viewModelScope.launch {
                val copied =
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                                    input.copyTo(output)
                                }
                            } != null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save identity file", e)
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                UiMessage.Text(R.string.identity_save_failed, listOf(e.message ?: "null")),
                            )
                        return@launch
                    }
                if (!copied) {
                    _uiState.value =
                        IdentityManagerUiState.Error(UiMessage.Text(R.string.identity_open_file_failed))
                    return@launch
                }
                _uiState.value =
                    IdentityManagerUiState.Success(UiMessage.Text(R.string.identity_exported))
                _exportedIdentityUri = null
            }
        }

        /**
         * Reset UI state to idle.
         */
        fun resetUiState() {
            _uiState.value = IdentityManagerUiState.Idle
        }

        override fun onCleared() {
            super.onCleared()
            _exportedIdentityUri = null
        }
    }

/**
 * UI state for the Identity Manager screen.
 */
sealed class IdentityManagerUiState {
    object Idle : IdentityManagerUiState()

    data class Loading(
        val message: UiMessage,
    ) : IdentityManagerUiState()

    data class Success(
        val message: UiMessage,
    ) : IdentityManagerUiState()

    data class Error(
        val message: UiMessage,
    ) : IdentityManagerUiState()

    object RequiresRestart : IdentityManagerUiState()

    data class ExportReady(
        val uri: Uri,
    ) : IdentityManagerUiState()

    data class ExportTextReady(
        val base32String: String,
    ) : IdentityManagerUiState()
}

/**
 * Statistics for an identity (used in delete confirmation).
 */
@androidx.compose.runtime.Immutable
data class IdentityStats(
    val conversationCount: Int,
    val contactCount: Int,
    val messageCount: Int,
)
