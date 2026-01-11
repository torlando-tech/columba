package com.lxmf.messenger.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * UI tests for PermissionsPage composable.
 *
 * Tests cover:
 * - Notification permission card display and interactions
 * - Battery optimization card display and interactions
 * - Permission granted state indicators
 * - Navigation button callbacks
 * - Permission descriptions display
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionsPageTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Header and Title Tests ==========

    @Test
    fun permissionsPage_displaysTitle() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Stay Connected").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysSubtitle() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Columba can notify you when:").assertIsDisplayed()
    }

    // ========== Feature Items Tests ==========

    @Test
    fun permissionsPage_displaysNewMessagesFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("New messages arrive").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysSomeoneAddsContactFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Someone adds you as a contact").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysDeliveryConfirmationsFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Delivery confirmations are received").assertIsDisplayed()
    }

    // ========== Notification Card Tests ==========

    @Test
    fun notificationCard_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun notificationCard_displaysDescription() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Get alerts for new messages").assertIsDisplayed()
    }

    @Test
    fun notificationCard_showsEnableButton_whenNotGranted() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should have at least one Enable button visible
        composeTestRule.onNodeWithText("Enable").assertIsDisplayed()
    }

    @Test
    fun notificationEnableButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true, // Battery already exempt to isolate notification button
                onEnableNotifications = { callbackInvoked = true },
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // When - Click the Enable button (for notifications since battery is exempt)
        composeTestRule.onNodeWithText("Enable").performClick()

        // Then
        assertTrue("onEnableNotifications callback should be invoked", callbackInvoked)
    }

    @Test
    fun notificationCard_showsSuccessIndicator_whenGranted() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should show check icon with "Granted" content description
        composeTestRule.onNodeWithContentDescription("Granted").assertIsDisplayed()
    }

    // ========== Battery Optimization Card Tests ==========

    @Test
    fun batteryOptimizationCard_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Unrestricted Battery").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryOptimizationCard_displaysDescription() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Receive messages even when phone is idle").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryOptimizationCard_displaysSecondaryDescription() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Prevents Android from pausing Columba").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryOptimizationCard_showsEnableButton_whenNotExempt() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Notification granted to isolate battery button
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should have Enable button visible for battery, scroll to make visible
        composeTestRule.onNodeWithText("Enable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryEnableButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Notification granted to isolate battery button
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = { callbackInvoked = true },
                onBack = {},
                onContinue = {},
            )
        }

        // When - Click the Enable button (for battery since notifications is granted)
        composeTestRule.onNodeWithText("Enable").performScrollTo().performClick()

        // Then
        assertTrue("onEnableBatteryOptimization callback should be invoked", callbackInvoked)
    }

    @Test
    fun batteryOptimizationCard_showsSuccessIndicator_whenExempt() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should show check icon with "Granted" content description, scroll to make visible
        composeTestRule.onNodeWithContentDescription("Granted").performScrollTo().assertIsDisplayed()
    }

    // ========== Both Permissions Granted Tests ==========

    @Test
    fun bothPermissionsGranted_showsTwoSuccessIndicators() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = true,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Enable button should not exist when both are granted
        composeTestRule.onNodeWithText("Enable").assertDoesNotExist()
    }

    @Test
    fun noPermissionsGranted_showsTwoEnableButtons() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Both permission cards should show their titles
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unrestricted Battery").performScrollTo().assertIsDisplayed()
    }

    // ========== Navigation Button Tests ==========

    @Test
    fun backButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = { callbackInvoked = true },
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Back").performScrollTo().performClick()

        // Then
        assertTrue("onBack callback should be invoked", callbackInvoked)
    }

    @Test
    fun continueButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun continueButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = { callbackInvoked = true },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Continue").performScrollTo().performClick()

        // Then
        assertTrue("onContinue callback should be invoked", callbackInvoked)
    }

    // ========== Callback Invocation Count Tests ==========

    @Test
    fun backButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = { callCount++ },
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Back").performScrollTo().performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    @Test
    fun continueButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = { callCount++ },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Continue").performScrollTo().performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    @Test
    fun notificationEnableButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true, // Isolate notification button
                onEnableNotifications = { callCount++ },
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Enable").performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    @Test
    fun batteryEnableButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Isolate battery button
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = { callCount++ },
                onBack = {},
                onContinue = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Enable").performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    // ========== State Transition Tests ==========

    @Test
    fun notificationCard_transitionsFromEnableButtonToSuccessIndicator() {
        // Given - Initial state: not granted
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = true,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - No Enable buttons should be visible
        composeTestRule.onNodeWithText("Enable").assertDoesNotExist()

        // And - Granted indicators should be visible
        composeTestRule.onNodeWithContentDescription("Granted").assertIsDisplayed()
    }

    // ========== Complete Page Layout Tests ==========

    @Test
    fun permissionsPage_displaysAllElements() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - All key elements should be displayed
        composeTestRule.onNodeWithText("Stay Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Columba can notify you when:").assertIsDisplayed()
        composeTestRule.onNodeWithText("New messages arrive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Someone adds you as a contact").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivery confirmations are received").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Get alerts for new messages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unrestricted Battery").assertIsDisplayed()
        composeTestRule.onNodeWithText("Receive messages even when phone is idle").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prevents Android from pausing Columba").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
    }
}
