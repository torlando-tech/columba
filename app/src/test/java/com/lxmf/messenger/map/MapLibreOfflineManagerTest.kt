package com.lxmf.messenger.map

import android.app.Application
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.offline.OfflineRegionDefinition
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MapLibreOfflineManager.
 *
 * Tests cover:
 * - Companion object constants
 * - OfflineRegionInfo data class
 *
 * Note: Tests for actual MapLibre operations (createRegion, deleteRegion, etc.)
 * are integration tests that require MapLibre initialization and are covered
 * in the androidTest suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapLibreOfflineManagerTest {
    // ========== Companion Object Constants Tests ==========

    @Test
    fun `DEFAULT_STYLE_URL has correct value`() {
        assertEquals(
            "https://tiles.openfreemap.org/styles/liberty",
            MapLibreOfflineManager.DEFAULT_STYLE_URL,
        )
    }

    @Test
    fun `DEFAULT_TILE_LIMIT is 6000`() {
        assertEquals(6000L, MapLibreOfflineManager.DEFAULT_TILE_LIMIT)
    }

    @Test
    fun `EXTENDED_TILE_LIMIT is 50000`() {
        assertEquals(50000L, MapLibreOfflineManager.EXTENDED_TILE_LIMIT)
    }

    @Test
    fun `DEFAULT_PIXEL_RATIO is 1 point 0`() {
        assertEquals(1.0f, MapLibreOfflineManager.DEFAULT_PIXEL_RATIO)
    }

    @Test
    fun `TILE_LIMIT constants have expected relationship`() {
        assertTrue(
            "EXTENDED_TILE_LIMIT should be greater than DEFAULT_TILE_LIMIT",
            MapLibreOfflineManager.EXTENDED_TILE_LIMIT > MapLibreOfflineManager.DEFAULT_TILE_LIMIT,
        )
    }

    // ========== OfflineRegionInfo Data Class Tests ==========

    @Test
    fun `OfflineRegionInfo holds correct values`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info =
            OfflineRegionInfo(
                id = 42L,
                name = "Test Region",
                definition = mockDefinition,
            )

        assertEquals(42L, info.id)
        assertEquals("Test Region", info.name)
        assertEquals(mockDefinition, info.definition)
    }

    @Test
    fun `OfflineRegionInfo with different ids are not equal`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info1 = OfflineRegionInfo(1L, "Region A", mockDefinition)
        val info2 = OfflineRegionInfo(2L, "Region A", mockDefinition)

        assertFalse(info1 == info2)
    }

    @Test
    fun `OfflineRegionInfo with same values are equal`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info1 = OfflineRegionInfo(1L, "Region A", mockDefinition)
        val info2 = OfflineRegionInfo(1L, "Region A", mockDefinition)

        assertEquals(info1, info2)
    }

    @Test
    fun `OfflineRegionInfo with different names are not equal`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info1 = OfflineRegionInfo(1L, "Region A", mockDefinition)
        val info2 = OfflineRegionInfo(1L, "Region B", mockDefinition)

        assertFalse(info1 == info2)
    }

    @Test
    fun `OfflineRegionInfo copy preserves unchanged fields`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val original = OfflineRegionInfo(1L, "Original", mockDefinition)
        val copied = original.copy(name = "Copied")

        assertEquals(1L, copied.id)
        assertEquals("Copied", copied.name)
        assertEquals(mockDefinition, copied.definition)
    }

    @Test
    fun `OfflineRegionInfo copy with id change`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val original = OfflineRegionInfo(1L, "Region", mockDefinition)
        val copied = original.copy(id = 99L)

        assertEquals(99L, copied.id)
        assertEquals("Region", copied.name)
    }

    @Test
    fun `OfflineRegionInfo hashCode is consistent for equal objects`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info1 = OfflineRegionInfo(1L, "Region", mockDefinition)
        val info2 = OfflineRegionInfo(1L, "Region", mockDefinition)

        assertEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun `OfflineRegionInfo toString contains id`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(42L, "Test", mockDefinition)
        val str = info.toString()

        assertTrue("toString should contain id", str.contains("42"))
    }

    @Test
    fun `OfflineRegionInfo toString contains name`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(1L, "MyRegion", mockDefinition)
        val str = info.toString()

        assertTrue("toString should contain name", str.contains("MyRegion"))
    }

    @Test
    fun `OfflineRegionInfo component functions work`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(1L, "Test", mockDefinition)

        val (id, name, definition) = info
        assertEquals(1L, id)
        assertEquals("Test", name)
        assertEquals(mockDefinition, definition)
    }

    @Test
    fun `OfflineRegionInfo with empty name`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(1L, "", mockDefinition)

        assertEquals("", info.name)
    }

    @Test
    fun `OfflineRegionInfo with id zero`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(0L, "Region", mockDefinition)

        assertEquals(0L, info.id)
    }

    @Test
    fun `OfflineRegionInfo with negative id`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(-1L, "Region", mockDefinition)

        assertEquals(-1L, info.id)
    }

    @Test
    fun `OfflineRegionInfo with max long id`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(Long.MAX_VALUE, "Region", mockDefinition)

        assertEquals(Long.MAX_VALUE, info.id)
    }

    @Test
    fun `OfflineRegionInfo with unicode name`() {
        val mockDefinition = mockk<OfflineRegionDefinition>()

        val info = OfflineRegionInfo(1L, "東京 Region 🗺️", mockDefinition)

        assertEquals("東京 Region 🗺️", info.name)
    }
}
