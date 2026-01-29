package com.lxmf.messenger.reticulum.flasher

import android.util.Log
import com.lxmf.messenger.reticulum.usb.KotlinUSBBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * ESPTool-compatible flasher for ESP32 devices.
 *
 * Implements the ESP32 ROM bootloader protocol for flashing RNode firmware
 * to ESP32-based boards like Heltec LoRa32, LilyGO T-Beam, T-Deck, etc.
 *
 * The ESP32 flash process:
 * 1. Enter bootloader via RTS/DTR sequence (EN/IO0 control)
 * 2. Sync with bootloader at 115200 baud
 * 3. Optionally switch to higher baud rate (921600)
 * 4. Flash multiple regions:
 *    - 0x1000: Bootloader
 *    - 0x8000: Partition table
 *    - 0x10000: Application firmware
 *    - 0x210000: Console image (SPIFFS)
 * 5. Verify with MD5 checksum
 * 6. Reset device
 *
 * Based on: https://github.com/espressif/esptool
 */
@Suppress("MagicNumber", "TooManyFunctions", "LargeClass")
class ESPToolFlasher(
    private val usbBridge: KotlinUSBBridge,
) {
    /**
     * Exception thrown when manual bootloader entry is required.
     * This happens with ESP32-S3 native USB devices that don't support
     * automatic reset via DTR/RTS signals.
     */
    class ManualBootModeRequired(
        message: String,
    ) : Exception(message)

    companion object {
        private const val TAG = "Columba:ESPTool"

        // Baud rates
        private const val INITIAL_BAUD = 115200
        private const val FLASH_BAUD = 921600

        // Timeouts
        private const val SYNC_TIMEOUT_MS = 5000L
        private const val COMMAND_TIMEOUT_MS = 3000L
        private const val READ_TIMEOUT_MS = 100
        private const val ERASE_TIMEOUT_PER_MB_MS = 10000L // 10 seconds per MB for flash erase
        private const val MIN_ERASE_TIMEOUT_MS = 10000L // Minimum 10 seconds for any erase

        // ESP32 ROM commands
        private const val ESP_FLASH_BEGIN: Byte = 0x02
        private const val ESP_FLASH_DATA: Byte = 0x03
        private const val ESP_FLASH_END: Byte = 0x04

        // Memory commands (reserved for future use with stub loader)
        // private const val ESP_MEM_BEGIN: Byte = 0x05
        // private const val ESP_MEM_END: Byte = 0x06
        // private const val ESP_MEM_DATA: Byte = 0x07
        private const val ESP_SYNC: Byte = 0x08
        private const val ESP_WRITE_REG: Byte = 0x09
        private const val ESP_READ_REG: Byte = 0x0A

        // private const val ESP_SPI_SET_PARAMS: Byte = 0x0B // Reserved
        private const val ESP_SPI_ATTACH: Byte = 0x0D
        private const val ESP_CHANGE_BAUDRATE: Byte = 0x0F
        // Deflate commands (reserved - require stub loader)
        // private const val ESP_FLASH_DEFL_BEGIN: Byte = 0x10
        // private const val ESP_FLASH_DEFL_DATA: Byte = 0x11
        // private const val ESP_FLASH_DEFL_END: Byte = 0x12
        // private const val ESP_SPI_FLASH_MD5: Byte = 0x13

        // SLIP constants
        private const val SLIP_END: Byte = 0xC0.toByte()
        private const val SLIP_ESC: Byte = 0xDB.toByte()
        private const val SLIP_ESC_END: Byte = 0xDC.toByte()
        private const val SLIP_ESC_ESC: Byte = 0xDD.toByte()

        // ESP32-S3 specific registers for reset
        private const val ESP32S3_RTC_CNTL_OPTION1_REG = 0x6000812C
        private const val ESP32S3_RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK = 0x1

        // Flash parameters
        // ROM bootloader uses 0x400 (1024) block size for all ESP32 variants
        // Stub loader uses 0x4000 (16KB) but USB CDC limits to 0x800 (2KB)
        // Since we're using ROM (no stub), always use 0x400
        private const val ESP_FLASH_BLOCK_SIZE = 0x400 // 1024 bytes for ROM bootloader
        private const val ESP_CHECKSUM_MAGIC: Byte = 0xEF.toByte()

        // Standard flash offsets for ESP32
        const val OFFSET_BOOTLOADER_ESP32 = 0x1000
        const val OFFSET_BOOTLOADER_ESP32_S3 = 0x0 // ESP32-S3 bootloader is at 0x0!
        const val OFFSET_PARTITIONS = 0x8000
        const val OFFSET_BOOT_APP0 = 0xE000
        const val OFFSET_APPLICATION = 0x10000
        const val OFFSET_CONSOLE = 0x210000

        /**
         * Check if a board uses ESP32-S3 (different bootloader address).
         */
        fun isEsp32S3(board: RNodeBoard): Boolean =
            when (board) {
                RNodeBoard.TBEAM_S, // T-Beam Supreme
                RNodeBoard.TDECK, // T-Deck
                RNodeBoard.HELTEC_V3, // Heltec LoRa32 v3
                RNodeBoard.HELTEC_V4, // Heltec LoRa32 v4
                -> true
                else -> false
            }

        /**
         * Get the bootloader offset for a board.
         */
        fun getBootloaderOffset(board: RNodeBoard): Int = if (isEsp32S3(board)) OFFSET_BOOTLOADER_ESP32_S3 else OFFSET_BOOTLOADER_ESP32

        // Bootloader entry timing
        private const val RESET_DELAY_MS = 100L
        private const val RESET_DELAY_MS_USB = 200L // ESP32-S3 USB needs longer delays
        private const val BOOT_DELAY_MS = 50L

        // ESP32-S3 native USB-JTAG-Serial identifiers
        // When connected via native USB (not a USB-UART bridge like CP2102),
        // DTR/RTS are virtual signals that may not trigger bootloader entry.
        const val ESPRESSIF_VID = 0x303A
        const val ESP32_S3_USB_JTAG_SERIAL_PID = 0x1001

        /**
         * Check if a device uses ESP32-S3 native USB-JTAG-Serial.
         * These devices have virtual DTR/RTS that may not work for bootloader entry.
         */
        fun isNativeUsbDevice(
            vendorId: Int,
            productId: Int,
        ): Boolean = vendorId == ESPRESSIF_VID && productId == ESP32_S3_USB_JTAG_SERIAL_PID
    }

    private var inBootloader = false
    private var currentBoardIsS3 = false
    private var isNativeUsb = false

    /**
     * Callback interface for flash progress updates.
     */
    interface ProgressCallback {
        fun onProgress(
            percent: Int,
            message: String,
        )

        fun onError(error: String)

        fun onComplete()
    }

    /**
     * Flash firmware from a ZIP package to an ESP32 device.
     *
     * @param firmwareZipStream Input stream of the firmware ZIP file
     * @param deviceId USB device ID to flash
     * @param board The target board (used to determine ESP32 vs ESP32-S3)
     * @param vendorId USB Vendor ID (used to detect native USB devices)
     * @param productId USB Product ID (used to detect native USB devices)
     * @param consoleImageStream Optional console image (SPIFFS) stream
     * @param progressCallback Progress callback
     * @return true if flashing succeeded
     * @throws ManualBootModeRequired if the device needs manual bootloader entry
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun flash(
        firmwareZipStream: InputStream,
        deviceId: Int,
        board: RNodeBoard = RNodeBoard.UNKNOWN,
        vendorId: Int = 0,
        productId: Int = 0,
        consoleImageStream: InputStream? = null,
        progressCallback: ProgressCallback,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val bootloaderOffset = getBootloaderOffset(board)
            val isS3 = isEsp32S3(board)
            currentBoardIsS3 = isS3
            isNativeUsb = isNativeUsbDevice(vendorId, productId)
            Log.d(
                TAG,
                "Flashing ${board.displayName}, ESP32-S3=$isS3, nativeUSB=$isNativeUsb, " +
                    "bootloader offset=0x${bootloaderOffset.toString(16)}",
            )
            try {
                progressCallback.onProgress(0, "Parsing firmware package...")

                // Parse the firmware ZIP
                val firmwareData = parseFirmwareZip(firmwareZipStream)
                if (firmwareData == null) {
                    progressCallback.onError("Invalid firmware package")
                    return@withContext false
                }

                // Read console image if provided
                val consoleImage = consoleImageStream?.readBytes()

                progressCallback.onProgress(5, "Connecting to device...")

                // Connect at initial baud rate
                if (!usbBridge.connect(deviceId, INITIAL_BAUD)) {
                    progressCallback.onError("Failed to connect to device")
                    return@withContext false
                }

                // Enable raw mode for ESPTool - stops async reads so readBlocking() works
                usbBridge.enableRawMode()

                progressCallback.onProgress(8, "Syncing with bootloader...")

                // First try to sync WITHOUT entering bootloader.
                // This handles the case where user has manually entered bootloader mode.
                // If we try enterBootloader() first, the DTR/RTS sequence would reset the chip
                // and kick it out of bootloader mode.
                var synced = trySyncQuick()

                if (synced) {
                    Log.d(TAG, "Device already in bootloader mode (manual entry detected)")
                } else {
                    // Not in bootloader yet, try to enter via DTR/RTS sequence
                    progressCallback.onProgress(9, "Entering bootloader...")
                    if (!enterBootloader()) {
                        progressCallback.onError("Failed to enter bootloader mode")
                        return@withContext false
                    }

                    progressCallback.onProgress(10, "Syncing with bootloader...")
                    synced = sync()
                }

                if (!synced) {
                    progressCallback.onError("Failed to sync with bootloader")
                    return@withContext false
                }

                // Read chip detect register - this "activates" the bootloader for subsequent commands
                progressCallback.onProgress(11, "Detecting chip...")
                val chipMagic = readChipDetectReg()
                if (chipMagic != null) {
                    Log.d(TAG, "Chip magic: 0x${chipMagic.toString(16)}")
                } else {
                    Log.w(TAG, "Could not read chip detect register")
                }

                // ESP32-S3 native USB doesn't use baud rate (it's not UART)
                // Only attempt baud rate change for non-S3 boards
                if (!isS3) {
                    progressCallback.onProgress(12, "Switching to high-speed mode...")
                    if (changeBaudRate(FLASH_BAUD)) {
                        delay(50)
                        usbBridge.setBaudRate(FLASH_BAUD)
                        Log.d(TAG, "Switched to $FLASH_BAUD baud")
                    } else {
                        Log.w(TAG, "Could not switch baud rate, continuing at $INITIAL_BAUD")
                    }
                } else {
                    Log.d(TAG, "Skipping baud rate change for ESP32-S3 USB")
                }

                // SPI attach configures flash pins (required before flash operations)
                progressCallback.onProgress(13, "Attaching SPI flash...")
                if (!spiAttach()) {
                    Log.w(TAG, "SPI attach failed, attempting to continue anyway")
                }

                progressCallback.onProgress(15, "Flashing bootloader...")

                // Flash each region
                var success = true
                var currentProgress = 15

                // Bootloader (if present)
                firmwareData.bootloader?.let { bootloader ->
                    success =
                        flashRegion(
                            bootloader,
                            bootloaderOffset,
                            "bootloader",
                            currentProgress,
                            20,
                            progressCallback,
                        )
                    if (!success) return@withContext false
                    currentProgress = 20
                }

                progressCallback.onProgress(currentProgress, "Flashing partition table...")

                // Partition table (if present)
                firmwareData.partitions?.let { partitions ->
                    success =
                        flashRegion(
                            partitions,
                            OFFSET_PARTITIONS,
                            "partition table",
                            currentProgress,
                            25,
                            progressCallback,
                        )
                    if (!success) return@withContext false
                    currentProgress = 25
                }

                // Boot app0 (if present)
                firmwareData.bootApp0?.let { bootApp0 ->
                    success =
                        flashRegion(
                            bootApp0,
                            OFFSET_BOOT_APP0,
                            "boot_app0",
                            currentProgress,
                            30,
                            progressCallback,
                        )
                    if (!success) return@withContext false
                    currentProgress = 30
                }

                progressCallback.onProgress(currentProgress, "Flashing application...")

                // Main application (required)
                success =
                    flashRegion(
                        firmwareData.application,
                        OFFSET_APPLICATION,
                        "application",
                        currentProgress,
                        80,
                        progressCallback,
                    )
                if (!success) return@withContext false

                // Console image (SPIFFS)
                consoleImage?.let { image ->
                    progressCallback.onProgress(80, "Flashing console image...")
                    success =
                        flashRegion(
                            image,
                            OFFSET_CONSOLE,
                            "console image",
                            80,
                            95,
                            progressCallback,
                        )
                    if (!success) return@withContext false
                }

                progressCallback.onProgress(95, "Finalizing...")

                // Send FLASH_END to tell bootloader to reboot and run application
                sendFlashEnd(reboot = true)

                // Give the device time to reboot
                delay(500)

                // Hard reset as backup (may not work on native USB but doesn't hurt)
                hardReset()

                progressCallback.onProgress(100, "Flash complete!")
                progressCallback.onComplete()

                true
            } catch (e: ManualBootModeRequired) {
                // Re-throw ManualBootModeRequired so it can be handled by the caller
                // finally block will handle cleanup
                Log.w(TAG, "Manual boot mode required", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Flash failed", e)
                progressCallback.onError("Flash failed: ${e.message}")
                false
            } finally {
                // Disable raw mode before disconnecting to restore normal async reads
                // (in case the connection is reused, though we disconnect anyway)
                usbBridge.disableRawMode()
                usbBridge.disconnect()
                inBootloader = false
            }
        }

    /**
     * Enter ESP32 bootloader using DTR/RTS sequence.
     *
     * DTR controls IO0 (boot mode) - active low (DTR=true means IO0=LOW)
     * RTS controls EN (chip reset) - active low (RTS=true means EN=LOW)
     *
     * To enter bootloader: IO0 must be LOW when EN goes HIGH (chip exits reset)
     *
     * ESP32-S3 with native USB uses a different sequence (USBJTAGSerialReset).
     */
    private suspend fun enterBootloader(): Boolean {
        Log.d(TAG, "Entering bootloader via DTR/RTS sequence (nativeUsb=$isNativeUsb, S3=$currentBoardIsS3)")

        if (isNativeUsb) {
            // USB-JTAG-Serial reset sequence (from esptool USBJTAGSerialReset)
            // Used for ESP32-S3/C3/etc with native USB (VID 0x303A, PID 0x1001).
            // The ROM bootloader watches for a specific PATTERN of DTR/RTS transitions
            // to enter download mode - it's not about sampling IO0 state.
            // The sequence must traverse (1,1) state instead of (0,0) for reliable triggering.
            Log.d(TAG, "Native USB reset: Setting idle state (DTR=false, RTS=false)")
            usbBridge.setRts(false)
            usbBridge.setDtr(false) // Idle state
            delay(RESET_DELAY_MS) // esptool uses 100ms

            Log.d(TAG, "Native USB reset: Setting IO0 (DTR=true)")
            usbBridge.setDtr(true) // Set IO0 signal
            usbBridge.setRts(false)
            delay(RESET_DELAY_MS) // esptool uses 100ms

            // Enter reset - traverses (1,1) state for reliable USB-JTAG-Serial triggering
            Log.d(TAG, "Native USB reset: Entering reset (RTS=true)")
            usbBridge.setRts(true) // Reset chip

            // Critical: Release DTR while still in reset, then set RTS again
            // This specific sequence triggers download mode on USB-JTAG-Serial
            Log.d(TAG, "Native USB reset: Releasing DTR while in reset (DTR=false, RTS=true)")
            usbBridge.setDtr(false)
            usbBridge.setRts(true) // Windows workaround: propagates DTR on usbser.sys driver
            delay(RESET_DELAY_MS) // esptool uses 100ms

            Log.d(TAG, "Native USB reset: Exiting reset (RTS=false)")
            usbBridge.setDtr(false) // Ensure DTR is false
            usbBridge.setRts(false) // Release reset - chip exits to bootloader
        } else {
            // Classic ESP32 reset sequence (from esptool ClassicReset)
            usbBridge.setDtr(false) // IO0=HIGH
            usbBridge.setRts(true) // EN=LOW, chip in reset
            delay(RESET_DELAY_MS)

            usbBridge.setDtr(true) // IO0=LOW (will be sampled on reset release)
            usbBridge.setRts(false) // EN=HIGH, chip out of reset -> enters bootloader
            delay(BOOT_DELAY_MS)

            usbBridge.setDtr(false) // IO0=HIGH, done
        }

        // For native USB, the chip resets and the ROM bootloader re-enumerates USB.
        // We need extra time for this to settle.
        if (isNativeUsb) {
            Log.d(TAG, "Native USB reset: Waiting for USB re-enumeration...")
            delay(500) // Give time for ROM bootloader USB to come up

            // Read boot log like esptool does - look for "waiting for download" message
            // This helps detect if bootloader mode was entered successfully
            val bootLog = ByteArray(512)
            val bootLogLen = usbBridge.readBlocking(bootLog, 200)
            if (bootLogLen > 0) {
                val bootLogStr = bootLog.take(bootLogLen).toByteArray().decodeToString()
                Log.d(TAG, "Native USB reset: Boot log ($bootLogLen bytes): $bootLogStr")
                if (bootLogStr.contains("waiting for download", ignoreCase = true)) {
                    Log.d(TAG, "Native USB reset: Detected 'waiting for download' - bootloader mode confirmed")
                }
            } else {
                Log.d(TAG, "Native USB reset: No boot log received")
            }
        }

        // Clear any remaining garbage data from the port
        delay(BOOT_DELAY_MS)
        usbBridge.drain(if (isNativeUsb) 300 else 200)

        inBootloader = true
        return true
    }

    /**
     * Quick sync check to see if device is already in bootloader mode.
     * This is used to detect manual bootloader entry before trying DTR/RTS reset.
     * Only tries a few times with short timeout to avoid delays.
     *
     * @return true if sync successful (device in bootloader mode), false otherwise
     */
    private suspend fun trySyncQuick(): Boolean {
        Log.d(TAG, "Quick sync check (detecting if already in bootloader)...")

        // Clear any garbage data and log what we drain
        val drained = ByteArray(256)
        val drainedCount = usbBridge.readBlocking(drained, 100)
        if (drainedCount > 0) {
            val hex =
                drained.take(minOf(drainedCount, 64)).joinToString(" ") {
                    String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
                }
            Log.d(TAG, "Drained $drainedCount bytes before sync: $hex")
        }

        // The sync packet is a series of 0x07 0x07 0x12 0x20 followed by 32 x 0x55
        val syncData = ByteArray(36)
        syncData[0] = 0x07
        syncData[1] = 0x07
        syncData[2] = 0x12
        syncData[3] = 0x20
        for (i in 4 until 36) {
            syncData[i] = 0x55
        }

        // Try only 3 times with short delays for quick check
        repeat(3) { attempt ->
            val response = sendCommand(ESP_SYNC, syncData, 0)

            if (response != null && response.isNotEmpty()) {
                Log.d(TAG, "Quick sync successful on attempt ${attempt + 1} - device already in bootloader")

                // Read and discard any additional sync responses
                delay(50)
                usbBridge.drain(100)

                inBootloader = true
                return true
            }

            delay(50)
        }

        Log.d(TAG, "Quick sync check failed - device not in bootloader mode")
        return false
    }

    /**
     * Sync with the ESP32 bootloader.
     *
     * @throws ManualBootModeRequired if sync fails on native USB device
     */
    private suspend fun sync(): Boolean {
        val result =
            withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                Log.d(TAG, "Syncing with bootloader...")

                // The sync packet is a series of 0x07 0x07 0x12 0x20 followed by 32 x 0x55
                val syncData = ByteArray(36)
                syncData[0] = 0x07
                syncData[1] = 0x07
                syncData[2] = 0x12
                syncData[3] = 0x20
                for (i in 4 until 36) {
                    syncData[i] = 0x55
                }

                // Try multiple times (esptool uses 5 attempts with 50ms delays)
                repeat(10) { attempt ->
                    // Flush input/output before each attempt (like esptool's flush_input/flushOutput)
                    usbBridge.drain(50)

                    val response = sendCommand(ESP_SYNC, syncData, 0)

                    if (response != null && response.isNotEmpty()) {
                        Log.d(TAG, "Sync successful on attempt ${attempt + 1}")

                        // Read and discard any additional sync responses (esptool behavior)
                        delay(50)
                        usbBridge.drain(100)

                        return@withTimeoutOrNull true
                    }

                    delay(50) // esptool uses 50ms between attempts
                }

                Log.e(TAG, "Sync failed after 10 attempts")
                false
            } ?: false

        if (!result && isNativeUsb) {
            Log.w(TAG, "Sync failed on native USB device - manual boot mode required")
            throw ManualBootModeRequired(
                "Could not sync with bootloader. This device uses native USB (ESP32-S3) " +
                    "which requires manual bootloader entry for initial flashing.\n\n" +
                    "Please enter boot mode manually:\n" +
                    "1. Hold the PRG (or BOOT) button\n" +
                    "2. Press and release the RST button\n" +
                    "3. Release the PRG (or BOOT) button\n" +
                    "4. Try flashing again\n\n" +
                    "Note: Once RNode firmware is installed, future updates will work automatically.",
            )
        }

        return result
    }

    /**
     * Change the bootloader's baud rate.
     */
    private suspend fun changeBaudRate(newBaud: Int): Boolean {
        val data = ByteArray(8)

        // New baud rate (little-endian)
        data[0] = (newBaud and 0xFF).toByte()
        data[1] = ((newBaud shr 8) and 0xFF).toByte()
        data[2] = ((newBaud shr 16) and 0xFF).toByte()
        data[3] = ((newBaud shr 24) and 0xFF).toByte()

        // Old baud rate (for reference, not always used)
        data[4] = (INITIAL_BAUD and 0xFF).toByte()
        data[5] = ((INITIAL_BAUD shr 8) and 0xFF).toByte()
        data[6] = ((INITIAL_BAUD shr 16) and 0xFF).toByte()
        data[7] = ((INITIAL_BAUD shr 24) and 0xFF).toByte()

        val response = sendCommand(ESP_CHANGE_BAUDRATE, data, 0)
        return response != null
    }

    /**
     * Read the chip detect magic register.
     * This helps "activate" the bootloader and identifies the chip type.
     */
    private suspend fun readChipDetectReg(): Long? {
        // ESP32-S3 chip detect magic register address
        val regAddr = 0x40001000L // CHIP_DETECT_MAGIC_REG_ADDR

        val data = ByteArray(4)
        putUInt32LE(data, 0, regAddr.toInt())

        Log.d(TAG, "Reading chip detect register at 0x${regAddr.toString(16)}")
        val response = sendCommand(ESP_READ_REG, data, 0)

        if (response != null && response.size >= 8) {
            // Value is in bytes 4-7 of the response (the 'val' field)
            val value =
                (response[4].toLong() and 0xFF) or
                    ((response[5].toLong() and 0xFF) shl 8) or
                    ((response[6].toLong() and 0xFF) shl 16) or
                    ((response[7].toLong() and 0xFF) shl 24)
            return value
        }
        return null
    }

    /**
     * Attach SPI flash (required for ESP32-S3 before flash operations).
     *
     * The SPI_ATTACH command configures the SPI flash pins and mode.
     * For ESP32-S3, this must be called before any flash operations.
     *
     * ROM mode requires 8 bytes: hspi_arg (4) + is_legacy flag + padding (4)
     */
    private suspend fun spiAttach(): Boolean {
        Log.d(TAG, "Attaching SPI flash...")

        // ROM mode SPI attach:
        // - bytes 0-3: hspi_arg (0 = HSPI, normal flash)
        // - bytes 4-7: is_legacy (0) + padding
        val data = ByteArray(8)
        putUInt32LE(data, 0, 0) // hspi_arg = 0 (default SPI flash mode)
        putUInt32LE(data, 4, 0) // is_legacy = 0, with padding

        val response = sendCommand(ESP_SPI_ATTACH, data, 0)
        if (response != null) {
            Log.d(TAG, "SPI attach successful")
            return true
        } else {
            Log.e(TAG, "SPI attach failed")
            return false
        }
    }

    /**
     * Flash a region of memory.
     */
    private suspend fun flashRegion(
        data: ByteArray,
        offset: Int,
        name: String,
        startProgress: Int,
        endProgress: Int,
        progressCallback: ProgressCallback,
    ): Boolean {
        Log.d(TAG, "Flashing $name: ${data.size} bytes at 0x${offset.toString(16)}")

        // ROM bootloader always uses 0x400 block size
        val blockSize = ESP_FLASH_BLOCK_SIZE
        val numBlocks = (data.size + blockSize - 1) / blockSize
        val eraseSize = numBlocks * blockSize

        Log.d(TAG, "Using block size: $blockSize, num blocks: $numBlocks, erase size: $eraseSize")

        // Send flash begin command
        // ESP32-S3 ROM mode requires extra encryption flag (4 bytes)
        val beginDataSize = if (currentBoardIsS3) 20 else 16
        val beginData = ByteArray(beginDataSize)
        // Erase size
        putUInt32LE(beginData, 0, eraseSize)
        // Number of blocks
        putUInt32LE(beginData, 4, numBlocks)
        // Block size
        putUInt32LE(beginData, 8, blockSize)
        // Offset
        putUInt32LE(beginData, 12, offset)
        // ESP32-S3 ROM: encryption flag (0 = not encrypted)
        if (currentBoardIsS3) {
            putUInt32LE(beginData, 16, 0)
        }

        // Calculate timeout based on erase size - larger regions need more time
        val eraseMB = eraseSize.toDouble() / (1024 * 1024)
        val eraseTimeout = maxOf(MIN_ERASE_TIMEOUT_MS, (eraseMB * ERASE_TIMEOUT_PER_MB_MS).toLong())
        Log.d(TAG, "Flash begin with ${eraseTimeout}ms timeout for ${String.format(Locale.ROOT, "%.2f", eraseMB)}MB erase")

        val beginResponse = sendCommand(ESP_FLASH_BEGIN, beginData, 0, eraseTimeout)
        if (beginResponse == null) {
            Log.e(TAG, "Flash begin failed for $name")
            return false
        }

        // Send data blocks
        for (blockNum in 0 until numBlocks) {
            val blockStart = blockNum * blockSize
            val blockEnd = minOf(blockStart + blockSize, data.size)
            var blockData = data.copyOfRange(blockStart, blockEnd)

            // Pad block to full size if needed
            if (blockData.size < blockSize) {
                val padded = ByteArray(blockSize) { 0xFF.toByte() }
                blockData.copyInto(padded)
                blockData = padded
            }

            // Calculate checksum
            val checksum = calculateChecksum(blockData)

            // Build data packet: size (4) + seq (4) + padding (8) + data
            val packet = ByteArray(16 + blockSize)
            putUInt32LE(packet, 0, blockSize)
            putUInt32LE(packet, 4, blockNum)
            putUInt32LE(packet, 8, 0) // Padding
            putUInt32LE(packet, 12, 0) // Padding
            blockData.copyInto(packet, 16)

            val dataResponse = sendCommand(ESP_FLASH_DATA, packet, checksum)
            if (dataResponse == null) {
                Log.e(TAG, "Flash data failed for $name at block $blockNum")
                return false
            }

            // Update progress
            val blockProgress =
                startProgress +
                    ((blockNum + 1) * (endProgress - startProgress) / numBlocks)
            progressCallback.onProgress(
                blockProgress,
                "Flashing $name: ${blockNum + 1}/$numBlocks",
            )
        }

        Log.d(TAG, "Successfully flashed $name")
        return true
    }

    /**
     * Send FLASH_END command to tell bootloader we're done flashing.
     * @param reboot If true, bootloader will reset and run the application.
     */
    private suspend fun sendFlashEnd(reboot: Boolean) {
        Log.d(TAG, "Sending FLASH_END (reboot=$reboot)")

        // For ESP32-S3, clear force download boot mode to avoid getting stuck
        // in bootloader after reset (workaround for arduino-esp32 issue #6762)
        if (currentBoardIsS3) {
            try {
                Log.d(TAG, "Clearing ESP32-S3 force download boot mode")
                writeReg(ESP32S3_RTC_CNTL_OPTION1_REG, 0, ESP32S3_RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear force download boot mode: ${e.message}")
                // Continue anyway - this is not fatal
            }
        }

        // FLASH_END packet: reboot flag (4 bytes)
        val data = ByteArray(4)
        putUInt32LE(data, 0, if (reboot) 0 else 1) // 0 = reboot, 1 = stay in bootloader (inverted!)

        // Don't wait for response if rebooting - device may reset before responding
        if (reboot) {
            val packet = buildCommandPacket(ESP_FLASH_END, data, 0)
            val slipPacket = slipEncode(packet)
            usbBridge.write(slipPacket)
            Log.d(TAG, "FLASH_END sent, device should reboot")
        } else {
            sendCommand(ESP_FLASH_END, data, 0)
        }
    }

    /**
     * Write to a register on the ESP32.
     */
    private suspend fun writeReg(
        address: Int,
        value: Int,
        mask: Int,
    ) {
        // WRITE_REG packet: addr (4) + value (4) + mask (4) + delay_us (4)
        val data = ByteArray(16)
        putUInt32LE(data, 0, address)
        putUInt32LE(data, 4, value)
        putUInt32LE(data, 8, mask)
        putUInt32LE(data, 12, 0) // delay_us = 0

        sendCommand(ESP_WRITE_REG, data, 0)
    }

    /**
     * Hard reset the device to exit bootloader.
     */
    private suspend fun hardReset() {
        Log.d(TAG, "Hard resetting device (S3=$currentBoardIsS3)")

        val resetDelay = if (currentBoardIsS3) RESET_DELAY_MS_USB else RESET_DELAY_MS

        usbBridge.setDtrRts(false, true) // Assert RTS (EN low - reset)
        delay(resetDelay)
        usbBridge.setDtrRts(false, false) // Release both
        delay(resetDelay)

        inBootloader = false
    }

    /**
     * Send a command to the bootloader and wait for response.
     */
    private suspend fun sendCommand(
        command: Byte,
        data: ByteArray,
        checksum: Int,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): ByteArray? {
        // Clear any pending data before sending
        usbBridge.drain(50)

        // Build command packet
        val packet = buildCommandPacket(command, data, checksum)

        // SLIP encode and send
        val slipPacket = slipEncode(packet)
        Log.d(
            TAG,
            "Sending command 0x${(command.toInt() and 0xFF).toString(16)}, " +
                "data size=${data.size}, packet size=${slipPacket.size}",
        )
        val bytesWritten = usbBridge.write(slipPacket)
        Log.d(TAG, "Wrote $bytesWritten bytes")

        // Give USB CDC time to transmit
        delay(10)

        // Wait for response
        return readResponse(command, timeoutMs)
    }

    /**
     * Build a command packet.
     */
    private fun buildCommandPacket(
        command: Byte,
        data: ByteArray,
        checksum: Int,
    ): ByteArray {
        // Packet format: direction (1) + command (1) + size (2) + checksum (4) + data
        val packet = ByteArray(8 + data.size)

        packet[0] = 0x00 // Direction: request
        packet[1] = command
        packet[2] = (data.size and 0xFF).toByte()
        packet[3] = ((data.size shr 8) and 0xFF).toByte()
        packet[4] = (checksum and 0xFF).toByte()
        packet[5] = ((checksum shr 8) and 0xFF).toByte()
        packet[6] = ((checksum shr 16) and 0xFF).toByte()
        packet[7] = ((checksum shr 24) and 0xFF).toByte()

        data.copyInto(packet, 8)

        return packet
    }

    /**
     * SLIP encode a packet.
     */
    private fun slipEncode(data: ByteArray): ByteArray {
        val encoded = mutableListOf<Byte>()
        encoded.add(SLIP_END)

        for (byte in data) {
            when (byte) {
                SLIP_END -> {
                    encoded.add(SLIP_ESC)
                    encoded.add(SLIP_ESC_END)
                }
                SLIP_ESC -> {
                    encoded.add(SLIP_ESC)
                    encoded.add(SLIP_ESC_ESC)
                }
                else -> encoded.add(byte)
            }
        }

        encoded.add(SLIP_END)
        return encoded.toByteArray()
    }

    /**
     * Read and decode a response from the bootloader.
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun readResponse(
        expectedCommand: Byte,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            val buffer = mutableListOf<Byte>()
            var inPacket = false
            var escape = false
            var totalBytesRead = 0

            while (true) {
                val readBuffer = ByteArray(256)
                val bytesRead = usbBridge.readBlocking(readBuffer, READ_TIMEOUT_MS)

                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }

                totalBytesRead += bytesRead
                if (totalBytesRead <= 64) {
                    // Log first bytes received for debugging
                    val hex =
                        readBuffer.take(bytesRead).joinToString(" ") {
                            String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
                        }
                    Log.d(TAG, "Received $bytesRead bytes: $hex")
                }

                for (i in 0 until bytesRead) {
                    val byte = readBuffer[i]

                    when {
                        byte == SLIP_END -> {
                            if (inPacket && buffer.size >= 8) {
                                // Parse response
                                val response = buffer.toByteArray()
                                buffer.clear()
                                inPacket = false

                                // Check if this is a valid response
                                if (response[0] == 0x01.toByte() &&
                                    response[1] == expectedCommand
                                ) {
                                    // Parse response: direction(1) + cmd(1) + size(2) + val(4) + data(size)
                                    val dataLen =
                                        (response[2].toInt() and 0xFF) or
                                            ((response[3].toInt() and 0xFF) shl 8)

                                    // Status is in the LAST 2 bytes of the data section
                                    // If no data, check the 'val' field instead
                                    val status =
                                        if (dataLen >= 2) {
                                            // Last 2 bytes of data (data starts at byte 8)
                                            response.getOrNull(8 + dataLen - 2)?.toInt()?.and(0xFF) ?: 1
                                        } else {
                                            // For empty responses, val field indicates status (byte 4)
                                            response.getOrNull(4)?.toInt()?.and(0xFF) ?: 1
                                        }

                                    if (status == 0) {
                                        return@withTimeoutOrNull response
                                    } else {
                                        val errorCode = response.getOrNull(8 + dataLen - 1)?.toInt()?.and(0xFF) ?: 0
                                        Log.w(
                                            TAG,
                                            "Command 0x${expectedCommand.toInt().and(0xFF).toString(16)} " +
                                                "returned error: status=$status, code=$errorCode",
                                        )
                                        return@withTimeoutOrNull null
                                    }
                                }
                            }
                            buffer.clear()
                            inPacket = true
                            escape = false
                        }
                        escape -> {
                            escape = false
                            when (byte) {
                                SLIP_ESC_END -> buffer.add(SLIP_END)
                                SLIP_ESC_ESC -> buffer.add(SLIP_ESC)
                                else -> buffer.add(byte)
                            }
                        }
                        byte == SLIP_ESC -> escape = true
                        inPacket -> buffer.add(byte)
                    }
                }
            }

            @Suppress("UNREACHABLE_CODE")
            null
        }

    /**
     * Calculate ESP32 checksum for data block.
     */
    private fun calculateChecksum(data: ByteArray): Int {
        var checksum = ESP_CHECKSUM_MAGIC.toInt() and 0xFF

        for (byte in data) {
            checksum = checksum xor (byte.toInt() and 0xFF)
        }

        return checksum
    }

    /**
     * Calculate MD5 hash for verification.
     */
    @Suppress("unused")
    private fun calculateMd5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    /**
     * Put a 32-bit value in little-endian format.
     */
    private fun putUInt32LE(
        array: ByteArray,
        offset: Int,
        value: Int,
    ) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = ((value shr 8) and 0xFF).toByte()
        array[offset + 2] = ((value shr 16) and 0xFF).toByte()
        array[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * Parse firmware ZIP file.
     */
    private fun parseFirmwareZip(inputStream: InputStream): ESP32FirmwareData? {
        var application: ByteArray? = null
        var bootloader: ByteArray? = null
        var partitions: ByteArray? = null
        var bootApp0: ByteArray? = null

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val name = entry.name.lowercase()
                when {
                    name.endsWith(".bin") &&
                        !name.contains("bootloader") &&
                        !name.contains("partition") &&
                        !name.contains("boot_app0") -> {
                        application = zip.readBytes()
                    }
                    name.contains("bootloader") -> {
                        bootloader = zip.readBytes()
                    }
                    name.contains("partition") -> {
                        partitions = zip.readBytes()
                    }
                    name.contains("boot_app0") -> {
                        bootApp0 = zip.readBytes()
                    }
                }
                entry = zip.nextEntry
            }
        }

        if (application == null) {
            Log.e(TAG, "No application binary found in firmware ZIP")
            return null
        }

        Log.d(
            TAG,
            "Parsed ESP32 firmware: app=${application!!.size}, " +
                "bootloader=${bootloader?.size ?: 0}, partitions=${partitions?.size ?: 0}",
        )

        return ESP32FirmwareData(
            application = application!!,
            bootloader = bootloader,
            partitions = partitions,
            bootApp0 = bootApp0,
        )
    }

    private data class ESP32FirmwareData(
        val application: ByteArray,
        val bootloader: ByteArray?,
        val partitions: ByteArray?,
        val bootApp0: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ESP32FirmwareData) return false
            return application.contentEquals(other.application) &&
                bootloader?.contentEquals(other.bootloader) ?: (other.bootloader == null) &&
                partitions?.contentEquals(other.partitions) ?: (other.partitions == null) &&
                bootApp0?.contentEquals(other.bootApp0) ?: (other.bootApp0 == null)
        }

        override fun hashCode(): Int {
            var result = application.contentHashCode()
            result = 31 * result + (bootloader?.contentHashCode() ?: 0)
            result = 31 * result + (partitions?.contentHashCode() ?: 0)
            result = 31 * result + (bootApp0?.contentHashCode() ?: 0)
            return result
        }
    }
}
