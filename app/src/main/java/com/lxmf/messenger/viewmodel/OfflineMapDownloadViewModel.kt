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
import com.lxmf.messenger.di.IoDispatcher
import com.lxmf.messenger.map.MapLibreOfflineManager
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.map.OfflineStyleInliner
import com.lxmf.messenger.map.TileDownloadManager
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
    val statusMessage: String? = null, // Shown during post-download finalization (e.g., style caching)
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
    val styleCacheWarning: String? = null, // Non-null when style caching failed but tiles were saved
    val updateRegionId: Long? = null, // Non-null when updating an existing region (delete old on complete)
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
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** URL fetcher — overridable for testing. */
        internal var urlFetcher: (String) -> String = ::defaultFetchUrl

        companion object {
            private const val TAG = "OfflineMapDownloadVM"

            // Rough estimate: ~20KB per tile on average (varies widely by zoom and content)
            private const val ESTIMATED_BYTES_PER_TILE = 20_000L

            // Style caching: retry parameters for fetching and inlining TileJSON
            internal const val STYLE_CACHE_MAX_RETRIES = 3
            internal const val STYLE_FETCH_TIMEOUT_MS = 10_000L
            private const val STYLE_CACHE_RETRY_DELAY_MS = 2_000L
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
         * Initialize the wizard pre-filled for updating an existing region.
         * Loads the region's parameters, skips to CONFIRM step, and records the
         * old region ID so it can be deleted after download completes.
         */
        fun initForUpdate(regionId: Long) {
            viewModelScope.launch {
                val region =
                    offlineMapRegionRepository.getRegionById(regionId) ?: run {
                        Log.e(TAG, "Cannot update: region $regionId not found")
                        return@launch
                    }
                val radiusOption =
                    RadiusOption.entries.find { it.km == region.radiusKm }
                        ?: RadiusOption.MEDIUM
                _state.update {
                    it.copy(
                        centerLatitude = region.centerLatitude,
                        centerLongitude = region.centerLongitude,
                        radiusOption = radiusOption,
                        minZoom = region.minZoom,
                        maxZoom = region.maxZoom,
                        name = region.name,
                        step = DownloadWizardStep.CONFIRM,
                        updateRegionId = regionId,
                    )
                }
                updateEstimate()
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
         * Delete the old region being replaced during an update.
         * Removes the MapLibre region, MBTiles file, cached style, and DB record.
         */
        private suspend fun deleteOldRegion(regionId: Long) {
            try {
                val oldRegion = offlineMapRegionRepository.getRegionById(regionId)
                if (oldRegion != null) {
                    // Delete MapLibre offline region
                    oldRegion.maplibreRegionId?.let { mlId ->
                        mapLibreOfflineManager.deleteRegion(mlId) { success ->
                            if (!success) Log.w(TAG, "Failed to delete old MapLibre region: $mlId")
                        }
                    }
                    // Delete legacy MBTiles file
                    oldRegion.mbtilesPath?.let { path ->
                        val file = java.io.File(path)
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Failed to delete old MBTiles file: $path")
                        }
                    }
                    // Delete cached style JSON
                    oldRegion.localStylePath?.let { path ->
                        val file = java.io.File(path)
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Failed to delete old style file: $path")
                        }
                    }
                }
                // Delete DB record
                offlineMapRegionRepository.deleteRegion(regionId)
                Log.d(TAG, "Deleted old region $regionId after successful update")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up old region $regionId (non-fatal)", e)
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

            val southwest =
                LatLng(
                    (centerLat - latDelta).coerceIn(-90.0, 90.0),
                    (centerLon - lonDelta).coerceIn(-180.0, 180.0),
                )
            val northeast =
                LatLng(
                    (centerLat + latDelta).coerceIn(-90.0, 90.0),
                    (centerLon + lonDelta).coerceIn(-180.0, 180.0),
                )

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
                                    // 1. Mark COMPLETE immediately so the database
                                    //    reflects reality (tiles are saved in
                                    //    mbgl-offline.db). If the app is killed after
                                    //    this point, the region won't appear stuck in
                                    //    DOWNLOADING state on next launch.
                                    offlineMapRegionRepository.markCompleteWithMaplibreId(
                                        id = regionId,
                                        tileCount =
                                            _state.value.downloadProgress
                                                ?.completedResources
                                                ?.toInt() ?: 0,
                                        sizeBytes = sizeBytes,
                                        maplibreRegionId = maplibreRegionId,
                                    )

                                    // 1b. Record the tile version so "Check for Updates"
                                    //     can compare against the server later.
                                    try {
                                        val tileVersion =
                                            TileDownloadManager.fetchCurrentTileVersion()
                                        if (tileVersion != null) {
                                            offlineMapRegionRepository.updateTileVersion(
                                                regionId,
                                                tileVersion,
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to record tile version (non-fatal)", e)
                                    }

                                    // 2. Show "Finalizing..." while style caching runs.
                                    //    This can take up to ~36s worst-case (3 retries ×
                                    //    10s timeout + backoff delays).
                                    _state.update {
                                        it.copy(
                                            downloadProgress =
                                                it.downloadProgress?.copy(
                                                    statusMessage = "Finalizing offline style…",
                                                ),
                                        )
                                    }

                                    // 3. Cache the inlined style JSON. This must
                                    //    happen BEFORE HTTP is auto-disabled so that
                                    //    MapTileSourceManager returns Online (not
                                    //    Unavailable) if anything queries the map
                                    //    while style caching is still in progress.
                                    val styleCached = fetchAndCacheStyleJson(regionId)
                                    val styleCacheWarning =
                                        if (!styleCached) {
                                            Log.w(TAG, "Style caching failed — offline map may not work after 24h")
                                            "Map tiles saved, but offline style caching failed. " +
                                                "The map may stop working offline after 24 hours. " +
                                                "Try re-downloading while connected to the internet."
                                        } else {
                                            null
                                        }

                                    // 4. Now safe to auto-disable HTTP — the cached
                                    //    style (if successful) is already persisted.
                                    val wasEnabledForDownload =
                                        settingsRepository.httpEnabledForDownloadFlow.first()
                                    if (wasEnabledForDownload) {
                                        Log.d(TAG, "Auto-disabling HTTP after download (was enabled for download)")
                                        mapTileSourceManager.setHttpEnabled(false)
                                        settingsRepository.setHttpEnabledForDownload(false)
                                    }

                                    // 5. If updating, delete the old region now that
                                    //    the replacement is fully downloaded and cached.
                                    val updateId = _state.value.updateRegionId
                                    if (updateId != null) {
                                        deleteOldRegion(updateId)
                                    }

                                    // 6. Signal completion with any warnings.
                                    //    Both httpAutoDisabled and styleCacheWarning
                                    //    are set atomically so the UI can consolidate
                                    //    them into a single notification.
                                    _state.update {
                                        it.copy(
                                            isComplete = true,
                                            downloadProgress =
                                                it.downloadProgress?.copy(
                                                    isComplete = true,
                                                    statusMessage = null,
                                                ),
                                            httpAutoDisabled = wasEnabledForDownload,
                                            styleCacheWarning = styleCacheWarning,
                                        )
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to complete region finalization", e)
                                    _state.update {
                                        it.copy(
                                            downloadProgress = it.downloadProgress?.copy(statusMessage = null),
                                            errorMessage =
                                                "Error finalizing download: ${e.message}. " +
                                                    "MapLibre region saved but finalization failed.",
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
         * Retries up to 3 times on failure. Returns true if style was cached successfully.
         */
        private suspend fun fetchAndCacheStyleJson(regionId: Long): Boolean =
            withContext(ioDispatcher) {
                repeat(STYLE_CACHE_MAX_RETRIES) { attempt ->
                    try {
                        // Fetch style JSON from the same URL MapLibre uses
                        val rawStyleJson = urlFetcher(MapTileSourceManager.DEFAULT_STYLE_URL)

                        // Inline TileJSON references so the style is fully self-contained.
                        // Without this, MapLibre needs to resolve TileJSON URLs at render time,
                        // which fails offline after the HTTP cache expires (~24h).
                        val styleJson =
                            OfflineStyleInliner.inlineTileJsonSources(rawStyleJson) { url ->
                                urlFetcher(url)
                            }

                        // Save to local file: filesDir/offline_styles/{regionId}.json
                        val styleDir = java.io.File(context.filesDir, "offline_styles")
                        if (!styleDir.exists() && !styleDir.mkdirs()) {
                            throw java.io.IOException("Failed to create offline styles directory: ${styleDir.absolutePath}")
                        }
                        val styleFile = java.io.File(styleDir, "$regionId.json")
                        styleFile.writeText(styleJson)

                        // Persist path to database
                        offlineMapRegionRepository.updateLocalStylePath(regionId, styleFile.absolutePath)

                        Log.d(TAG, "Cached style JSON (inlined) for region $regionId at ${styleFile.absolutePath}")
                        return@withContext true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Style cache attempt ${attempt + 1}/$STYLE_CACHE_MAX_RETRIES failed for region $regionId", e)
                        if (attempt < STYLE_CACHE_MAX_RETRIES - 1) {
                            kotlinx.coroutines.delay(STYLE_CACHE_RETRY_DELAY_MS * (attempt + 1))
                        }
                    }
                }
                Log.e(TAG, "All style cache attempts failed for region $regionId")
                false
            }

        override fun onCleared() {
            super.onCleared()
            isDownloading = false
        }
    }

/**
 * Default URL fetcher with OS-level socket timeouts.
 * Uses [java.net.HttpURLConnection] instead of [java.net.URL.readText] so that
 * connect/read timeouts are enforced at the OS level, not via cooperative cancellation.
 */
private fun defaultFetchUrl(url: String): String {
    val timeoutMs = OfflineMapDownloadViewModel.STYLE_FETCH_TIMEOUT_MS.toInt()
    val connection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
    return try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw java.io.IOException("HTTP $code fetching style: ${connection.responseMessage}")
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
