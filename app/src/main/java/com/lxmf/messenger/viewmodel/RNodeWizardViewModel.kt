package com.lxmf.messenger.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.CommunitySlots
import com.lxmf.messenger.data.model.DeviceClassifier
import com.lxmf.messenger.data.model.DeviceTypeCache
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.FrequencyRegion
import com.lxmf.messenger.data.model.FrequencyRegions
import com.lxmf.messenger.data.model.FrequencySlotCalculator
import com.lxmf.messenger.data.model.ModemPreset
import com.lxmf.messenger.data.model.RNodeRegionalPreset
import com.lxmf.messenger.data.model.RNodeRegionalPresets
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.ble.util.BlePairingHandler
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.util.RssiThrottler
import com.lxmf.messenger.util.validation.DeviceNameValidator
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Wizard step enumeration.
 */
enum class WizardStep {
    DEVICE_DISCOVERY,
    REGION_SELECTION,
    MODEM_PRESET,
    FREQUENCY_SLOT,
    REVIEW_CONFIGURE,
}

/**
 * Connection type for RNode devices.
 */
enum class RNodeConnectionType {
    BLUETOOTH, // Classic or BLE Bluetooth
    TCP_WIFI, // TCP over WiFi
    USB_SERIAL, // USB Serial (CDC-ACM, FTDI, CP210x, CH340, etc.)
}

/** Default interface name - used to detect if user has customized it */
private const val DEFAULT_INTERFACE_NAME = "RNode LoRa"

/**
 * Regulatory limits for a frequency region.
 * Used to validate user input against regional regulations.
 */
data class RegionLimits(
    val maxTxPower: Int,
    val minFrequency: Long,
    val maxFrequency: Long,
    val dutyCycle: Int,
)

/**
 * State for the RNode setup wizard.
 */
@androidx.compose.runtime.Immutable
data class RNodeWizardState(
    // Wizard navigation
    val currentStep: WizardStep = WizardStep.DEVICE_DISCOVERY,
    // Edit mode
    val editingInterfaceId: Long? = null,
    val isEditMode: Boolean = false,
    // Step 1: Device Discovery
    val connectionType: RNodeConnectionType = RNodeConnectionType.BLUETOOTH,
    val isScanning: Boolean = false,
    val discoveredDevices: List<DiscoveredRNode> = emptyList(),
    val selectedDevice: DiscoveredRNode? = null,
    val scanError: String? = null,
    val showManualEntry: Boolean = false,
    val manualDeviceName: String = "",
    val manualDeviceNameError: String? = null,
    val manualDeviceNameWarning: String? = null,
    val manualBluetoothType: BluetoothType = BluetoothType.CLASSIC,
    val isPairingInProgress: Boolean = false,
    val pairingError: String? = null,
    val pairingTimeRemaining: Int = 0,
    val lastPairingDeviceAddress: String? = null,
    val isWaitingForReconnect: Boolean = false,
    val reconnectDeviceName: String? = null,
    // TCP/WiFi connection fields
    val tcpHost: String = "",
    val tcpPort: String = "7633",
    val isTcpValidating: Boolean = false,
    val tcpValidationSuccess: Boolean? = null,
    val tcpValidationError: String? = null,
    // USB Serial connection fields
    val usbDevices: List<com.lxmf.messenger.data.model.DiscoveredUsbDevice> = emptyList(),
    val selectedUsbDevice: com.lxmf.messenger.data.model.DiscoveredUsbDevice? = null,
    val isUsbScanning: Boolean = false,
    val usbScanError: String? = null,
    val isRequestingUsbPermission: Boolean = false,
    // Bluetooth pairing via USB mode
    val isUsbPairingMode: Boolean = false,
    val usbBluetoothPin: String? = null,
    val usbPairingStatus: String? = null,
    val showManualPinEntry: Boolean = false,
    val manualPinInput: String = "",
    // USB-assisted Bluetooth pairing (from Bluetooth tab)
    val isUsbAssistedPairingActive: Boolean = false,
    val usbAssistedPairingDevices: List<com.lxmf.messenger.data.model.DiscoveredRNode> = emptyList(),
    val usbAssistedPairingPin: String? = null,
    val usbAssistedPairingStatus: String? = null,
    // Companion Device Association (Android 12+)
    val isAssociating: Boolean = false,
    val pendingAssociationIntent: IntentSender? = null,
    val associationError: String? = null,
    // Step 2: Region/Frequency Selection
    val searchQuery: String = "",
    val selectedCountry: String? = null,
    // Legacy: popular local presets
    val selectedPreset: RNodeRegionalPreset? = null,
    // New: frequency band selection
    val selectedFrequencyRegion: FrequencyRegion? = null,
    val isCustomMode: Boolean = false,
    // Collapsible section for local presets
    val showPopularPresets: Boolean = false,
    // Step 3: Modem Preset Selection
    val selectedModemPreset: ModemPreset = ModemPreset.DEFAULT,
    // Step 4: Frequency Slot Selection
    // Default Meshtastic slot
    val selectedSlot: Int = 20,
    // Set when a preset is selected that doesn't align with slots
    val customFrequency: Long? = null,
    // The preset selected on slot page
    val selectedSlotPreset: RNodeRegionalPreset? = null,
    // Step 5: Review & Configure
    val interfaceName: String = DEFAULT_INTERFACE_NAME,
    // US default
    val frequency: String = "914875000",
    // Long Fast default
    val bandwidth: String = "250000",
    // Long Fast default
    val spreadingFactor: String = "11",
    // Long Fast default (4/5)
    val codingRate: String = "5",
    // Safe default for all devices
    val txPower: String = "17",
    val stAlock: String = "",
    val ltAlock: String = "",
    val interfaceMode: String = "boundary",
    val showAdvancedSettings: Boolean = false,
    // Display logo on RNode OLED
    val enableFramebuffer: Boolean = true,
    // Validation errors
    val nameError: String? = null,
    val frequencyError: String? = null,
    val bandwidthError: String? = null,
    val txPowerError: String? = null,
    val spreadingFactorError: String? = null,
    val codingRateError: String? = null,
    val stAlockError: String? = null,
    val ltAlockError: String? = null,
    // Regulatory warning
    val showRegulatoryWarning: Boolean = false,
    val regulatoryWarningMessage: String? = null,
    // Save state
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
    // Pending LoRa params from discovered interfaces (applied when reaching Review step)
    val pendingFrequency: Long? = null,
    val pendingBandwidth: Int? = null,
    val pendingSpreadingFactor: Int? = null,
    val pendingCodingRate: Int? = null,
    val hasPendingParams: Boolean = false,
    // Track if user skipped region selection (for back navigation)
    val skippedRegionSelection: Boolean = false,
) {
    /**
     * Returns the appropriate interface name for a device.
     * If user hasn't customized the name (still default), generates "RNode <identifier> <BLE|BT>".
     * Otherwise preserves the user's custom name.
     */
    fun defaultInterfaceNameFor(device: DiscoveredRNode): String =
        if (interfaceName == DEFAULT_INTERFACE_NAME) {
            val identifier = device.name.removePrefix("RNode ").trim().ifEmpty { device.name }
            val suffix = if (device.type == BluetoothType.BLE) "BLE" else "BT"
            "RNode $identifier $suffix"
        } else {
            interfaceName
        }
}

/**
 * ViewModel for the RNode setup wizard.
 */
