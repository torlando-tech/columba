package com.lxmf.messenger.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.reticulum.flasher.FirmwarePackage
import com.lxmf.messenger.reticulum.flasher.FrequencyBand
import com.lxmf.messenger.reticulum.flasher.RNodeBoard
import com.lxmf.messenger.reticulum.flasher.RNodeDeviceInfo
import com.lxmf.messenger.reticulum.flasher.RNodeFlasher
import com.lxmf.messenger.reticulum.usb.UsbDeviceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Flasher wizard step enumeration.
 */
enum class FlasherStep {
    DEVICE_SELECTION,
    DEVICE_DETECTION,
    FIRMWARE_SELECTION,
    FLASH_PROGRESS,
    COMPLETE,
}

/**
 * Result of a flash operation.
 */
sealed class FlashResult {
    data class Success(val deviceInfo: RNodeDeviceInfo?) : FlashResult()

    data class Failure(val error: String) : FlashResult()

    data object Cancelled : FlashResult()
}

/**
 * State for the RNode flasher wizard UI.
 */
@androidx.compose.runtime.Immutable
data class FlasherUiState(
    // Wizard navigation
    val currentStep: FlasherStep = FlasherStep.DEVICE_SELECTION,
    // Step 1: Device Selection
    val connectedDevices: List<UsbDeviceInfo> = emptyList(),
    val selectedDevice: UsbDeviceInfo? = null,
    val isRefreshingDevices: Boolean = false,
    val permissionPending: Boolean = false,
    val permissionError: String? = null,
    val bootloaderMode: Boolean = false, // Skip detection for fresh devices in bootloader
    // Step 2: Device Detection
    val isDetecting: Boolean = false,
    val detectedInfo: RNodeDeviceInfo? = null,
    val detectionError: String? = null,
    val useManualBoardSelection: Boolean = false,
    // Step 3: Firmware Selection
    val selectedBoard: RNodeBoard? = null,
    val selectedBand: FrequencyBand = FrequencyBand.BAND_868_915,
    val availableFirmware: List<FirmwarePackage> = emptyList(),
    val selectedFirmware: FirmwarePackage? = null,
    val availableVersions: List<String> = emptyList(),
    val selectedVersion: String? = null,
    val isDownloadingFirmware: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadError: String? = null,
    // Step 4: Flash Progress
    val flashProgress: Int = 0,
    val flashMessage: String = "",
    val isFlashing: Boolean = false,
    val showCancelConfirmation: Boolean = false,
    // Step 4b: Waiting for manual reset (ESP32-S3 native USB)
    val needsManualReset: Boolean = false,
    val resetMessage: String? = null,
    // Step 4c: Provisioning
    val isProvisioning: Boolean = false,
    val provisioningMessage: String? = null,
    // Step 5: Complete
    val flashResult: FlashResult? = null,
    // General state
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for the RNode Flasher wizard.
 *
 * Manages the multi-step firmware flashing workflow:
 * 1. Device Selection - List and select USB devices
 * 2. Device Detection - Identify the RNode board type
 * 3. Firmware Selection - Choose firmware version and frequency band
 * 4. Flash Progress - Monitor flashing progress with cancel option
 * 5. Complete - Show result and next actions
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class FlasherViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        companion object {
            private const val TAG = "Columba:FlasherVM"
        }

        private val flasher = RNodeFlasher(context)

        private val _state = MutableStateFlow(FlasherUiState())
        val state: StateFlow<FlasherUiState> = _state.asStateFlow()

        // Flag to skip detection and go straight to manual board selection
        // Used when flashing fresh devices that are already in bootloader mode
        private var skipDetectionMode = false

        // Firmware hash from the flashed binary (used for provisioning)
        private var firmwareHashForProvisioning: ByteArray? = null

        init {
            // Observe flash state from the flasher
            observeFlashState()
            // Initial device scan
            refreshDevices()
        }

        /**
         * Enable skip detection mode for flashing fresh devices in bootloader mode.
         * When enabled, selecting a device will skip the detection step and go
         * directly to firmware selection with manual board selection.
         */
        fun enableSkipDetectionMode() {
            Log.d(TAG, "Skip detection mode enabled (for bootloader flashing)")
            skipDetectionMode = true
            _state.update { it.copy(bootloaderMode = true, useManualBoardSelection = true) }
        }

        /**
         * Toggle bootloader mode on/off.
         * When enabled, device detection is skipped and user must select board manually.
         * Also disables USB auto-navigation to prevent interfering with bootloader.
         */
        fun setBootloaderMode(enabled: Boolean) {
            Log.d(TAG, "Bootloader mode set to: $enabled")
            skipDetectionMode = enabled
            // Disable USB auto-navigation when bootloader mode is active
            com.lxmf.messenger.MainActivity.bootloaderFlashModeActive = enabled
            _state.update { it.copy(bootloaderMode = enabled, useManualBoardSelection = enabled) }
        }

        private fun observeFlashState() {
            viewModelScope.launch {
                flasher.flashState.collect { flashState ->
                    when (flashState) {
                        is RNodeFlasher.FlashState.Idle -> {
                            // Do nothing, maintain current UI state
                        }
                        is RNodeFlasher.FlashState.Detecting -> {
                            _state.update {
                                it.copy(
                                    isDetecting = true,
                                    flashMessage = flashState.message,
                                )
                            }
                        }
                        is RNodeFlasher.FlashState.Progress -> {
                            _state.update {
                                it.copy(
                                    flashProgress = flashState.percent,
                                    flashMessage = flashState.message,
                                    isFlashing = true,
                                    needsManualReset = false,
                                    isProvisioning = false,
                                )
                            }
                        }
                        is RNodeFlasher.FlashState.NeedsManualReset -> {
                            Log.i(TAG, "Device needs manual reset: ${flashState.message} (hash provided: ${flashState.firmwareHash != null})")
                            // Store the firmware hash for provisioning after reset
                            firmwareHashForProvisioning = flashState.firmwareHash
                            _state.update {
                                it.copy(
                                    isFlashing = false,
                                    needsManualReset = true,
                                    resetMessage = flashState.message,
                                    flashMessage = flashState.message,
                                )
                            }
                        }
                        is RNodeFlasher.FlashState.Provisioning -> {
                            _state.update {
                                it.copy(
                                    isFlashing = false,
                                    needsManualReset = false,
                                    isProvisioning = true,
                                    provisioningMessage = flashState.message,
                                    flashMessage = flashState.message,
                                )
                            }
                        }
                        is RNodeFlasher.FlashState.Complete -> {
                            _state.update {
                                it.copy(
                                    currentStep = FlasherStep.COMPLETE,
                                    isFlashing = false,
                                    needsManualReset = false,
                                    isProvisioning = false,
                                    flashResult = FlashResult.Success(flashState.deviceInfo),
                                )
                            }
                        }
                        is RNodeFlasher.FlashState.Error -> {
                            if (_state.value.currentStep == FlasherStep.DEVICE_DETECTION) {
                                _state.update {
                                    it.copy(
                                        isDetecting = false,
                                        detectionError = flashState.message,
                                    )
                                }
                            } else if (_state.value.currentStep == FlasherStep.FLASH_PROGRESS) {
                                _state.update {
                                    it.copy(
                                        currentStep = FlasherStep.COMPLETE,
                                        isFlashing = false,
                                        needsManualReset = false,
                                        isProvisioning = false,
                                        flashResult = FlashResult.Failure(flashState.message),
                                    )
                                }
                            } else {
                                _state.update {
                                    it.copy(
                                        error = flashState.message,
                                        isLoading = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==================== Step 1: Device Selection ====================

        fun refreshDevices() {
            _state.update { it.copy(isRefreshingDevices = true, permissionError = null) }
            viewModelScope.launch {
                try {
                    val devices = flasher.getConnectedDevices()
                    Log.d(TAG, "Found ${devices.size} USB devices")
                    _state.update {
                        it.copy(
                            connectedDevices = devices,
                            isRefreshingDevices = false,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh devices", e)
                    _state.update {
                        it.copy(
                            isRefreshingDevices = false,
                            error = "Failed to scan devices: ${e.message}",
                        )
                    }
                }
            }
        }

        fun selectDevice(device: UsbDeviceInfo) {
            _state.update { it.copy(selectedDevice = device, permissionError = null) }

            // Check permission and request if needed
            if (!flasher.hasPermission(device.deviceId)) {
                _state.update { it.copy(permissionPending = true) }
                flasher.requestPermission(device.deviceId) { granted ->
                    _state.update { it.copy(permissionPending = false) }
                    if (granted) {
                        Log.d(TAG, "USB permission granted for device ${device.deviceId}")
                    } else {
                        Log.w(TAG, "USB permission denied for device ${device.deviceId}")
                        _state.update { it.copy(permissionError = "USB permission denied") }
                    }
                }
            }
        }

        fun canProceedFromDeviceSelection(): Boolean {
            val currentState = _state.value
            return currentState.selectedDevice != null &&
                !currentState.permissionPending &&
                currentState.permissionError == null &&
                flasher.hasPermission(currentState.selectedDevice.deviceId)
        }

        // ==================== Step 2: Device Detection ====================

        fun detectDevice() {
            val device = _state.value.selectedDevice ?: return

            _state.update {
                it.copy(
                    currentStep = FlasherStep.DEVICE_DETECTION,
                    isDetecting = true,
                    detectedInfo = null,
                    detectionError = null,
                    useManualBoardSelection = false,
                )
            }

            viewModelScope.launch {
                try {
                    val deviceInfo = flasher.detectDevice(device.deviceId)
                    if (deviceInfo != null) {
                        Log.i(TAG, "Detected device: ${deviceInfo.board.displayName}")
                        // If board is unknown, enable manual selection mode
                        val needsManualSelection = deviceInfo.board == RNodeBoard.UNKNOWN
                        _state.update {
                            it.copy(
                                isDetecting = false,
                                detectedInfo = deviceInfo,
                                // Don't set selectedBoard if unknown - user must select manually
                                selectedBoard = if (needsManualSelection) null else deviceInfo.board,
                                selectedBand = FrequencyBand.fromModelCode(deviceInfo.model),
                                useManualBoardSelection = needsManualSelection,
                            )
                        }
                    } else {
                        Log.w(TAG, "Device not detected - unknown device type")
                        _state.update {
                            it.copy(
                                isDetecting = false,
                                detectionError = "Could not identify device. You can proceed with manual board selection.",
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Device detection failed", e)
                    _state.update {
                        it.copy(
                            isDetecting = false,
                            detectionError = "Detection failed: ${e.message}",
                        )
                    }
                }
            }
        }

        fun enableManualBoardSelection() {
            _state.update { it.copy(useManualBoardSelection = true, detectionError = null) }
            // Navigate directly to firmware selection step
            goToFirmwareSelection()
        }

        fun canProceedFromDetection(): Boolean {
            val currentState = _state.value
            // Can proceed if device detected OR manual board selection is enabled
            // (board will be selected in the firmware selection step)
            return currentState.detectedInfo != null || currentState.useManualBoardSelection
        }

        // ==================== Step 3: Firmware Selection ====================

        fun goToFirmwareSelection() {
            _state.update {
                it.copy(
                    currentStep = FlasherStep.FIRMWARE_SELECTION,
                    downloadError = null,
                )
            }
            loadAvailableFirmware()
        }

        fun selectBoard(board: RNodeBoard) {
            _state.update {
                it.copy(
                    selectedBoard = board,
                    selectedFirmware = null,
                )
            }
            loadAvailableFirmware()
        }

        fun selectFrequencyBand(band: FrequencyBand) {
            _state.update {
                it.copy(
                    selectedBand = band,
                    selectedFirmware = null,
                )
            }
            loadAvailableFirmware()
        }

        fun selectFirmware(firmware: FirmwarePackage) {
            _state.update { it.copy(selectedFirmware = firmware) }
        }

        private fun loadAvailableFirmware() {
            val board = _state.value.selectedBoard ?: return
            val band = _state.value.selectedBand

            viewModelScope.launch {
                try {
                    // Get cached firmware
                    val cachedFirmware =
                        flasher.firmwareRepository.getFirmwareForBoard(board)
                            .filter { it.frequencyBand == band }

                    _state.update {
                        it.copy(
                            availableFirmware = cachedFirmware,
                            selectedFirmware = cachedFirmware.maxByOrNull { pkg -> pkg.version },
                        )
                    }

                    // Optionally fetch available versions from GitHub
                    fetchAvailableVersions()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load firmware", e)
                    _state.update { it.copy(downloadError = "Failed to load firmware: ${e.message}") }
                }
            }
        }

        private fun fetchAvailableVersions() {
            viewModelScope.launch {
                try {
                    val releases = flasher.firmwareDownloader.getAvailableReleases()
                    if (releases != null) {
                        val versions = releases.map { it.version }
                        _state.update {
                            it.copy(
                                availableVersions = versions,
                                selectedVersion = versions.firstOrNull(),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch available versions", e)
                    // Non-fatal - can proceed with cached firmware
                }
            }
        }

        fun downloadFirmware(version: String) {
            val board = _state.value.selectedBoard ?: return
            val band = _state.value.selectedBand

            _state.update {
                it.copy(
                    isDownloadingFirmware = true,
                    downloadProgress = 0,
                    downloadError = null,
                )
            }

            viewModelScope.launch {
                try {
                    // Check if already cached
                    val existing = flasher.firmwareRepository.getLatestFirmware(board, band)
                    if (existing != null && existing.version == version) {
                        _state.update {
                            it.copy(
                                isDownloadingFirmware = false,
                                selectedFirmware = existing,
                            )
                        }
                        return@launch
                    }

                    // TODO: Implement actual download with progress callback
                    // For now, using downloadAndFlash internally handles this

                    _state.update { it.copy(isDownloadingFirmware = false) }
                } catch (e: Exception) {
                    Log.e(TAG, "Firmware download failed", e)
                    _state.update {
                        it.copy(
                            isDownloadingFirmware = false,
                            downloadError = "Download failed: ${e.message}",
                        )
                    }
                }
            }
        }

        fun canProceedFromFirmwareSelection(): Boolean {
            val currentState = _state.value
            return currentState.selectedBoard != null && !currentState.isDownloadingFirmware
        }

        // ==================== Step 4: Flash Progress ====================

        fun startFlashing() {
            val device = _state.value.selectedDevice ?: return
            val board = _state.value.selectedBoard ?: return
            val band = _state.value.selectedBand

            _state.update {
                it.copy(
                    currentStep = FlasherStep.FLASH_PROGRESS,
                    isFlashing = true,
                    flashProgress = 0,
                    flashMessage = "Starting flash...",
                    showCancelConfirmation = false,
                )
            }

            viewModelScope.launch {
                try {
                    val selectedFirmware = _state.value.selectedFirmware
                    val success =
                        if (selectedFirmware != null) {
                            // Use cached firmware
                            flasher.flashFirmware(
                                deviceId = device.deviceId,
                                firmwarePackage = selectedFirmware,
                                consoleImage = flasher.getConsoleImageStream(),
                            )
                        } else {
                            // Download and flash
                            flasher.downloadAndFlash(
                                deviceId = device.deviceId,
                                board = board,
                                frequencyBand = band,
                                version = _state.value.selectedVersion,
                            )
                        }

                    // FlashState observer will handle state updates
                    if (!success) {
                        Log.e(TAG, "Flash operation returned false")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Flash failed with exception", e)
                    _state.update {
                        it.copy(
                            currentStep = FlasherStep.COMPLETE,
                            isFlashing = false,
                            flashResult = FlashResult.Failure(e.message ?: "Unknown error"),
                        )
                    }
                }
            }
        }

        fun showCancelConfirmation() {
            _state.update { it.copy(showCancelConfirmation = true) }
        }

        fun hideCancelConfirmation() {
            _state.update { it.copy(showCancelConfirmation = false) }
        }

        fun cancelFlash() {
            Log.w(TAG, "User cancelled flash operation")
            // Note: Actual cancellation depends on flasher implementation
            // For safety, we transition to complete with cancelled state
            _state.update {
                it.copy(
                    currentStep = FlasherStep.COMPLETE,
                    isFlashing = false,
                    showCancelConfirmation = false,
                    flashResult = FlashResult.Cancelled,
                )
            }
            flasher.resetState()
        }

        /**
         * Called by the user after they have manually reset the device.
         * This triggers the provisioning step for the freshly flashed firmware.
         */
        fun onDeviceReset() {
            val device = _state.value.selectedDevice ?: return
            val board = _state.value.selectedBoard ?: return

            Log.i(TAG, "User confirmed device reset, starting provisioning (hash available: ${firmwareHashForProvisioning != null})")
            _state.update {
                it.copy(
                    needsManualReset = false,
                    isProvisioning = true,
                    provisioningMessage = "Connecting...",
                )
            }

            val band = _state.value.selectedBand

            viewModelScope.launch {
                flasher.onDeviceManuallyReset(device.deviceId, board, band, firmwareHashForProvisioning)
                // State will be updated by observeFlashState
                // Clear the hash after use
                firmwareHashForProvisioning = null
            }
        }

        /**
         * Skip flashing and go directly to provisioning.
         * Useful for testing or when flashing was already done externally.
         */
        fun provisionOnly() {
            val device = _state.value.selectedDevice ?: return
            val board = _state.value.selectedBoard ?: return
            val firmware = _state.value.selectedFirmware

            Log.i(TAG, "Starting provision-only flow for ${board.displayName}")

            // Calculate firmware hash from selected firmware if available
            firmwareHashForProvisioning = firmware?.calculateFirmwareBinaryHash()
            if (firmwareHashForProvisioning != null) {
                Log.d(TAG, "Pre-calculated firmware hash for provisioning")
            } else {
                Log.w(TAG, "No firmware selected - will attempt to get hash from device (may fail)")
            }

            _state.update {
                it.copy(
                    currentStep = FlasherStep.FLASH_PROGRESS,
                    needsManualReset = true,
                    resetMessage = "Connect your ${board.displayName} and press RST if needed.",
                    flashMessage = "Ready to provision ${board.displayName}",
                )
            }
        }

        // ==================== Step 5: Complete ====================

        fun flashAnother() {
            // Reset bootloader mode
            setBootloaderMode(false)
            _state.update {
                FlasherUiState(
                    currentStep = FlasherStep.DEVICE_SELECTION,
                )
            }
            refreshDevices()
            flasher.resetState()
        }

        override fun onCleared() {
            super.onCleared()
            // Ensure bootloader flash mode is disabled when leaving flasher
            com.lxmf.messenger.MainActivity.bootloaderFlashModeActive = false
        }

        // ==================== Navigation Helpers ====================

        fun goToNextStep() {
            val currentState = _state.value
            when (currentState.currentStep) {
                FlasherStep.DEVICE_SELECTION -> {
                    if (canProceedFromDeviceSelection()) {
                        if (skipDetectionMode) {
                            // Skip detection entirely - go straight to firmware selection
                            Log.d(TAG, "Skipping detection (bootloader mode)")
                            goToFirmwareSelection()
                        } else {
                            detectDevice()
                        }
                    }
                }
                FlasherStep.DEVICE_DETECTION -> {
                    if (canProceedFromDetection()) {
                        goToFirmwareSelection()
                    }
                }
                FlasherStep.FIRMWARE_SELECTION -> {
                    if (canProceedFromFirmwareSelection()) {
                        startFlashing()
                    }
                }
                FlasherStep.FLASH_PROGRESS,
                FlasherStep.COMPLETE,
                -> {
                    // No next step from these
                }
            }
        }

        fun goToPreviousStep() {
            val currentState = _state.value
            when (currentState.currentStep) {
                FlasherStep.DEVICE_SELECTION -> {
                    // Already at first step
                }
                FlasherStep.DEVICE_DETECTION -> {
                    _state.update { it.copy(currentStep = FlasherStep.DEVICE_SELECTION) }
                }
                FlasherStep.FIRMWARE_SELECTION -> {
                    _state.update { it.copy(currentStep = FlasherStep.DEVICE_DETECTION) }
                }
                FlasherStep.FLASH_PROGRESS -> {
                    // Can't go back during flashing
                }
                FlasherStep.COMPLETE -> {
                    // Use "Flash Another" instead
                }
            }
        }

        fun canGoBack(): Boolean {
            val currentState = _state.value
            return when (currentState.currentStep) {
                FlasherStep.DEVICE_SELECTION -> false
                FlasherStep.DEVICE_DETECTION -> !currentState.isDetecting
                FlasherStep.FIRMWARE_SELECTION -> true
                FlasherStep.FLASH_PROGRESS -> false
                FlasherStep.COMPLETE -> false
            }
        }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }
    }
