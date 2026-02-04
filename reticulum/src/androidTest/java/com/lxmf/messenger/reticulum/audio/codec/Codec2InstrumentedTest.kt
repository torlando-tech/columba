package com.lxmf.messenger.reticulum.audio.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Codec2 codec.
 *
 * These tests run on Android device/emulator where JNI libraries can load.
 * Verifies actual encode/decode functionality for all 7 modes.
 *
 * CRITICAL: Verifies header byte (0x00-0x06) is correctly prepended to encoded packets.
 * This is required for wire compatibility with Python LXST.
 */
@RunWith(AndroidJUnit4::class)
class Codec2InstrumentedTest {

    private var codec2: Codec2? = null

    @After
    fun cleanup() {
        codec2?.close()
        codec2 = null
    }

    /**
     * Generate test audio: 40ms of 440Hz sine wave at 8kHz.
     * Codec2 operates at fixed 8kHz sample rate.
     */
    private fun generateTestTone(): FloatArray {
        val sampleRate = 8000
        val samples = (sampleRate * 0.04).toInt() // 40ms = 320 samples at 8kHz
        return FloatArray(samples) { i ->
            val phase = (i.toFloat() / sampleRate) * 440f * 2f * Math.PI.toFloat()
            kotlin.math.sin(phase) * 0.5f // 50% amplitude
        }
    }

    // ===== MODE 700C (0x00) =====

    @Test
    fun mode700c_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_700C)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("700C encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode700c_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_700C)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "700C header byte should be 0x00",
            Codec2.MODE_HEADERS[Codec2.CODEC2_700C],
            encoded[0]
        )
    }

    // ===== MODE 1200 (0x01) =====

    @Test
    fun mode1200_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_1200)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("1200 encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode1200_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_1200)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "1200 header byte should be 0x01",
            Codec2.MODE_HEADERS[Codec2.CODEC2_1200],
            encoded[0]
        )
    }

    // ===== MODE 1300 (0x02) =====

    @Test
    fun mode1300_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_1300)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("1300 encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode1300_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_1300)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "1300 header byte should be 0x02",
            Codec2.MODE_HEADERS[Codec2.CODEC2_1300],
            encoded[0]
        )
    }

    // ===== MODE 1400 (0x03) =====

    @Test
    fun mode1400_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_1400)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("1400 encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode1400_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_1400)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "1400 header byte should be 0x03",
            Codec2.MODE_HEADERS[Codec2.CODEC2_1400],
            encoded[0]
        )
    }

    // ===== MODE 1600 (0x04) =====

    @Test
    fun mode1600_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_1600)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("1600 encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode1600_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_1600)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "1600 header byte should be 0x04",
            Codec2.MODE_HEADERS[Codec2.CODEC2_1600],
            encoded[0]
        )
    }

    // ===== MODE 2400 (0x05) =====

    @Test
    fun mode2400_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_2400)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("2400 encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode2400_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_2400)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "2400 header byte should be 0x05",
            Codec2.MODE_HEADERS[Codec2.CODEC2_2400],
            encoded[0]
        )
    }

    // ===== MODE 3200 (0x06) =====

    @Test
    fun mode3200_encodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_3200)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertTrue("Encoded output should not be empty", encoded.isNotEmpty())
        println("3200 encoded ${testAudio.size} samples to ${encoded.size} bytes")
    }

    @Test
    fun mode3200_hasCorrectHeaderByte() {
        codec2 = Codec2(Codec2.CODEC2_3200)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)

        assertEquals(
            "3200 header byte should be 0x06",
            Codec2.MODE_HEADERS[Codec2.CODEC2_3200],
            encoded[0]
        )
    }

    // ===== DECODE TEST =====

    @Test
    fun mode2400_decodesSuccessfully() {
        codec2 = Codec2(Codec2.CODEC2_2400)
        val testAudio = generateTestTone()

        val encoded = codec2!!.encode(testAudio)
        val decoded = codec2!!.decode(encoded)

        assertTrue("Decoded output should not be empty", decoded.isNotEmpty())
        println("2400 round-trip: ${testAudio.size} -> ${encoded.size} bytes -> ${decoded.size} samples")
    }

    // ===== COMPREHENSIVE TEST =====

    @Test
    fun allModes_encodeWithCorrectHeaders() {
        val modes = listOf(
            Codec2.CODEC2_700C to "700C",
            Codec2.CODEC2_1200 to "1200",
            Codec2.CODEC2_1300 to "1300",
            Codec2.CODEC2_1400 to "1400",
            Codec2.CODEC2_1600 to "1600",
            Codec2.CODEC2_2400 to "2400",
            Codec2.CODEC2_3200 to "3200"
        )

        val results = mutableListOf<String>()

        for ((mode, name) in modes) {
            codec2?.close()
            codec2 = Codec2(mode)

            val testAudio = generateTestTone()

            try {
                val encoded = codec2!!.encode(testAudio)

                // Verify non-empty
                assertTrue("$name should produce non-empty output", encoded.isNotEmpty())

                // Verify header byte
                val expectedHeader = Codec2.MODE_HEADERS[mode]
                assertEquals(
                    "$name should have correct header byte",
                    expectedHeader,
                    encoded[0]
                )

                results.add("$name: OK (${encoded.size} bytes, header=0x${String.format("%02X", encoded[0])})")
            } catch (e: Exception) {
                fail("$name failed to encode: ${e.message}")
            }
        }

        println("All modes encode summary:")
        results.forEach { println("  $it") }
    }

    @Test
    fun decoderSwitchesMode_basedOnHeaderByte() {
        // Encode with 2400 mode
        codec2 = Codec2(Codec2.CODEC2_2400)
        val testAudio = generateTestTone()
        val encoded2400 = codec2!!.encode(testAudio)

        // Close and create fresh codec with different mode
        codec2?.close()
        codec2 = Codec2(Codec2.CODEC2_1600)

        // Decode the 2400 packet - decoder should auto-switch based on header
        val decoded = codec2!!.decode(encoded2400)

        assertTrue("Decoder should successfully decode after mode switch", decoded.isNotEmpty())
        assertEquals(
            "Decoder should have switched to 2400 mode based on header",
            Codec2.CODEC2_2400,
            codec2!!.currentMode
        )
    }
}
