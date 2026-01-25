package com.lxmf.messenger.reticulum.flasher

import android.util.Log
import com.lxmf.messenger.reticulum.usb.KotlinUSBBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Detects RNode devices over USB and retrieves their capabilities.
 *
 * This class communicates with an RNode using KISS protocol to determine:
 * - Platform (AVR, ESP32, NRF52)
 * - MCU type
 * - Board/product type
 * - Firmware version
 * - Provisioning status
 *
 * Based on the device detection code in rnode.js from rnode-flasher.
 */
class RNodeDetector(
    private val usbBridge: KotlinUSBBridge,
) {
    companion object {
        private const val TAG = "Columba:RNodeDetector"
        private const val COMMAND_TIMEOUT_MS = 2000L
        private const val READ_POLL_INTERVAL_MS = 50L
        private const val EEPROM_WRITE_DELAY_MS = 85L // Time to wait after each EEPROM write
        private const val SIGNATURE_LENGTH = 128
        private const val CHECKSUM_LENGTH = 16
        private const val FIRMWARE_HASH_LENGTH = 32
    }

    private val frameParser = KISSFrameParser()
    private val pendingResponses = mutableMapOf<Byte, ByteArray?>()

    /**
     * Detect if the connected device is an RNode.
     *
     * @return true if device responds to RNode detect command
     */
    suspend fun isRNode(): Boolean = withContext(Dispatchers.IO) {
        val response = sendCommandAndWait(
            RNodeConstants.CMD_DETECT,
            byteArrayOf(RNodeConstants.DETECT_REQ),
        )

        if (response != null && response.isNotEmpty()) {
            val isRNode = response[0] == RNodeConstants.DETECT_RESP
            Log.d(TAG, "Device detection response: isRNode=$isRNode")
            return@withContext isRNode
        }

        Log.d(TAG, "No detection response received")
        false
    }

    /**
     * Get full device information for an RNode.
     *
     * @return Device info or null if detection failed
     */
    suspend fun getDeviceInfo(): RNodeDeviceInfo? = withContext(Dispatchers.IO) {
        try {
            // First verify it's an RNode
            if (!isRNode()) {
                Log.w(TAG, "Device did not respond to RNode detection")
                return@withContext null
            }

            // Get platform
            val platformByte = getByteValue(RNodeConstants.CMD_PLATFORM)
            val platform = platformByte?.let { RNodePlatform.fromCode(it) } ?: RNodePlatform.UNKNOWN

            // Get MCU
            val mcuByte = getByteValue(RNodeConstants.CMD_MCU)
            val mcu = mcuByte?.let { RNodeMcu.fromCode(it) } ?: RNodeMcu.UNKNOWN

            // Get board (this is actually the product code in ROM)
            val boardByte = getByteValue(RNodeConstants.CMD_BOARD)

            // Get firmware version
            val firmwareVersion = getFirmwareVersion()

            // Read ROM for detailed device info
            val romData = readRom()
            val romInfo = romData?.let { parseRom(it) }

            val board = romInfo?.product?.let { RNodeBoard.fromProductCode(it) }
                ?: boardByte?.let { RNodeBoard.fromProductCode(it) }
                ?: RNodeBoard.UNKNOWN

            Log.i(
                TAG,
                "Detected RNode: platform=$platform, mcu=$mcu, board=$board, " +
                    "fw=$firmwareVersion, provisioned=${romInfo?.isProvisioned}",
            )

            RNodeDeviceInfo(
                platform = platform,
                mcu = mcu,
                board = board,
                firmwareVersion = firmwareVersion,
                isProvisioned = romInfo?.isProvisioned ?: false,
                isConfigured = romInfo?.isConfigured ?: false,
                serialNumber = romInfo?.serialNumber,
                hardwareRevision = romInfo?.hardwareRevision,
                product = romInfo?.product ?: 0,
                model = romInfo?.model ?: 0,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device info", e)
            null
        }
    }

    /**
     * Get the firmware version string.
     */
    private suspend fun getFirmwareVersion(): String? {
        val response = sendCommandAndWait(
            RNodeConstants.CMD_FW_VERSION,
            byteArrayOf(0x00),
        )

        if (response != null && response.size >= 2) {
            val major = response[0].toInt() and 0xFF
            val minor = response[1].toInt() and 0xFF
            val minorStr = if (minor < 10) "0$minor" else minor.toString()
            return "$major.$minorStr"
        }
        return null
    }

    /**
     * Get a single byte value from a command.
     */
    private suspend fun getByteValue(command: Byte): Byte? {
        val response = sendCommandAndWait(command, byteArrayOf(0x00))
        return response?.firstOrNull()
    }

    /**
     * Read the ROM/EEPROM contents.
     */
    private suspend fun readRom(): ByteArray? {
        return sendCommandAndWait(
            RNodeConstants.CMD_ROM_READ,
            byteArrayOf(0x00),
        )
    }

    /**
     * Parse ROM data into structured info.
     */
    @Suppress("MagicNumber")
    private fun parseRom(rom: ByteArray): RomInfo? {
        if (rom.size < 0xA8) {
            Log.w(TAG, "ROM data too short: ${rom.size} bytes")
            return null
        }

        val infoLockByte = rom[RNodeConstants.ADDR_INFO_LOCK]
        val isInfoLocked = infoLockByte == RNodeConstants.INFO_LOCK_BYTE

        if (!isInfoLocked) {
            Log.d(TAG, "Device ROM is not locked (unprovisioned)")
            return RomInfo(
                product = rom[RNodeConstants.ADDR_PRODUCT],
                model = rom[RNodeConstants.ADDR_MODEL],
                hardwareRevision = rom[RNodeConstants.ADDR_HW_REV].toInt() and 0xFF,
                serialNumber = null,
                isProvisioned = false,
                isConfigured = false,
            )
        }

        // Extract serial number (4 bytes, big-endian)
        val serialNumber = (
            (rom[RNodeConstants.ADDR_SERIAL].toInt() and 0xFF) shl 24
            ) or (
            (rom[RNodeConstants.ADDR_SERIAL + 1].toInt() and 0xFF) shl 16
            ) or (
            (rom[RNodeConstants.ADDR_SERIAL + 2].toInt() and 0xFF) shl 8
            ) or (
            rom[RNodeConstants.ADDR_SERIAL + 3].toInt() and 0xFF
            )

        val confOkByte = rom[RNodeConstants.ADDR_CONF_OK]
        val isConfigured = confOkByte == RNodeConstants.CONF_OK_BYTE

        return RomInfo(
            product = rom[RNodeConstants.ADDR_PRODUCT],
            model = rom[RNodeConstants.ADDR_MODEL],
            hardwareRevision = rom[RNodeConstants.ADDR_HW_REV].toInt() and 0xFF,
            serialNumber = serialNumber,
            isProvisioned = true,
            isConfigured = isConfigured,
        )
    }

    /**
     * Send a KISS command and wait for response.
     */
    private suspend fun sendCommandAndWait(
        command: Byte,
        data: ByteArray,
    ): ByteArray? = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
        // Clear any pending data
        usbBridge.clearReadBuffer()
        frameParser.reset()

        // Send the command
        val frame = KISSCodec.createFrame(command, data)
        val bytesWritten = usbBridge.write(frame)

        if (bytesWritten < 0) {
            Log.e(TAG, "Failed to write command 0x${command.toInt().and(0xFF).toString(16)}")
            return@withTimeoutOrNull null
        }

        Log.v(TAG, "Sent command 0x${command.toInt().and(0xFF).toString(16)}, waiting for response")

        // Wait for response
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT_MS) {
            val received = usbBridge.read()

            if (received.isNotEmpty()) {
                val frames = frameParser.processBytes(received)

                for (kissFrame in frames) {
                    if (kissFrame.command == command) {
                        Log.v(
                            TAG,
                            "Received response for command 0x${command.toInt().and(0xFF).toString(16)}: " +
                                "${kissFrame.data.size} bytes",
                        )
                        return@withTimeoutOrNull kissFrame.data
                    }
                }
            }

            delay(READ_POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Timeout waiting for response to command 0x${command.toInt().and(0xFF).toString(16)}")
        null
    }

    /**
     * Reset the device.
     */
    suspend fun resetDevice(): Boolean = withContext(Dispatchers.IO) {
        val frame = KISSCodec.createFrame(
            RNodeConstants.CMD_RESET,
            byteArrayOf(RNodeConstants.CMD_RESET_BYTE),
        )
        usbBridge.write(frame) > 0
    }

    /**
     * Indicate to the RNode firmware that a firmware update is about to begin.
     *
     * This command tells the RNode firmware to prepare for an update. For ESP32 devices,
     * this enables the auto-reset functionality that allows esptool to put the device
     * into bootloader mode via DTR/RTS signals.
     *
     * Based on rnodeconf's indicate_firmware_update() which sends CMD_FW_UPD (0x61)
     * before attempting to flash.
     *
     * @return true if the command was sent successfully
     */
    suspend fun indicateFirmwareUpdate(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending firmware update indication to RNode")
        val frame = KISSCodec.createFrame(
            RNodeConstants.CMD_FW_UPD,
            byteArrayOf(0x01),
        )
        val result = usbBridge.write(frame) > 0
        if (result) {
            // Give firmware time to process the command
            delay(100)
            Log.d(TAG, "Firmware update indication sent successfully")
        } else {
            Log.e(TAG, "Failed to send firmware update indication")
        }
        result
    }

    /**
     * Write a single byte to EEPROM.
     *
     * @param address The EEPROM address to write to
     * @param value The byte value to write
     * @return true if the write was sent successfully
     */
    suspend fun writeEeprom(address: Int, value: Byte): Boolean = withContext(Dispatchers.IO) {
        Log.v(TAG, "Writing EEPROM: addr=0x${address.toString(16)}, value=0x${(value.toInt() and 0xFF).toString(16)}")
        val frame = KISSCodec.createFrame(
            RNodeConstants.CMD_ROM_WRITE,
            byteArrayOf(address.toByte(), value),
        )
        val result = usbBridge.write(frame) > 0
        if (result) {
            // Wait for EEPROM write to complete
            delay(EEPROM_WRITE_DELAY_MS)
        }
        result
    }

    /**
     * Get the current firmware hash from the device.
     * This is the SHA256 hash that the firmware calculates on boot.
     *
     * @return The 32-byte firmware hash, or null if not available
     */
    suspend fun getFirmwareHash(): ByteArray? = withContext(Dispatchers.IO) {
        val response = sendCommandAndWait(
            RNodeConstants.CMD_HASHES,
            byteArrayOf(RNodeConstants.HASH_TYPE_FIRMWARE),
        )

        if (response != null && response.size >= FIRMWARE_HASH_LENGTH) {
            Log.d(TAG, "Got firmware hash: ${response.take(FIRMWARE_HASH_LENGTH).joinToString("") {
                String.format("%02x", it.toInt() and 0xFF)
            }}")
            return@withContext response.take(FIRMWARE_HASH_LENGTH).toByteArray()
        }
        Log.w(TAG, "Failed to get firmware hash")
        null
    }

    /**
     * Set the target firmware hash in EEPROM.
     * This should match the actual firmware hash for the device to be considered valid.
     *
     * @param hash The 32-byte SHA256 hash
     * @return true if the command was sent successfully
     */
    suspend fun setFirmwareHash(hash: ByteArray): Boolean = withContext(Dispatchers.IO) {
        require(hash.size == FIRMWARE_HASH_LENGTH) { "Firmware hash must be $FIRMWARE_HASH_LENGTH bytes" }

        Log.d(TAG, "Setting firmware hash: ${hash.joinToString("") {
            String.format("%02x", it.toInt() and 0xFF)
        }}")

        val frame = KISSCodec.createFrame(RNodeConstants.CMD_FW_HASH, hash)
        val result = usbBridge.write(frame) > 0
        if (result) {
            // Wait for hash to be written
            delay(1000)
            Log.d(TAG, "Firmware hash set successfully")
        } else {
            Log.e(TAG, "Failed to set firmware hash")
        }
        result
    }

    /**
     * Provision a device's EEPROM with device information.
     * This sets up the device identity including product, model, serial number, etc.
     * Uses a blank (all-zeros) signature since we don't have signing keys.
     *
     * @param product The product code (e.g., PRODUCT_H32_V4 for Heltec LoRa32 V4)
     * @param model The model code (frequency band variant)
     * @param hardwareRevision Hardware revision number
     * @param serialNumber Unique serial number for this device
     * @return true if provisioning completed successfully
     */
    @Suppress("MagicNumber")
    suspend fun provisionDevice(
        product: Byte,
        model: Byte,
        hardwareRevision: Byte = 0x01,
        serialNumber: Int = 1,
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Provisioning device: product=0x${(product.toInt() and 0xFF).toString(16)}, " +
            "model=0x${(model.toInt() and 0xFF).toString(16)}, hwRev=$hardwareRevision, serial=$serialNumber")

        try {
            // Generate timestamp
            val timestamp = (System.currentTimeMillis() / 1000).toInt()

            // Convert to big-endian byte arrays
            val serialBytes = intToBytesBigEndian(serialNumber)
            val timestampBytes = intToBytesBigEndian(timestamp)

            // Calculate MD5 checksum of device info
            val infoChunk = byteArrayOf(product, model, hardwareRevision) + serialBytes + timestampBytes
            val md5 = MessageDigest.getInstance("MD5")
            val checksum = md5.digest(infoChunk)

            Log.d(TAG, "Info chunk: ${infoChunk.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }}")
            Log.d(TAG, "Checksum: ${checksum.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }}")

            // Blank signature (128 zeros)
            val signature = ByteArray(SIGNATURE_LENGTH) { 0x00 }

            // Write device info
            Log.d(TAG, "Writing device info...")
            if (!writeEeprom(RNodeConstants.ADDR_PRODUCT, product)) return@withContext false
            if (!writeEeprom(RNodeConstants.ADDR_MODEL, model)) return@withContext false
            if (!writeEeprom(RNodeConstants.ADDR_HW_REV, hardwareRevision)) return@withContext false

            // Write serial number (4 bytes)
            for (i in 0 until 4) {
                if (!writeEeprom(RNodeConstants.ADDR_SERIAL + i, serialBytes[i])) return@withContext false
            }

            // Write timestamp (4 bytes)
            for (i in 0 until 4) {
                if (!writeEeprom(RNodeConstants.ADDR_MADE + i, timestampBytes[i])) return@withContext false
            }

            // Write checksum (16 bytes)
            Log.d(TAG, "Writing checksum...")
            for (i in 0 until CHECKSUM_LENGTH) {
                if (!writeEeprom(RNodeConstants.ADDR_CHKSUM + i, checksum[i])) return@withContext false
            }

            // Write signature (128 bytes - blank)
            Log.d(TAG, "Writing signature (blank)...")
            for (i in 0 until SIGNATURE_LENGTH) {
                if (!writeEeprom(RNodeConstants.ADDR_SIGNATURE + i, signature[i])) return@withContext false
            }

            // Write lock byte
            Log.d(TAG, "Writing lock byte...")
            if (!writeEeprom(RNodeConstants.ADDR_INFO_LOCK, RNodeConstants.INFO_LOCK_BYTE)) return@withContext false

            // Wait for NRF52 EEPROM to complete (slower)
            delay(3000)

            Log.i(TAG, "Device provisioned successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to provision device", e)
            false
        }
    }

    /**
     * Complete provisioning flow: provision EEPROM and set firmware hash.
     * This should be called after flashing firmware and resetting the device.
     *
     * @param board The board type being provisioned
     * @return true if provisioning completed successfully
     */
    suspend fun provisionAndSetFirmwareHash(board: RNodeBoard): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting full provisioning for ${board.displayName}")

        // Get product code and model from board
        val product = board.productCode
        val model = getDefaultModelForBoard(board)

        // Provision EEPROM
        if (!provisionDevice(product, model)) {
            Log.e(TAG, "EEPROM provisioning failed")
            return@withContext false
        }

        // Wait a moment for EEPROM writes to settle
        delay(500)

        // Get and set firmware hash
        val firmwareHash = getFirmwareHash()
        if (firmwareHash == null) {
            Log.e(TAG, "Failed to get firmware hash")
            return@withContext false
        }

        if (!setFirmwareHash(firmwareHash)) {
            Log.e(TAG, "Failed to set firmware hash")
            return@withContext false
        }

        // Wait for hash write
        delay(500)

        Log.i(TAG, "Provisioning completed successfully")
        true
    }

    /**
     * Get the default model code for a board (assumes 868/915 MHz band).
     */
    private fun getDefaultModelForBoard(board: RNodeBoard): Byte {
        // Most boards use model 0x11 for 868/915 MHz
        // Model 0x12 is for 433 MHz variants
        return when (board) {
            RNodeBoard.RAK4631 -> RNodeConstants.MODEL_11
            else -> 0x11 // Default to 868/915 MHz model
        }
    }

    /**
     * Convert an integer to a 4-byte big-endian byte array.
     */
    @Suppress("MagicNumber")
    private fun intToBytesBigEndian(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    private data class RomInfo(
        val product: Byte,
        val model: Byte,
        val hardwareRevision: Int,
        val serialNumber: Int?,
        val isProvisioned: Boolean,
        val isConfigured: Boolean,
    )
}
