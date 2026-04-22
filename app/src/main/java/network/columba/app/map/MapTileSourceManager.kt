package network.columba.app.map

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import network.columba.app.data.model.MapStylePreference
import network.columba.app.data.repository.OfflineMapRegion
import network.columba.app.data.repository.OfflineMapRegionRepository
import network.columba.app.data.repository.RmspServer
import network.columba.app.data.repository.RmspServerRepository
import network.columba.app.repository.SettingsRepository
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
            const val DEFAULT_STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
            const val DEFAULT_STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"

            // Preserved for callers that don't care about dark/light (e.g. offline downloader).
            // OpenFreeMap serves the same vector tiles under both styles, so an offline region
            // downloaded against the light URL renders correctly under either style.
            const val DEFAULT_STYLE_URL = DEFAULT_STYLE_URL_LIGHT
        }

        /**
         * Resolve the base-style URL for online rendering, honouring the user's
         * MapStylePreference and (for AUTO) the system day/night theme.
         */
        private suspend fun resolveOnlineStyleUrl(): String {
            val preference =
                runCatching { settingsRepository.mapStylePreferenceFlow.first() }
                    .getOrElse { MapStylePreference.DEFAULT }
            return when (preference) {
                MapStylePreference.LIGHT -> DEFAULT_STYLE_URL_LIGHT
                MapStylePreference.DARK -> DEFAULT_STYLE_URL_DARK
                MapStylePreference.AUTO ->
                    if (isSystemInNightMode()) DEFAULT_STYLE_URL_DARK else DEFAULT_STYLE_URL_LIGHT
            }
        }

        private fun isSystemInNightMode(): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

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
                    MapStyleResult.Online(resolveOnlineStyleUrl())
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
                        // No MBTiles files — check for cached inlined style JSON from
                        // MapLibre OfflineManager downloads. Without this, the TileJSON
                        // reference in the style URL expires after ~24h and MapLibre can
                        // no longer discover the tile URL templates, making cached tiles
                        // in mbgl-offline.db unreachable.
                        val (cachedPath, styleExists) =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val r = offlineMapRegionRepository.getFirstCompletedRegionWithStyle()
                                val path = r?.localStylePath
                                Pair(path, path != null && java.io.File(path).exists())
                            }
                        if (styleExists) {
                            Log.d(TAG, "Using cached inlined style JSON: $cachedPath")
                            MapStyleResult.OfflineWithLocalStyle(cachedPath!!)
                        } else {
                            Log.w(TAG, "Offline regions exist but no cached style found — falling back to HTTP style URL")
                            // Tiles work until TileJSON cache expires (~24h); prompt re-download but don't block immediately
                            MapStyleResult.Offline(resolveOnlineStyleUrl())
                        }
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
