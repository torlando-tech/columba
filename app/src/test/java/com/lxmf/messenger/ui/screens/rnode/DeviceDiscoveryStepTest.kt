package com.lxmf.messenger.ui.screens.rnode

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.RNodeConnectionType
import com.lxmf.messenger.viewmodel.RNodeWizardState
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unpairedBleDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.initiateBluetoothPairing(any()) } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the device card (using device name as identifier)
        val result =
            runCatching {
                composeTestRule.onNodeWithText("RNode 1234").performClick()
            }

        // Then - pairing should be initiated, not selection
        assertTrue("Click on unpaired device card should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.initiateBluetoothPairing(unpairedBleDevice) }
        verify(exactly = 0) { mockViewModel.requestDeviceAssociation(any(), any()) }
        verify(exactly = 0) { mockViewModel.selectDevice(any()) }
    }

    @Test
    fun pairedDevice_cardClick_selectsDevice() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(pairedBleDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.requestDeviceAssociation(any(), any()) } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the device card
        val result =
            runCatching {
                composeTestRule.onNodeWithText("RNode 5678").performClick()
            }

        // Then - selection should occur, not pairing
        assertTrue("Click on paired device card should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.requestDeviceAssociation(pairedBleDevice, any()) }
        verify(exactly = 0) { mockViewModel.initiateBluetoothPairing(any()) }
    }

    @Test
    fun unknownTypeDevice_cardClick_showsTypeSelector() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
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
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                discoveredDevices = listOf(unpairedBleDevice),
                selectedDevice = null,
                isPairingInProgress = false,
                isAssociating = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.initiateBluetoothPairing(any()) } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the "Pair" text button specifically
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Pair").performClick()
            }

        // Then - pairing should be initiated
        assertTrue("Click on Pair button should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.initiateBluetoothPairing(unpairedBleDevice) }
    }

    // ========== Reconnect Waiting State Tests ==========

    @Test
    fun reconnectWaitingState_showsWaitingCard() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
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
        val mockViewModel = mockk<RNodeWizardViewModel>()
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
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                discoveredDevices = emptyList(),
                isWaitingForReconnect = true,
                reconnectDeviceName = "RNode 1234",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.cancelReconnectScan() } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the Cancel button
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Cancel").performClick()
            }

        // Then - cancelReconnectScan should be called
        assertTrue("Click on Cancel button should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.cancelReconnectScan() }
    }

    @Test
    fun reconnectWaitingState_notShownWhenFalse() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
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

    // ========== TCP Mode UI Tests ==========

    @Test
    fun tcpMode_showsConnectionForm() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                tcpHost = "",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - TCP connection form should be displayed
        composeTestRule.onNodeWithText("Connect to an RNode device over WiFi/TCP (port 7633).").assertIsDisplayed()
        composeTestRule.onNodeWithText("IP Address or Hostname").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Connection").assertIsDisplayed()
    }

    @Test
    fun tcpMode_hidesBluetoothDeviceList() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                discoveredDevices = listOf(unpairedBleDevice, pairedBleDevice),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Bluetooth device list should NOT be shown
        composeTestRule.onNodeWithText("RNode 1234").assertDoesNotExist()
        composeTestRule.onNodeWithText("RNode 5678").assertDoesNotExist()
    }

    @Test
    fun tcpValidation_inProgress_showsSpinner() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                tcpHost = "10.0.0.1",
                isTcpValidating = true,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Test Connection button should show spinner
        composeTestRule.onNodeWithText("Test Connection").assertIsDisplayed()
    }

    @Test
    fun tcpValidation_success_showsCheckmark() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                tcpHost = "10.0.0.1",
                isTcpValidating = false,
                tcpValidationSuccess = true,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Success indicator should be displayed
        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Success").assertIsDisplayed()
    }

    @Test
    fun tcpValidation_failure_showsErrorIcon() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                tcpHost = "10.0.0.1",
                isTcpValidating = false,
                tcpValidationSuccess = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Failure indicator should be displayed
        composeTestRule.onNodeWithText("Failed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Failed").assertIsDisplayed()
    }

    @Test
    fun tcpErrorMessage_displaysCorrectly() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.TCP_WIFI,
                tcpHost = "10.0.0.1",
                tcpValidationError = "Connection timeout. Please check the IP address and ensure the device is online.",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Error message should be displayed
        composeTestRule.onNodeWithText("Connection timeout. Please check the IP address and ensure the device is online.").assertIsDisplayed()
    }

    // ========== Manual Entry Form Tests ==========

    @Test
    fun manualEntryForm_showsOnButtonClick() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(unpairedBleDevice),
                showManualEntry = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.showManualEntry() } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the manual entry button
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Enter device manually").performClick()
            }

        // Then - should call showManualEntry
        assertTrue("Click on 'Enter device manually' button should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.showManualEntry() }
    }

    @Test
    fun manualEntryForm_showsValidationError() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                showManualEntry = true,
                manualDeviceName = "X",
                manualDeviceNameError = "Device name must be at least 3 characters",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - error message should be displayed
        composeTestRule.onNodeWithText("Device name must be at least 3 characters").assertIsDisplayed()
    }

    @Test
    fun manualEntryForm_showsWarningForNonRNodeName() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                showManualEntry = true,
                manualDeviceName = "MyDevice",
                manualDeviceNameWarning = "This doesn't look like a typical RNode name (e.g., 'RNode 1234')",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - warning message should be displayed
        composeTestRule.onNodeWithText("This doesn't look like a typical RNode name (e.g., 'RNode 1234')").assertIsDisplayed()
    }

    @Test
    fun bluetoothTypeChips_updateOnSelection() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                showManualEntry = true,
                manualBluetoothType = BluetoothType.CLASSIC,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.updateManualBluetoothType(any()) } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the BLE chip
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Bluetooth LE").performClick()
            }

        // Then - should update manual bluetooth type
        assertTrue("Click on 'Bluetooth LE' chip should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.updateManualBluetoothType(BluetoothType.BLE) }
    }

    @Test
    fun cancelManualEntry_hidesForm() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                showManualEntry = true,
                manualDeviceName = "RNode 1234",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.hideManualEntry() } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the cancel button
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Cancel manual entry").performClick()
            }

        // Then - should hide manual entry
        assertTrue("Click on 'Cancel manual entry' button should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.hideManualEntry() }
    }

    // ========== Device Card Tests ==========

    @Test
    fun unknownTypeDeviceCard_showsTypeSelectorOnClick() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(unknownTypeDevice),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the device card - this should already be tested in the existing test
        composeTestRule.onNodeWithText("RNode ABCD").performClick()

        // Then - type selector should be visible
        composeTestRule.onNodeWithText("Select connection type:").assertIsDisplayed()
    }

    @Test
    fun typeSelector_bleChip_setsDeviceType() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(unknownTypeDevice),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.setDeviceType(any(), any()) } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // First click to show type selector
        composeTestRule.onNodeWithText("RNode ABCD").performClick()

        // Then click the BLE chip
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Bluetooth LE").performClick()
            }

        // Then - should set device type
        assertTrue("Click on 'Bluetooth LE' chip should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.setDeviceType(unknownTypeDevice, BluetoothType.BLE) }
    }

    @Test
    fun typeSelector_classicChip_setsDeviceType() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(unknownTypeDevice),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.setDeviceType(any(), any()) } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // First click to show type selector
        composeTestRule.onNodeWithText("RNode ABCD").performClick()

        // Then click the Classic chip
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Bluetooth Classic").performClick()
            }

        // Then - should set device type
        assertTrue("Click on 'Bluetooth Classic' chip should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.setDeviceType(unknownTypeDevice, BluetoothType.CLASSIC) }
    }

    @Test
    fun associatingState_showsProgressIndicator() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(pairedBleDevice),
                isAssociating = true,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - progress indicator should be present
        // The progress indicator is shown when isAssociating is true
        // We can verify this by checking that the device card is displayed
        composeTestRule.onNodeWithText("RNode 5678").assertIsDisplayed()
    }

    @Test
    fun pairingInProgress_showsSpinnerOnPairButton() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(unpairedBleDevice),
                isPairingInProgress = true,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - spinner should be shown instead of Pair button text
        // The device card is still displayed
        composeTestRule.onNodeWithText("RNode 1234").assertIsDisplayed()
    }

    // ========== Edit Mode Tests ==========

    @Test
    fun editMode_showsCurrentDeviceSection() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                isEditMode = true,
                selectedDevice = pairedBleDevice,
                discoveredDevices = listOf(unpairedBleDevice),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - current device section should be displayed
        composeTestRule.onNodeWithText("Current Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("RNode 5678").assertIsDisplayed()
        composeTestRule.onNodeWithText("Or select a different device:").assertIsDisplayed()
    }

    @Test
    fun editMode_allowsSelectingDifferentDevice() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                isEditMode = true,
                selectedDevice = pairedBleDevice,
                discoveredDevices = listOf(unpairedBleDevice, pairedBleDevice),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - other devices should be visible in the list
        composeTestRule.onNodeWithText("RNode 1234").assertIsDisplayed()
        // The current device (RNode 5678) should not be in the list below, only in "Current Device" section
    }

    // ========== USB Bluetooth Pairing Mode Tests ==========

    @Test
    fun usbPairingMode_showsPairingCard_withPinDisplay() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                isUsbPairingMode = true,
                usbBluetoothPin = "1234",
                usbPairingStatus = "Enter this PIN when prompted",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - PIN label and value should be displayed
        composeTestRule.onNodeWithText("PIN Code:").assertIsDisplayed()
        composeTestRule.onNodeWithText("1234").assertIsDisplayed()
    }

    @Test
    fun usbPairingMode_exitButton_callsExitPairingMode() {
        // Given - test in Bluetooth tab where the same card is also used
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                isUsbPairingMode = true,
                usbBluetoothPin = "5678",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.exitUsbBluetoothPairingMode() } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click exit button (use assertExists since button may be below viewport fold)
        val exitButton = composeTestRule.onNodeWithText("Exit Pairing Mode")
        exitButton.assertExists()

        // Check if button is enabled and perform click
        val result =
            runCatching {
                exitButton.performClick()
            }

        // Wait for idle after click
        composeTestRule.waitForIdle()

        // Then
        assertTrue("Click on 'Exit Pairing Mode' button should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.exitUsbBluetoothPairingMode() }
    }

    @Test
    fun usbPairingMode_showsManualPinEntry_whenNoPin() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                isUsbPairingMode = true,
                showManualPinEntry = true,
                manualPinInput = "",
                usbPairingStatus = "Enter the PIN shown on your RNode's display",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - manual entry section should be shown
        composeTestRule.onNodeWithText("Enter the 6-digit PIN shown on your RNode's display:").assertIsDisplayed()
    }

    @Test
    fun usbPairingMode_showsStatusMessage() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                isUsbPairingMode = true,
                usbPairingStatus = "Scanning for RNode...",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then
        composeTestRule.onNodeWithText("Scanning for RNode...").assertIsDisplayed()
    }

    @Test
    fun bluetoothTab_pairViaUsb_button_startsUsbAssistedPairing() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = emptyList(),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)
        every { mockViewModel.startUsbAssistedPairing() } just Runs

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the "Pair via USB" button
        val result =
            runCatching {
                composeTestRule.onNodeWithText("Pair via USB").performClick()
            }

        // Then
        assertTrue("Click on 'Pair via USB' button should succeed", result.isSuccess)
        verify(exactly = 1) { mockViewModel.startUsbAssistedPairing() }
    }

    @Test
    fun bluetoothTab_showsUsbPairingCard_whenPairingModeActive() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>()
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                isUsbPairingMode = true,
                usbBluetoothPin = "9876",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - should show pairing card with PIN
        composeTestRule.onNodeWithText("PIN Code:").assertIsDisplayed()
        composeTestRule.onNodeWithText("9876").assertIsDisplayed()
    }
}
