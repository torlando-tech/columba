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
import java.util.regex.Pattern
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.CommunitySlots
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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
    val isScanning: Boolean = false,
    val discoveredDevices: List<DiscoveredRNode> = emptyList(),
    val selectedDevice: DiscoveredRNode? = null,
    val scanError: String? = null,
    val showManualEntry: Boolean = false,
    val manualDeviceName: String = "",
    val manualBluetoothType: BluetoothType = BluetoothType.CLASSIC,
    val isPairingInProgress: Boolean = false,
    val pairingError: String? = null,
    val pairingTimeRemaining: Int = 0,
    val lastPairingDeviceAddress: String? = null,

    // Companion Device Association (Android 12+)
    val isAssociating: Boolean = false,
    val pendingAssociationIntent: IntentSender? = null,
    val associationError: String? = null,

    // Step 2: Region/Frequency Selection
    val searchQuery: String = "",
    val selectedCountry: String? = null,
    val selectedPreset: RNodeRegionalPreset? = null,  // Legacy: popular local presets
    val selectedFrequencyRegion: FrequencyRegion? = null,  // New: frequency band selection
    val isCustomMode: Boolean = false,
    val showPopularPresets: Boolean = false,  // Collapsible section for local presets

    // Step 3: Modem Preset Selection
    val selectedModemPreset: ModemPreset = ModemPreset.DEFAULT,

    // Step 4: Frequency Slot Selection
    val selectedSlot: Int = 20,  // Default Meshtastic slot
    val customFrequency: Long? = null,  // Set when a preset is selected that doesn't align with slots
    val selectedSlotPreset: RNodeRegionalPreset? = null,  // The preset selected on slot page

    // Step 5: Review & Configure
    val interfaceName: String = "RNode LoRa",
    val frequency: String = "914875000",  // US default
    val bandwidth: String = "250000",     // Long Fast default
    val spreadingFactor: String = "11",   // Long Fast default
    val codingRate: String = "5",         // Long Fast default (4/5)
    val txPower: String = "17",           // Safe default for all devices
    val stAlock: String = "",
    val ltAlock: String = "",
    val interfaceMode: String = "full",
    val showAdvancedSettings: Boolean = false,
    val enableFramebuffer: Boolean = true, // Display logo on RNode OLED

    // Validation errors
    val nameError: String? = null,
    val frequencyError: String? = null,
    val bandwidthError: String? = null,
    val txPowerError: String? = null,
    val spreadingFactorError: String? = null,
    val codingRateError: String? = null,
    val stAlockError: String? = null,
    val ltAlockError: String? = null,

    // Save state
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
)

/**
 * ViewModel for the RNode setup wizard.
 */
