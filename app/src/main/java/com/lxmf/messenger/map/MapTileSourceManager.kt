package com.lxmf.messenger.map

import android.util.Log
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.data.repository.RmspServer
import com.lxmf.messenger.data.repository.RmspServerRepository
import com.lxmf.messenger.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of resolving which map style to use.
 */
sealed class MapStyleResult {
    /**
     * Use online HTTP tiles (default).
     */
    data class Online(val styleUrl: String) : MapStyleResult()

    /**
     * Use offline MBTiles file.
     */
    data class Offline(val styleJson: String, val regionName: String) : MapStyleResult()

    /**
     * Use RMSP server for tiles.
     */
    data class Rmsp(val server: RmspServer, val styleJson: String) : MapStyleResult()

    /**
     * No map source available.
     */
    data class Unavailable(val reason: String) : MapStyleResult()
}

/**
 * Manages map tile source selection between offline, HTTP, and RMSP sources.
 *
 * Priority order (when both enabled):
 * 1. Offline MBTiles (if region covers current location)
 * 2. HTTP (OpenFreeMap)
 * 3. RMSP (if HTTP disabled and RMSP server available)
 */
@Singleton
class MapTileSourceManager
    @Inject
    constructor(
        private val offlineMapRegionRepository: OfflineMapRegionRepository,
        private val rmspServerRepository: RmspServerRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        companion object {
            private const val TAG = "MapTileSourceManager"
            const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        }

        /**
         * Flow of HTTP enabled setting from SettingsRepository.
         */
        val httpEnabledFlow: Flow<Boolean> = settingsRepository.mapSourceHttpEnabledFlow

        /**
         * Flow of RMSP enabled setting from SettingsRepository.
         */
        val rmspEnabledFlow: Flow<Boolean> = settingsRepository.mapSourceRmspEnabledFlow

        // Runtime overrides for backwards compatibility
        @Volatile
        private var _httpEnabledOverride: Boolean? = null

        @Volatile
        private var _rmspEnabledOverride: Boolean? = null

        var httpEnabled: Boolean
            get() = _httpEnabledOverride ?: true
            set(value) {
                _httpEnabledOverride = value
            }

        var rmspEnabled: Boolean
            get() = _rmspEnabledOverride ?: false
            set(value) {
                _rmspEnabledOverride = value
            }

        /**
         * Get the map style for a given location.
         *
         * @param latitude Current latitude (or null for default)
         * @param longitude Current longitude (or null for default)
         * @return MapStyleResult indicating which source to use
         */
        suspend fun getMapStyle(
            latitude: Double?,
            longitude: Double?,
        ): MapStyleResult {
            val httpEnabled = _httpEnabledOverride ?: settingsRepository.getMapSourceHttpEnabled()
            val rmspEnabled = _rmspEnabledOverride ?: settingsRepository.getMapSourceRmspEnabled()

            Log.d(TAG, "Getting map style for location: $latitude, $longitude (HTTP=$httpEnabled, RMSP=$rmspEnabled)")

            // Check offline source first
            val offlineResult =
                if (latitude != null && longitude != null) {
                    findOfflineRegion(latitude, longitude)?.let { region ->
                        val path = region.mbtilesPath
                        if (path != null) {
                            Log.d(TAG, "Found offline region: ${region.name}")
                            val styleJson = OfflineMapStyleBuilder.buildOfflineStyle(path, region.name)
                            MapStyleResult.Offline(styleJson, region.name)
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
            if (offlineResult != null) return offlineResult

            // Check HTTP source, then RMSP source, then return unavailable
            return when {
                httpEnabled -> {
                    Log.d(TAG, "Using HTTP source")
                    MapStyleResult.Online(DEFAULT_STYLE_URL)
                }
                rmspEnabled -> {
                    val servers = rmspServerRepository.getNearestServers(1).first()
                    if (servers.isNotEmpty()) {
                        val server = servers.first()
                        Log.d(TAG, "Using RMSP server: ${server.serverName}")
                        MapStyleResult.Rmsp(server = server, styleJson = "")
                    } else {
                        Log.w(TAG, "RMSP enabled but no servers available")
                        MapStyleResult.Unavailable(
                            "No RMSP servers discovered. Enable HTTP in Settings > Map Sources to use online maps.",
                        )
                    }
                }
                else -> MapStyleResult.Unavailable("Both HTTP and RMSP sources are disabled")
            }
        }

        /**
         * Find an offline region that covers the given location.
         */
        private suspend fun findOfflineRegion(
            latitude: Double,
            longitude: Double,
        ): OfflineMapRegion? {
            val regions = offlineMapRegionRepository.getCompletedRegions().first()

            return regions.find { region ->
                // Check if location is within radius of region center
                val distance =
                    haversineDistance(
                        lat1 = latitude,
                        lon1 = longitude,
                        lat2 = region.centerLatitude,
                        lon2 = region.centerLongitude,
                    )
                val mbtilesPath = region.mbtilesPath
                distance <= region.radiusKm && mbtilesPath != null && File(mbtilesPath).exists()
            }
        }

        /**
         * Calculate distance between two points using Haversine formula.
         *
         * @return Distance in kilometers
         */
        private fun haversineDistance(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val r = 6371.0 // Earth radius in km

            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a =
                kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                    kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                    kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

            return r * c
        }

        /**
         * Observe offline regions that could be used for the map.
         */
        fun observeOfflineRegions(): Flow<List<OfflineMapRegion>> {
            return offlineMapRegionRepository.getCompletedRegions()
        }

        /**
         * Observe available RMSP servers.
         */
        fun observeRmspServers(): Flow<List<RmspServer>> {
            return rmspServerRepository.getAllServers()
        }

        /**
         * Check if any offline maps are available.
         */
        fun hasOfflineMaps(): Flow<Boolean> {
            return offlineMapRegionRepository.getCompletedRegions().map { it.isNotEmpty() }
        }

        /**
         * Check if any RMSP servers are available.
         */
        fun hasRmspServers(): Flow<Boolean> {
            return rmspServerRepository.hasServers()
        }

        /**
         * Observe the combined availability of map sources.
         */
        fun observeSourceAvailability(): Flow<SourceAvailability> {
            return combine(
                hasOfflineMaps(),
                hasRmspServers(),
                httpEnabledFlow,
                rmspEnabledFlow,
            ) { hasOffline, hasRmsp, httpEnabled, rmspEnabled ->
                SourceAvailability(
                    hasOfflineMaps = hasOffline,
                    hasRmspServers = hasRmsp,
                    httpEnabled = httpEnabled,
                    rmspEnabled = rmspEnabled,
                )
            }
        }

        /**
         * Get the count of RMSP servers.
         */
        fun observeRmspServerCount(): Flow<Int> {
            return rmspServerRepository.getAllServers().map { it.size }
        }

        /**
         * Save HTTP enabled setting.
         */
        suspend fun setHttpEnabled(enabled: Boolean) {
            settingsRepository.saveMapSourceHttpEnabled(enabled)
            _httpEnabledOverride = null // Clear override to use repository value
        }

        /**
         * Save RMSP enabled setting.
         */
        suspend fun setRmspEnabled(enabled: Boolean) {
            settingsRepository.saveMapSourceRmspEnabled(enabled)
            _rmspEnabledOverride = null // Clear override to use repository value
        }
    }

/**
 * Current availability of map sources.
 */
data class SourceAvailability(
    val hasOfflineMaps: Boolean,
    val hasRmspServers: Boolean,
    val httpEnabled: Boolean,
    val rmspEnabled: Boolean,
) {
    val hasAnySource: Boolean
        get() = hasOfflineMaps || httpEnabled || (rmspEnabled && hasRmspServers)
}
