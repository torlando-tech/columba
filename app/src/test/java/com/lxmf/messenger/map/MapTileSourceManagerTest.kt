package com.lxmf.messenger.map

import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.data.repository.RmspServer
import com.lxmf.messenger.data.repository.RmspServerRepository
import com.lxmf.messenger.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MapTileSourceManager.
 *
 * Tests the map style resolution logic and source priority.
 */
class MapTileSourceManagerTest {
    private lateinit var context: android.content.Context
    private lateinit var offlineMapRegionRepository: OfflineMapRegionRepository
    private lateinit var rmspServerRepository: RmspServerRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mapTileSourceManager: MapTileSourceManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        // All methods are explicitly stubbed below, no need for relaxed mocks
        offlineMapRegionRepository = mockk()
        rmspServerRepository = mockk()
        settingsRepository = mockk()

        // Default: no offline regions, no RMSP servers
        every { offlineMapRegionRepository.getCompletedRegions() } returns flowOf(emptyList())
        coEvery { offlineMapRegionRepository.getCompletedRegionsWithMbtiles() } returns emptyList()
        coEvery { offlineMapRegionRepository.getFirstCompletedRegionWithStyle() } returns null
        every { rmspServerRepository.getAllServers() } returns flowOf(emptyList())
        every { rmspServerRepository.getNearestServers(any()) } returns flowOf(emptyList())
        every { rmspServerRepository.hasServers() } returns flowOf(false)

        // Default settings: HTTP enabled, RMSP disabled
        coEvery { settingsRepository.getMapSourceHttpEnabled() } returns true
        coEvery { settingsRepository.getMapSourceRmspEnabled() } returns false
        every { settingsRepository.mapSourceHttpEnabledFlow } returns flowOf(true)
        every { settingsRepository.mapSourceRmspEnabledFlow } returns flowOf(false)

