package network.columba.app.rns.api.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Field-codec round-trip for the thread field (threads-spec §3 fact 5):
 * the Python bridge hex-encodes byte fields into `fieldsJson` keyed by the
 * decimal field id, so `0x08` arrives as key `"8"` — exactly like `0x30`
 * arrives as `"48"`.
 */
class ThreadFieldCodecTest {
    private val threadId =
        ByteArray(16) { (it * 17 + 3).toByte() } // arbitrary distinct 16B pattern

    @Test
    fun `parses thread id from fieldsJson key 8`() {
        val fieldsJson = """{"8": "${threadId.toHex()}"}"""
        assertArrayEquals(threadId, ThreadFieldCodec.threadIdFromFieldsJson(fieldsJson))
    }

    @Test
    fun `round-trips through threadIdToFieldsJson`() {
        val json = ThreadFieldCodec.threadIdToFieldsJson(threadId)
        assertArrayEquals(threadId, ThreadFieldCodec.threadIdFromFieldsJson(json))
    }

    @Test
    fun `accepts any byte length - reception is opaque`() {
        for (size in intArrayOf(1, 8, 15, 16, 17, 32)) {
            val id = ByteArray(size) { it.toByte() }
            val json = """{"8": "${id.toHex()}"}"""
            assertArrayEquals("length $size", id, ThreadFieldCodec.threadIdFromFieldsJson(json))
        }
    }

    @Test
    fun `coexists with other fields including reply hash and quote`() {
        val fieldsJson =
            """{"8": "${threadId.toHex()}", "48": "${"ab".repeat(32)}", "49": "quoted text", "15": 0}"""
        assertArrayEquals(threadId, ThreadFieldCodec.threadIdFromFieldsJson(fieldsJson))
        assertEquals("ab".repeat(32), ThreadFieldCodec.replyHashFromFieldsJson(fieldsJson))
    }

    @Test
    fun `absent field and null json parse to null - Main`() {
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson(null))
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("""{"48": "abcd"}"""))
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("{}"))
    }

    @Test
    fun `malformed values fail closed to Main and never throw`() {
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("not json"))
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("""{"8": ""}"""))
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("""{"8": "abc"}""")) // odd length
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("""{"8": "zzzz"}""")) // non-hex
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("""{"8": 16}""")) // wrong type
        assertNull(ThreadFieldCodec.threadIdFromFieldsJson("""{"8": null}"""))
    }

    @Test
    fun `empty thread id serializes to null json`() {
        assertNull(ThreadFieldCodec.threadIdToFieldsJson(null))
        assertNull(ThreadFieldCodec.threadIdToFieldsJson(ByteArray(0)))
    }

    @Test
    fun `reply hash parses canonical key 48 only`() {
        assertEquals("cafe1234", ThreadFieldCodec.replyHashFromFieldsJson("""{"48": "Cafe1234"}"""))
        assertNull(ThreadFieldCodec.replyHashFromFieldsJson("""{"16": {"reply_to": "cafe"}}"""))
        assertNull(ThreadFieldCodec.replyHashFromFieldsJson(null))
        assertNull(ThreadFieldCodec.replyHashFromFieldsJson("""{"48": ""}"""))
    }

    @Test
    fun `field id constant matches json key convention`() {
        assertEquals(0x08, LxmfFields.FIELD_THREAD)
        assertEquals("8", LxmfFields.FIELD_THREAD.toString())
        assertEquals("48", LxmfFields.FIELD_REPLY_HASH.toString())
    }
}
