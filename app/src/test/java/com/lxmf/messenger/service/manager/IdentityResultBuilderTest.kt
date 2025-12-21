package com.lxmf.messenger.service.manager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for IdentityResultBuilder.
 */
class IdentityResultBuilderTest {
    @Test
    fun `buildIdentityResultJson includes all fields when present`() {
        val result =
            buildIdentityResultJson(
                identityHash = "abc123",
                destinationHash = "def456",
                filePath = "/path/to/identity",
                keyDataBase64 = "dGVzdC1rZXktZGF0YQ==",
                displayName = "Test User",
            )

        val json = JSONObject(result)
        assertEquals("abc123", json.getString("identity_hash"))
        assertEquals("def456", json.getString("destination_hash"))
        assertEquals("/path/to/identity", json.getString("file_path"))
        assertEquals("Test User", json.getString("display_name"))
        assertEquals("dGVzdC1rZXktZGF0YQ==", json.getString("key_data"))
    }

    @Test
    fun `buildIdentityResultJson omits key_data when null`() {
        val result =
            buildIdentityResultJson(
                identityHash = "abc123",
                destinationHash = "def456",
                filePath = "/path/to/identity",
                keyDataBase64 = null,
                displayName = "Test User",
            )

        val json = JSONObject(result)
        assertEquals("abc123", json.getString("identity_hash"))
        assertEquals("def456", json.getString("destination_hash"))
        assertFalse(json.has("key_data"))
    }

    @Test
    fun `buildIdentityResultJson handles null string fields`() {
        val result =
            buildIdentityResultJson(
                identityHash = null,
                destinationHash = null,
                filePath = null,
                keyDataBase64 = null,
                displayName = null,
            )

        val json = JSONObject(result)
        // JSONObject.put with null value results in JSONObject.NULL
        assertTrue(json.isNull("identity_hash"))
        assertTrue(json.isNull("destination_hash"))
        assertTrue(json.isNull("file_path"))
        assertTrue(json.isNull("display_name"))
        assertFalse(json.has("key_data"))
    }

    @Test
    fun `buildIdentityResultJson with empty keyDataBase64 includes key_data`() {
        val result =
            buildIdentityResultJson(
                identityHash = "abc123",
                destinationHash = "def456",
                filePath = "/path/to/identity",
                keyDataBase64 = "",
                displayName = "Test User",
            )

        val json = JSONObject(result)
        assertTrue(json.has("key_data"))
        assertEquals("", json.getString("key_data"))
    }
}
