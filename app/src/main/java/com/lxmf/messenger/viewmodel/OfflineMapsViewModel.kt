package com.lxmf.messenger.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.MapLibreOfflineManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val isResettingCache: Boolean = false,
    val cacheResetMessage: String? = null,
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
        private val mapLibreOfflineManager: MapLibreOfflineManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "OfflineMapsViewModel"
        }

        private val _errorMessage = MutableStateFlow<String?>(null)
        private val _isDeleting = MutableStateFlow(false)
        private val _isResettingCache = MutableStateFlow(false)
        private val _cacheResetMessage = MutableStateFlow<String?>(null)
        private val _updateCheckResults = MutableStateFlow<Map<Long, UpdateCheckResult>>(emptyMap())
        private val _latestTileVersion = MutableStateFlow<String?>(null)

        init {
            // Scan for orphaned files on startup (legacy MBTiles and MapLibre regions)
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
                combine(
                    _updateCheckResults,
                    _latestTileVersion,
                    _isResettingCache,
                    _cacheResetMessage,
                ) { a, b, c, d -> Triple(a to b, c, d) },
            ) { regions, totalStorage, error, isDeleting, (updatePair, isResetting, resetMsg) ->
                val (updateResults, latestVersion) = updatePair
                OfflineMapsState(
                    regions = regions,
                    totalStorageBytes = totalStorage ?: 0L,
                    isLoading = false,
                    isDeleting = isDeleting,
                    isResettingCache = isResetting,
                    cacheResetMessage = resetMsg,
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
         * Also deletes the associated MapLibre region or legacy MBTiles file.
         */
        fun deleteRegion(region: OfflineMapRegion) {
            viewModelScope.launch {
                _isDeleting.value = true
                try {
                    // Delete MapLibre region if present (new OfflineManager API)
                    region.maplibreRegionId?.let { maplibreId ->
                        Log.d(TAG, "Deleting MapLibre region: $maplibreId")
                        mapLibreOfflineManager.deleteRegion(maplibreId) { success ->
                            if (!success) {
                                Log.w(TAG, "Failed to delete MapLibre region: $maplibreId")
                            }
                        }
                    }

                    // Delete the legacy MBTiles file if present
                    region.mbtilesPath?.let { path ->
                        val file = File(path)
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Failed to delete MBTiles file at $path")
                        }
                    }

                    // Delete from database
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
         * Clear the cache reset message.
         */
        fun clearCacheResetMessage() {
            _cacheResetMessage.value = null
        }

        /**
         * Clear MapLibre's ambient tile cache.
         *
         * This removes tiles cached during regular map browsing without affecting
         * downloaded offline regions. Fixes map rendering issues caused by corrupted
         * ambient cache entries (#354).
         */
        fun clearMapTileCache() {
            _isResettingCache.value = true
            mapLibreOfflineManager.clearAmbientCache { success ->
                _isResettingCache.value = false
                _cacheResetMessage.value =
                    if (success) {
                        "Map tile cache cleared. Return to the map to reload."
                    } else {
                        "Failed to clear map tile cache."
                    }
            }
        }

        /**
         * Reset MapLibre's entire offline database.
         *
         * WARNING: This deletes ALL data including downloaded offline regions.
         * All offline regions will need to be re-downloaded. Also clears the Columba
         * database records for consistency.
         *
         * Use as a last resort when clearMapTileCache doesn't resolve rendering issues (#354).
         */
        fun resetMapDatabase() {
            _isResettingCache.value = true
            mapLibreOfflineManager.resetDatabase { success ->
                if (success) {
                    // Also clear Columba's tracking records since the MapLibre regions are gone
                    viewModelScope.launch {
                        try {
                            val regions = offlineMapRegionRepository.getAllRegions().first()
                            for (region in regions) {
                                offlineMapRegionRepository.deleteRegion(region.id)
                            }
                            // Clean up cached style JSON files
                            val styleDir = File(context.filesDir, "offline_styles")
                            if (styleDir.exists()) {
                                styleDir.listFiles()?.forEach { it.delete() }
                            }
                            Log.d(TAG, "Cleared ${regions.size} region records and cached styles")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to clean up region records", e)
                        }
                        _isResettingCache.value = false
                        _cacheResetMessage.value =
                            "Map database reset. All offline maps have been removed and will need to be re-downloaded."
                    }
                } else {
                    _isResettingCache.value = false
                    _cacheResetMessage.value = "Failed to reset map database."
                }
            }
        }

        /**
         * Check if updates are available for a specific region.
         *
         * Note: With MapLibre's OfflineManager, "updating" a region means re-downloading it.
         * This method checks if the region was downloaded with an older tile version.
         */
        fun checkForUpdates(region: OfflineMapRegion) {
            viewModelScope.launch {
                // Mark as checking
                _updateCheckResults.value = _updateCheckResults.value + (
                    region.id to
                        UpdateCheckResult(
                            regionId = region.id,
                            currentVersion = region.tileVersion,
                            latestVersion = null,
                            isChecking = true,
                        )
                )

                try {
                    // For MapLibre regions, we can invalidate them to refresh tiles
                    // For now, just indicate that updates require re-downloading
                    // Version tracking not supported with OfflineManager
                    _updateCheckResults.value = _updateCheckResults.value + (
                        region.id to
                            UpdateCheckResult(
                                regionId = region.id,
                                currentVersion = region.tileVersion,
                                latestVersion = null,
                                isChecking = false,
                            )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check for updates", e)
                    _updateCheckResults.value = _updateCheckResults.value + (
                        region.id to
                            UpdateCheckResult(
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
         * Get the offline maps directory (for legacy MBTiles files).
         */
        fun getOfflineMapsDir(): File {
            return File(context.filesDir, "offline_maps").also { it.mkdirs() }
        }

        /**
         * Scan for orphaned files and clean up.
         *
         * This handles:
         * 1. Legacy MBTiles files not tracked in the database
         * 2. MapLibre regions in the database but not in MapLibre's storage
         * 3. MapLibre regions in MapLibre's storage but not in the database
         */
        private suspend fun recoverOrphanedFiles() {
            try {
                // 1. Recover orphaned MBTiles files (legacy)
                val orphanedFiles = offlineMapRegionRepository.findOrphanedFiles(getOfflineMapsDir())
                for (file in orphanedFiles) {
                    Log.i(TAG, "Recovering orphaned MBTiles file: ${file.name}")
                    offlineMapRegionRepository.importOrphanedFile(file)
                }
                if (orphanedFiles.isNotEmpty()) {
                    Log.i(TAG, "Recovered ${orphanedFiles.size} orphaned MBTiles file(s)")
                }

                // 2. Clean up orphaned MapLibre regions (in DB but not in MapLibre)
                // Note: This is handled by checking region status when listing
                // MapLibre regions are auto-deleted if they become corrupted

                // 3. Log MapLibre regions for debugging
                mapLibreOfflineManager.listRegions { regions ->
                    Log.d(TAG, "MapLibre has ${regions.size} offline regions")
                    regions.forEach { region ->
                        Log.d(TAG, "  - ${region.name} (ID: ${region.id})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover orphaned files", e)
            }
        }
    }
