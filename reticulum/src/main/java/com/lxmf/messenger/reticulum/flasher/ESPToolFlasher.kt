package com.lxmf.messenger.reticulum.flasher

import android.util.Log
import com.lxmf.messenger.reticulum.usb.KotlinUSBBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.security.MessageDigest
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
    companion object {
        private const val TAG = "Columba:ESPTool"

        // Baud rates
        private const val INITIAL_BAUD = 115200
        private const val FLASH_BAUD = 921600

        // Timeouts
        private const val SYNC_TIMEOUT_MS = 5000L
        private const val COMMAND_TIMEOUT_MS = 3000L
        private const val READ_TIMEOUT_MS = 100

        // ESP32 ROM commands
        private const val ESP_FLASH_BEGIN: Byte = 0x02
        private const val ESP_FLASH_DATA: Byte = 0x03
        private const val ESP_FLASH_END: Byte = 0x04
        private const val ESP_MEM_BEGIN: Byte = 0x05
        private const val ESP_MEM_END: Byte = 0x06
        private const val ESP_MEM_DATA: Byte = 0x07
        private const val ESP_SYNC: Byte = 0x08
        private const val ESP_WRITE_REG: Byte = 0x09
        private const val ESP_READ_REG: Byte = 0x0A
        private const val ESP_SPI_SET_PARAMS: Byte = 0x0B
        private const val ESP_SPI_ATTACH: Byte = 0x0D
        private const val ESP_CHANGE_BAUDRATE: Byte = 0x0F
        private const val ESP_FLASH_DEFL_BEGIN: Byte = 0x10
        private const val ESP_FLASH_DEFL_DATA: Byte = 0x11
        private const val ESP_FLASH_DEFL_END: Byte = 0x12
        private const val ESP_SPI_FLASH_MD5: Byte = 0x13

        // SLIP constants
        private const val SLIP_END: Byte = 0xC0.toByte()
        private const val SLIP_ESC: Byte = 0xDB.toByte()
        private const val SLIP_ESC_END: Byte = 0xDC.toByte()
        private const val SLIP_ESC_ESC: Byte = 0xDD.toByte()

        // Flash parameters
        private const val ESP_FLASH_BLOCK_SIZE = 0x400 // 1024 bytes
        private const val ESP_CHECKSUM_MAGIC: Byte = 0xEF.toByte()

        // Standard flash offsets for ESP32
        const val OFFSET_BOOTLOADER = 0x1000
        const val OFFSET_PARTITIONS = 0x8000
        const val OFFSET_BOOT_APP0 = 0xE000
        const val OFFSET_APPLICATION = 0x10000
        const val OFFSET_CONSOLE = 0x210000

        // Bootloader entry timing
        private const val RESET_DELAY_MS = 100L
        private const val BOOT_DELAY_MS = 50L
    }

    private var inBootloader = false

    /**
     * Callback interface for flash progress updates.
     */
    interface ProgressCallback {
        fun onProgress(percent: Int, message: String)
        fun onError(error: String)
        fun onComplete()
    }

    /**
     * Flash firmware from a ZIP package to an ESP32 device.
     *
     * @param firmwareZipStream Input stream of the firmware ZIP file
     * @param deviceId USB device ID to flash
     * @param consoleImageStream Optional console image (SPIFFS) stream
     * @param progressCallback Progress callback
     * @return true if flashing succeeded
     */
    suspend fun flash(
        firmwareZipStream: InputStream,
        deviceId: Int,
        consoleImageStream: InputStream? = null,
        progressCallback: ProgressCallback,
    ): Boolean = withContext(Dispatchers.IO) {
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

            progressCallback.onProgress(8, "Entering bootloader...")

            // Enter bootloader mode
            if (!enterBootloader()) {
                progressCallback.onError("Failed to enter bootloader mode")
                return@withContext false
            }

            progressCallback.onProgress(10, "Syncing with bootloader...")

            // Sync with bootloader
            if (!sync()) {
                progressCallback.onError("Failed to sync with bootloader")
                return@withContext false
            }

            progressCallback.onProgress(12, "Switching to high-speed mode...")

            // Try to switch to higher baud rate
            if (changeBaudRate(FLASH_BAUD)) {
                delay(50)
                usbBridge.setBaudRate(FLASH_BAUD)
                Log.d(TAG, "Switched to $FLASH_BAUD baud")
            } else {
                Log.w(TAG, "Could not switch baud rate, continuing at $INITIAL_BAUD")
            }

            progressCallback.onProgress(15, "Flashing bootloader...")

            // Flash each region
            var success = true
            var currentProgress = 15

            // Bootloader (if present)
            firmwareData.bootloader?.let { bootloader ->
                success = flashRegion(
                    bootloader,
                    OFFSET_BOOTLOADER,
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
                success = flashRegion(
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
                success = flashRegion(
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
            success = flashRegion(
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
                success = flashRegion(
                    image,
                    OFFSET_CONSOLE,
                    "console image",
                    80,
                    95,
                    progressCallback,
                )
                if (!success) return@withContext false
            }

            progressCallback.onProgress(95, "Verifying...")

            // Hard reset to exit bootloader
            hardReset()

            progressCallback.onProgress(100, "Flash complete!")
            progressCallback.onComplete()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Flash failed", e)
            progressCallback.onError("Flash failed: ${e.message}")
            false
        } finally {
            usbBridge.disconnect()
            inBootloader = false
        }
    }

    /**
     * Enter ESP32 bootloader using DTR/RTS sequence.
     *
     * The ESP32 bootloader is entered by:
     * 1. Assert DTR (hold EN low)
     * 2. Assert RTS (hold IO0 low)
     * 3. Release DTR (release EN - chip resets)
     * 4. Wait briefly
     * 5. Release RTS (release IO0)
     */
    private suspend fun enterBootloader(): Boolean {
        Log.d(TAG, "Entering bootloader via DTR/RTS sequence")

        // Classic ESP32 bootloader entry sequence
        usbBridge.setDtrRts(false, true)  // RTS high, DTR low
        delay(RESET_DELAY_MS)

        usbBridge.setDtrRts(true, true)   // Both high (hold in reset)
        delay(BOOT_DELAY_MS)

        usbBridge.setDtrRts(true, false)  // DTR high, RTS low (IO0 low during reset)
        delay(RESET_DELAY_MS)

        usbBridge.setDtrRts(false, false) // Release both
        delay(BOOT_DELAY_MS)

        // Clear any garbage data
        usbBridge.drain(200)

        inBootloader = true
        return true
    }

    /**
     * Sync with the ESP32 bootloader.
     */
    private suspend fun sync(): Boolean = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
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

        // Try multiple times
        repeat(10) { attempt ->
            val response = sendCommand(ESP_SYNC, syncData, 0)

            if (response != null && response.isNotEmpty()) {
                Log.d(TAG, "Sync successful on attempt ${attempt + 1}")

                // Read and discard any additional sync responses
                delay(100)
                usbBridge.drain(100)

                return@withTimeoutOrNull true
            }

            delay(100)
        }

        Log.e(TAG, "Sync failed after 10 attempts")
        false
    } ?: false

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

        // Calculate number of blocks
        val blockSize = ESP_FLASH_BLOCK_SIZE
        val numBlocks = (data.size + blockSize - 1) / blockSize
        val eraseSize = numBlocks * blockSize

        // Send flash begin command
        val beginData = ByteArray(16)
        // Erase size
        putUInt32LE(beginData, 0, eraseSize)
        // Number of blocks
        putUInt32LE(beginData, 4, numBlocks)
        // Block size
        putUInt32LE(beginData, 8, blockSize)
        // Offset
        putUInt32LE(beginData, 12, offset)

        val beginResponse = sendCommand(ESP_FLASH_BEGIN, beginData, 0)
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
            val blockProgress = startProgress +
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
     * Hard reset the device to exit bootloader.
     */
    private suspend fun hardReset() {
        Log.d(TAG, "Hard resetting device")

        usbBridge.setDtrRts(false, true)  // Assert RTS (EN low - reset)
        delay(RESET_DELAY_MS)
        usbBridge.setDtrRts(false, false) // Release both
        delay(RESET_DELAY_MS)

        inBootloader = false
    }

    /**
     * Send a command to the bootloader and wait for response.
     */
    private suspend fun sendCommand(
        command: Byte,
        data: ByteArray,
        checksum: Int,
    ): ByteArray? {
        // Build command packet
        val packet = buildCommandPacket(command, data, checksum)

        // SLIP encode and send
        val slipPacket = slipEncode(packet)
        usbBridge.write(slipPacket)

        // Wait for response
        return readResponse(command)
    }

    /**
     * Build a command packet.
     */
    private fun buildCommandPacket(command: Byte, data: ByteArray, checksum: Int): ByteArray {
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
    private suspend fun readResponse(expectedCommand: Byte): ByteArray? =
        withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            val buffer = mutableListOf<Byte>()
            var inPacket = false
            var escape = false

            while (true) {
                val readBuffer = ByteArray(256)
                val bytesRead = usbBridge.readBlocking(readBuffer, READ_TIMEOUT_MS)

                if (bytesRead <= 0) {
                    delay(10)
                    continue
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
                                    // Check status (bytes 8-11)
                                    val status = response.getOrNull(8)?.toInt()?.and(0xFF) ?: 1
                                    if (status == 0) {
                                        return@withTimeoutOrNull response
                                    } else {
                                        Log.w(
                                            TAG,
                                            "Command 0x${expectedCommand.toInt().and(0xFF).toString(16)} " +
                                                "returned error: $status",
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
    private fun calculateMd5(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(data)
    }

    /**
     * Put a 32-bit value in little-endian format.
     */
    private fun putUInt32LE(array: ByteArray, offset: Int, value: Int) {
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
                    name.endsWith(".bin") && !name.contains("bootloader") &&
                        !name.contains("partition") && !name.contains("boot_app0") -> {
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
