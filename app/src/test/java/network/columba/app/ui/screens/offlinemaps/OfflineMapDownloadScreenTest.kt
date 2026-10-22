package network.columba.app.ui.screens.offlinemaps

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.AddressSearchResult
import network.columba.app.viewmodel.DownloadProgress
import network.columba.app.viewmodel.RadiusOption
import network.columba.app.viewmodel.UiMessage
import org.junit.Assert.assertEquals
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

    private fun resText(
        id: Int,
        vararg args: Any,
    ): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *args)

    private fun resPlural(
        id: Int,
        quantity: Int,
        vararg args: Any,
    ): String =
        ApplicationProvider.getApplicationContext<Application>().resources.getQuantityString(id, quantity, *args)

    // ========== LocationSelectionStep Tests ==========

    @Test
    fun locationStep_displaysInstructions() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule
            .onNodeWithText(resText(R.string.offline_map_download_location_subtitle))
            .assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysUseCurrentLocationButton() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_use_current_location)).assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysLatitudeField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysLongitudeField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_longitude)).assertIsDisplayed()
    }

    @Test
    fun locationStep_displaysGeohashField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_geohash_label)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_geohash_helper)).assertExists()
    }

    @Test
    fun locationStep_nextButtonDisabledWithoutLocation() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_next)).assertIsNotEnabled()
    }

    @Test
    fun locationStep_nextButtonEnabledWithLocation() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = true,
                latitude = 37.7749,
                longitude = -122.4194,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_next)).assertIsEnabled()
    }

    @Test
    fun locationStep_showsLocationSetCard() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = true,
                latitude = 37.7749,
                longitude = -122.4194,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_location_set, "37.7749", "-122.4194")).assertExists()
    }

    @Test
    fun locationStep_displaysAddressSearchField() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_search_label)).assertExists()
    }

    @Test
    fun locationStep_displaysAddressSearchResults() {
        val searchResults =
            listOf(
                AddressSearchResult(UiMessage.Raw("Dallas, TX, USA"), 32.7767, -96.7970),
                AddressSearchResult(UiMessage.Raw("Dallas, OR, USA"), 44.9193, -123.3172),
            )

        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "Dallas",
                addressSearchResults = searchResults,
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText("Dallas, TX, USA").assertExists()
        composeTestRule.onNodeWithText("Dallas, OR, USA").assertExists()
    }

    @Test
    fun locationStep_displaysAddressSearchError() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "xyz123",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = UiMessage.Raw("No results found"),
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_no_results)).assertExists()
    }

    @Test
    fun locationStep_showsLoadingIndicatorDuringSearch() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = true,
                addressQuery = "New York",
                addressSearchResults = emptyList(),
                isSearchingAddress = true,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // The CircularProgressIndicator should be displayed when isSearchingAddress is true
        // We can't easily test for CircularProgressIndicator directly, but we can verify
        // the search icon is NOT displayed when searching
        composeTestRule.onNodeWithContentDescription(resText(R.string.offline_map_download_search_cd)).assertDoesNotExist()
    }

    @Test
    fun locationStep_hidesAddressSearchWhenGeocoderUnavailable() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // Address search field should NOT be displayed when geocoder is unavailable
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_search_label)).assertDoesNotExist()
        // But other location options should still be available
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_use_current_location)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_geohash_label)).assertExists()
    }

    // ========== LocationSelectionStep Validation Tests (Issue #574) ==========

    @Test
    fun locationStep_showsErrorForLatitudeAbove90() {
        var receivedLat: Double? = null

        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { lat, _ -> receivedLat = lat },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // Type an out-of-range latitude
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("123")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lat_range)).assertExists()
        // Should NOT have called onLocationSet with invalid value
        assert(receivedLat == null) { "onLocationSet should not be called with out-of-range latitude" }
    }

    @Test
    fun locationStep_showsErrorForLatitudeBelowNegative90() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("-91")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lat_range)).assertExists()
    }

    @Test
    fun locationStep_showsErrorForLongitudeAbove180() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_longitude)).performTextInput("200")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lon_range)).assertExists()
    }

    @Test
    fun locationStep_noErrorForValidLatitude() {
        var receivedLat: Double? = null

        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { lat, _ -> receivedLat = lat },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // Type valid lat; note: lon is still empty so onLocationSet won't fire (lon is null)
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("45")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lat_range)).assertDoesNotExist()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_invalid_number)).assertDoesNotExist()
    }

    @Test
    fun locationStep_noErrorWhileTypingNegativeSign() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // Typing just "-" should not show an error (user is still typing)
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("-")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_invalid_number)).assertDoesNotExist()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lat_range)).assertDoesNotExist()
    }

    @Test
    fun locationStep_showsInvalidNumberForNonNumericInput() {
        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("abc")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_invalid_number)).assertExists()
    }

    @Test
    fun locationStep_callsOnLocationSetWithValidCoordinates() {
        var receivedLat: Double? = null
        var receivedLon: Double? = null

        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = false,
                latitude = null,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { lat, lon ->
                    receivedLat = lat
                    receivedLon = lon
                },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // Type valid longitude first, then latitude — onLocationSet fires when both are valid
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_longitude)).performTextInput("-74")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("40")

        assertEquals(40.0, receivedLat)
        assertEquals(-74.0, receivedLon)
    }

    @Test
    fun locationStep_errorClearsWhenLocationSetExternally() {
        // Simulates: user types invalid lat, then GPS/address sets a valid location
        val latState = mutableStateOf<Double?>(null)

        composeTestRule.setContent {
            LocationSelectionStep(
                hasLocation = latState.value != null,
                latitude = latState.value,
                longitude = null,
                isGeocoderAvailable = false,
                addressQuery = "",
                addressSearchResults = emptyList(),
                isSearchingAddress = false,
                addressSearchError = null,
                onLocationSet = { _, _ -> },
                onCurrentLocationRequest = {},
                onAddressQueryChange = {},
                onSearchAddress = {},
                onSelectAddressResult = {},
                httpEnabled = true,
                onEnableHttp = {},
                onNext = {},
            )
        }

        // Type an invalid latitude — error should appear
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_latitude)).performTextInput("123")
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lat_range)).assertExists()

        // Simulate GPS setting a valid latitude (recomposition with new prop)
        latState.value = 40.0

        // Error should be cleared because latitude prop changed, resetting the remember key
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_err_lat_range)).assertDoesNotExist()
    }

    // ========== RadiusSelectionStep Tests ==========

    @Test
    fun radiusStep_displaysTitle() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_area_size_title)).assertIsDisplayed()
    }

    @Test
    fun radiusStep_displaysAllRadiusOptions() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000L,
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_zoom_range_title)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_min_zoom, 5)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_max_zoom, 14)).assertExists()
    }

    @Test
    fun radiusStep_displaysEstimatedDownloadCard() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.MEDIUM,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 2500L,
                estimatedSize = "37 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_estimated_download_title)).assertExists()
        composeTestRule.onNodeWithText(resPlural(R.plurals.offline_map_download_tiles, 2500, 2500)).assertExists()
        composeTestRule.onNodeWithText("~37 MB").assertExists()
    }

    @Test
    fun radiusStep_hasBackAndNextButtons() {
        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_back)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_next)).assertExists()
    }

    @Test
    fun radiusStep_backButtonCallsOnBack() {
        var backCalled = false

        composeTestRule.setContent {
            RadiusSelectionStep(
                radiusOption = RadiusOption.SMALL,
                minZoom = 5,
                maxZoom = 14,
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = {},
                onBack = { backCalled = true },
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_back)).performScrollTo().performClick()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                onRadiusChange = {},
                onZoomRangeChange = { _, _ -> },
                onNext = { nextCalled = true },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_next)).performScrollTo().performClick()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                name = "",
                httpEnabled = true,
                onNameChange = {},
                onEnableHttp = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_name_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_region_name_label)).assertIsDisplayed()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                name = "Test Region",
                httpEnabled = true,
                onNameChange = {},
                onEnableHttp = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_summary_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_summary_location)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_summary_radius)).assertExists()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                name = "Test",
                httpEnabled = true,
                onNameChange = {},
                onEnableHttp = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_notice)).assertExists()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                name = "",
                httpEnabled = true,
                onNameChange = {},
                onEnableHttp = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_download_button)).assertIsNotEnabled()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                name = "My Region",
                httpEnabled = true,
                onNameChange = {},
                onEnableHttp = {},
                onStartDownload = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_download_button)).assertIsEnabled()
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
                estimatedTileCount = 1000L,
                estimatedSize = "15 MB",
                name = "Test",
                httpEnabled = true,
                onNameChange = {},
                onEnableHttp = {},
                onStartDownload = { downloadStarted = true },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_download_button)).performScrollTo().performClick()
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

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_preparing_download)).assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsPreparingStatus() {
        val progress =
            DownloadProgress(
                progress = 0f,
                completedResources = 0L,
                requiredResources = 0L,
                isComplete = false,
                errorMessage = null,
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_status_preparing)).assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsDownloadingStatus() {
        val progress =
            DownloadProgress(
                progress = 0.5f,
                completedResources = 50L,
                requiredResources = 100L,
                isComplete = false,
                errorMessage = null,
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_status_downloading)).assertIsDisplayed()
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_resources_progress, 50L, 100L)).assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsCompleteStatus() {
        val progress =
            DownloadProgress(
                progress = 1.0f,
                completedResources = 100L,
                requiredResources = 100L,
                isComplete = true,
                errorMessage = null,
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_status_complete)).assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsErrorStatus() {
        val progress =
            DownloadProgress(
                progress = 0.5f,
                completedResources = 50L,
                requiredResources = 100L,
                isComplete = false,
                errorMessage = UiMessage.Raw("Network error"),
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_status_error)).assertIsDisplayed()
    }

    @Test
    fun downloadingStep_showsErrorMessage() {
        val progress =
            DownloadProgress(
                progress = 0.5f,
                completedResources = 50L,
                requiredResources = 100L,
                isComplete = false,
                errorMessage = UiMessage.Raw("Network error"),
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Network error").assertIsDisplayed()
    }

    @Test
    fun downloadingStep_cancelButtonVisibleDuringDownload() {
        val progress =
            DownloadProgress(
                progress = 0.5f,
                completedResources = 50L,
                requiredResources = 100L,
                isComplete = false,
                errorMessage = null,
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_cancel)).assertIsDisplayed()
    }

    @Test
    fun downloadingStep_cancelButtonNotVisibleWhenComplete() {
        val progress =
            DownloadProgress(
                progress = 1.0f,
                completedResources = 100L,
                requiredResources = 100L,
                isComplete = true,
                errorMessage = null,
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_cancel)).assertDoesNotExist()
    }

    @Test
    fun downloadingStep_cancelButtonNotVisibleOnError() {
        val progress =
            DownloadProgress(
                progress = 0.5f,
                completedResources = 50L,
                requiredResources = 100L,
                isComplete = false,
                errorMessage = UiMessage.Raw("Download failed"),
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_cancel)).assertDoesNotExist()
    }

    @Test
    fun downloadingStep_cancelButtonCallsOnCancel() {
        var cancelCalled = false
        val progress =
            DownloadProgress(
                progress = 0.5f,
                completedResources = 50L,
                requiredResources = 100L,
                isComplete = false,
                errorMessage = null,
            )

        composeTestRule.setContent {
            DownloadingStep(
                progress = progress,
                onCancel = { cancelCalled = true },
            )
        }

        composeTestRule.onNodeWithText(resText(R.string.offline_map_download_cancel)).performClick()
        assert(cancelCalled) { "onCancel should have been called" }
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
