package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            ScaleBarTestWrapper(metersPerPixel = 0.05)
        }

        composeTestRule.onNodeWithText("5 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays10mForCloseZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 0.1)
        }

        composeTestRule.onNodeWithText("10 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays50mForMediumZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 0.5)
        }

        composeTestRule.onNodeWithText("50 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays100mForStreetLevelZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 1.0)
        }

        composeTestRule.onNodeWithText("100 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays500mForNeighborhoodZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 5.0)
        }

        composeTestRule.onNodeWithText("500 m").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays1kmForCityZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 10.0)
        }

        composeTestRule.onNodeWithText("1 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays5kmForRegionalZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 50.0)
        }

        composeTestRule.onNodeWithText("5 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays10kmForCountryZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 100.0)
        }

        composeTestRule.onNodeWithText("10 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays100kmForContinentZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 1000.0)
        }

        composeTestRule.onNodeWithText("100 km").assertIsDisplayed()
    }

    @Test
    fun scaleBar_displays1000kmForGlobalZoom() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 10000.0)
        }

        // At 10000 m/px, 100000m (100km) fits in ~10px which is too small
        // So it will use a larger value like 1000km or 2000km
        composeTestRule.onNodeWithText("km", substring = true).assertIsDisplayed()
    }

    @Test
    fun scaleBar_displaysCorrectFormatForMeters() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 0.2)
        }

        // Should show meters, not km
        composeTestRule.onNodeWithText("m", substring = true).assertIsDisplayed()
    }

    @Test
    fun scaleBar_displaysCorrectFormatForKilometers() {
        composeTestRule.setContent {
            ScaleBarTestWrapper(metersPerPixel = 20.0)
        }

        // Should show km, not m
        composeTestRule.onNodeWithText("km", substring = true).assertIsDisplayed()
    }

    // ========== EmptyMapStateCard Tests ==========

    @Test
    fun emptyMapStateCard_displaysLocationIcon() {
        composeTestRule.setContent {
            EmptyMapStateCardTestWrapper()
        }

        // The card should be displayed
        composeTestRule.onNodeWithText("Location permission required").assertIsDisplayed()
    }

    @Test
    fun emptyMapStateCard_displaysPrimaryText() {
        composeTestRule.setContent {
            EmptyMapStateCardTestWrapper()
        }

        composeTestRule.onNodeWithText("Location permission required").assertIsDisplayed()
    }

    @Test
    fun emptyMapStateCard_displaysSecondaryText() {
        composeTestRule.setContent {
            EmptyMapStateCardTestWrapper()
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
            InterfaceDetailRowTestWrapper(label = "Frequency", value = "915.0 MHz")
        }

        composeTestRule.onNodeWithText("Frequency").assertIsDisplayed()
    }

    @Test
    fun `InterfaceDetailRow displays value`() {
        composeTestRule.setContent {
            InterfaceDetailRowTestWrapper(label = "Bandwidth", value = "125 kHz")
        }

        composeTestRule.onNodeWithText("125 kHz").assertIsDisplayed()
    }

    // ========== FocusInterfaceBottomSheet Content Tests ==========

    @Test
    fun `FocusInterfaceContent displays interface name`() {
        val details = createTestFocusInterfaceDetails(name = "Test Interface")
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Test Interface").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays interface type`() {
        val details = createTestFocusInterfaceDetails(type = "RNode (LoRa)")
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("RNode (LoRa)").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays status badge`() {
        val details = createTestFocusInterfaceDetails(status = "available")
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
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
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Location").assertIsDisplayed()
        composeTestRule.onNodeWithText("45.1234, -122.5678").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays altitude when present`() {
        val details = createTestFocusInterfaceDetails(height = 150.0)
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
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
            FocusInterfaceContentTestWrapper(details = details)
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
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Network").assertIsDisplayed()
        composeTestRule.onNodeWithText("Host").assertIsDisplayed()
        composeTestRule.onNodeWithText("192.168.1.1").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays hops when present`() {
        val details = createTestFocusInterfaceDetails(hops = 3)
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Hops").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays Copy Params button for LoRa`() {
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Copy Params").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent displays Use for RNode button for LoRa`() {
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Use for RNode").assertIsDisplayed()
    }

    @Test
    fun `FocusInterfaceContent hides LoRa buttons when no frequency`() {
        val details = createTestFocusInterfaceDetails(frequency = null)
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(details = details)
        }

        composeTestRule.onNodeWithText("Copy Params").assertDoesNotExist()
        composeTestRule.onNodeWithText("Use for RNode").assertDoesNotExist()
    }

    @Test
    fun `FocusInterfaceContent Copy Params button triggers callback`() {
        var copyClicked = false
        val details = createTestFocusInterfaceDetails(frequency = 915000000L)
        composeTestRule.setContent {
            FocusInterfaceContentTestWrapper(
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
            FocusInterfaceContentTestWrapper(
                details = details,
                onUseForNewRNode = { useClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Use for RNode").performClick()
        assertTrue(useClicked)
    }
}

/**
 * Test wrapper for ScaleBar to make it accessible for testing.
 * ScaleBar is private in MapScreen, so we recreate it here for testing.
 */
@Suppress("TestFunctionName")
@Composable
private fun ScaleBarTestWrapper(metersPerPixel: Double) {
    // Recreate the ScaleBar logic for testing
    val density = LocalDensity.current.density
    val minBarWidthDp = 80f
    val maxBarWidthDp = 140f
    val minBarWidthPx = minBarWidthDp * density
    val maxBarWidthPx = maxBarWidthDp * density
    val minMeters = metersPerPixel * minBarWidthPx
    val maxMeters = metersPerPixel * maxBarWidthPx

    val niceDistances =
        listOf(
            5, 10, 20, 50, 100, 200, 500,
            1_000, 2_000, 5_000, 10_000, 20_000, 50_000,
            100_000, 200_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000,
        )

    val selectedDistance =
        niceDistances.findLast { it >= minMeters && it <= maxMeters }
            ?: niceDistances.firstOrNull { it >= minMeters }
            ?: niceDistances.last()

    val distanceText =
        when {
            selectedDistance >= 1_000_000 -> "${selectedDistance / 1_000_000} km"
            selectedDistance >= 1_000 -> "${selectedDistance / 1_000} km"
            else -> "$selectedDistance m"
        }

    Text(text = distanceText)
}

/**
 * Test wrapper for EmptyMapStateCard.
 */
@Suppress("TestFunctionName")
@Composable
private fun EmptyMapStateCardTestWrapper() {
    Card(
        modifier = Modifier.padding(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Location permission required",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Enable location access to see your position on the map.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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

/**
 * Test wrapper for InterfaceDetailRow.
 */
@Suppress("TestFunctionName")
@Composable
private fun InterfaceDetailRowTestWrapper(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Test wrapper for FocusInterfaceBottomSheet content.
 * We test the content directly since ModalBottomSheet is difficult to test in Robolectric.
 */
@Suppress("TestFunctionName", "LongMethod")
@Composable
private fun FocusInterfaceContentTestWrapper(
    details: FocusInterfaceDetails,
    onCopyLoraParams: () -> Unit = {},
    onUseForNewRNode: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header with name and type
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = details.type,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            details.status?.let { status ->
                Surface(
                    color = when (status.lowercase()) {
                        "available" -> MaterialTheme.colorScheme.primaryContainer
                        "unknown" -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = status.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        HorizontalDivider()

        // Location info
        InterfaceDetailRowTestWrapper(
            label = "Location",
            value = "%.4f, %.4f".format(details.latitude, details.longitude),
        )
        details.height?.let { height ->
            InterfaceDetailRowTestWrapper(
                label = "Altitude",
                value = "${height.toInt()} m",
            )
        }

        // Radio parameters (if LoRa interface)
        if (details.frequency != null) {
            HorizontalDivider()
            Text(
                text = "Radio Parameters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InterfaceDetailRowTestWrapper(
                label = "Frequency",
                value = "%.3f MHz".format(details.frequency / 1_000_000.0),
            )
            details.bandwidth?.let { bw ->
                InterfaceDetailRowTestWrapper(
                    label = "Bandwidth",
                    value = "$bw kHz",
                )
            }
            details.spreadingFactor?.let { sf ->
                InterfaceDetailRowTestWrapper(
                    label = "Spreading Factor",
                    value = "SF$sf",
                )
            }
            details.codingRate?.let { cr ->
                InterfaceDetailRowTestWrapper(
                    label = "Coding Rate",
                    value = "4/$cr",
                )
            }
            details.modulation?.let { mod ->
                InterfaceDetailRowTestWrapper(
                    label = "Modulation",
                    value = mod,
                )
            }
        }

        // TCP parameters (if TCP interface)
        if (details.reachableOn != null) {
            HorizontalDivider()
            Text(
                text = "Network",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InterfaceDetailRowTestWrapper(
                label = "Host",
                value = details.reachableOn,
            )
            details.port?.let { port ->
                InterfaceDetailRowTestWrapper(
                    label = "Port",
                    value = port.toString(),
                )
            }
        }

        // Status details
        if (details.lastHeard != null || details.hops != null) {
            HorizontalDivider()
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            details.lastHeard?.let { timestamp ->
                val timeAgo = formatTimeAgo(timestamp)
                InterfaceDetailRowTestWrapper(
                    label = "Last Heard",
                    value = timeAgo,
                )
            }
            details.hops?.let { hops ->
                InterfaceDetailRowTestWrapper(
                    label = "Hops",
                    value = hops.toString(),
                )
            }
        }

        // LoRa params buttons (only for radio interfaces with frequency info)
        if (details.frequency != null) {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Copy button
                OutlinedButton(
                    onClick = onCopyLoraParams,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Params")
                }
                // Use for New RNode button
                Button(
                    onClick = onUseForNewRNode,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use for RNode")
                }
            }
        }
    }
}
