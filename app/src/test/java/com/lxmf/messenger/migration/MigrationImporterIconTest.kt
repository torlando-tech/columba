package com.lxmf.messenger.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for icon data in migration export/import.
 * Verifies that icon fields are properly serialized and deserialized.
 */
class MigrationImporterIconTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `IdentityExport serializes icon fields`() {
        val identity =
            IdentityExport(
                identityHash = "test_hash",
                displayName = "Test User",
                destinationHash = "dest_hash",
                keyData = "dGVzdA==",
                createdTimestamp = 1700000000000L,
                lastUsedTimestamp = 1700001000000L,
                isActive = true,
                iconName = "Star",
                iconForegroundColor = "#FFFFFF",
                iconBackgroundColor = "#FF5722",
            )

        val jsonString = json.encodeToString(identity)

        assert(jsonString.contains("\"iconName\":\"Star\""))
        assert(jsonString.contains("\"iconForegroundColor\":\"#FFFFFF\""))
        assert(jsonString.contains("\"iconBackgroundColor\":\"#FF5722\""))
    }

    @Test
    fun `IdentityExport deserializes icon fields`() {
        val jsonString =
            """
            {
                "identityHash": "test_hash",
                "displayName": "Test User",
                "destinationHash": "dest_hash",
                "keyData": "dGVzdA==",
                "createdTimestamp": 1700000000000,
                "lastUsedTimestamp": 1700001000000,
                "isActive": true,
                "iconName": "Heart",
                "iconForegroundColor": "#000000",
                "iconBackgroundColor": "#E91E63"
            }
            """.trimIndent()

        val identity = json.decodeFromString<IdentityExport>(jsonString)

        assertEquals("Heart", identity.iconName)
        assertEquals("#000000", identity.iconForegroundColor)
        assertEquals("#E91E63", identity.iconBackgroundColor)
    }

    @Test
    fun `IdentityExport handles missing icon fields for backward compatibility`() {
        // Old exports won't have icon fields
        val jsonString =
            """
            {
                "identityHash": "test_hash",
                "displayName": "Test User",
                "destinationHash": "dest_hash",
                "keyData": "dGVzdA==",
                "createdTimestamp": 1700000000000,
                "lastUsedTimestamp": 1700001000000,
                "isActive": true
            }
            """.trimIndent()

        val identity = json.decodeFromString<IdentityExport>(jsonString)

        assertNull(identity.iconName)
        assertNull(identity.iconForegroundColor)
        assertNull(identity.iconBackgroundColor)
    }

    @Test
    fun `IdentityExport with icon data round trips correctly`() {
        val original =
            IdentityExport(
                identityHash = "round_trip_hash",
                displayName = "Round Trip User",
                destinationHash = "dest_rt",
                keyData = "cm91bmR0cmlw",
                createdTimestamp = 1700000000000L,
                lastUsedTimestamp = 1700001000000L,
                isActive = true,
                iconName = "Rocket",
                iconForegroundColor = "#FFEB3B",
                iconBackgroundColor = "#673AB7",
            )

        val jsonString = json.encodeToString(original)
        val restored = json.decodeFromString<IdentityExport>(jsonString)

        assertEquals(original.iconName, restored.iconName)
        assertEquals(original.iconForegroundColor, restored.iconForegroundColor)
        assertEquals(original.iconBackgroundColor, restored.iconBackgroundColor)
    }
}
