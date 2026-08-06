package network.columba.app.rns.ipc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtraFieldsBundleCodecTest {
    @Test
    fun `round trips official LXMF Ogg Opus audio field without stringification`() {
        val payload = byteArrayOf(0x4f, 0x67, 0x67, 0x53, 0x00, 0x02)
        val decoded =
            mapOf<Int, Any>(
                0x07 to listOf(0x10, payload),
            ).toExtraFieldsBundle().toExtraFieldsMap()

        val audio = decoded[0x07] as List<*>
        assertEquals(2, audio.size)
        assertEquals(0x10, audio[0])
        assertArrayEquals(payload, audio[1] as ByteArray)
    }

    @Test
    fun `round trips primitive field values`() {
        val source =
            mapOf<Int, Any>(
                1 to true,
                2 to 42,
                3 to 99L,
                4 to "value",
                5 to byteArrayOf(1, 2, 3),
            )
        val decoded = source.toExtraFieldsBundle().toExtraFieldsMap()

        assertEquals(true, decoded[1])
        assertEquals(42, decoded[2])
        assertEquals(99L, decoded[3])
        assertEquals("value", decoded[4])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded[5] as ByteArray)
    }

    @Test
    fun `rejects unsupported values instead of corrupting them with toString`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapOf<Int, Any>(7 to object {}).toExtraFieldsBundle()
        }
    }
}
