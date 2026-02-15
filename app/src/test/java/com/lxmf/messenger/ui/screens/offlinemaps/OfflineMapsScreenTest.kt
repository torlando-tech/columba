package com.lxmf.messenger.ui.screens.offlinemaps

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for OfflineMapsScreen UI components.
 *
 * Tests cover:
 * - EmptyOfflineMapsState display
 * - StorageSummaryCard display
 * - OfflineMapRegionCard for various statuses
 * - StatusChip rendering for all status types
 * - Delete dialog interactions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapsScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    // ========== EmptyOfflineMapsState Tests ==========

    @Test
    fun emptyState_displaysNoOfflineMapsText() {
        composeTestRule.setContent {
            EmptyOfflineMapsState()
        }

        composeTestRule.onNodeWithText("No Offline Maps").assertIsDisplayed()
    }

    @Test
    fun emptyState_displaysInstructions() {
        composeTestRule.setContent {
            EmptyOfflineMapsState()
        }

        composeTestRule.onNodeWithText("Download or import map regions for offline use").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tap + to download, or use the import button to load an MBTiles file").assertIsDisplayed()
    }

    // ========== StorageSummaryCard Tests ==========

    @Test
    fun storageSummaryCard_displaysTotalStorage() {
        composeTestRule.setContent {
            StorageSummaryCard(
                totalStorage = "150 MB",
                regionCount = 3,
            )
        }

        composeTestRule.onNodeWithText("Total Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("150 MB").assertIsDisplayed()
    }

    @Test
    fun storageSummaryCard_displaysRegionCount() {
        composeTestRule.setContent {
            StorageSummaryCard(
                totalStorage = "50 MB",
                regionCount = 5,
            )
        }

        composeTestRule.onNodeWithText("Regions").assertIsDisplayed()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun storageSummaryCard_displaysZeroRegions() {
        composeTestRule.setContent {
            StorageSummaryCard(
                totalStorage = "0 B",
                regionCount = 0,
            )
        }

        composeTestRule.onNodeWithText("0 B").assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
    }

    @Test
    fun storageSummaryCard_displaysGBStorage() {
        composeTestRule.setContent {
            StorageSummaryCard(
                totalStorage = "1.5 GB",
                regionCount = 10,
            )
        }

        composeTestRule.onNodeWithText("1.5 GB").assertIsDisplayed()
        composeTestRule.onNodeWithText("10").assertIsDisplayed()
    }

    // ========== StatusChip Tests ==========

    @Test
    fun statusChip_displaysPendingText() {
        composeTestRule.setContent {
            StatusChip(status = OfflineMapRegion.Status.PENDING)
        }

        composeTestRule.onNodeWithText("Pending").assertIsDisplayed()
    }

    @Test
    fun statusChip_displaysDownloadingText() {
        composeTestRule.setContent {
            StatusChip(status = OfflineMapRegion.Status.DOWNLOADING)
        }

        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
    }

    @Test
    fun statusChip_displaysCompleteText() {
        composeTestRule.setContent {
            StatusChip(status = OfflineMapRegion.Status.COMPLETE)
        }

        composeTestRule.onNodeWithText("Complete").assertIsDisplayed()
    }

    @Test
    fun statusChip_displaysErrorText() {
        composeTestRule.setContent {
            StatusChip(status = OfflineMapRegion.Status.ERROR)
        }

        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
    }

    // ========== OfflineMapRegionCard Tests ==========

    @Test
    fun regionCard_displaysRegionName() {
        val region = createTestRegion(name = "San Francisco")

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("San Francisco").assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysSize() {
        val region = createTestRegion(sizeBytes = 50 * 1024 * 1024L) // 50 MB

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("50 MB").assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysRadiusAndZoom() {
        val region = createTestRegion(radiusKm = 10, minZoom = 5, maxZoom = 14)

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("10 km radius - Zoom 5-14").assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysCompleteStatus() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Complete").assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysDownloadingStatus() {
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.DOWNLOADING,
                downloadProgress = 0.5f,
                tileCount = 1000,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
        composeTestRule.onNodeWithText("50% - 1000 tiles").assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysErrorMessage() {
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.ERROR,
                errorMessage = "Network error occurred",
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Network error occurred").assertIsDisplayed()
    }

    @Test
    fun regionCard_hasDeleteButton() {
        val region = createTestRegion()

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Delete").assertIsEnabled()
    }

    @Test
    fun regionCard_deleteButtonDisabledWhileDeleting() {
        val region = createTestRegion()

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = true,
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete").assertIsNotEnabled()
    }

    @Test
    fun regionCard_showsDeleteDialogOnClick() {
        val region = createTestRegion(name = "Test Region")

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete").performClick()

        composeTestRule.onNodeWithText("Delete Offline Map").assertIsDisplayed()
        composeTestRule.onNodeWithText("Are you sure you want to delete \"Test Region\"? This will free up 15 MB of storage.")
            .assertIsDisplayed()
    }

    @Test
    fun regionCard_deleteDialogHasCancelButton() {
        val region = createTestRegion()

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun regionCard_deleteDialogCancelDismisses() {
        val region = createTestRegion()

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        composeTestRule.onNodeWithText("Delete Offline Map").assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onNodeWithText("Delete Offline Map").assertDoesNotExist()
    }

    @Test
    fun regionCard_deleteDialogConfirmCallsOnDelete() {
        var deleteCalled = false
        val region = createTestRegion()

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = { deleteCalled = true },
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        composeTestRule.onNodeWithText("Delete", useUnmergedTree = true)
            .performClick()

        assert(deleteCalled) { "onDelete should have been called" }
    }

    @Test
    fun regionCard_displaysCompletedDate() {
        // Dec 31, 2023
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.COMPLETE,
                completedAt = 1703980800000L,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        // The exact date format depends on locale, just check it contains "Downloaded"
        composeTestRule.onNodeWithText("Downloaded", substring = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_pendingStatusNoCompletedDate() {
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.PENDING,
                completedAt = null,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Downloaded", substring = true).assertDoesNotExist()
    }

    // ========== Update Check UI Tests ==========

    @Test
    fun regionCard_displaysCheckForUpdatesButton() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = null,
                onCheckForUpdates = {},
            )
        }

        composeTestRule.onNodeWithText("Check for Updates", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysCheckingState() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)
        val updateResult =
            com.lxmf.messenger.viewmodel.UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = null,
                isChecking = true,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = updateResult,
            )
        }

        composeTestRule.onNodeWithText("Checking...", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysUpdateAvailableState() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)
        val updateResult =
            com.lxmf.messenger.viewmodel.UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v2",
                isChecking = false,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = updateResult,
            )
        }

        composeTestRule.onNodeWithText("Update available", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Update Now", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_displaysUpToDateState() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)
        val updateResult =
            com.lxmf.messenger.viewmodel.UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v1",
                isChecking = false,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = updateResult,
            )
        }

        composeTestRule.onNodeWithText("Up to date", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_checkForUpdatesCallsCallback() {
        var callbackCalled = false
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = null,
                onCheckForUpdates = { callbackCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Check for Updates", useUnmergedTree = true).performClick()

        assert(callbackCalled) { "onCheckForUpdates should have been called" }
    }

    @Test
    fun regionCard_showsUpdateDialogOnUpdateNowClick() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE, name = "Test Region")
        val updateResult =
            com.lxmf.messenger.viewmodel.UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v2",
                isChecking = false,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = updateResult,
            )
        }

        composeTestRule.onNodeWithText("Update Now", useUnmergedTree = true).performClick()

        composeTestRule.onNodeWithText("Update Offline Map").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download the latest map data", substring = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_updateDialogCancelDismisses() {
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)
        val updateResult =
            com.lxmf.messenger.viewmodel.UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v2",
                isChecking = false,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = updateResult,
            )
        }

        composeTestRule.onNodeWithText("Update Now", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Update Offline Map").assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onNodeWithText("Update Offline Map").assertDoesNotExist()
    }

    @Test
    fun regionCard_updateDialogConfirmCallsCallback() {
        var callbackCalled = false
        val region = createTestRegion(status = OfflineMapRegion.Status.COMPLETE)
        val updateResult =
            com.lxmf.messenger.viewmodel.UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v2",
                isChecking = false,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
                updateCheckResult = updateResult,
                onUpdateNow = { callbackCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Update Now", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Update", useUnmergedTree = true).performClick()

        assert(callbackCalled) { "onUpdateNow should have been called" }
    }

    @Test
    fun regionCard_displaysTileVersion() {
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.COMPLETE,
                tileVersion = "20260107_001001_pt",
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Map data: 2026-01-07", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regionCard_pendingStatusNoCheckForUpdates() {
        val region = createTestRegion(status = OfflineMapRegion.Status.PENDING)

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Check for Updates", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun regionCard_downloadingStatusNoCheckForUpdates() {
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.DOWNLOADING,
                downloadProgress = 0.5f,
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Check for Updates", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun regionCard_errorStatusNoCheckForUpdates() {
        val region =
            createTestRegion(
                status = OfflineMapRegion.Status.ERROR,
                errorMessage = "Test error",
            )

        composeTestRule.setContent {
            OfflineMapRegionCard(
                region = region,
                onDelete = {},
                isDeleting = false,
            )
        }

        composeTestRule.onNodeWithText("Check for Updates", useUnmergedTree = true).assertDoesNotExist()
    }

    // ========== Helper Functions ==========

    @Suppress("LongParameterList")
    private fun createTestRegion(
        id: Long = 1L,
        name: String = "Test Region",
        status: OfflineMapRegion.Status = OfflineMapRegion.Status.COMPLETE,
        // 15 MB
        sizeBytes: Long = 15 * 1024 * 1024L,
        radiusKm: Int = 10,
        minZoom: Int = 5,
        maxZoom: Int = 14,
        downloadProgress: Float = 1.0f,
        tileCount: Int = 500,
        errorMessage: String? = null,
        completedAt: Long? = System.currentTimeMillis(),
        tileVersion: String? = null,
    ): OfflineMapRegion {
        return OfflineMapRegion(
            id = id,
            name = name,
            centerLatitude = 37.7749,
            centerLongitude = -122.4194,
            radiusKm = radiusKm,
            minZoom = minZoom,
            maxZoom = maxZoom,
            status = status,
            mbtilesPath = "/path/to/test.mbtiles",
            tileCount = tileCount,
            sizeBytes = sizeBytes,
            downloadProgress = downloadProgress,
            errorMessage = errorMessage,
            createdAt = System.currentTimeMillis(),
            completedAt = completedAt,
            source = OfflineMapRegion.Source.HTTP,
            tileVersion = tileVersion,
        )
    }
}