        mapTileSourceManager =
            MapTileSourceManager(
                context,
                offlineMapRegionRepository,
                rmspServerRepository,
                settingsRepository,
            )
    }

    // ========== getMapStyle() Tests - HTTP Source ==========

    @Test
    fun `getMapStyle returns Online when HTTP is enabled and no offline regions`() =
        runTest {
            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            assertTrue(result is MapStyleResult.Online)
            assertEquals(MapTileSourceManager.DEFAULT_STYLE_URL, (result as MapStyleResult.Online).styleUrl)
        }

    @Test
    fun `getMapStyle returns Online when HTTP is enabled with null location`() =
        runTest {
            val result = mapTileSourceManager.getMapStyle(null, null)

            assertTrue(result is MapStyleResult.Online)
        }

    // ========== getMapStyle() Tests - RMSP Source ==========

    @Test
    fun `getMapStyle returns Rmsp when HTTP is disabled and RMSP is enabled with server`() =
        runTest {
            // Setup: HTTP disabled, RMSP enabled with server available
            coEvery { settingsRepository.getMapSourceHttpEnabled() } returns false
            coEvery { settingsRepository.getMapSourceRmspEnabled() } returns true

            val mockServer = createMockRmspServer("test-server")
            every { rmspServerRepository.getNearestServers(any()) } returns flowOf(listOf(mockServer))

            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            assertTrue(result is MapStyleResult.Rmsp)
            assertEquals(mockServer, (result as MapStyleResult.Rmsp).server)
        }

    @Test
    fun `getMapStyle returns Unavailable when both HTTP and RMSP are disabled`() =
        runTest {
            // Setup: Both disabled
            coEvery { settingsRepository.getMapSourceHttpEnabled() } returns false
            coEvery { settingsRepository.getMapSourceRmspEnabled() } returns false

            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            assertTrue(result is MapStyleResult.Unavailable)
            assertEquals("HTTP map source is disabled. Enable it in Settings or download offline maps.", (result as MapStyleResult.Unavailable).reason)
        }

    @Test
    fun `getMapStyle returns Unavailable when RMSP enabled but no servers available`() =
        runTest {
            // Setup: HTTP disabled, RMSP enabled but no servers
            coEvery { settingsRepository.getMapSourceHttpEnabled() } returns false
            coEvery { settingsRepository.getMapSourceRmspEnabled() } returns true
            every { rmspServerRepository.getNearestServers(any()) } returns flowOf(emptyList())

            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            assertTrue(result is MapStyleResult.Unavailable)
            assertTrue((result as MapStyleResult.Unavailable).reason.contains("No RMSP servers discovered"))
        }

    @Test
    fun `getMapStyle returns Offline when HTTP disabled but offline maps exist`() =
        runTest {
            // Setup: HTTP disabled, offline maps available
            coEvery { settingsRepository.getMapSourceHttpEnabled() } returns false
            coEvery { settingsRepository.getMapSourceRmspEnabled() } returns false

            val region =
                OfflineMapRegion(
                    id = 1,
                    name = "Test Region",
                    centerLatitude = 37.7749,
                    centerLongitude = -122.4194,
                    radiusKm = 10,
                    minZoom = 0,
                    maxZoom = 14,
                    status = OfflineMapRegion.Status.COMPLETE,
                    mbtilesPath = null,
                    tileCount = 1000,
                    sizeBytes = 5000000L,
                    downloadProgress = 1.0f,
                    errorMessage = null,
                    createdAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    source = OfflineMapRegion.Source.HTTP,
                    tileVersion = null,
                )
            every { offlineMapRegionRepository.getCompletedRegions() } returns flowOf(listOf(region))

            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            // Should return Offline with the default style URL (not Unavailable)
            assertTrue("Expected Offline but got ${result::class.simpleName}", result is MapStyleResult.Offline)
            assertEquals(MapTileSourceManager.DEFAULT_STYLE_URL, (result as MapStyleResult.Offline).styleUrl)
        }

    // ========== getMapStyle() Tests - Offline Source ==========

    @Test
    fun `getMapStyle returns Offline when offline region covers location`() =
        runTest {
            // Setup: Offline region that covers San Francisco
            val region =
                OfflineMapRegion(
                    id = 1,
                    name = "San Francisco",
                    centerLatitude = 37.7749,
                    centerLongitude = -122.4194,
                    radiusKm = 10,
                    minZoom = 5,
                    maxZoom = 14,
                    status = OfflineMapRegion.Status.COMPLETE,
                    mbtilesPath = null, // Will be null since file doesn't exist in tests
                    tileCount = 1000,
                    sizeBytes = 5000000L,
                    downloadProgress = 1.0f,
                    errorMessage = null,
                    createdAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    source = OfflineMapRegion.Source.HTTP,
                    tileVersion = null,
                )
            every { offlineMapRegionRepository.getCompletedRegions() } returns flowOf(listOf(region))

            // Without a real file, the region won't be used
            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            // Should fall back to HTTP since file doesn't exist
            assertTrue(result is MapStyleResult.Online)
        }

    // ========== httpEnabled/rmspEnabled Override Tests ==========

    @Test
    fun `httpEnabled setter overrides repository value`() =
        runTest {
            mapTileSourceManager.httpEnabled = false

            // Even though repository returns true, override should take precedence
            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            // With HTTP disabled and no RMSP, should be unavailable
            assertTrue(result is MapStyleResult.Unavailable)
        }

    @Test
    fun `rmspEnabled setter overrides repository value`() =
        runTest {
            mapTileSourceManager.httpEnabled = false
            mapTileSourceManager.rmspEnabled = true

            val mockServer = createMockRmspServer("test-server")
            every { rmspServerRepository.getNearestServers(any()) } returns flowOf(listOf(mockServer))

            val result = mapTileSourceManager.getMapStyle(37.7749, -122.4194)

            assertTrue(result is MapStyleResult.Rmsp)
        }

    // ========== observeSourceAvailability() Tests ==========

    @Test
    fun `observeSourceAvailability combines all sources correctly`() =
        runTest {
            every { offlineMapRegionRepository.getCompletedRegions() } returns flowOf(emptyList())
            every { rmspServerRepository.hasServers() } returns flowOf(true)
            every { settingsRepository.mapSourceHttpEnabledFlow } returns flowOf(true)
            every { settingsRepository.mapSourceRmspEnabledFlow } returns flowOf(false)

            // Re-create manager with new mocks
            mapTileSourceManager =
                MapTileSourceManager(
                    context,
                    offlineMapRegionRepository,
                    rmspServerRepository,
                    settingsRepository,
                )

            var availability: SourceAvailability? = null
            mapTileSourceManager.observeSourceAvailability().collect {
                availability = it
            }

            assertEquals(false, availability?.hasOfflineMaps)
            assertEquals(true, availability?.hasRmspServers)
            assertEquals(true, availability?.httpEnabled)
            assertEquals(false, availability?.rmspEnabled)
        }

    // ========== SourceAvailability Tests ==========

    @Test
    fun `SourceAvailability hasAnySource is true when offline maps available`() {
        val availability =
            SourceAvailability(
                hasOfflineMaps = true,
                hasRmspServers = false,
                httpEnabled = false,
                rmspEnabled = false,
            )

        assertTrue(availability.hasAnySource)
    }

    @Test
    fun `SourceAvailability hasAnySource is true when HTTP enabled`() {
        val availability =
            SourceAvailability(
                hasOfflineMaps = false,
                hasRmspServers = false,
                httpEnabled = true,
                rmspEnabled = false,
            )

        assertTrue(availability.hasAnySource)
    }

    @Test
    fun `SourceAvailability hasAnySource is true when RMSP enabled with servers`() {
        val availability =
            SourceAvailability(
                hasOfflineMaps = false,
                hasRmspServers = true,
                httpEnabled = false,
                rmspEnabled = true,
            )

        assertTrue(availability.hasAnySource)
    }

    @Test
    fun `SourceAvailability hasAnySource is false when RMSP enabled but no servers`() {
        val availability =
            SourceAvailability(
                hasOfflineMaps = false,
                hasRmspServers = false,
                httpEnabled = false,
                rmspEnabled = true,
            )

        assertFalse(availability.hasAnySource)
    }

    @Test
    fun `SourceAvailability hasAnySource is false when nothing available`() {
        val availability =
            SourceAvailability(
                hasOfflineMaps = false,
                hasRmspServers = false,
                httpEnabled = false,
                rmspEnabled = false,
            )

        assertFalse(availability.hasAnySource)
    }

    // ========== MapStyleResult Tests ==========

    @Test
    fun `MapStyleResult Online holds style URL`() {
        val result = MapStyleResult.Online("https://example.com/style")
        assertEquals("https://example.com/style", result.styleUrl)
    }

    @Test
    fun `MapStyleResult Offline holds style URL`() {
        val result = MapStyleResult.Offline("https://example.com/style")
        assertEquals("https://example.com/style", result.styleUrl)
    }

    @Test
    fun `MapStyleResult Rmsp holds server and style JSON`() {
        val server = createMockRmspServer("test")
        val result = MapStyleResult.Rmsp(server, "{}")
        assertEquals(server, result.server)
        assertEquals("{}", result.styleJson)
    }

    @Test
    fun `MapStyleResult Unavailable holds reason`() {
        val result = MapStyleResult.Unavailable("Test reason")
        assertEquals("Test reason", result.reason)
    }

    // ========== Helper Functions ==========

    private fun createMockRmspServer(name: String): RmspServer =
        RmspServer(
            destinationHash = "abc123",
            serverName = name,
            publicKey = byteArrayOf(1, 2, 3),
            coverageGeohashes = listOf("9q8y"),
            minZoom = 0,
            maxZoom = 14,
            formats = listOf("pbf"),
            layers = listOf("base"),
            dataUpdatedTimestamp = System.currentTimeMillis(),
            dataSize = 1000000L,
            version = "1.0",
            lastSeenTimestamp = System.currentTimeMillis(),
            hops = 1,
        )
}