@HiltViewModel
@Suppress("LargeClass")
class RNodeWizardViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val interfaceRepository: InterfaceRepository,
        private val configManager: InterfaceConfigManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "RNodeWizardVM"
            private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
            private const val SCAN_DURATION_MS = 10000L
            private const val PREFS_NAME = "rnode_device_types"
            private const val KEY_DEVICE_TYPES = "device_type_cache"
            private const val PAIRING_START_TIMEOUT_MS = 5_000L // 5s to start pairing
            private const val PIN_ENTRY_TIMEOUT_MS = 60_000L // 60s for user to enter PIN
            private const val RECONNECT_SCAN_TIMEOUT_MS = 15_000L // 15s to find device after reboot
            private const val RSSI_UPDATE_INTERVAL_MS = 3000L // Update RSSI every 3s
            private const val TCP_CONNECTION_TIMEOUT_MS = 5000 // 5 second TCP connection timeout

            // Test configuration flag - disable RSSI polling during tests
            internal var enableRssiPolling = true
        }

        private val _state = MutableStateFlow(RNodeWizardState())
        val state: StateFlow<RNodeWizardState> = _state.asStateFlow()

        private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

        // USB bridge (singleton from reticulum module)
        private val usbBridge by lazy {
            com.lxmf.messenger.reticulum.usb.KotlinUSBBridge.getInstance(context)
        }

        // RSSI update throttling - prevent excessive UI updates
        private val rssiThrottler = RssiThrottler(intervalMs = RSSI_UPDATE_INTERVAL_MS)

        // RSSI polling for connected RNode (edit mode)
        private var rssiPollingJob: Job? = null

        // Device type cache - persists detected BLE vs Classic types
        private val deviceTypePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Device classifier for determining BLE vs Classic device types
        private val deviceClassifier = DeviceClassifier(
            deviceTypeCache = object : DeviceTypeCache {
                override fun getCachedType(address: String): BluetoothType? {
                    val json = deviceTypePrefs.getString(KEY_DEVICE_TYPES, "{}") ?: "{}"
                    return try {
                        val jsonObj = org.json.JSONObject(json)
                        if (!jsonObj.has(address)) return null
                        when (jsonObj.optString(address)) {
                            "CLASSIC" -> BluetoothType.CLASSIC
                            "BLE" -> BluetoothType.BLE
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read device type cache", e)
                        null
                    }
                }

                override fun cacheType(address: String, type: BluetoothType) {
                    try {
                        val json = deviceTypePrefs.getString(KEY_DEVICE_TYPES, "{}") ?: "{}"
                        val obj = org.json.JSONObject(json)
                        obj.put(address, type.name)
                        deviceTypePrefs.edit().putString(KEY_DEVICE_TYPES, obj.toString()).apply()
                        Log.d(TAG, "Cached device type: $address -> $type")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cache device type", e)
                    }
                }
            },
        )

        // Track user-modified fields to preserve state during navigation
        // When user explicitly modifies a field, we don't overwrite it with region defaults
        private val userModifiedFields = mutableSetOf<String>()


        // ========== INITIALIZATION ==========

        /**
         * Initialize wizard for editing an existing RNode interface.
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod") // Complex config mapping is inherent
        fun loadExistingConfig(interfaceId: Long) {
            viewModelScope.launch {
                try {
                    val entity = interfaceRepository.getInterfaceById(interfaceId).first()
                    if (entity == null) {
                        Log.e(TAG, "Interface not found: $interfaceId")
                        return@launch
                    }

                    val config = interfaceRepository.entityToConfig(entity)
                    if (config !is InterfaceConfig.RNode) {
                        Log.e(TAG, "Interface is not RNode type: ${entity.type}")
                        return@launch
                    }

                    // Find matching preset if any
                    val matchingPreset =
                        RNodeRegionalPresets.findMatchingPreset(
                            config.frequency,
                            config.bandwidth,
                            config.spreadingFactor,
                        )

                    val isTcp = config.connectionMode == "tcp"
                    val isBle = config.connectionMode == "ble"
                    val isUsb = config.connectionMode == "usb"

                    // Determine connection type
                    val connectionType =
                        when {
                            isTcp -> RNodeConnectionType.TCP_WIFI
                            isUsb -> RNodeConnectionType.USB_SERIAL
                            else -> RNodeConnectionType.BLUETOOTH
                        }

                    // Create placeholder USB device for USB mode
                    // Capture nullable values in local variables for smart cast
                    val usbVendorId = config.usbVendorId
                    val usbProductId = config.usbProductId
                    val usbDevice =
                        if (isUsb && usbVendorId != null && usbProductId != null) {
                            com.lxmf.messenger.data.model.DiscoveredUsbDevice(
                                deviceId = config.usbDeviceId ?: 0,
                                vendorId = usbVendorId,
                                productId = usbProductId,
                                deviceName = "",
                                manufacturerName = null,
                                productName = "Configured USB RNode",
                                serialNumber = null,
                                driverType = "Unknown",
                                hasPermission = true, // Assume permission since it was previously configured
                            )
                        } else {
                            null
                        }

                    _state.update { state ->
                        state.copy(
                            editingInterfaceId = interfaceId,
                            isEditMode = true,
                            // Connection type
                            connectionType = connectionType,
                            // Pre-populate TCP fields (for TCP mode)
                            tcpHost = config.tcpHost ?: "",
                            tcpPort = config.tcpPort.toString(),
                            // Pre-populate USB device (for USB mode)
                            selectedUsbDevice = usbDevice,
                            // Pre-populate Bluetooth device (for Bluetooth mode)
                            selectedDevice =
                                if (isTcp || isUsb) {
                                    null
                                } else {
                                    DiscoveredRNode(
                                        name = config.targetDeviceName,
                                        address = "",
                                        type = if (isBle) BluetoothType.BLE else BluetoothType.CLASSIC,
                                        rssi = null,
                                        isPaired = true,
                                    )
                                },
                            // Pre-populate region
                            selectedPreset = matchingPreset,
                            selectedCountry = matchingPreset?.countryName,
                            isCustomMode = matchingPreset == null,
                            // Pre-populate settings
                            interfaceName = config.name,
                            frequency = config.frequency.toString(),
                            bandwidth = config.bandwidth.toString(),
                            spreadingFactor = config.spreadingFactor.toString(),
                            codingRate = config.codingRate.toString(),
                            txPower = config.txPower.toString(),
                            stAlock = config.stAlock?.toString() ?: "",
                            ltAlock = config.ltAlock?.toString() ?: "",
                            interfaceMode = config.mode,
                            enableFramebuffer = config.enableFramebuffer,
                        )
                    }

                    // Start RSSI polling for BLE devices (not TCP)
                    if (isBle && !isTcp) {
                        startRssiPolling()
                    }

                    Log.d(TAG, "Loaded existing config for interface $interfaceId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load existing config", e)
                }
            }
        }

        /**
         * Start polling RSSI from the active RNode BLE connection.
         * Updates the selected device's RSSI every 3 seconds.
         */
        private fun startRssiPolling() {
            rssiPollingJob?.cancel()
            if (!enableRssiPolling) {
                return  // Skip during tests
            }
            rssiPollingJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(RSSI_UPDATE_INTERVAL_MS)
                        val rssi = configManager.getRNodeRssi()
                        if (rssi > -100) {
                            _state.update { state ->
                                state.copy(
                                    selectedDevice = state.selectedDevice?.copy(rssi = rssi),
                                )
                            }
                            Log.v(TAG, "RNode RSSI updated: $rssi dBm")
                        }
                    }
                }
        }

        /**
         * Stop RSSI polling.
         */
        private fun stopRssiPolling() {
            rssiPollingJob?.cancel()
            rssiPollingJob = null
        }

        // ========== PENDING LORA PARAMS ==========

        /**
         * Set initial LoRa radio params from a discovered interface.
         * These will be applied when the user reaches the Review step.
         */
        fun setInitialRadioParams(
            frequency: Long?,
            bandwidth: Int?,
            spreadingFactor: Int?,
            codingRate: Int?,
        ) {
            _state.update { state ->
                state.copy(
                    pendingFrequency = frequency,
                    pendingBandwidth = bandwidth,
                    pendingSpreadingFactor = spreadingFactor,
                    pendingCodingRate = codingRate,
                    hasPendingParams = frequency != null || bandwidth != null ||
                        spreadingFactor != null || codingRate != null,
                )
            }
            Log.d(TAG, "Set pending LoRa params: freq=$frequency, bw=$bandwidth, sf=$spreadingFactor, cr=$codingRate")
        }

        /**
         * Apply pending LoRa params to the current configuration.
         * Called when entering the Review step to populate fields with discovered values.
         */
        fun applyPendingParams() {
            val currentState = _state.value
            if (!currentState.hasPendingParams) return

            _state.update { state ->
                state.copy(
                    frequency = state.pendingFrequency?.toString() ?: state.frequency,
                    bandwidth = state.pendingBandwidth?.toString() ?: state.bandwidth,
                    spreadingFactor = state.pendingSpreadingFactor?.toString() ?: state.spreadingFactor,
                    codingRate = state.pendingCodingRate?.toString() ?: state.codingRate,
                    // Clear pending params after applying
                    pendingFrequency = null,
                    pendingBandwidth = null,
                    pendingSpreadingFactor = null,
                    pendingCodingRate = null,
                    hasPendingParams = false,
                    // Skip region/modem/slot selection - go to custom mode since we have specific params
                    isCustomMode = true,
                    showAdvancedSettings = true,
                )
            }
            Log.d(TAG, "Applied pending LoRa params to configuration")
        }

        // ========== NAVIGATION ==========

        fun goToStep(step: WizardStep) {
            // Apply pending params when transitioning to Review step
            if (step == WizardStep.REVIEW_CONFIGURE) {
                applyPendingParams()
            }
            _state.update { it.copy(currentStep = step) }
        }

        fun goToNextStep() {
            val currentState = _state.value
            val nextStep =
                when (currentState.currentStep) {
                    WizardStep.DEVICE_DISCOVERY -> {
                        // Stop RSSI polling when leaving device discovery to prevent memory leaks
                        stopRssiPolling()
                        // If we have pending LoRa params from discovered interface, skip to review
                        if (currentState.hasPendingParams) {
                            _state.update { it.copy(skippedRegionSelection = true) }
                            WizardStep.REVIEW_CONFIGURE
                        } else {
                            WizardStep.REGION_SELECTION
                        }
                    }
                    WizardStep.REGION_SELECTION -> {
                        if (currentState.isCustomMode) {
                            // Custom mode: skip modem and slot selection, go straight to review
                            // Also expand advanced settings by default
                            _state.update { it.copy(showAdvancedSettings = true) }
                            WizardStep.REVIEW_CONFIGURE
                        } else if (currentState.selectedPreset != null) {
                            // Popular local preset: skip modem and slot, go straight to review
                            // Preset already contains all modem/frequency settings
                            // Also expand advanced settings so user can see the configured values
                            _state.update { it.copy(showAdvancedSettings = true) }
                            WizardStep.REVIEW_CONFIGURE
                        } else {
                            // Apply frequency region settings when moving to modem step
                            applyFrequencyRegionSettings()
                            WizardStep.MODEM_PRESET
                        }
                    }
                    WizardStep.MODEM_PRESET -> {
                        // Apply modem preset settings when moving to slot step
                        applyModemPresetSettings()
                        // Initialize slot to default for this region/bandwidth
                        initializeDefaultSlot()
                        WizardStep.FREQUENCY_SLOT
                    }
                    WizardStep.FREQUENCY_SLOT -> {
                        // Apply slot to frequency when moving to review
                        applySlotToFrequency()
                        WizardStep.REVIEW_CONFIGURE
                    }
                    WizardStep.REVIEW_CONFIGURE -> WizardStep.REVIEW_CONFIGURE // Already at end
                }
            // Apply pending params when reaching Review step
            if (nextStep == WizardStep.REVIEW_CONFIGURE) {
                applyPendingParams()
            }
            _state.update { it.copy(currentStep = nextStep) }
        }

        fun goToPreviousStep() {
            val currentState = _state.value
            val prevStep =
                when (currentState.currentStep) {
                    WizardStep.DEVICE_DISCOVERY -> WizardStep.DEVICE_DISCOVERY // Already at start
                    WizardStep.REGION_SELECTION -> WizardStep.DEVICE_DISCOVERY
                    WizardStep.MODEM_PRESET -> WizardStep.REGION_SELECTION
                    WizardStep.FREQUENCY_SLOT -> WizardStep.MODEM_PRESET
                    WizardStep.REVIEW_CONFIGURE ->
                        if (currentState.skippedRegionSelection) {
                            // Came from discovered interface params: go back to device selection
                            WizardStep.DEVICE_DISCOVERY
                        } else if (currentState.isCustomMode || currentState.selectedPreset != null) {
                            // Custom mode or preset: go back to region selection (skipping modem and slot)
                            WizardStep.REGION_SELECTION
                        } else {
                            WizardStep.FREQUENCY_SLOT
                        }
                }
            // Reset skippedRegionSelection flag when going back to device discovery
            if (prevStep == WizardStep.DEVICE_DISCOVERY && currentState.skippedRegionSelection) {
                _state.update { it.copy(currentStep = prevStep, skippedRegionSelection = false) }
            } else {
                _state.update { it.copy(currentStep = prevStep) }
            }
        }

        fun canProceed(): Boolean {
            val state = _state.value
            return when (state.currentStep) {
                WizardStep.DEVICE_DISCOVERY ->
                    when (state.connectionType) {
                        RNodeConnectionType.TCP_WIFI ->
                            state.tcpHost.isNotBlank()
                        RNodeConnectionType.BLUETOOTH ->
                            state.selectedDevice != null ||
                                (
                                    state.showManualEntry &&
                                        state.manualDeviceName.isNotBlank() &&
                                        state.manualDeviceNameError == null
                                )
                        RNodeConnectionType.USB_SERIAL ->
                            state.selectedUsbDevice != null && state.selectedUsbDevice.hasPermission
                    }
                WizardStep.REGION_SELECTION ->
                    state.selectedFrequencyRegion != null || state.isCustomMode || state.selectedPreset != null
                WizardStep.MODEM_PRESET ->
                    true // Default preset is pre-selected, user can always proceed
                WizardStep.FREQUENCY_SLOT ->
                    true // Slot always has a valid selection
                WizardStep.REVIEW_CONFIGURE ->
                    state.interfaceName.isNotBlank() && validateConfigurationSilent()
            }
        }

        private fun applyFrequencyRegionSettings() {
            val region = _state.value.selectedFrequencyRegion ?: return

            // Apply duty cycle as airtime limits if the region has restrictions
            // stAlock = short-term airtime lock, ltAlock = long-term airtime lock
            val airtimeLimit =
                if (region.dutyCycle < 100) {
                    region.dutyCycle.toDouble().toString()
                } else {
                    "" // No limit
                }

            // Only apply defaults for fields that the user hasn't explicitly modified
            // This preserves user changes and validation errors during navigation
            _state.update {
                it.copy(
                    frequency = if ("frequency" !in userModifiedFields) region.frequency.toString() else it.frequency,
                    frequencyError = if ("frequency" !in userModifiedFields) null else it.frequencyError,
                    txPower = if ("txPower" !in userModifiedFields) region.defaultTxPower.toString() else it.txPower,
                    txPowerError = if ("txPower" !in userModifiedFields) null else it.txPowerError,
                    stAlock = if ("stAlock" !in userModifiedFields) airtimeLimit else it.stAlock,
                    stAlockError = if ("stAlock" !in userModifiedFields) null else it.stAlockError,
                    ltAlock = if ("ltAlock" !in userModifiedFields) airtimeLimit else it.ltAlock,
                    ltAlockError = if ("ltAlock" !in userModifiedFields) null else it.ltAlockError,
                )
            }
        }

        private fun applyModemPresetSettings() {
            val preset = _state.value.selectedModemPreset
            _state.update {
                it.copy(
                    bandwidth = preset.bandwidth.toString(),
                    spreadingFactor = preset.spreadingFactor.toString(),
                    codingRate = preset.codingRate.toString(),
                )
            }
        }

        private fun initializeDefaultSlot() {
            val region = _state.value.selectedFrequencyRegion ?: return
            val bandwidth = _state.value.selectedModemPreset.bandwidth
            val defaultSlot = FrequencySlotCalculator.getDefaultSlot(region, bandwidth)
            _state.update { it.copy(selectedSlot = defaultSlot) }
        }

        private fun applySlotToFrequency() {
            val state = _state.value
            val region = state.selectedFrequencyRegion ?: return

            // Use custom frequency if a preset was selected, otherwise calculate from slot
            val frequency =
                state.customFrequency ?: run {
                    val bandwidth = state.selectedModemPreset.bandwidth
                    FrequencySlotCalculator.calculateFrequency(region, bandwidth, state.selectedSlot)
                }
            _state.update { it.copy(frequency = frequency.toString(), frequencyError = null) }
        }

        // ========== STEP 4: FREQUENCY SLOT SELECTION ==========

        /**
         * Get the number of available frequency slots for the current region/bandwidth.
         */
        fun getNumSlots(): Int {
            val region = _state.value.selectedFrequencyRegion ?: return 0
            val bandwidth = _state.value.selectedModemPreset.bandwidth
            return FrequencySlotCalculator.getNumSlots(region, bandwidth)
        }

        /**
         * Calculate the frequency for a given slot.
         */
        fun getFrequencyForSlot(slot: Int): Long {
            val region = _state.value.selectedFrequencyRegion ?: return 0
            val bandwidth = _state.value.selectedModemPreset.bandwidth
            return FrequencySlotCalculator.calculateFrequency(region, bandwidth, slot)
        }

        /**
         * Get community slots for the current region.
         */
        fun getCommunitySlots(): List<com.lxmf.messenger.data.model.CommunitySlot> {
            val region = _state.value.selectedFrequencyRegion ?: return emptyList()
            return CommunitySlots.forRegion(region.id)
        }

        /**
         * Select a frequency slot.
         * Clears any custom frequency selection.
         */
        fun selectSlot(slot: Int) {
            val numSlots = getNumSlots()
            if (slot in 0 until numSlots) {
                _state.update {
                    it.copy(
                        selectedSlot = slot,
                        customFrequency = null,
                        selectedSlotPreset = null,
                    )
                }
            }
        }

        /**
         * Select a preset frequency directly (bypassing slot calculation).
         * Used when a preset's frequency doesn't align with slot boundaries.
         */
        fun selectPresetFrequency(preset: RNodeRegionalPreset) {
            _state.update {
                it.copy(
                    customFrequency = preset.frequency,
                    selectedSlotPreset = preset,
                )
            }
        }

        /**
         * Get popular RNode presets for the current region.
         * These are community-tested configurations that can be used as slot suggestions.
         */
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun getPopularPresetsForRegion(): List<RNodeRegionalPreset> {
            val region = _state.value.selectedFrequencyRegion ?: return emptyList()

            // For 433 MHz bands, filter specifically for 433 MHz presets
            if (region.id == "eu_433") {
                return RNodeRegionalPresets.presets
                    .filter { it.frequency in 430_000_000..440_000_000 }
                    .take(5)
            }

            // For 2.4 GHz, filter for 2.4 GHz presets
            if (region.id == "lora_24") {
                return RNodeRegionalPresets.presets
                    .filter { it.frequency in 2_400_000_000..2_500_000_000 }
                    .take(5)
            }

            // EU 868 sub-bands: Filter presets by frequency within the sub-band range
            when (region.id) {
                // Sub-band L: 865-868 MHz (UK, Italy Brescia/Treviso, Netherlands Rotterdam, Belgium Duffel)
                "eu_868_l" -> {
                    return RNodeRegionalPresets.presets
                        .filter { it.frequency in 865_000_000..867_999_999 }
                        .take(5)
                }
                // Sub-band M: 868-868.6 MHz (Spain Madrid, Switzerland Bern, Belgium/Germany/etc defaults)
                "eu_868_m" -> {
                    return RNodeRegionalPresets.presets
                        .filter { it.frequency in 868_000_000..868_599_999 }
                        .take(5)
                }
                // Sub-band P: 869.4-869.65 MHz (Germany Darmstadt/Wiesbaden, Italy Salerno)
                "eu_868_p" -> {
                    return RNodeRegionalPresets.presets
                        .filter { it.frequency in 869_400_000..869_650_000 }
                        .take(5)
                }
                // Sub-band Q: 869.7-870 MHz
                "eu_868_q" -> {
                    return RNodeRegionalPresets.presets
                        .filter { it.frequency in 869_700_000..869_999_999 }
                        .take(5)
                }
            }

            // Map region IDs to country codes for presets in the same frequency band
            val countryCodes =
                when (region.id) {
                    // US 915 MHz
                    "us_915" -> listOf("US")

                    // Brazil 902-907.5 MHz - no presets defined (US presets are at 914 MHz)
                    "br_902" -> return emptyList()

                    // Russia 868.7-869.2 MHz - no presets defined (narrow band, no matching EU presets)
                    "ru_868" -> return emptyList()

                    // Ukraine 868-868.6 MHz - no presets defined
                    "ua_868" -> return emptyList()

                    // Australia 915 MHz
                    "au_915" -> listOf("AU")

                    // NZ 865 MHz - no presets defined (different band from AU)
                    "nz_865" -> emptyList()

                    // Japan 920.8-927.8 MHz - no presets defined (AS923 presets at 920.5 MHz are outside range)
                    "jp_920" -> return emptyList()

                    // Asia-Pacific 920 MHz bands (AS923)
                    "kr_920", "tw_920", "th_920", "sg_923", "my_919" ->
                        listOf("MY", "SG", "TH")

                    // Philippines 915-918 MHz - no presets defined (different band from AS923)
                    "ph_915" -> emptyList()

                    else -> emptyList()
                }

            // Filter presets by country and exclude 433 MHz / 2.4 GHz presets from non-matching regions
            return RNodeRegionalPresets.presets
                .filter { it.countryCode in countryCodes }
                .filter { it.frequency !in 430_000_000..440_000_000 } // Exclude 433 MHz
                .filter { it.frequency !in 2_400_000_000..2_500_000_000 } // Exclude 2.4 GHz
                .take(5)
        }

        // ========== STEP 1: DEVICE DISCOVERY ==========

        @SuppressLint("MissingPermission")
        fun startDeviceScan() {
            // Check for Bluetooth availability early before starting scan
            if (bluetoothAdapter == null) {
                setScanError("Bluetooth not available on this device")
                return
            }

            viewModelScope.launch {
                _state.update {
                    it.copy(
                        isScanning = true,
                        scanError = null,
                        discoveredDevices = emptyList(),
                    )
                }

                val bleDeviceAddresses = mutableSetOf<String>()
                val devices = mutableMapOf<String, DiscoveredRNode>()

                // 1. BLE scan FIRST - this definitively identifies BLE devices
                performBleScan(bleDeviceAddresses, devices)

                // 2. Check bonded devices - classify based on BLE scan results and cache
                addBondedRNodes(bleDeviceAddresses, devices)

                // 3. Update selectedDevice if found during scan (for edit mode)
                val updatedSelected = updateSelectedFromScan(devices.values)

                // 4. Finalize scan state
                finalizeScan(devices.values.toList(), updatedSelected)
            }
        }

        /**
         * Perform BLE scan for RNode devices.
         * RNodes use EITHER Classic OR BLE, never both (determined by board type).
         */
        @SuppressLint("MissingPermission")
        private suspend fun performBleScan(
            bleDeviceAddresses: MutableSet<String>,
            devices: MutableMap<String, DiscoveredRNode>,
        ) {
            try {
                scanForBleRNodes { bleDevice ->
                    bleDeviceAddresses.add(bleDevice.address)
                    devices[bleDevice.address] = bleDevice
                    deviceClassifier.cacheDeviceType(bleDevice.address, BluetoothType.BLE)
                    _state.update { it.copy(discoveredDevices = devices.values.toList()) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLE scan failed", e)
            }
        }

        /**
         * Add bonded RNode devices, classifying them based on BLE scan results and cache.
         */
        @SuppressLint("MissingPermission")
        private fun addBondedRNodes(
            bleDeviceAddresses: Set<String>,
            devices: MutableMap<String, DiscoveredRNode>,
        ) {
            try {
                bluetoothAdapter?.bondedDevices?.forEach { device ->
                    if (deviceClassifier.shouldIncludeInDiscovery(device)) {
                        classifyBondedDevice(device, bleDeviceAddresses, devices)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing permission for bonded devices check", e)
            }
        }

        /**
         * Classify a bonded device as BLE or Classic based on scan results and cache.
         */
        @SuppressLint("MissingPermission")
        private fun classifyBondedDevice(
            device: BluetoothDevice,
            bleDeviceAddresses: Set<String>,
            devices: MutableMap<String, DiscoveredRNode>,
        ) {
            val address = device.address
            val name = device.name ?: address

            when (val result = deviceClassifier.classifyDevice(device, bleDeviceAddresses)) {
                is DeviceClassifier.ClassificationResult.ConfirmedBle -> {
                    // Found in BLE scan - update paired status
                    devices[address]?.let { existing ->
                        devices[address] = existing.copy(isPaired = true)
                    }
                }
                is DeviceClassifier.ClassificationResult.Cached -> {
                    // Use cached type
                    devices[address] =
                        DiscoveredRNode(
                            name = name,
                            address = address,
                            type = result.type,
                            rssi = null,
                            isPaired = true,
                            bluetoothDevice = device,
                        )
                    Log.d(TAG, "Using cached type for $name: ${result.type}")
                }
                is DeviceClassifier.ClassificationResult.Unknown -> {
                    // Mark as unknown
                    devices[address] =
                        DiscoveredRNode(
                            name = name,
                            address = address,
                            type = BluetoothType.UNKNOWN,
                            rssi = null,
                            isPaired = true,
                            bluetoothDevice = device,
                        )
                    Log.d(TAG, "Unknown type for bonded device $name (no cache)")
                }
            }
        }

        /**
         * Update selected device from scan results.
         * Handles edit mode where device was loaded from config without RSSI/address.
         */
        private fun updateSelectedFromScan(devices: Collection<DiscoveredRNode>): DiscoveredRNode? {
            val currentSelected = _state.value.selectedDevice ?: return null
            return devices.find { it.name == currentSelected.name }?.also { foundDevice ->
                Log.d(TAG, "Updating selected device from scan: rssi=${foundDevice.rssi}")
            } ?: currentSelected
        }

        /**
         * Finalize scan by updating state with results.
         */
        private fun finalizeScan(
            devices: List<DiscoveredRNode>,
            selectedDevice: DiscoveredRNode?,
        ) {
            _state.update {
                it.copy(
                    discoveredDevices = devices,
                    isScanning = false,
                    selectedDevice = selectedDevice,
                )
            }

            if (devices.isEmpty()) {
                _state.update {
                    it.copy(
                        scanError =
                            "No RNode devices found. " +
                                "Make sure your RNode is powered on and Bluetooth is enabled.",
                    )
                }
            }
        }

        /**
         * Set the Bluetooth type for a device (user manual selection).
         * This caches the selection for future scans.
         */
        fun setDeviceType(
            device: DiscoveredRNode,
            type: BluetoothType,
        ) {
            deviceClassifier.cacheDeviceType(device.address, type)
            val updatedDevice = device.copy(type = type)
            _state.update { state ->
                val isSelected = state.selectedDevice?.address == device.address
                val newSelected = if (isSelected) updatedDevice else state.selectedDevice
                state.copy(
                    discoveredDevices =
                        state.discoveredDevices.map {
                            if (it.address == device.address) updatedDevice else it
                        },
                    selectedDevice = newSelected,
                    // Update interface name suffix if selected device type changed
                    interfaceName = if (isSelected) state.defaultInterfaceNameFor(updatedDevice) else state.interfaceName,
                )
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun scanForBleRNodes(onDeviceFound: (DiscoveredRNode) -> Unit) {
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

            val filter =
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                    .build()

            val settings =
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

            val foundDevices = mutableSetOf<String>()

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val name = result.device.name ?: return
                        if (!name.startsWith("RNode ")) return

                        val address = result.device.address
                        if (foundDevices.contains(address)) {
                            // Update RSSI for existing device (throttled to every 3s)
                            updateDeviceRssi(address, result.rssi)
                        } else {
                            // New device - add to list
                            foundDevices.add(address)
                            onDeviceFound(
                                DiscoveredRNode(
                                    name = name,
                                    address = address,
                                    type = BluetoothType.BLE,
                                    rssi = result.rssi,
                                    isPaired = result.device.bondState == BluetoothDevice.BOND_BONDED,
                                    bluetoothDevice = result.device,
                                ),
                            )
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.e(TAG, "BLE scan failed: $errorCode")
                        val errorMessage =
                            when (errorCode) {
                                1 -> "BLE scan failed: already started"
                                2 -> "BLE scan failed: app not registered"
                                3 -> "BLE scan failed: internal error"
                                4 -> "BLE scan failed: feature unsupported"
                                5 -> "BLE scan failed: out of hardware resources"
                                6 -> "BLE scan failed: scanning too frequently"
                                else -> "BLE scan failed: error code $errorCode"
                            }
                        setScanError(errorMessage)
                    }
                }

            try {
                scanner.startScan(listOf(filter), settings, callback)
                delay(SCAN_DURATION_MS)
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "BLE scan permission denied", e)
                setScanError("Bluetooth permission required. Please grant Bluetooth permissions in Settings.")
            }
        }

        /**
         * Set a scan error message and stop scanning.
         */
        private fun setScanError(message: String) {
            _state.update {
                it.copy(
                    scanError = message,
                    isScanning = false,
                )
            }
        }

        /**
         * Scan for a specific BLE device by name with a timeout.
         * Used to detect when a device comes back online after a reboot.
         *
         * @param deviceName The name of the device to find
         * @param deviceType The expected Bluetooth type (for returned DiscoveredRNode)
         * @return The discovered device if found within timeout, null otherwise
         */
        @SuppressLint("MissingPermission")
        private suspend fun scanForDeviceByName(
            deviceName: String,
            deviceType: BluetoothType,
        ): DiscoveredRNode? {
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return null

            val filter =
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                    .build()

            val settings =
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

            var foundDevice: DiscoveredRNode? = null

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val name = result.device.name ?: return
                        if (name == deviceName) {
                            foundDevice =
                                DiscoveredRNode(
                                    name = name,
                                    address = result.device.address,
                                    type = deviceType,
                                    rssi = result.rssi,
                                    isPaired = result.device.bondState == BluetoothDevice.BOND_BONDED,
                                    bluetoothDevice = result.device,
                                )
                            Log.d(TAG, "Found device during reconnect scan: $name")
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.e(TAG, "Reconnect scan failed: $errorCode")
                    }
                }

            try {
                scanner.startScan(listOf(filter), settings, callback)

                // Poll for device with timeout
                val startTime = System.currentTimeMillis()
                while (foundDevice == null &&
                    System.currentTimeMillis() - startTime < RECONNECT_SCAN_TIMEOUT_MS
                ) {
                    // Check if scan was cancelled
                    if (!_state.value.isWaitingForReconnect) {
                        Log.d(TAG, "Reconnect scan cancelled by user")
                        break
                    }
                    delay(200)
                }

                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "Reconnect scan permission denied", e)
            }

            return foundDevice
        }

        /**
         * Cancel the ongoing reconnect scan.
         * Called when user wants to stop waiting for the device.
         */
        fun cancelReconnectScan() {
            Log.d(TAG, "User cancelled reconnect scan")
            _state.update {
                it.copy(
                    isWaitingForReconnect = false,
                    reconnectDeviceName = null,
                    isPairingInProgress = false,
                )
            }
        }

        fun selectDevice(device: DiscoveredRNode) {
            _state.update {
                it.copy(
                    selectedDevice = device,
                    showManualEntry = false,
                    interfaceName = it.defaultInterfaceNameFor(device),
                )
            }
        }

        /**
         * Update RSSI for an already-discovered device (throttled to every 3 seconds).
         */
        private fun updateDeviceRssi(
            address: String,
            rssi: Int,
        ) {
            if (!rssiThrottler.shouldUpdate(address)) return

            _state.update { state ->
                // Update RSSI in discovered devices list
                val updatedDevices =
                    state.discoveredDevices.map { device ->
                        if (device.address == address) device.copy(rssi = rssi) else device
                    }
                // Also update selectedDevice if it matches
                val updatedSelected =
                    state.selectedDevice?.let { selected ->
                        if (selected.address == address) selected.copy(rssi = rssi) else selected
                    }
                state.copy(
                    discoveredDevices = updatedDevices,
                    selectedDevice = updatedSelected,
                )
            }
        }

        // ========== COMPANION DEVICE ASSOCIATION (Android 12+) ==========

        private val companionDeviceManager: CompanionDeviceManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
            } else {
                null
            }

        /**
         * Check if CompanionDeviceManager is available.
         */
        fun isCompanionDeviceAvailable(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && companionDeviceManager != null
        }

        /**
         * Request device association via CompanionDeviceManager.
         * On Android 12+, this shows a native system picker for the device.
         *
         * @param device The device to associate
         * @param onFallback Called if CDM is not available (pre-Android 12)
         */
        @SuppressLint("MissingPermission")
        fun requestDeviceAssociation(
            device: DiscoveredRNode,
            onFallback: () -> Unit,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || companionDeviceManager == null) {
                // Fall back to direct selection on older Android
                onFallback()
                return
            }

            Log.d(TAG, "Requesting CDM association for ${device.name} (${device.type})")
            _state.update { it.copy(isAssociating = true, associationError = null) }

            try {
                val request = buildAssociationRequest(device)

                companionDeviceManager.associate(
                    request,
                    object : CompanionDeviceManager.Callback() {
                        override fun onAssociationPending(intentSender: IntentSender) {
                            Log.d(TAG, "Association pending - providing IntentSender to UI")
                            _state.update {
                                it.copy(
                                    pendingAssociationIntent = intentSender,
                                    isAssociating = true,
                                )
                            }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onDeviceFound(intentSender: IntentSender) {
                            // Legacy callback for older API - same handling
                            Log.d(TAG, "Device found (legacy) - providing IntentSender to UI")
                            _state.update {
                                it.copy(
                                    pendingAssociationIntent = intentSender,
                                    isAssociating = true,
                                )
                            }
                        }

                        override fun onAssociationCreated(associationInfo: AssociationInfo) {
                            Log.d(TAG, "Association created: ${associationInfo.id}")

                            // Start observing device presence so Android binds our
                            // RNodeCompanionService when the device connects
                            try {
                                companionDeviceManager?.startObservingDevicePresence(
                                    associationInfo.deviceMacAddress?.toString()
                                        ?: device.address,
                                )
                                Log.d(TAG, "Started observing device presence")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to start observing device presence", e)
                            }

                            // Device is now associated - select it and update interface name
                            _state.update {
                                it.copy(
                                    selectedDevice = device,
                                    isAssociating = false,
                                    pendingAssociationIntent = null,
                                    showManualEntry = false,
                                    interfaceName = it.defaultInterfaceNameFor(device),
                                )
                            }
                            // Cache the device type since it's now confirmed
                            deviceClassifier.cacheDeviceType(device.address, device.type)
                        }

                        override fun onFailure(error: CharSequence?) {
                            Log.e(TAG, "CDM association failed: $error")
                            _state.update {
                                it.copy(
                                    isAssociating = false,
                                    pendingAssociationIntent = null,
                                    associationError = error?.toString() ?: "Association failed",
                                )
                            }
                        }
                    },
                    // Handler - use main thread
                    null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CDM association", e)
                _state.update {
                    it.copy(
                        isAssociating = false,
                        associationError = e.message ?: "Failed to start association",
                    )
                }
            }
        }

        /**
         * Build an AssociationRequest for the given device.
         */
        @SuppressLint("NewApi")
        private fun buildAssociationRequest(device: DiscoveredRNode): AssociationRequest {
            val builder = AssociationRequest.Builder()
            val escapedName = Pattern.quote(device.name)

            if (device.type == BluetoothType.BLE) {
                // BLE device filter with NUS service UUID
                val bleFilter =
                    BluetoothLeDeviceFilter.Builder()
                        .setNamePattern(Pattern.compile(escapedName))
                        .setScanFilter(
                            ScanFilter.Builder()
                                .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                                .build(),
                        )
                        .build()
                builder.addDeviceFilter(bleFilter)
            } else {
                // Classic Bluetooth device filter
                val classicFilter =
                    BluetoothDeviceFilter.Builder()
                        .setNamePattern(Pattern.compile(escapedName))
                        .build()
                builder.addDeviceFilter(classicFilter)
            }

            // Request single device since we're filtering for a specific device name
            builder.setSingleDevice(true)

            return builder.build()
        }

        /**
         * Called when the UI has launched the association IntentSender.
         */
        fun onAssociationIntentLaunched() {
            _state.update { it.copy(pendingAssociationIntent = null) }
        }

        /**
         * Called when the user cancels the association picker.
         */
        fun onAssociationCancelled() {
            Log.d(TAG, "User cancelled association")
            _state.update {
                it.copy(
                    isAssociating = false,
                    pendingAssociationIntent = null,
                )
            }
        }

        /**
         * Clear association error.
         */
        fun clearAssociationError() {
            _state.update { it.copy(associationError = null) }
        }

        fun showManualEntry() {
            _state.update {
                it.copy(
                    showManualEntry = true,
                    selectedDevice = null,
                )
            }
        }

        fun hideManualEntry() {
            _state.update {
                it.copy(
                    showManualEntry = false,
                )
            }
        }

        fun updateManualDeviceName(name: String) {
            val (error, warning) = validateManualDeviceName(name)
            _state.update {
                it.copy(
                    manualDeviceName = name,
                    manualDeviceNameError = error,
                    manualDeviceNameWarning = warning,
                )
            }
        }

        /**
         * Validates the manual device name entry.
         * @return Pair of (error, warning) - error prevents proceeding, warning is informational
         */
        private fun validateManualDeviceName(name: String): Pair<String?, String?> {
            return when (val result = DeviceNameValidator.validate(name)) {
                is DeviceNameValidator.ValidationResult.Valid -> null to null
                is DeviceNameValidator.ValidationResult.Error -> result.message to null
                is DeviceNameValidator.ValidationResult.Warning -> null to result.message
            }
        }

        fun updateManualBluetoothType(type: BluetoothType) {
            _state.update { it.copy(manualBluetoothType = type) }
        }

        // ========== TCP/WiFi Connection Methods ==========

        /**
         * Set the connection type (Bluetooth or TCP/WiFi).
         * Clears device selection when switching modes.
         */
        fun setConnectionType(type: RNodeConnectionType) {
            _state.update {
                // Auto-generate interface name based on connection type if not manually customized
                // Update if it's the default or matches an auto-generated pattern
                val isAutoGeneratedName = it.interfaceName == DEFAULT_INTERFACE_NAME ||
                    it.interfaceName == "RNode TCP" ||
                    it.interfaceName == "RNode USB" ||
                    it.interfaceName.matches(Regex("RNode .+ (BLE|BT)"))

                val newInterfaceName =
                    if (isAutoGeneratedName) {
                        when (type) {
                            RNodeConnectionType.TCP_WIFI -> "RNode TCP"
                            RNodeConnectionType.USB_SERIAL -> "RNode USB"
                            RNodeConnectionType.BLUETOOTH -> DEFAULT_INTERFACE_NAME // Updated when device selected
                        }
                    } else {
                        it.interfaceName
                    }

                it.copy(
                    connectionType = type,
                    interfaceName = newInterfaceName,
                    // Clear Bluetooth selection when switching away from Bluetooth
                    selectedDevice = if (type != RNodeConnectionType.BLUETOOTH) null else it.selectedDevice,
                    // Clear TCP validation when switching away from TCP
                    tcpValidationSuccess = if (type != RNodeConnectionType.TCP_WIFI) null else it.tcpValidationSuccess,
                    tcpValidationError = if (type != RNodeConnectionType.TCP_WIFI) null else it.tcpValidationError,
                    // Clear USB selection when switching away from USB
                    selectedUsbDevice = if (type != RNodeConnectionType.USB_SERIAL) null else it.selectedUsbDevice,
                    usbScanError = if (type != RNodeConnectionType.USB_SERIAL) null else it.usbScanError,
                    usbBluetoothPin = if (type != RNodeConnectionType.USB_SERIAL) null else it.usbBluetoothPin,
                    isUsbPairingMode = if (type != RNodeConnectionType.USB_SERIAL) false else it.isUsbPairingMode,
                )
            }

            // Auto-scan USB devices when switching to USB mode
            if (type == RNodeConnectionType.USB_SERIAL) {
                scanUsbDevices()
            }
        }

        /**
         * Update the TCP host (IP address or hostname).
         * Clears any previous validation result.
         */
        fun updateTcpHost(host: String) {
            _state.update {
                it.copy(
                    tcpHost = host,
                    tcpValidationSuccess = null,
                    tcpValidationError = null,
                )
            }
        }

        /**
         * Update the TCP port.
         * Clears any previous validation result.
         */
        fun updateTcpPort(port: String) {
            _state.update {
                it.copy(
                    tcpPort = port,
                    tcpValidationSuccess = null,
                    tcpValidationError = null,
                )
            }
        }

        /**
         * Validate the TCP connection by attempting to connect to the RNode.
         * Uses a 5-second timeout for the connection attempt.
         */
        fun validateTcpConnection() {
            val state = _state.value
            val host = state.tcpHost.trim()
            val port = state.tcpPort.toIntOrNull() ?: 7633

            if (host.isBlank()) {
                _state.update {
                    it.copy(
                        tcpValidationSuccess = false,
                        tcpValidationError = "Host cannot be empty",
                    )
                }
                return
            }

            if (port !in 1..65535) {
                _state.update {
                    it.copy(
                        tcpValidationSuccess = false,
                        tcpValidationError = "Port must be between 1 and 65535",
                    )
                }
                return
            }

            viewModelScope.launch {
                _state.update { it.copy(isTcpValidating = true, tcpValidationError = null) }

                try {
                    val success =
                        withContext(Dispatchers.IO) {
                            try {
                                Socket().use { socket ->
                                    socket.connect(InetSocketAddress(host, port), TCP_CONNECTION_TIMEOUT_MS)
                                    true
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "TCP connection test failed: ${e.message}")
                                false
                            }
                        }

                    _state.update {
                        it.copy(
                            isTcpValidating = false,
                            tcpValidationSuccess = success,
                            tcpValidationError = if (!success) "Could not connect to $host:$port" else null,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TCP validation error", e)
                    _state.update {
                        it.copy(
                            isTcpValidating = false,
                            tcpValidationSuccess = false,
                            tcpValidationError = e.message ?: "Connection failed",
                        )
                    }
                }
            }
        }

        // =========================================================================
        // USB Serial Methods
        // =========================================================================

        /**
         * Scan for connected USB serial devices.
         */
        fun scanUsbDevices() {
            viewModelScope.launch {
                _state.update { it.copy(isUsbScanning = true, usbScanError = null) }

                try {
                    val devices =
                        withContext(Dispatchers.IO) {
                            usbBridge.getConnectedUsbDevices()
                        }

                    val usbDevices =
                        devices.map { device ->
                            com.lxmf.messenger.data.model.DiscoveredUsbDevice(
                                deviceId = device.deviceId,
                                vendorId = device.vendorId,
                                productId = device.productId,
                                deviceName = device.deviceName,
                                manufacturerName = device.manufacturerName,
                                productName = device.productName,
                                serialNumber = device.serialNumber,
                                driverType = device.driverType,
                                hasPermission = usbBridge.hasPermission(device.deviceId),
                            )
                        }

                    _state.update {
                        it.copy(
                            isUsbScanning = false,
                            usbDevices = usbDevices,
                            usbScanError = if (usbDevices.isEmpty()) "No USB serial devices found" else null,
                        )
                    }

                    Log.d(TAG, "Found ${usbDevices.size} USB serial device(s)")
                } catch (e: Exception) {
                    Log.e(TAG, "USB scan failed", e)
                    _state.update {
                        it.copy(
                            isUsbScanning = false,
                            usbScanError = "Failed to scan USB devices: ${e.message}",
                        )
                    }
                }
            }
        }

        /**
         * Select a USB device.
         * If permission is needed, requests it first.
         */
        fun selectUsbDevice(device: com.lxmf.messenger.data.model.DiscoveredUsbDevice) {
            if (device.hasPermission) {
                _state.update {
                    // Auto-generate interface name if user hasn't customized it
                    val newInterfaceName =
                        if (it.interfaceName == DEFAULT_INTERFACE_NAME) {
                            "RNode USB"
                        } else {
                            it.interfaceName
                        }
                    it.copy(
                        selectedUsbDevice = device,
                        interfaceName = newInterfaceName,
                    )
                }
            } else {
                requestUsbPermission(device)
            }
        }

        /**
         * Request USB permission for a device.
         */
        fun requestUsbPermission(device: com.lxmf.messenger.data.model.DiscoveredUsbDevice) {
            _state.update { it.copy(isRequestingUsbPermission = true) }

            usbBridge.requestPermission(device.deviceId) { granted ->
                viewModelScope.launch {
                    if (granted) {
                        Log.d(TAG, "USB permission granted for device ${device.deviceId}")
                        // Update device list to reflect new permission status
                        val updatedDevices =
                            _state.value.usbDevices.map {
                                if (it.deviceId == device.deviceId) it.copy(hasPermission = true) else it
                            }
                        val updatedDevice = device.copy(hasPermission = true)

                        _state.update {
                            // Auto-generate interface name if user hasn't customized it
                            val newInterfaceName =
                                if (it.interfaceName == DEFAULT_INTERFACE_NAME) {
                                    "RNode USB"
                                } else {
                                    it.interfaceName
                                }
                            it.copy(
                                isRequestingUsbPermission = false,
                                usbDevices = updatedDevices,
                                selectedUsbDevice = updatedDevice,
                                interfaceName = newInterfaceName,
                            )
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for device ${device.deviceId}")
                        _state.update {
                            it.copy(
                                isRequestingUsbPermission = false,
                                usbScanError = "USB permission denied. Please grant permission to use this device.",
                            )
                        }
                    }
                }
            }
        }

        /**
         * Pre-select a USB device from USB intent handling.
         * This is called when a USB device is plugged in and the app is opened.
         */
        fun preselectUsbDevice(
            deviceId: Int,
            vendorId: Int,
            productId: Int,
            deviceName: String,
        ) {
            Log.d(TAG, "Pre-selecting USB device: $deviceId ($deviceName)")

            // Switch to USB connection type
            setConnectionType(RNodeConnectionType.USB_SERIAL)

            // Scan for USB devices to get proper device info and permission status
            scanUsbDevices()

            // Try to find and select the device after scanning
            viewModelScope.launch {
                // Wait a bit for USB scan to complete
                delay(500)

                // Find the device in the scanned list
                val scannedDevice = _state.value.usbDevices.find { it.deviceId == deviceId }
                if (scannedDevice != null) {
                    selectUsbDevice(scannedDevice)
                    Log.d(TAG, "Found and selected USB device: ${scannedDevice.displayName}")
                } else {
                    // If not found in scan, create a placeholder device
                    // (permission will be requested when selected)
                    val placeholderDevice = com.lxmf.messenger.data.model.DiscoveredUsbDevice(
                        deviceId = deviceId,
                        vendorId = vendorId,
                        productId = productId,
                        deviceName = deviceName,
                        manufacturerName = null,
                        productName = "USB RNode",
                        serialNumber = null,
                        driverType = "Unknown",
                        hasPermission = false,
                    )
                    _state.update {
                        it.copy(
                            usbDevices = listOf(placeholderDevice) + it.usbDevices,
                            selectedUsbDevice = null,
                        )
                    }
                    // Request permission for the device
                    requestUsbPermission(placeholderDevice)
                    Log.d(TAG, "Created placeholder USB device and requesting permission")
                }
            }
        }

        /**
         * Enter Bluetooth pairing mode via USB connection.
         * Sends CMD_BT_CTRL command to RNode to enter pairing mode.
         * RNode will respond with CMD_BT_PIN containing the 6-digit PIN.
         */
        fun enterUsbBluetoothPairingMode() {
            val device = _state.value.selectedUsbDevice ?: return

            viewModelScope.launch {
                _state.update { it.copy(isUsbPairingMode = true, usbBluetoothPin = null) }

                try {
                    // Flag to track if PIN was received
                    var pinReceived = false

                    // Set up PIN callback (Kotlin version)
                    usbBridge.setOnBluetoothPinReceivedKotlin { pin ->
                        viewModelScope.launch {
                            Log.d(TAG, "Received Bluetooth PIN from RNode: $pin")
                            pinReceived = true
                            _state.update { it.copy(usbBluetoothPin = pin) }

                            // Attempt auto-pairing with the received PIN
                            initiateAutoPairingWithPin(pin)
                        }
                    }

                    // Connect to USB device
                    val connected =
                        withContext(Dispatchers.IO) {
                            usbBridge.connect(device.deviceId)
                        }

                    if (!connected) {
                        _state.update {
                            it.copy(
                                isUsbPairingMode = false,
                                usbScanError = "Failed to connect to USB device",
                            )
                        }
                        return@launch
                    }

                    // Send pairing mode command via Python bridge
                    // Note: This will be handled by the Python rnode_interface
                    // For now, we send raw KISS command directly
                    // KISS frame: FEND (0xC0), CMD_BT_CTRL (0x46), BT_CTRL_PAIRING_MODE (0x02), FEND (0xC0)
                    val kissPairingCmd =
                        byteArrayOf(
                            0xC0.toByte(),
                            0x46.toByte(),
                            0x02.toByte(),
                            0xC0.toByte(),
                        )

                    val written =
                        withContext(Dispatchers.IO) {
                            usbBridge.write(kissPairingCmd)
                        }

                    if (written != kissPairingCmd.size) {
                        _state.update {
                            it.copy(
                                isUsbPairingMode = false,
                                usbScanError = "Failed to send pairing command",
                            )
                        }
                        usbBridge.disconnect()
                        return@launch
                    }

                    Log.d(TAG, "Bluetooth pairing mode command sent via USB")

                    // Start discovery IMMEDIATELY - don't wait for PIN entry!
                    // RNode pairing mode lasts ~35 seconds, but we want to discover
                    // the device while it's still advertising.
                    // Run BOTH Classic BT and BLE discovery since we don't know
                    // which type of Bluetooth the RNode uses.
                    Log.d(TAG, "Starting BOTH Classic BT and BLE discovery immediately after pairing command")
                    startClassicBluetoothDiscoveryForPairing()
                    startBleScanForPairingEarly()

                    // Wait for PIN response with timeout (3 seconds)
                    // RNode should respond quickly if it supports the command
                    try {
                        withTimeout(3_000L) {
                            while (!pinReceived) {
                                delay(100)
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "Timeout waiting for Bluetooth PIN from RNode - prompting for manual entry")
                        // RNode entered pairing mode but didn't send PIN over serial
                        // (common on some firmware versions like Heltec v3)
                        // Prompt user to enter PIN shown on RNode's display
                        _state.update {
                            it.copy(
                                showManualPinEntry = true,
                                usbPairingStatus = "Enter the PIN shown on your RNode's display",
                            )
                        }
                        // Keep USB connected - we'll disconnect after manual PIN entry
                        // Classic BT discovery is already running in background
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enter Bluetooth pairing mode", e)
                    _state.update {
                        it.copy(
                            isUsbPairingMode = false,
                            usbScanError = "Error: ${e.message}",
                        )
                    }
                }
            }
        }

        /**
         * Initiate auto-pairing with an RNode using the PIN received via USB.
         * This scans for RNode devices and attempts to pair automatically.
         */
        @SuppressLint("MissingPermission")
        private suspend fun initiateAutoPairingWithPin(pin: String) {
            Log.d(TAG, "Initiating auto-pairing with PIN: $pin")

            // Unregister any existing pairing handler to avoid duplicate receivers
            pairingHandler?.unregister()

            // Create and register pairing handler with the PIN
            val handler = BlePairingHandler(context).apply {
                setAutoPairPin(pin)
                register()
            }
            pairingHandler = handler

            try {
                // RNode may advertise via Classic Bluetooth OR BLE depending on the board:
                // - Boards with HAS_BLUETOOTH (e.g., Heltec v2): Classic Bluetooth
                // - Boards with HAS_BLE (e.g., Heltec v3): BLE only
                // We don't know which type we're pairing with, so run BOTH discovery methods.
                Log.d(TAG, "Starting BOTH Classic BT discovery AND BLE scan for RNode")
                _state.update { it.copy(usbPairingStatus = "Scanning for RNode...") }

                // Start both discovery methods in parallel
                startClassicBluetoothDiscovery(pin)
                startBleScanForPairing(pin)
            } catch (e: Exception) {
                Log.e(TAG, "Auto-pairing failed", e)
                _state.update { it.copy(usbPairingStatus = "Auto-pairing failed: ${e.message}") }
            }
        }

        /** Active BLE scan callback for pairing - stored to allow stopping the scan */
        private var pairingScanCallback: ScanCallback? = null

        /**
         * Start BLE scan to find RNode devices for pairing.
         * RNodes advertise the Nordic UART Service (NUS) UUID.
         */
        @SuppressLint("MissingPermission")
        private fun startBleScanForPairing(pin: String) {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BLE scanner not available")
                _state.update { it.copy(usbPairingStatus = "BLE scanner not available") }
                return
            }

            // Get the set of already-bonded device addresses to filter them out
            val bondedAddresses =
                bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
            Log.d(TAG, "Bonded device addresses to skip: $bondedAddresses")

            // Don't filter by service UUID - RNode in pairing mode may not advertise NUS
            // We'll filter by device name in the callback instead
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val deviceName = device.name ?: return

                    // Only log and process RNode devices to reduce noise
                    if (!deviceName.startsWith("RNode", ignoreCase = true)) return

                    val isAlreadyBonded = bondedAddresses.contains(device.address)

                    Log.d(
                        TAG,
                        "BLE scan found: $deviceName (${device.address}), " +
                            "bondState=${device.bondState}, isAlreadyBonded=$isAlreadyBonded",
                    )

                    // Stop the scan - we found an RNode
                    try {
                        scanner.stopScan(this)
                        pairingScanCallback = null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to stop BLE scan", e)
                    }

                    if (isAlreadyBonded) {
                        // RNode is already paired - no need to pair again, just add it to discovered devices
                        Log.i(TAG, "Found already-bonded RNode via BLE: $deviceName - adding to discovered devices")
                        val discoveredNode = DiscoveredRNode(
                            name = deviceName,
                            address = device.address,
                            type = BluetoothType.BLE,
                            rssi = result.rssi,
                            isPaired = true,
                            bluetoothDevice = device,
                        )
                        viewModelScope.launch {
                            _state.update { state ->
                                state.copy(
                                    usbPairingStatus = "$deviceName is already paired!",
                                    isUsbPairingMode = false,
                                    usbBluetoothPin = null,
                                    // Add to discovered devices and auto-select it
                                    discoveredDevices = state.discoveredDevices.map {
                                        if (it.address == device.address) discoveredNode else it
                                    } + if (state.discoveredDevices.none { it.address == device.address }) {
                                        listOf(discoveredNode)
                                    } else {
                                        emptyList()
                                    },
                                    selectedDevice = discoveredNode,
                                    interfaceName = state.defaultInterfaceNameFor(discoveredNode),
                                )
                            }
                        }
                    } else {
                        // RNode is not bonded - initiate pairing
                        Log.i(TAG, "Discovered unbonded RNode via BLE: $deviceName")

                        // Set the target device for the pairing handler
                        pairingHandler?.setAutoPairPin(pin, device.address)

                        viewModelScope.launch {
                            _state.update {
                                it.copy(usbPairingStatus = "Found $deviceName, pairing...")
                            }

                            // Initiate bonding with BLE transport
                            try {
                                val createBondMethod = BluetoothDevice::class.java.getMethod(
                                    "createBond",
                                    Int::class.javaPrimitiveType
                                )
                                // TRANSPORT_LE = 2 for BLE
                                createBondMethod.invoke(device, 2)
                                Log.d(TAG, "createBond(TRANSPORT_LE) called for RNode")
                            } catch (e: Exception) {
                                Log.w(TAG, "createBond with transport failed, using default", e)
                                device.createBond()
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE pairing scan failed: $errorCode")
                    pairingScanCallback = null
                    val errorMessage = when (errorCode) {
                        1 -> "BLE scan failed: already started"
                        2 -> "BLE scan failed: app not registered"
                        3 -> "BLE scan failed: internal error"
                        4 -> "BLE scan failed: feature unsupported"
                        5 -> "BLE scan failed: out of hardware resources"
                        6 -> "BLE scan failed: scanning too frequently"
                        else -> "BLE scan failed: error code $errorCode"
                    }
                    _state.update { it.copy(usbPairingStatus = errorMessage) }
                }
            }

            pairingScanCallback = callback
            Log.d(TAG, "Starting BLE scan for RNode pairing (no UUID filter)")

            try {
                // Scan without filters - we filter by device name in the callback
                scanner.startScan(null, scanSettings, callback)

                // Set a timeout to stop scanning after 30 seconds
                viewModelScope.launch {
                    delay(30_000)
                    pairingScanCallback?.let { cb ->
                        Log.d(TAG, "BLE pairing scan timeout - stopping scan")
                        try {
                            scanner.stopScan(cb)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to stop BLE scan on timeout", e)
                        }
                        pairingScanCallback = null
                        // Only update status if we're still in scanning state
                        if (_state.value.usbPairingStatus == "Scanning for RNode...") {
                            _state.update {
                                it.copy(usbPairingStatus = "No RNode found. Make sure your RNode is in pairing mode.")
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "BLE scan permission denied", e)
                _state.update { it.copy(usbPairingStatus = "Bluetooth permission required") }
            }
        }

        /** Broadcast receiver for Classic Bluetooth discovery during manual PIN pairing */
        private var classicDiscoveryReceiver: BroadcastReceiver? = null

        /** Device discovered during early Classic BT discovery (before PIN entry) */
        private var discoveredPairingDevice: BluetoothDevice? = null

        /**
         * Start Classic Bluetooth discovery to find RNode devices in pairing mode.
         * RNode advertises via Classic Bluetooth when in pairing mode, NOT via BLE.
         */
        @SuppressLint("MissingPermission")
        private fun startClassicBluetoothDiscovery(pin: String) {
            // Get bonded device addresses - we'll skip these during discovery
            // since we want to find the NEW unbonded RNode in pairing mode
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            val bondedAddresses = bondedDevices.map { it.address }.toSet()
            Log.d(TAG, "Starting Classic BT discovery. Bonded addresses to skip: $bondedAddresses")

            // Unregister any existing receiver
            classicDiscoveryReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister previous discovery receiver", e)
                }
            }

            val discoveryReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device: BluetoothDevice? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(
                                            BluetoothDevice.EXTRA_DEVICE,
                                            BluetoothDevice::class.java,
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                    }

                                device?.let {
                                    val deviceName = it.name ?: return
                                    val isAlreadyBonded = bondedAddresses.contains(it.address)

                                    Log.d(
                                        TAG,
                                        "Classic BT discovery found: $deviceName (${it.address}), " +
                                            "bondState=${it.bondState}, isAlreadyBonded=$isAlreadyBonded",
                                    )

                                    // Only pair with RNodes that are NOT already bonded
                                    if (deviceName.startsWith("RNode", ignoreCase = true) && !isAlreadyBonded) {
                                        Log.i(TAG, "Discovered unbonded RNode via Classic BT: $deviceName")
                                        bluetoothAdapter?.cancelDiscovery()

                                        // Set the target device for the pairing handler
                                        pairingHandler?.setAutoPairPin(pin, it.address)

                                        viewModelScope.launch {
                                            _state.update { state ->
                                                state.copy(usbPairingStatus = "Found $deviceName, pairing...")
                                            }

                                            // Initiate bonding
                                            try {
                                                val createBondMethod = BluetoothDevice::class.java.getMethod(
                                                    "createBond",
                                                    Int::class.javaPrimitiveType,
                                                )
                                                // TRANSPORT_BREDR = 1 for Classic Bluetooth
                                                createBondMethod.invoke(it, 1)
                                                Log.d(TAG, "createBond(TRANSPORT_BREDR) called for RNode")
                                            } catch (e: Exception) {
                                                Log.w(TAG, "createBond with transport failed, using default", e)
                                                it.createBond()
                                            }
                                        }

                                        try {
                                            context.unregisterReceiver(this)
                                            classicDiscoveryReceiver = null
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to unregister discovery receiver", e)
                                        }
                                    }
                                }
                            }

                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                Log.d(TAG, "Classic Bluetooth discovery finished")
                                // Check if we're still in scanning state (no device found)
                                if (_state.value.usbPairingStatus == "Scanning for RNode...") {
                                    _state.update {
                                        it.copy(usbPairingStatus = "No RNode found. Make sure your RNode is in pairing mode.")
                                    }
                                }
                                try {
                                    context.unregisterReceiver(this)
                                    classicDiscoveryReceiver = null
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to unregister discovery receiver", e)
                                }
                            }
                        }
                    }
                }

            classicDiscoveryReceiver = discoveryReceiver

            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(discoveryReceiver, filter)
            }

            Log.d(TAG, "Starting Classic Bluetooth discovery for RNode")
            bluetoothAdapter?.startDiscovery()
        }

        /**
         * Start Classic Bluetooth discovery EARLY (before PIN entry) to catch the RNode
         * while it's still in pairing mode. RNode firmware only advertises for ~10 seconds,
         * so we need to discover the device immediately after sending the pairing command,
         * not after the user enters the PIN.
         *
         * This function only DISCOVERS the device and stores it in [discoveredPairingDevice].
         * It does NOT initiate pairing - that happens later when the user submits the PIN.
         */
        @SuppressLint("MissingPermission")
        private fun startClassicBluetoothDiscoveryForPairing() {
            // Clear any previously discovered device
            discoveredPairingDevice = null

            // Get bonded device addresses - we'll skip these during discovery
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            val bondedAddresses = bondedDevices.map { it.address }.toSet()
            Log.d(TAG, "Starting early Classic BT discovery. Bonded addresses to skip: $bondedAddresses")

            // Unregister any existing receiver
            classicDiscoveryReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister previous discovery receiver", e)
                }
            }

            val discoveryReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device: BluetoothDevice? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(
                                            BluetoothDevice.EXTRA_DEVICE,
                                            BluetoothDevice::class.java,
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                    }

                                device?.let {
                                    val deviceName = it.name ?: return
                                    val isAlreadyBonded = bondedAddresses.contains(it.address)

                                    Log.d(
                                        TAG,
                                        "Early discovery found: $deviceName (${it.address}), " +
                                            "bondState=${it.bondState}, isAlreadyBonded=$isAlreadyBonded",
                                    )

                                    // Store unbonded RNode for later pairing (when PIN is submitted)
                                    if (deviceName.startsWith("RNode", ignoreCase = true) && !isAlreadyBonded) {
                                        Log.i(TAG, "Early discovery found unbonded RNode: $deviceName - storing for later pairing")
                                        discoveredPairingDevice = it
                                        bluetoothAdapter?.cancelDiscovery()

                                        // Update UI to indicate device was found
                                        viewModelScope.launch {
                                            _state.update { state ->
                                                state.copy(usbPairingStatus = "Found $deviceName - enter PIN to pair")
                                            }
                                        }

                                        try {
                                            context.unregisterReceiver(this)
                                            classicDiscoveryReceiver = null
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to unregister discovery receiver", e)
                                        }
                                    }
                                }
                            }

                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                Log.d(TAG, "Early Classic Bluetooth discovery finished")
                                // Don't update UI status here - user might still be entering PIN
                                // The submitManualPin() function will handle the case where no device was found
                                try {
                                    context.unregisterReceiver(this)
                                    classicDiscoveryReceiver = null
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to unregister discovery receiver", e)
                                }
                            }
                        }
                    }
                }

            classicDiscoveryReceiver = discoveryReceiver

            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(discoveryReceiver, filter)
            }

            Log.d(TAG, "Starting early Classic Bluetooth discovery for RNode (before PIN entry)")
            bluetoothAdapter?.startDiscovery()
        }

        /** BLE scan callback for early discovery (before PIN entry) */
        private var earlyBleScanCallback: ScanCallback? = null

        /**
         * Start BLE scan EARLY (before PIN entry) to catch BLE-only RNodes
         * (like Heltec v3) while they're still in pairing mode.
         *
         * This function only DISCOVERS the device and stores it in [discoveredPairingDevice].
         * It does NOT initiate pairing - that happens later when the user submits the PIN.
         */
        @SuppressLint("MissingPermission")
        private fun startBleScanForPairingEarly() {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "BLE scanner not available for early discovery")
                return
            }

            // Get the set of already-bonded device addresses to filter them out
            val bondedAddresses =
                bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
            Log.d(TAG, "Starting early BLE scan. Bonded addresses to skip: $bondedAddresses")

            // Stop any existing early scan
            earlyBleScanCallback?.let {
                try {
                    scanner.stopScan(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop previous early BLE scan", e)
                }
            }

            // Don't filter by service UUID - RNode in pairing mode may not advertise NUS
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val deviceName = device.name ?: return

                    // Only process RNode devices
                    if (!deviceName.startsWith("RNode", ignoreCase = true)) return

                    val isAlreadyBonded = bondedAddresses.contains(device.address)

                    Log.d(
                        TAG,
                        "Early BLE scan found: $deviceName (${device.address}), " +
                            "bondState=${device.bondState}, isAlreadyBonded=$isAlreadyBonded",
                    )

                    // Store unbonded RNode for later pairing (when PIN is submitted)
                    if (!isAlreadyBonded) {
                        Log.i(TAG, "Early BLE scan found unbonded RNode: $deviceName - storing for later pairing")
                        discoveredPairingDevice = device

                        // Stop the scan - we found our device
                        try {
                            scanner.stopScan(this)
                            earlyBleScanCallback = null
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to stop early BLE scan", e)
                        }

                        // Also stop Classic BT discovery if running
                        bluetoothAdapter?.cancelDiscovery()

                        // Update UI to indicate device was found
                        viewModelScope.launch {
                            _state.update { state ->
                                state.copy(usbPairingStatus = "Found $deviceName - enter PIN to pair")
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Early BLE scan failed with error code: $errorCode")
                }
            }

            earlyBleScanCallback = callback

            try {
                scanner.startScan(null, scanSettings, callback)
                Log.d(TAG, "Early BLE scan started for RNode (before PIN entry)")

                // Auto-stop the scan after 30 seconds if nothing found
                viewModelScope.launch {
                    delay(30_000L)
                    earlyBleScanCallback?.let {
                        try {
                            scanner.stopScan(it)
                            earlyBleScanCallback = null
                            Log.d(TAG, "Early BLE scan timed out after 30 seconds")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to stop timed-out early BLE scan", e)
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Early BLE scan permission denied", e)
            }
        }

        /**
         * Exit USB Bluetooth pairing mode.
         */
        fun exitUsbBluetoothPairingMode() {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    // Send command to exit pairing mode: stop BT (0x00), then start BT (0x01)
                    // CMD_BT_CTRL = 0x46
                    val stopPairingCmd = byteArrayOf(
                        0xC0.toByte(),  // FEND
                        0x46.toByte(),  // CMD_BT_CTRL
                        0x00.toByte(),  // Stop Bluetooth (exits pairing mode)
                        0xC0.toByte(),  // FEND
                    )
                    val restartBtCmd = byteArrayOf(
                        0xC0.toByte(),  // FEND
                        0x46.toByte(),  // CMD_BT_CTRL
                        0x01.toByte(),  // Start Bluetooth
                        0xC0.toByte(),  // FEND
                    )
                    usbBridge.write(stopPairingCmd)
                    delay(100)  // Brief delay between commands
                    usbBridge.write(restartBtCmd)
                    delay(100)
                    usbBridge.disconnect()
                }
                _state.update {
                    it.copy(
                        isUsbPairingMode = false,
                        usbBluetoothPin = null,
                    )
                }
            }
        }

        /**
         * Clear USB-related errors.
         */
        fun clearUsbError() {
            _state.update { it.copy(usbScanError = null) }
        }

        /**
         * Update manual PIN input field.
         */
        fun updateManualPinInput(pin: String) {
            // Only allow digits, max 6 characters
            val filtered = pin.filter { it.isDigit() }.take(6)
            _state.update { it.copy(manualPinInput = filtered) }
        }

        /**
         * Submit manually entered PIN for Bluetooth pairing.
         * Called when user enters the PIN shown on RNode's display.
         */
        @SuppressLint("MissingPermission")
        fun submitManualPin() {
            val pin = _state.value.manualPinInput
            if (pin.length != 6) {
                _state.update {
                    it.copy(usbScanError = "PIN must be 6 digits")
                }
                return
            }

            viewModelScope.launch {
                Log.d(TAG, "Manual PIN submitted: $pin")
                _state.update {
                    it.copy(
                        showManualPinEntry = false,
                        manualPinInput = "",
                        usbBluetoothPin = pin,
                    )
                }

                // Disconnect USB - we have the PIN now
                withContext(Dispatchers.IO) {
                    usbBridge.disconnect()
                }

                // Check if we already discovered an RNode during early discovery
                val preDiscoveredDevice = discoveredPairingDevice
                if (preDiscoveredDevice != null) {
                    // Great! We already found the RNode while waiting for PIN entry.
                    // Use it directly for pairing without starting new discovery.
                    Log.i(TAG, "Using pre-discovered RNode: ${preDiscoveredDevice.name} (${preDiscoveredDevice.address})")
                    pairWithDiscoveredDevice(preDiscoveredDevice, pin)
                } else {
                    // Device wasn't found during early discovery (maybe RNode already exited
                    // pairing mode, or discovery didn't find it). Fall back to new discovery.
                    Log.w(TAG, "No pre-discovered device available, starting new discovery")
                    initiateAutoPairingWithPin(pin)
                }
            }
        }

        /**
         * Pair with a previously discovered RNode device using the given PIN.
         */
        @SuppressLint("MissingPermission")
        private fun pairWithDiscoveredDevice(device: BluetoothDevice, pin: String) {
            val deviceName = device.name ?: "RNode"
            Log.d(TAG, "Pairing with pre-discovered device: $deviceName (${device.address})")

            _state.update { it.copy(usbPairingStatus = "Pairing with $deviceName...") }

            // Unregister any existing pairing handler to avoid duplicate receivers
            pairingHandler?.unregister()

            // Create and register pairing handler with the PIN
            val handler = BlePairingHandler(context).apply {
                setAutoPairPin(pin, device.address)
                register()
            }
            pairingHandler = handler

            // Register bond state change listener BEFORE calling createBond
            val bondReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                    val bondedDevice: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    // Only process events for our target device
                    if (bondedDevice?.address != device.address) return

                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    Log.d(TAG, "Bond state changed for ${device.name}: $prevState -> $bondState")

                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "Successfully paired with ${device.name}!")
                            viewModelScope.launch {
                                // Create DiscoveredRNode for the paired device
                                val discoveredNode = DiscoveredRNode(
                                    name = deviceName,
                                    address = device.address,
                                    type = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) BluetoothType.BLE else BluetoothType.CLASSIC,
                                    rssi = -50,  // Unknown RSSI
                                    isPaired = true,
                                    bluetoothDevice = device,
                                )
                                _state.update { state ->
                                    state.copy(
                                        usbPairingStatus = "Successfully paired with $deviceName!",
                                        isUsbPairingMode = false,
                                        usbBluetoothPin = null,
                                        // Add to discovered devices and auto-select it
                                        discoveredDevices = state.discoveredDevices.filter { it.address != device.address } + discoveredNode,
                                        selectedDevice = discoveredNode,
                                        interfaceName = state.defaultInterfaceNameFor(discoveredNode),
                                    )
                                }
                            }
                            // Unregister receiver
                            try {
                                context.unregisterReceiver(this)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to unregister bond receiver", e)
                            }
                            // Cleanup pairing handler
                            pairingHandler?.unregister()
                            pairingHandler = null
                        }
                        BluetoothDevice.BOND_NONE -> {
                            // Pairing failed
                            Log.e(TAG, "Pairing failed with ${device.name}")
                            viewModelScope.launch {
                                _state.update {
                                    it.copy(usbPairingStatus = "Pairing failed with $deviceName")
                                }
                            }
                            // Unregister receiver
                            try {
                                context.unregisterReceiver(this)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to unregister bond receiver", e)
                            }
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            Log.d(TAG, "Bonding in progress with ${device.name}...")
                        }
                    }
                }
            }

            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(bondReceiver, filter)
            }

            // Initiate bonding with the appropriate transport based on device type
            viewModelScope.launch {
                try {
                    val createBondMethod = BluetoothDevice::class.java.getMethod(
                        "createBond",
                        Int::class.javaPrimitiveType,
                    )
                    // TRANSPORT_BREDR = 1 for Classic Bluetooth, TRANSPORT_LE = 2 for BLE
                    val transport = when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_LE -> 2  // TRANSPORT_LE
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> 1  // TRANSPORT_BREDR
                        BluetoothDevice.DEVICE_TYPE_DUAL -> 2  // Prefer LE for dual-mode devices
                        else -> 1  // Default to Classic
                    }
                    val transportName = if (transport == 2) "TRANSPORT_LE" else "TRANSPORT_BREDR"
                    Log.d(TAG, "Device type: ${device.type}, using $transportName")
                    createBondMethod.invoke(device, transport)
                    Log.d(TAG, "createBond($transportName) called for pre-discovered RNode")
                } catch (e: Exception) {
                    Log.w(TAG, "createBond with transport failed, using default", e)
                    device.createBond()
                }
            }

            // Clear the pre-discovered device reference
            discoveredPairingDevice = null
        }

        /**
         * Cancel manual PIN entry and exit pairing mode.
         */
        fun cancelManualPinEntry() {
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        showManualPinEntry = false,
                        manualPinInput = "",
                        isUsbPairingMode = false,
                        usbPairingStatus = null,
                    )
                }
                withContext(Dispatchers.IO) {
                    usbBridge.disconnect()
                }
            }
        }

        // ========== USB-ASSISTED BLUETOOTH PAIRING (from Bluetooth tab) ==========

        /**
         * Start USB-assisted Bluetooth pairing from the Bluetooth tab.
         * This uses the same state and code path as the USB tab's pairing flow,
         * just auto-discovers and selects the USB device first.
         */
        fun startUsbAssistedPairing() {
            viewModelScope.launch {
                Log.d(TAG, "USB-assisted pairing from Bluetooth tab: scanning for USB devices")

                _state.update {
                    it.copy(
                        usbScanError = null,
                        usbPairingStatus = "Scanning for USB devices...",
                    )
                }

                try {
                    // Scan for USB devices
                    val devices = withContext(Dispatchers.IO) {
                        usbBridge.getConnectedUsbDevices()
                    }

                    val usbDevices = devices.map { device ->
                        com.lxmf.messenger.data.model.DiscoveredUsbDevice(
                            deviceId = device.deviceId,
                            vendorId = device.vendorId,
                            productId = device.productId,
                            deviceName = device.deviceName,
                            manufacturerName = device.manufacturerName,
                            productName = device.productName,
                            serialNumber = device.serialNumber,
                            driverType = device.driverType,
                            hasPermission = usbBridge.hasPermission(device.deviceId),
                        )
                    }

                    if (usbDevices.isEmpty()) {
                        _state.update {
                            it.copy(
                                usbPairingStatus = null,
                                pairingError = "No USB devices found. Connect your RNode via USB cable.",
                            )
                        }
                        return@launch
                    }

                    // Auto-select the first USB device
                    val usbDevice = usbDevices.first()
                    Log.d(TAG, "USB-assisted pairing: Found ${usbDevice.displayName}")

                    // Check if we need USB permission
                    if (!usbDevice.hasPermission) {
                        _state.update {
                            it.copy(
                                selectedUsbDevice = usbDevice,
                                isRequestingUsbPermission = true,
                                usbPairingStatus = "Requesting USB permission...",
                            )
                        }
                        usbBridge.requestPermission(usbDevice.deviceId) { granted ->
                            viewModelScope.launch {
                                _state.update { it.copy(isRequestingUsbPermission = false) }
                                if (granted) {
                                    val updatedDevice = usbDevice.copy(hasPermission = true)
                                    _state.update { it.copy(selectedUsbDevice = updatedDevice) }
                                    // Now enter pairing mode using the shared flow
                                    enterUsbBluetoothPairingMode()
                                } else {
                                    _state.update {
                                        it.copy(
                                            usbPairingStatus = null,
                                            pairingError = "USB permission denied. Please grant permission.",
                                        )
                                    }
                                }
                            }
                        }
                        return@launch
                    }

                    // Select device and enter pairing mode using the shared flow
                    _state.update { it.copy(selectedUsbDevice = usbDevice) }
                    enterUsbBluetoothPairingMode()
                } catch (e: Exception) {
                    Log.e(TAG, "USB-assisted pairing failed", e)
                    _state.update {
                        it.copy(
                            usbPairingStatus = null,
                            pairingError = "USB-assisted pairing failed: ${e.message}",
                        )
                    }
                }
            }
        }

        /**
         * Continue USB-assisted pairing after USB device is connected.
         */
        private suspend fun continueUsbAssistedPairing(
            usbDevice: com.lxmf.messenger.data.model.DiscoveredUsbDevice,
        ) {
            try {
                _state.update { it.copy(usbAssistedPairingStatus = "Entering Bluetooth pairing mode...") }

                // Set up PIN callback
                usbBridge.setOnBluetoothPinReceivedKotlin { pin ->
                    viewModelScope.launch {
                        Log.d(TAG, "USB-assisted: Received Bluetooth PIN from RNode: $pin")
                        _state.update {
                            it.copy(
                                usbAssistedPairingPin = pin,
                                usbAssistedPairingStatus = "Scanning for RNodes...",
                            )
                        }

                        // Start scanning for RNodes to pair with
                        startUsbAssistedBluetoothDiscovery(pin)
                    }
                }

                // Connect to USB device
                val connected =
                    withContext(Dispatchers.IO) {
                        usbBridge.connect(usbDevice.deviceId)
                    }

                if (!connected) {
                    _state.update {
                        it.copy(
                            isUsbAssistedPairingActive = false,
                            usbAssistedPairingStatus = null,
                            pairingError = "Failed to connect to USB device",
                        )
                    }
                    return
                }

                // Send pairing mode command
                val kissPairingCmd =
                    byteArrayOf(
                        0xC0.toByte(),
                        0x46.toByte(),
                        0x02.toByte(),
                        0xC0.toByte(),
                    )

                val written =
                    withContext(Dispatchers.IO) {
                        usbBridge.write(kissPairingCmd)
                    }

                if (written != kissPairingCmd.size) {
                    _state.update {
                        it.copy(
                            isUsbAssistedPairingActive = false,
                            usbAssistedPairingStatus = null,
                            pairingError = "Failed to send pairing command",
                        )
                    }
                    usbBridge.disconnect()
                } else {
                    Log.d(TAG, "USB-assisted: Bluetooth pairing mode command sent")
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB-assisted pairing error", e)
                _state.update {
                    it.copy(
                        isUsbAssistedPairingActive = false,
                        usbAssistedPairingStatus = null,
                        pairingError = "Error: ${e.message}",
                    )
                }
            }
        }

        // Track USB-assisted pairing discovery receiver for cleanup
        private var usbAssistedDiscoveryReceiver: BroadcastReceiver? = null

        /**
         * Start Classic Bluetooth discovery to find all unbonded RNodes for user selection.
         */
        @SuppressLint("MissingPermission")
        private fun startUsbAssistedBluetoothDiscovery(pin: String) {
            // Get the set of already-bonded device addresses to filter them out
            val bondedAddresses =
                bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
            Log.d(TAG, "USB-assisted: Bonded device addresses to skip: $bondedAddresses")

            // Collect discovered RNodes
            val discoveredRNodes = mutableListOf<DiscoveredRNode>()

            // Unregister any existing pairing handler
            pairingHandler?.unregister()

            // Create and register pairing handler with the PIN
            val handler = BlePairingHandler(context).apply {
                setAutoPairPin(pin)
                register()
            }
            pairingHandler = handler

            // Cleanup previous receiver if any
            usbAssistedDiscoveryReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister previous discovery receiver", e)
                }
            }

            val discoveryReceiver =
                object : BroadcastReceiver() {
                    @Suppress("LongMethod") // Discovery callback handles multiple cases
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device: BluetoothDevice? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(
                                            BluetoothDevice.EXTRA_DEVICE,
                                            BluetoothDevice::class.java,
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                    }

                                device?.let { btDevice ->
                                    val deviceName = btDevice.name ?: return
                                    val isAlreadyBonded = bondedAddresses.contains(btDevice.address)

                                    Log.d(
                                        TAG,
                                        "USB-assisted discovery found: $deviceName (${btDevice.address}), " +
                                            "bondState=${btDevice.bondState}, isAlreadyBonded=$isAlreadyBonded",
                                    )

                                    // Only collect RNodes that are NOT already bonded
                                    if (deviceName.startsWith("RNode", ignoreCase = true) && !isAlreadyBonded) {
                                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                                        val rnode = DiscoveredRNode(
                                            name = deviceName,
                                            address = btDevice.address,
                                            type = BluetoothType.CLASSIC,
                                            rssi = if (rssi != Short.MIN_VALUE) rssi.toInt() else null,
                                            isPaired = false,
                                            bluetoothDevice = btDevice,
                                        )

                                        // Add to list if not already present
                                        if (discoveredRNodes.none { it.address == btDevice.address }) {
                                            discoveredRNodes.add(rnode)
                                            Log.i(TAG, "USB-assisted: Found unbonded RNode: $deviceName")

                                            // Update UI with discovered devices
                                            viewModelScope.launch {
                                                _state.update { state ->
                                                    state.copy(
                                                        usbAssistedPairingDevices = discoveredRNodes.toList(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                Log.d(TAG, "USB-assisted: Bluetooth discovery finished, found ${discoveredRNodes.size} RNodes")

                                viewModelScope.launch {
                                    if (discoveredRNodes.isEmpty()) {
                                        _state.update {
                                            it.copy(
                                                isUsbAssistedPairingActive = false,
                                                usbAssistedPairingStatus = null,
                                                pairingError = "No unpaired RNodes found. Make sure your RNode is powered on and in range.",
                                            )
                                        }
                                        withContext(Dispatchers.IO) {
                                            usbBridge.disconnect()
                                        }
                                    } else if (discoveredRNodes.size == 1) {
                                        // Auto-select if only one RNode found
                                        Log.i(TAG, "USB-assisted: Auto-selecting single RNode: ${discoveredRNodes[0].name}")
                                        _state.update {
                                            it.copy(usbAssistedPairingStatus = "Found ${discoveredRNodes[0].name}, pairing...")
                                        }
                                        selectDeviceForUsbPairing(discoveredRNodes[0])
                                    } else {
                                        // Multiple RNodes found - let user select
                                        _state.update {
                                            it.copy(
                                                usbAssistedPairingStatus = "Select your RNode from the list",
                                                usbAssistedPairingDevices = discoveredRNodes.toList(),
                                            )
                                        }
                                    }
                                }

                                try {
                                    context.unregisterReceiver(this)
                                    usbAssistedDiscoveryReceiver = null
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to unregister discovery receiver", e)
                                }
                            }
                        }
                    }
                }

            usbAssistedDiscoveryReceiver = discoveryReceiver

            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(discoveryReceiver, filter)
            }

            Log.d(TAG, "USB-assisted: Starting Classic Bluetooth discovery for RNodes")
            bluetoothAdapter?.startDiscovery()
        }

        // Track bond state receiver for USB-assisted pairing
        private var usbAssistedBondReceiver: BroadcastReceiver? = null

        /**
         * Select a device for USB-assisted pairing.
         */
        @SuppressLint("MissingPermission")
        @Suppress("LongMethod") // BT pairing flow requires sequential steps with state updates
        fun selectDeviceForUsbPairing(device: DiscoveredRNode) {
            val pin = _state.value.usbAssistedPairingPin ?: return

            viewModelScope.launch {
                _state.update {
                    it.copy(
                        usbAssistedPairingStatus = "Pairing with ${device.name}...",
                        isPairingInProgress = true,
                    )
                }

                // Set the target device for the pairing handler
                pairingHandler?.setAutoPairPin(pin, device.address)

                try {
                    val btDevice = device.bluetoothDevice ?: bluetoothAdapter?.getRemoteDevice(device.address)
                    if (btDevice == null) {
                        _state.update {
                            it.copy(
                                isUsbAssistedPairingActive = false,
                                usbAssistedPairingStatus = null,
                                isPairingInProgress = false,
                                pairingError = "Could not find Bluetooth device",
                            )
                        }
                        return@launch
                    }

                    // Use CompletableDeferred to wait for bond state change via broadcast
                    val bondResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

                    // Register receiver for bond state changes
                    val bondReceiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                            val bondDevice: BluetoothDevice? =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                }

                            if (bondDevice?.address != device.address) return

                            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                            val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                            Log.d(TAG, "USB-assisted: Bond state changed for ${device.name}: $prevState -> $newState")

                            when (newState) {
                                BluetoothDevice.BOND_BONDED -> {
                                    Log.i(TAG, "USB-assisted: Pairing successful for ${device.name}")
                                    bondResult.complete(true)
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    if (prevState == BluetoothDevice.BOND_BONDING) {
                                        Log.w(TAG, "USB-assisted: Pairing failed for ${device.name}")
                                        bondResult.complete(false)
                                    }
                                }
                            }
                        }
                    }

                    usbAssistedBondReceiver = bondReceiver

                    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(bondReceiver, filter)
                    }

                    // Initiate bonding with BLE transport (TRANSPORT_LE = 2)
                    try {
                        val createBondMethod = BluetoothDevice::class.java.getMethod(
                            "createBond",
                            Int::class.javaPrimitiveType,
                        )
                        createBondMethod.invoke(btDevice, 2)
                        Log.d(TAG, "USB-assisted: createBond(TRANSPORT_LE) called for ${device.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "USB-assisted: createBond with transport failed, using default", e)
                        btDevice.createBond()
                    }

                    // Wait for bonding to complete with timeout
                    val success = try {
                        kotlinx.coroutines.withTimeout(PIN_ENTRY_TIMEOUT_MS) {
                            bondResult.await()
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "USB-assisted: Pairing timed out for ${device.name}", e)
                        false
                    }

                    // Unregister receiver
                    try {
                        context.unregisterReceiver(bondReceiver)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unregister bond receiver", e)
                    }
                    usbAssistedBondReceiver = null

                    if (success) {
                        val pairedDevice = device.copy(isPaired = true)
                        _state.update { state ->
                            state.copy(
                                isUsbAssistedPairingActive = false,
                                usbAssistedPairingDevices = emptyList(),
                                usbAssistedPairingPin = null,
                                usbAssistedPairingStatus = null,
                                isPairingInProgress = false,
                                selectedDevice = pairedDevice,
                                discoveredDevices = state.discoveredDevices.map {
                                    if (it.address == device.address) pairedDevice else it
                                } + if (state.discoveredDevices.none { it.address == device.address }) {
                                    listOf(pairedDevice)
                                } else {
                                    emptyList()
                                },
                                interfaceName = state.defaultInterfaceNameFor(pairedDevice),
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isUsbAssistedPairingActive = false,
                                usbAssistedPairingDevices = emptyList(),
                                usbAssistedPairingPin = null,
                                usbAssistedPairingStatus = null,
                                isPairingInProgress = false,
                                pairingError = "Pairing failed. Please try again.",
                            )
                        }
                    }

                    // Disconnect USB
                    withContext(Dispatchers.IO) {
                        usbBridge.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "USB-assisted pairing error", e)
                    _state.update {
                        it.copy(
                            isUsbAssistedPairingActive = false,
                            usbAssistedPairingDevices = emptyList(),
                            usbAssistedPairingPin = null,
                            usbAssistedPairingStatus = null,
                            isPairingInProgress = false,
                            pairingError = "Pairing error: ${e.message}",
                        )
                    }
                    withContext(Dispatchers.IO) {
                        usbBridge.disconnect()
                    }
                }
            }
        }

        /**
         * Cancel USB-assisted Bluetooth pairing.
         */
        @SuppressLint("MissingPermission")
        fun cancelUsbAssistedPairing() {
            viewModelScope.launch {
                // Stop Bluetooth discovery if running
                bluetoothAdapter?.cancelDiscovery()

                // Unregister discovery receiver
                usbAssistedDiscoveryReceiver?.let {
                    try {
                        context.unregisterReceiver(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unregister discovery receiver", e)
                    }
                    usbAssistedDiscoveryReceiver = null
                }

                // Unregister bond state receiver
                usbAssistedBondReceiver?.let {
                    try {
                        context.unregisterReceiver(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unregister bond receiver", e)
                    }
                    usbAssistedBondReceiver = null
                }

                // Disconnect USB
                withContext(Dispatchers.IO) {
                    usbBridge.disconnect()
                }

                // Clear pairing handler
                pairingHandler?.clearAutoPairPin()

                _state.update {
                    it.copy(
                        isUsbAssistedPairingActive = false,
                        usbAssistedPairingDevices = emptyList(),
                        usbAssistedPairingPin = null,
                        usbAssistedPairingStatus = null,
                    )
                }

                Log.d(TAG, "USB-assisted pairing cancelled")
            }
        }

        // Pairing handler to auto-confirm Just Works pairing
        private var pairingHandler: BlePairingHandler? = null

        @SuppressLint("MissingPermission")
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun initiateBluetoothPairing(device: DiscoveredRNode) {
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        isPairingInProgress = true,
                        pairingError = null,
                        pairingTimeRemaining = 0,
                        lastPairingDeviceAddress = device.address,
                    )
                }

                // Register pairing handler (helps with Just Works devices, not PIN-based)
                pairingHandler = BlePairingHandler(context).also { it.register() }

                try {
                    // Use the actual BluetoothDevice from scan (preserves BLE transport context)
                    // Fall back to getRemoteDevice() for manually entered devices
                    val btDevice =
                        device.bluetoothDevice
                            ?: bluetoothAdapter?.getRemoteDevice(device.address)
                    if (btDevice != null && btDevice.bondState != BluetoothDevice.BOND_BONDED) {
                        // Initiate pairing - this will trigger system pairing dialog
                        btDevice.createBond()

                        // Phase 1: Wait for pairing to START (transition from BOND_NONE)
                        val startTime = System.currentTimeMillis()
                        var pairingStarted = false
                        while (System.currentTimeMillis() - startTime < PAIRING_START_TIMEOUT_MS) {
                            val bondState = btDevice.bondState
                            if (bondState == BluetoothDevice.BOND_BONDING) {
                                pairingStarted = true
                                break
                            }
                            if (bondState == BluetoothDevice.BOND_BONDED) {
                                pairingStarted = true
                                break
                            }
                            delay(200)
                        }

                        if (!pairingStarted && btDevice.bondState == BluetoothDevice.BOND_NONE) {
                            // Pairing never started - for BLE devices, try to wait for reconnect
                            if (device.type == BluetoothType.BLE) {
                                // Start waiting for device to reconnect
                                Log.d(TAG, "Device not responding, waiting for reconnect: ${device.name}")
                                _state.update {
                                    it.copy(
                                        isPairingInProgress = false,
                                        isWaitingForReconnect = true,
                                        reconnectDeviceName = device.name,
                                    )
                                }

                                // Scan for device by name with timeout
                                val reconnectedDevice = scanForDeviceByName(device.name, device.type)

                                _state.update {
                                    it.copy(isWaitingForReconnect = false, reconnectDeviceName = null)
                                }

                                if (reconnectedDevice != null) {
                                    // Device found - retry pairing with the new device reference
                                    Log.d(TAG, "Device reconnected, retrying pairing: ${device.name}")
                                    // Update discovered devices list with new device info
                                    _state.update { state ->
                                        state.copy(
                                            discoveredDevices =
                                                state.discoveredDevices.map {
                                                    if (it.name == device.name) reconnectedDevice else it
                                                },
                                        )
                                    }
                                    // Recursively retry pairing with the refreshed device
                                    initiateBluetoothPairing(reconnectedDevice)
                                    return@launch
                                } else {
                                    // Timeout - device not found
                                    _state.update {
                                        it.copy(
                                            pairingError =
                                                "Could not find RNode. Please ensure the device is powered on, " +
                                                    "Bluetooth is enabled, and it's within range. " +
                                                    "Tap 'Scan Again' to refresh the device list.",
                                        )
                                    }
                                    return@launch
                                }
                            } else {
                                // Classic Bluetooth - show standard error
                                _state.update {
                                    it.copy(
                                        pairingError =
                                            "RNode is not in pairing mode. Press the " +
                                                "pairing button until a PIN code appears on the display.",
                                    )
                                }
                                return@launch
                            }
                        }

                        // Phase 2: Wait for user to enter PIN (longer timeout)
                        val pinStartTime = System.currentTimeMillis()
                        while (btDevice.bondState == BluetoothDevice.BOND_BONDING) {
                            val elapsed = System.currentTimeMillis() - pinStartTime
                            if (elapsed >= PIN_ENTRY_TIMEOUT_MS) break
                            delay(500)
                        }

                        // Check final result
                        when (btDevice.bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                val updatedDevice = device.copy(isPaired = true)
                                _state.update { state ->
                                    state.copy(
                                        selectedDevice = updatedDevice,
                                        discoveredDevices =
                                            state.discoveredDevices.map {
                                                if (it.address == device.address) updatedDevice else it
                                            },
                                        interfaceName = state.defaultInterfaceNameFor(updatedDevice),
                                    )
                                }
                                Log.d(TAG, "Pairing successful for ${device.name}")
                            }
                            BluetoothDevice.BOND_NONE -> {
                                _state.update {
                                    it.copy(
                                        pairingError =
                                            "Pairing was cancelled or the PIN was " +
                                                "incorrect. Try again and enter the PIN shown " +
                                                "on the RNode.",
                                    )
                                }
                            }
                            BluetoothDevice.BOND_BONDING -> {
                                // Still bonding after long timeout - very unusual
                                Log.w(TAG, "Pairing still in progress after timeout")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Pairing failed", e)
                    _state.update { it.copy(pairingError = e.message ?: "Pairing failed") }
                } finally {
                    pairingHandler?.unregister()
                    pairingHandler = null
                    _state.update { it.copy(isPairingInProgress = false, pairingTimeRemaining = 0) }
                }
            }
        }

        fun clearPairingError() {
            _state.update { it.copy(pairingError = null) }
        }

        /**
         * Retry pairing with the last device that failed pairing.
         */
        fun retryPairing() {
            val address = _state.value.lastPairingDeviceAddress ?: return
            val device = _state.value.discoveredDevices.find { it.address == address } ?: return
            clearPairingError()
            initiateBluetoothPairing(device)
        }

        // ========== STEP 2: REGION SELECTION ==========

        fun updateSearchQuery(query: String) {
            _state.update { it.copy(searchQuery = query) }
        }

        fun selectCountry(country: String?) {
            _state.update {
                it.copy(
                    selectedCountry = country,
                    selectedPreset = null,
                    isCustomMode = false,
                )
            }
        }

        fun selectPreset(preset: RNodeRegionalPreset) {
            _state.update {
                it.copy(
                    selectedPreset = preset,
                    isCustomMode = false,
                    // Apply preset values and clear any validation errors
                    frequency = preset.frequency.toString(),
                    frequencyError = null,
                    bandwidth = preset.bandwidth.toString(),
                    bandwidthError = null,
                    spreadingFactor = preset.spreadingFactor.toString(),
                    spreadingFactorError = null,
                    codingRate = preset.codingRate.toString(),
                    codingRateError = null,
                    txPower = preset.txPower.toString(),
                    txPowerError = null,
                )
            }
        }

        fun enableCustomMode() {
            _state.update {
                it.copy(
                    isCustomMode = true,
                    selectedPreset = null,
                    selectedFrequencyRegion = null,
                )
            }
            updateRegulatoryWarning()
        }

        /**
         * Updates the regulatory warning based on current state.
         * Shows warning when:
         * - Custom mode with no region selected
         * - Region with duty cycle restrictions but airtime limits not set
         */
        private fun updateRegulatoryWarning() {
            val state = _state.value
            val region = state.selectedFrequencyRegion
            val stAlock = state.stAlock.toDoubleOrNull()
            val ltAlock = state.ltAlock.toDoubleOrNull()
            val isCustomMode = state.isCustomMode

            val (showWarning, message) =
                when {
                    isCustomMode && region == null ->
                        true to "No region selected. You are responsible for ensuring compliance with local regulations."

                    region != null && region.dutyCycle < 100 && (stAlock == null || ltAlock == null) ->
                        true to "Region ${region.name} has a ${region.dutyCycle}% duty cycle limit. " +
                            "Airtime limits are not set. Ensure compliance with local regulations."

                    else -> false to null
                }

            _state.update {
                it.copy(
                    showRegulatoryWarning = showWarning,
                    regulatoryWarningMessage = message,
                )
            }
        }

        // ========== FREQUENCY REGION SELECTION ==========

        fun selectFrequencyRegion(region: FrequencyRegion) {
            // Clear user modifications - user is explicitly requesting region defaults
            userModifiedFields.clear()
            _state.update {
                it.copy(
                    selectedFrequencyRegion = region,
                    // Clear any popular preset selection
                    selectedPreset = null,
                    isCustomMode = false,
                )
            }
            // Apply region defaults (frequency, tx power, airtime limits) when region changes
            // This ensures validation errors from a previous region are cleared
            applyFrequencyRegionSettings()
        }

        fun getFrequencyRegions(): List<FrequencyRegion> = FrequencyRegions.regions

        /**
         * Get the regulatory limits for the currently selected region.
         * Returns null if no region is selected.
         */
        fun getRegionLimits(): RegionLimits? {
            val region = _state.value.selectedFrequencyRegion ?: return null
            return RegionLimits(
                maxTxPower = region.maxTxPower,
                minFrequency = region.frequencyStart,
                maxFrequency = region.frequencyEnd,
                dutyCycle = region.dutyCycle,
            )
        }

        fun togglePopularPresets() {
            _state.update { it.copy(showPopularPresets = !it.showPopularPresets) }
        }

        // ========== MODEM PRESET SELECTION ==========

        fun selectModemPreset(preset: ModemPreset) {
            _state.update { it.copy(selectedModemPreset = preset) }
        }

        fun getModemPresets(): List<ModemPreset> = ModemPreset.entries

        fun getFilteredCountries(): List<String> {
            val query = _state.value.searchQuery.lowercase()
            return RNodeRegionalPresets.getCountries().filter {
                it.lowercase().contains(query)
            }
        }

        fun getPresetsForSelectedCountry(): List<RNodeRegionalPreset> {
            val country = _state.value.selectedCountry ?: return emptyList()
            return RNodeRegionalPresets.getPresetsForCountry(country)
        }

        // ========== STEP 4: REVIEW & CONFIGURE ==========

        fun updateInterfaceName(name: String) {
            _state.update { it.copy(interfaceName = name, nameError = null) }
        }

        fun updateFrequency(value: String) {
            userModifiedFields.add("frequency")
            val region = _state.value.selectedFrequencyRegion
            val result = RNodeConfigValidator.validateFrequency(value, region)
            _state.update { it.copy(frequency = value, frequencyError = result.errorMessage) }
        }

        fun updateBandwidth(value: String) {
            val result = RNodeConfigValidator.validateBandwidth(value)
            _state.update { it.copy(bandwidth = value, bandwidthError = result.errorMessage) }
        }

        fun updateSpreadingFactor(value: String) {
            val result = RNodeConfigValidator.validateSpreadingFactor(value)
            _state.update { it.copy(spreadingFactor = value, spreadingFactorError = result.errorMessage) }
        }

        fun updateCodingRate(value: String) {
            val result = RNodeConfigValidator.validateCodingRate(value)
            _state.update { it.copy(codingRate = value, codingRateError = result.errorMessage) }
        }

        fun updateTxPower(value: String) {
            userModifiedFields.add("txPower")
            val region = _state.value.selectedFrequencyRegion
            val result = RNodeConfigValidator.validateTxPower(value, region)
            _state.update { it.copy(txPower = value, txPowerError = result.errorMessage) }
        }

        fun updateStAlock(value: String) {
            userModifiedFields.add("stAlock")
            val region = _state.value.selectedFrequencyRegion
            val result = RNodeConfigValidator.validateAirtimeLimit(value, region)
            _state.update { it.copy(stAlock = value, stAlockError = result.errorMessage) }
            updateRegulatoryWarning()
        }

        fun updateLtAlock(value: String) {
            userModifiedFields.add("ltAlock")
            val region = _state.value.selectedFrequencyRegion
            val result = RNodeConfigValidator.validateAirtimeLimit(value, region)
            _state.update { it.copy(ltAlock = value, ltAlockError = result.errorMessage) }
            updateRegulatoryWarning()
        }

        fun updateInterfaceMode(mode: String) {
            _state.update { it.copy(interfaceMode = mode) }
        }

        fun toggleAdvancedSettings() {
            _state.update { it.copy(showAdvancedSettings = !it.showAdvancedSettings) }
        }

        fun updateEnableFramebuffer(enabled: Boolean) {
            _state.update { it.copy(enableFramebuffer = enabled) }
        }

        /**
         * Get the maximum TX power for the selected region (or default fallback).
         */
        private fun getMaxTxPower(): Int {
            return RNodeConfigValidator.getMaxTxPower(_state.value.selectedFrequencyRegion)
        }

        /**
         * Get the frequency range for the selected region (or default fallback).
         */
        private fun getFrequencyRange(): Pair<Long, Long> {
            return RNodeConfigValidator.getFrequencyRange(_state.value.selectedFrequencyRegion)
        }

        /**
         * Validate configuration silently (without updating error messages).
         */
        private fun validateConfigurationSilent(): Boolean {
            val state = _state.value
            return RNodeConfigValidator.validateConfigSilent(
                name = state.interfaceName,
                frequency = state.frequency,
                bandwidth = state.bandwidth,
                spreadingFactor = state.spreadingFactor,
                codingRate = state.codingRate,
                txPower = state.txPower,
                stAlock = state.stAlock,
                ltAlock = state.ltAlock,
                region = state.selectedFrequencyRegion,
            )
        }

        /**
         * Validate configuration with error messages.
         */
        private fun validateConfiguration(): Boolean {
            val state = _state.value
            val result =
                RNodeConfigValidator.validateConfig(
                    name = state.interfaceName,
                    frequency = state.frequency,
                    bandwidth = state.bandwidth,
                    spreadingFactor = state.spreadingFactor,
                    codingRate = state.codingRate,
                    txPower = state.txPower,
                    stAlock = state.stAlock,
                    ltAlock = state.ltAlock,
                    region = state.selectedFrequencyRegion,
                )

            _state.update {
                it.copy(
                    nameError = result.nameError,
                    frequencyError = result.frequencyError,
                    bandwidthError = result.bandwidthError,
                    spreadingFactorError = result.spreadingFactorError,
                    codingRateError = result.codingRateError,
                    txPowerError = result.txPowerError,
                    stAlockError = result.stAlockError,
                    ltAlockError = result.ltAlockError,
                )
            }

            return result.isValid
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun saveConfiguration() {
            if (!validateConfiguration()) return

            viewModelScope.launch {
                _state.update { it.copy(isSaving = true, saveError = null) }

                try {
                    val state = _state.value

                    // Check for duplicate interface names before saving
                    var existingNames = interfaceRepository.allInterfaces.first().map { it.name }

                    // When editing, exclude the original interface from duplicate check
                    if (state.editingInterfaceId != null) {
                        val originalInterface =
                            interfaceRepository.getInterfaceById(state.editingInterfaceId).first()
                        originalInterface?.name?.let { originalName ->
                            existingNames =
                                existingNames.filter {
                                    !it.equals(originalName, ignoreCase = true)
                                }
                        }
                    }

                    when (
                        val uniqueResult =
                            InputValidator.validateInterfaceNameUniqueness(
                                state.interfaceName,
                                existingNames,
                            )
                    ) {
                        is ValidationResult.Error -> {
                            _state.update { it.copy(nameError = uniqueResult.message, isSaving = false) }
                            return@launch
                        }
                        is ValidationResult.Success -> { /* Name is unique, continue */ }
                    }

                    // Determine connection parameters based on connection type
                    val deviceName: String
                    val connectionMode: String
                    val tcpHost: String?
                    val tcpPort: Int
                    val usbDeviceId: Int?
                    val usbVendorId: Int?
                    val usbProductId: Int?

                    when (state.connectionType) {
                        RNodeConnectionType.TCP_WIFI -> {
                            // TCP/WiFi mode
                            deviceName = ""
                            connectionMode = "tcp"
                            tcpHost = state.tcpHost.trim()
                            tcpPort = state.tcpPort.toIntOrNull() ?: 7633
                            usbDeviceId = null
                            usbVendorId = null
                            usbProductId = null
                        }
                        RNodeConnectionType.USB_SERIAL -> {
                            // USB Serial mode
                            deviceName = ""
                            connectionMode = "usb"
                            tcpHost = null
                            tcpPort = 7633
                            usbDeviceId = state.selectedUsbDevice?.deviceId
                            usbVendorId = state.selectedUsbDevice?.vendorId
                            usbProductId = state.selectedUsbDevice?.productId
                        }
                        RNodeConnectionType.BLUETOOTH -> {
                            // Bluetooth mode
                            val (name, mode) =
                                if (state.selectedDevice != null) {
                                    state.selectedDevice.name to
                                        when (state.selectedDevice.type) {
                                            BluetoothType.CLASSIC -> "classic"
                                            BluetoothType.BLE -> "ble"
                                            BluetoothType.UNKNOWN -> "classic"
                                        }
                                } else {
                                    state.manualDeviceName to
                                        when (state.manualBluetoothType) {
                                            BluetoothType.CLASSIC -> "classic"
                                            BluetoothType.BLE -> "ble"
                                            BluetoothType.UNKNOWN -> "classic"
                                        }
                                }
                            deviceName = name
                            connectionMode = mode
                            tcpHost = null
                            tcpPort = 7633
                            usbDeviceId = null
                            usbVendorId = null
                            usbProductId = null
                        }
                    }

                    val config =
                        InterfaceConfig.RNode(
                            name = state.interfaceName.trim(),
                            enabled = true,
                            targetDeviceName = deviceName,
                            connectionMode = connectionMode,
                            tcpHost = tcpHost,
                            tcpPort = tcpPort,
                            usbDeviceId = usbDeviceId,
                            usbVendorId = usbVendorId,
                            usbProductId = usbProductId,
                            frequency = state.frequency.toLongOrNull() ?: 915000000,
                            bandwidth = state.bandwidth.toIntOrNull() ?: 125000,
                            txPower = state.txPower.toIntOrNull() ?: 7,
                            spreadingFactor = state.spreadingFactor.toIntOrNull() ?: 8,
                            codingRate = state.codingRate.toIntOrNull() ?: 5,
                            stAlock = state.stAlock.toDoubleOrNull(),
                            ltAlock = state.ltAlock.toDoubleOrNull(),
                            mode = state.interfaceMode,
                            enableFramebuffer = state.enableFramebuffer,
                        )

                    if (state.editingInterfaceId != null) {
                        // Update existing interface
                        interfaceRepository.updateInterface(state.editingInterfaceId, config)
                        Log.d(TAG, "Updated RNode interface: ${state.editingInterfaceId}")
                    } else {
                        // Insert new interface
                        interfaceRepository.insertInterface(config)
                        Log.d(TAG, "Created new RNode interface")
                    }

                    // Mark pending changes for InterfaceManagementScreen to show "Apply" button
                    configManager.setPendingChanges(true)

                    _state.update { it.copy(saveSuccess = true, isSaving = false) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save configuration", e)
                    _state.update {
                        it.copy(
                            saveError = e.message ?: "Failed to save configuration",
                            isSaving = false,
                        )
                    }
                }
            }
        }

        fun clearSaveError() {
            _state.update { it.copy(saveError = null) }
        }

        /**
         * Get the effective device name for display.
         * For TCP mode, shows the host:port. For USB, shows USB device name. For Bluetooth, shows device name.
         */
        fun getEffectiveDeviceName(): String {
            val state = _state.value
            return when (state.connectionType) {
                RNodeConnectionType.TCP_WIFI -> {
                    val port = state.tcpPort.toIntOrNull() ?: 7633
                    if (state.tcpHost.isNotBlank()) {
                        if (port == 7633) state.tcpHost else "${state.tcpHost}:$port"
                    } else {
                        "No host specified"
                    }
                }
                RNodeConnectionType.USB_SERIAL -> {
                    state.selectedUsbDevice?.let { device ->
                        // Prefer product name, fall back to manufacturer, then driver type
                        device.productName
                            ?: device.manufacturerName?.let { "$it Device" }
                            ?: device.driverType
                    } ?: "No USB device selected"
                }
                RNodeConnectionType.BLUETOOTH -> {
                    state.selectedDevice?.name
                        ?: state.manualDeviceName.ifBlank { "No device selected" }
                }
            }
        }

        /**
         * Get the effective Bluetooth type for display.
         * Returns null for TCP mode or USB mode.
         */
        fun getEffectiveBluetoothType(): BluetoothType? {
            val state = _state.value
            return when (state.connectionType) {
                RNodeConnectionType.TCP_WIFI -> null
                RNodeConnectionType.USB_SERIAL -> null
                RNodeConnectionType.BLUETOOTH -> state.selectedDevice?.type ?: state.manualBluetoothType
            }
        }

        /**
         * Get the connection type string for display.
         */
        fun getConnectionTypeString(): String {
            val state = _state.value
            return when (state.connectionType) {
                RNodeConnectionType.TCP_WIFI -> "WiFi / TCP"
                RNodeConnectionType.USB_SERIAL -> "USB Serial"
                RNodeConnectionType.BLUETOOTH -> {
                    when (state.selectedDevice?.type ?: state.manualBluetoothType) {
                        BluetoothType.CLASSIC -> "Bluetooth Classic"
                        BluetoothType.BLE -> "Bluetooth LE"
                        BluetoothType.UNKNOWN -> "Bluetooth"
                    }
                }
            }
        }

        /**
         * Check if currently in TCP/WiFi mode.
         */
        fun isTcpMode(): Boolean = _state.value.connectionType == RNodeConnectionType.TCP_WIFI

        /**
         * Check if currently in USB Serial mode.
         */
        fun isUsbMode(): Boolean = _state.value.connectionType == RNodeConnectionType.USB_SERIAL

        /**
         * Cleanup when ViewModel is cleared.
         * Cancels any ongoing polling jobs to prevent memory leaks.
         */
        override fun onCleared() {
            super.onCleared()
            stopRssiPolling()
            Log.d(TAG, "RNodeWizardViewModel cleared, RSSI polling stopped")
        }
    }
