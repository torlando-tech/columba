package network.columba.app.reticulum.flasher

import network.columba.app.reticulum.usb.KotlinUSBBridge
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests that the exact KISS command sequence for TNC enable/disable matches
 * the rnodeconf Python reference implementation.
 *
 * Enable TNC (rnodeconf --tnc):
 *   1. CMD_FREQUENCY (0x01) — 4-byte big-endian frequency in Hz
 *   2. CMD_BANDWIDTH (0x02) — 4-byte big-endian bandwidth in Hz
 *   3. CMD_TXPOWER   (0x03) — 1-byte TX power in dBm
 *   4. CMD_SF         (0x04) — 1-byte spreading factor
 *   5. CMD_CR         (0x05) — 1-byte coding rate
 *   6. CMD_RADIO_STATE(0x06) — 0x01 (ON)
 *   7. CMD_CONF_SAVE  (0x53) — 0x00
 *   8. CMD_RESET      (0x55) — 0xF8
 *
 * Disable TNC (rnodeconf --normal):
 *   1. CMD_CONF_DELETE(0x54) — 0x00
 */
class RNodeTncCommandSequenceTest {
    private lateinit var mockBridge: KotlinUSBBridge
    private lateinit var detector: RNodeDetector
    private val writtenFrames = mutableListOf<ByteArray>()

    @Before
    fun setup() {
        writtenFrames.clear()
        mockBridge = mockk()

        // Stub clearReadBuffer (called during enableTncMode)
        every { mockBridge.clearReadBuffer() } returns Unit

        // Capture every write call and record the raw frame
        val frameSlot = slot<ByteArray>()
        every { mockBridge.write(capture(frameSlot)) } answers {
            writtenFrames.add(frameSlot.captured.copyOf())
            frameSlot.captured.size // Return bytes written
        }

        detector = RNodeDetector(mockBridge)
    }

    /**
     * Decode all captured writes into (command, data) pairs using KISSCodec.
     */
    private fun decodedCommands(): List<Pair<Byte, ByteArray>> = writtenFrames.mapNotNull { KISSCodec.decodeFrame(it) }

    /**
     * Convert a 4-byte big-endian byte array to an Int for readable assertions.
     */
    private fun bytesToIntBigEndian(bytes: ByteArray): Int {
        require(bytes.size == 4) { "Expected 4 bytes, got ${bytes.size}" }
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    // ==================== enableTncMode tests ====================

    @Test
    fun `enableTncMode sends exactly 8 commands in correct order`() =
        runTest {
            val result =
                detector.enableTncMode(
                    band = FrequencyBand.BAND_868_915,
                    frequency = 868000000,
                    bandwidth = 125000,
                    spreadingFactor = 8,
                    codingRate = 5,
                    txPower = 17,
                )

            assertTrue("enableTncMode should succeed", result)

            val commands = decodedCommands()
            assertEquals("Expected exactly 8 KISS commands", 8, commands.size)

            assertEquals("Command 1 should be CMD_FREQUENCY", RNodeConstants.CMD_FREQUENCY, commands[0].first)
            assertEquals("Command 2 should be CMD_BANDWIDTH", RNodeConstants.CMD_BANDWIDTH, commands[1].first)
            assertEquals("Command 3 should be CMD_TXPOWER", RNodeConstants.CMD_TXPOWER, commands[2].first)
            assertEquals("Command 4 should be CMD_SF", RNodeConstants.CMD_SF, commands[3].first)
            assertEquals("Command 5 should be CMD_CR", RNodeConstants.CMD_CR, commands[4].first)
            assertEquals("Command 6 should be CMD_RADIO_STATE", RNodeConstants.CMD_RADIO_STATE, commands[5].first)
            assertEquals("Command 7 should be CMD_CONF_SAVE", RNodeConstants.CMD_CONF_SAVE, commands[6].first)
            assertEquals("Command 8 should be CMD_RESET", RNodeConstants.CMD_RESET, commands[7].first)
        }

    @Test
    fun `enableTncMode sends correct frequency as 4-byte big-endian`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                frequency = 868000000,
            )

