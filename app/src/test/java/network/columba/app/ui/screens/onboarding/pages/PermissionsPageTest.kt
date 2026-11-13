package network.columba.app.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.test.RegisterComponentActivityRule
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("New messages arrive").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysIncomingCallFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("A voice call comes in").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysAnnounceFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("A nearby peer announces").assertIsDisplayed()
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Get alerts for messages and calls").assertIsDisplayed()
    }

    @Test
    fun notificationCard_showsEnableButton_whenNotGranted() {
        // When - battery exempt to isolate the notification Enable button
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true, // Battery already exempt to isolate notification button
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should have exactly one Enable button visible (scroll to make visible)
        composeTestRule.onNodeWithText("Enable").performScrollTo().assertIsDisplayed()
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Enable").performScrollTo().performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    // ========== State Transition Tests ==========

    @Test
    fun notificationCard_transitionsFromEnableButtonToSuccessIndicator() {
        // Given - Only notification granted to have exactly one Granted indicator
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = false, // Battery not exempt so we have exactly one Granted icon
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Notification Granted indicator should be visible (scroll to make visible)
        composeTestRule.onNodeWithContentDescription("Granted").performScrollTo().assertIsDisplayed()

        // And - Battery Enable button should still exist
        composeTestRule.onNodeWithText("Enable").performScrollTo().assertIsDisplayed()
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
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - All key elements should be displayed (scroll for off-screen elements)
        composeTestRule.onNodeWithText("Stay Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Columba can notify you when:").assertIsDisplayed()
        composeTestRule.onNodeWithText("New messages arrive").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("A voice call comes in").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("A nearby peer announces").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Get alerts for messages and calls").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Unrestricted Battery").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Receive messages even when phone is idle").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Prevents Android from pausing Columba").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }

    // ========== Message Privacy Tests ==========

    @Test
    fun permissionsPage_displaysMessagePrivacySection() {
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                notificationPrivacyLevel = NotificationPrivacyLevel.DEFAULT,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onNotificationPrivacyLevelChange = {},
                onBack = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithText("Message privacy").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Name and message").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Name only").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("No name or message").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun permissionsPage_selectingPrivacyLevel_invokesCallback() {
        var selectedLevel: NotificationPrivacyLevel? = null
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                notificationPrivacyLevel = NotificationPrivacyLevel.FULL,
                onEnableNotifications = {},
                onEnableBatteryOptimization = {},
                onNotificationPrivacyLevelChange = { selectedLevel = it },
                onBack = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithText("No name or message").performScrollTo().performClick()

        assertEquals(NotificationPrivacyLevel.NONE, selectedLevel)
    }
}
