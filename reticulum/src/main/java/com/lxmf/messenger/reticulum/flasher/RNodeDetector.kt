package com.lxmf.messenger.reticulum.flasher

import android.util.Log
import com.lxmf.messenger.reticulum.usb.KotlinUSBBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    private data class RomInfo(
        val product: Byte,
        val model: Byte,
        val hardwareRevision: Int,
        val serialNumber: Int?,
        val isProvisioned: Boolean,
        val isConfigured: Boolean,
    )
}
