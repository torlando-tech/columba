package com.lxmf.messenger.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.TileDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Result of checking for updates for a region.
 */
@Immutable
data class UpdateCheckResult(
    val regionId: Long,
    val currentVersion: String?,
    val latestVersion: String?,
    val isChecking: Boolean = false,
    val error: String? = null,
) {
    val hasUpdate: Boolean
        get() = latestVersion != null && currentVersion != null && latestVersion != currentVersion
}

/**
 * UI state for the Offline Maps screen.
 */
@Immutable
data class OfflineMapsState(
    val regions: List<OfflineMapRegion> = emptyList(),
    val totalStorageBytes: Long = 0L,
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
    val updateCheckResults: Map<Long, UpdateCheckResult> = emptyMap(),
    val latestTileVersion: String? = null,
) {
    /**
     * Get a human-readable total storage string.
     */
    fun getTotalStorageString(): String {
        return when {
            totalStorageBytes < 1024 -> "$totalStorageBytes B"
            totalStorageBytes < 1024 * 1024 -> "${totalStorageBytes / 1024} KB"
            totalStorageBytes < 1024 * 1024 * 1024 -> "${totalStorageBytes / (1024 * 1024)} MB"
            else -> "%.1f GB".format(totalStorageBytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/**
 * ViewModel for managing offline map regions.
 *
 * Provides:
 * - List of all offline regions
 * - Total storage usage
 * - Delete functionality
 */
@HiltViewModel
class OfflineMapsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val offlineMapRegionRepository: OfflineMapRegionRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "OfflineMapsViewModel"
        }

        private val _errorMessage = MutableStateFlow<String?>(null)
        private val _isDeleting = MutableStateFlow(false)
        private val _updateCheckResults = MutableStateFlow<Map<Long, UpdateCheckResult>>(emptyMap())
        private val _latestTileVersion = MutableStateFlow<String?>(null)

        init {
            // Scan for orphaned MBTiles files on startup
            viewModelScope.launch {
                recoverOrphanedFiles()
            }
        }

        val state: StateFlow<OfflineMapsState> =
            combine(
                offlineMapRegionRepository.getAllRegions(),
                offlineMapRegionRepository.getTotalStorageUsed(),
                _errorMessage,
                _isDeleting,
                combine(_updateCheckResults, _latestTileVersion) { a, b -> a to b },
            ) { regions, totalStorage, error, isDeleting, (updateResults, latestVersion) ->
                OfflineMapsState(
                    regions = regions,
                    totalStorageBytes = totalStorage ?: 0L,
                    isLoading = false,
                    isDeleting = isDeleting,
                    errorMessage = error,
                    updateCheckResults = updateResults,
                    latestTileVersion = latestVersion,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = OfflineMapsState(),
            )

        /**
         * Delete an offline map region.
         * Also deletes the associated MBTiles file.
         */
        fun deleteRegion(region: OfflineMapRegion) {
            viewModelScope.launch {
                _isDeleting.value = true
                try {
                    // Delete the MBTiles file first - if this fails, keep the DB record for retry
                    region.mbtilesPath?.let { path ->
                        val file = File(path)
                        if (file.exists() && !file.delete()) {
                            error("Failed to delete MBTiles file at $path")
                        }
                    }

                    // Delete from database only after file is successfully removed
                    offlineMapRegionRepository.deleteRegion(region.id)
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to delete region: ${e.message}"
                } finally {
                    _isDeleting.value = false
                }
            }
        }

        /**
         * Retry a failed download.
         *
         * @param region The region to retry downloading
         */
        fun retryDownload(region: OfflineMapRegion) {
            // Navigate to download screen with pre-filled parameters
            Log.d(TAG, "Retry download requested for region: ${region.name}")
        }

        /**
         * Clear the error message.
         */
        fun clearError() {
            _errorMessage.value = null
        }

        /**
         * Check if updates are available for a specific region.
         */
        fun checkForUpdates(region: OfflineMapRegion) {
            viewModelScope.launch {
                // Mark as checking
                _updateCheckResults.value = _updateCheckResults.value + (
                    region.id to UpdateCheckResult(
                        regionId = region.id,
                        currentVersion = region.tileVersion,
                        latestVersion = null,
                        isChecking = true,
                    )
                )

                try {
                    val latestVersion = TileDownloadManager.fetchCurrentTileVersion()
                    _latestTileVersion.value = latestVersion

                    _updateCheckResults.value = _updateCheckResults.value + (
                        region.id to UpdateCheckResult(
                            regionId = region.id,
                            currentVersion = region.tileVersion,
                            latestVersion = latestVersion,
                            isChecking = false,
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check for updates", e)
                    _updateCheckResults.value = _updateCheckResults.value + (
                        region.id to UpdateCheckResult(
                            regionId = region.id,
                            currentVersion = region.tileVersion,
                            latestVersion = null,
                            isChecking = false,
                            error = e.message,
                        )
                    )
                }
            }
        }

        /**
         * Clear the update check result for a region.
         */
        fun clearUpdateCheckResult(regionId: Long) {
            _updateCheckResults.value = _updateCheckResults.value - regionId
        }

        /**
         * Get the offline maps directory.
         */
        fun getOfflineMapsDir(): File {
            return TileDownloadManager.getOfflineMapsDir(context)
        }

        /**
         * Scan for orphaned MBTiles files and import them into the database.
         */
        private suspend fun recoverOrphanedFiles() {
            try {
                val orphanedFiles = offlineMapRegionRepository.findOrphanedFiles(getOfflineMapsDir())
                for (file in orphanedFiles) {
                    Log.i(TAG, "Recovering orphaned map file: ${file.name}")
                    offlineMapRegionRepository.importOrphanedFile(file)
                }
                if (orphanedFiles.isNotEmpty()) {
                    Log.i(TAG, "Recovered ${orphanedFiles.size} orphaned map file(s)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover orphaned files", e)
            }
        }
    }
