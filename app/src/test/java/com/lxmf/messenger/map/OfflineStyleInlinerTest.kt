package com.lxmf.messenger.map

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OfflineStyleInliner].
 *
 * Verifies that TileJSON URL references in style JSON are correctly resolved
 * and inlined, producing a self-contained style for offline map rendering.
 */
class OfflineStyleInlinerTest {
    // A minimal style JSON with a TileJSON reference (vector source)
    private val styleWithTileJsonRef =
        """
        {
            "version": 8,
            "name": "Test Style",
            "sources": {
                "openmaptiles": {
                    "type": "vector",
                    "url": "https://tiles.example.com/planet"
                }
            },
            "layers": [{"id": "background", "type": "background"}]
        }
        """.trimIndent()

    // The TileJSON response for the vector source
    private val tileJsonResponse =
        """
        {
            "tilejson": "3.0.0",
            "tiles": ["https://tiles.example.com/planet/20260204/{z}/{x}/{y}.pbf"],
            "minzoom": 0,
            "maxzoom": 14,
            "bounds": [-180.0, -85.05, 180.0, 85.05],
            "attribution": "Test Attribution"
        }
        """.trimIndent()

    // A style JSON with inline tiles (raster source) — no resolution needed
    private val styleWithInlineTiles =
        """
        {
            "version": 8,
            "sources": {
                "raster": {
                    "type": "raster",
                    "tileSize": 256,
                    "tiles": ["https://tiles.example.com/raster/{z}/{x}/{y}.png"]
                }
            },
            "layers": []
        }
        """.trimIndent()

    // A style with mixed sources (TileJSON ref + inline tiles)
    private val styleWithMixedSources =
        """
        {
            "version": 8,
            "sources": {
                "openmaptiles": {
                    "type": "vector",
                    "url": "https://tiles.example.com/planet"
                },
                "ne2_shaded": {
                    "type": "raster",
                    "tileSize": 256,
                    "maxzoom": 6,
                    "tiles": ["https://tiles.example.com/natural_earth/{z}/{x}/{y}.png"]
                }
            },
            "layers": []
        }
        """.trimIndent()

    // ========== Core inlining ==========

    @Test
    fun `inlines vector source with TileJSON url`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { url ->
                    assertEquals("https://tiles.example.com/planet", url)
                    tileJsonResponse
                }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")

            assertFalse("'url' key should be removed", source.has("url"))
            assertTrue("'tiles' key should be added", source.has("tiles"))

