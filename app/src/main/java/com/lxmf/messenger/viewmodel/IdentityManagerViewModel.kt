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
import com.lxmf.messenger.util.Base32
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
                    _uiState.value = IdentityManagerUiState.Loading("Importing identity...")

                    // Decode Base32 to raw bytes
                    val fileData =
                        try {
                            Base32.decode(base32Text.trim())
                        } catch (e: IllegalArgumentException) {
                            error("Invalid Base32 key: ${e.message}")
                        }

                    require(fileData.size == 64) {
                        "Invalid identity key: expected 64 bytes, got ${fileData.size}"
                    }

                    // Import via Python service (same path as file import)
                    val result = reticulumProtocol.importIdentityFile(fileData, displayName)

                    if (result.containsKey("error")) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                result["error"] as? String ?: "Unknown error",
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
                            _uiState.value =
                                IdentityManagerUiState.Success("Identity imported successfully")
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to save imported identity",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to import identity from text",
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
                    _uiState.value = IdentityManagerUiState.Loading("Exporting identity...")

                    val fileData = reticulumProtocol.exportIdentityFile(identityHash, filePath)

                    if (fileData.isEmpty()) {
                        _uiState.value =
                            IdentityManagerUiState.Error("Failed to read identity file")
                        return@launch
                    }

                    val base32String = Base32.encode(fileData)
                    _uiState.value = IdentityManagerUiState.ExportTextReady(base32String)
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to export identity as text",
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
                    _uiState.value = IdentityManagerUiState.Loading("Extracting backup...")

                    // Read and extract identity from tar.gz
                    val fileData =
                        checkNotNull(
                            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                GZIPInputStream(inputStream).use { gzipStream ->
                                    extractIdentityFromTar(gzipStream)
                                }
                            },
                        ) { "Failed to open backup file" }

                    require(fileData.size == 64) {
                        "Invalid identity in backup: expected 64 bytes, got ${fileData.size}"
                    }

                    // Import via Python service (same path as file import)
                    _uiState.value = IdentityManagerUiState.Loading("Importing identity...")
                    val result = reticulumProtocol.importIdentityFile(fileData, displayName)

                    if (result.containsKey("error")) {
                        _uiState.value =
                            IdentityManagerUiState.Error(
                                result["error"] as? String ?: "Unknown error",
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
                            filePath = resultFilePath,
                            keyData = keyData,
                        ).onSuccess {
                            _uiState.value =
                                IdentityManagerUiState.Success(
                                    "Identity imported from backup successfully",
                                )
                        }.onFailure { e ->
                            _uiState.value =
                                IdentityManagerUiState.Error(
                                    e.message ?: "Failed to save imported identity",
                                )
                        }
                } catch (e: Exception) {
                    _uiState.value =
                        IdentityManagerUiState.Error(
                            e.message ?: "Failed to import from backup",
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
        private fun extractIdentityFromTar(inputStream: java.io.InputStream): ByteArray {
            // Simple tar parser: read 512-byte headers, look for primary_identity
            val headerBuffer = ByteArray(512)
            while (true) {
                val bytesRead = inputStream.readFully(headerBuffer, 0, 512)
                check(bytesRead == 512) { "Identity not found in backup (unexpected end of archive)" }

                // Check for zero block (end of archive)
                check(!headerBuffer.all { it == 0.toByte() }) { "Identity not found in backup" }

                // Extract filename from header (bytes 0-99, null-terminated)
                val nameEnd =
                    headerBuffer
                        .indexOf(0)
                        .let { if (it < 0 || it > 100) 100 else it }
                val fileName = String(headerBuffer, 0, nameEnd).trim()

                // Extract file size from header (bytes 124-135, octal ASCII)
                val sizeStr = String(headerBuffer, 124, 11).trim()
                val fileSize = sizeStr.toLongOrNull(8) ?: 0L

                // Calculate padded size (tar entries are padded to 512-byte boundaries)
                val paddedSize = ((fileSize + 511) / 512) * 512

                if (fileName.endsWith("primary_identity") || fileName.endsWith("/identity")) {
                    // Found it - read the file data
                    val data = ByteArray(fileSize.toInt())
                    val dataRead = inputStream.readFully(data, 0, fileSize.toInt())
                    check(dataRead == fileSize.toInt()) { "Truncated identity file in backup" }
                    return data
                } else {
                    // Skip this entry's data
                    inputStream.skipFully(paddedSize)
                }
            }
        }

        /** Read exactly [len] bytes into [buf] at [off]. Returns bytes read (< len only at EOF). */
        private fun java.io.InputStream.readFully(
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            var totalRead = 0
            while (totalRead < len) {
                val n = read(buf, off + totalRead, len - totalRead)
                if (n < 0) break
                totalRead += n
            }
            return totalRead
        }

        /** Skip exactly [n] bytes, looping since InputStream.skip() may skip fewer. */
        private fun java.io.InputStream.skipFully(n: Long) {
            var remaining = n
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped == 0L) {
                    // skip() returned 0 — read and discard a byte to make progress
                    if (read() < 0) break
                    remaining--
                } else {
                    remaining -= skipped
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
