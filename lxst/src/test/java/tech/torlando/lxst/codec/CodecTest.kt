package tech.torlando.lxst.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for Codec base classes and utilities.
 *
 * Tests Null codec passthrough and audio resampling functions.
 */
class CodecTest {
    // ===== Null Codec Tests =====

    @Test
    fun `null codec encode-decode roundtrip preserves data`() {
        val codec = Null()
        val input = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val encoded = codec.encode(input)
        val decoded = codec.decode(encoded)

        // Should match within float32 precision
        assertArrayEquals(input, decoded, 0.0001f)
    }

    @Test
    fun `null codec encode converts float32 to int16 bytes`() {
        val codec = Null()
        val input = floatArrayOf(1f, -1f, 0f)
        val encoded = codec.encode(input)

        // Should be 6 bytes (3 samples × 2 bytes per int16)
        assertEquals(6, encoded.size)

        // Verify int16 values (little-endian)
        // 1.0f → 32767 (0x7FFF)
        assertEquals(0xFF.toByte(), encoded[0])
        assertEquals(0x7F.toByte(), encoded[1])

        // -1.0f → -32767 (0x8001) because we multiply by 32767
        assertEquals(0x01.toByte(), encoded[2])
        assertEquals(0x80.toByte(), encoded[3])

        // 0.0f → 0 (0x0000)
        assertEquals(0x00.toByte(), encoded[4])
        assertEquals(0x00.toByte(), encoded[5])
    }

    @Test
    fun `null codec decode converts int16 bytes to float32`() {
        val codec = Null()
        // Create int16 bytes: [32767, -32767, 0] (little-endian)
        val bytes = byteArrayOf(
            0xFF.toByte(), 0x7F.toByte(), // 32767
            0x01.toByte(), 0x80.toByte(), // -32767
            0x00.toByte(), 0x00.toByte(), // 0
        )
        val decoded = codec.decode(bytes)

        assertEquals(3, decoded.size)
        assertEquals(1f, decoded[0], 0.0001f)
        assertEquals(-1f, decoded[1], 0.0001f)
        assertEquals(0f, decoded[2], 0.0001f)
    }

    @Test
    fun `null codec handles maximum amplitude without clipping`() {
        val codec = Null()
        val input = floatArrayOf(-1f, 1f)
        val encoded = codec.encode(input)
        val decoded = codec.decode(encoded)

        assertEquals(-1f, decoded[0], 0.0001f)
        assertEquals(1f, decoded[1], 0.0001f)
    }

    // ===== codecChannels Tests =====

    @Test
    fun `null codec codecChannels returns 1 (mono default)`() {
        val codec = Null()
        assertEquals(1, codec.codecChannels)
    }

    // ===== Resample Tests =====

    @Test
    fun `resample 48kHz to 8kHz reduces sample count by 6x`() {
        val input48k = FloatArray(960) { (it / 960f) * 2f - 1f } // 20ms at 48kHz, ramp -1 to 1
        val resampled8k = resample(input48k, 16, 1, 48000, 8000)

        // 20ms at 8kHz = 160 samples
        assertEquals(160, resampled8k.size)
    }

    @Test
    fun `resample 8kHz to 48kHz increases sample count by 6x`() {
        val input8k = FloatArray(160) { (it / 160f) * 2f - 1f } // 20ms at 8kHz
        val resampled48k = resample(input8k, 16, 1, 8000, 48000)

        // 20ms at 48kHz = 960 samples
        assertEquals(960, resampled48k.size)
    }

    @Test
    fun `resample preserves DC offset`() {
        val input = FloatArray(480) { 0.5f } // DC offset at 0.5, 10ms at 48kHz
        val resampled = resample(input, 16, 1, 48000, 8000)

        // Average should still be ~0.5
        val avg = resampled.average().toFloat()
        assertEquals(0.5f, avg, 0.05f)
    }

    @Test
    fun `resample same rate returns input unchanged`() {
        val input = FloatArray(960) { (it / 960f) * 2f - 1f }
        val resampled = resample(input, 16, 1, 48000, 48000)

        // Should return exact same array
        assertTrue(input === resampled)
    }

    @Test
    fun `resample stereo audio preserves channel count`() {
        // 20ms at 48kHz stereo = 960 samples per channel = 1920 total
        val inputStereo = FloatArray(1920) { (it / 1920f) * 2f - 1f }
        val resampledStereo = resample(inputStereo, 16, 2, 48000, 8000)

        // 20ms at 8kHz stereo = 160 samples per channel = 320 total
        assertEquals(320, resampledStereo.size)
    }

    @Test
    fun `resample handles silence correctly`() {
        val silence = FloatArray(960) { 0f }
        val resampled = resample(silence, 16, 1, 48000, 8000)

        assertEquals(160, resampled.size)
        // All samples should be near zero
        resampled.forEach { sample ->
            assertEquals(0f, sample, 0.001f)
        }
    }

    @Test
    fun `resample handles tone signal`() {
        // Generate 1kHz tone at 48kHz
        val freq = 1000f
        val sampleRate = 48000
        val samples = 960 // 20ms
        val tone = FloatArray(samples) { i ->
            kotlin.math.sin(2 * Math.PI * freq * i / sampleRate).toFloat() * 0.5f
        }

        val resampled = resample(tone, 16, 1, 48000, 8000)

        // Should still have similar amplitude range
        val maxInput = tone.maxOrNull() ?: 0f
        val maxOutput = resampled.maxOrNull() ?: 0f
        assertEquals(maxInput, maxOutput, 0.1f)
    }

    // ===== Helper Function Tests =====

    @Test
    fun `samplesToBytes converts float32 to int16`() {
        val samples = floatArrayOf(1f, -1f, 0f, 0.5f)
        val bytes = samplesToBytes(samples, 16)

        assertEquals(8, bytes.size) // 4 samples × 2 bytes
    }

    @Test
    fun `bytesToSamples converts int16 to float32`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0x7F.toByte(), // 32767
            0x01.toByte(), 0x80.toByte(), // -32767
        )
        val samples = bytesToSamples(bytes, 16)

        assertEquals(2, samples.size)
        assertEquals(1f, samples[0], 0.0001f)
        assertEquals(-1f, samples[1], 0.0001f)
    }

    @Test
    fun `samplesToBytes and bytesToSamples roundtrip`() {
        val input = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val bytes = samplesToBytes(input, 16)
        val output = bytesToSamples(bytes, 16)

        assertArrayEquals(input, output, 0.0001f)
    }

    @Test
    fun `resampleBytes same rate returns input unchanged`() {
        val bytes = byteArrayOf(0x00, 0x10, 0x00, 0x20)
        val resampled = resampleBytes(bytes, 16, 1, 48000, 48000)

        assertTrue(bytes === resampled)
    }

    @Test
    fun `resampleBytes downsamples correctly`() {
        // 960 samples at 48kHz → 160 samples at 8kHz
        val bytes = ByteArray(960 * 2) { it.toByte() }
        val resampled = resampleBytes(bytes, 16, 1, 48000, 8000)

        assertEquals(160 * 2, resampled.size) // 160 samples × 2 bytes per sample
    }
}
