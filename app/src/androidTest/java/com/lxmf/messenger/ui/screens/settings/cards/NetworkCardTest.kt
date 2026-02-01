package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.TestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for NetworkCard composable.
 * Tests the transport node toggle, buttons, and conditional interface management.
 */
class NetworkCardTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestHostActivity>()

    // ========== Header and Content Tests ==========

    @Test
    fun networkCard_displaysHeader() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Network").assertIsDisplayed()
    }

    @Test
    fun networkCard_displaysNetworkStatusDescription() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule
            .onNodeWithText(
                "Monitor your Reticulum network status, active interfaces, BLE connections, and connection diagnostics.",
            ).assertIsDisplayed()
    }

    @Test
    fun networkCard_displaysInterfaceDescription() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = false,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule
            .onNodeWithText(
                "Configure how your device connects to the Reticulum network. " +
                    "Add TCP connections, auto-discovery, LoRa (via RNode), or BLE interfaces.",
            ).assertIsDisplayed()
    }

    // ========== Transport Node Toggle Tests ==========

    @Test
    fun transportNodeToggle_defaultEnabled() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = true,
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
        // Switch should be checked when enabled
    }

    @Test
    fun transportNodeToggle_displaysDescription() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule
            .onNodeWithText(
                "Forward traffic for the mesh network. When disabled, this device will only handle its own traffic and won't relay messages for other peers.",
            ).assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_callsCallback_whenToggled() {
        // Given
        var toggledValue: Boolean? = null
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = true,
                onTransportNodeToggle = { toggledValue = it },
            )
        }
        composeTestRule.waitForIdle()

        // When - Click on the Transport Node row to toggle
        composeTestRule.onNodeWithText("Transport Node").performClick()

        // Then - Callback should be invoked
        // Note: The exact value depends on Switch behavior; we verify callback was called
        assertTrue("Toggle callback should be called", toggledValue != null)
    }

    // ========== Button Tests ==========

    @Test
    fun viewNetworkStatusButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("View Network Status").assertIsDisplayed()
    }

    @Test
    fun viewNetworkStatusButton_callsCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = { clicked = true },
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithText("View Network Status").performClick()

        // Then
        assertTrue("onViewStatus callback should be called", clicked)
    }

    @Test
    fun manageInterfacesButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsDisplayed()
    }

    @Test
    fun manageInterfacesButton_callsCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = { clicked = true },
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithText("Manage Interfaces").performClick()

        // Then
        assertTrue("onManageInterfaces callback should be called", clicked)
    }

    // ========== Shared Instance Tests ==========

    @Test
    fun manageInterfacesButton_disabled_whenSharedInstanceOnline() {
        // When - Using shared instance that is online
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Button should be disabled
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsNotEnabled()
    }

    @Test
    fun manageInterfacesButton_enabled_whenSharedInstanceOffline() {
        // When - Shared instance went offline (using own instance now)
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = false,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Button should be enabled because we're using our own instance
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsEnabled()
    }

    @Test
    fun manageInterfacesButton_enabled_whenNotSharedInstance() {
        // When - Not using shared instance
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = false,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Button should be enabled
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsEnabled()
    }

    @Test
    fun sharedInstance_displaysDisabledDescription() {
        // When - Using shared instance
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Should display disabled message
        composeTestRule
            .onNodeWithText(
                "Interface management is disabled while using a shared system instance.",
            ).assertIsDisplayed()
    }

    @Test
    fun viewNetworkStatusButton_alwaysEnabled_whenSharedInstance() {
        // When - Using shared instance
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }
        composeTestRule.waitForIdle()

        // Then - View Network Status should always be enabled
        composeTestRule.onNodeWithText("View Network Status").assertIsEnabled()
    }

    // ========== Transport Node State Tests ==========

    @Test
    fun transportNodeToggle_showsEnabledState() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = true,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Transport Node label should be visible
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_showsDisabledState() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = false,
            )
        }
        composeTestRule.waitForIdle()

        // Then - Transport Node label should still be visible
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
    }

    @Test
    fun networkCard_withAllDefaultValues_displaysCorrectly() {
        // When - Use all defaults
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then - All key elements should be displayed
        composeTestRule.onNodeWithText("Network").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
        composeTestRule.onNodeWithText("View Network Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsDisplayed()
    }

    // ========== Callback Counter Tests ==========

    @Test
    fun viewNetworkStatusButton_callbackCalledOnce() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = { clickCount++ },
                onManageInterfaces = {},
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithText("View Network Status").performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, clickCount)
    }

    @Test
    fun manageInterfacesButton_callbackCalledOnce() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = { clickCount++ },
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.onNodeWithText("Manage Interfaces").performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, clickCount)
    }

    @Test
    fun manageInterfacesButton_noCallbackWhenDisabled() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            NetworkCard(
                isExpanded = true,
                onExpandedChange = {},
                onViewStatus = {},
                onManageInterfaces = { clickCount++ },
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }
        composeTestRule.waitForIdle()

        // When - Try to click disabled button
        composeTestRule.onNodeWithText("Manage Interfaces").performClick()

        // Then - Callback should not be called because button is disabled
        assertEquals("Callback should not be called when button is disabled", 0, clickCount)
    }
}
