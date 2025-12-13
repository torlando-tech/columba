package com.lxmf.messenger.ui.screens.rnode

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.RNodeWizardState
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for DeviceDiscoveryStep.
 * Tests card click behavior for paired, unpaired, and unknown type devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceDiscoveryStepTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private val unpairedBleDevice =
        DiscoveredRNode(
            name = "RNode 1234",
            address = "AA:BB:CC:DD:EE:FF",
            type = BluetoothType.BLE,
            rssi = -65,
            isPaired = false,
        )

    private val pairedBleDevice =
        DiscoveredRNode(
            name = "RNode 5678",
            address = "11:22:33:44:55:66",
            type = BluetoothType.BLE,
            rssi = -70,
            isPaired = true,
        )

    private val unknownTypeDevice =
        DiscoveredRNode(
            name = "RNode ABCD",
            address = "AA:11:BB:22:CC:33",
            type = BluetoothType.UNKNOWN,
            rssi = null,
            isPaired = false,
        )

    @Test
    fun unpairedDevice_cardClick_initiatesPairing() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unpairedBleDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the device card (using device name as identifier)
        composeTestRule.onNodeWithText("RNode 1234").performClick()

        // Then - pairing should be initiated, not selection
        verify(exactly = 1) { mockViewModel.initiateBluetoothPairing(unpairedBleDevice) }
        verify(exactly = 0) { mockViewModel.requestDeviceAssociation(any(), any()) }
        verify(exactly = 0) { mockViewModel.selectDevice(any()) }
    }

    @Test
    fun pairedDevice_cardClick_selectsDevice() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(pairedBleDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the device card
        composeTestRule.onNodeWithText("RNode 5678").performClick()

        // Then - selection should occur, not pairing
        verify(exactly = 1) { mockViewModel.requestDeviceAssociation(pairedBleDevice, any()) }
        verify(exactly = 0) { mockViewModel.initiateBluetoothPairing(any()) }
    }

    @Test
    fun unknownTypeDevice_cardClick_showsTypeSelector() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unknownTypeDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the device card
        composeTestRule.onNodeWithText("RNode ABCD").performClick()

        // Then - neither pairing nor selection should occur (type selector should show instead)
        verify(exactly = 0) { mockViewModel.initiateBluetoothPairing(any()) }
        verify(exactly = 0) { mockViewModel.requestDeviceAssociation(any(), any()) }
        verify(exactly = 0) { mockViewModel.selectDevice(any()) }

        // Type selector options should be visible
        composeTestRule.onNodeWithText("Select connection type:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth Classic").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth LE").assertIsDisplayed()
    }

    @Test
    fun unpairedDevice_pairTextButton_initiatesPairing() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unpairedBleDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the "Pair" text button specifically
        composeTestRule.onNodeWithText("Pair").performClick()

        // Then - pairing should be initiated
        verify(exactly = 1) { mockViewModel.initiateBluetoothPairing(unpairedBleDevice) }
    }

    // ========== Reconnect Waiting State Tests ==========

    @Test
    fun reconnectWaitingState_showsWaitingCard() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unpairedBleDevice),
                isWaitingForReconnect = true,
                reconnectDeviceName = "RNode 1234",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - waiting card should be displayed
        composeTestRule.onNodeWithText("Waiting for RNode to reconnect...").assertIsDisplayed()
    }

    @Test
    fun reconnectWaitingState_showsDeviceName() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = emptyList(),
                isWaitingForReconnect = true,
                reconnectDeviceName = "My RNode Device",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - device name should be shown
        composeTestRule.onNodeWithText("Looking for: My RNode Device").assertIsDisplayed()
    }

    @Test
    fun reconnectWaitingState_cancelButton_callsCancelReconnectScan() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = emptyList(),
                isWaitingForReconnect = true,
                reconnectDeviceName = "RNode 1234",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the Cancel button
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Then - cancelReconnectScan should be called
        verify(exactly = 1) { mockViewModel.cancelReconnectScan() }
    }

    @Test
    fun reconnectWaitingState_notShownWhenFalse() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unpairedBleDevice),
                isWaitingForReconnect = false,
                reconnectDeviceName = null,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - waiting card should NOT be displayed
        composeTestRule.onNodeWithText("Waiting for RNode to reconnect...").assertDoesNotExist()
    }
}
