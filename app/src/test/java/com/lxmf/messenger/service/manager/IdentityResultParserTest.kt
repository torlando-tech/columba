package com.lxmf.messenger.service.manager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IdentityResultParserTest {
    @Test
    fun `parseIdentityResultJson with all fields present`() {
        val keyData = byteArrayOf(1, 2, 3, 4, 5)
        val keyDataBase64 = java.util.Base64.getEncoder().encodeToString(keyData)

        val json =
            """
            {
                "identity_hash": "abc123",
                "destination_hash": "def456",
                "file_path": "/path/to/identity",
                "key_data": "$keyDataBase64",
                "display_name": "Test User"
            }
            """.trimIndent()

        val result = parseIdentityResultJson(json)

        assertEquals("abc123", result["identity_hash"])
        assertEquals("def456", result["destination_hash"])
        assertEquals("/path/to/identity", result["file_path"])
        assertArrayEquals(keyData, result["key_data"] as ByteArray)
        assertFalse(result.containsKey("error"))
    }

    @Test
    fun `parseIdentityResultJson with error field`() {
        val json =
            """
            {
                "error": "Identity creation failed"
            }
            """.trimIndent()

        val result = parseIdentityResultJson(json)

        assertEquals("Identity creation failed", result["error"])
        assertFalse(result.containsKey("identity_hash"))
    }

    @Test
    fun `parseIdentityResultJson with missing optional fields`() {
        val json =
            """
            {
                "identity_hash": "abc123",
                "destination_hash": "def456"
            }
            """.trimIndent()

        val result = parseIdentityResultJson(json)

        assertEquals("abc123", result["identity_hash"])
        assertEquals("def456", result["destination_hash"])
        assertFalse(result.containsKey("file_path"))
        assertFalse(result.containsKey("key_data"))
    }

    @Test
    fun `parseIdentityResultJson with empty key_data`() {
        val emptyKeyData = byteArrayOf()
        val keyDataBase64 = java.util.Base64.getEncoder().encodeToString(emptyKeyData)

        val json =
            """
            {
                "identity_hash": "abc123",
                "key_data": "$keyDataBase64"
            }
            """.trimIndent()

        val result = parseIdentityResultJson(json)

        assertEquals("abc123", result["identity_hash"])
        // Empty base64 decodes to empty array, which should still be included
        assertArrayEquals(emptyKeyData, result["key_data"] as ByteArray)
    }

    @Test
    fun `parseIdentityResultJson with invalid base64 key_data omits key`() {
        val json =
            """
            {
                "identity_hash": "abc123",
                "key_data": "not-valid-base64!!!"
            }
            """.trimIndent()

        val result = parseIdentityResultJson(json)

        assertEquals("abc123", result["identity_hash"])
        // Invalid base64 should result in key_data being omitted
        assertFalse(result.containsKey("key_data"))
    }
}
