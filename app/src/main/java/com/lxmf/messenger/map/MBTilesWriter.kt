package com.lxmf.messenger.map

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import kotlin.math.pow

/**
 * Writer for MBTiles format files.
 *
 * MBTiles is a SQLite-based format for storing map tiles.
 * This implementation creates vector tile (MVT/PBF) compatible MBTiles.
 *
 * Note: MBTiles uses TMS tile scheme (origin at bottom-left),
 * while most online tile servers use XYZ (origin at top-left).
 * This writer handles the conversion automatically.
 */
class MBTilesWriter(
    private val file: File,
    private val name: String,
    private val description: String = "",
    private val minZoom: Int = 0,
    private val maxZoom: Int = 14,
    private val bounds: Bounds? = null,
    private val center: Center? = null,
) : Closeable {
    /**
     * Geographic bounds in WGS84 coordinates.
     */
    data class Bounds(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
    ) {
        override fun toString(): String = "$west,$south,$east,$north"
    }

    /**
     * Center point with zoom level.
     */
    data class Center(
        val longitude: Double,
        val latitude: Double,
        val zoom: Int,
    ) {
        override fun toString(): String = "$longitude,$latitude,$zoom"
    }

    private var db: SQLiteDatabase? = null
    private var tileCount = 0
    private var totalBytes = 0L

    /**
     * Open the MBTiles file for writing.
     * Creates the file and initializes the schema if it doesn't exist.
     */
    fun open() {
        // Ensure parent directory exists
        file.parentFile?.mkdirs()

        // Delete existing file to start fresh
        if (file.exists()) {
            file.delete()
        }

        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createSchema()
        writeMetadata()
    }

    private fun createSchema() {
        db?.let { database ->
            // Create tiles table
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tiles (
                    zoom_level INTEGER NOT NULL,
                    tile_column INTEGER NOT NULL,
                    tile_row INTEGER NOT NULL,
                    tile_data BLOB NOT NULL,
                    PRIMARY KEY (zoom_level, tile_column, tile_row)
                )
                """.trimIndent(),
            )

            // Create metadata table
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS metadata (
                    name TEXT NOT NULL,
                    value TEXT,
                    PRIMARY KEY (name)
                )
                """.trimIndent(),
            )

            // Create index for faster tile lookups
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS tiles_index ON tiles (zoom_level, tile_column, tile_row)",
            )
        }
    }

    private fun writeMetadata() {
        db?.let { database ->
            val metadata =
                mutableMapOf(
                    "name" to name,
                    "format" to "pbf", // Vector tiles
                    "type" to "baselayer",
                    "version" to "1.0.0",
                    "minzoom" to minZoom.toString(),
                    "maxzoom" to maxZoom.toString(),
                    "scheme" to "tms", // MBTiles uses TMS scheme
                )

            if (description.isNotEmpty()) {
                metadata["description"] = description
            }

            bounds?.let {
                metadata["bounds"] = it.toString()
            }

            center?.let {
                metadata["center"] = it.toString()
            }

            // Write each metadata entry
            for ((key, value) in metadata) {
                val values =
                    ContentValues().apply {
                        put("name", key)
                        put("value", value)
                    }
                database.insertWithOnConflict(
                    "metadata",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    /**
     * Write a tile to the MBTiles file.
     *
     * @param z Zoom level
     * @param x Tile X coordinate (XYZ scheme, origin at top-left)
     * @param y Tile Y coordinate (XYZ scheme, origin at top-left)
     * @param data The tile data (PBF/MVT bytes)
     */
    fun writeTile(
        z: Int,
        x: Int,
        y: Int,
        data: ByteArray,
    ) {
        db?.let { database ->
            // Convert from XYZ to TMS y-coordinate
            // TMS y = (2^zoom - 1) - xyz_y
            val tmsY = flipY(z, y)

            val values =
                ContentValues().apply {
                    put("zoom_level", z)
                    put("tile_column", x)
                    put("tile_row", tmsY)
                    put("tile_data", data)
                }

            database.insertWithOnConflict(
                "tiles",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )

            tileCount++
            totalBytes += data.size
        }
    }

    /**
     * Begin a transaction for bulk inserts.
     * Call [endTransaction] when done.
     *
     * Note: Uses NON-EXCLUSIVE mode to allow concurrent reads during tile writes.
     * Only one writer should exist at a time (enforced by singleton ViewModel).
     */
    fun beginTransaction() {
        db?.beginTransactionNonExclusive()
    }

    /**
     * End a transaction, committing if successful.
     * @param success Whether to commit the transaction
     */
    fun endTransaction(success: Boolean = true) {
        db?.let { database ->
            try {
                if (success && database.inTransaction()) {
                    database.setTransactionSuccessful()
                }
            } finally {
                if (database.inTransaction()) {
                    database.endTransaction()
                }
            }
        }
    }

    /**
     * Get the current tile count.
     */
    fun getTileCount(): Int = tileCount

    /**
     * Get the total bytes written.
     */
    fun getTotalBytes(): Long = totalBytes

    /**
     * Get the file size on disk.
     */
    fun getFileSize(): Long = file.length()

    /**
     * Optimize the database by running VACUUM.
     * Should be called after bulk inserts are complete.
     */
    fun optimize() {
        db?.execSQL("VACUUM")
    }

    override fun close() {
        db?.close()
        db = null
    }

    companion object {
        /**
         * Convert XYZ y-coordinate to TMS y-coordinate.
         *
         * MBTiles uses TMS scheme (origin at bottom-left),
         * while most tile servers use XYZ (origin at top-left).
         */
        fun flipY(
            zoom: Int,
            xyzY: Int,
        ): Int {
            return (2.0.pow(zoom) - 1 - xyzY).toInt()
        }

        /**
         * Convert TMS y-coordinate to XYZ y-coordinate.
         */
        fun tmsToXyzY(
            zoom: Int,
            tmsY: Int,
        ): Int {
            return (2.0.pow(zoom) - 1 - tmsY).toInt()
        }

        /**
         * Calculate bounds from center point and radius in kilometers.
         */
        fun boundsFromCenter(
            centerLat: Double,
            centerLon: Double,
            radiusKm: Int,
        ): Bounds {
            // Approximate degrees per km at this latitude
            val latDegPerKm = 1.0 / 111.0 // ~111 km per degree latitude
            val lonDegPerKm = 1.0 / (111.0 * kotlin.math.cos(Math.toRadians(centerLat)))

            val latOffset = radiusKm * latDegPerKm
            val lonOffset = radiusKm * lonDegPerKm

            return Bounds(
                west = centerLon - lonOffset,
                south = centerLat - latOffset,
                east = centerLon + lonOffset,
                north = centerLat + latOffset,
            )
        }
    }
}
