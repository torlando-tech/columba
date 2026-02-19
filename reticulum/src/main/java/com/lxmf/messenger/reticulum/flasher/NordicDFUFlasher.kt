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
        private const val NRF52_BOOTLOADER_PID = 0x0071

        private const val SERIAL_PORT_OPEN_WAIT_MS = 100L
        private const val DTR_DEASSERT_WAIT_MS = 50L
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

        // Flash word write time: nRF52840 ~41us max, nRF52832 ~338us max.
        // Using conservative estimate from reference: (4096/4) * 0.000100 = 0.1024s
        private const val FLASH_PAGE_WRITE_TIME_MS = 103L // rounded up from 102.4

        // DFU packet max size
        private const val DFU_PACKET_MAX_SIZE = 512

        // How many data packets fill one flash page (4096 / 512 = 8)
        private const val PACKETS_PER_PAGE = FLASH_PAGE_SIZE / DFU_PACKET_MAX_SIZE

        // ACK handling constants
        // Reference uses 1s, but our USB serial stack has higher latency.
        // Bootloader response times vary: Init ~1-3.3s, data unknown.
        // Retries send duplicate packets that confuse the bootloader state
        // machine, so we use a generous timeout to succeed on first attempt.
        private const val ACK_READ_TIMEOUT_MS = 5000L
        private const val MAX_RETRIES = 3

        // Short timeout for readBlocking polls inside readAckNr.
        // We use short reads to keep the polling loop responsive.
        private const val DFU_READ_POLL_MS = 200

        // Post-DFU settle time. After the DFU Stop ACK, the bootloader
        // validates firmware CRC, writes bootloader settings to flash, and
        // prepares to reset. For single-bank app-only updates, this takes
        // ~200ms (one flash page erase + write). We wait generously to
        // ensure the bootloader finishes before we disconnect USB.
        private const val POST_DFU_SETTLE_MS = 2000L

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

                // Check if device is already in bootloader mode (PID 0x0071).
                // If so, skip the 1200-baud touch — the bootloader doesn't handle it
                // and won't re-enumerate.
                val deviceInfo = usbBridge.getConnectedUsbDevices().find { it.deviceId == deviceId }
                val alreadyInBootloader = deviceInfo?.productId == NRF52_BOOTLOADER_PID

                val bootloaderDeviceId: Int
                if (alreadyInBootloader) {
                    Log.d(TAG, "Device already in bootloader mode (PID=0x0071), skipping 1200-baud touch")
                    bootloaderDeviceId = deviceId
                } else {
                    progressCallback.onProgress(5, "Entering DFU mode...")

                    // Enter DFU mode (1200 baud touch)
                    if (!enterDfuMode(deviceId)) {
                        progressCallback.onError("Failed to enter DFU mode")
                        return@withContext false
                    }

                    progressCallback.onProgress(10, "Connecting to bootloader...")

                    // After the 1200-baud touch, the device re-enumerates in bootloader
                    // mode with a new USB device ID (and different PID). Find it.
                    val foundId = findBootloaderDevice(deviceId)
                    if (foundId == null) {
                        progressCallback.onError("Failed to find bootloader device after reset")
                        return@withContext false
                    }
                    bootloaderDeviceId = foundId
                }

                // Connect at flash baud rate WITHOUT starting the ioManager.
                // The ioManager calls port.read() in a loop, and when it times
                // out (returns 0 bytes), CdcAcmSerialDriver calls testConnection()
                // — a USB GET_STATUS control transfer. The nRF52840 bootloader's
                // minimal TinyUSB stack cannot handle GET_STATUS, causing a USB
                // bus reset that kills the connection. By skipping the ioManager
                // entirely, no port.read() or testConnection() ever occurs.
                // All DFU I/O uses readBlockingDirect()/writeBlockingDirect()
                // which call bulkTransfer() directly without testConnection.
                //
                // After a 1200-baud touch, the device re-enumerates with a new USB
                // device ID. Android revokes permission for the old ID and shows a
                // "Open with Columba?" system dialog. Until the user taps it (or
                // the system auto-grants via device_filter.xml), connect() will fail
                // with "No permission". Retry to give time for permission grant.
                if (!connectWithRetry(bootloaderDeviceId, DFU_FLASH_BAUD)) {
                    progressCallback.onError("Failed to connect to bootloader")
                    return@withContext false
                }

                // Set raw mode flag (no ioManager to stop, just sets the flag
                // so onRunError suppresses disconnects and readBuffer is cleared).
                usbBridge.enableRawMode(drainPort = false)

                delay(SERIAL_PORT_OPEN_WAIT_MS)

                progressCallback.onProgress(15, "Starting DFU transfer...")

                // Send DFU packets
                val success =
                    dfuSendImage(
                        firmwareData.firmware,
                        firmwareData.initPacket,
                        progressCallback,
                    )

                // Disconnect BEFORE settle wait (matches reference: close → sleep).
                // The bootloader validates firmware CRC and writes bootloader settings
                // after DFU Stop — closing the port prevents USB interference.
                // disconnect() first so currentPort=null, then disableRawMode() just
                // clears the flag without restarting the ioManager (which would
                // immediately call testConnection() on the closing port).
                usbBridge.disconnect()
                usbBridge.disableRawMode()

                if (success) {
                    // Wait for bootloader to finalize: validate CRC, write settings,
                    // and (for dual-bank) copy bank1→bank0
                    Log.d(TAG, "Waiting for bootloader to finalize firmware...")
                    delay(POST_DFU_SETTLE_MS)
                    progressCallback.onComplete()
                }

                success
            } catch (e: Exception) {
                Log.e(TAG, "Flash failed", e)
                progressCallback.onError("Flash failed: ${e.message}")
                false
            } finally {
                // Safety net: disconnect if still connected (e.g. after exception)
                usbBridge.disconnect()
                usbBridge.disableRawMode()
            }
        }

    /**
     * Enter DFU bootloader mode using the 1200 baud touch technique.
     *
     * The nRF52840's Adafruit bootloader monitors for a specific DTR sequence:
     * 1. SET_LINE_CODING at 1200 baud → firmware flags _line_coding_1200 = true
     * 2. SET_CONTROL_LINE_STATE with DTR=false → triggers system reset into bootloader
     *
     * On Android USB serial, port.close() does NOT send SET_CONTROL_LINE_STATE,
     * so we must explicitly deassert DTR before disconnecting to trigger the touch.
     */
    private suspend fun enterDfuMode(deviceId: Int): Boolean {
        Log.d(TAG, "Entering DFU mode via 1200 baud touch")

        // Disconnect if connected
        usbBridge.disconnect()

        // Connect at 1200 baud without ioManager — the ioManager's
        // testConnection() sends USB GET_STATUS which crashes the nRF52840's
        // minimal TinyUSB CDC-ACM stack. We only need to set baud + toggle DTR.
        if (!usbBridge.connect(deviceId, DFU_TOUCH_BAUD, startIoManager = false)) {
            Log.e(TAG, "Failed to connect at 1200 baud")
            return false
        }

        // Wait for the line coding to be processed by the device
        delay(SERIAL_PORT_OPEN_WAIT_MS)

        // Explicitly deassert DTR to trigger the 1200 baud touch.
        // This sends SET_CONTROL_LINE_STATE(DTR=false) which the nRF52840
        // firmware's tud_cdc_line_state_cb() detects as the reset signal.
        usbBridge.setDtr(false)
        delay(DTR_DEASSERT_WAIT_MS)

        // Now disconnect (port.close() alone wouldn't trigger the touch)
        usbBridge.disconnect()

        // Wait for device to reset and enter DFU bootloader mode
        Log.d(TAG, "Waiting for device to enter DFU mode...")
        delay(TOUCH_RESET_WAIT_MS)

        return true
    }

    /**
     * Find the bootloader device after a 1200-baud touch reset.
     *
     * The device re-enumerates with a new USB device ID (and different PID:
     * application PID 0x8071 → bootloader PID 0x0071). Scan for any supported
     * USB serial device with a different ID than the original.
     *
     * @return The new device ID, or null if not found
     */
    private suspend fun findBootloaderDevice(originalDeviceId: Int): Int? {
        val maxAttempts = 5
        val scanDelayMs = 500L

        for (attempt in 1..maxAttempts) {
            val devices = usbBridge.getConnectedUsbDevices()
            val newDevice = devices.find { it.deviceId != originalDeviceId }

            if (newDevice != null) {
                Log.d(
                    TAG,
                    "Found bootloader device on attempt $attempt: ID=${newDevice.deviceId} " +
                        "(VID=0x${newDevice.vendorId.toString(16)}, PID=0x${newDevice.productId.toString(16)})",
                )
                return newDevice.deviceId
            }

            if (attempt < maxAttempts) {
                delay(scanDelayMs)
            }
        }

        Log.e(TAG, "Bootloader device not found after $maxAttempts attempts")
        return null
    }

    /**
     * Connect to a USB device with retries, waiting for Android USB permission.
     *
     * After USB re-enumeration (1200-baud touch or DFU reset), Android assigns
     * a new device ID and requires fresh permission. The system shows "Open with
     * Columba?" — until the user taps it, connect() fails with "No permission".
     *
     * @return true if connected within the retry window
     */
    private suspend fun connectWithRetry(
        deviceId: Int,
        baudRate: Int,
    ): Boolean {
        val maxAttempts = 10
        val retryDelayMs = 1000L

        for (attempt in 1..maxAttempts) {
            if (usbBridge.connect(deviceId, baudRate, startIoManager = false)) {
                Log.d(TAG, "Connected to device $deviceId on attempt $attempt")
                return true
            }
            if (attempt < maxAttempts) {
                Log.d(TAG, "Connect attempt $attempt/$maxAttempts failed (likely waiting for USB permission)")
                delay(retryDelayMs)
            }
        }

        Log.e(TAG, "Failed to connect to device $deviceId after $maxAttempts attempts")
        return false
    }

    /**
     * Send the firmware image using DFU protocol.
     *
     * Uses raw mode (no ioManager) for the entire transfer. The ioManager's
     * testConnection() sends USB GET_STATUS control transfers that the nRF52840
     * bootloader can't handle — causing a USB controller reset. By using raw
     * mode with readBlocking(testConnection=false), the USB connection stays
     * alive throughout erase and data transfer.
     */
    @Suppress("ReturnCount")
    private suspend fun dfuSendImage(
        firmware: ByteArray,
        initPacket: ByteArray,
        progressCallback: ProgressCallback,
    ): Boolean {
        // Reset sequence number
        sequenceNumber = 0

        // Diagnostic: verify firmware CRC16 matches init packet's embedded CRC16.
        // The last 2 bytes of the init packet are the firmware CRC16 (little-endian).
        if (initPacket.size >= 2) {
            val initCrc =
                (initPacket[initPacket.size - 2].toInt() and 0xFF) or
                    ((initPacket[initPacket.size - 1].toInt() and 0xFF) shl 8)
            val firmwareCrc = CRC16.calculate(firmware)
            Log.d(
                TAG,
                "Firmware CRC16: 0x${"%04X".format(firmwareCrc)}, " +
                    "Init packet CRC16: 0x${"%04X".format(initCrc)}, " +
                    "match=${firmwareCrc == initCrc}",
            )
        }

        // Flush the bootloader's SLIP parser. The app may have sent non-DFU
        // data (KISS probes) before DFU started, leaving the SLIP parser with
        // a partial frame. Sending FEND (0xC0) terminates any pending frame.
        usbBridge.writeBlockingDirect(byteArrayOf(0xC0.toByte(), 0xC0.toByte()))
        delay(50)

        // Send DFU Start packet (triggers flash erase)
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
     * Send DFU Start packet and wait for flash erase to complete.
     *
     * After the bootloader ACKs DFU Start, it begins erasing flash. During the
     * erase (~5-8s), the nRF52840's CPU is blocked by synchronous NVMC operations
     * and cannot service USB. Since we're in raw mode (no ioManager), there are no
     * testConnection() calls to corrupt the USB controller. The USB connection
     * stays alive, and we simply wait for the erase to complete.
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

        val hciPacket = createHciPacketFromFrame(frame)
        val eraseWaitMs = getEraseWaitTime().toLong()

        // Log the complete packet hex for protocol debugging
        val hex =
            hciPacket.joinToString(" ") {
                String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF)
            }
        Log.d(TAG, "DFU Start packet (${hciPacket.size} bytes): $hex")

        // Send DFU Start and read ACK. The bootloader ACKs at the HCI
        // transport layer before starting erase, so ACK arrives quickly.
        if (!sendAndWaitForAck(hciPacket, "DFU Start")) {
            Log.e(TAG, "DFU Start: no ACK")
            return false
        }

        // Wait for flash erase. The bootloader erases pages for the firmware
        // region after ACKing DFU Start. During erase, CPU is blocked.
        Log.d(TAG, "Waiting ${eraseWaitMs}ms for flash erase")
        delay(eraseWaitMs)

        return true
    }

    /**
     * Send DFU Init packet with ACK verification.
     *
     * The bootloader's DFU state machine (in RAM) survived the flash erase
     * and is waiting for the Init packet. The USB connection stayed alive
     * because raw mode prevented testConnection() calls during erase.
     */
    private suspend fun sendInitPacket(initPacket: ByteArray): Boolean {
        val frame = mutableListOf<Byte>()

        // DFU_INIT_PACKET type
        frame.addAll(int32ToBytes(DFU_INIT_PACKET))

        // Init packet data
        frame.addAll(initPacket.toList())

        // Unconditional 2-byte suffix (matches reference: int16_to_bytes(0x0000)).
        // The reference comment says "Padding required" — it's always appended
        // regardless of init packet length. Without this, the HCI payload is 2
        // bytes short, producing wrong length/checksum/CRC.
        frame.add(0x00.toByte())
        frame.add(0x00.toByte())

        val hciPacket = createHciPacketFromFrame(frame)
        return sendAndWaitForAck(hciPacket, "DFU Init")
    }

    /**
     * Send firmware data in chunks with ACK verification for each packet.
     *
     * The bootloader's HCI layer requires ACK-based flow control — each
     * reliable packet must be acknowledged before the next is sent. Without
     * this, the nRF52840's small CDC receive buffer (~64 bytes) overflows
     * and subsequent packets are silently dropped.
     *
     * After DFU Stop ACK, we wait for the bootloader to validate firmware
     * CRC and write bootloader settings to flash (~200ms for single-bank).
     */
    @Suppress("ReturnCount")
    private suspend fun sendFirmware(
        firmware: ByteArray,
        progressCallback: ProgressCallback,
    ): Boolean {
        val totalPackets = (firmware.size + DFU_PACKET_MAX_SIZE - 1) / DFU_PACKET_MAX_SIZE
        Log.d(TAG, "Sending firmware data (${firmware.size} bytes in $totalPackets packets)")

        var offset = 0
        var packetIndex = 0
        while (offset < firmware.size) {
            val chunkSize = minOf(DFU_PACKET_MAX_SIZE, firmware.size - offset)
            val chunk = firmware.copyOfRange(offset, offset + chunkSize)

            val frame = mutableListOf<Byte>()
            frame.addAll(int32ToBytes(DFU_DATA_PACKET))
            frame.addAll(chunk.toList())

            val hciPacket = createHciPacketFromFrame(frame)
            if (!sendAndWaitForAck(hciPacket, "Data packet ${packetIndex + 1}/$totalPackets")) {
                return false
            }

            offset += chunkSize
            packetIndex++

            // After every page worth of packets (8 × 512 = 4096 bytes), give the
            // bootloader time to write the page to flash. The nRF52840's CPU is
            // blocked by synchronous NVMC write operations and cannot service USB.
            if (packetIndex % PACKETS_PER_PAGE == 0) {
                delay(FLASH_PAGE_WRITE_TIME_MS)
            }

            val progress = 25 + (packetIndex * 70 / totalPackets)
            progressCallback.onProgress(progress, "Sending firmware: $packetIndex/$totalPackets")
        }

        // Wait for last page to write (may be partial page)
        delay(FLASH_PAGE_WRITE_TIME_MS)

        // Send DFU Stop packet with ACK verification
        Log.d(TAG, "Sending DFU stop packet")
        val stopFrame = mutableListOf<Byte>()
        stopFrame.addAll(int32ToBytes(DFU_STOP_DATA_PACKET))
        val stopPacket = createHciPacketFromFrame(stopFrame)
        if (!sendAndWaitForAck(stopPacket, "DFU Stop")) {
            return false
        }

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
     * Read a SLIP-framed ACK response from the bootloader.
     *
     * Matches the reference implementation (adafruit-nrfutil): extracts the ACK
     * number from bits 5:3 of the first decoded byte. Does not validate header
     * checksums or CRC — the reference doesn't either for ACK packets.
     *
     * @param timeoutMs Read timeout in milliseconds
     * @return The ACK number (0-7), or -1 on timeout
     */
    private fun readAckNr(timeoutMs: Long = ACK_READ_TIMEOUT_MS): Int {
        val parser = SLIPFrameParser()
        val buffer = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            // Use readBlockingDirect for testConnection-free reads. This calls
            // UsbDeviceConnection.bulkTransfer() directly, bypassing the serial
            // library's port.read() which sends USB GET_STATUS control transfers
            // that kill the nRF52840 bootloader during NVMC flash operations.
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceIn(1, DFU_READ_POLL_MS)
            val bytesRead = usbBridge.readBlockingDirect(buffer, remaining)
            if (bytesRead == -1) break // Connection lost — stop immediately

            if (bytesRead > 0) {
                val data = buffer.copyOf(bytesRead)
                val hex =
                    data.joinToString(" ") {
                        String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF)
                    }
                Log.d(TAG, "readAck: got ${data.size} bytes: $hex")

                val frames = parser.processBytes(data)
                val ackFrame = frames.firstOrNull { it.isNotEmpty() }
                if (ackFrame != null) {
                    val ackNr = (ackFrame[0].toInt() shr 3) and 0x07
                    Log.d(TAG, "readAck: decoded frame ${ackFrame.size} bytes, ack=$ackNr")
                    return ackNr
                }
            }
            // bytesRead == 0 → timeout, loop retries
        }

        Log.e(TAG, "ACK timeout (no response within ${timeoutMs}ms)")
        return -1
    }

    /**
     * Send an HCI packet and wait for any ACK response, with retry logic.
     *
     * Matches the reference implementation (adafruit-nrfutil send_packet):
     * accepts the first valid SLIP frame as a successful ACK without
     * validating the ACK sequence number. The reference uses a local
     * `last_ack = None` that resets every call, so it never actually
     * performs cross-packet ACK validation.
     *
     * @param hciPacket The SLIP-encoded HCI packet to send
     * @param description Human-readable description for logging
     * @param ackTimeoutMs Per-attempt ACK read timeout (default: ACK_READ_TIMEOUT_MS)
     * @param maxRetries Max send attempts (default: MAX_RETRIES)
     * @return true if ACK was received within retry limit
     */
    private suspend fun sendAndWaitForAck(
        hciPacket: ByteArray,
        description: String,
        ackTimeoutMs: Long = ACK_READ_TIMEOUT_MS,
        maxRetries: Int = MAX_RETRIES,
    ): Boolean {
        for (attempt in 1..maxRetries) {
            val bytesWritten = usbBridge.writeBlockingDirect(hciPacket)
            Log.d(TAG, "$description: wrote ${hciPacket.size} bytes, result=$bytesWritten")

            val ackNr = readAckNr(ackTimeoutMs)
            if (ackNr >= 0) {
                Log.d(TAG, "$description: ACK=$ackNr (accepted)")
                return true
            }

            if (attempt < maxRetries) {
                Log.w(TAG, "$description: ACK timeout, retry $attempt/$maxRetries")
            }
        }

        Log.e(TAG, "$description: failed after $maxRetries attempts")
        return false
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