            val commands = decodedCommands()
            val freqData = commands[0].second
            assertEquals("Frequency should be 4 bytes", 4, freqData.size)
            assertEquals("Frequency should be 868000000 Hz", 868000000, bytesToIntBigEndian(freqData))
        }

    @Test
    fun `enableTncMode sends correct bandwidth as 4-byte big-endian`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                bandwidth = 125000,
            )

            val commands = decodedCommands()
            val bwData = commands[1].second
            assertEquals("Bandwidth should be 4 bytes", 4, bwData.size)
            assertEquals("Bandwidth should be 125000 Hz", 125000, bytesToIntBigEndian(bwData))
        }

    @Test
    fun `enableTncMode sends TX power as single byte`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                txPower = 17,
            )

            val commands = decodedCommands()
            val txpData = commands[2].second
            assertEquals("TX power should be 1 byte", 1, txpData.size)
            assertEquals("TX power should be 17", 17, txpData[0].toInt())
        }

    @Test
    fun `enableTncMode sends spreading factor as single byte`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                spreadingFactor = 8,
            )

            val commands = decodedCommands()
            val sfData = commands[3].second
            assertEquals("SF should be 1 byte", 1, sfData.size)
            assertEquals("SF should be 8", 8, sfData[0].toInt())
        }

    @Test
    fun `enableTncMode sends coding rate as single byte`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                codingRate = 5,
            )

            val commands = decodedCommands()
            val crData = commands[4].second
            assertEquals("CR should be 1 byte", 1, crData.size)
            assertEquals("CR should be 5", 5, crData[0].toInt())
        }

    @Test
    fun `enableTncMode sends RADIO_STATE ON before saving`() =
        runTest {
            detector.enableTncMode(band = FrequencyBand.BAND_868_915)

            val commands = decodedCommands()
            val radioStateData = commands[5].second
            assertEquals("RADIO_STATE payload should be 1 byte", 1, radioStateData.size)
            assertEquals(
                "RADIO_STATE should be ON (0x01)",
                RNodeConstants.RADIO_STATE_ON,
                radioStateData[0],
            )
        }

    @Test
    fun `enableTncMode sends CONF_SAVE with 0x00 payload`() =
        runTest {
            detector.enableTncMode(band = FrequencyBand.BAND_868_915)

            val commands = decodedCommands()
            val saveData = commands[6].second
            assertEquals("CONF_SAVE payload should be 1 byte", 1, saveData.size)
            assertEquals("CONF_SAVE payload should be 0x00", 0x00, saveData[0].toInt())
        }

    @Test
    fun `enableTncMode sends RESET with 0xF8 payload`() =
        runTest {
            detector.enableTncMode(band = FrequencyBand.BAND_868_915)

            val commands = decodedCommands()
            val resetData = commands[7].second
            assertEquals("RESET payload should be 1 byte", 1, resetData.size)
            assertEquals(
                "RESET payload should be CMD_RESET_BYTE (0xF8)",
                RNodeConstants.CMD_RESET_BYTE,
                resetData[0],
            )
        }

    @Test
    fun `enableTncMode defaults to 868 MHz for BAND_868_915`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                frequency = null,
            )

            val commands = decodedCommands()
            val freqData = commands[0].second
            assertEquals("Default 868/915 frequency should be 868000000", 868000000, bytesToIntBigEndian(freqData))
        }

    @Test
    fun `enableTncMode defaults to 433_775 MHz for BAND_433`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_433,
                frequency = null,
            )

            val commands = decodedCommands()
            val freqData = commands[0].second
            assertEquals("Default 433 frequency should be 433775000", 433775000, bytesToIntBigEndian(freqData))
        }

    @Test
    fun `enableTncMode with custom 915 MHz params sends exact values`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                frequency = 915000000,
                bandwidth = 250000,
                spreadingFactor = 12,
                codingRate = 8,
                txPower = 22,
            )

            val commands = decodedCommands()

            // Verify all values
            assertEquals(915000000, bytesToIntBigEndian(commands[0].second))
            assertEquals(250000, bytesToIntBigEndian(commands[1].second))
            assertEquals(22, commands[2].second[0].toInt())
            assertEquals(12, commands[3].second[0].toInt())
            assertEquals(8, commands[4].second[0].toInt())
        }

    @Test
    fun `enableTncMode KISS frames have correct FEND framing`() =
        runTest {
            detector.enableTncMode(band = FrequencyBand.BAND_868_915)

            // Every raw frame should start and end with FEND (0xC0)
            for ((index, frame) in writtenFrames.withIndex()) {
                assertEquals(
                    "Frame $index should start with FEND",
                    RNodeConstants.KISS_FEND,
                    frame.first(),
                )
                assertEquals(
                    "Frame $index should end with FEND",
                    RNodeConstants.KISS_FEND,
                    frame.last(),
                )
            }
        }

    @Test
    fun `enableTncMode complete byte-level verification for 868 MHz defaults`() =
        runTest {
            detector.enableTncMode(
                band = FrequencyBand.BAND_868_915,
                frequency = 868000000,
                bandwidth = 125000,
                spreadingFactor = 8,
                codingRate = 5,
                txPower = 17,
            )

            val commands = decodedCommands()

            // CMD_FREQUENCY: 868000000 = 0x33BC_A100
            assertArrayEquals(
                "Frequency 868000000 = 0x33BCA100 big-endian",
                byteArrayOf(0x33, 0xBC.toByte(), 0xA1.toByte(), 0x00),
                commands[0].second,
            )

            // CMD_BANDWIDTH: 125000 = 0x0001_E848
            assertArrayEquals(
                "Bandwidth 125000 = 0x0001E848 big-endian",
                byteArrayOf(0x00, 0x01, 0xE8.toByte(), 0x48),
                commands[1].second,
            )

            // CMD_TXPOWER: 17
            assertArrayEquals("TX power 17", byteArrayOf(17), commands[2].second)

            // CMD_SF: 8
            assertArrayEquals("SF 8", byteArrayOf(8), commands[3].second)

            // CMD_CR: 5
            assertArrayEquals("CR 5", byteArrayOf(5), commands[4].second)

            // CMD_RADIO_STATE: ON (0x01)
            assertArrayEquals("Radio state ON", byteArrayOf(0x01), commands[5].second)

            // CMD_CONF_SAVE: 0x00
            assertArrayEquals("Conf save", byteArrayOf(0x00), commands[6].second)

            // CMD_RESET: 0xF8
            assertArrayEquals("Reset", byteArrayOf(0xF8.toByte()), commands[7].second)
        }

    // ==================== disableTncMode tests ====================

    @Test
    fun `disableTncMode sends exactly 1 command`() =
        runTest {
            val result = detector.disableTncMode()

            assertTrue("disableTncMode should succeed", result)

            val commands = decodedCommands()
            assertEquals("Expected exactly 1 KISS command", 1, commands.size)
        }

    @Test
    fun `disableTncMode sends CMD_CONF_DELETE with 0x00 payload`() =
        runTest {
            detector.disableTncMode()

            val commands = decodedCommands()
            assertEquals("Command should be CMD_CONF_DELETE", RNodeConstants.CMD_CONF_DELETE, commands[0].first)
            assertEquals("Payload should be 1 byte", 1, commands[0].second.size)
            assertEquals("Payload should be 0x00", 0x00, commands[0].second[0].toInt())
        }

    @Test
    fun `disableTncMode KISS frame has correct FEND framing`() =
        runTest {
            detector.disableTncMode()

            assertEquals("Should have 1 raw frame", 1, writtenFrames.size)
            val frame = writtenFrames[0]
            assertEquals("Frame should start with FEND", RNodeConstants.KISS_FEND, frame.first())
            assertEquals("Frame should end with FEND", RNodeConstants.KISS_FEND, frame.last())
        }

    @Test
    fun `disableTncMode sends exact raw bytes`() =
        runTest {
            detector.disableTncMode()

            // Expected: FEND(0xC0) + CMD_CONF_DELETE(0x54) + 0x00 + FEND(0xC0)
            val expectedFrame =
                byteArrayOf(
                    0xC0.toByte(), // FEND
                    0x54, // CMD_CONF_DELETE
                    0x00, // payload
                    0xC0.toByte(), // FEND
                )
            assertArrayEquals("Raw frame should match exactly", expectedFrame, writtenFrames[0])
        }

    // ==================== enableTncMode failure handling ====================

    @Test
    fun `enableTncMode returns false if first write fails`() =
        runTest {
            // Override mock to fail all writes
            every { mockBridge.write(any()) } returns 0

            val result =
                detector.enableTncMode(
                    band = FrequencyBand.BAND_868_915,
                )

            assertTrue("Should return false on write failure", !result)
        }

    @Test
    fun `enableTncMode returns false if radio state write fails`() =
        runTest {
            // Re-setup with a write counter that fails on the 6th write (CMD_RADIO_STATE)
            var writeCount = 0
            writtenFrames.clear()
            every { mockBridge.write(any()) } answers {
                writeCount++
                val frame = firstArg<ByteArray>()
                writtenFrames.add(frame.copyOf())
                // Fail on the 6th write (CMD_RADIO_STATE)
                if (writeCount == 6) 0 else frame.size
            }

            val result = detector.enableTncMode(band = FrequencyBand.BAND_868_915)

            assertTrue("Should return false when radio state write fails", !result)
            // Should have attempted 6 writes: freq, bw, txp, sf, cr, radio_state(failed)
            assertEquals("Should have attempted 6 writes", 6, writtenFrames.size)
        }

    @Test
    fun `disableTncMode returns false if write fails`() =
        runTest {
            // Override mock to fail all writes
            every { mockBridge.write(any()) } returns 0

            val result = detector.disableTncMode()

            assertTrue("Should return false on write failure", !result)
        }
}
