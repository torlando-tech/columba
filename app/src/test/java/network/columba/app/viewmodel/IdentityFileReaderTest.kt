package network.columba.app.viewmodel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class IdentityFileReaderTest {
    @Test
    fun `accepts exactly 64 raw identity bytes`() {
        val bytes = ByteArray(64) { it.toByte() }

        val result = IdentityFileReader.read(ByteArrayInputStream(bytes))

        assertTrue(result.isSuccess)
        assertArrayEquals(bytes, result.getOrThrow())
    }

    @Test
    fun `reports exact size for a short identity file`() {
        val result = IdentityFileReader.read(ByteArrayInputStream(ByteArray(32)))

        assertTrue(result.isFailure)
        assertEquals(
            "Invalid identity file: expected 64 bytes, got 32 bytes",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `rejects a large file after reading only 65 bytes`() {
        val input = CountingInputStream(337_172_840L)

        val result = IdentityFileReader.read(input)

        assertTrue(result.isFailure)
        assertEquals(65L, input.bytesRead)
        assertEquals(
            "Invalid identity file: expected 64 bytes, file is larger than 64 bytes",
            result.exceptionOrNull()?.message,
        )
    }

    private class CountingInputStream(
        private val size: Long,
    ) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            if (bytesRead >= size) return -1
            bytesRead++
            return 0
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (bytesRead >= size) return -1
            val count = minOf(length.toLong(), size - bytesRead).toInt()
            buffer.fill(0, offset, offset + count)
            bytesRead += count
            return count
        }
    }
}
