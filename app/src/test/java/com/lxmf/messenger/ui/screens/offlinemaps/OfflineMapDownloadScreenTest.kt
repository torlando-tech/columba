package com.lxmf.messenger.ui.screens.offlinemaps

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.map.TileDownloadManager
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.RadiusOption
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for OfflineMapDownloadScreen UI components.
 *
 * Tests cover:
 * - LocationSelectionStep display and interactions
 * - RadiusSelectionStep display and interactions
 * - ConfirmDownloadStep display and interactions
 * - DownloadingStep progress states
 * - SummaryRow rendering
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapDownloadScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    // ========== LocationSelectionStep Tests ==========

    @Test
    fun locationStep_displaysInstructions() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Choose the center point for your offline map region.")
            .assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysUseCurrentLocationButton() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Use Current Location").assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysLatitudeField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Latitude").assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysLongitudeField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Longitude").assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysGeohashField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Geohash").assertExists()
        composeTestRule.onNodeWithText("Enter a geohash", substring = true).assertExists()
    }

    @Test
    fun locationStep_nextButtonDisabledWithoutLocation() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun locationStep_nextButtonEnabledWithLocation() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = true,
                latitude = 37.7749,
                longitude = -122.4194,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun locationStep_showsLocationSetCard() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = true,
                latitude = 37.7749,
                longitude = -122.4194,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Location set", substring = true).assertExists()
    }

    // ========== RadiusSelectionStep Tests ==========

    @Test
    fun radiusStep_displaysTitle() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Select Area Size").assertIsDisplayed()
    }

    @Test
    fun radiusStep_displaysAllRadiusOptions() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        // Check all radius option labels are displayed
        RadiusOption.entries.forEach { option ->
            composeTestRule.onNodeWithText(option.label).assertIsDisplayed()
        }
    }

    @Test
    fun radiusStep_displaysZoomRangeSection() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.MEDIUM,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Zoom Range").assertExists()
        composeTestRule.onNodeWithText("Min zoom: 5", substring = true).assertExists()
        composeTestRule.onNodeWithText("Max zoom: 14", substring = true).assertExists()
    }

    @Test
    fun radiusStep_displaysEstimatedDownloadCard() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.MEDIUM,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 2500,
                estimatedSize = "37 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Estimated Download").assertExists()
        composeTestRule.onNodeWithText("2500 tiles").assertExists()
        composeTestRule.onNodeWithText("~37 MB").assertExists()
    }

    @Test
    fun radiusStep_hasBackAndNextButtons() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Back").assertExists()
        composeTestRule.onNodeWithText("Next").assertExists()
    }

    @Test
    fun radiusStep_backButtonCallsOnBack() {
        var backCalled = false

        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = { backCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Back").performScrollTo().performClick()
        assert(backCalled) { "onBack should have been called" }
    }

    @Test
    fun radiusStep_nextButtonCallsOnNext() {
        var nextCalled = false

        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = { nextCalled = true },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Next").performScrollTo().performClick()
        assert(nextCalled) { "onNext should have been called" }
    }

    // ========== ConfirmDownloadStep Tests ==========

    @Test
    fun confirmStep_displaysNameField() {
        composeTestRule.setContent {
            ConfirmDownloadStep(
                latitude = 37.7749,
                longitude = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                name = "",
                onNameChange = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Name Your Map").assertIsDisplayed()
        composeTestRule.onNodeWithText("Region Name").assertIsDisplayed()
    }

    @Test
    fun confirmStep_displaysSummary() {
        composeTestRule.setContent {
            ConfirmDownloadStep(
                latitude = 37.7749,
                longitude = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                name = "Test Region",
                onNameChange = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Summary").assertIsDisplayed()
        composeTestRule.onNodeWithText("Location").assertExists()
        composeTestRule.onNodeWithText("Radius").assertExists()
        composeTestRule.onNodeWithText("10 km").assertExists()
        composeTestRule.onNodeWithText("5 - 14").assertExists()
        composeTestRule.onNodeWithText("1000").assertExists()
        composeTestRule.onNodeWithText("~15 MB").assertExists()
    }

    @Test
    fun confirmStep_displaysWifiWarning() {
        composeTestRule.setContent {
            ConfirmDownloadStep(
                latitude = 37.7749,
                longitude = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                name = "Test",
                onNameChange = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Wi-Fi", substring = true).assertExists()
    }

    @Test
    fun confirmStep_downloadButtonDisabledWithEmptyName() {
        composeTestRule.setContent {
            ConfirmDownloadStep(
                latitude = 37.7749,
                longitude = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                name = "",
                onNameChange = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Download").assertIsNotEnabled()
    }

    @Test
    fun confirmStep_downloadButtonEnabledWithName() {
        composeTestRule.setContent {
            ConfirmDownloadStep(
                latitude = 37.7749,
                longitude = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                name = "My Region",
                onNameChange = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Download").assertIsEnabled()
    }

    @Test
    fun confirmStep_downloadButtonCallsOnStartDownload() {
        var downloadStarted = false

        composeTestRule.setContent {
            ConfirmDownloadStep(
                latitude = 37.7749,
                longitude = -122.4194,
                radiusKm = 10,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000,
                estimatedSize = "15 MB",
                name = "Test",
                onNameChange = {},
                onStartDownload = { downloadStarted = true },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Download").performScrollTo().performClick()
        assert(downloadStarted) { "onStartDownload should have been called" }
    }

    // ========== DownloadingStep Tests ==========

    @Test
    fun downloadingStep_showsPreparingWhenProgressNull() {
        composeTestRule.setContent {
            DownloadingStep(
                progress = null,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Preparing download...").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsIdleStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.IDLE,
            totalTiles = 0,
            downloadedTiles = 0,
            failedTiles = 0,
            bytesDownloaded = 0,
            currentZoom = 0,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Preparing...").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsCalculatingStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.CALCULATING,
            totalTiles = 0,
            downloadedTiles = 0,
            failedTiles = 0,
            bytesDownloaded = 0,
            currentZoom = 0,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Calculating tiles...").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsDownloadingStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
            totalTiles = 100,
            downloadedTiles = 50,
            failedTiles = 0,
            bytesDownloaded = 1024 * 1024,
            currentZoom = 10,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Downloading...").assertIsDisplayed()
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
        composeTestRule.onNodeWithText("50 / 100 tiles").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zoom level 10").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsWritingStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.WRITING,
            totalTiles = 100,
            downloadedTiles = 100,
            failedTiles = 0,
            bytesDownloaded = 2 * 1024 * 1024,
            currentZoom = 14,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Finalizing...").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsCompleteStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.COMPLETE,
            totalTiles = 100,
            downloadedTiles = 100,
            failedTiles = 0,
            bytesDownloaded = 2 * 1024 * 1024,
            currentZoom = 14,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Complete!").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsErrorStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.ERROR,
            totalTiles = 100,
            downloadedTiles = 50,
            failedTiles = 10,
            bytesDownloaded = 1024 * 1024,
            currentZoom = 10,
            errorMessage = "Network error",
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsCancelledStatus() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.CANCELLED,
            totalTiles = 100,
            downloadedTiles = 25,
            failedTiles = 0,
            bytesDownloaded = 500 * 1024,
            currentZoom = 8,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Cancelled").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsFailedTilesCount() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
            totalTiles = 100,
            downloadedTiles = 50,
            failedTiles = 5,
            bytesDownloaded = 1024 * 1024,
            currentZoom = 10,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("5 tiles failed").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsBytesDownloaded() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
            totalTiles = 100,
            downloadedTiles = 50,
            failedTiles = 0,
            bytesDownloaded = (1.5 * 1024 * 1024).toLong(),
            currentZoom = 10,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("1.5 MB downloaded").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_cancelButtonVisibleDuringDownload() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
            totalTiles = 100,
            downloadedTiles = 50,
            failedTiles = 0,
            bytesDownloaded = 1024 * 1024,
            currentZoom = 10,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_cancelButtonNotVisibleWhenComplete() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.COMPLETE,
            totalTiles = 100,
            downloadedTiles = 100,
            failedTiles = 0,
            bytesDownloaded = 2 * 1024 * 1024,
            currentZoom = 14,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun downloadingStep_cancelButtonCallsOnCancel() {
        var cancelCalled = false
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
            totalTiles = 100,
            downloadedTiles = 50,
            failedTiles = 0,
            bytesDownloaded = 1024 * 1024,
            currentZoom = 10,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = { cancelCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(cancelCalled) { "onCancel should have been called" }
    }

    @Test
    fun downloadingStep_hidesZoomLevelWhenZero() {
        val progress = TileDownloadManager.DownloadProgress(
            status = TileDownloadManager.DownloadProgress.Status.CALCULATING,
            totalTiles = 0,
            downloadedTiles = 0,
            failedTiles = 0,
            bytesDownloaded = 0,
            currentZoom = 0,
        )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Zoom level", substring = true).assertDoesNotExist()
    }

    // ========== SummaryRow Tests ==========

    @Test
    fun summaryRow_displaysLabelAndValue() {
        composeTestRule.setContent {
            SummaryRow(
                label = "Test Label",
                value = "Test Value",
            )
        }

        composeTestRule.onNodeWithText("Test Label").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Value").assertIsDisplayed()
    }

    @Test
    fun summaryRow_displaysNumericValue() {
        composeTestRule.setContent {
            SummaryRow(
                label = "Count",
                value = "12345",
            )
        }

        composeTestRule.onNodeWithText("Count").assertIsDisplayed()
        composeTestRule.onNodeWithText("12345").assertIsDisplayed()
    }
}
