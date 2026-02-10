package com.lxmf.messenger.viewmodel

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.MapLibreOfflineManager
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.map.OfflineStyleInliner
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.util.Locale
import javax.inject.Inject
import kotlin.math.cos

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
enum class RadiusOption(
    val km: Int,
    val label: String,
) {
    SMALL(5, "5 km"),
    MEDIUM(10, "10 km"),
    LARGE(25, "25 km"),
    EXTRA_LARGE(50, "50 km"),
    HUGE(100, "100 km"),
}

/**
 * Download progress tracking for MapLibre OfflineManager.
 */
@Immutable
data class DownloadProgress(
    val progress: Float = 0f,
    val completedResources: Long = 0L,
    val requiredResources: Long = 0L,
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Address search result for display in the UI.
 */
@Immutable
data class AddressSearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

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
    val estimatedTileCount: Long = 0L,
    val estimatedSizeBytes: Long = 0L,
    val downloadProgress: DownloadProgress? = null,
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
    val createdRegionId: Long? = null,
    val maplibreRegionId: Long? = null,
    // Address search fields
    val addressQuery: String = "",
    val addressSearchResults: List<AddressSearchResult> = emptyList(),
    val isSearchingAddress: Boolean = false,
    val addressSearchError: String? = null,
    val isGeocoderAvailable: Boolean = true, // Checked on init
    val httpEnabled: Boolean = true, // HTTP map source enabled (needed for downloads)
    val httpAutoDisabled: Boolean = false, // True when HTTP was auto-disabled after download
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
    fun getEstimatedSizeString(): String =
        when {
            estimatedSizeBytes < 1024 -> "$estimatedSizeBytes B"
            estimatedSizeBytes < 1024 * 1024 -> "${estimatedSizeBytes / 1024} KB"
            estimatedSizeBytes < 1024 * 1024 * 1024 -> "${estimatedSizeBytes / (1024 * 1024)} MB"
            else -> "%.1f GB".format(estimatedSizeBytes / (1024.0 * 1024.0 * 1024.0))
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
@Suppress("TooManyFunctions") // ViewModel with address search adds necessary additional functions
@HiltViewModel
class OfflineMapDownloadViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val offlineMapRegionRepository: OfflineMapRegionRepository,
        private val mapLibreOfflineManager: MapLibreOfflineManager,
        private val mapTileSourceManager: MapTileSourceManager,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "OfflineMapDownloadVM"

            // Rough estimate: ~20KB per tile on average (varies widely by zoom and content)
            private const val ESTIMATED_BYTES_PER_TILE = 20_000L
        }

        private val _state = MutableStateFlow(OfflineMapDownloadState())
        val state: StateFlow<OfflineMapDownloadState> = _state.asStateFlow()

        // Track if a download is in progress
        private var isDownloading = false

        init {
            // Check if geocoder backend is actually working (not just installed)
            // Geocoder.isPresent() only checks if installed, not if Play Services is enabled
            viewModelScope.launch(Dispatchers.IO) {
                val geocoderAvailable = checkGeocoderAvailable()
                if (!geocoderAvailable) {
                    _state.update { it.copy(isGeocoderAvailable = false) }
                }
            }

            // Observe HTTP enabled state - downloads require HTTP to be enabled
            viewModelScope.launch {
                mapTileSourceManager.httpEnabledFlow.collect { enabled ->
                    _state.update { it.copy(httpEnabled = enabled) }
                }
            }
        }

        @Suppress("DEPRECATION") // Geocoder API deprecated but replacement requires API 33+
        private fun checkGeocoderAvailable(): Boolean {
            if (!Geocoder.isPresent()) return false
            return try {
                // Try a simple geocode to verify the service is actually working
                val geocoder = Geocoder(context, Locale.getDefault())
                geocoder.getFromLocationName("test", 1)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder not available: ${e.javaClass.simpleName}")
                false
            }
        }

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
        fun setLocation(
            latitude: Double,
            longitude: Double,
        ) {
            // Validate coordinate ranges
            val validLat = latitude.coerceIn(-90.0, 90.0)
            val validLon = longitude.coerceIn(-180.0, 180.0)
            _state.update {
                it.copy(
                    centerLatitude = validLat,
                    centerLongitude = validLon,
                )
            }
            updateEstimate()
        }

        /**
         * Set the address search query.
         */
        fun setAddressQuery(query: String) {
            _state.update { it.copy(addressQuery = query) }
        }

        /**
         * Search for addresses matching the current query.
         */
        @Suppress("DEPRECATION") // Geocoder.getFromLocationName is deprecated but replacement requires API 33+
        fun searchAddress() {
            val query = _state.value.addressQuery.trim()
            if (query.isBlank()) return

            viewModelScope.launch(Dispatchers.IO) {
                _state.update {
                    it.copy(
                        isSearchingAddress = true,
                        addressSearchError = null,
                        addressSearchResults = emptyList(),
                    )
                }

                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocationName(query, 5) ?: emptyList()

                    val results =
                        addresses.mapNotNull { address ->
                            if (address.hasLatitude() && address.hasLongitude()) {
                                AddressSearchResult(
                                    displayName = formatAddress(address),
                                    latitude = address.latitude,
                                    longitude = address.longitude,
                                )
                            } else {
                                null
                            }
                        }

                    _state.update {
                        it.copy(
                            addressSearchResults = results,
                            isSearchingAddress = false,
                            addressSearchError = if (results.isEmpty()) "No results found" else null,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Address search failed", e)
                    _state.update {
                        it.copy(
                            isSearchingAddress = false,
                            addressSearchError = "Search failed: ${e.message}",
                        )
                    }
                }
            }
        }

        /**
         * Select an address from the search results.
         */
        fun selectAddressResult(result: AddressSearchResult) {
            setLocation(result.latitude, result.longitude)
            _state.update {
                it.copy(
                    addressQuery = "",
                    addressSearchResults = emptyList(),
                    addressSearchError = null,
                )
            }
        }

        /**
         * Clear address search results.
         */
        fun clearAddressSearch() {
            _state.update {
                it.copy(
                    addressQuery = "",
                    addressSearchResults = emptyList(),
                    addressSearchError = null,
                )
            }
        }

        /**
         * Enable HTTP map source.
         * Downloads require HTTP to be enabled to fetch tiles from the internet.
         * Marks that HTTP was enabled specifically for downloading, so it can be
         * auto-disabled after the download completes.
         */
        fun enableHttp() {
            viewModelScope.launch {
                settingsRepository.setHttpEnabledForDownload(true)
                mapTileSourceManager.setHttpEnabled(true)
            }
        }

        /**
         * Dismiss the "HTTP auto-disabled" snackbar notification.
         */
        fun dismissHttpAutoDisabledMessage() {
            _state.update { it.copy(httpAutoDisabled = false) }
        }

        private fun formatAddress(address: Address): String {
            // Try to build a readable address string
            val parts = mutableListOf<String>()

            address.locality?.let { parts.add(it) } // City
            address.adminArea?.let { parts.add(it) } // State/Province
            address.countryName?.let { parts.add(it) } // Country

            return if (parts.isNotEmpty()) {
                parts.joinToString(", ")
            } else {
                // Fallback to full address line
                address.getAddressLine(0) ?: "Unknown location"
            }
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
        fun setZoomRange(
            minZoom: Int,
            maxZoom: Int,
        ) {
            val validMin = minZoom.coerceIn(0, 14)
            val validMax = maxZoom.coerceIn(0, 14)
            // Ensure minZoom <= maxZoom
            _state.update {
                it.copy(
                    minZoom = minOf(validMin, validMax),
                    maxZoom = maxOf(validMin, validMax),
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
            val nextStep =
                when (currentState.step) {
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
            val prevStep =
                when (currentState.step) {
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
         * Cancel the current download and clean up.
         */
        fun cancelDownload() {
            val currentState = _state.value
            val maplibreId = currentState.maplibreRegionId
            val dbRegionId = currentState.createdRegionId

            // Delete MapLibre region if it exists
            if (maplibreId != null) {
                mapLibreOfflineManager.deleteRegion(maplibreId) { success ->
                    Log.d(TAG, "MapLibre region deletion: $success")
                }
            }

            // Delete cached style JSON file if it exists
            if (dbRegionId != null) {
                val styleFile = java.io.File(context.filesDir, "offline_styles/$dbRegionId.json")
                if (styleFile.exists() && !styleFile.delete()) {
                    Log.w(TAG, "Failed to delete cached style file: ${styleFile.absolutePath}")
                }
            }

            // Delete database record if it exists
            if (dbRegionId != null) {
                viewModelScope.launch {
                    try {
                        offlineMapRegionRepository.deleteRegion(dbRegionId)
                        Log.d(TAG, "Database region deleted: $dbRegionId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete database region", e)
                    }
                }
            }

            isDownloading = false
            _state.update {
                it.copy(
                    maplibreRegionId = null,
                    createdRegionId = null,
                )
            }
        }

        /**
         * Reset the wizard to start over.
         */
        fun reset() {
            isDownloading = false
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

            // Calculate bounds from center and radius
            val bounds = calculateBounds(lat, lon, currentState.radiusOption.km)

            // Estimate tile count using MapLibreOfflineManager
            val tileCount =
                mapLibreOfflineManager.estimateTileCount(
                    bounds = bounds,
                    minZoom = currentState.minZoom,
                    maxZoom = currentState.maxZoom,
                )

            // Estimate size (rough: ~20KB per tile average)
            val estimatedSize = tileCount * ESTIMATED_BYTES_PER_TILE

            _state.update {
                it.copy(
                    estimatedTileCount = tileCount,
                    estimatedSizeBytes = estimatedSize,
                )
            }
        }

        /**
         * Calculate LatLngBounds from center point and radius.
         */
        private fun calculateBounds(
            centerLat: Double,
            centerLon: Double,
            radiusKm: Int,
        ): LatLngBounds {
            // Convert radius to degrees (approximate)
            // 1 degree latitude ≈ 111 km
            val latDelta = radiusKm / 111.0
            // 1 degree longitude ≈ 111 * cos(lat) km
            val lonDelta = radiusKm / (111.0 * cos(Math.toRadians(centerLat)))

            val southwest = LatLng(centerLat - latDelta, centerLon - lonDelta)
            val northeast = LatLng(centerLat + latDelta, centerLon + lonDelta)

            return LatLngBounds
                .Builder()
                .include(southwest)
                .include(northeast)
                .build()
        }

        @Suppress("LongMethod") // Orchestrates download process - splitting would fragment cohesive flow
        private fun startDownload() {
            val currentState = _state.value
            val lat = currentState.centerLatitude ?: return
            val lon = currentState.centerLongitude ?: return
            val name = currentState.name.ifBlank { "Offline Map" }

            if (isDownloading) {
                Log.w(TAG, "Download already in progress")
                return
            }

            isDownloading = true

            viewModelScope.launch {
                try {
                    Log.d(TAG, "Starting MapLibre offline download for region: $name")

                    // Create database record first
                    val regionId =
                        offlineMapRegionRepository.createRegion(
                            name = name,
                            centerLatitude = lat,
                            centerLongitude = lon,
                            radiusKm = currentState.radiusOption.km,
                            minZoom = currentState.minZoom,
                            maxZoom = currentState.maxZoom,
                        )

                    _state.update { it.copy(createdRegionId = regionId) }

                    // Calculate bounds for the region
                    val bounds = calculateBounds(lat, lon, currentState.radiusOption.km)

                    // Start download using MapLibre's OfflineManager
                    mapLibreOfflineManager.downloadRegion(
                        name = name,
                        bounds = bounds,
                        minZoom = currentState.minZoom.toDouble(),
                        maxZoom = currentState.maxZoom.toDouble(),
                        styleUrl = MapLibreOfflineManager.DEFAULT_STYLE_URL,
                        onCreated = { maplibreId ->
                            _state.update { it.copy(maplibreRegionId = maplibreId) }
                        },
                        onProgress = { progress, completed, required ->
                            _state.update {
                                it.copy(
                                    downloadProgress =
                                        DownloadProgress(
                                            progress = progress,
                                            completedResources = completed,
                                            requiredResources = required,
                                        ),
                                )
                            }

                            // Update database with progress
                            viewModelScope.launch {
                                offlineMapRegionRepository.updateProgress(
                                    id = regionId,
                                    status = OfflineMapRegion.Status.DOWNLOADING,
                                    progress = progress,
                                    tileCount = completed.toInt(),
                                )
                            }
                        },
                        onComplete = { maplibreRegionId, sizeBytes ->
                            Log.d(TAG, "Download complete! MapLibre region ID: $maplibreRegionId, size: $sizeBytes bytes")

                            viewModelScope.launch {
                                try {
                                    // Mark as complete in database with MapLibre region ID
                                    offlineMapRegionRepository.markCompleteWithMaplibreId(
                                        id = regionId,
                                        tileCount =
                                            _state.value.downloadProgress
                                                ?.completedResources
                                                ?.toInt() ?: 0,
                                        sizeBytes = sizeBytes,
                                        maplibreRegionId = maplibreRegionId,
                                    )

                                    // Check if HTTP was enabled specifically for this download
                                    val wasEnabledForDownload =
                                        settingsRepository.httpEnabledForDownloadFlow.first()
                                    if (wasEnabledForDownload) {
                                        // Auto-disable HTTP and clear the flag
                                        Log.d(TAG, "Auto-disabling HTTP after download (was enabled for download)")
                                        mapTileSourceManager.setHttpEnabled(false)
                                        settingsRepository.setHttpEnabledForDownload(false)
                                        _state.update {
                                            it.copy(
                                                isComplete = true,
                                                downloadProgress = it.downloadProgress?.copy(isComplete = true),
                                                httpAutoDisabled = true,
                                            )
                                        }
                                    } else {
                                        _state.update {
                                            it.copy(
                                                isComplete = true,
                                                downloadProgress = it.downloadProgress?.copy(isComplete = true),
                                            )
                                        }
                                    }

                                    // Fetch and cache style JSON for offline rendering (async, non-blocking)
                                    // Launch in separate coroutine so it doesn't block UI state updates
                                    launch { fetchAndCacheStyleJson(regionId) }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to mark region complete in database", e)
                                    _state.update {
                                        it.copy(
                                            errorMessage =
                                                "Database error: ${e.message}. " +
                                                    "MapLibre region saved but database update failed.",
                                        )
                                    }
                                }
                            }

                            isDownloading = false
                        },
                        onError = { errorMessage ->
                            Log.e(TAG, "Download failed: $errorMessage")

                            viewModelScope.launch {
                                offlineMapRegionRepository.markError(
                                    id = regionId,
                                    errorMessage = errorMessage,
                                )
                            }

                            _state.update {
                                it.copy(
                                    errorMessage = "Download failed: $errorMessage",
                                    downloadProgress =
                                        (it.downloadProgress ?: DownloadProgress()).copy(
                                            errorMessage = errorMessage,
                                        ),
                                )
                            }

                            isDownloading = false
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start download", e)
                    isDownloading = false
                    _state.update {
                        it.copy(errorMessage = "Download failed: ${e.message}")
                    }
                }
            }
        }

        /**
         * Fetch and cache the style JSON file locally for offline rendering.
         * Called after download completes successfully (while device is still online).
         *
         * Resolves TileJSON URL references and inlines the tile URL templates into the
         * cached style JSON. This is critical because OpenFreeMap's TileJSON endpoint
         * uses date-versioned tile URLs (e.g. /planet/20260204_001001_pt/{z}/{x}/{y}.pbf)
         * and has a 24-hour cache expiration. Without inlining, MapLibre cannot discover
         * the tile URL templates after the TileJSON cache expires, making downloaded
         * offline tiles unreachable.
         *
         * This is non-fatal - if it fails, the download is still considered successful.
         */
        private suspend fun fetchAndCacheStyleJson(regionId: Long) {
            withContext(Dispatchers.IO) {
                try {
                    // Fetch style JSON from the same URL MapLibre uses
                    val rawStyleJson =
                        kotlinx.coroutines.withTimeout(5000) {
                            java.net.URL(MapTileSourceManager.DEFAULT_STYLE_URL).readText()
                        }

                    // Inline TileJSON references so the style is fully self-contained.
                    // Without this, MapLibre needs to resolve TileJSON URLs at render time,
                    // which fails offline after the HTTP cache expires (~24h).
                    val styleJson =
                        OfflineStyleInliner.inlineTileJsonSources(rawStyleJson) { url ->
                            kotlinx.coroutines.withTimeout(5000) {
                                java.net.URL(url).readText()
                            }
                        }

                    // Save to local file: filesDir/offline_styles/{regionId}.json
                    val styleDir = java.io.File(context.filesDir, "offline_styles")
                    styleDir.mkdirs()
                    val styleFile = java.io.File(styleDir, "$regionId.json")
                    styleFile.writeText(styleJson)

                    // Persist path to database
                    offlineMapRegionRepository.updateLocalStylePath(regionId, styleFile.absolutePath)

                    Log.d(TAG, "Cached style JSON (inlined) for region $regionId at ${styleFile.absolutePath}")
                } catch (e: Exception) {
                    // Non-fatal: download already succeeded, tiles are saved
                    Log.w(TAG, "Failed to cache style JSON for region $regionId (non-fatal)", e)
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            isDownloading = false
        }
    }
