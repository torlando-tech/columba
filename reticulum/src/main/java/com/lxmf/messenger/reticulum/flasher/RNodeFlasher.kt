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
                // Re-detect device after flashing
                _flashState.value = FlashState.Progress(98, "Verifying flash...")

                // Give device time to boot
                kotlinx.coroutines.delay(2000)

                val deviceInfo = detectDevice(deviceId)
                _flashState.value = FlashState.Complete(deviceInfo)
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

            _flashState.value = FlashState.Progress(5, "Starting flash...")

            // Select flasher based on platform
            val success = when (info.platform) {
                RNodePlatform.NRF52 -> {
                    flashNrf52Direct(deviceId, firmwareStream)
                }
                RNodePlatform.ESP32 -> {
                    flashEsp32Direct(deviceId, firmwareStream, consoleImage)
                }
                else -> {
                    _flashState.value = FlashState.Error(
                        "Unsupported platform: ${info.platform}",
                    )
                    false
                }
            }

            if (success) {
                _flashState.value = FlashState.Complete(info)
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
        return espToolFlasher.flash(
            firmwarePackage.getInputStream(),
            deviceId,
            consoleImage,
            createEspProgressCallback(),
        )
    }

    private suspend fun flashEsp32Direct(
        deviceId: Int,
        firmwareStream: InputStream,
        consoleImage: InputStream?,
    ): Boolean {
        return espToolFlasher.flash(
            firmwareStream,
            deviceId,
            consoleImage,
            createEspProgressCallback(),
        )
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
}
