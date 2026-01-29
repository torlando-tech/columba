package com.lxmf.messenger.reticulum.flasher

import android.util.Log
import com.lxmf.messenger.reticulum.usb.KotlinUSBBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Nordic DFU (Device Firmware Update) flasher for nRF52 devices.
 *
 * Implements the Nordic DFU protocol over serial for flashing RNode firmware
 * to nRF52-based boards like the RAK4631, Heltec T114, and T-Echo.
 *
 * The DFU process:
 * 1. Enter bootloader mode via 1200 baud touch
 * 2. Reconnect at 115200 baud
 * 3. Send DFU Start packet with firmware size
 * 4. Send DFU Init packet with init data (.dat file)
 * 5. Send firmware in 512-byte chunks as DFU Data packets
 * 6. Send DFU Stop packet
 *
 * Based on: https://github.com/adafruit/Adafruit_nRF52_nrfutil
 */
@Suppress("MagicNumber", "TooManyFunctions")
class NordicDFUFlasher(
    private val usbBridge: KotlinUSBBridge,
) {
    companion object {
        private const val TAG = "Columba:NordicDFU"

        // DFU Protocol Constants
        private const val DFU_TOUCH_BAUD = 1200
        private const val DFU_FLASH_BAUD = 115200

        private const val SERIAL_PORT_OPEN_WAIT_MS = 100L
        private const val TOUCH_RESET_WAIT_MS = 1500L

        // DFU Packet Types
        private const val DFU_INIT_PACKET = 1
        private const val DFU_START_PACKET = 3
        private const val DFU_DATA_PACKET = 4
        private const val DFU_STOP_DATA_PACKET = 5

        // HCI Packet Constants
        private const val DATA_INTEGRITY_CHECK_PRESENT = 1
        private const val RELIABLE_PACKET = 1
        private const val HCI_PACKET_TYPE = 14

        // Flash timing constants
        private const val FLASH_PAGE_SIZE = 4096
        private const val FLASH_PAGE_ERASE_TIME_MS = 89.7
        private const val FLASH_WORD_WRITE_TIME_MS = 0.1
        private const val FLASH_PAGE_WRITE_TIME_MS = (FLASH_PAGE_SIZE / 4) * FLASH_WORD_WRITE_TIME_MS

        // DFU packet max size
        private const val DFU_PACKET_MAX_SIZE = 512

        // Hex type for application
        private const val HEX_TYPE_APPLICATION = 4
    }

    private var sequenceNumber = 0
    private var totalSize = 0

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
     * Flash firmware from a ZIP package to an nRF52 device.
     *
     * @param firmwareZipStream Input stream of the firmware ZIP file
     * @param deviceId USB device ID to flash
     * @param progressCallback Progress callback
     * @return true if flashing succeeded
     */
    suspend fun flash(
        firmwareZipStream: InputStream,
        deviceId: Int,
        progressCallback: ProgressCallback,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                progressCallback.onProgress(0, "Parsing firmware package...")

                // Parse the firmware ZIP
                val firmwareData = parseFirmwareZip(firmwareZipStream)
                if (firmwareData == null) {
                    progressCallback.onError("Invalid firmware package: missing required files")
                    return@withContext false
                }

                progressCallback.onProgress(5, "Entering DFU mode...")

                // Enter DFU mode (1200 baud touch)
                if (!enterDfuMode(deviceId)) {
                    progressCallback.onError("Failed to enter DFU mode")
                    return@withContext false
                }

                progressCallback.onProgress(10, "Connecting to bootloader...")

                // Connect at flash baud rate
                if (!usbBridge.connect(deviceId, DFU_FLASH_BAUD)) {
                    progressCallback.onError("Failed to connect to bootloader")
                    return@withContext false
                }

                delay(SERIAL_PORT_OPEN_WAIT_MS)

                progressCallback.onProgress(15, "Starting DFU transfer...")

                // Send DFU packets
                val success =
                    dfuSendImage(
                        firmwareData.firmware,
                        firmwareData.initPacket,
                        progressCallback,
                    )

                if (success) {
                    progressCallback.onComplete()
                }

                success
            } catch (e: Exception) {
                Log.e(TAG, "Flash failed", e)
                progressCallback.onError("Flash failed: ${e.message}")
                false
            } finally {
                usbBridge.disconnect()
            }
        }

    /**
     * Enter DFU bootloader mode using the 1200 baud touch technique.
     */
    private suspend fun enterDfuMode(deviceId: Int): Boolean {
        Log.d(TAG, "Entering DFU mode via 1200 baud touch")

        // Disconnect if connected
        usbBridge.disconnect()

        // Connect at 1200 baud
        if (!usbBridge.connect(deviceId, DFU_TOUCH_BAUD)) {
            Log.e(TAG, "Failed to connect at 1200 baud")
            return false
        }

        // Wait briefly
        delay(SERIAL_PORT_OPEN_WAIT_MS)

        // Disconnect (the 1200 baud touch triggers bootloader)
        usbBridge.disconnect()

        // Wait for device to enter DFU mode
        Log.d(TAG, "Waiting for device to enter DFU mode...")
        delay(TOUCH_RESET_WAIT_MS)

        return true
    }

    /**
     * Send the firmware image using DFU protocol.
     */
    @Suppress("ReturnCount")
    private suspend fun dfuSendImage(
        firmware: ByteArray,
        initPacket: ByteArray,
        progressCallback: ProgressCallback,
    ): Boolean {
        // Reset sequence number
        sequenceNumber = 0

        // Send DFU Start packet
        Log.d(TAG, "Sending DFU start packet (firmware size: ${firmware.size} bytes)")
        if (!sendStartDfu(HEX_TYPE_APPLICATION, 0, 0, firmware.size)) {
            progressCallback.onError("Failed to send DFU start packet")
            return false
        }

        progressCallback.onProgress(20, "Sending init packet...")

        // Send DFU Init packet
        Log.d(TAG, "Sending DFU init packet (${initPacket.size} bytes)")
        if (!sendInitPacket(initPacket)) {
            progressCallback.onError("Failed to send DFU init packet")
            return false
        }

        progressCallback.onProgress(25, "Sending firmware...")

        // Send firmware data in chunks
        Log.d(TAG, "Sending firmware data (${firmware.size} bytes in ${(firmware.size + DFU_PACKET_MAX_SIZE - 1) / DFU_PACKET_MAX_SIZE} packets)")
        if (!sendFirmware(firmware, progressCallback)) {
            progressCallback.onError("Failed to send firmware data")
            return false
        }

        progressCallback.onProgress(100, "Flashing complete!")
        Log.i(TAG, "DFU transfer complete")

        return true
    }

    /**
     * Send DFU Start packet.
     */
    private suspend fun sendStartDfu(
        mode: Int,
        softdeviceSize: Int,
        bootloaderSize: Int,
        appSize: Int,
    ): Boolean {
        val frame = mutableListOf<Byte>()

        // DFU_START_PACKET type
        frame.addAll(int32ToBytes(DFU_START_PACKET))

        // Mode
        frame.addAll(int32ToBytes(mode))

        // Image sizes (softdevice, bootloader, application)
        frame.addAll(int32ToBytes(softdeviceSize))
        frame.addAll(int32ToBytes(bootloaderSize))
        frame.addAll(int32ToBytes(appSize))

        // Remember total size for erase time calculation
        totalSize = softdeviceSize + bootloaderSize + appSize

        // Send HCI packet
        val hciPacket = createHciPacketFromFrame(frame)
        usbBridge.write(hciPacket)

        // Wait for flash erase
        val eraseWaitTime = getEraseWaitTime()
        Log.d(TAG, "Waiting ${eraseWaitTime}ms for flash erase")
        delay(eraseWaitTime.toLong())

        return true
    }

    /**
     * Send DFU Init packet.
     */
    private suspend fun sendInitPacket(initPacket: ByteArray): Boolean {
        val frame = mutableListOf<Byte>()

        // DFU_INIT_PACKET type
        frame.addAll(int32ToBytes(DFU_INIT_PACKET))

        // Init packet data
        frame.addAll(initPacket.toList())

        // Padding (required by protocol)
        frame.addAll(int16ToBytes(0x0000))

        // Send HCI packet
        val hciPacket = createHciPacketFromFrame(frame)
        usbBridge.write(hciPacket)

        // Brief delay for processing
        delay(100)

        return true
    }

    /**
     * Send firmware data in chunks.
     */
    private suspend fun sendFirmware(
        firmware: ByteArray,
        progressCallback: ProgressCallback,
    ): Boolean {
        val packets = mutableListOf<ByteArray>()

        // Chunk firmware into packets
        var offset = 0
        while (offset < firmware.size) {
            val chunkSize = minOf(DFU_PACKET_MAX_SIZE, firmware.size - offset)
            val chunk = firmware.copyOfRange(offset, offset + chunkSize)

            val frame = mutableListOf<Byte>()
            frame.addAll(int32ToBytes(DFU_DATA_PACKET))
            frame.addAll(chunk.toList())

            packets.add(createHciPacketFromFrame(frame))
            offset += chunkSize
        }

        Log.d(TAG, "Sending ${packets.size} data packets")

        // Send each packet
        for ((index, packet) in packets.withIndex()) {
            usbBridge.write(packet)

            // Wait for flash write
            delay(FLASH_PAGE_WRITE_TIME_MS.toLong())

            // Update progress (25% to 95% range for data transfer)
            val progress = 25 + ((index + 1) * 70 / packets.size)
            progressCallback.onProgress(progress, "Sending firmware: ${index + 1}/${packets.size}")
        }

        // Send DFU Stop packet
        Log.d(TAG, "Sending DFU stop packet")
        val stopFrame = mutableListOf<Byte>()
        stopFrame.addAll(int32ToBytes(DFU_STOP_DATA_PACKET))
        val stopPacket = createHciPacketFromFrame(stopFrame)
        usbBridge.write(stopPacket)

        return true
    }

    /**
     * Create an HCI packet from frame data.
     */
    private fun createHciPacketFromFrame(frame: List<Byte>): ByteArray {
        // Increment sequence number (wrap at 8)
        sequenceNumber = (sequenceNumber + 1) % 8

        // Create SLIP header
        val slipHeader =
            createSlipHeader(
                sequenceNumber,
                DATA_INTEGRITY_CHECK_PRESENT,
                RELIABLE_PACKET,
                HCI_PACKET_TYPE,
                frame.size,
            )

        // Combine header and frame
        val data = slipHeader.toMutableList()
        data.addAll(frame)

        // Calculate and append CRC
        val crc = CRC16.calculate(data)
        data.add((crc and 0xFF).toByte())
        data.add(((crc shr 8) and 0xFF).toByte())

        // SLIP encode with frame delimiters
        val encoded = mutableListOf<Byte>()
        encoded.add(0xC0.toByte()) // FEND

        for (byte in data) {
            when (byte) {
                0xC0.toByte() -> {
                    encoded.add(0xDB.toByte()) // FESC
                    encoded.add(0xDC.toByte()) // TFEND
                }
                0xDB.toByte() -> {
                    encoded.add(0xDB.toByte()) // FESC
                    encoded.add(0xDD.toByte()) // TFESC
                }
                else -> encoded.add(byte)
            }
        }

        encoded.add(0xC0.toByte()) // FEND

        return encoded.toByteArray()
    }

    /**
     * Create SLIP header bytes.
     *
     * Header format (4 bytes):
     * - Byte 0: seq | (ack << 3) | (dip << 6) | (rp << 7)
     * - Byte 1: pktType | (pktLen_low << 4)
     * - Byte 2: pktLen_high
     * - Byte 3: Header checksum (two's complement)
     */
    private fun createSlipHeader(
        seq: Int,
        dip: Int,
        rp: Int,
        pktType: Int,
        pktLen: Int,
    ): List<Byte> {
        val header = IntArray(4)

        header[0] = seq or (((seq + 1) % 8) shl 3) or (dip shl 6) or (rp shl 7)
        header[1] = pktType or ((pktLen and 0x000F) shl 4)
        header[2] = (pktLen and 0x0FF0) shr 4
        header[3] = ((header[0] + header[1] + header[2]).inv() + 1) and 0xFF

        return header.map { it.toByte() }
    }

    /**
     * Calculate erase wait time based on firmware size.
     */
    private fun getEraseWaitTime(): Double {
        // Always wait at least 500ms
        val pages = (totalSize / FLASH_PAGE_SIZE) + 1
        return maxOf(500.0, pages * FLASH_PAGE_ERASE_TIME_MS)
    }

    /**
     * Convert int32 to 4 bytes (little-endian).
     */
    private fun int32ToBytes(value: Int): List<Byte> =
        listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    /**
     * Convert int16 to 2 bytes (little-endian).
     */
    private fun int16ToBytes(value: Int): List<Byte> =
        listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )

    /**
     * Parse firmware ZIP file to extract firmware and init packet.
     */
    private fun parseFirmwareZip(inputStream: InputStream): FirmwareData? {
        var firmware: ByteArray? = null
        var initPacket: ByteArray? = null
        var manifestJson: String? = null

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                when {
                    entry.name == "manifest.json" -> {
                        manifestJson = zip.readBytes().decodeToString()
                    }
                    entry.name.endsWith(".bin") -> {
                        firmware = zip.readBytes()
                    }
                    entry.name.endsWith(".dat") -> {
                        initPacket = zip.readBytes()
                    }
                }
                entry = zip.nextEntry
            }
        }

        // Both firmware and init packet are required
        if (firmware == null || initPacket == null) {
            Log.e(TAG, "Firmware ZIP missing required files: firmware=${firmware != null}, init=${initPacket != null}")
            return null
        }

        Log.d(TAG, "Parsed firmware: ${firmware!!.size} bytes, init: ${initPacket!!.size} bytes")

        return FirmwareData(
            firmware = firmware!!,
            initPacket = initPacket!!,
            manifest = manifestJson,
        )
    }

    private data class FirmwareData(
        val firmware: ByteArray,
        val initPacket: ByteArray,
        val manifest: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FirmwareData) return false
            return firmware.contentEquals(other.firmware) &&
                initPacket.contentEquals(other.initPacket) &&
                manifest == other.manifest
        }

        override fun hashCode(): Int {
            var result = firmware.contentHashCode()
            result = 31 * result + initPacket.contentHashCode()
            result = 31 * result + (manifest?.hashCode() ?: 0)
            return result
        }
    }
}
