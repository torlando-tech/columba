package com.lxmf.messenger.map

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds MapLibre style JSON for offline map sources.
 *
 * MapLibre GL can read from MBTiles files using the mbtiles:// protocol.
 * This builder creates style JSON that references local MBTiles files
 * while maintaining the same visual appearance as the online style.
 */
object OfflineMapStyleBuilder {
    /**
     * Base style layers for OpenFreeMap Liberty style.
     * Simplified version that works with OpenMapTiles schema.
     */
    private val BASE_LAYERS =
        """
        [
          {
            "id": "background",
            "type": "background",
            "paint": { "background-color": "#f8f4f0" }
          },
          {
            "id": "landcover-grass",
            "type": "fill",
            "source": "openmaptiles",
            "source-layer": "landcover",
            "filter": ["==", "class", "grass"],
            "paint": { "fill-color": "#d8e8c8", "fill-opacity": 0.5 }
          },
          {
            "id": "landcover-wood",
            "type": "fill",
            "source": "openmaptiles",
            "source-layer": "landcover",
            "filter": ["==", "class", "wood"],
            "paint": { "fill-color": "#b8d8a8", "fill-opacity": 0.5 }
          },
          {
            "id": "water",
            "type": "fill",
            "source": "openmaptiles",
            "source-layer": "water",
            "paint": { "fill-color": "#a0c8f0" }
          },
          {
            "id": "waterway",
            "type": "line",
            "source": "openmaptiles",
            "source-layer": "waterway",
            "paint": { "line-color": "#a0c8f0", "line-width": 1 }
          },
          {
            "id": "building",
            "type": "fill",
            "source": "openmaptiles",
            "source-layer": "building",
            "minzoom": 13,
            "paint": { "fill-color": "#e0d8d0", "fill-opacity": 0.7 }
          },
          {
            "id": "road-minor",
            "type": "line",
            "source": "openmaptiles",
            "source-layer": "transportation",
            "filter": ["all", ["==", "class", "minor"]],
            "paint": { "line-color": "#ffffff", "line-width": 2 }
          },
          {
            "id": "road-secondary",
            "type": "line",
            "source": "openmaptiles",
            "source-layer": "transportation",
            "filter": ["all", ["in", "class", "secondary", "tertiary"]],
            "paint": { "line-color": "#ffffff", "line-width": 4 }
          },
          {
            "id": "road-primary",
            "type": "line",
            "source": "openmaptiles",
            "source-layer": "transportation",
            "filter": ["all", ["==", "class", "primary"]],
            "paint": { "line-color": "#fcd6a4", "line-width": 5 }
          },
          {
            "id": "road-trunk",
            "type": "line",
            "source": "openmaptiles",
            "source-layer": "transportation",
            "filter": ["all", ["in", "class", "trunk", "motorway"]],
            "paint": { "line-color": "#ffa0a0", "line-width": 6 }
          },
          {
            "id": "place-town",
            "type": "symbol",
            "source": "openmaptiles",
            "source-layer": "place",
            "filter": ["==", "class", "town"],
            "layout": { "text-field": "{name}", "text-size": 14 },
            "paint": { "text-color": "#333333", "text-halo-color": "#ffffff", "text-halo-width": 1 }
          },
          {
            "id": "place-city",
            "type": "symbol",
            "source": "openmaptiles",
            "source-layer": "place",
            "filter": ["==", "class", "city"],
            "layout": { "text-field": "{name}", "text-size": 18 },
            "paint": { "text-color": "#333333", "text-halo-color": "#ffffff", "text-halo-width": 1.5 }
          }
        ]
        """.trimIndent()

    /**
     * Build a style JSON string for offline MBTiles source.
     *
     * @param mbtilesPath Absolute path to the MBTiles file
     * @param name Display name for the source
     * @return Style JSON string ready for MapLibre
     */
    fun buildOfflineStyle(
        mbtilesPath: String,
        name: String = "Offline Map",
    ): String {
        val style =
            JSONObject().apply {
                put("version", 8)
                put("name", name)
                put(
                    "sources",
                    JSONObject().apply {
                        put(
                            "openmaptiles",
                            JSONObject().apply {
                                put("type", "vector")
                                // Use url with mbtiles:// protocol (note: path must start with /)
                                put("url", "mbtiles://$mbtilesPath")
                            },
                        )
                    },
                )
                put("layers", JSONArray(BASE_LAYERS))
            }
        val json = style.toString()
        Log.d("OfflineMapStyleBuilder", "Generated style JSON: $json")
        return json
    }

