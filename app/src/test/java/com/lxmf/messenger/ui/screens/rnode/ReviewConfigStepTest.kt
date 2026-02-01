package com.lxmf.messenger.ui.screens.rnode

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.FrequencyRegion
import com.lxmf.messenger.data.model.ModemPreset
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.RNodeConnectionType
import com.lxmf.messenger.viewmodel.RNodeWizardState
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for ReviewConfigStep.
 * Tests display of device info, region settings, duty cycle warnings, and advanced settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReviewConfigStepTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private val testFrequencyRegion =
        FrequencyRegion(
            id = "us_915",
            name = "US / Americas (915 MHz)",
            frequencyStart = 902_000_000,
            frequencyEnd = 928_000_000,
            maxTxPower = 30,
            defaultTxPower = 17,
            dutyCycle = 100,
            description = "902-928 MHz ISM band",
        )

    private val restrictedRegion =
        FrequencyRegion(
            id = "eu_868_m",
            name = "Europe 868 MHz (1%)",
            frequencyStart = 868_000_000,
            frequencyEnd = 868_600_000,
            maxTxPower = 14,
            defaultTxPower = 14,
            dutyCycle = 1,
            description = "Sub-band M: 1% duty cycle, 25 mW (LoRaWAN default)",
        )

    private val testDevice =
        DiscoveredRNode(
            name = "RNode 1234",
            address = "AA:BB:CC:DD:EE:FF",
            type = BluetoothType.BLE,
            rssi = -65,
            isPaired = true,
        )

    @Test
    fun tcpMode_showsWifiIcon() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                tcpHost = "10.0.0.1",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns true
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "10.0.0.1"
        every { mockViewModel.getEffectiveBluetoothType() } returns null
        every { mockViewModel.getConnectionTypeString() } returns "WiFi / TCP"
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Then - WiFi icon should be displayed
        composeTestRule.onNodeWithText("Connection").assertIsDisplayed()
        composeTestRule.onNodeWithText("WiFi / TCP").assertIsDisplayed()
    }

    @Test
    fun bluetoothMode_showsBluetoothIcon() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Then - Bluetooth type should be displayed
        composeTestRule.onNodeWithText("Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth LE").assertIsDisplayed()
    }

    @Test
    fun showsCorrectDeviceName_fromViewModel() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Then - device name should be displayed
        composeTestRule.onNodeWithText("RNode 1234").assertIsDisplayed()
    }

    @Test
    fun showsFrequencyRegionCard_whenNotCustomMode() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
                isCustomMode = false,
                selectedFrequencyRegion = testFrequencyRegion,
                selectedModemPreset = ModemPreset.LONG_FAST,
                selectedSlot = 20,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getFrequencyForSlot(20) } returns 914875000L
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Then - frequency region card should be displayed
        composeTestRule.onNodeWithText("Frequency Region").assertIsDisplayed()
        composeTestRule.onNodeWithText("US / Americas (915 MHz)").assertIsDisplayed()
        composeTestRule.onNodeWithText("30 dBm max", substring = true).assertIsDisplayed()
    }

    @Test
    fun hidesRegionCard_inCustomMode() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
                isCustomMode = true,
                selectedFrequencyRegion = testFrequencyRegion,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Then - frequency region card should NOT be displayed
        composeTestRule.onNodeWithText("Frequency Region").assertDoesNotExist()
    }

    @Test
    fun showsDutyCycleWarning_forRestrictedRegions() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
                isCustomMode = false,
                selectedFrequencyRegion = restrictedRegion,
                selectedModemPreset = ModemPreset.LONG_FAST,
                selectedSlot = 1,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getFrequencyForSlot(1) } returns 868125000L
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Then - duty cycle warning should be displayed (scroll to ensure visible)
        composeTestRule.onNode(hasText("Duty Cycle Limit: 1%")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun advancedSettingsButton_togglesVisibility() {
        // Given
        var toggleCalled = false
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
                showAdvancedSettings = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getRegionLimits() } returns null
        every { mockViewModel.toggleAdvancedSettings() } answers { toggleCalled = true }

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Scroll to and click the Advanced Settings button
        composeTestRule.onNode(hasText("Advanced Settings")).performScrollTo().performClick()

        // Then - should call toggleAdvancedSettings
        assertTrue("toggleAdvancedSettings should be called when button is clicked", toggleCalled)
        verify(exactly = 1) { mockViewModel.toggleAdvancedSettings() }
    }

    @Test
    fun interfaceModeSelector_showsDropdownOnClick() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                selectedDevice = testDevice,
                showAdvancedSettings = true,
                interfaceMode = "full",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.isTcpMode() } returns false
        every { mockViewModel.isUsbMode() } returns false
        every { mockViewModel.getEffectiveDeviceName() } returns "RNode 1234"
        every { mockViewModel.getEffectiveBluetoothType() } returns BluetoothType.BLE
        every { mockViewModel.getConnectionTypeString() } returns "Bluetooth LE"
        every { mockViewModel.getRegionLimits() } returns null

        // When
        composeTestRule.setContent {
            ReviewConfigStep(viewModel = mockViewModel)
        }

        // Scroll to and click on the interface mode dropdown
        composeTestRule.onNode(hasText("Full (all features enabled)")).performScrollTo().performClick()

        // Then - dropdown menu items should be visible
        composeTestRule.onNodeWithText("Gateway (path discovery for others)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Access Point (quiet unless active)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Roaming (mobile relative to others)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Boundary (network edge)").assertIsDisplayed()
    }
}
