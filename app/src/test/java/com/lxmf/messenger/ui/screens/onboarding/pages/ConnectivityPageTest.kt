package com.lxmf.messenger.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.screens.onboarding.OnboardingInterfaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for ConnectivityPage.kt.
 * Tests interface selection cards, callbacks, BLE permission states, and navigation buttons.
 * Uses Robolectric for local testing without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectivityPageTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Page Header Tests ==========

    @Test
    fun header_displaysTitle() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("How will you connect?").assertIsDisplayed()
    }

    @Test
    fun header_displaysSubtitle() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Select the networks you'd like to use:").assertIsDisplayed()
    }

    // ========== All Interface Options Displayed Tests ==========

    @Test
    fun interfaceOptions_displaysWiFiOption() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(OnboardingInterfaceType.AUTO.displayName).assertIsDisplayed()
    }

    @Test
    fun interfaceOptions_displaysBleOption() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(OnboardingInterfaceType.BLE.displayName).assertIsDisplayed()
    }

    @Test
    fun interfaceOptions_displaysTcpOption() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible if needed
        composeTestRule.onNodeWithText(OnboardingInterfaceType.TCP.displayName)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun interfaceOptions_displaysRnodeOption() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible if needed
        composeTestRule.onNodeWithText(OnboardingInterfaceType.RNODE.displayName)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun interfaceOptions_allFourOptionsDisplayed() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - verify all four interface types are visible (with scrolling for bottom items)
        composeTestRule.onNodeWithText("Local WiFi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth LE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Internet (TCP)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("LoRa Radio").performScrollTo().assertIsDisplayed()
    }

    // ========== Interface Descriptions Tests ==========

    @Test
    fun interfaceDescriptions_displaysWiFiDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(OnboardingInterfaceType.AUTO.description).assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysBleDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(OnboardingInterfaceType.BLE.description).assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysTcpDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText(OnboardingInterfaceType.TCP.description)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysRnodeDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText(OnboardingInterfaceType.RNODE.description)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysWiFiSecondaryDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("No internet required").assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysBleSecondaryDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Requires Bluetooth permissions").assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysTcpSecondaryDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Requires internet connection")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun interfaceDescriptions_displaysRnodeSecondaryDescription() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Requires external hardware - configure in Settings")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Interface Toggle Callback Tests ==========

    @Test
    fun interfaceToggle_wifiClick_triggersCallbackWithAutoType() {
        // Given
        var toggledInterface: OnboardingInterfaceType? = null

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = { toggledInterface = it },
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Local WiFi").performClick()

        // Then
        assertEquals(OnboardingInterfaceType.AUTO, toggledInterface)
    }

    @Test
    fun interfaceToggle_bleClick_triggersCallbackWithBleType() {
        // Given
        var toggledInterface: OnboardingInterfaceType? = null

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = { toggledInterface = it },
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Bluetooth LE").performClick()

        // Then
        assertEquals(OnboardingInterfaceType.BLE, toggledInterface)
    }

    @Test
    fun interfaceToggle_tcpClick_triggersCallbackWithTcpType() {
        // Given
        var toggledInterface: OnboardingInterfaceType? = null

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = { toggledInterface = it },
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Internet (TCP)").performScrollTo().performClick()

        // Then
        assertEquals(OnboardingInterfaceType.TCP, toggledInterface)
    }

    @Test
    fun interfaceToggle_rnodeClick_triggersCallbackWithRnodeType() {
        // Given
        var toggledInterface: OnboardingInterfaceType? = null

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = { toggledInterface = it },
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("LoRa Radio").performScrollTo().performClick()

        // Then
        assertEquals(OnboardingInterfaceType.RNODE, toggledInterface)
    }

    // ========== Selected Interfaces Show As Checked Tests ==========

    @Test
    fun selectedInterfaces_wifiSelected_showsAsChecked() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(OnboardingInterfaceType.AUTO),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - find the checkbox associated with WiFi card
        // The checkbox within the card with "Local WiFi" text should be checked
        composeTestRule.onAllNodes(isToggleable())[0].assertIsOn()
    }

    @Test
    fun selectedInterfaces_bleSelected_showsAsChecked() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(OnboardingInterfaceType.BLE),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onAllNodes(isToggleable())[1].assertIsOn()
    }

    @Test
    fun selectedInterfaces_tcpSelected_showsAsChecked() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(OnboardingInterfaceType.TCP),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onAllNodes(isToggleable())[2].assertIsOn()
    }

    @Test
    fun selectedInterfaces_rnodeSelected_showsAsChecked() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(OnboardingInterfaceType.RNODE),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onAllNodes(isToggleable())[3].assertIsOn()
    }

    @Test
    fun selectedInterfaces_noneSelected_allUnchecked() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - all four checkboxes should be unchecked
        composeTestRule.onAllNodes(isToggleable())[0].assertIsOff()
        composeTestRule.onAllNodes(isToggleable())[1].assertIsOff()
        composeTestRule.onAllNodes(isToggleable())[2].assertIsOff()
        composeTestRule.onAllNodes(isToggleable())[3].assertIsOff()
    }

    @Test
    fun selectedInterfaces_multipleSelected_allShowAsChecked() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(
                    OnboardingInterfaceType.AUTO,
                    OnboardingInterfaceType.BLE,
                    OnboardingInterfaceType.TCP,
                ),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - first three should be checked, last unchecked
        composeTestRule.onAllNodes(isToggleable())[0].assertIsOn()
        composeTestRule.onAllNodes(isToggleable())[1].assertIsOn()
        composeTestRule.onAllNodes(isToggleable())[2].assertIsOn()
        composeTestRule.onAllNodes(isToggleable())[3].assertIsOff()
    }

    // ========== Navigation Button Tests ==========

    @Test
    fun navigationButtons_backButtonDisplayed() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun navigationButtons_continueButtonDisplayed() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun navigationButtons_backClick_triggersOnBackCallback() {
        // Given
        var backClicked = false

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = { backClicked = true },
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Back").performScrollTo().performClick()

        // Then
        assertTrue(backClicked)
    }

    @Test
    fun navigationButtons_continueClick_triggersOnContinueCallback() {
        // Given
        var continueClicked = false

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = { continueClicked = true },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Continue").performScrollTo().performClick()

        // Then
        assertTrue(continueClicked)
    }

    // ========== BLE Permission States Tests ==========

    @Test
    fun blePermissions_denied_showsWarningText() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = true,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Permissions denied").assertIsDisplayed()
    }

    @Test
    fun blePermissions_granted_showsSuccessIndicator() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = true,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Permissions granted").assertIsDisplayed()
    }

    @Test
    fun blePermissions_neitherGrantedNorDenied_showsNoStatusText() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - neither status text should be visible
        composeTestRule.onNodeWithText("Permissions denied").assertDoesNotExist()
        composeTestRule.onNodeWithText("Permissions granted").assertDoesNotExist()
    }

    @Test
    fun blePermissions_deniedWithBleSelected_showsWarning() {
        // Given/When - BLE is selected but permissions denied
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(OnboardingInterfaceType.BLE),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = true,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - should still show warning even when selected
        composeTestRule.onNodeWithText("Permissions denied").assertIsDisplayed()
    }

    @Test
    fun blePermissions_grantedWithBleSelected_showsSuccess() {
        // Given/When - BLE is selected and permissions granted
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(OnboardingInterfaceType.BLE),
                onInterfaceToggle = {},
                blePermissionsGranted = true,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Permissions granted").assertIsDisplayed()
    }

    // ========== Helper Text Tests ==========

    @Test
    fun helperText_displaysConfigureLaterMessage() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("You can configure these later in Settings")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Edge Cases ==========

    @Test
    fun edgeCase_allInterfacesSelected_displaysCorrectly() {
        // Given/When
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = setOf(
                    OnboardingInterfaceType.AUTO,
                    OnboardingInterfaceType.BLE,
                    OnboardingInterfaceType.TCP,
                    OnboardingInterfaceType.RNODE,
                ),
                onInterfaceToggle = {},
                blePermissionsGranted = true,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - all checkboxes should be on
        composeTestRule.onAllNodes(isToggleable())[0].assertIsOn()
        composeTestRule.onAllNodes(isToggleable())[1].assertIsOn()
        composeTestRule.onAllNodes(isToggleable())[2].assertIsOn()
        composeTestRule.onAllNodes(isToggleable())[3].assertIsOn()
    }

    @Test
    fun edgeCase_bothBleStatesTrue_showsDenied() {
        // Given - edge case where both states are true (shouldn't happen, but testing resilience)
        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = {},
                blePermissionsGranted = true,
                blePermissionsDenied = true,
                onBack = {},
                onContinue = {},
            )
        }

        // Then - denied takes precedence based on the when statement in the implementation
        composeTestRule.onNodeWithText("Permissions denied").assertIsDisplayed()
    }

    @Test
    fun checkboxClick_triggersOnInterfaceToggle() {
        // Given
        var toggledInterface: OnboardingInterfaceType? = null

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = { toggledInterface = it },
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // When - clicking the checkbox directly
        composeTestRule.onAllNodes(isToggleable())[0].performClick()

        // Then
        assertEquals(OnboardingInterfaceType.AUTO, toggledInterface)
    }

    @Test
    fun multipleToggleCallbacks_eachTriggersCorrectType() {
        // Given
        val toggledInterfaces = mutableListOf<OnboardingInterfaceType>()

        composeTestRule.setContent {
            ConnectivityPage(
                selectedInterfaces = emptySet(),
                onInterfaceToggle = { toggledInterfaces.add(it) },
                blePermissionsGranted = false,
                blePermissionsDenied = false,
                onBack = {},
                onContinue = {},
            )
        }

        // When - click each interface in order (with scrolling for bottom items)
        composeTestRule.onNodeWithText("Local WiFi").performClick()
        composeTestRule.onNodeWithText("Bluetooth LE").performClick()
        composeTestRule.onNodeWithText("Internet (TCP)").performScrollTo().performClick()
        composeTestRule.onNodeWithText("LoRa Radio").performScrollTo().performClick()

        // Then
        assertEquals(4, toggledInterfaces.size)
        assertEquals(OnboardingInterfaceType.AUTO, toggledInterfaces[0])
        assertEquals(OnboardingInterfaceType.BLE, toggledInterfaces[1])
        assertEquals(OnboardingInterfaceType.TCP, toggledInterfaces[2])
        assertEquals(OnboardingInterfaceType.RNODE, toggledInterfaces[3])
    }
}
