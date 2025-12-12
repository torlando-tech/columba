package com.lxmf.messenger.ui.screens.rnode

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.test.TestActivity
import com.lxmf.messenger.viewmodel.RNodeWizardState
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for DeviceDiscoveryStep.
 * Tests card click behavior for paired, unpaired, and unknown type devices.
 */
class DeviceDiscoveryStepTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

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
        composeTestRule.waitForIdle()

        // Click the device card (using device name as identifier)
        composeTestRule.onNodeWithText("RNode 1234").performClick()
        composeTestRule.waitForIdle()

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
        composeTestRule.waitForIdle()

        // Click the device card
        composeTestRule.onNodeWithText("RNode 5678").performClick()
        composeTestRule.waitForIdle()

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
        composeTestRule.waitForIdle()

        // Click the device card
        composeTestRule.onNodeWithText("RNode ABCD").performClick()
        composeTestRule.waitForIdle()

        // Then - neither pairing nor selection should occur (type selector should show instead)
        verify(exactly = 0) { mockViewModel.initiateBluetoothPairing(any()) }
        verify(exactly = 0) { mockViewModel.requestDeviceAssociation(any(), any()) }
        verify(exactly = 0) { mockViewModel.selectDevice(any()) }

        // Type selector options should be visible
        composeTestRule.onNodeWithText("Select connection type:").assertExists()
        composeTestRule.onNodeWithText("Bluetooth Classic").assertExists()
        composeTestRule.onNodeWithText("Bluetooth LE").assertExists()
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
        composeTestRule.waitForIdle()

        // Click the "Pair" text button specifically
        composeTestRule.onNodeWithText("Pair").performClick()
        composeTestRule.waitForIdle()

        // Then - pairing should be initiated
        verify(exactly = 1) { mockViewModel.initiateBluetoothPairing(unpairedBleDevice) }
    }
}
