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
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.CommunitySlots
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
}

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
    val interfaceName: String = "RNode LoRa",
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
)

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
            private const val MAX_DEVICE_NAME_LENGTH = 32 // Standard Bluetooth device name limit
            private const val TCP_CONNECTION_TIMEOUT_MS = 5000 // 5 second TCP connection timeout
        }

        private val _state = MutableStateFlow(RNodeWizardState())
        val state: StateFlow<RNodeWizardState> = _state.asStateFlow()

        private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

        // RSSI update throttling - track last update time per device
        private val lastRssiUpdate = mutableMapOf<String, Long>()

        // RSSI polling for connected RNode (edit mode)
        private var rssiPollingJob: Job? = null

        // Device type cache - persists detected BLE vs Classic types
        private val deviceTypePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Track user-modified fields to preserve state during navigation
        // When user explicitly modifies a field, we don't overwrite it with region defaults
        private val userModifiedFields = mutableSetOf<String>()

        /**
         * Get cached Bluetooth type for a device address.
         */
        private fun getCachedDeviceType(address: String): BluetoothType? {
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

        /**
         * Cache the Bluetooth type for a device address.
         */
        private fun cacheDeviceType(
            address: String,
            type: BluetoothType,
        ) {
            if (type == BluetoothType.UNKNOWN) return // Don't cache unknown
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

        // ========== INITIALIZATION ==========

        /**
         * Initialize wizard for editing an existing RNode interface.
         */
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

                    _state.update { state ->
                        state.copy(
                            editingInterfaceId = interfaceId,
                            isEditMode = true,
                            // Connection type
                            connectionType = if (isTcp) RNodeConnectionType.TCP_WIFI else RNodeConnectionType.BLUETOOTH,
                            // Pre-populate TCP fields (for TCP mode)
                            tcpHost = config.tcpHost ?: "",
                            tcpPort = config.tcpPort.toString(),
                            // Pre-populate device (for Bluetooth mode)
                            selectedDevice =
                                if (isTcp) {
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
            rssiPollingJob =
                viewModelScope.launch {
                    while (true) {
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

        // ========== NAVIGATION ==========

        fun goToStep(step: WizardStep) {
            _state.update { it.copy(currentStep = step) }
        }

        fun goToNextStep() {
            val currentState = _state.value
            val nextStep =
                when (currentState.currentStep) {
                    WizardStep.DEVICE_DISCOVERY -> {
                        // Stop RSSI polling when leaving device discovery to prevent memory leaks
                        stopRssiPolling()
                        WizardStep.REGION_SELECTION
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
                        if (currentState.isCustomMode || currentState.selectedPreset != null) {
                            // Custom mode or preset: go back to region selection (skipping modem and slot)
                            WizardStep.REGION_SELECTION
                        } else {
                            WizardStep.FREQUENCY_SLOT
                        }
                }
            _state.update { it.copy(currentStep = prevStep) }
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
                    cacheDeviceType(bleDevice.address, BluetoothType.BLE)
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
                    if (device.name?.startsWith("RNode ") == true) {
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

            if (bleDeviceAddresses.contains(address)) {
                // Found in BLE scan - definitely BLE, update paired status
                devices[address]?.let { existing ->
                    devices[address] = existing.copy(isPaired = true)
                }
            } else {
                // Use cached type or mark as unknown
                val cachedType = getCachedDeviceType(address)
                val deviceType = cachedType ?: BluetoothType.UNKNOWN

                devices[address] =
                    DiscoveredRNode(
                        name = name,
                        address = address,
                        type = deviceType,
                        rssi = null,
                        isPaired = true,
                        bluetoothDevice = device,
                    )

                if (cachedType != null) {
                    Log.d(TAG, "Using cached type for $name: $cachedType")
                } else {
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
            cacheDeviceType(device.address, type)
            val updatedDevice = device.copy(type = type)
            _state.update { state ->
                val newSelected =
                    if (state.selectedDevice?.address == device.address) {
                        updatedDevice
                    } else {
                        state.selectedDevice
                    }
                state.copy(
                    discoveredDevices =
                        state.discoveredDevices.map {
                            if (it.address == device.address) updatedDevice else it
                        },
                    selectedDevice = newSelected,
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
            val now = System.currentTimeMillis()
            val lastUpdate = lastRssiUpdate[address] ?: 0L
            if (now - lastUpdate < RSSI_UPDATE_INTERVAL_MS) return

            lastRssiUpdate[address] = now
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

                            // Device is now associated - select it
                            _state.update {
                                it.copy(
                                    selectedDevice = device,
                                    isAssociating = false,
                                    pendingAssociationIntent = null,
                                    showManualEntry = false,
                                )
                            }
                            // Cache the device type since it's now confirmed
                            cacheDeviceType(device.address, device.type)
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
            return when {
                name.length > MAX_DEVICE_NAME_LENGTH ->
                    "Device name must be $MAX_DEVICE_NAME_LENGTH characters or less" to null
                name.isNotBlank() && !name.startsWith("RNode", ignoreCase = true) ->
                    null to "Device may not be an RNode. Proceed with caution."
                else -> null to null
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
                it.copy(
                    connectionType = type,
                    // Clear Bluetooth selection when switching to TCP
                    selectedDevice = if (type == RNodeConnectionType.TCP_WIFI) null else it.selectedDevice,
                    // Clear TCP validation when switching to Bluetooth
                    tcpValidationSuccess = if (type == RNodeConnectionType.BLUETOOTH) null else it.tcpValidationSuccess,
                    tcpValidationError = if (type == RNodeConnectionType.BLUETOOTH) null else it.tcpValidationError,
                )
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
                    val existingNames = interfaceRepository.allInterfaces.first().map { it.name }
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
                    val isTcpMode = state.connectionType == RNodeConnectionType.TCP_WIFI

                    val deviceName: String
                    val connectionMode: String
                    val tcpHost: String?
                    val tcpPort: Int

                    if (isTcpMode) {
                        // TCP/WiFi mode
                        deviceName = ""
                        connectionMode = "tcp"
                        tcpHost = state.tcpHost.trim()
                        tcpPort = state.tcpPort.toIntOrNull() ?: 7633
                    } else {
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
                    }

                    val config =
                        InterfaceConfig.RNode(
                            name = state.interfaceName.trim(),
                            enabled = true,
                            targetDeviceName = deviceName,
                            connectionMode = connectionMode,
                            tcpHost = tcpHost,
                            tcpPort = tcpPort,
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
         * For TCP mode, shows the host:port. For Bluetooth, shows device name.
         */
        fun getEffectiveDeviceName(): String {
            val state = _state.value
            return if (state.connectionType == RNodeConnectionType.TCP_WIFI) {
                val port = state.tcpPort.toIntOrNull() ?: 7633
                if (state.tcpHost.isNotBlank()) {
                    if (port == 7633) state.tcpHost else "${state.tcpHost}:$port"
                } else {
                    "No host specified"
                }
            } else {
                state.selectedDevice?.name
                    ?: state.manualDeviceName.ifBlank { "No device selected" }
            }
        }

        /**
         * Get the effective Bluetooth type for display.
         * Returns null for TCP mode.
         */
        fun getEffectiveBluetoothType(): BluetoothType? {
            val state = _state.value
            return if (state.connectionType == RNodeConnectionType.TCP_WIFI) {
                null
            } else {
                state.selectedDevice?.type ?: state.manualBluetoothType
            }
        }

        /**
         * Check if currently in TCP/WiFi mode.
         */
        fun isTcpMode(): Boolean = _state.value.connectionType == RNodeConnectionType.TCP_WIFI

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
