package com.lxmf.messenger.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.MapLibreOfflineManager
import com.lxmf.messenger.map.OfflineMapStyleBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val isImporting: Boolean = false,
    val importSuccessMessage: String? = null,
    val errorMessage: String? = null,
    val updateCheckResults: Map<Long, UpdateCheckResult> = emptyMap(),
    val latestTileVersion: String? = null,
) {
    /**
     * Get a human-readable total storage string.
     */
    fun getTotalStorageString(): String =
        when {
            totalStorageBytes < 1024 -> "$totalStorageBytes B"
            totalStorageBytes < 1024 * 1024 -> "${totalStorageBytes / 1024} KB"
            totalStorageBytes < 1024 * 1024 * 1024 -> "${totalStorageBytes / (1024 * 1024)} MB"
            else -> "%.1f GB".format(totalStorageBytes / (1024.0 * 1024.0 * 1024.0))
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
        private val _isImporting = MutableStateFlow(false)
        private val _importSuccessMessage = MutableStateFlow<String?>(null)
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
                    _isImporting,
                    _importSuccessMessage,
                ) { a, b, c, d -> Triple(a to b, c, d) },
            ) { regions, totalStorage, error, isDeleting, (updatePair, isImporting, importSuccess) ->
                val (updateResults, latestVersion) = updatePair
                OfflineMapsState(
                    regions = regions,
                    totalStorageBytes = totalStorage ?: 0L,
                    isLoading = false,
                    isDeleting = isDeleting,
                    isImporting = isImporting,
                    importSuccessMessage = importSuccess,
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

                    // Delete the cached style JSON file if present
                    region.localStylePath?.let { path ->
                        val file = File(path)
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Failed to delete cached style file at $path")
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
         * Clear the import success message.
         */
        fun clearImportSuccess() {
            _importSuccessMessage.value = null
        }

        /**
         * Import an MBTiles file from a content URI (e.g. from a file picker).
         * Copies the file to the offline_maps directory and registers it in the database.
         */
        fun importMbtilesFile(uri: Uri) {
            viewModelScope.launch {
                _isImporting.value = true
                var destFile: File? = null
                try {
                    // Resolve destination file path before copy so cleanup works on failure
                    destFile =
                        withContext(Dispatchers.IO) {
                            val offlineMapsDir = getOfflineMapsDir()

                            // Derive a filename from the URI
                            val displayName = resolveFileName(uri) ?: "imported_${System.currentTimeMillis()}.mbtiles"
                            val safeName =
                                displayName.let {
                                    if (!it.endsWith(".mbtiles")) "$it.mbtiles" else it
                                }

                            // Avoid overwriting existing files
                            var file = File(offlineMapsDir, safeName)
                            var counter = 1
                            while (file.exists()) {
                                val base = safeName.removeSuffix(".mbtiles")
                                file = File(offlineMapsDir, "${base}_$counter.mbtiles")
                                counter++
                            }
                            file
                        }

                    // Copy the content URI to the destination file
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        resolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: error("Could not open input stream for URI")

                        // Validate the copied file
                        if (!OfflineMapStyleBuilder.isValidMBTiles(destFile.absolutePath)) {
                            destFile.delete()
                            error("File does not appear to be a valid MBTiles file")
                        }
                    }

                    // Register in the database using existing import logic
                    val regionId = offlineMapRegionRepository.importOrphanedFile(destFile)
                    val region = offlineMapRegionRepository.getRegionById(regionId)
                    val regionName = region?.name ?: destFile.nameWithoutExtension

                    // Generate and cache the style JSON for this MBTiles file
                    cacheStyleForRegion(regionId, destFile, regionName)

                    Log.i(TAG, "Imported MBTiles file: ${destFile.name} as region $regionId ($regionName)")
                    _importSuccessMessage.value = "Imported \"$regionName\""
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import MBTiles file", e)
                    _errorMessage.value = "Import failed: ${e.message}"
                    // Clean up the copied file on any failure (copy or DB registration)
                    destFile?.let { file ->
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Failed to clean up imported file: ${file.absolutePath}")
                        }
                    }
                } finally {
                    _isImporting.value = false
                }
            }
        }

        /**
         * Set a region as the default map center.
         * Clears any previous default and sets the given region.
         */
        fun setDefaultRegion(regionId: Long) {
            viewModelScope.launch {
                try {
                    offlineMapRegionRepository.setDefaultRegion(regionId)
                    Log.d(TAG, "Set default region: $regionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set default region", e)
                    _errorMessage.value = "Failed to set default region: ${e.message}"
                }
            }
        }

        /**
         * Resolve the display name for a content URI.
         */
        private fun resolveFileName(uri: Uri): String? {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            }
            return uri.lastPathSegment
        }

        /**
         * Clear the default region (no region is default).
         */
        fun clearDefaultRegion() {
            viewModelScope.launch {
                try {
                    offlineMapRegionRepository.clearDefaultRegion()
                    Log.d(TAG, "Cleared default region")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear default region", e)
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
        fun getOfflineMapsDir(): File = File(context.filesDir, "offline_maps").also { it.mkdirs() }

        /**
         * Generate and cache a style JSON file for an imported MBTiles region.
         * Detects raster vs vector format and builds the appropriate style.
         */
        private suspend fun cacheStyleForRegion(
            regionId: Long,
            mbtilesFile: File,
            regionName: String,
        ) {
            try {
                withContext(Dispatchers.IO) {
                    val styleJson =
                        OfflineMapStyleBuilder.buildAutoOfflineStyle(
                            mbtilesPath = mbtilesFile.absolutePath,
                            name = regionName,
                        )
                    val styleDir = File(context.filesDir, "offline_styles")
                    styleDir.mkdirs()
                    val styleFile = File(styleDir, "$regionId.json")
                    styleFile.writeText(styleJson)
                    offlineMapRegionRepository.updateLocalStylePath(regionId, styleFile.absolutePath)
                    Log.d(TAG, "Cached style JSON for imported region $regionId at ${styleFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache style JSON for imported region $regionId (non-fatal)", e)
            }
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
                    val regionId = offlineMapRegionRepository.importOrphanedFile(file)
                    val region = offlineMapRegionRepository.getRegionById(regionId)
                    cacheStyleForRegion(regionId, file, region?.name ?: file.nameWithoutExtension)
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
