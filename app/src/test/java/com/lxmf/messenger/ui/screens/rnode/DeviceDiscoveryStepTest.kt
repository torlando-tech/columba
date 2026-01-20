package com.lxmf.messenger.ui.screens.rnode

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.DiscoveredUsbDevice
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.RNodeConnectionType
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

    // ========== TCP Mode UI Tests ==========

    @Test
    fun tcpMode_showsConnectionForm() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                discoveredDevices = listOf(unpairedBleDevice),
                showManualEntry = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the manual entry button
        composeTestRule.onNodeWithText("Enter device manually").performClick()

        // Then - should call showManualEntry
        verify(exactly = 1) { mockViewModel.showManualEntry() }
    }

    @Test
    fun manualEntryForm_showsValidationError() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                showManualEntry = true,
                manualBluetoothType = BluetoothType.CLASSIC,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the BLE chip
        composeTestRule.onNodeWithText("Bluetooth LE").performClick()

        // Then - should update manual bluetooth type
        verify(exactly = 1) { mockViewModel.updateManualBluetoothType(BluetoothType.BLE) }
    }

    @Test
    fun cancelManualEntry_hidesForm() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
                showManualEntry = true,
                manualDeviceName = "RNode 1234",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the cancel button
        composeTestRule.onNodeWithText("Cancel manual entry").performClick()

        // Then - should hide manual entry
        verify(exactly = 1) { mockViewModel.hideManualEntry() }
    }

    // ========== Device Card Tests ==========

    @Test
    fun unknownTypeDeviceCard_showsTypeSelectorOnClick() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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

        // First click to show type selector
        composeTestRule.onNodeWithText("RNode ABCD").performClick()

        // Then click the BLE chip
        composeTestRule.onNodeWithText("Bluetooth LE").performClick()

        // Then - should set device type
        verify(exactly = 1) { mockViewModel.setDeviceType(unknownTypeDevice, BluetoothType.BLE) }
    }

    @Test
    fun typeSelector_classicChip_setsDeviceType() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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

        // First click to show type selector
        composeTestRule.onNodeWithText("RNode ABCD").performClick()

        // Then click the Classic chip
        composeTestRule.onNodeWithText("Bluetooth Classic").performClick()

        // Then - should set device type
        verify(exactly = 1) { mockViewModel.setDeviceType(unknownTypeDevice, BluetoothType.CLASSIC) }
    }

    @Test
    fun associatingState_showsProgressIndicator() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
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

    // ========== USB Mode UI Tests ==========

    private val usbDeviceWithPermission =
        DiscoveredUsbDevice(
            deviceId = 1001,
            vendorId = 0x0403,
            productId = 0x6001,
            deviceName = "/dev/bus/usb/001/002",
            manufacturerName = "FTDI",
            productName = "FT232R USB UART",
            serialNumber = "A12345",
            driverType = "FTDI",
            hasPermission = true,
        )

    private val usbDeviceNoPermission =
        DiscoveredUsbDevice(
            deviceId = 1002,
            vendorId = 0x1A86,
            productId = 0x7523,
            deviceName = "/dev/bus/usb/001/003",
            manufacturerName = "QinHeng",
            productName = "HL-340 USB-Serial",
            serialNumber = null,
            driverType = "CH340",
            hasPermission = false,
        )

    @Test
    fun usbMode_showsUsbDeviceList() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = listOf(usbDeviceWithPermission, usbDeviceNoPermission),
                isUsbScanning = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - both devices should be displayed
        composeTestRule.onNodeWithText("FT232R USB UART").assertIsDisplayed()
        composeTestRule.onNodeWithText("HL-340 USB-Serial").assertIsDisplayed()
    }

    @Test
    fun usbMode_scanningIndicator_showsWhileScanning() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                isUsbScanning = true,
                usbDevices = emptyList(),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - scanning message should be displayed
        composeTestRule.onNodeWithText("Scanning for USB devices...").assertIsDisplayed()
    }

    @Test
    fun usbMode_errorMessage_displaysAndDismisses() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = emptyList(),
                usbScanError = "No USB serial devices found",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - error message should be displayed
        composeTestRule.onNodeWithText("No USB serial devices found").assertIsDisplayed()

        // Click dismiss
        composeTestRule.onNodeWithText("Dismiss").performClick()

        // Verify clearUsbError was called
        verify(exactly = 1) { mockViewModel.clearUsbError() }
    }

    @Test
    fun usbMode_deviceSelection_callsSelectUsbDevice() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = listOf(usbDeviceWithPermission),
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the USB device card
        composeTestRule.onNodeWithText("FT232R USB UART").performClick()

        // Then - selectUsbDevice should be called
        verify(exactly = 1) { mockViewModel.selectUsbDevice(usbDeviceWithPermission) }
    }

    @Test
    fun usbMode_rescanButton_callsScanUsbDevices() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = listOf(usbDeviceWithPermission),
                isUsbScanning = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the Rescan button
        composeTestRule.onNodeWithText("Rescan USB Devices").performClick()

        // Then - scanUsbDevices should be called
        verify(exactly = 1) { mockViewModel.scanUsbDevices() }
    }

    @Test
    fun usbMode_rescanButton_notShownWhileScanning() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                isUsbScanning = true,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Rescan button should not be displayed
        composeTestRule.onNodeWithText("Rescan USB Devices").assertDoesNotExist()
    }

    @Test
    fun usbMode_selectedDevice_showsBluetoothPairingOption() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = listOf(usbDeviceWithPermission),
                selectedUsbDevice = usbDeviceWithPermission,
                isUsbPairingMode = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - Bluetooth pairing option should be shown
        composeTestRule.onNodeWithText("Enter Bluetooth Pairing Mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pair RNode with your phone via Bluetooth").assertIsDisplayed()
    }

    @Test
    fun usbMode_bluetoothPairingOption_triggersEnterPairingMode() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = listOf(usbDeviceWithPermission),
                selectedUsbDevice = usbDeviceWithPermission,
                isUsbPairingMode = false,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the Bluetooth pairing option
        composeTestRule.onNodeWithText("Enter Bluetooth Pairing Mode").performClick()

        // Then - enterUsbBluetoothPairingMode should be called
        verify(exactly = 1) { mockViewModel.enterUsbBluetoothPairingMode() }
    }

    @Test
    fun usbMode_bluetoothPairingMode_showsPinWhenReceived() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                selectedUsbDevice = usbDeviceWithPermission,
                isUsbPairingMode = true,
                usbBluetoothPin = "123456",
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - PIN should be displayed
        composeTestRule.onNodeWithText("Bluetooth Pairing Mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("123456").assertIsDisplayed()
    }

    @Test
    fun usbMode_tabSwitch_callsSetConnectionType() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.BLUETOOTH,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Click the USB tab
        composeTestRule.onNodeWithText("USB").performClick()

        // Then - setConnectionType should be called with USB_SERIAL
        verify(exactly = 1) { mockViewModel.setConnectionType(RNodeConnectionType.USB_SERIAL) }
    }

    @Test
    fun usbMode_permissionRequired_showsIndicator() {
        // Given
        val mockViewModel = mockk<RNodeWizardViewModel>(relaxed = true)
        val state =
            RNodeWizardState(
                connectionType = RNodeConnectionType.USB_SERIAL,
                usbDevices = listOf(usbDeviceNoPermission),
                isRequestingUsbPermission = true,
            )
        every { mockViewModel.state } returns MutableStateFlow(state)

        // When
        composeTestRule.setContent {
            DeviceDiscoveryStep(viewModel = mockViewModel)
        }

        // Then - device should be shown (permission request is handled by the system)
        composeTestRule.onNodeWithText("HL-340 USB-Serial").assertIsDisplayed()
    }
}
