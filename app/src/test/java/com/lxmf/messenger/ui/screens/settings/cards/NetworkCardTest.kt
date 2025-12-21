package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Unit tests for NetworkCard composable using Robolectric.
 * Tests the transport node toggle, buttons, and conditional interface management.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Header and Content Tests ==========

    @Test
    fun networkCard_displaysHeader() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Network").assertIsDisplayed()
    }

    @Test
    fun networkCard_displaysNetworkStatusDescription() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            "Monitor your Reticulum network status, active interfaces, BLE connections, and connection diagnostics.",
        ).assertIsDisplayed()
    }

    @Test
    fun networkCard_displaysInterfaceDescription_whenNotSharedInstance() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = false,
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            "Configure how your device connects to the Reticulum network. " +
                "Add TCP connections, auto-discovery, LoRa (via RNode), or BLE interfaces.",
        ).assertIsDisplayed()
    }

    // ========== Transport Node Toggle Tests ==========

    @Test
    fun transportNodeToggle_displaysLabel() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_displaysDescription() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            "Forward traffic for the mesh network. When disabled, this device will only handle its own traffic and won't relay messages for other peers.",
        ).assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_isOn_whenEnabled() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = true,
            )
        }

        // Then - Switch should be checked
        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun transportNodeToggle_isOff_whenDisabled() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = false,
            )
        }

        // Then - Switch should be unchecked
        composeTestRule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun transportNodeToggle_callsCallback_withFalse_whenTurningOff() {
        // Given
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = true,
                onTransportNodeToggle = { receivedValue = it },
            )
        }

        // When - Click the switch to turn it off
        composeTestRule.onNode(isToggleable()).performClick()

        // Then - Callback should receive false
        assertEquals(false, receivedValue)
    }

    @Test
    fun transportNodeToggle_callsCallback_withTrue_whenTurningOn() {
        // Given
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = false,
                onTransportNodeToggle = { receivedValue = it },
            )
        }

        // When - Click the switch to turn it on
        composeTestRule.onNode(isToggleable()).performClick()

        // Then - Callback should receive true
        assertEquals(true, receivedValue)
    }

    // ========== View Network Status Button Tests ==========

    @Test
    fun viewNetworkStatusButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("View Network Status").assertIsDisplayed()
    }

    @Test
    fun viewNetworkStatusButton_isEnabled() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("View Network Status").assertIsEnabled()
    }

    @Test
    fun viewNetworkStatusButton_callsCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = { clicked = true },
                onManageInterfaces = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("View Network Status").performClick()

        // Then
        assertTrue("onViewStatus callback should be called", clicked)
    }

    @Test
    fun viewNetworkStatusButton_alwaysEnabled_whenSharedInstance() {
        // When - Using shared instance (which normally disables some things)
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }

        // Then - View Network Status should always be enabled
        composeTestRule.onNodeWithText("View Network Status").assertIsEnabled()
    }

    // ========== Manage Interfaces Button Tests ==========

    @Test
    fun manageInterfacesButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsDisplayed()
    }

    @Test
    fun manageInterfacesButton_isEnabled_whenNotSharedInstance() {
        // When
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = false,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsEnabled()
    }

    @Test
    fun manageInterfacesButton_callsCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = { clicked = true },
            )
        }

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
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }

        // Then - Button should be disabled
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsNotEnabled()
    }

    @Test
    fun manageInterfacesButton_enabled_whenSharedInstanceOffline() {
        // When - Shared instance went offline (using own instance now)
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = false,
            )
        }

        // Then - Button should be enabled because we're using our own instance
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsEnabled()
    }

    @Test
    fun sharedInstance_displaysDisabledDescription() {
        // When - Using shared instance that is online
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }

        // Then - Should display disabled message
        composeTestRule.onNodeWithText(
            "Interface management is disabled while using a shared system instance.",
        ).assertIsDisplayed()
    }

    @Test
    fun sharedInstanceOffline_displaysNormalDescription() {
        // When - Shared instance is offline (fallback to own instance)
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                isSharedInstance = true,
                sharedInstanceOnline = false,
            )
        }

        // Then - Should display normal interface description
        composeTestRule.onNodeWithText(
            "Configure how your device connects to the Reticulum network. " +
                "Add TCP connections, auto-discovery, LoRa (via RNode), or BLE interfaces.",
        ).assertIsDisplayed()
    }

    @Test
    fun manageInterfacesButton_noCallbackWhenDisabled() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = { clickCount++ },
                isSharedInstance = true,
                sharedInstanceOnline = true,
            )
        }

        // When - Try to click disabled button
        composeTestRule.onNodeWithText("Manage Interfaces").performClick()

        // Then - Callback should not be called because button is disabled
        assertEquals("Callback should not be called when button is disabled", 0, clickCount)
    }

    // ========== Default Values Tests ==========

    @Test
    fun networkCard_withAllDefaultValues_displaysCorrectly() {
        // When - Use all defaults
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
            )
        }

        // Then - All key elements should be displayed
        composeTestRule.onNodeWithText("Network").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
        composeTestRule.onNodeWithText("View Network Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manage Interfaces").assertIsDisplayed()
    }

    @Test
    fun networkCard_defaultTransportNodeEnabled_isTrue() {
        // When - Default value
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                // transportNodeEnabled defaults to true
            )
        }

        // Then - Switch should be on by default
        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    // ========== Callback Invocation Count Tests ==========

    @Test
    fun viewNetworkStatusButton_callbackCalledOnce() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = { clickCount++ },
                onManageInterfaces = {},
            )
        }

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
                onViewStatus = {},
                onManageInterfaces = { clickCount++ },
            )
        }

        // When
        composeTestRule.onNodeWithText("Manage Interfaces").performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, clickCount)
    }

    @Test
    fun transportNodeToggle_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            NetworkCard(
                onViewStatus = {},
                onManageInterfaces = {},
                transportNodeEnabled = true,
                onTransportNodeToggle = { callCount++ },
            )
        }

        // When
        composeTestRule.onNode(isToggleable()).performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }
}
