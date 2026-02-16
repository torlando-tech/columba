package com.lxmf.messenger.map

import android.content.Context
import android.util.Log
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.data.repository.RmspServer
import com.lxmf.messenger.data.repository.RmspServerRepository
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of resolving which map style to use.
 */
sealed class MapStyleResult {
    /**
     * Use online HTTP tiles (default).
     */
    data class Online(
        val styleUrl: String,
    ) : MapStyleResult()

    /**
     * Use offline cached tiles (via MapLibre's OfflineManager).
     * Uses the same style URL as online - MapLibre serves cached tiles automatically.
     */
    data class Offline(
        val styleUrl: String,
    ) : MapStyleResult()

    /**
     * Use offline cached tiles with a locally stored style JSON file.
     * The style JSON was fetched and cached during the offline map download.
     * Uses fromJson() instead of fromUri() to avoid HTTP cache expiration.
     */
    data class OfflineWithLocalStyle(
        val localStylePath: String,
    ) : MapStyleResult()

    /**
     * Use RMSP server for tiles.
     */
    data class Rmsp(
        val server: RmspServer,
        val styleJson: String,
    ) : MapStyleResult()

    /**
     * No map source available.
     */
    data class Unavailable(
        val reason: String,
    ) : MapStyleResult()
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
        @ApplicationContext private val context: Context,
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
            val hasOffline = hasOfflineMaps().first()

            Log.d(
                TAG,
                "Getting map style for location: $latitude, $longitude " +
                    "(HTTP=$httpEnabled, RMSP=$rmspEnabled, hasOffline=$hasOffline)",
            )

            // NOTE: Offline maps are handled by MapLibre's native OfflineManager API.
            // Tiles downloaded via OfflineManager are automatically used when the style
            // references them - MapLibre serves cached tiles without network requests.
            // We must still load the style URL so MapLibre knows which sources to use.

            // Check HTTP source, offline maps, RMSP source, then return unavailable
            return when {
                httpEnabled -> {
                    Log.d(TAG, "Using HTTP source")
                    MapStyleResult.Online(DEFAULT_STYLE_URL)
                }
                hasOffline -> {
                    // Get all completed regions with MBTiles files
                    val regions = offlineMapRegionRepository.getCompletedRegionsWithMbtiles()
                    val mbtilesPaths = regions.mapNotNull { it.mbtilesPath }

                    if (mbtilesPaths.isNotEmpty()) {
                        // Build combined style on IO thread (SQLite reads + file write)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val combinedStyleJson = OfflineMapStyleBuilder.buildCombinedOfflineStyle(mbtilesPaths)
                            val styleDir = java.io.File(context.filesDir, "offline_styles")
                            styleDir.mkdirs()
                            val combinedStyleFile = java.io.File(styleDir, "combined.json")
                            combinedStyleFile.writeText(combinedStyleJson)
                            Log.d(TAG, "Built combined offline style from ${mbtilesPaths.size} region(s)")
                            MapStyleResult.OfflineWithLocalStyle(combinedStyleFile.absolutePath)
                        }
                    } else {
                        // Fallback to HTTP style URL (works if HTTP cache hasn't expired)
                        Log.w(TAG, "No MBTiles files found, falling back to HTTP style URL")
                        MapStyleResult.Offline(DEFAULT_STYLE_URL)
                    }
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
                else -> MapStyleResult.Unavailable("HTTP map source is disabled. Enable it in Settings or download offline maps.")
            }
        }

        /**
         * Observe offline regions that could be used for the map.
         */
        fun observeOfflineRegions(): Flow<List<OfflineMapRegion>> = offlineMapRegionRepository.getCompletedRegions()

        /**
         * Observe available RMSP servers.
         */
        fun observeRmspServers(): Flow<List<RmspServer>> = rmspServerRepository.getAllServers()

        /**
         * Check if any offline maps are available.
         */
        fun hasOfflineMaps(): Flow<Boolean> = offlineMapRegionRepository.getCompletedRegions().map { it.isNotEmpty() }

        /**
         * Check if any RMSP servers are available.
         */
        fun hasRmspServers(): Flow<Boolean> = rmspServerRepository.hasServers()

        /**
         * Observe the combined availability of map sources.
         */
        fun observeSourceAvailability(): Flow<SourceAvailability> =
            combine(
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

        /**
         * Get the count of RMSP servers.
         */
        fun observeRmspServerCount(): Flow<Int> = rmspServerRepository.getAllServers().map { it.size }

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