    /**
     * Build a combined style from multiple MBTiles files, auto-detecting raster vs vector.
     * Raster layers render first (below), vector layers render on top.
     *
     * @param mbtilesPaths List of absolute paths to MBTiles files
     * @return Style JSON string with all sources and layers
     */
    fun buildCombinedOfflineStyle(mbtilesPaths: List<String>): String {
        val sources = JSONObject()
        val rasterLayers = JSONArray()
        var hasVector = false
        var vectorSourceId: String? = null

        mbtilesPaths.forEachIndexed { index, path ->
            val format = getTileFormat(path)
            val sourceId = if (format == "pbf") "vector-$index" else "raster-$index"

            if (format == "pbf") {
                sources.put(
                    sourceId,
                    JSONObject().apply {
                        put("type", "vector")
                        put("url", "mbtiles://$path")
                    },
                )
                if (!hasVector) {
                    vectorSourceId = sourceId
                    hasVector = true
                }
            } else {
                sources.put(
                    sourceId,
                    JSONObject().apply {
                        put("type", "raster")
                        put("url", "mbtiles://$path")
                        put("tileSize", 256)
                    },
                )
                rasterLayers.put(
                    JSONObject().apply {
                        put("id", "raster-layer-$index")
                        put("type", "raster")
                        put("source", sourceId)
                    },
                )
            }
        }

        // Build layers: background -> raster layers -> vector layers
        val layers = JSONArray()
        layers.put(
            JSONObject().apply {
                put("id", "background")
                put("type", "background")
                put("paint", JSONObject().put("background-color", "#f8f4f0"))
            },
        )

        // Add raster layers first (they render below vector)
        for (i in 0 until rasterLayers.length()) {
            layers.put(rasterLayers.getJSONObject(i))
        }

        // Add vector layers on top if any vector source exists
        if (hasVector) {
            val baseLayersArray = JSONArray(BASE_LAYERS)
            for (i in 0 until baseLayersArray.length()) {
                val layer = baseLayersArray.getJSONObject(i)
                // Skip the background layer (already added)
                if (layer.getString("id") == "background") continue
                // Point vector layers at the vector source
                if (layer.has("source") && layer.getString("source") == "openmaptiles") {
                    layer.put("source", vectorSourceId)
                }
                layers.put(layer)
            }
        }

        val style =
            JSONObject().apply {
                put("version", 8)
                put("name", "Offline Maps")
                put("sources", sources)
                put("layers", layers)
            }
        val json = style.toString()
        Log.d("OfflineMapStyleBuilder", "Generated combined style with ${mbtilesPaths.size} sources")
        return json
    }

    /**
     * Build a style JSON string for a raster MBTiles source (e.g., PNG/JPEG tiles).
     *
     * @param mbtilesPath Absolute path to the MBTiles file
     * @param name Display name for the source
     * @return Style JSON string ready for MapLibre
     */
    fun buildRasterOfflineStyle(
        mbtilesPath: String,
        name: String = "Offline Map",
    ): String {
        val style =
            JSONObject().apply {
                put("version", 8)
                put("name", name)
                put(
                    "sources",
                    JSONObject().apply {
                        put(
                            "raster-tiles",
                            JSONObject().apply {
                                put("type", "raster")
                                put("url", "mbtiles://$mbtilesPath")
                                put("tileSize", 256)
                            },
                        )
                    },
                )
                put(
                    "layers",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("id", "background")
                                put("type", "background")
                                put("paint", JSONObject().put("background-color", "#f8f4f0"))
                            },
                        )
                        put(
                            JSONObject().apply {
                                put("id", "raster-layer")
                                put("type", "raster")
                                put("source", "raster-tiles")
                            },
                        )
                    },
                )
            }
        val json = style.toString()
        Log.d("OfflineMapStyleBuilder", "Generated raster style JSON: $json")
        return json
    }

    /**
     * Build the appropriate style (raster or vector) based on the MBTiles tile format.
     * Reads the format from the MBTiles metadata table.
     *
     * @param mbtilesPath Absolute path to the MBTiles file
     * @param name Display name for the source
     * @return Style JSON string ready for MapLibre
     */
    fun buildAutoOfflineStyle(
        mbtilesPath: String,
        name: String = "Offline Map",
    ): String {
        val format = getTileFormat(mbtilesPath)
        Log.d("OfflineMapStyleBuilder", "Detected tile format '$format' for $mbtilesPath")
        return if (format == "pbf") {
            buildOfflineStyle(mbtilesPath, name)
        } else {
            buildRasterOfflineStyle(mbtilesPath, name)
        }
    }

    /**
     * Read the tile format from an MBTiles file's metadata table.
     *
     * @param mbtilesPath Absolute path to the MBTiles file
     * @return The format string (e.g., "pbf", "png", "jpg"), or "png" as default
     */
    fun getTileFormat(mbtilesPath: String): String {
        var db: android.database.sqlite.SQLiteDatabase? = null
        return try {
            db =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    mbtilesPath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                )
            db
                .rawQuery(
                    "SELECT value FROM metadata WHERE name = 'format'",
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "png"
                }
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            Log.w("OfflineMapStyleBuilder", "Failed to read tile format from $mbtilesPath", e)
            "png"
        } finally {
            db?.close()
        }
    }

    /**
     * Build a hybrid style that uses offline tiles with online fallback.
     *
     * Note: This creates a style with both sources. MapLibre doesn't support
     * automatic fallback, so this should be used when you want to show both.
     *
     * @param mbtilesPath Path to offline MBTiles
     * @param onlineStyleUrl URL to online style (for comparison)
     * @return Style JSON string
     */
    fun buildHybridStyle(mbtilesPath: String): String {
        // For now, just use offline - hybrid requires more complex handling
        return buildOfflineStyle(mbtilesPath)
    }

    /**
     * Check if an MBTiles file is valid and readable.
     *
     * @param mbtilesPath Path to MBTiles file
     * @return true if file exists and appears to be valid MBTiles
     */
    fun isValidMBTiles(mbtilesPath: String): Boolean {
        val file = File(mbtilesPath)
        if (!file.exists() || !file.isFile || !file.canRead()) return false

        // Basic validation - check file size > 0
        if (file.length() == 0L) return false

        // Could add SQLite header check here if needed
        return true
    }

    /**
     * Get the offline maps directory.
     */
    fun getOfflineMapsDir(context: Context): File = File(context.filesDir, "offline_maps").also { it.mkdirs() }

    /**
     * List all MBTiles files in the offline maps directory.
     */
    fun listOfflineMaps(context: Context): List<File> {
        val dir = getOfflineMapsDir(context)
        return dir.listFiles { file -> file.extension == "mbtiles" }?.toList() ?: emptyList()
    }
}
