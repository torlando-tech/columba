package com.lxmf.messenger.reticulum.flasher

import android.content.Context
import android.util.Log
import com.lxmf.messenger.reticulum.usb.KotlinUSBBridge
import com.lxmf.messenger.reticulum.usb.UsbDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Unified RNode flasher interface.
 *
 * This class provides a high-level API for flashing RNode firmware to devices,
 * automatically selecting the appropriate flashing protocol based on the device type.
 *
 * Supported devices:
 * - nRF52-based: RAK4631, Heltec T114, T-Echo (uses Nordic DFU)
 * - ESP32-based: Heltec LoRa32, T-Beam, T-Deck, etc. (uses ESPTool)
 *
 * Usage:
 * ```kotlin
 * val flasher = RNodeFlasher(context)
 *
 * // List connected devices
 * val devices = flasher.getConnectedDevices()
 *
 * // Detect device info
 * val info = flasher.detectDevice(deviceId)
 *
 * // Flash firmware
 * flasher.flashFirmware(deviceId, firmwarePackage) { state ->
 *     when (state) {
 *         is FlashState.Progress -> updateProgressUI(state.percent, state.message)
 *         is FlashState.Complete -> showSuccess()
 *         is FlashState.Error -> showError(state.message)
 *     }
 * }
 * ```
 */