@HiltViewModel
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
            private const val RSSI_UPDATE_INTERVAL_MS = 3000L // Update RSSI every 3s
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
        private fun cacheDeviceType(address: String, type: BluetoothType) {
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
                    val matchingPreset = RNodeRegionalPresets.findMatchingPreset(
                        config.frequency,
                        config.bandwidth,
                        config.spreadingFactor,
                    )

                    val isBle = config.connectionMode == "ble"

                    _state.update { state ->
                        state.copy(
                            editingInterfaceId = interfaceId,
                            isEditMode = true,
                            // Pre-populate device
                            selectedDevice = DiscoveredRNode(
                                name = config.targetDeviceName,
                                address = "",
                                type = if (isBle) BluetoothType.BLE else BluetoothType.CLASSIC,
                                rssi = null,
                                isPaired = true,
                            ),
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

                    // Start RSSI polling for BLE devices
                    if (isBle) {
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
            rssiPollingJob = viewModelScope.launch {
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
            val nextStep = when (currentState.currentStep) {
                WizardStep.DEVICE_DISCOVERY -> WizardStep.REGION_SELECTION
                WizardStep.REGION_SELECTION -> {
                    // Apply frequency region settings when moving to modem step
                    applyFrequencyRegionSettings()
                    WizardStep.MODEM_PRESET
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
            val prevStep = when (_state.value.currentStep) {
                WizardStep.DEVICE_DISCOVERY -> WizardStep.DEVICE_DISCOVERY // Already at start
                WizardStep.REGION_SELECTION -> WizardStep.DEVICE_DISCOVERY
                WizardStep.MODEM_PRESET -> WizardStep.REGION_SELECTION
                WizardStep.FREQUENCY_SLOT -> WizardStep.MODEM_PRESET
                WizardStep.REVIEW_CONFIGURE -> WizardStep.FREQUENCY_SLOT
            }
            _state.update { it.copy(currentStep = prevStep) }
        }

        fun canProceed(): Boolean {
            val state = _state.value
            return when (state.currentStep) {
                WizardStep.DEVICE_DISCOVERY ->
                    state.selectedDevice != null ||
                        (state.showManualEntry && state.manualDeviceName.isNotBlank())
                WizardStep.REGION_SELECTION ->
                    state.selectedFrequencyRegion != null // Must select a region
                WizardStep.MODEM_PRESET ->
                    state.selectedModemPreset != null // Must select a modem preset
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
            val airtimeLimit = if (region.dutyCycle < 100) {
                region.dutyCycle.toDouble().toString()
            } else {
                "" // No limit
            }

            _state.update {
                it.copy(
                    frequency = region.frequency.toString(),
                    txPower = region.defaultTxPower.toString(),
                    stAlock = airtimeLimit,
                    ltAlock = airtimeLimit,
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
            val frequency = state.customFrequency ?: run {
                val bandwidth = state.selectedModemPreset.bandwidth
                FrequencySlotCalculator.calculateFrequency(region, bandwidth, state.selectedSlot)
            }
            _state.update { it.copy(frequency = frequency.toString()) }
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
            val countryCodes = when (region.id) {
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

                // Asia-Pacific 920 MHz bands
                "jp_920", "kr_920", "tw_920", "th_920", "sg_923", "my_919" ->
                    listOf("MY", "SG", "TH")

                // Philippines 915-918 MHz - no presets defined (different band from AS923)
                "ph_915" -> emptyList()

                else -> emptyList()
            }

            // Filter presets by country and exclude 433 MHz / 2.4 GHz presets from non-matching regions
            return RNodeRegionalPresets.presets
                .filter { it.countryCode in countryCodes }
                .filter { it.frequency !in 430_000_000..440_000_000 }  // Exclude 433 MHz
                .filter { it.frequency !in 2_400_000_000..2_500_000_000 }  // Exclude 2.4 GHz
                .take(5)
        }

        // ========== STEP 1: DEVICE DISCOVERY ==========

        @SuppressLint("MissingPermission")
        fun startDeviceScan() {
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
                // RNodes use EITHER Classic OR BLE, never both (determined by board type)
                try {
                    scanForBleRNodes { bleDevice ->
                        bleDeviceAddresses.add(bleDevice.address)
                        devices[bleDevice.address] = bleDevice
                        // Cache this device as BLE since we definitively detected it
                        cacheDeviceType(bleDevice.address, BluetoothType.BLE)
                        _state.update { it.copy(discoveredDevices = devices.values.toList()) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BLE scan failed", e)
                }

                // 2. Check bonded devices - classify based on BLE scan results and cache
                try {
                    bluetoothAdapter?.bondedDevices?.forEach { device ->
                        if (device.name?.startsWith("RNode ") == true) {
                            val address = device.address
                            val name = device.name ?: address

                            when {
                                // Found in BLE scan - definitely BLE, update paired status
                                bleDeviceAddresses.contains(address) -> {
                                    devices[address]?.let { existing ->
                                        devices[address] = existing.copy(isPaired = true)
                                    }
                                }
                                // Check cache for previously detected type
                                else -> {
                                    val cachedType = getCachedDeviceType(address)
                                    val deviceType = cachedType ?: BluetoothType.UNKNOWN

                                    devices[address] = DiscoveredRNode(
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
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing permission for bonded devices check", e)
                }

                // Update selectedDevice if we found a matching device during scan
                // This handles edit mode where device was loaded from config without RSSI/address
                val currentSelected = _state.value.selectedDevice
                val updatedSelected = if (currentSelected != null) {
                    // Try to find matching device by name (we may not have address from saved config)
                    devices.values.find { it.name == currentSelected.name }?.let { foundDevice ->
                        // Always use the scanned device - it has live RSSI and proper address
                        Log.d(TAG, "Updating selected device from scan: rssi=${foundDevice.rssi}")
                        foundDevice
                    } ?: currentSelected
                } else {
                    null
                }

                _state.update {
                    it.copy(
                        discoveredDevices = devices.values.toList(),
                        isScanning = false,
                        selectedDevice = updatedSelected,
                    )
                }

                if (devices.isEmpty()) {
                    _state.update {
                        it.copy(
                            scanError = "No RNode devices found. " +
                                "Make sure your RNode is powered on and Bluetooth is enabled.",
                        )
                    }
                }
            }
        }

        /**
         * Set the Bluetooth type for a device (user manual selection).
         * This caches the selection for future scans.
         */
        fun setDeviceType(device: DiscoveredRNode, type: BluetoothType) {
            cacheDeviceType(device.address, type)
            val updatedDevice = device.copy(type = type)
            _state.update { state ->
                val newSelected = if (state.selectedDevice?.address == device.address) {
                    updatedDevice
                } else {
                    state.selectedDevice
                }
                state.copy(
                    discoveredDevices = state.discoveredDevices.map {
                        if (it.address == device.address) updatedDevice else it
                    },
                    selectedDevice = newSelected,
                )
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun scanForBleRNodes(onDeviceFound: (DiscoveredRNode) -> Unit) {
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val foundDevices = mutableSetOf<String>()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
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
                }
            }

            try {
                scanner.startScan(listOf(filter), settings, callback)
                delay(SCAN_DURATION_MS)
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "BLE scan permission denied", e)
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
        private fun updateDeviceRssi(address: String, rssi: Int) {
            val now = System.currentTimeMillis()
            val lastUpdate = lastRssiUpdate[address] ?: 0L
            if (now - lastUpdate < RSSI_UPDATE_INTERVAL_MS) return

            lastRssiUpdate[address] = now
            _state.update { state ->
                // Update RSSI in discovered devices list
                val updatedDevices = state.discoveredDevices.map { device ->
                    if (device.address == address) device.copy(rssi = rssi) else device
                }
                // Also update selectedDevice if it matches
                val updatedSelected = state.selectedDevice?.let { selected ->
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
        fun requestDeviceAssociation(device: DiscoveredRNode, onFallback: () -> Unit) {
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
                    null, // Handler - use main thread
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
                val bleFilter = BluetoothLeDeviceFilter.Builder()
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
                val classicFilter = BluetoothDeviceFilter.Builder()
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
            _state.update { it.copy(manualDeviceName = name) }
        }

        fun updateManualBluetoothType(type: BluetoothType) {
            _state.update { it.copy(manualBluetoothType = type) }
        }

        // Pairing handler to auto-confirm Just Works pairing
        private var pairingHandler: BlePairingHandler? = null

        @SuppressLint("MissingPermission")
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
                    val btDevice = device.bluetoothDevice
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
                            // Pairing never started - device not in pairing mode
                            _state.update {
                                it.copy(
                                    pairingError = "RNode is not in pairing mode. Press the " +
                                        "pairing button until a PIN code appears on the display.",
                                )
                            }
                            return@launch
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
                                        discoveredDevices = state.discoveredDevices.map {
                                            if (it.address == device.address) updatedDevice else it
                                        },
                                    )
                                }
                                Log.d(TAG, "Pairing successful for ${device.name}")
                            }
                            BluetoothDevice.BOND_NONE -> {
                                _state.update {
                                    it.copy(
                                        pairingError = "Pairing was cancelled or the PIN was " +
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
                    // Apply preset values
                    frequency = preset.frequency.toString(),
                    bandwidth = preset.bandwidth.toString(),
                    spreadingFactor = preset.spreadingFactor.toString(),
                    codingRate = preset.codingRate.toString(),
                    txPower = preset.txPower.toString(),
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
        }

        // ========== FREQUENCY REGION SELECTION ==========

        fun selectFrequencyRegion(region: FrequencyRegion) {
            _state.update {
                it.copy(
                    selectedFrequencyRegion = region,
                    selectedPreset = null,  // Clear any popular preset selection
                    isCustomMode = false,
                )
            }
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
            val (minFreq, maxFreq) = getFrequencyRange()
            val freq = value.toLongOrNull()
            val error = when {
                value.isBlank() -> null // Allow empty while typing
                freq == null -> "Invalid number"
                freq < minFreq || freq > maxFreq -> {
                    val minMhz = minFreq / 1_000_000.0
                    val maxMhz = maxFreq / 1_000_000.0
                    "Must be %.1f-%.1f MHz".format(minMhz, maxMhz)
                }
                else -> null
            }
            _state.update { it.copy(frequency = value, frequencyError = error) }
        }

        fun updateBandwidth(value: String) {
            _state.update { it.copy(bandwidth = value, bandwidthError = null) }
        }

        fun updateSpreadingFactor(value: String) {
            _state.update { it.copy(spreadingFactor = value, spreadingFactorError = null) }
        }

        fun updateCodingRate(value: String) {
            _state.update { it.copy(codingRate = value, codingRateError = null) }
        }

        fun updateTxPower(value: String) {
            val maxPower = getMaxTxPower()
            val txp = value.toIntOrNull()
            val error = when {
                value.isBlank() -> null // Allow empty while typing
                txp == null -> "Invalid number"
                txp < 0 -> "Must be >= 0"
                txp > maxPower -> "Max: $maxPower dBm"
                else -> null
            }
            _state.update { it.copy(txPower = value, txPowerError = error) }
        }

        fun updateStAlock(value: String) {
            val maxAirtime = getMaxAirtimeLimit()
            val parsed = value.toDoubleOrNull()
            val error = when {
                value.isBlank() -> null // Empty is allowed (no limit)
                parsed == null -> "Invalid number"
                parsed < 0 -> "Must be >= 0"
                parsed > 100 -> "Must be <= 100%"
                maxAirtime != null && parsed > maxAirtime -> "Max: $maxAirtime% (regional limit)"
                else -> null
            }
            _state.update { it.copy(stAlock = value, stAlockError = error) }
        }

        fun updateLtAlock(value: String) {
            val maxAirtime = getMaxAirtimeLimit()
            val parsed = value.toDoubleOrNull()
            val error = when {
                value.isBlank() -> null // Empty is allowed (no limit)
                parsed == null -> "Invalid number"
                parsed < 0 -> "Must be >= 0"
                parsed > 100 -> "Must be <= 100%"
                maxAirtime != null && parsed > maxAirtime -> "Max: $maxAirtime% (regional limit)"
                else -> null
            }
            _state.update { it.copy(ltAlock = value, ltAlockError = error) }
        }

        /**
         * Get the maximum airtime limit for the current region.
         * Returns null if there's no limit (100% duty cycle).
         */
        private fun getMaxAirtimeLimit(): Double? {
            val region = _state.value.selectedFrequencyRegion ?: return null
            return if (region.dutyCycle < 100) region.dutyCycle.toDouble() else null
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
            return _state.value.selectedFrequencyRegion?.maxTxPower ?: 22
        }

        /**
         * Get the frequency range for the selected region (or default fallback).
         */
        private fun getFrequencyRange(): Pair<Long, Long> {
            val region = _state.value.selectedFrequencyRegion
            return if (region != null) {
                region.frequencyStart to region.frequencyEnd
            } else {
                137_000_000L to 3_000_000_000L
            }
        }

        /**
         * Validate configuration silently (without updating error messages).
         */
        private fun validateConfigurationSilent(): Boolean {
            val state = _state.value

            // Validate name
            if (state.interfaceName.isBlank()) return false

            // Validate frequency against region limits
            val freq = state.frequency.toLongOrNull()
            val (minFreq, maxFreq) = getFrequencyRange()
            if (freq == null || freq < minFreq || freq > maxFreq) return false

            // Validate bandwidth
            val bw = state.bandwidth.toIntOrNull()
            if (bw == null || bw < 7800 || bw > 1625000) return false

            // Validate spreading factor
            val sf = state.spreadingFactor.toIntOrNull()
            if (sf == null || sf < 5 || sf > 12) return false

            // Validate coding rate
            val cr = state.codingRate.toIntOrNull()
            if (cr == null || cr < 5 || cr > 8) return false

            // Validate TX power against region max
            val txp = state.txPower.toIntOrNull()
            val maxPower = getMaxTxPower()
            if (txp == null || txp < 0 || txp > maxPower) return false

            // Validate airtime limits against region duty cycle
            val maxAirtime = getMaxAirtimeLimit()
            if (state.stAlock.isNotBlank()) {
                val stAlock = state.stAlock.toDoubleOrNull()
                if (stAlock == null || stAlock < 0 || stAlock > 100) return false
                if (maxAirtime != null && stAlock > maxAirtime) return false
            }
            if (state.ltAlock.isNotBlank()) {
                val ltAlock = state.ltAlock.toDoubleOrNull()
                if (ltAlock == null || ltAlock < 0 || ltAlock > 100) return false
                if (maxAirtime != null && ltAlock > maxAirtime) return false
            }

            return true
        }

        /**
         * Validate configuration with error messages.
         */
        private fun validateConfiguration(): Boolean {
            var isValid = true
            val state = _state.value
            val region = state.selectedFrequencyRegion

            // Validate name
            if (state.interfaceName.isBlank()) {
                _state.update { it.copy(nameError = "Interface name is required") }
                isValid = false
            }

            // Validate frequency against region limits
            val freq = state.frequency.toLongOrNull()
            val (minFreq, maxFreq) = getFrequencyRange()
            if (freq == null || freq < minFreq || freq > maxFreq) {
                val minMhz = minFreq / 1_000_000.0
                val maxMhz = maxFreq / 1_000_000.0
                _state.update {
                    it.copy(frequencyError = "Frequency must be %.1f-%.1f MHz".format(minMhz, maxMhz))
                }
                isValid = false
            }

            // Validate bandwidth
            val bw = state.bandwidth.toIntOrNull()
            if (bw == null || bw < 7800 || bw > 1625000) {
                _state.update { it.copy(bandwidthError = "Bandwidth must be 7.8-1625 kHz") }
                isValid = false
            }

            // Validate spreading factor
            val sf = state.spreadingFactor.toIntOrNull()
            if (sf == null || sf < 5 || sf > 12) {
                _state.update { it.copy(spreadingFactorError = "SF must be 5-12") }
                isValid = false
            }

            // Validate coding rate
            val cr = state.codingRate.toIntOrNull()
            if (cr == null || cr < 5 || cr > 8) {
                _state.update { it.copy(codingRateError = "CR must be 5-8") }
                isValid = false
            }

            // Validate TX power against region's regulatory max
            val txp = state.txPower.toIntOrNull()
            val maxPower = getMaxTxPower()
            if (txp == null || txp < 0 || txp > maxPower) {
                val regionName = region?.name ?: "this region"
                _state.update {
                    it.copy(txPowerError = "TX power must be 0-$maxPower dBm for $regionName")
                }
                isValid = false
            }

            return isValid
        }

        fun saveConfiguration() {
            if (!validateConfiguration()) return

            viewModelScope.launch {
                _state.update { it.copy(isSaving = true, saveError = null) }

                try {
                    val state = _state.value

                    // Determine device name and connection mode
                    val (deviceName, connectionMode) = if (state.selectedDevice != null) {
                        state.selectedDevice.name to when (state.selectedDevice.type) {
                            BluetoothType.CLASSIC -> "classic"
                            BluetoothType.BLE -> "ble"
                            BluetoothType.UNKNOWN -> "classic" // Default to classic
                        }
                    } else {
                        state.manualDeviceName to when (state.manualBluetoothType) {
                            BluetoothType.CLASSIC -> "classic"
                            BluetoothType.BLE -> "ble"
                            BluetoothType.UNKNOWN -> "classic"
                        }
                    }

                    val config = InterfaceConfig.RNode(
                        name = state.interfaceName.trim(),
                        enabled = true,
                        targetDeviceName = deviceName,
                        connectionMode = connectionMode,
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
         */
        fun getEffectiveDeviceName(): String {
            val state = _state.value
            return state.selectedDevice?.name
                ?: state.manualDeviceName.ifBlank { "No device selected" }
        }

        /**
         * Get the effective Bluetooth type for display.
         */
        fun getEffectiveBluetoothType(): BluetoothType {
            val state = _state.value
            return state.selectedDevice?.type ?: state.manualBluetoothType
        }
    }
