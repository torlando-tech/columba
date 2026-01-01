package com.lxmf.messenger.viewmodel

import android.content.Context
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

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
        private val _errorMessage = MutableStateFlow<String?>(null)
        private val _isDeleting = MutableStateFlow(false)

        val state: StateFlow<OfflineMapsState> = combine(
            offlineMapRegionRepository.getAllRegions(),
            offlineMapRegionRepository.getTotalStorageUsed(),
            _errorMessage,
            _isDeleting,
        ) { regions, totalStorage, error, isDeleting ->
            OfflineMapsState(
                regions = regions,
                totalStorageBytes = totalStorage ?: 0L,
                isLoading = false,
                isDeleting = isDeleting,
                errorMessage = error,
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
                    // Delete the MBTiles file if it exists
                    region.mbtilesPath?.let { path ->
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
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
         */
        fun retryDownload(region: OfflineMapRegion) {
            // This will be handled by navigating to the download screen
            // with the region's parameters pre-filled
        }

        /**
         * Clear the error message.
         */
        fun clearError() {
            _errorMessage.value = null
        }

        /**
         * Get the offline maps directory.
         */
        fun getOfflineMapsDir(): File {
            return TileDownloadManager.getOfflineMapsDir(context)
        }
    }
