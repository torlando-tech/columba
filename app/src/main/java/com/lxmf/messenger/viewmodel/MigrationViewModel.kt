package com.lxmf.messenger.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.migration.ExportResult
import com.lxmf.messenger.migration.ImportResult
import com.lxmf.messenger.migration.MigrationCrypto
import com.lxmf.messenger.migration.MigrationExporter
import com.lxmf.messenger.migration.MigrationImporter
import com.lxmf.messenger.migration.MigrationPreview
import com.lxmf.messenger.migration.PasswordRequiredException
import com.lxmf.messenger.migration.WrongPasswordException
import com.lxmf.messenger.service.InterfaceConfigManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "MigrationVM"

/**
 * ViewModel for the Migration screen.
 * Handles data export and import for app migration.
 */
@HiltViewModel
class MigrationViewModel
    @Inject
    constructor(
        private val migrationExporter: MigrationExporter,
        private val migrationImporter: MigrationImporter,
        private val interfaceConfigManager: InterfaceConfigManager,
    ) : ViewModel() {
        /**
         * UI state for the Migration screen.
         */
        private val _uiState = MutableStateFlow<MigrationUiState>(MigrationUiState.Idle)
        val uiState: StateFlow<MigrationUiState> = _uiState.asStateFlow()

        /**
         * Export progress (0.0 to 1.0).
         */
        private val _exportProgress = MutableStateFlow(0f)
        val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

        /**
         * Import progress (0.0 to 1.0).
         */
        private val _importProgress = MutableStateFlow(0f)
        val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

        /**
         * Export preview data.
         */
        private val _exportPreview = MutableStateFlow<ExportResult?>(null)
        val exportPreview: StateFlow<ExportResult?> = _exportPreview.asStateFlow()

        /**
         * Import preview data.
         */
        private val _importPreview = MutableStateFlow<MigrationPreview?>(null)
        val importPreview: StateFlow<MigrationPreview?> = _importPreview.asStateFlow()

        /**
         * URI of the exported file for sharing.
         */
        private val _exportedFileUri = MutableStateFlow<Uri?>(null)
        val exportedFileUri: StateFlow<Uri?> = _exportedFileUri.asStateFlow()

        /**
         * Whether to include file/image attachments in export.
         */
        private val _includeAttachments = MutableStateFlow(true)
        val includeAttachments: StateFlow<Boolean> = _includeAttachments.asStateFlow()

        /**
         * Pending import URI that requires a password.
         */
        private val _pendingImportUri = MutableStateFlow<Uri?>(null)
        val pendingImportUri: StateFlow<Uri?> = _pendingImportUri.asStateFlow()

        init {
            loadExportPreview()
        }

        /**
         * Load a preview of what will be exported.
         */
        fun loadExportPreview() {
            viewModelScope.launch {
                val result = migrationExporter.getExportPreview()
                _exportPreview.value = result
            }
        }

        /**
         * Set whether to include file/image attachments in export.
         */
        fun setIncludeAttachments(include: Boolean) {
            _includeAttachments.value = include
        }

        /**
         * Export all app data to a migration file.
         */
        fun exportData(password: String) {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "Starting export...")
                    _uiState.value = MigrationUiState.Exporting
                    _exportProgress.value = 0f

                    val result =
                        migrationExporter.exportData(
                            password = password,
                            onProgress = { progress -> _exportProgress.value = progress },
                            includeAttachments = _includeAttachments.value,
                        )

                    result.fold(
                        onSuccess = { uri ->
                            Log.i(TAG, "Export completed: $uri")
                            _exportedFileUri.value = uri
                            _uiState.value = MigrationUiState.ExportComplete(uri)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Export failed", error)
                            _uiState.value =
                                MigrationUiState.Error(
                                    "Export failed: ${error.message}",
                                )
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Export failed with exception", e)
                    _uiState.value = MigrationUiState.Error("Export failed: ${e.message}")
                }
            }
        }

        /**
         * Preview a migration file before importing.
         */
        fun previewImport(uri: Uri, password: String? = null) {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "Previewing import: $uri")
                    _uiState.value = MigrationUiState.Loading("Reading migration file...")

                    // Check if file is encrypted and we don't have a password yet
                    if (password == null) {
                        val encryptedResult = migrationImporter.isEncryptedExport(uri)
                        encryptedResult.fold(
                            onSuccess = { isEncrypted ->
                                if (isEncrypted) {
                                    Log.i(TAG, "File is encrypted, requesting password")
                                    _pendingImportUri.value = uri
                                    _uiState.value = MigrationUiState.PasswordRequired(uri)
                                    return@launch
                                }
                            },
                            onFailure = { error ->
                                _uiState.value = MigrationUiState.Error(
                                    "Could not read migration file: ${error.message}",
                                )
                                return@launch
                            },
                        )
                    }

                    val result = migrationImporter.previewMigration(uri, password)

                    result.fold(
                        onSuccess = { preview ->
                            Log.i(TAG, "Preview loaded: ${preview.identityCount} identities")
                            _importPreview.value = preview
                            _pendingImportUri.value = null
                            _uiState.value = MigrationUiState.ImportPreview(preview, uri, password)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Preview failed", error)
                            when (error) {
                                is WrongPasswordException -> {
                                    _uiState.value = MigrationUiState.WrongPassword(uri)
                                }
                                is PasswordRequiredException -> {
                                    _pendingImportUri.value = uri
                                    _uiState.value = MigrationUiState.PasswordRequired(uri)
                                }
                                else -> {
                                    _uiState.value = MigrationUiState.Error(
                                        "Could not read migration file: ${error.message}",
                                    )
                                }
                            }
                        },
                    )
                } catch (e: WrongPasswordException) {
                    Log.w(TAG, "Wrong password for encrypted export", e)
                    _uiState.value = MigrationUiState.WrongPassword(uri)
                } catch (e: PasswordRequiredException) {
                    Log.d(TAG, "Password required for encrypted export", e)
                    _pendingImportUri.value = uri
                    _uiState.value = MigrationUiState.PasswordRequired(uri)
                } catch (e: Exception) {
                    Log.e(TAG, "Preview failed with exception", e)
                    _uiState.value =
                        MigrationUiState.Error(
                            "Could not read migration file: ${e.message}",
                        )
                }
            }
        }

        /**
         * Import data from a migration file.
         */
        fun importData(uri: Uri, password: String? = null) {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "Starting import from: $uri")
                    _uiState.value = MigrationUiState.Importing
                    _importProgress.value = 0f

                    val result =
                        migrationImporter.importData(uri, password) { progress ->
                            _importProgress.value = progress
                        }

                    when (result) {
                        is ImportResult.Success -> {
                            Log.i(
                                TAG,
                                "Import completed: ${result.identitiesImported} identities, " +
                                    "${result.messagesImported} messages",
                            )
                            // Show restarting dialog while service restarts
                            _uiState.value = MigrationUiState.RestartingService(result)

                            // Restart the service to load imported data
                            Log.i(TAG, "Restarting service after import...")
                            withContext(Dispatchers.IO) {
                                interfaceConfigManager.applyInterfaceChanges()
                            }
                                .onSuccess {
                                    Log.i(TAG, "Service restarted successfully")
                                    _uiState.value = MigrationUiState.ImportComplete(result)
                                }
                                .onFailure { e ->
                                    Log.e(TAG, "Service restart failed", e)
                                    // Still mark as complete since import succeeded
                                    _uiState.value = MigrationUiState.ImportComplete(result)
                                }
                        }
                        is ImportResult.Error -> {
                            Log.e(TAG, "Import failed: ${result.message}")
                            _uiState.value = MigrationUiState.Error(result.message)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed with exception", e)
                    _uiState.value = MigrationUiState.Error("Import failed: ${e.message}")
                }
            }
        }

        /**
         * Reset state to idle.
         */
        fun resetState() {
            _uiState.value = MigrationUiState.Idle
            _exportProgress.value = 0f
            _importProgress.value = 0f
            _importPreview.value = null
            _pendingImportUri.value = null
        }

        /**
         * Clean up exported files.
         */
        fun cleanupExportFiles() {
            migrationExporter.cleanupExportFiles()
            _exportedFileUri.value = null
        }

        override fun onCleared() {
            super.onCleared()
            cleanupExportFiles()
        }
    }

/**
 * UI state for the Migration screen.
 */
sealed class MigrationUiState {
    data object Idle : MigrationUiState()

    data class Loading(val message: String) : MigrationUiState()

    data object Exporting : MigrationUiState()

    data class ExportComplete(val fileUri: Uri) : MigrationUiState()

    data class ImportPreview(
        val preview: MigrationPreview,
        val fileUri: Uri,
        val password: String? = null,
    ) : MigrationUiState()

    data class PasswordRequired(val fileUri: Uri) : MigrationUiState()

    data class WrongPassword(val fileUri: Uri) : MigrationUiState()

    data object Importing : MigrationUiState()

    data class RestartingService(val result: ImportResult.Success) : MigrationUiState()

    data class ImportComplete(val result: ImportResult.Success) : MigrationUiState()

    data class Error(val message: String) : MigrationUiState()
}
