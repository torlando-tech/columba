package com.lxmf.messenger.viewmodel

import android.content.Context
import android.location.Location
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.TileDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Download wizard step.
 */
enum class DownloadWizardStep {
    /** Select the center location */
    LOCATION,
    /** Select radius and zoom range */
    RADIUS,
    /** Confirm and start download */
    CONFIRM,
    /** Download in progress */
    DOWNLOADING,
}

/**
 * Radius option for offline map download.
 */
enum class RadiusOption(val km: Int, val label: String) {
    SMALL(5, "5 km"),
    MEDIUM(10, "10 km"),
    LARGE(25, "25 km"),
    EXTRA_LARGE(50, "50 km"),
    HUGE(100, "100 km"),
}

/**
 * UI state for the offline map download wizard.
 */
@Immutable
data class OfflineMapDownloadState(
    val step: DownloadWizardStep = DownloadWizardStep.LOCATION,
    val centerLatitude: Double? = null,
    val centerLongitude: Double? = null,
    val radiusOption: RadiusOption = RadiusOption.MEDIUM,
    val minZoom: Int = 0,
    val maxZoom: Int = 14,
    val name: String = "",
    val estimatedTileCount: Int = 0,
    val estimatedSizeBytes: Long = 0L,
    val downloadProgress: TileDownloadManager.DownloadProgress? = null,
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
    val createdRegionId: Long? = null,
) {
    /**
     * Check if the location is set.
     */
    val hasLocation: Boolean
        get() = centerLatitude != null && centerLongitude != null

    /**
     * Check if the name is valid.
     */
    val hasValidName: Boolean
        get() = name.isNotBlank()

    /**
     * Get a human-readable estimated size string.
     */
    fun getEstimatedSizeString(): String {
        return when {
            estimatedSizeBytes < 1024 -> "$estimatedSizeBytes B"
            estimatedSizeBytes < 1024 * 1024 -> "${estimatedSizeBytes / 1024} KB"
            estimatedSizeBytes < 1024 * 1024 * 1024 -> "${estimatedSizeBytes / (1024 * 1024)} MB"
            else -> "%.1f GB".format(estimatedSizeBytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/**
 * ViewModel for the offline map download wizard.
 *
 * Guides the user through:
 * 1. Selecting a center location
 * 2. Choosing radius and zoom range
 * 3. Naming and confirming the download
 * 4. Monitoring download progress
 */
@HiltViewModel
class OfflineMapDownloadViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val offlineMapRegionRepository: OfflineMapRegionRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(OfflineMapDownloadState())
        val state: StateFlow<OfflineMapDownloadState> = _state.asStateFlow()

        private var downloadManager: TileDownloadManager? = null

        /**
         * Set the center location from user's current position.
         */
        fun setLocationFromCurrent(location: Location) {
            _state.update {
                it.copy(
                    centerLatitude = location.latitude,
                    centerLongitude = location.longitude,
                )
            }
            updateEstimate()
        }

        /**
         * Set the center location from map tap.
         */
        fun setLocation(latitude: Double, longitude: Double) {
            _state.update {
                it.copy(
                    centerLatitude = latitude,
                    centerLongitude = longitude,
                )
            }
            updateEstimate()
        }

        /**
         * Set the radius option.
         */
        fun setRadiusOption(option: RadiusOption) {
            _state.update { it.copy(radiusOption = option) }
            updateEstimate()
        }

        /**
         * Set the zoom range.
         */
        fun setZoomRange(minZoom: Int, maxZoom: Int) {
            _state.update {
                it.copy(
                    minZoom = minZoom.coerceIn(0, 14),
                    maxZoom = maxZoom.coerceIn(0, 14),
                )
            }
            updateEstimate()
        }

        /**
         * Set the region name.
         */
        fun setName(name: String) {
            _state.update { it.copy(name = name) }
        }

        /**
         * Move to the next step.
         */
        fun nextStep() {
            val currentState = _state.value
            val nextStep = when (currentState.step) {
                DownloadWizardStep.LOCATION -> DownloadWizardStep.RADIUS
                DownloadWizardStep.RADIUS -> DownloadWizardStep.CONFIRM
                DownloadWizardStep.CONFIRM -> {
                    startDownload()
                    DownloadWizardStep.DOWNLOADING
                }
                DownloadWizardStep.DOWNLOADING -> DownloadWizardStep.DOWNLOADING
            }
            _state.update { it.copy(step = nextStep) }
        }

        /**
         * Go back to the previous step.
         */
        fun previousStep() {
            val currentState = _state.value
            val prevStep = when (currentState.step) {
                DownloadWizardStep.LOCATION -> DownloadWizardStep.LOCATION
                DownloadWizardStep.RADIUS -> DownloadWizardStep.LOCATION
                DownloadWizardStep.CONFIRM -> DownloadWizardStep.RADIUS
                DownloadWizardStep.DOWNLOADING -> {
                    cancelDownload()
                    DownloadWizardStep.CONFIRM
                }
            }
            _state.update { it.copy(step = prevStep) }
        }

        /**
         * Cancel the current download.
         */
        fun cancelDownload() {
            downloadManager?.cancel()
        }

        /**
         * Reset the wizard to start over.
         */
        fun reset() {
            downloadManager?.reset()
            _state.value = OfflineMapDownloadState()
        }

        /**
         * Clear error message.
         */
        fun clearError() {
            _state.update { it.copy(errorMessage = null) }
        }

        private fun updateEstimate() {
            val currentState = _state.value
            val lat = currentState.centerLatitude ?: return
            val lon = currentState.centerLongitude ?: return

            val manager = downloadManager ?: TileDownloadManager(context).also {
                downloadManager = it
            }

            val (tileCount, estimatedSize) = manager.estimateDownload(
                centerLat = lat,
                centerLon = lon,
                radiusKm = currentState.radiusOption.km,
                minZoom = currentState.minZoom,
                maxZoom = currentState.maxZoom,
            )

            _state.update {
                it.copy(
                    estimatedTileCount = tileCount,
                    estimatedSizeBytes = estimatedSize,
                )
            }
        }

        private fun startDownload() {
            val currentState = _state.value
            val lat = currentState.centerLatitude ?: return
            val lon = currentState.centerLongitude ?: return
            val name = currentState.name.ifBlank { "Offline Map" }

            viewModelScope.launch {
                try {
                    // Create database record
                    val regionId = offlineMapRegionRepository.createRegion(
                        name = name,
                        centerLatitude = lat,
                        centerLongitude = lon,
                        radiusKm = currentState.radiusOption.km,
                        minZoom = currentState.minZoom,
                        maxZoom = currentState.maxZoom,
                    )

                    _state.update { it.copy(createdRegionId = regionId) }

                    // Generate output file path
                    val outputDir = TileDownloadManager.getOfflineMapsDir(context)
                    val filename = TileDownloadManager.generateFilename(name)
                    val outputFile = File(outputDir, filename)

                    // Create download manager if needed
                    val manager = downloadManager ?: TileDownloadManager(context).also {
                        downloadManager = it
                    }

                    // Collect progress updates
                    launch {
                        manager.progress.collect { progress ->
                            _state.update { it.copy(downloadProgress = progress) }

                            // Update database with progress
                            when (progress.status) {
                                TileDownloadManager.DownloadProgress.Status.DOWNLOADING -> {
                                    offlineMapRegionRepository.updateProgress(
                                        id = regionId,
                                        status = OfflineMapRegion.Status.DOWNLOADING,
                                        progress = progress.progress,
                                        tileCount = progress.downloadedTiles,
                                    )
                                }
                                TileDownloadManager.DownloadProgress.Status.COMPLETE -> {
                                    // Handled below after download completes
                                }
                                TileDownloadManager.DownloadProgress.Status.ERROR -> {
                                    offlineMapRegionRepository.markError(
                                        id = regionId,
                                        errorMessage = progress.errorMessage ?: "Download failed",
                                    )
                                }
                                TileDownloadManager.DownloadProgress.Status.CANCELLED -> {
                                    // Delete the region if cancelled
                                    offlineMapRegionRepository.deleteRegion(regionId)
                                }
                                else -> { /* Ignore other states */ }
                            }
                        }
                    }

                    // Start download
                    val result = manager.downloadRegion(
                        centerLat = lat,
                        centerLon = lon,
                        radiusKm = currentState.radiusOption.km,
                        minZoom = currentState.minZoom,
                        maxZoom = currentState.maxZoom,
                        name = name,
                        outputFile = outputFile,
                    )

                    if (result != null) {
                        // Mark as complete in database
                        offlineMapRegionRepository.markComplete(
                            id = regionId,
                            tileCount = manager.progress.value.downloadedTiles,
                            sizeBytes = result.length(),
                            mbtilesPath = result.absolutePath,
                        )

                        _state.update { it.copy(isComplete = true) }
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(errorMessage = "Download failed: ${e.message}")
                    }
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            downloadManager?.cancel()
        }
    }
