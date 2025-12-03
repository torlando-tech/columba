package com.lxmf.messenger.viewmodel

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.repository.BleStatusRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State for the interface management screen.
 */
@androidx.compose.runtime.Immutable
data class InterfaceManagementState(
    val interfaces: List<InterfaceEntity> = emptyList(),
    val enabledCount: Int = 0,
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val editingInterface: InterfaceEntity? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val hasPendingChanges: Boolean = false,
    val isApplyingChanges: Boolean = false,
    val applyChangesError: String? = null,
    val showBlePermissionRequest: Boolean = false,
    // Bluetooth state for BLE interface management
    val bluetoothState: Int = BluetoothAdapter.STATE_ON, // Assume on initially
    val blePermissionsGranted: Boolean = false,
    // Info message for transient notifications (lighter than error/success)
    val infoMessage: String? = null,
)

/**
 * State for the interface configuration dialog.
 */
@androidx.compose.runtime.Immutable
data class InterfaceConfigState(
    val name: String = "",
    val type: String = "AutoInterface",
    val enabled: Boolean = true,
    // AutoInterface fields
    val groupId: String = "",
    val discoveryScope: String = "link",
    val discoveryPort: String = "",
    val dataPort: String = "",
    // TCPClient fields
    val targetHost: String = "",
    val targetPort: String = "4242",
    val networkName: String = "",
    val passphrase: String = "",
    val passphraseVisible: Boolean = false,
    // AndroidBLE fields
    val deviceName: String = "",
    val maxConnections: String = "7",
    // RNode fields
    val targetDeviceName: String = "",
    val connectionMode: String = "classic", // "classic" or "ble"
    val frequency: String = "915000000", // Hz
    val bandwidth: String = "125000", // Hz
    val txPower: String = "7", // dBm
    val spreadingFactor: String = "7",
    val codingRate: String = "5",
    val stAlock: String = "", // Short-term airtime limit % (optional)
    val ltAlock: String = "", // Long-term airtime limit % (optional)
    // Common fields
    val mode: String = "roaming",
    // Validation
    val nameError: String? = null,
    val targetHostError: String? = null,
    val targetPortError: String? = null,
    val discoveryPortError: String? = null,
    val dataPortError: String? = null,
    val deviceNameError: String? = null,
    val maxConnectionsError: String? = null,
    val targetDeviceNameError: String? = null,
    val frequencyError: String? = null,
    val bandwidthError: String? = null,
    val txPowerError: String? = null,
    val spreadingFactorError: String? = null,
    val codingRateError: String? = null,
)

/**
 * ViewModel for managing Reticulum network interface configurations.
 */