class RNodeFlasher(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Columba:RNodeFlasher"
    }

    private val usbBridge = KotlinUSBBridge.getInstance(context)
    private val detector = RNodeDetector(usbBridge)
    private val nordicDfuFlasher = NordicDFUFlasher(usbBridge)
    private val espToolFlasher = ESPToolFlasher(usbBridge)

    val firmwareRepository = FirmwareRepository(context)
    val firmwareDownloader = FirmwareDownloader()

    private val _flashState = MutableStateFlow<FlashState>(FlashState.Idle)
    val flashState: StateFlow<FlashState> = _flashState.asStateFlow()

    /**
     * Flash state for UI observation.
     */
    sealed class FlashState {
        data object Idle : FlashState()
        data class Detecting(val message: String) : FlashState()
        data class Progress(val percent: Int, val message: String) : FlashState()
        data class Provisioning(val message: String) : FlashState()
        data class NeedsManualReset(val board: RNodeBoard, val message: String) : FlashState()
        data class Complete(val deviceInfo: RNodeDeviceInfo?) : FlashState()
        data class Error(val message: String, val recoverable: Boolean = true) : FlashState()
    }

    /**
     * Get list of connected USB devices that could be RNodes.
     */
    fun getConnectedDevices(): List<UsbDeviceInfo> {
        return usbBridge.getConnectedUsbDevices()
    }

    /**
     * Check if we have USB permission for a device.
     */
    fun hasPermission(deviceId: Int): Boolean {
        return usbBridge.hasPermission(deviceId)
    }

    /**
     * Request USB permission for a device.
     */
    fun requestPermission(deviceId: Int, callback: (Boolean) -> Unit) {
        usbBridge.requestPermission(deviceId, callback)
    }

    /**
     * Detect if a connected device is an RNode and get its info.
     *
     * @param deviceId USB device ID to check
     * @return Device info if detected, null otherwise
     */
    suspend fun detectDevice(deviceId: Int): RNodeDeviceInfo? = withContext(Dispatchers.IO) {
        _flashState.value = FlashState.Detecting("Connecting to device...")

        try {
            // Connect to device
            if (!usbBridge.connect(deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                Log.e(TAG, "Failed to connect to device $deviceId")
                _flashState.value = FlashState.Error("Failed to connect to device")
                return@withContext null
            }

            _flashState.value = FlashState.Detecting("Detecting RNode...")

            // Detect device
            val deviceInfo = detector.getDeviceInfo()

            if (deviceInfo != null) {
                Log.i(TAG, "Detected RNode: ${deviceInfo.board.displayName}")
                _flashState.value = FlashState.Idle
            } else {
                Log.w(TAG, "Device is not an RNode or could not be detected")
                _flashState.value = FlashState.Error("Device is not an RNode")
            }

            // Disconnect after detection
            usbBridge.disconnect()

            deviceInfo
        } catch (e: Exception) {
            Log.e(TAG, "Device detection failed", e)
            _flashState.value = FlashState.Error("Detection failed: ${e.message}")
            usbBridge.disconnect()
            null
        }
    }

    /**
     * Flash firmware to a device.
     *
     * @param deviceId USB device ID to flash
     * @param firmwarePackage Firmware package to flash
     * @param consoleImage Optional console image (SPIFFS) for ESP32 devices
     * @return true if flashing succeeded
     */
    suspend fun flashFirmware(
        deviceId: Int,
        firmwarePackage: FirmwarePackage,
        consoleImage: InputStream? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        _flashState.value = FlashState.Progress(0, "Starting flash...")

        try {
            // Verify firmware integrity
            if (!firmwarePackage.verifyIntegrity()) {
                _flashState.value = FlashState.Error("Firmware file is corrupted")
                return@withContext false
            }

            // Check if device is ESP32-S3 native USB (needs manual reset after flashing)
            val deviceInfo = usbBridge.getConnectedUsbDevices().find { it.deviceId == deviceId }
            val isNativeUsb = deviceInfo?.let {
                ESPToolFlasher.isNativeUsbDevice(it.vendorId, it.productId)
            } ?: false

            // Select flasher based on platform
            val success = when (firmwarePackage.platform) {
                RNodePlatform.NRF52 -> {
                    flashNrf52(deviceId, firmwarePackage)
                }
                RNodePlatform.ESP32 -> {
                    flashEsp32(deviceId, firmwarePackage, consoleImage)
                }
                else -> {
                    _flashState.value = FlashState.Error(
                        "Unsupported platform: ${firmwarePackage.platform}",
                    )
                    false
                }
            }

            if (success) {
                if (isNativeUsb && firmwarePackage.platform == RNodePlatform.ESP32) {
                    // ESP32-S3 native USB doesn't auto-reboot reliably
                    // User needs to manually reset the device, then we'll provision
                    Log.i(TAG, "Flash complete. Native USB device needs manual reset for provisioning.")
                    _flashState.value = FlashState.NeedsManualReset(
                        firmwarePackage.board,
                        "Flashing complete! Please press the RST button on your ${firmwarePackage.board.displayName}.",
                    )
                } else {
                    // Standard flow: verify and complete
                    _flashState.value = FlashState.Progress(98, "Verifying flash...")

                    // Give device time to boot
                    kotlinx.coroutines.delay(2000)

                    val detectedInfo = detectDevice(deviceId)
                    _flashState.value = FlashState.Complete(detectedInfo)
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Flash failed", e)
            _flashState.value = FlashState.Error("Flash failed: ${e.message}")
            false
        }
    }

    /**
     * Flash firmware to a device using auto-detection.
     *
     * @param deviceId USB device ID to flash
     * @param firmwareStream Firmware ZIP file stream
     * @param deviceInfo Pre-detected device info (optional, will detect if null)
     * @param consoleImage Optional console image for ESP32 devices
     * @return true if flashing succeeded
     */
    suspend fun flashFirmwareAutoDetect(
        deviceId: Int,
        firmwareStream: InputStream,
        deviceInfo: RNodeDeviceInfo? = null,
        consoleImage: InputStream? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        _flashState.value = FlashState.Progress(0, "Preparing...")

        try {
            // Detect device if not provided
            val info = deviceInfo ?: detectDevice(deviceId)

            if (info == null) {
                _flashState.value = FlashState.Error("Could not detect device type")
                return@withContext false
            }

            // Check if device is ESP32-S3 native USB (needs manual reset after flashing)
            val usbDeviceInfo = usbBridge.getConnectedUsbDevices().find { it.deviceId == deviceId }
            val isNativeUsb = usbDeviceInfo?.let {
                ESPToolFlasher.isNativeUsbDevice(it.vendorId, it.productId)
            } ?: false

            _flashState.value = FlashState.Progress(5, "Starting flash...")

            // Select flasher based on platform
            val success = when (info.platform) {
                RNodePlatform.NRF52 -> {
                    flashNrf52Direct(deviceId, firmwareStream)
                }
                RNodePlatform.ESP32 -> {
                    flashEsp32Direct(deviceId, firmwareStream, info.board, consoleImage)
                }
                else -> {
                    _flashState.value = FlashState.Error(
                        "Unsupported platform: ${info.platform}",
                    )
                    false
                }
            }

            if (success) {
                if (isNativeUsb && info.platform == RNodePlatform.ESP32) {
                    // ESP32-S3 native USB doesn't auto-reboot reliably
                    Log.i(TAG, "Flash complete. Native USB device needs manual reset for provisioning.")
                    _flashState.value = FlashState.NeedsManualReset(
                        info.board,
                        "Flashing complete! Please press the RST button on your ${info.board.displayName}.",
                    )
                } else {
                    _flashState.value = FlashState.Complete(info)
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Flash failed", e)
            _flashState.value = FlashState.Error("Flash failed: ${e.message}")
            false
        }
    }

    private suspend fun flashNrf52(deviceId: Int, firmwarePackage: FirmwarePackage): Boolean {
        return nordicDfuFlasher.flash(
            firmwarePackage.getInputStream(),
            deviceId,
            createProgressCallback(),
        )
    }

    private suspend fun flashNrf52Direct(deviceId: Int, firmwareStream: InputStream): Boolean {
        return nordicDfuFlasher.flash(
            firmwareStream,
            deviceId,
            createProgressCallback(),
        )
    }

    private suspend fun flashEsp32(
        deviceId: Int,
        firmwarePackage: FirmwarePackage,
        consoleImage: InputStream?,
    ): Boolean {
        // Get device info to pass VID/PID for native USB detection
        val deviceInfo = usbBridge.getConnectedUsbDevices().find { it.deviceId == deviceId }
        val vendorId = deviceInfo?.vendorId ?: 0
        val productId = deviceInfo?.productId ?: 0

        // For native USB devices (ESP32-S3), skip the firmware update indication.
        // These devices either:
        // 1. Don't have RNode firmware yet (so CMD_FW_UPD won't work)
        // 2. May already be in bootloader mode from manual entry (connecting would interfere)
        // For devices with USB-UART bridges (CP2102, CH343, etc.), try the indication.
        val isNativeUsb = ESPToolFlasher.isNativeUsbDevice(vendorId, productId)
        if (!isNativeUsb) {
            _flashState.value = FlashState.Progress(2, "Preparing device for update...")
            if (!prepareFirmwareUpdate(deviceId)) {
                Log.w(TAG, "Could not send firmware update indication, proceeding anyway")
            }
        } else {
            Log.d(TAG, "Skipping firmware update indication for native USB device")
        }

        return try {
            espToolFlasher.flash(
                firmwarePackage.getInputStream(),
                deviceId,
                firmwarePackage.board,
                vendorId,
                productId,
                consoleImage,
                createEspProgressCallback(),
            )
        } catch (e: ESPToolFlasher.ManualBootModeRequired) {
            _flashState.value = FlashState.Error(e.message ?: "Manual boot mode required", recoverable = true)
            false
        }
    }

    private suspend fun flashEsp32Direct(
        deviceId: Int,
        firmwareStream: InputStream,
        board: RNodeBoard,
        consoleImage: InputStream?,
    ): Boolean {
        // Get device info to pass VID/PID for native USB detection
        val deviceInfo = usbBridge.getConnectedUsbDevices().find { it.deviceId == deviceId }
        val vendorId = deviceInfo?.vendorId ?: 0
        val productId = deviceInfo?.productId ?: 0

        // For native USB devices (ESP32-S3), skip the firmware update indication.
        // These devices either:
        // 1. Don't have RNode firmware yet (so CMD_FW_UPD won't work)
        // 2. May already be in bootloader mode from manual entry (connecting would interfere)
        // For devices with USB-UART bridges (CP2102, CH343, etc.), try the indication.
        val isNativeUsb = ESPToolFlasher.isNativeUsbDevice(vendorId, productId)
        if (!isNativeUsb) {
            _flashState.value = FlashState.Progress(2, "Preparing device for update...")
            if (!prepareFirmwareUpdate(deviceId)) {
                Log.w(TAG, "Could not send firmware update indication, proceeding anyway")
            }
        } else {
            Log.d(TAG, "Skipping firmware update indication for native USB device")
        }

        return try {
            espToolFlasher.flash(
                firmwareStream,
                deviceId,
                board,
                vendorId,
                productId,
                consoleImage,
                createEspProgressCallback(),
            )
        } catch (e: ESPToolFlasher.ManualBootModeRequired) {
            _flashState.value = FlashState.Error(e.message ?: "Manual boot mode required", recoverable = true)
            false
        }
    }

    /**
     * Prepare the RNode for a firmware update by sending the CMD_FW_UPD command.
     *
     * This tells the running RNode firmware that a firmware update is about to begin.
     * For ESP32 devices, this enables the auto-reset functionality via DTR/RTS signals.
     *
     * Based on rnodeconf's indicate_firmware_update() function which is called before
     * invoking esptool.
     *
     * @param deviceId USB device ID
     * @return true if the command was sent successfully
     */
    private suspend fun prepareFirmwareUpdate(deviceId: Int): Boolean {
        return try {
            // Connect to send KISS command
            if (!usbBridge.connect(deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                Log.e(TAG, "Failed to connect for firmware update indication")
                return false
            }

            // Send the firmware update indication
            val success = detector.indicateFirmwareUpdate()

            // Disconnect - esptool will reconnect
            // Give the device a moment to process before disconnecting
            kotlinx.coroutines.delay(500)
            usbBridge.disconnect()

            // Additional delay to let the device fully prepare
            kotlinx.coroutines.delay(500)

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send firmware update indication", e)
            usbBridge.disconnect()
            false
        }
    }

    private fun createProgressCallback(): NordicDFUFlasher.ProgressCallback {
        return object : NordicDFUFlasher.ProgressCallback {
            override fun onProgress(percent: Int, message: String) {
                _flashState.value = FlashState.Progress(percent, message)
            }

            override fun onError(error: String) {
                _flashState.value = FlashState.Error(error)
            }

            override fun onComplete() {
                // Complete state is set by the main flash function
            }
        }
    }

    private fun createEspProgressCallback(): ESPToolFlasher.ProgressCallback {
        return object : ESPToolFlasher.ProgressCallback {
            override fun onProgress(percent: Int, message: String) {
                _flashState.value = FlashState.Progress(percent, message)
            }

            override fun onError(error: String) {
                _flashState.value = FlashState.Error(error)
            }

            override fun onComplete() {
                // Complete state is set by the main flash function
            }
        }
    }

    /**
     * Download and flash firmware for a board.
     *
     * @param deviceId USB device ID to flash
     * @param board Target board type
     * @param frequencyBand Frequency band
     * @param version Version to download (null for latest)
     * @return true if successful
     */
    suspend fun downloadAndFlash(
        deviceId: Int,
        board: RNodeBoard,
        frequencyBand: FrequencyBand,
        version: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        _flashState.value = FlashState.Progress(0, "Checking for firmware...")

        try {
            // Get release info
            val release = if (version != null) {
                val releases = firmwareDownloader.getAvailableReleases()
                releases?.find { it.version == version }
            } else {
                firmwareDownloader.getLatestRelease()
            }

            if (release == null) {
                _flashState.value = FlashState.Error("Could not find firmware release")
                return@withContext false
            }

            // Find firmware asset
            val asset = firmwareDownloader.findFirmwareAsset(release, board, frequencyBand)
            if (asset == null) {
                _flashState.value = FlashState.Error(
                    "No firmware found for ${board.displayName} (${frequencyBand.displayName})",
                )
                return@withContext false
            }

            _flashState.value = FlashState.Progress(5, "Downloading firmware...")

            // Download firmware
            var downloadedData: ByteArray? = null

            firmwareDownloader.downloadFirmware(
                asset,
                object : FirmwareDownloader.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
                        val percent = if (totalBytes > 0) {
                            5 + ((bytesDownloaded * 15) / totalBytes).toInt()
                        } else {
                            5
                        }
                        _flashState.value = FlashState.Progress(
                            percent,
                            "Downloading: ${bytesDownloaded / 1024}KB / ${totalBytes / 1024}KB",
                        )
                    }

                    override fun onComplete(data: ByteArray) {
                        downloadedData = data
                    }

                    override fun onError(error: String) {
                        _flashState.value = FlashState.Error(error)
                    }
                },
            )

            if (downloadedData == null) {
                return@withContext false
            }

            // Save firmware
            _flashState.value = FlashState.Progress(20, "Saving firmware...")

            val firmwarePackage = firmwareRepository.saveFirmware(
                board,
                release.version,
                frequencyBand,
                downloadedData!!,
            )

            if (firmwarePackage == null) {
                _flashState.value = FlashState.Error("Failed to save firmware")
                return@withContext false
            }

            // Flash firmware
            flashFirmware(deviceId, firmwarePackage)
        } catch (e: Exception) {
            Log.e(TAG, "Download and flash failed", e)
            _flashState.value = FlashState.Error("Failed: ${e.message}")
            false
        }
    }

    /**
     * Reset the flash state to idle.
     */
    fun resetState() {
        _flashState.value = FlashState.Idle
    }

    /**
     * Get console image input stream from assets.
     * The console image provides the web interface for ESP32-based RNodes.
     */
    fun getConsoleImageStream(): InputStream? {
        return try {
            // Check for bundled console image
            val consoleFile = File(context.filesDir, "console_image.bin")
            if (consoleFile.exists()) {
                consoleFile.inputStream()
            } else {
                // Try to load from assets
                context.assets.open("firmware/console_image.bin")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Console image not available: ${e.message}")
            null
        }
    }

    /**
     * Provision a freshly flashed device.
     *
     * After flashing, the device needs to be provisioned with:
     * 1. EEPROM device info (product, model, serial, checksum, blank signature)
     * 2. Firmware hash set to match the actual firmware
     *
     * This should be called after the device has been reset and is running the new firmware.
     *
     * @param deviceId USB device ID
     * @param board The board type that was flashed
     * @return true if provisioning succeeded
     */
    suspend fun provisionDevice(deviceId: Int, board: RNodeBoard): Boolean = withContext(Dispatchers.IO) {
        _flashState.value = FlashState.Provisioning("Waiting for device...")

        try {
            // After reset, device may take time to re-enumerate and may have new device ID
            // Try multiple times with increasing delays
            var connected = false
            var actualDeviceId = deviceId
            val maxRetries = 5
            val retryDelayMs = 2000L

            for (attempt in 1..maxRetries) {
                Log.d(TAG, "Provisioning connect attempt $attempt/$maxRetries")
                _flashState.value = FlashState.Provisioning("Connecting to device (attempt $attempt)...")

                // First try the original device ID
                if (usbBridge.connect(deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                    connected = true
                    actualDeviceId = deviceId
                    break
                }

                // If that failed, scan for devices and try to find one matching native USB VID/PID
                Log.d(TAG, "Original device ID failed, scanning for devices...")
                val devices = usbBridge.getConnectedUsbDevices()
                val nativeUsbDevice = devices.find {
                    ESPToolFlasher.isNativeUsbDevice(it.vendorId, it.productId)
                }

                if (nativeUsbDevice != null && nativeUsbDevice.deviceId != deviceId) {
                    Log.d(TAG, "Found native USB device with new ID: ${nativeUsbDevice.deviceId}")
                    if (usbBridge.connect(nativeUsbDevice.deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                        connected = true
                        actualDeviceId = nativeUsbDevice.deviceId
                        break
                    }
                }

                // Wait before retrying
                if (attempt < maxRetries) {
                    Log.d(TAG, "Waiting ${retryDelayMs}ms before retry...")
                    kotlinx.coroutines.delay(retryDelayMs)
                }
            }

            if (!connected) {
                Log.e(TAG, "Failed to connect for provisioning after $maxRetries attempts")
                _flashState.value = FlashState.Error("Failed to connect for provisioning. Make sure the device is connected and has been reset.")
                return@withContext false
            }

            Log.i(TAG, "Connected to device $actualDeviceId for provisioning")

            // Small delay to let the device settle after boot
            kotlinx.coroutines.delay(1000)

            // Check if already provisioned
            _flashState.value = FlashState.Provisioning("Checking provisioning status...")
            val deviceInfo = detector.getDeviceInfo()

            if (deviceInfo?.isProvisioned == true) {
                Log.i(TAG, "Device is already provisioned")
                _flashState.value = FlashState.Provisioning("Device already provisioned, setting firmware hash...")

                // Still need to set firmware hash if not matching
                val firmwareHash = detector.getFirmwareHash()
                if (firmwareHash != null) {
                    detector.setFirmwareHash(firmwareHash)
                }

                usbBridge.disconnect()
                _flashState.value = FlashState.Complete(deviceInfo)
                return@withContext true
            }

            // Provision EEPROM
            _flashState.value = FlashState.Provisioning("Writing device information...")
            if (!detector.provisionAndSetFirmwareHash(board)) {
                Log.e(TAG, "Provisioning failed")
                _flashState.value = FlashState.Error("Failed to provision device EEPROM")
                usbBridge.disconnect()
                return@withContext false
            }

            // Wait for writes to complete
            _flashState.value = FlashState.Provisioning("Finalizing...")
            kotlinx.coroutines.delay(2000)

            // Reset device to apply changes
            _flashState.value = FlashState.Provisioning("Resetting device...")
            detector.resetDevice()
            usbBridge.disconnect()

            // Wait for device to reboot
            kotlinx.coroutines.delay(3000)

            // Verify provisioning
            _flashState.value = FlashState.Provisioning("Verifying provisioning...")
            if (!usbBridge.connect(deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                // Device may have changed ports after reset, but provisioning likely succeeded
                Log.w(TAG, "Could not reconnect to verify, but provisioning likely succeeded")
                _flashState.value = FlashState.Complete(null)
                return@withContext true
            }

            kotlinx.coroutines.delay(500)
            val verifiedInfo = detector.getDeviceInfo()
            usbBridge.disconnect()

            if (verifiedInfo?.isProvisioned == true) {
                Log.i(TAG, "Provisioning verified successfully")
                _flashState.value = FlashState.Complete(verifiedInfo)
                true
            } else {
                Log.w(TAG, "Provisioning verification failed, but writes may have succeeded")
                _flashState.value = FlashState.Complete(verifiedInfo)
                true // Optimistic - EEPROM writes likely succeeded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning failed", e)
            _flashState.value = FlashState.Error("Provisioning failed: ${e.message}")
            usbBridge.disconnect()
            false
        }
    }

    /**
     * Signal that the user has manually reset the device after flashing.
     * This triggers the provisioning step.
     *
     * @param deviceId USB device ID
     * @param board The board type that was flashed
     */
    suspend fun onDeviceManuallyReset(deviceId: Int, board: RNodeBoard): Boolean {
        Log.i(TAG, "Device manually reset, starting provisioning")
        return provisionDevice(deviceId, board)
    }

    /**
     * Notify that flashing is complete but manual reset is required.
     * The UI should show instructions for the user to reset the device.
     */
    fun notifyNeedsManualReset(board: RNodeBoard) {
        _flashState.value = FlashState.NeedsManualReset(
            board,
            "Flashing complete! Please press the RESET button on your ${board.displayName} to continue.",
        )
    }
}
