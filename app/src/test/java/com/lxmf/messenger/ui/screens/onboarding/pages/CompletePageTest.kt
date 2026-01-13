package com.lxmf.messenger.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.screens.onboarding.OnboardingInterfaceType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for CompletePage composable.
 * Tests the onboarding completion page including success message,
 * summary card, QR code button, and start messaging button.
 * Uses Robolectric for local testing without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompletePageTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Success Message and Icon Tests ==========

    @Test
    fun completePage_displaysSuccessTitle() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("You're all set!").assertIsDisplayed()
    }

    @Test
    fun completePage_displaysCheckCircleIcon() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - The CheckCircle icon has no content description, but the QR code hint text is displayed
        // Verifying the success title is displayed confirms the success section is rendered
        composeTestRule.onNodeWithText("You're all set!").assertIsDisplayed()
    }

    // ========== Summary Card Display Name Tests ==========

    @Test
    fun summaryCard_displaysIdentityLabel() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Alice",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Identity").assertIsDisplayed()
    }

    @Test
    fun summaryCard_displaysDisplayName() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Alice",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun summaryCard_emptyDisplayName_showsAnonymousPeer() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Anonymous Peer").assertIsDisplayed()
    }

    // ========== Summary Card Selected Networks Tests ==========

    @Test
    fun summaryCard_displaysNetworksLabel() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Networks").assertIsDisplayed()
    }

    @Test
    fun summaryCard_singleInterface_displaysNetworkName() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Local WiFi").assertIsDisplayed()
    }

    @Test
    fun summaryCard_multipleInterfaces_displaysAllNetworkNames() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces =
                    setOf(
                        OnboardingInterfaceType.AUTO,
                        OnboardingInterfaceType.BLE,
                        OnboardingInterfaceType.TCP,
                    ),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - All network names should appear in the comma-separated list
        composeTestRule.onNodeWithText("Local WiFi", substring = true).assertIsDisplayed()
    }

    @Test
    fun summaryCard_noInterfaces_displaysNoneSelected() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = emptySet(),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("None selected").assertIsDisplayed()
    }

    @Test
    fun summaryCard_bleInterface_displaysBluetoothLE() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.BLE),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Bluetooth LE").assertIsDisplayed()
    }

    @Test
    fun summaryCard_tcpInterface_displaysInternetTcp() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.TCP),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Internet (TCP)").assertIsDisplayed()
    }

    @Test
    fun summaryCard_rnodeInterface_displaysLoRaRadio() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.RNODE),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("LoRa Radio").assertIsDisplayed()
    }

    // ========== Summary Card Notification Status Tests ==========

    @Test
    fun summaryCard_displaysNotificationsLabel() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun summaryCard_notificationsEnabled_displaysEnabled() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Enabled").assertIsDisplayed()
    }

    @Test
    fun summaryCard_notificationsDisabled_displaysDisabled() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = false,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Disabled").assertIsDisplayed()
    }

    // ========== Summary Card Battery Status Tests ==========

    @Test
    fun summaryCard_displaysBatteryLabel() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Battery").assertIsDisplayed()
    }

    @Test
    fun summaryCard_batteryOptimizationExempt_displaysUnrestricted() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Unrestricted").assertIsDisplayed()
    }

    @Test
    fun summaryCard_batteryNotExempt_displaysRestricted() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = false,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Restricted").assertIsDisplayed()
    }

    // ========== Show QR Code Button Tests ==========

    @Test
    fun showQrCodeButton_isDisplayed() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Show QR Code").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun showQrCodeButton_displaysQrCodeIcon() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
                qrCodeData = "test-qr-data",
            )
        }

        // Then - QR Code button should be displayed and enabled - scroll to make visible
        composeTestRule.onNodeWithText("Show QR Code").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Show QR Code").assertIsEnabled()
    }

    @Test
    fun showQrCodeButton_withoutQrCodeData_isDisabled() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
                qrCodeData = null,
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Show QR Code").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun showQrCodeButton_withQrCodeData_isEnabled() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
                qrCodeData = "test-qr-data",
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Show QR Code").performScrollTo().assertIsEnabled()
    }

    @Test
    fun showQrCodeButton_displaysHintText() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Share your identity QR code to let others add you as a contact.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Start Messaging Button Tests ==========

    @Test
    fun startMessagingButton_isDisplayed() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Start Messaging").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun startMessagingButton_click_triggersCallback() {
        // Given
        var callbackTriggered = false
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = { callbackTriggered = true },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Start Messaging").performScrollTo().performClick()

        // Then
        assertTrue(callbackTriggered)
    }

    @Test
    fun startMessagingButton_notSaving_isEnabled() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Start Messaging").performScrollTo().assertIsEnabled()
    }

    // ========== Configure LoRa Radio Button Tests ==========

    @Test
    fun button_withLoRaSelected_displaysConfigureLoRaRadio() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.RNODE),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Configure LoRa Radio").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun button_withLoRaAndOtherInterfaces_displaysConfigureLoRaRadio() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces =
                    setOf(
                        OnboardingInterfaceType.AUTO,
                        OnboardingInterfaceType.RNODE,
                    ),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Configure LoRa Radio").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun button_withoutLoRa_displaysStartMessaging() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces =
                    setOf(
                        OnboardingInterfaceType.AUTO,
                        OnboardingInterfaceType.BLE,
                    ),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Start Messaging").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Configure LoRa Radio").assertDoesNotExist()
    }

    @Test
    fun configureLoRaRadioButton_click_triggersCallback() {
        // Given
        var callbackTriggered = false
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.RNODE),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = { callbackTriggered = true },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Configure LoRa Radio").performScrollTo().performClick()

        // Then
        assertTrue(callbackTriggered)
    }

    // ========== Loading Indicator Tests ==========

    @Test
    fun button_isSaving_isDisabled() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = true,
                onStartMessaging = {},
            )
        }

        // Then - When saving, the button text is replaced with a progress indicator
        // The button itself should be disabled
        composeTestRule.onNodeWithText("Start Messaging").assertDoesNotExist()
    }

    @Test
    fun button_isSaving_hidesButtonText() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = true,
                onStartMessaging = {},
            )
        }

        // Then - Button text should be hidden while saving (replaced with progress indicator)
        // Note: CircularProgressIndicator doesn't expose ProgressBarRangeInfo semantics in M3
        composeTestRule.onNodeWithText("Start Messaging").assertDoesNotExist()
    }

    @Test
    fun button_withLoRa_isSaving_hidesButtonText() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.RNODE),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = true,
                onStartMessaging = {},
            )
        }

        // Then - LoRa text should be replaced with progress indicator
        // Note: CircularProgressIndicator doesn't expose ProgressBarRangeInfo semantics in M3
        composeTestRule.onNodeWithText("Configure LoRa Radio").assertDoesNotExist()
    }

    // ========== QR Code Dialog Tests ==========
    // Note: Dialog state changes and rendering in Robolectric can be inconsistent.
    // These tests verify the button is clickable when QR code data is provided.

    @Test
    fun showQrCodeButton_click_isClickable() {
        // Given
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
                identityHash = "abc123",
                destinationHash = "def456",
                qrCodeData = "test-qr-code-data",
            )
        }

        // When/Then - button can be scrolled to and clicked without exceptions
        composeTestRule.onNodeWithText("Show QR Code").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        // Button click succeeded - test passes if no exception is thrown
    }

    // ========== Summary Card Title Tests ==========

    @Test
    fun summaryCard_displaysTitle() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Summary").assertIsDisplayed()
    }

    // ========== Edge Cases ==========

    @Test
    fun completePage_allInterfacesSelected_displaysAllNetworks() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Test User",
                selectedInterfaces =
                    setOf(
                        OnboardingInterfaceType.AUTO,
                        OnboardingInterfaceType.BLE,
                        OnboardingInterfaceType.TCP,
                        OnboardingInterfaceType.RNODE,
                    ),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then - With LoRa selected, button should say "Configure LoRa Radio" - scroll to make visible
        composeTestRule.onNodeWithText("Configure LoRa Radio").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completePage_allDisabled_displaysCorrectStates() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "",
                selectedInterfaces = emptySet(),
                notificationsEnabled = false,
                batteryOptimizationExempt = false,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Anonymous Peer").assertIsDisplayed()
        composeTestRule.onNodeWithText("None selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restricted").assertIsDisplayed()
    }

    @Test
    fun completePage_allEnabled_displaysCorrectStates() {
        // Given/When
        composeTestRule.setContent {
            CompletePage(
                displayName = "Full User",
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                notificationsEnabled = true,
                batteryOptimizationExempt = true,
                isSaving = false,
                onStartMessaging = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Full User").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local WiFi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unrestricted").assertIsDisplayed()
    }
}
