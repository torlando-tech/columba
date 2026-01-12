package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for TelemetryCollectorCard.
 *
 * Tests:
 * - UI display elements
 * - User interactions (toggle, address input, interval selection, send button)
 * - Callback invocations
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TelemetryCollectorCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Basic Display Tests ==========

    @Test
    fun `card displays title`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = false,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Telemetry Collector").assertIsDisplayed()
    }

    @Test
    fun `card displays description`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = false,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Send your location to a telemetry collector",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `card displays enable collector toggle label`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = false,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Enable Collector").assertIsDisplayed()
    }

    @Test
    fun `card displays collector address input label`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = false,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Collector Address").assertIsDisplayed()
    }

    @Test
    fun `card displays send now button`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Send Now").assertIsDisplayed()
    }

    // ========== Interval Chip Tests ==========

    @Test
    fun `card displays 5min interval chip`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("5min").assertIsDisplayed()
    }

    @Test
    fun `card displays 15min interval chip`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("15min").assertIsDisplayed()
    }

    @Test
    fun `card displays 30min interval chip`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("30min").assertIsDisplayed()
    }

    @Test
    fun `card displays 1hr interval chip`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("1hr").assertIsDisplayed()
    }

    @Test
    fun `card displays current send interval`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Send interval: 5min").assertIsDisplayed()
    }

    @Test
    fun `card displays send interval for 15 minutes`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 900,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Send interval: 15min").assertIsDisplayed()
    }

    @Test
    fun `card displays send interval for 1 hour`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 3600,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Send interval: 1hr", substring = true).assertIsDisplayed()
    }

    // ========== Send Button State Tests ==========

    @Test
    fun `send button is disabled when no collector address`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Send Now").assertIsNotEnabled()
    }

    @Test
    fun `send button is enabled when collector address is set`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Send Now").assertIsEnabled()
    }

    @Test
    fun `send button shows sending state`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = true,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Sending...").assertIsDisplayed()
    }

    @Test
    fun `send button is disabled while sending`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = true,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Sending...").assertIsNotEnabled()
    }

    // ========== Callback Tests ==========

    @Test
    fun `send now button invokes callback`() {
        var sendNowCalled = false

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = { sendNowCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Send Now").performClick()

        assertTrue(sendNowCalled)
    }

    @Test
    fun `interval chip 15min invokes callback with 900`() {
        var receivedInterval = 0

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = { receivedInterval = it },
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("15min").performClick()

        assertEquals(900, receivedInterval)
    }

    @Test
    fun `interval chip 30min invokes callback with 1800`() {
        var receivedInterval = 0

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = { receivedInterval = it },
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("30min").performClick()

        assertEquals(1800, receivedInterval)
    }

    @Test
    fun `interval chip 1hr invokes callback with 3600`() {
        var receivedInterval = 0

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = { receivedInterval = it },
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("1hr").performClick()

        assertEquals(3600, receivedInterval)
    }

    // ========== Last Send Time Display Tests ==========

    @Test
    fun `last send time is not displayed when null`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Last sent:", substring = true).assertDoesNotExist()
    }

    @Test
    fun `last send time displays just now for recent send`() {
        val recentTime = System.currentTimeMillis() - 2000 // 2 seconds ago

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = recentTime,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Last sent: Just now").assertIsDisplayed()
    }

    @Test
    fun `last send time displays seconds ago`() {
        val pastTime = System.currentTimeMillis() - 30_000 // 30 seconds ago

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = pastTime,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Last sent:", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("seconds ago", substring = true).assertIsDisplayed()
    }

    @Test
    fun `last send time displays minutes ago`() {
        val pastTime = System.currentTimeMillis() - 5 * 60_000 // 5 minutes ago

        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = pastTime,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Last sent:", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("minutes ago", substring = true).assertIsDisplayed()
    }

    // ========== Address Input Tests ==========

    @Test
    fun `address input shows placeholder when empty`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Destination Hash").assertIsDisplayed()
    }

    @Test
    fun `address input shows supporting text`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("Enter the collector's destination hash").assertIsDisplayed()
    }

    // ========== Interval Chips Enabled State Tests ==========

    @Test
    fun `interval chips disabled when collector is disabled`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = false,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        // When enabled=false, chips should be disabled
        composeTestRule.onNodeWithText("5min").assertIsNotEnabled()
        composeTestRule.onNodeWithText("15min").assertIsNotEnabled()
    }

    @Test
    fun `interval chips disabled when no collector address`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = null,
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        // When no address, chips should be disabled
        composeTestRule.onNodeWithText("5min").assertIsNotEnabled()
        composeTestRule.onNodeWithText("15min").assertIsNotEnabled()
    }

    @Test
    fun `interval chips enabled when collector enabled and address set`() {
        composeTestRule.setContent {
            TelemetryCollectorCard(
                enabled = true,
                collectorAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
                sendIntervalSeconds = 300,
                lastSendTime = null,
                isSending = false,
                onEnabledChange = {},
                onCollectorAddressChange = {},
                onSendIntervalChange = {},
                onSendNow = {},
            )
        }

        composeTestRule.onNodeWithText("5min").assertIsEnabled()
        composeTestRule.onNodeWithText("15min").assertIsEnabled()
        composeTestRule.onNodeWithText("30min").assertIsEnabled()
        composeTestRule.onNodeWithText("1hr").assertIsEnabled()
    }
}
