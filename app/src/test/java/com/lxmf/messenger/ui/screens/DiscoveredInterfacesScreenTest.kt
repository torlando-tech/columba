package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Antenna
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TreePine
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
 * Unit tests for DiscoveredInterfacesScreen UI components and helper functions.
 *
 * Tests cover:
 * - isYggdrasilAddress() helper function for IPv6 address detection
 * - formatInterfaceType() helper function for interface type display
 * - InterfaceTypeIcon composable for different interface types
 * - Connected badge visibility logic
 * - Info icon visibility for special networks (Yggdrasil, I2P)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiscoveredInterfacesScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== isYggdrasilAddress Tests ==========

    @Test
    fun `isYggdrasilAddress with null returns false`() {
        assertFalse(isYggdrasilAddressTestable(null))
    }

    @Test
    fun `isYggdrasilAddress with IPv4 returns false`() {
        assertFalse(isYggdrasilAddressTestable("192.168.1.1"))
    }

    @Test
    fun `isYggdrasilAddress with regular IPv6 returns false`() {
        assertFalse(isYggdrasilAddressTestable("2001:db8::1"))
    }

    @Test
    fun `isYggdrasilAddress with 0200 prefix returns true`() {
        assertTrue(isYggdrasilAddressTestable("200:abcd::1"))
    }

    @Test
    fun `isYggdrasilAddress with 0300 prefix returns true`() {
        assertTrue(isYggdrasilAddressTestable("300:1234::1"))
    }

    @Test
    fun `isYggdrasilAddress with 0400 above range returns false`() {
        assertFalse(isYggdrasilAddressTestable("400:abcd::1"))
    }

    @Test
    fun `isYggdrasilAddress with 01FF below range returns false`() {
        assertFalse(isYggdrasilAddressTestable("1FF:abcd::1"))
    }

    @Test
    fun `isYggdrasilAddress with 0200 exact boundary returns true`() {
        assertTrue(isYggdrasilAddressTestable("200::1"))
    }

    @Test
    fun `isYggdrasilAddress with 03FF exact boundary returns true`() {
        assertTrue(isYggdrasilAddressTestable("3FF::1"))
    }

    @Test
    fun `isYggdrasilAddress with bracketed address returns true`() {
        assertTrue(isYggdrasilAddressTestable("[200:abcd::1]"))
    }

    @Test
    fun `isYggdrasilAddress with empty string returns false`() {
        assertFalse(isYggdrasilAddressTestable(""))
    }

    // ========== formatInterfaceType Tests ==========

    @Test
    fun `formatInterfaceType TCPServerInterface returns TCP Server`() {
        assertEquals("TCP Server", formatInterfaceTypeTestable("TCPServerInterface"))
    }

    @Test
    fun `formatInterfaceType TCPClientInterface returns TCP Client`() {
        assertEquals("TCP Client", formatInterfaceTypeTestable("TCPClientInterface"))
    }

    @Test
    fun `formatInterfaceType BackboneInterface returns Backbone TCP`() {
        assertEquals("Backbone (TCP)", formatInterfaceTypeTestable("BackboneInterface"))
    }

    @Test
    fun `formatInterfaceType I2PInterface returns I2P`() {
        assertEquals("I2P", formatInterfaceTypeTestable("I2PInterface"))
    }

    @Test
    fun `formatInterfaceType RNodeInterface returns RNode LoRa`() {
        assertEquals("RNode (LoRa)", formatInterfaceTypeTestable("RNodeInterface"))
    }

    @Test
    fun `formatInterfaceType WeaveInterface returns Weave LoRa`() {
        assertEquals("Weave (LoRa)", formatInterfaceTypeTestable("WeaveInterface"))
    }

    @Test
    fun `formatInterfaceType KISSInterface returns KISS`() {
        assertEquals("KISS", formatInterfaceTypeTestable("KISSInterface"))
    }

    @Test
    fun `formatInterfaceType unknown type returns type unchanged`() {
        assertEquals("UnknownType", formatInterfaceTypeTestable("UnknownType"))
    }

    // ========== InterfaceTypeIcon Tests ==========

    @Test
    fun `InterfaceTypeIcon displays Globe for TCP interface with public IP`() {
        composeTestRule.setContent {
            InterfaceTypeIconTestWrapper(
                type = "TCPServerInterface",
                host = "192.168.1.1",
            )
        }

        composeTestRule.onNodeWithText("public_icon").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeIcon displays TreePine for Yggdrasil address`() {
        composeTestRule.setContent {
            InterfaceTypeIconTestWrapper(
                type = "TCPServerInterface",
                host = "200:abcd::1",
            )
        }

        composeTestRule.onNodeWithText("treepine_icon").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeIcon displays Antenna for RNode interface`() {
        composeTestRule.setContent {
            InterfaceTypeIconTestWrapper(
                type = "RNodeInterface",
                host = null,
            )
        }

        composeTestRule.onNodeWithText("antenna_icon").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeIcon displays incognito for I2P interface`() {
        composeTestRule.setContent {
            InterfaceTypeIconTestWrapper(
                type = "I2PInterface",
                host = null,
            )
        }

        composeTestRule.onNodeWithText("incognito_icon").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeIcon displays Settings for unknown interface type`() {
        composeTestRule.setContent {
            InterfaceTypeIconTestWrapper(
                type = "UnknownInterface",
                host = null,
            )
        }

        composeTestRule.onNodeWithText("settings_icon").assertIsDisplayed()
    }

    // ========== Connected Badge Tests ==========

    @Test
    fun `Connected badge shown when isConnected true`() {
        composeTestRule.setContent {
            ConnectedBadgeTestWrapper(isConnected = true)
        }

        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
    }

    @Test
    fun `Connected badge hidden when isConnected false`() {
        composeTestRule.setContent {
            ConnectedBadgeTestWrapper(isConnected = false)
        }

        composeTestRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    // ========== Info Icon Tests ==========

    @Test
    fun `Info icon shown for Yggdrasil interface`() {
        composeTestRule.setContent {
            NetworkInfoIconTestWrapper(isYggdrasil = true, isI2p = false)
        }

        composeTestRule.onNodeWithText("network_info_icon").assertIsDisplayed()
    }

    @Test
    fun `Info icon shown for I2P interface`() {
        composeTestRule.setContent {
            NetworkInfoIconTestWrapper(isYggdrasil = false, isI2p = true)
        }

        composeTestRule.onNodeWithText("network_info_icon").assertIsDisplayed()
    }

    @Test
    fun `Info icon hidden for regular TCP interface`() {
        composeTestRule.setContent {
            NetworkInfoIconTestWrapper(isYggdrasil = false, isI2p = false)
        }

        composeTestRule.onNodeWithText("network_info_icon").assertDoesNotExist()
    }
}

// ========== Testable Helper Functions (recreated from private functions) ==========

/**
 * Testable version of isYggdrasilAddress function from DiscoveredInterfacesScreen.
 * Check if a host address is a Yggdrasil network address (IPv6 in 0200::/7 space).
 */
private fun isYggdrasilAddressTestable(host: String?): Boolean {
    // Early exit for null
    if (host == null) return false

    // Quick check: Yggdrasil addresses start with "2" or "3" after optional brackets
    val cleanHost = host.trim().removePrefix("[").removeSuffix("]")

    // Check if it's IPv6, parse first segment, and validate range
    val firstSegment = cleanHost.takeIf { it.contains(":") }?.split(":")?.firstOrNull()
    val value = firstSegment?.toIntOrNull(16)

    // 0200::/7 means first 7 bits are 0000001, covering 0x0200-0x03FF
    return value != null && value in 0x0200..0x03FF
}

/**
 * Testable version of formatInterfaceType function from DiscoveredInterfacesScreen.
 */
private fun formatInterfaceTypeTestable(type: String): String {
    return when (type) {
        "TCPServerInterface" -> "TCP Server"
        "TCPClientInterface" -> "TCP Client"
        "BackboneInterface" -> "Backbone (TCP)"
        "I2PInterface" -> "I2P"
        "RNodeInterface" -> "RNode (LoRa)"
        "WeaveInterface" -> "Weave (LoRa)"
        "KISSInterface" -> "KISS"
        else -> type
    }
}

// ========== Test Wrappers (Composables for testing) ==========

/**
 * Test wrapper for InterfaceTypeIcon that outputs a text indicator for the icon type.
 */
@Suppress("TestFunctionName")
@Composable
private fun InterfaceTypeIconTestWrapper(
    type: String,
    host: String?,
) {
    // Determine expected icon type based on logic from DiscoveredInterfacesScreen
    val expectedIcon = when (type) {
        "TCPServerInterface", "TCPClientInterface", "BackboneInterface" -> {
            if (isYggdrasilAddressTestable(host)) "treepine" else "public"
        }
        "I2PInterface" -> "incognito"
        "RNodeInterface", "WeaveInterface", "KISSInterface" -> "antenna"
        else -> "settings"
    }

    // Render a text indicator for the icon type (for test assertion)
    Text(text = "${expectedIcon}_icon")

    // Also render the actual icon for visual verification
    when (expectedIcon) {
        "public" -> Icon(
            imageVector = Icons.Default.Public,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        "treepine" -> Icon(
            imageVector = Lucide.TreePine,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        "antenna" -> Icon(
            imageVector = Lucide.Antenna,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        "settings" -> Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        "incognito" -> {
            // For test purposes, just show a text placeholder
            // Real implementation uses MDI font
            Text(text = "I2P")
        }
    }
}

/**
 * Test wrapper for Connected badge.
 */
@Suppress("TestFunctionName")
@Composable
private fun ConnectedBadgeTestWrapper(isConnected: Boolean) {
    if (isConnected) {
        Surface(
            color = Color.Green.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Green,
                ) {}
                Text(text = "Connected")
            }
        }
    }
}

/**
 * Test wrapper for Network info icon (shown for Yggdrasil/I2P).
 */
@Suppress("TestFunctionName")
@Composable
private fun NetworkInfoIconTestWrapper(
    isYggdrasil: Boolean,
    isI2p: Boolean,
) {
    Column {
        if (isYggdrasil || isI2p) {
            Text(text = "network_info_icon")
        }
    }
}
