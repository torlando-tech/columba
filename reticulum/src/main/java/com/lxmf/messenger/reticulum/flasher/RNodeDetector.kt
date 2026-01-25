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
     * Save the current configuration to persistent storage.
     * This sends CMD_CONF_SAVE to make radio config changes persist across reboots.
     */
    suspend fun saveConfig(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending config save command")
        val frame = KISSCodec.createFrame(
            RNodeConstants.CMD_CONF_SAVE,
            byteArrayOf(0x00),
        )
        val result = usbBridge.write(frame) > 0
        if (result) {
            // Wait for save to complete
            delay(500)
            Log.d(TAG, "Config save command sent successfully")
        } else {
            Log.e(TAG, "Failed to send config save command")
        }
        result
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
        // First get the actual firmware hash (calculated by firmware on boot)
        val actualResponse = sendCommandAndWait(
            RNodeConstants.CMD_HASHES,
            byteArrayOf(RNodeConstants.HASH_TYPE_FIRMWARE),
        )

        // Also get the target firmware hash (stored in EEPROM) for comparison
        val targetResponse = sendCommandAndWait(
            RNodeConstants.CMD_HASHES,
            byteArrayOf(RNodeConstants.HASH_TYPE_TARGET_FIRMWARE),
        )

        if (targetResponse != null && targetResponse.size >= FIRMWARE_HASH_LENGTH + 1) {
            val targetHash = targetResponse.drop(1).take(FIRMWARE_HASH_LENGTH).toByteArray()
            Log.d(TAG, "Target firmware hash (EEPROM): ${targetHash.joinToString("") {
                String.format("%02x", it.toInt() and 0xFF)
            }}")
        }

        // Response format: [hash_type_byte] + [32 bytes of hash]
        // We need to skip the first byte (hash type) and take the next 32 bytes
        if (actualResponse != null && actualResponse.size >= FIRMWARE_HASH_LENGTH + 1) {
            val hash = actualResponse.drop(1).take(FIRMWARE_HASH_LENGTH).toByteArray()
            Log.d(TAG, "Actual firmware hash (calculated): ${hash.joinToString("") {
                String.format("%02x", it.toInt() and 0xFF)
            }}")
            return@withContext hash
        }
        Log.w(TAG, "Failed to get firmware hash (response size: ${actualResponse?.size ?: 0})")
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
     * Note: This does NOT configure TNC mode (radio parameters saved to EEPROM).
     * The device will show "Missing Config" after provisioning, which is EXPECTED
     * and CORRECT for devices used with Reticulum apps (Columba, Sideband, MeshChat).
     * These apps send radio parameters at runtime via KISS commands.
     *
     * TNC mode is only needed for standalone KISS TNC operation with amateur radio software.
     * Use enableTncMode() separately if TNC mode is specifically required.
     *
     * @param board The board type being provisioned
     * @param band The frequency band for this device (used for model code in EEPROM)
     * @param providedFirmwareHash Optional pre-calculated firmware hash from the binary file.
     *        If provided, this hash will be used instead of querying the device.
     *        The device firmware often returns zeros for the hash, so this should be
     *        calculated from the firmware binary file before flashing.
     * @return true if provisioning completed successfully
     */
    suspend fun provisionAndSetFirmwareHash(
        board: RNodeBoard,
        band: FrequencyBand = FrequencyBand.BAND_868_915,
        providedFirmwareHash: ByteArray? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting full provisioning for ${board.displayName} band=${band.displayName} (hash provided: ${providedFirmwareHash != null})")

        // Get product code and model from board and band
        val product = board.productCode
        val model = getModelForBoardAndBand(board, band)

        // Provision EEPROM (device info, checksum, signature, lock byte)
        if (!provisionDevice(product, model)) {
            Log.e(TAG, "EEPROM provisioning failed")
            return@withContext false
        }

        // Wait a moment for EEPROM writes to settle
        delay(500)

        // Note: We intentionally do NOT configure TNC mode (radio config).
        // The device will display "Missing Config" which is normal for Reticulum apps.
        // Columba/Sideband/MeshChat send radio parameters at runtime.

        // Use provided hash or try to get from device (may return zeros)
        val firmwareHash = providedFirmwareHash ?: run {
            Log.d(TAG, "No pre-calculated hash provided, attempting to get from device...")
            getFirmwareHash()
        }

        if (firmwareHash == null) {
            Log.e(TAG, "Failed to get firmware hash")
            return@withContext false
        }

        // Check if hash is all zeros (indicates device doesn't calculate hash)
        val isAllZeros = firmwareHash.all { it == 0.toByte() }
        if (isAllZeros) {
            Log.w(TAG, "Firmware hash is all zeros - this is unexpected, hash should be pre-calculated from firmware binary")
        } else {
            Log.d(TAG, "Using firmware hash: ${firmwareHash.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }}")
        }

        if (!setFirmwareHash(firmwareHash)) {
            Log.e(TAG, "Failed to set firmware hash")
            return@withContext false
        }

        // Wait for hash write
        delay(500)

        Log.i(TAG, "Provisioning completed successfully (device will show 'Missing Config' - this is normal)")
        true
    }

    /**
     * Enable TNC mode with radio configuration saved to EEPROM.
     *
     * TNC mode allows the RNode to operate as a standalone KISS TNC, which is useful
     * for amateur radio applications. When TNC mode is enabled, the device will display
     * its configured frequency instead of "Missing Config".
     *
     * IMPORTANT: TNC mode should be DISABLED when using the RNode with Reticulum apps
     * like Columba, Sideband, or MeshChat. These apps send radio parameters at runtime
     * and expect the device to be in "Normal (host-controlled)" mode.
     *
     * The RNode firmware expects radio config to be set via KISS commands (CMD_FREQUENCY,
     * CMD_BANDWIDTH, etc.) and then saved with CMD_CONF_SAVE.
     *
     * @param band The frequency band to configure
     * @param frequency Optional specific frequency in Hz (defaults based on band)
     * @param bandwidth Bandwidth in Hz (default 125000 = 125 kHz)
     * @param spreadingFactor LoRa spreading factor 7-12 (default 8)
     * @param codingRate LoRa coding rate 5-8 (default 5 = 4/5)
     * @param txPower Transmit power in dBm (default 17)
     * @return true if all commands succeeded
     */
    @Suppress("MagicNumber")
    suspend fun enableTncMode(
        band: FrequencyBand,
        frequency: Int? = null,
        bandwidth: Int = 125000,
        spreadingFactor: Int = 8,
        codingRate: Int = 5,
        txPower: Int = 17,
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Enabling TNC mode for band: ${band.displayName}")

        try {
            // Use provided frequency or default based on band
            val actualFrequency = frequency ?: when (band) {
                FrequencyBand.BAND_868_915 -> 868000000 // 868 MHz (Europe default)
                FrequencyBand.BAND_433 -> 433775000 // 433.775 MHz
                FrequencyBand.UNKNOWN -> 868000000 // Default to 868 MHz
            }

            // Set frequency via KISS command (CMD_FREQUENCY 0x01)
            Log.d(TAG, "Setting frequency: $actualFrequency Hz")
            if (!setRadioFrequency(actualFrequency)) return@withContext false

            // Set bandwidth via KISS command (CMD_BANDWIDTH 0x02)
            Log.d(TAG, "Setting bandwidth: $bandwidth Hz")
            if (!setRadioBandwidth(bandwidth)) return@withContext false

            // Set TX power via KISS command (CMD_TXPOWER 0x03)
            Log.d(TAG, "Setting TX power: $txPower dBm")
            if (!setRadioTxPower(txPower)) return@withContext false

            // Set spreading factor via KISS command (CMD_SF 0x04)
            Log.d(TAG, "Setting spreading factor: $spreadingFactor")
            if (!setRadioSpreadingFactor(spreadingFactor)) return@withContext false

            // Set coding rate via KISS command (CMD_CR 0x05)
            Log.d(TAG, "Setting coding rate: $codingRate")
            if (!setRadioCodingRate(codingRate)) return@withContext false

            // Save config to EEPROM (CMD_CONF_SAVE) - this persists the settings
            Log.d(TAG, "Saving configuration to EEPROM...")
            if (!saveConfig()) {
                Log.w(TAG, "Config save command failed")
                return@withContext false
            }

            Log.i(TAG, "TNC mode enabled successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable TNC mode", e)
            false
        }
    }

    /**
     * Disable TNC mode by deleting saved radio configuration from EEPROM.
     *
     * This returns the device to "Normal (host-controlled)" mode where it expects
     * the host application to send radio parameters at runtime. The device will
     * display "Missing Config" after this, which is normal.
     *
     * Use this to switch from TNC mode back to normal mode for use with Reticulum apps.
     *
     * @return true if the command succeeded
     */
    suspend fun disableTncMode(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Disabling TNC mode")
        try {
            val frame = KISSCodec.createFrame(RNodeConstants.CMD_CONF_DELETE, byteArrayOf(0x00))
            val result = usbBridge.write(frame) > 0
            if (result) {
                delay(500)
                Log.i(TAG, "TNC mode disabled successfully")
            } else {
                Log.e(TAG, "Failed to send config delete command")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable TNC mode", e)
            false
        }
    }

    /**
     * Set radio frequency via KISS command.
     */
    private suspend fun setRadioFrequency(frequency: Int): Boolean {
        val data = intToBytesBigEndian(frequency)
        val frame = KISSCodec.createFrame(RNodeConstants.CMD_FREQUENCY, data)
        val result = usbBridge.write(frame) > 0
        if (result) delay(100)
        return result
    }

    /**
     * Set radio bandwidth via KISS command.
     */
    private suspend fun setRadioBandwidth(bandwidth: Int): Boolean {
        val data = intToBytesBigEndian(bandwidth)
        val frame = KISSCodec.createFrame(RNodeConstants.CMD_BANDWIDTH, data)
        val result = usbBridge.write(frame) > 0
        if (result) delay(100)
        return result
    }

    /**
     * Set radio TX power via KISS command.
     */
    private suspend fun setRadioTxPower(txPower: Int): Boolean {
        val frame = KISSCodec.createFrame(RNodeConstants.CMD_TXPOWER, byteArrayOf(txPower.toByte()))
        val result = usbBridge.write(frame) > 0
        if (result) delay(100)
        return result
    }

    /**
     * Set radio spreading factor via KISS command.
     */
    private suspend fun setRadioSpreadingFactor(sf: Int): Boolean {
        val frame = KISSCodec.createFrame(RNodeConstants.CMD_SF, byteArrayOf(sf.toByte()))
        val result = usbBridge.write(frame) > 0
        if (result) delay(100)
        return result
    }

    /**
     * Set radio coding rate via KISS command.
     */
    private suspend fun setRadioCodingRate(cr: Int): Boolean {
        val frame = KISSCodec.createFrame(RNodeConstants.CMD_CR, byteArrayOf(cr.toByte()))
        val result = usbBridge.write(frame) > 0
        if (result) delay(100)
        return result
    }

    /**
     * Get the model code for a board and frequency band combination.
     *
     * The model byte encodes the frequency band variant:
     * - 0xX1, 0xX4, 0xX5: 868/915 MHz variants
     * - 0xX2, 0xX6, 0xX7: 433 MHz variants
     *
     * @param board The board type
     * @param band The frequency band
     * @return The model code byte
     */
    private fun getModelForBoardAndBand(board: RNodeBoard, band: FrequencyBand): Byte {
        // Model codes follow a pattern where the lower nibble indicates frequency:
        // 0x11 = 868/915 MHz (SX127x style)
        // 0x12 = 433 MHz (SX127x style)
        // 0x14 = 868/915 MHz (SX126x style, higher power)
        // 0x16 = 433 MHz (SX126x style, higher power)
        return when (band) {
            FrequencyBand.BAND_868_915 -> 0x11.toByte()
            FrequencyBand.BAND_433 -> 0x12.toByte()
            FrequencyBand.UNKNOWN -> 0x11.toByte() // Default to 868/915
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
