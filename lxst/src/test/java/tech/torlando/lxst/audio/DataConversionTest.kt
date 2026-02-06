package tech.torlando.lxst.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for LXST data conversion utilities.
 *
 * Tests bytesToFloat32() and float32ToBytes() functions from Sink.kt.
 * These are pure Kotlin functions with no JNI dependencies.
 */
class DataConversionTest {

    companion object {
        private const val EPSILON = 0.0001f // Float comparison tolerance
    }

    @Test
    fun `bytesToFloat32 converts silence correctly`() {
        // Silence = all zeros
        val silenceBytes = ByteArray(8) // 4 samples
        val result = bytesToFloat32(silenceBytes)

        assertEquals(4, result.size)
        result.forEach { sample ->
            assertEquals(0f, sample, EPSILON)
        }
    }

    @Test
    fun `bytesToFloat32 converts max positive correctly`() {
        // Max positive int16 = 32767 = 0xFF7F in little-endian
        val bytes = byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F)
        val result = bytesToFloat32(bytes)

        assertEquals(2, result.size)
        // 32767 / 32768 = 0.999969...
        result.forEach { sample ->
            assertEquals(0.9999f, sample, 0.001f)
        }
    }

    @Test
    fun `bytesToFloat32 converts max negative correctly`() {
        // Max negative int16 = -32768 = 0x0080 in little-endian (0x8000 big-endian)
        val bytes = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x80.toByte())
        val result = bytesToFloat32(bytes)

        assertEquals(2, result.size)
        // -32768 / 32768 = -1.0
        result.forEach { sample ->
            assertEquals(-1f, sample, EPSILON)
        }
    }

    @Test
    fun `float32ToBytes converts silence correctly`() {
        val samples = floatArrayOf(0f, 0f, 0f, 0f)
        val result = float32ToBytes(samples)

        assertEquals(8, result.size)
        result.forEach { byte ->
            assertEquals(0, byte.toInt())
        }
    }

    @Test
    fun `float32ToBytes converts max positive correctly`() {
        val samples = floatArrayOf(1f, 1f)
        val result = float32ToBytes(samples)

        // 1.0 * 32767 = 32767 = 0xFF7F little-endian
        assertEquals(4, result.size)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767, buffer.short.toInt())
        assertEquals(32767, buffer.short.toInt())
    }

    @Test
    fun `float32ToBytes clamps values above 1`() {
        val samples = floatArrayOf(1.5f, 2.0f)
        val result = float32ToBytes(samples)

        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        // Should clamp to 32767 (max int16)
        assertEquals(32767, buffer.short.toInt())
        assertEquals(32767, buffer.short.toInt())
    }

    @Test
    fun `float32ToBytes clamps values below -1`() {
        val samples = floatArrayOf(-1.5f, -2.0f)
        val result = float32ToBytes(samples)

        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        // Should clamp to -1.0, then multiply by 32767 = -32767
        // Note: Implementation uses 32767 multiplier, not 32768
        assertEquals(-32767, buffer.short.toInt())
        assertEquals(-32767, buffer.short.toInt())
    }

    @Test
    fun `round trip preserves data within tolerance`() {
        // Generate test samples
        val original = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 0.999f)

        // Convert to bytes and back
        val bytes = float32ToBytes(original)
        val recovered = bytesToFloat32(bytes)

        assertEquals(original.size, recovered.size)
        for (i in original.indices) {
            // Quantization error is at most 1/32768 = ~0.00003
            assertEquals(original[i], recovered[i], 0.0001f)
        }
    }

    @Test
    fun `bytesToFloat32 handles empty array`() {
        val result = bytesToFloat32(ByteArray(0))
        assertEquals(0, result.size)
    }

    @Test
    fun `float32ToBytes handles empty array`() {
        val result = float32ToBytes(FloatArray(0))
        assertEquals(0, result.size)
    }
}
