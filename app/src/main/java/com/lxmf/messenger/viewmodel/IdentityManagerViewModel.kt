package com.lxmf.messenger.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        private val reticulumProtocol: ReticulumProtocol,
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
         * Create a new identity with the given display name.
         */
        fun createNewIdentity(displayName: String) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "createNewIdentity: Starting creation")
                    _uiState.value = IdentityManagerUiState.Loading("Creating identity...")

                    // Call Python service to create identity file
                    Log.d(TAG, "createNewIdentity: Calling Python service...")
                    val result = reticulumProtocol.createIdentityWithName(displayName)
                    Log.d(TAG, "createNewIdentity: Python service returned: ${result.keys}")

                    if (result.containsKey("error")) {
                        val error = result["error"] as? String ?: "Unknown error"
                        Log.e(TAG, "createNewIdentity: Python service error: $error")
                        _uiState.value = IdentityManagerUiState.Error(error)
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
                        _uiState.value = IdentityManagerUiState.Success("Identity created successfully")
                    } else {
                        val error = createResult.exceptionOrNull()?.message ?: "Unknown database error"
                        Log.e(TAG, "createNewIdentity: Database save FAILED: $error")
                        _uiState.value = IdentityManagerUiState.Error(error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "createNewIdentity: Exception caught", e)
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to create identity",
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
        @Suppress("LongMethod") // Identity switch requires coordinated file and service operations
        fun switchToIdentity(identityHash: String) {
            viewModelScope.launch {
                try {
                    _uiState.value = IdentityManagerUiState.Loading("Switching identity...")

                    // Check if identity file exists and recover if needed
                    val identity = identityRepository.getIdentity(identityHash)
                    if (identity != null) {
                        val identityFile = java.io.File(identity.filePath)
                        val keyDataBackup = identity.keyData
                        if (!identityFile.exists() && keyDataBackup != null) {
                            Log.d(TAG, "Identity file missing, attempting recovery from backup keyData...")
                            _uiState.value = IdentityManagerUiState.Loading("Recovering identity file...")

                            val recoveryResult =
                                reticulumProtocol.recoverIdentityFile(
                                    identityHash = identityHash,
                                    keyData = keyDataBackup,
                                    filePath = identity.filePath,
                                )

                            val success = recoveryResult["success"] as? Boolean ?: false
                            if (!success) {
                                val error = recoveryResult["error"] as? String ?: "Unknown recovery error"
                                Log.e(TAG, "Failed to recover identity file: $error")
                                _uiState.value =
                                    IdentityManagerUiState.Error(
                                        "Failed to recover identity file: $error",
                                    )
                                return@launch
                            }
                            Log.d(TAG, "Identity file recovered successfully")
                        } else if (!identityFile.exists()) {
                            Log.e(TAG, "Identity file missing and no backup keyData available")
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    "Identity file is missing and cannot be recovered",
                                )
                            return@launch
                        }
                    }

                    // Note: InterfaceConfigManager.applyInterfaceChanges() will use
                    // ensureIdentityFileExists() to verify/recover the identity file
                    // and pass the correct identity_<hash> path to Python.
                    // No need to copy to default_identity anymore.

                    identityRepository
                        .switchActiveIdentity(identityHash)
                        .onSuccess {
                            // Restart service with new identity (no app restart needed)
                            Log.d(TAG, "Identity switched in database, restarting service...")
                            _uiState.value = IdentityManagerUiState.Loading("Restarting service...")

                            interfaceConfigManager
                                .applyInterfaceChanges()
                                .onSuccess {
                                    Log.d(TAG, "Service restarted with new identity")
                                    _uiState.value = IdentityManagerUiState.Success("Identity switched successfully")
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to restart service", e)
                                    _uiState.value =
                                        IdentityManagerUiState.Error(
                                            "Identity switched but service restart failed: ${e.message}",
                                        )
                                }
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to switch identity",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to switch identity",
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
                    _uiState.value = IdentityManagerUiState.Loading("Deleting identity...")

                    // Check if this is the active identity
                    val active = activeIdentity.value
                    if (active?.identityHash == identityHash) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                "Cannot delete the active identity. Switch to another identity first.",
                            )
                        return@launch
                    }

                    // Delete identity file via Python service
                    val deleteResult = reticulumProtocol.deleteIdentityFile(identityHash)

                    if (deleteResult["success"] != true) {
                        val error = deleteResult["error"] as? String ?: "Unknown error"
                        _uiState.value = IdentityManagerUiState.Error("Failed to delete identity file: $error")
                        return@launch
                    }

                    // Delete from database (cascade delete will remove associated data)
                    identityRepository
                        .deleteIdentity(identityHash)
                        .onSuccess {
                            _uiState.value = IdentityManagerUiState.Success("Identity deleted successfully")
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to delete identity from database",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to delete identity",
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
                    _uiState.value = IdentityManagerUiState.Loading("Renaming identity...")

                    identityRepository
                        .updateDisplayName(identityHash, newName)
                        .onSuccess {
                            _uiState.value = IdentityManagerUiState.Success("Identity renamed successfully")
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to rename identity",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to rename identity",
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
                    _uiState.value = IdentityManagerUiState.Loading("Importing identity...")

                    // Read file data
                    val fileData =
                        try {
                            context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                                ?: throw Exception("Failed to read file")
                        } catch (e: Exception) {
                            throw Exception("Failed to read identity file: ${e.message}")
                        }

                    // Import via Python service
                    val result = reticulumProtocol.importIdentityFile(fileData, displayName)

                    if (result.containsKey("error")) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                result["error"] as? String ?: "Unknown error",
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
                                "Identity already exists as \"${existingIdentity.displayName}\"",
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
                            _uiState.value = IdentityManagerUiState.Success("Identity imported successfully")
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to save imported identity",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to import identity",
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
                    _uiState.value = IdentityManagerUiState.Loading("Exporting identity...")

                    // Export via Python service
                    val fileData = reticulumProtocol.exportIdentityFile(identityHash, filePath)

                    if (fileData.isEmpty()) {
                        _uiState.value = IdentityManagerUiState.Error("Failed to read identity file")
                        return@launch
                    }

                    // Create shareable URI
                    identityRepository
                        .exportIdentity(identityHash, fileData)
                        .onSuccess { uri ->
                            _uiState.value = IdentityManagerUiState.ExportReady(uri)
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to create shareable file",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to export identity",
                        )
                }
            }
        }

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
         * Reset UI state to idle.
         */
        fun resetUiState() {
            _uiState.value = IdentityManagerUiState.Idle
        }
    }

/**
 * UI state for the Identity Manager screen.
 */
sealed class IdentityManagerUiState {
    object Idle : IdentityManagerUiState()

    data class Loading(
        val message: String,
    ) : IdentityManagerUiState()

    data class Success(
        val message: String,
    ) : IdentityManagerUiState()

    data class Error(
        val message: String,
    ) : IdentityManagerUiState()

    object RequiresRestart : IdentityManagerUiState()

    data class ExportReady(
        val uri: Uri,
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
