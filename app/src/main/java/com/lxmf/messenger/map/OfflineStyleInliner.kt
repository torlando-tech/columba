package com.lxmf.messenger.map

import android.util.Log
import org.json.JSONObject

/**
 * Resolves TileJSON URL references in a MapLibre style JSON, replacing them
 * with inline tile URL templates.
 *
 * MapLibre styles can reference tile sources in two ways:
 * 1. Inline: `"tiles": ["https://example.com/{z}/{x}/{y}.pbf"]`
 * 2. TileJSON reference: `"url": "https://example.com/tilejson"`
 *
 * The TileJSON reference requires a network fetch to resolve at render time.
 * OpenFreeMap's TileJSON has a 24-hour cache expiration and uses date-versioned
 * tile URLs (e.g. `/planet/20260204_001001_pt/{z}/{x}/{y}.pbf`). After the cache
 * expires offline, MapLibre cannot discover the tile URL templates, so downloaded
 * offline tiles become unreachable even though they exist in the database.
 *
 * This utility resolves all TileJSON references at download time and inlines them
 * into the style JSON, making it fully self-contained for offline use.
 */
object OfflineStyleInliner {
    private const val TAG = "OfflineStyleInliner"

    /**
     * Resolve TileJSON references in a style JSON and inline the tile URLs.
     *
     * For each source with a `"url"` key pointing to an HTTP(S) TileJSON endpoint,
     * fetches the TileJSON and replaces the reference with inline `"tiles"`, `"minzoom"`,
     * `"maxzoom"`, `"bounds"`, and `"attribution"` fields from the response.
     *
     * Sources that already use inline `"tiles"` arrays are left unchanged.
     * If a TileJSON fetch fails, the source is left as-is (graceful degradation).
     *
     * @param styleJson The raw style JSON string
     * @param fetchUrl Function to fetch a URL and return its content as a string
     * @return The modified style JSON with TileJSON references resolved to inline tiles
     */
    suspend fun inlineTileJsonSources(
        styleJson: String,
        fetchUrl: suspend (String) -> String,
    ): String {
        val style = JSONObject(styleJson)
        val sources = style.optJSONObject("sources") ?: return styleJson

        // Collect keys first to avoid concurrent modification issues
        val sourceNames = mutableListOf<String>()
        val keys = sources.keys()
        while (keys.hasNext()) {
            sourceNames.add(keys.next())
        }

        for (sourceName in sourceNames) {
            val source = sources.optJSONObject(sourceName) ?: continue
            val tileJsonUrl = source.optString("url", "")

            // Only resolve http(s) TileJSON URLs (skip empty, mapbox://, mbtiles://, etc.)
            if (tileJsonUrl.isNotEmpty() &&
                (tileJsonUrl.startsWith("http://") || tileJsonUrl.startsWith("https://"))
            ) {
                resolveTileJsonSource(sourceName, source, tileJsonUrl, fetchUrl)
            }
        }

        return style.toString()
    }

    /**
     * Fetch and inline a single TileJSON source.
     *
     * On success, replaces the source's `"url"` with inline `"tiles"` and metadata.
     * On failure (network error, malformed JSON, empty tiles), leaves the source unchanged.
     */
    private suspend fun resolveTileJsonSource(
        sourceName: String,
        source: JSONObject,
        tileJsonUrl: String,
        fetchUrl: suspend (String) -> String,
    ) {
        try {
            val tileJsonStr = fetchUrl(tileJsonUrl)
            val tileJson = JSONObject(tileJsonStr)
            val tiles = tileJson.optJSONArray("tiles")
            if (tiles == null || tiles.length() == 0) {
                Log.w(TAG, "TileJSON for '$sourceName' has no tiles array, skipping")
                return
            }

            // Remove the url reference and add inline tiles
            source.remove("url")
            source.put("tiles", tiles)

            // Copy relevant TileJSON metadata
            if (tileJson.has("minzoom")) source.put("minzoom", tileJson.get("minzoom"))
            if (tileJson.has("maxzoom")) source.put("maxzoom", tileJson.get("maxzoom"))
            if (tileJson.has("bounds")) source.put("bounds", tileJson.get("bounds"))
            val attribution = tileJson.optString("attribution", null)
            if (attribution != null) source.put("attribution", attribution)

            Log.d(TAG, "Inlined TileJSON for source '$sourceName' (${tiles.length()} tile URL template(s))")
        } catch (e: Exception) {
            // Leave the source as-is; MapLibre will try to resolve it at render time
            Log.w(TAG, "Failed to resolve TileJSON for source '$sourceName': ${e.message}")
        }
    }
}