            val tiles = source.getJSONArray("tiles")
            assertEquals(1, tiles.length())
            assertEquals(
                "https://tiles.example.com/planet/20260204/{z}/{x}/{y}.pbf",
                tiles.getString(0),
            )
        }

    @Test
    fun `preserves source type after inlining`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { tileJsonResponse }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertEquals("vector", source.getString("type"))
        }

    @Test
    fun `preserves raster source with inline tiles`() =
        runTest {
            var fetchCalled = false
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithInlineTiles) {
                    fetchCalled = true
                    throw AssertionError("Should not fetch for inline tiles")
                }

            assertFalse("No URL should be fetched for inline tiles", fetchCalled)

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("raster")
            val tiles = source.getJSONArray("tiles")
            assertEquals(
                "https://tiles.example.com/raster/{z}/{x}/{y}.png",
                tiles.getString(0),
            )
        }

    @Test
    fun `handles mixed sources - inlines url, preserves inline tiles`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithMixedSources) { tileJsonResponse }

            val sources = JSONObject(result).getJSONObject("sources")

            // Vector source should be inlined
            val vector = sources.getJSONObject("openmaptiles")
            assertFalse("Vector source 'url' should be removed", vector.has("url"))
            assertTrue("Vector source should have 'tiles'", vector.has("tiles"))

            // Raster source should be preserved as-is
            val raster = sources.getJSONObject("ne2_shaded")
            assertFalse(raster.has("url"))
            assertTrue(raster.has("tiles"))
            assertEquals(
                "https://tiles.example.com/natural_earth/{z}/{x}/{y}.png",
                raster.getJSONArray("tiles").getString(0),
            )
        }

    // ========== TileJSON metadata copying ==========

    @Test
    fun `copies minzoom and maxzoom from TileJSON`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { tileJsonResponse }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertEquals(0, source.getInt("minzoom"))
            assertEquals(14, source.getInt("maxzoom"))
        }

    @Test
    fun `copies bounds from TileJSON`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { tileJsonResponse }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertTrue("Bounds should be copied from TileJSON", source.has("bounds"))
        }

    @Test
    fun `copies attribution from TileJSON`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { tileJsonResponse }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertEquals("Test Attribution", source.getString("attribution"))
        }

    @Test
    fun `does not copy absent TileJSON fields`() =
        runTest {
            val minimalTileJson = """{"tiles": ["https://example.com/{z}/{x}/{y}.pbf"]}"""

            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { minimalTileJson }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertFalse("'url' should be removed", source.has("url"))
            assertTrue("'tiles' should be added", source.has("tiles"))
            // These should NOT be present since they weren't in the TileJSON
            assertFalse("minzoom should not be added if absent in TileJSON", source.has("minzoom"))
            assertFalse("maxzoom should not be added if absent in TileJSON", source.has("maxzoom"))
            assertFalse("bounds should not be added if absent in TileJSON", source.has("bounds"))
            assertFalse("attribution should not be added if absent", source.has("attribution"))
        }

    // ========== Error handling ==========

    @Test
    fun `leaves source unchanged when fetch fails`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) {
                    throw java.io.IOException("Network error")
                }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertTrue("'url' should remain when fetch fails", source.has("url"))
            assertFalse("'tiles' should not be added when fetch fails", source.has("tiles"))
        }

    @Test
    fun `handles malformed TileJSON response`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) {
                    "not valid json"
                }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertTrue("Source should be unchanged for malformed TileJSON", source.has("url"))
        }

    @Test
    fun `handles TileJSON without tiles array`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) {
                    """{"tilejson": "3.0.0", "name": "Test"}"""
                }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertTrue("Source should be unchanged when TileJSON has no tiles", source.has("url"))
        }

    @Test
    fun `handles TileJSON with empty tiles array`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) {
                    """{"tiles": []}"""
                }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("openmaptiles")
            assertTrue("Source should be unchanged when TileJSON has empty tiles", source.has("url"))
        }

    // ========== Edge cases ==========

    @Test
    fun `handles empty sources`() =
        runTest {
            val style = """{"version": 8, "sources": {}, "layers": []}"""
            val result =
                OfflineStyleInliner.inlineTileJsonSources(style) {
                    throw AssertionError("Should not fetch for empty sources")
                }

            assertEquals(8, JSONObject(result).getInt("version"))
        }

    @Test
    fun `handles missing sources key`() =
        runTest {
            val style = """{"version": 8, "layers": []}"""
            val result =
                OfflineStyleInliner.inlineTileJsonSources(style) {
                    throw AssertionError("Should not fetch when sources key is missing")
                }

            assertEquals(8, JSONObject(result).getInt("version"))
        }

    @Test
    fun `skips non-http urls`() =
        runTest {
            val style =
                """
                {
                    "version": 8,
                    "sources": {
                        "custom": {
                            "type": "vector",
                            "url": "mapbox://mapbox.mapbox-streets-v8"
                        }
                    },
                    "layers": []
                }
                """.trimIndent()

            val result =
                OfflineStyleInliner.inlineTileJsonSources(style) {
                    throw AssertionError("Should not fetch non-http URL")
                }

            val source = JSONObject(result).getJSONObject("sources").getJSONObject("custom")
            assertTrue("Non-http URL should be preserved", source.has("url"))
        }

    @Test
    fun `preserves other style properties`() =
        runTest {
            val result =
                OfflineStyleInliner.inlineTileJsonSources(styleWithTileJsonRef) { tileJsonResponse }

            val parsed = JSONObject(result)
            assertEquals(8, parsed.getInt("version"))
            assertEquals("Test Style", parsed.getString("name"))
            assertTrue(parsed.has("layers"))
            assertEquals(1, parsed.getJSONArray("layers").length())
        }

    @Test
    fun `handles multiple TileJSON sources`() =
        runTest {
            val style =
                """
                {
                    "version": 8,
                    "sources": {
                        "source1": {"type": "vector", "url": "https://tiles.example.com/source1"},
                        "source2": {"type": "vector", "url": "https://tiles.example.com/source2"}
                    },
                    "layers": []
                }
                """.trimIndent()

            val fetchedUrls = mutableListOf<String>()
            val result =
                OfflineStyleInliner.inlineTileJsonSources(style) { url ->
                    fetchedUrls.add(url)
                    """{"tiles": ["$url/{z}/{x}/{y}.pbf"], "minzoom": 0, "maxzoom": 14}"""
                }

            assertEquals("Both TileJSON URLs should be fetched", 2, fetchedUrls.size)

            val sources = JSONObject(result).getJSONObject("sources")
            for (name in listOf("source1", "source2")) {
                val source = sources.getJSONObject(name)
                assertFalse("'url' should be removed from $name", source.has("url"))
                assertTrue("'tiles' should be added to $name", source.has("tiles"))
            }
        }

    @Test
    fun `partial fetch failure inlines successful sources and leaves failed ones`() =
        runTest {
            val style =
                """
                {
                    "version": 8,
                    "sources": {
                        "good": {"type": "vector", "url": "https://tiles.example.com/good"},
                        "bad": {"type": "vector", "url": "https://tiles.example.com/bad"}
                    },
                    "layers": []
                }
                """.trimIndent()

            val result =
                OfflineStyleInliner.inlineTileJsonSources(style) { url ->
                    if (url.contains("bad")) throw java.io.IOException("Server error")
                    """{"tiles": ["$url/{z}/{x}/{y}.pbf"]}"""
                }

            val sources = JSONObject(result).getJSONObject("sources")

            // Good source should be inlined
            val good = sources.getJSONObject("good")
            assertFalse("Good source 'url' should be removed", good.has("url"))
            assertTrue("Good source should have 'tiles'", good.has("tiles"))

            // Bad source should be left as-is
            val bad = sources.getJSONObject("bad")
            assertTrue("Bad source 'url' should remain", bad.has("url"))
            assertFalse("Bad source should not have 'tiles'", bad.has("tiles"))
        }

    // ========== Realistic integration test ==========

    @Test
    fun `works with realistic OpenFreeMap style structure`() =
        runTest {
            // Mimics the actual OpenFreeMap liberty style structure
            val realisticStyle =
                """
                {
                    "version": 8,
                    "name": "liberty",
                    "sources": {
                        "openmaptiles": {
                            "type": "vector",
                            "url": "https://tiles.openfreemap.org/planet"
                        },
                        "ne2_shaded": {
                            "type": "raster",
                            "tileSize": 256,
                            "maxzoom": 6,
                            "tiles": ["https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"]
                        }
                    },
                    "layers": [
                        {"id": "background", "type": "background"},
                        {"id": "water", "type": "fill", "source": "openmaptiles", "source-layer": "water"}
                    ]
                }
                """.trimIndent()

            val realisticTileJson =
                """
                {
                    "tilejson": "3.0.0",
                    "name": "OpenFreeMap",
                    "version": "3.15.0",
                    "tiles": ["https://tiles.openfreemap.org/planet/20260204_001001_pt/{z}/{x}/{y}.pbf"],
                    "minzoom": 0,
                    "maxzoom": 14,
                    "bounds": [-180.0, -85.05113, 180.0, 85.05113],
                    "attribution": "OpenFreeMap"
                }
                """.trimIndent()

            val result =
                OfflineStyleInliner.inlineTileJsonSources(realisticStyle) { url ->
                    assertEquals("https://tiles.openfreemap.org/planet", url)
                    realisticTileJson
                }

            val parsed = JSONObject(result)
            val sources = parsed.getJSONObject("sources")

            // Vector source should have inlined tiles with date-versioned URL
            val vector = sources.getJSONObject("openmaptiles")
            assertFalse("Vector source 'url' should be removed", vector.has("url"))
            assertEquals("vector", vector.getString("type"))
            val tiles = vector.getJSONArray("tiles")
            assertEquals(
                "https://tiles.openfreemap.org/planet/20260204_001001_pt/{z}/{x}/{y}.pbf",
                tiles.getString(0),
            )
            assertEquals(0, vector.getInt("minzoom"))
            assertEquals(14, vector.getInt("maxzoom"))

            // Raster source should be preserved unchanged
            val raster = sources.getJSONObject("ne2_shaded")
            assertEquals("raster", raster.getString("type"))
            assertEquals(256, raster.getInt("tileSize"))
            assertEquals(6, raster.getInt("maxzoom"))
            assertEquals(
                "https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png",
                raster.getJSONArray("tiles").getString(0),
            )

            // Layers should be fully preserved
            assertEquals(2, parsed.getJSONArray("layers").length())
            assertEquals("background", parsed.getJSONArray("layers").getJSONObject(0).getString("id"))
            assertEquals("water", parsed.getJSONArray("layers").getJSONObject(1).getString("id"))
        }
}
