package com.lxmf.messenger.service.binder

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD test for Python result conversion to JSON.
 *
 * Bug: sendLxmfMessageWithMethod was using result?.toString() which converts
 * Python dict to its string representation (e.g., {'success': True, 'message_hash': b'\x13\x1f...'})
 * instead of valid JSON. This caused JSON parsing errors like:
 * "Unterminated object at character 37"
 *
 * Fix: Extract dict values and encode bytes as Base64 to produce valid JSON.
 *
 * Since PyObject cannot be mocked in unit tests (native Chaquopy class),
 * we test using a testable interface abstraction.
 */
class PythonResultConverterTest {
    @Before
    fun setup() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            val bytes = firstArg<ByteArray>()
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    @After
    fun teardown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun `convertSendMessageResult produces valid JSON for successful result`() {
        // Given: A simulated Python dict result with bytes values
        val messageHashBytes = byteArrayOf(0x13, 0x1f, 0x89.toByte(), 0xf6.toByte())
        val destHashBytes = byteArrayOf(0xf3.toByte(), 0xd6.toByte(), 0x6c, 0xe5.toByte())
        val timestamp = 1765560315794L

        val dictAccessor =
            FakeDictAccessor(
                mapOf(
                    "success" to true,
                    "message_hash" to messageHashBytes,
                    "timestamp" to timestamp,
                    "delivery_method" to "opportunistic",
                    "destination_hash" to destHashBytes,
                ),
            )

        // When: Converting to JSON
        val json = PythonResultConverter.convertSendMessageResult(dictAccessor)
        val parsed = JSONObject(json)

        // Then: JSON should be valid and contain expected values
        assertTrue("Should indicate success", parsed.getBoolean("success"))
        assertEquals("opportunistic", parsed.getString("delivery_method"))
        assertEquals(timestamp, parsed.getLong("timestamp"))

        // Bytes should be Base64 encoded
        val msgHashB64 = parsed.getString("message_hash")
        val destHashB64 = parsed.getString("destination_hash")

        // Verify they decode back to original bytes
        val decodedMsgHash = java.util.Base64.getDecoder().decode(msgHashB64)
        val decodedDestHash = java.util.Base64.getDecoder().decode(destHashB64)

        assertTrue(
            "message_hash should decode to original bytes",
            messageHashBytes.contentEquals(decodedMsgHash),
        )
        assertTrue(
            "destination_hash should decode to original bytes",
            destHashBytes.contentEquals(decodedDestHash),
        )
    }

    @Test
    fun `convertSendMessageResult produces valid JSON for failed result`() {
        // Given: A simulated Python dict result with error
        val dictAccessor =
            FakeDictAccessor(
                mapOf(
                    "success" to false,
                    "error" to "Test error message",
                ),
            )

        // When: Converting to JSON
        val json = PythonResultConverter.convertSendMessageResult(dictAccessor)
        val parsed = JSONObject(json)

        // Then: JSON should be valid and contain error
        assertFalse("Should indicate failure", parsed.getBoolean("success"))
        assertEquals("Test error message", parsed.getString("error"))
    }

    @Test
    fun `convertSendMessageResult handles null result gracefully`() {
        // When: Converting null result
        val nullAccessor: PythonResultConverter.DictAccessor? = null
        val json = PythonResultConverter.convertSendMessageResult(nullAccessor)
        val parsed = JSONObject(json)

        // Then: Should return error JSON
        assertFalse("Should indicate failure", parsed.getBoolean("success"))
        assertTrue("Should have error message", parsed.optString("error").isNotEmpty())
    }

    @Test
    fun `convertSendMessageResult handles missing optional fields`() {
        // Given: A result without destination_hash
        val dictAccessor =
            FakeDictAccessor(
                mapOf(
                    "success" to true,
                    "message_hash" to byteArrayOf(0x01, 0x02),
                    "timestamp" to 12345L,
                    "delivery_method" to "direct",
                ),
            )

        // When: Converting to JSON
        val json = PythonResultConverter.convertSendMessageResult(dictAccessor)
        val parsed = JSONObject(json)

        // Then: Should still be valid JSON
        assertTrue(parsed.getBoolean("success"))
        // destination_hash may be empty or missing
    }

    /**
     * Fake implementation of DictAccessor for testing.
     * Simulates how Python dict values would be accessed.
     */
    class FakeDictAccessor(private val values: Map<String, Any?>) : PythonResultConverter.DictAccessor {
        override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

        override fun getLong(key: String): Long? = (values[key] as? Number)?.toLong()

        override fun getString(key: String): String? = values[key] as? String

        override fun getByteArray(key: String): ByteArray? = values[key] as? ByteArray
    }
}