@HiltViewModel
class InterfaceManagementViewModel
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
        private val configManager: InterfaceConfigManager,
        private val bleStatusRepository: BleStatusRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "InterfaceMgmtVM"
        }

        private val _state = MutableStateFlow(InterfaceManagementState())
        val state: StateFlow<InterfaceManagementState> = _state.asStateFlow()

        private val _configState = MutableStateFlow(InterfaceConfigState())
        val configState: StateFlow<InterfaceConfigState> = _configState.asStateFlow()

        // Track previous Bluetooth state for transition detection
        private var previousBluetoothState: Int = BluetoothAdapter.STATE_ON

        init {
            Log.d(TAG, "ViewModel initialized")
            loadInterfaces()
            observeBluetoothState()
        }

        /**
         * Load all interfaces from the database.
         */
        private fun loadInterfaces() {
            viewModelScope.launch {
                combine(
                    interfaceRepository.allInterfaceEntities,
                    interfaceRepository.enabledInterfaceCount,
                    interfaceRepository.totalInterfaceCount,
                ) { interfaces, enabledCount, totalCount ->
                    _state.value.copy(
                        interfaces = interfaces,
                        enabledCount = enabledCount,
                        totalCount = totalCount,
                        isLoading = false,
                    )
                }.collect { newState ->
                    _state.value = newState
                }
            }
        }

        /**
         * Observe Bluetooth adapter state changes to update UI accordingly.
         */
        private fun observeBluetoothState() {
            Log.d(TAG, "Starting Bluetooth state observation")
            viewModelScope.launch {
                bleStatusRepository.getConnectedPeersFlow().collect { bleState ->
                    Log.d(TAG, "BLE state received: $bleState")
                    val adapterState =
                        when (bleState) {
                            is BleConnectionsState.BluetoothDisabled -> {
                                Log.d(TAG, "Bluetooth is DISABLED")
                                BluetoothAdapter.STATE_OFF
                            }
                            is BleConnectionsState.Loading -> {
                                Log.d(TAG, "Bluetooth is TURNING ON")
                                BluetoothAdapter.STATE_TURNING_ON
                            }
                            is BleConnectionsState.Success -> {
                                Log.d(TAG, "Bluetooth is ON")
                                BluetoothAdapter.STATE_ON
                            }
                            is BleConnectionsState.Error -> {
                                Log.d(TAG, "BLE error (assuming BT is ON): ${bleState.message}")
                                BluetoothAdapter.STATE_ON
                            }
                            else -> {
                                Log.d(TAG, "Unknown BLE state (assuming BT is ON)")
                                BluetoothAdapter.STATE_ON
                            }
                        }

                    Log.d(TAG, "Setting adapter state to: $adapterState, permissions: ${_state.value.blePermissionsGranted}")
                    // Update state
                    _state.value = _state.value.copy(bluetoothState = adapterState)

                    // Handle state transitions
                    handleBluetoothStateChange(adapterState)
                }
            }
        }

        /**
         * Handle Bluetooth state changes and show appropriate notifications.
         */
        private fun handleBluetoothStateChange(newState: Int) {
            // Check if we have any enabled BLE interfaces
            val hasEnabledBleInterface =
                _state.value.interfaces.any { iface ->
                    iface.enabled && iface.type == "AndroidBLE"
                }

            // Only show notifications if there are enabled BLE interfaces
            if (!hasEnabledBleInterface) {
                previousBluetoothState = newState
                return
            }

            // Detect transitions
            when {
                // Bluetooth turned off while it was on
                previousBluetoothState == BluetoothAdapter.STATE_ON &&
                    newState == BluetoothAdapter.STATE_OFF -> {
                    showInfo("Bluetooth turned off - BLE interface paused")
                }

                // Bluetooth turned back on while it was off
                previousBluetoothState == BluetoothAdapter.STATE_OFF &&
                    newState == BluetoothAdapter.STATE_ON -> {
                    showInfo("Bluetooth enabled - BLE interface resuming")
                }
            }

            previousBluetoothState = newState
        }

        /**
         * Show the add interface dialog.
         */
        fun showAddDialog() {
            _configState.value = InterfaceConfigState() // Reset to defaults
            _state.value =
                _state.value.copy(
                    showAddDialog = true,
                    editingInterface = null,
                )
        }

        /**
         * Show the edit interface dialog.
         */
        fun showEditDialog(interfaceEntity: InterfaceEntity) {
            _configState.value = entityToConfigState(interfaceEntity)
            _state.value =
                _state.value.copy(
                    showAddDialog = true,
                    editingInterface = interfaceEntity,
                )
        }

        /**
         * Hide the add/edit dialog.
         */
        fun hideDialog() {
            _state.value =
                _state.value.copy(
                    showAddDialog = false,
                    editingInterface = null,
                )
            _configState.value = InterfaceConfigState() // Reset
        }

        /**
         * Update the configuration state.
         */
        fun updateConfigState(update: (InterfaceConfigState) -> InterfaceConfigState) {
            _configState.value = update(_configState.value)
        }

        /**
         * Save the current interface configuration.
         */
        fun saveInterface() {
            if (!validateConfig()) {
                return
            }

            viewModelScope.launch {
                try {
                    val config = configStateToInterfaceConfig(_configState.value)
                    val editingId = _state.value.editingInterface?.id

                    if (editingId != null) {
                        // Update existing interface
                        interfaceRepository.updateInterface(editingId, config)
                        showSuccess("Interface updated successfully")
                    } else {
                        // Insert new interface
                        interfaceRepository.insertInterface(config)
                        showSuccess("Interface added successfully")
                    }

                    // Mark that there are pending changes
                    _state.value = _state.value.copy(hasPendingChanges = true)

                    hideDialog()
                } catch (e: Exception) {
                    showError("Failed to save interface: ${e.message}")
                }
            }
        }

        /**
         * Delete an interface.
         */
        fun deleteInterface(id: Long) {
            viewModelScope.launch {
                try {
                    interfaceRepository.deleteInterface(id)
                    showSuccess("Interface deleted successfully")

                    // Mark that there are pending changes
                    _state.value = _state.value.copy(hasPendingChanges = true)
                } catch (e: Exception) {
                    showError("Failed to delete interface: ${e.message}")
                }
            }
        }

        /**
         * Toggle the enabled state of an interface.
         * For AndroidBLE interfaces, this will trigger a permission check if enabling.
         *
         * @param id Interface ID
         * @param enabled Target enabled state
         * @param hasPermissions Whether BLE permissions are granted (only checked for AndroidBLE)
         */
        fun toggleInterface(
            id: Long,
            enabled: Boolean,
            hasPermissions: Boolean = true,
        ) {
            viewModelScope.launch {
                try {
                    // Check if this is an AndroidBLE interface and we're enabling it
                    if (enabled) {
                        val interfaceEntity = _state.value.interfaces.find { it.id == id }
                        val config = interfaceEntity?.let { interfaceRepository.entityToConfig(it) }

                        if (config is InterfaceConfig.AndroidBLE && !hasPermissions) {
                            // Request permissions from UI
                            _state.value = _state.value.copy(showBlePermissionRequest = true)
                            return@launch
                        }
                    }

                    interfaceRepository.toggleInterfaceEnabled(id, enabled)

                    // Mark that there are pending changes
                    _state.value = _state.value.copy(hasPendingChanges = true)
                } catch (e: Exception) {
                    showError("Failed to toggle interface: ${e.message}")
                }
            }
        }

        /**
         * Dismiss the BLE permission request.
         */
        fun dismissBlePermissionRequest() {
            _state.value = _state.value.copy(showBlePermissionRequest = false)
        }

        /**
         * Update the BLE permissions granted state.
         * Called when permissions are checked or changed.
         */
        fun updateBlePermissions(granted: Boolean) {
            Log.d(TAG, "Updating BLE permissions: granted=$granted")
            _state.value = _state.value.copy(blePermissionsGranted = granted)
            Log.d(TAG, "State updated - BT state: ${_state.value.bluetoothState}, permissions: ${_state.value.blePermissionsGranted}")
        }

        /**
         * Clear error message.
         */
        fun clearError() {
            _state.value = _state.value.copy(errorMessage = null)
        }

        /**
         * Clear success message.
         */
        fun clearSuccess() {
            _state.value = _state.value.copy(successMessage = null)
        }

        /**
         * Clear info message.
         */
        fun clearInfo() {
            _state.value = _state.value.copy(infoMessage = null)
        }

        /**
         * Update network name for TCP interface.
         */
        fun updateNetworkName(value: String) {
            _configState.update { it.copy(networkName = value) }
        }

        /**
         * Update passphrase for TCP interface.
         */
        fun updatePassphrase(value: String) {
            _configState.update { it.copy(passphrase = value) }
        }

        /**
         * Toggle passphrase visibility.
         */
        fun togglePassphraseVisibility() {
            _configState.update { it.copy(passphraseVisible = !it.passphraseVisible) }
        }

        /**
         * Show error message.
         */
        private fun showError(message: String) {
            _state.value = _state.value.copy(errorMessage = message)
        }

        /**
         * Show success message.
         */
        private fun showSuccess(message: String) {
            _state.value = _state.value.copy(successMessage = message)
        }

        /**
         * Show info message (for transient notifications like BT state changes).
         */
        private fun showInfo(message: String) {
            _state.value = _state.value.copy(infoMessage = message)
        }

        /**
         * Validate the current configuration using InputValidator.
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        private fun validateConfig(): Boolean {
            val config = _configState.value
            var isValid = true

            // VALIDATION: Validate interface name with InputValidator
            when (val nameResult = InputValidator.validateInterfaceName(config.name)) {
                is ValidationResult.Error -> {
                    _configState.value = config.copy(nameError = nameResult.message)
                    isValid = false
                }
                is ValidationResult.Success -> {
                    _configState.value = config.copy(nameError = null)
                }
            }

            // Type-specific validation
            when (config.type) {
                "TCPClient" -> {
                    // VALIDATION: Validate target host with proper hostname/IP validation
                    when (val hostResult = InputValidator.validateHostname(config.targetHost)) {
                        is ValidationResult.Error -> {
                            _configState.value = _configState.value.copy(targetHostError = hostResult.message)
                            isValid = false
                        }
                        is ValidationResult.Success -> {
                            _configState.value = _configState.value.copy(targetHostError = null)
                        }
                    }

                    // VALIDATION: Validate target port with proper range checking
                    when (val portResult = InputValidator.validatePort(config.targetPort)) {
                        is ValidationResult.Error -> {
                            _configState.value = _configState.value.copy(targetPortError = portResult.message)
                            isValid = false
                        }
                        is ValidationResult.Success -> {
                            _configState.value = _configState.value.copy(targetPortError = null)
                        }
                    }
                }

                "AutoInterface" -> {
                    // VALIDATION: Validate discovery port (empty = use RNS default)
                    if (config.discoveryPort.isNotBlank()) {
                        when (val portResult = InputValidator.validatePort(config.discoveryPort)) {
                            is ValidationResult.Error -> {
                                _configState.value = _configState.value.copy(discoveryPortError = portResult.message)
                                isValid = false
                            }
                            is ValidationResult.Success -> {
                                _configState.value = _configState.value.copy(discoveryPortError = null)
                            }
                        }
                    } else {
                        _configState.value = _configState.value.copy(discoveryPortError = null)
                    }

                    // VALIDATION: Validate data port (empty = use RNS default)
                    if (config.dataPort.isNotBlank()) {
                        when (val portResult = InputValidator.validatePort(config.dataPort)) {
                            is ValidationResult.Error -> {
                                _configState.value = _configState.value.copy(dataPortError = portResult.message)
                                isValid = false
                            }
                            is ValidationResult.Success -> {
                                _configState.value = _configState.value.copy(dataPortError = null)
                            }
                        }
                    } else {
                        _configState.value = _configState.value.copy(dataPortError = null)
                    }
                }

                "AndroidBLE" -> {
                    // VALIDATION: Validate device name (optional, but if provided must be valid)
                    if (config.deviceName.isNotBlank()) {
                        when (val deviceNameResult = InputValidator.validateDeviceName(config.deviceName)) {
                            is ValidationResult.Error -> {
                                _configState.value = _configState.value.copy(deviceNameError = deviceNameResult.message)
                                isValid = false
                            }
                            is ValidationResult.Success -> {
                                _configState.value = _configState.value.copy(deviceNameError = null)
                            }
                        }
                    } else {
                        _configState.value = _configState.value.copy(deviceNameError = null)
                    }

                    // Validate max connections
                    val maxConn = config.maxConnections.toIntOrNull()
                    if (maxConn == null || maxConn !in 1..7) {
                        _configState.value = _configState.value.copy(maxConnectionsError = "Max connections must be 1-7")
                        isValid = false
                    } else {
                        _configState.value = _configState.value.copy(maxConnectionsError = null)
                    }
                }
            }

            return isValid
        }

        /**
         * Convert InterfaceEntity to InterfaceConfigState for editing.
         */
        private fun entityToConfigState(entity: InterfaceEntity): InterfaceConfigState {
            val config = interfaceRepository.entityToConfig(entity)
            return when (config) {
                is InterfaceConfig.AutoInterface ->
                    InterfaceConfigState(
                        name = config.name,
                        type = "AutoInterface",
                        enabled = config.enabled,
                        groupId = config.groupId,
                        discoveryScope = config.discoveryScope,
                        discoveryPort = config.discoveryPort?.toString() ?: "",
                        dataPort = config.dataPort?.toString() ?: "",
                        mode = config.mode,
                    )

                is InterfaceConfig.TCPClient ->
                    InterfaceConfigState(
                        name = config.name,
                        type = "TCPClient",
                        enabled = config.enabled,
                        targetHost = config.targetHost,
                        targetPort = config.targetPort.toString(),
                        networkName = config.networkName.orEmpty(),
                        passphrase = config.passphrase.orEmpty(),
                        mode = config.mode,
                    )

                is InterfaceConfig.AndroidBLE ->
                    InterfaceConfigState(
                        name = config.name,
                        type = "AndroidBLE",
                        enabled = config.enabled,
                        deviceName = config.deviceName,
                        maxConnections = config.maxConnections.toString(),
                        mode = config.mode,
                    )

                is InterfaceConfig.RNode ->
                    InterfaceConfigState(
                        name = config.name,
                        type = "RNode",
                        enabled = config.enabled,
                        targetDeviceName = config.targetDeviceName,
                        connectionMode = config.connectionMode,
                        frequency = config.frequency.toString(),
                        bandwidth = config.bandwidth.toString(),
                        txPower = config.txPower.toString(),
                        spreadingFactor = config.spreadingFactor.toString(),
                        codingRate = config.codingRate.toString(),
                        stAlock = config.stAlock?.toString() ?: "",
                        ltAlock = config.ltAlock?.toString() ?: "",
                        mode = config.mode,
                    )

                else -> InterfaceConfigState() // Default for unsupported types
            }
        }

        /**
         * Convert InterfaceConfigState to InterfaceConfig for saving.
         */
        private fun configStateToInterfaceConfig(state: InterfaceConfigState): InterfaceConfig {
            return when (state.type) {
                "AutoInterface" ->
                    InterfaceConfig.AutoInterface(
                        name = state.name.trim(),
                        enabled = state.enabled,
                        groupId = state.groupId.trim(),
                        discoveryScope = state.discoveryScope,
                        discoveryPort = state.discoveryPort.takeIf { it.isNotBlank() }?.toIntOrNull(),
                        dataPort = state.dataPort.takeIf { it.isNotBlank() }?.toIntOrNull(),
                        mode = state.mode,
                    )

                "TCPClient" ->
                    InterfaceConfig.TCPClient(
                        name = state.name.trim(),
                        enabled = state.enabled,
                        targetHost = state.targetHost.trim(),
                        targetPort = state.targetPort.toIntOrNull() ?: 4242,
                        kissFraming = false, // Always false, removed from UI
                        mode = state.mode,
                        networkName = state.networkName.trim().ifEmpty { null },
                        passphrase = state.passphrase.ifEmpty { null },
                    )

                "AndroidBLE" ->
                    InterfaceConfig.AndroidBLE(
                        name = state.name.trim(),
                        enabled = state.enabled,
                        deviceName = state.deviceName.trim(),
                        maxConnections = state.maxConnections.toIntOrNull() ?: 7,
                        mode = state.mode,
                    )

                "RNode" ->
                    InterfaceConfig.RNode(
                        name = state.name.trim(),
                        enabled = state.enabled,
                        targetDeviceName = state.targetDeviceName.trim(),
                        connectionMode = state.connectionMode,
                        frequency = state.frequency.toLongOrNull() ?: 915000000,
                        bandwidth = state.bandwidth.toIntOrNull() ?: 125000,
                        txPower = state.txPower.toIntOrNull() ?: 7,
                        spreadingFactor = state.spreadingFactor.toIntOrNull() ?: 7,
                        codingRate = state.codingRate.toIntOrNull() ?: 5,
                        stAlock = state.stAlock.toDoubleOrNull(),
                        ltAlock = state.ltAlock.toDoubleOrNull(),
                        mode = state.mode,
                    )

                else -> throw IllegalArgumentException("Unsupported interface type: ${state.type}")
            }
        }

        /**
         * Apply pending interface configuration changes to the running Reticulum instance.
         *
         * This will:
         * 1. Shutdown Reticulum
         * 2. Regenerate config file from database
         * 3. Reinitialize Reticulum with new config
         * 4. Restore peer identities
         * 5. Restart message collector
         *
         * This operation typically takes 3-5 seconds.
         */
        fun applyChanges() {
            viewModelScope.launch {
                try {
                    _state.value =
                        _state.value.copy(
                            isApplyingChanges = true,
                            applyChangesError = null,
                        )

                    // Run on IO dispatcher to avoid blocking UI with IPC calls
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        configManager.applyInterfaceChanges()
                    }
                        .onSuccess {
                            _state.value =
                                _state.value.copy(
                                    hasPendingChanges = false,
                                    isApplyingChanges = false,
                                )
                            showSuccess("Configuration applied successfully")
                        }
                        .onFailure { error ->
                            _state.value =
                                _state.value.copy(
                                    isApplyingChanges = false,
                                    applyChangesError = error.message ?: "Failed to apply changes",
                                )
                        }
                } catch (e: Exception) {
                    // Catch any unexpected exceptions to ensure UI state is reset
                    _state.value =
                        _state.value.copy(
                            isApplyingChanges = false,
                            applyChangesError = e.message ?: "Unexpected error occurred",
                        )
                }
            }
        }

        /**
         * Clear the apply changes error message.
         */
        fun clearApplyError() {
            _state.value = _state.value.copy(applyChangesError = null)
        }
    }
