package com.lxmf.messenger.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for OfflineMapStyleBuilder.
 *
 * Tests the MapLibre style JSON generation for offline map sources.
 */
class OfflineMapStyleBuilderTest {
    // ========== buildOfflineStyle() Tests ==========

    @Test
    fun `buildOfflineStyle returns valid JSON`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles", "Test Map")

        // Should be parseable as JSON
        val json = JSONObject(style)
        assertNotNull(json)
    }

    @Test
    fun `buildOfflineStyle sets version to 8`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)

        assertEquals(8, json.getInt("version"))
    }

    @Test
    fun `buildOfflineStyle sets name correctly`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles", "My Custom Map")
        val json = JSONObject(style)

        assertEquals("My Custom Map", json.getString("name"))
    }

    @Test
    fun `buildOfflineStyle uses default name when not specified`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)

        assertEquals("Offline Map", json.getString("name"))
    }

    @Test
    fun `buildOfflineStyle creates mbtiles URL source`() {
        val path = "/data/app/maps/test.mbtiles"
        val style = OfflineMapStyleBuilder.buildOfflineStyle(path)
        val json = JSONObject(style)

        val sources = json.getJSONObject("sources")
        val openmaptiles = sources.getJSONObject("openmaptiles")

        assertEquals("vector", openmaptiles.getString("type"))
        assertEquals("mbtiles://$path", openmaptiles.getString("url"))
    }

    @Test
    fun `buildOfflineStyle includes base layers`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)

        val layers = json.getJSONArray("layers")
        assertTrue(layers.length() > 0)
    }

    @Test
    fun `buildOfflineStyle includes background layer`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        var hasBackground = false
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.getString("id") == "background") {
                hasBackground = true
                assertEquals("background", layer.getString("type"))
                break
            }
        }
        assertTrue("Should have background layer", hasBackground)
    }

    @Test
    fun `buildOfflineStyle includes water layer`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        var hasWater = false
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.getString("id") == "water") {
                hasWater = true
                assertEquals("fill", layer.getString("type"))
                assertEquals("openmaptiles", layer.getString("source"))
                break
            }
        }
        assertTrue("Should have water layer", hasWater)
    }

    @Test
    fun `buildOfflineStyle includes road layers`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        val roadLayerIds = mutableListOf<String>()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val id = layer.getString("id")
            if (id.startsWith("road-")) {
                roadLayerIds.add(id)
            }
        }

        assertTrue(roadLayerIds.contains("road-minor"))
        assertTrue(roadLayerIds.contains("road-primary"))
        assertTrue(roadLayerIds.contains("road-trunk"))
    }

    @Test
    fun `buildOfflineStyle includes place labels`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        var hasCityLabel = false
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.getString("id") == "place-city") {
                hasCityLabel = true
                assertEquals("symbol", layer.getString("type"))
                break
            }
        }
        assertTrue("Should have city label layer", hasCityLabel)
    }

    // ========== buildCombinedOfflineStyle() Tests ==========

    @Test
    fun `buildCombinedOfflineStyle returns valid JSON`() {
        val paths = listOf("/path/to/map1.mbtiles", "/path/to/map2.mbtiles")

        val style = OfflineMapStyleBuilder.buildCombinedOfflineStyle(paths)
        val json = JSONObject(style)

        assertNotNull(json)
        assertEquals(8, json.getInt("version"))
    }

    @Test
    fun `buildCombinedOfflineStyle creates multiple sources`() {
        val paths = listOf("/path/to/region1.mbtiles", "/path/to/region2.mbtiles")

        val style = OfflineMapStyleBuilder.buildCombinedOfflineStyle(paths)
        val json = JSONObject(style)
        val sources = json.getJSONObject("sources")

        // Non-existent files default to raster format
        assertTrue(sources.has("raster-0"))
        assertTrue(sources.has("raster-1"))

        val source0 = sources.getJSONObject("raster-0")
        assertEquals("mbtiles:///path/to/region1.mbtiles", source0.getString("url"))
    }

    @Test
    fun `buildCombinedOfflineStyle creates raster layers for non-existent files`() {
        val paths = listOf("/path/to/primary.mbtiles", "/path/to/secondary.mbtiles")

        val style = OfflineMapStyleBuilder.buildCombinedOfflineStyle(paths)
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        // Non-existent files default to raster, so layers should include raster-layer-0
        var hasRasterLayer = false
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.getString("id") == "raster-layer-0") {
                hasRasterLayer = true
                assertEquals("raster", layer.getString("type"))
                break
            }
        }
        assertTrue("Should have raster layer for first source", hasRasterLayer)
    }

    @Test
    fun `buildCombinedOfflineStyle handles empty list`() {
        val style = OfflineMapStyleBuilder.buildCombinedOfflineStyle(emptyList())
        val json = JSONObject(style)

        assertNotNull(json)
        assertEquals("Offline Maps", json.getString("name"))
    }

    // ========== buildHybridStyle() Tests ==========

    @Test
    fun `buildHybridStyle returns valid JSON`() {
        val style = OfflineMapStyleBuilder.buildHybridStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)

        assertNotNull(json)
        assertEquals(8, json.getInt("version"))
    }

    @Test
    fun `buildHybridStyle currently delegates to buildOfflineStyle`() {
        val path = "/path/to/map.mbtiles"

        val hybridStyle = OfflineMapStyleBuilder.buildHybridStyle(path)
        val offlineStyle = OfflineMapStyleBuilder.buildOfflineStyle(path)

        // Currently they should be the same
        assertEquals(offlineStyle, hybridStyle)
    }

    // ========== isValidMBTiles() Tests ==========

    @Test
    fun `isValidMBTiles returns false for non-existent file`() {
        val result = OfflineMapStyleBuilder.isValidMBTiles("/non/existent/file.mbtiles")
        assertFalse(result)
    }

    @Test
    fun `isValidMBTiles returns false for empty path`() {
        val result = OfflineMapStyleBuilder.isValidMBTiles("")
        assertFalse(result)
    }

    @Test
    fun `isValidMBTiles returns false for directory path`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_dir_${System.currentTimeMillis()}")
        tempDir.mkdir()
        try {
            val result = OfflineMapStyleBuilder.isValidMBTiles(tempDir.absolutePath)
            assertFalse(result)
        } finally {
            tempDir.delete()
        }
    }

    @Test
    fun `isValidMBTiles returns true for existing non-empty file`() {
        val tempFile = File.createTempFile("test", ".mbtiles")
        try {
            tempFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            val result = OfflineMapStyleBuilder.isValidMBTiles(tempFile.absolutePath)
            assertTrue(result)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `isValidMBTiles returns false for empty file`() {
        val tempFile = File.createTempFile("test", ".mbtiles")
        try {
            // File is empty by default
            val result = OfflineMapStyleBuilder.isValidMBTiles(tempFile.absolutePath)
            assertFalse(result)
        } finally {
            tempFile.delete()
        }
    }

    // ========== Style Layer Validation Tests ==========

    @Test
    fun `style layers have valid types`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        val validTypes = setOf("background", "fill", "line", "symbol", "circle", "fill-extrusion", "raster", "hillshade", "heatmap")

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val type = layer.getString("type")
            assertTrue("Layer type '$type' should be valid", type in validTypes)
        }
    }

    @Test
    fun `style layers have paint or layout properties`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val isBackground = layer.getString("type") == "background"

            // Background layers only need paint
            if (isBackground) {
                val hasPaint = layer.has("paint")
                assertTrue("Background layer should have paint", hasPaint)
            }
        }
    }

    @Test
    fun `style layers reference openmaptiles source`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.has("source")) {
                assertEquals(
                    "Non-background layers should reference openmaptiles source",
                    "openmaptiles",
                    layer.getString("source"),
                )
            }
        }
    }

    // ========== JSON Structure Tests ==========

    @Test
    fun `style JSON has required top-level keys`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)

        assertTrue(json.has("version"))
        assertTrue(json.has("name"))
        assertTrue(json.has("sources"))
        assertTrue(json.has("layers"))
    }

    @Test
    fun `sources object has openmaptiles key`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val sources = json.getJSONObject("sources")

        assertTrue(sources.has("openmaptiles"))
    }

    @Test
    fun `layers array is not empty`() {
        val style = OfflineMapStyleBuilder.buildOfflineStyle("/path/to/map.mbtiles")
        val json = JSONObject(style)
        val layers = json.getJSONArray("layers")

        assertTrue(layers.length() > 0)
    }

    // ========== Path Handling Tests ==========

    @Test
    fun `buildOfflineStyle handles paths with spaces`() {
        val path = "/path/with spaces/to/map.mbtiles"
        val style = OfflineMapStyleBuilder.buildOfflineStyle(path)
        val json = JSONObject(style)
        val sources = json.getJSONObject("sources")
        val openmaptiles = sources.getJSONObject("openmaptiles")

        assertEquals("mbtiles://$path", openmaptiles.getString("url"))
    }

    @Test
    fun `buildOfflineStyle handles paths with special characters`() {
        val path = "/path/with-special_chars.123/map.mbtiles"
        val style = OfflineMapStyleBuilder.buildOfflineStyle(path)
        val json = JSONObject(style)
        val sources = json.getJSONObject("sources")
        val openmaptiles = sources.getJSONObject("openmaptiles")

        assertEquals("mbtiles://$path", openmaptiles.getString("url"))
    }
}
