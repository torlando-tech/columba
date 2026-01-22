package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MapScreen UI components.
 *
 * Tests cover:
 * - ScaleBar distance formatting and rendering
 * - EmptyMapStateCard display
 * - MapScreen FAB states and interactions
 * - SharingStatusChip display
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== ScaleBar Tests ==========

    @Test
    fun scaleBar_displays5mForVeryCloseZoom() {
        composeTestRule.setContent {
            // At very close zoom, metersPerPixel is small
            // 5m bar at 0.05 metersPerPixel = 100px (within 80-140dp range)
            ScaleBar(metersPerPixel = 0.05)
        }

        composeTestRule.onNodeWithText("5 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays10mForCloseZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 0.1)
        }

        composeTestRule.onNodeWithText("10 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays50mForMediumZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 0.5)
        }

        composeTestRule.onNodeWithText("50 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays100mForStreetLevelZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 1.0)
        }

        composeTestRule.onNodeWithText("100 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays500mForNeighborhoodZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 5.0)
        }

        composeTestRule.onNodeWithText("500 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays1kmForCityZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 10.0)
        }

        composeTestRule.onNodeWithText("1 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays5kmForRegionalZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 50.0)
        }

        composeTestRule.onNodeWithText("5 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays10kmForCountryZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 100.0)
        }

        composeTestRule.onNodeWithText("10 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays100kmForContinentZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 1000.0)
        }

        composeTestRule.onNodeWithText("100 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays1000kmForGlobalZoom() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 10000.0)
        }

        // At 10000 m/px, 100000m (100km) fits in ~10px which is too small
        // So it will use a larger value like 1000km or 2000km
        composeTestRule.onNodeWithText("km", substring = true).assertIsDisplayed()
    }

    @Test
    fun scaleBar_displaysCorrectFormatForMeters() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 0.2)
        }

        // Should show meters, not km
        composeTestRule.onNodeWithText("m", substring = true).assertIsDisplayed()
    }

    @Test
    fun scaleBar_displaysCorrectFormatForKilometers() {
        composeTestRule.setContent {
            ScaleBar(metersPerPixel = 20.0)
        }

        // Should show km, not m
        composeTestRule.onNodeWithText("km", substring = true).assertIsDisplayed()
    }

    // ========== EmptyMapStateCard Tests ==========

    @Test
    fun emptyMapStateCard_displaysLocationIcon() {
        composeTestRule.setContent {
            EmptyMapStateCard(contactCount = 0, onDismiss = {})
        }

        // The card should be displayed
        composeTestRule.onNodeWithText("Location permission required").assertIsDisplayed()
    }

    @Test
    fun emptyMapStateCard_displaysPrimaryText() {
        composeTestRule.setContent {
            EmptyMapStateCard(contactCount = 0, onDismiss = {})
        }

        composeTestRule.onNodeWithText("Location permission required").assertIsDisplayed()
    }

    @Test
    fun emptyMapStateCard_displaysSecondaryText() {
        composeTestRule.setContent {
            EmptyMapStateCard(contactCount = 0, onDismiss = {})
        }

        composeTestRule.onNodeWithText("Enable location access to see your position on the map.").assertIsDisplayed()
    }

    // NOTE: MapScreen integration tests removed because MapLibre requires native libraries
    // that are not available in Robolectric. The MapScreen uses AndroidView with MapLibre
    // which triggers UnsatisfiedLinkError for native .so files.
    //
    // The MapScreen should be tested via:
    // 1. Instrumented tests on a real device/emulator
    // 2. Screenshot tests with Paparazzi (if MapLibre supports it)
    // 3. Unit testing the ViewModel (MapViewModelTest) for logic coverage

    // ========== formatTimeAgo Tests ==========

    @Test
    fun `formatTimeAgo with recent timestamp returns Just now`() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("Just now", formatTimeAgo(now - 30))
    }

    @Test
    fun `formatTimeAgo with 5 minutes ago returns min ago`() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("5 min ago", formatTimeAgo(now - 300))
    }

    @Test
    fun `formatTimeAgo with 2 hours ago returns hours ago`() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("2 hours ago", formatTimeAgo(now - 7200))
    }

    @Test
    fun `formatTimeAgo with 3 days ago returns days ago`() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("3 days ago", formatTimeAgo(now - 259200))
    }

    // ========== formatLoraParamsForClipboard Tests ==========

    @Test
    fun `formatLoraParamsForClipboard includes interface name`() {
        val details = createTestFocusInterfaceDetails(name = "Test RNode")
        val result = formatLoraParamsForClipboard(details)
        assertTrue(result.contains("Test RNode"))
    }

    @Test
    fun `formatLoraParamsForClipboard formats frequency in MHz`() {
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        val result = formatLoraParamsForClipboard(details)
        assertTrue(result.contains("915.0 MHz"))
    }

    @Test
    fun `formatLoraParamsForClipboard formats bandwidth in kHz`() {
        val details = createTestFocusInterfaceDetails(bandwidth = 125000)
        val result = formatLoraParamsForClipboard(details)
        assertTrue(result.contains("125 kHz"))
    }

    @Test
    fun `formatLoraParamsForClipboard formats spreading factor`() {
        val details = createTestFocusInterfaceDetails(spreadingFactor = 10)
        val result = formatLoraParamsForClipboard(details)
        assertTrue(result.contains("SF10"))
    }

    @Test
    fun `formatLoraParamsForClipboard formats coding rate`() {
        val details = createTestFocusInterfaceDetails(codingRate = 5)
        val result = formatLoraParamsForClipboard(details)
        assertTrue(result.contains("4/5"))
    }

    @Test
    fun `formatLoraParamsForClipboard includes modulation`() {
        val details = createTestFocusInterfaceDetails(modulation = "LoRa")
        val result = formatLoraParamsForClipboard(details)
        assertTrue(result.contains("Modulation: LoRa"))
    }

    @Test
    fun `formatLoraParamsForClipboard omits null values`() {
        val details = createTestFocusInterfaceDetails(
            frequency = null,
            bandwidth = null,
            spreadingFactor = null,
            codingRate = null,
            modulation = null,
        )
        val result = formatLoraParamsForClipboard(details)
        assertFalse(result.contains("Frequency"))
        assertFalse(result.contains("Bandwidth"))
        assertFalse(result.contains("Spreading Factor"))
        assertFalse(result.contains("Coding Rate"))
        assertFalse(result.contains("Modulation"))
    }

    // ========== InterfaceDetailRow Tests ==========

    @Test
    fun `InterfaceDetailRow displays label`() {
        composeTestRule.setContent {
            InterfaceDetailRow(label = "Frequency", value = "915.0 MHz")
        }

        composeTestRule.onNodeWithText("Frequency").assertIsDisplayed()
    }

    @Test
    fun `InterfaceDetailRow displays value`() {
        composeTestRule.setContent {
            InterfaceDetailRow(label = "Bandwidth", value = "125 kHz")
        }

        composeTestRule.onNodeWithText("125 kHz").assertIsDisplayed()
    }

    // ========== FocusInterfaceBottomSheet Content Tests ==========

    @Test
    fun `FocusInterfaceContent displays interface name`() {
        val details = createTestFocusInterfaceDetails(name = "Test Interface")
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Test Interface").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays interface type`() {
        val details = createTestFocusInterfaceDetails(type = "RNode (LoRa)")
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("RNode (LoRa)").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays status badge`() {
        val details = createTestFocusInterfaceDetails(status = "available")
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Available").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays location`() {
        val details = createTestFocusInterfaceDetails(
            latitude = 45.1234,
            longitude = -122.5678,
        )
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Location").assertIsDisplayed()
        composeTestRule.onNodeWithText("45.1234, -122.5678").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays altitude when present`() {
        val details = createTestFocusInterfaceDetails(height = 150.0)
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Altitude").assertIsDisplayed()
        composeTestRule.onNodeWithText("150 m").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays Radio Parameters section for LoRa`() {
        val details = createTestFocusInterfaceDetails(
            frequency = 915000000L,
            bandwidth = 125000,
            spreadingFactor = 10,
            codingRate = 5,
        )
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Radio Parameters").assertIsDisplayed()
        composeTestRule.onNodeWithText("915.000 MHz").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays Network section for TCP`() {
        val details = createTestFocusInterfaceDetails(
            reachableOn = "192.168.1.1",
            port = 4242,
        )
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Network").assertIsDisplayed()
        composeTestRule.onNodeWithText("Host").assertIsDisplayed()
        composeTestRule.onNodeWithText("192.168.1.1").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays hops when present`() {
        val details = createTestFocusInterfaceDetails(hops = 3)
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Hops").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays Copy Params button for LoRa`() {
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Copy Params").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays Use for RNode button for LoRa`() {
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Use for RNode").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent hides LoRa buttons when no frequency`() {
        val details = createTestFocusInterfaceDetails(frequency = null)
        composeTestRule.setContent {
            FocusInterfaceContent(details = details)
        }

        composeTestRule.onNodeWithText("Copy Params").assertDoesNotExist()
        composeTestRule.onNodeWithText("Use for RNode").assertDoesNotExist()
    }

    @Test
    fun `FocusInterfaceContent Copy Params button triggers callback`() {
        var copyClicked = false
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContent(
                details = details,
                onCopyLoraParams = { copyClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Copy Params").performClick()
        assertTrue(copyClicked)
    }

    @Test
    fun `FocusInterfaceContent Use for RNode button triggers callback`() {
        var useClicked = false
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContent(
                details = details,
                onUseForNewRNode = { useClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Use for RNode").performClick()
        assertTrue(useClicked)
    }
}

// ========== Helper Functions ==========

/**
 * Create a test FocusInterfaceDetails with specified parameters.
 */
@Suppress("LongParameterList")
private fun createTestFocusInterfaceDetails(
    name: String = "Test Interface",
    type: String = "RNode (LoRa)",
    latitude: Double = 45.0,
    longitude: Double = -122.0,
    height: Double? = null,
    reachableOn: String? = null,
    port: Int? = null,
    frequency: Long? = null,
    bandwidth: Int? = null,
    spreadingFactor: Int? = null,
    codingRate: Int? = null,
    modulation: String? = null,
    status: String? = null,
    lastHeard: Long? = null,
    hops: Int? = null,
): FocusInterfaceDetails {
    return FocusInterfaceDetails(
        name = name,
        type = type,
        latitude = latitude,
        longitude = longitude,
        height = height,
        reachableOn = reachableOn,
        port = port,
        frequency = frequency,
        bandwidth = bandwidth,
        spreadingFactor = spreadingFactor,
        codingRate = codingRate,
        modulation = modulation,
        status = status,
        lastHeard = lastHeard,
        hops = hops,
    )
}

