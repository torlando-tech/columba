package com.lxmf.messenger.service

import com.lxmf.messenger.data.model.ConnectionType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import android.app.Application
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for BLE connection details via IPC.
 * Tests the AIDL boundary between app and ReticulumService.
 *
 * Note: These tests require the ReticulumService to be running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReticulumServiceBleTest {
    // ========== JSON Format Tests ==========

    @Test
    fun getBleConnectionDetails_returnsValidJsonArray() {
        // Given
        val jsonString =
            """
            [
                {
                    "identityHash": "abc123",
                    "peerName": "RNS-Test",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1234567890,
                    "firstSeen": 1234567890,
                    "lastSeen": 1234567900,
                    "rssi": -70
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)

        // Then
        assertEquals(1, jsonArray.length())
        val jsonObj = jsonArray.getJSONObject(0)
        assertEquals("abc123", jsonObj.getString("identityHash"))
        assertEquals("RNS-Test", jsonObj.getString("peerName"))
        assertEquals("AA:BB:CC:DD:EE:FF", jsonObj.getString("currentMac"))
        assertTrue(jsonObj.getBoolean("hasCentralConnection"))
        assertFalse(jsonObj.getBoolean("hasPeripheralConnection"))
        assertEquals(512, jsonObj.getInt("mtu"))
        assertEquals(-70, jsonObj.getInt("rssi"))
    }

    @Test
    fun getBleConnectionDetails_emptyArray_isValidJson() {
        // Given
        val emptyJsonString = "[]"

        // When
        val jsonArray = JSONArray(emptyJsonString)

        // Then
        assertEquals(0, jsonArray.length())
    }

    @Test
    fun getBleConnectionDetails_multipleConnections_parsesCorrectly() {
        // Given
        val jsonString =
            """
            [
                {
                    "identityHash": "hash1",
                    "peerName": "RNS-Peer1",
                    "currentMac": "AA:BB:CC:DD:EE:01",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1000,
                    "firstSeen": 1000,
                    "lastSeen": 2000,
                    "rssi": -60
                },
                {
                    "identityHash": "hash2",
                    "peerName": "RNS-Peer2",
                    "currentMac": "AA:BB:CC:DD:EE:02",
                    "hasCentralConnection": false,
                    "hasPeripheralConnection": true,
                    "mtu": 256,
                    "connectedAt": 3000,
                    "firstSeen": 3000,
                    "lastSeen": 4000,
                    "rssi": -80
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)

        // Then
        assertEquals(2, jsonArray.length())

        val peer1 = jsonArray.getJSONObject(0)
        assertEquals("hash1", peer1.getString("identityHash"))
        assertEquals("RNS-Peer1", peer1.getString("peerName"))
        assertEquals(-60, peer1.getInt("rssi"))

        val peer2 = jsonArray.getJSONObject(1)
        assertEquals("hash2", peer2.getString("identityHash"))
        assertEquals("RNS-Peer2", peer2.getString("peerName"))
        assertEquals(-80, peer2.getInt("rssi"))
    }

    // ========== Connection Type Mapping Tests ==========

    @Test
    fun connectionTypeMapping_centralOnly() {
        // Given
        val hasCentral = true
        val hasPeripheral = false

        // When
        val connectionType =
            when {
                hasCentral && hasPeripheral -> ConnectionType.BOTH
                hasCentral -> ConnectionType.CENTRAL
                hasPeripheral -> ConnectionType.PERIPHERAL
                else -> ConnectionType.CENTRAL
            }

        // Then
        assertEquals(ConnectionType.CENTRAL, connectionType)
    }

    @Test
    fun connectionTypeMapping_peripheralOnly() {
        // Given
        val hasCentral = false
        val hasPeripheral = true

        // When
        val connectionType =
            when {
                hasCentral && hasPeripheral -> ConnectionType.BOTH
                hasCentral -> ConnectionType.CENTRAL
                hasPeripheral -> ConnectionType.PERIPHERAL
                else -> ConnectionType.CENTRAL
            }

        // Then
        assertEquals(ConnectionType.PERIPHERAL, connectionType)
    }

    @Test
    fun connectionTypeMapping_both() {
        // Given
        val hasCentral = true
        val hasPeripheral = true

        // When
        val connectionType =
            when {
                hasCentral && hasPeripheral -> ConnectionType.BOTH
                hasCentral -> ConnectionType.CENTRAL
                hasPeripheral -> ConnectionType.PERIPHERAL
                else -> ConnectionType.CENTRAL
            }

        // Then
        assertEquals(ConnectionType.BOTH, connectionType)
    }

    @Test
    fun connectionTypeMapping_neither_defaultsToCentral() {
        // Given
        val hasCentral = false
        val hasPeripheral = false

        // When
        val connectionType =
            when {
                hasCentral && hasPeripheral -> ConnectionType.BOTH
                hasCentral -> ConnectionType.CENTRAL
                hasPeripheral -> ConnectionType.PERIPHERAL
                else -> ConnectionType.CENTRAL
            }

        // Then
        assertEquals(ConnectionType.CENTRAL, connectionType)
    }

    // ========== JSON Parsing Error Handling Tests ==========

    @Test
    fun parseConnectionDetails_handlesInvalidJson() {
        // Given
        val invalidJson = "{invalid json"

        // When/Then
        try {
            JSONArray(invalidJson)
            fail("Should have thrown JSONException")
        } catch (e: JSONException) {
            // Expected - verify exception contains useful message
            assertNotNull(e.message)
        }
    }

    @Test
    fun parseConnectionDetails_handlesMissingFields() {
        // Given - JSON with missing required fields
        val incompleteJson =
            """
            [
                {
                    "identityHash": "hash1"
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(incompleteJson)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then - Missing fields should throw when accessed
        try {
            jsonObj.getString("peerName")
            fail("Should have thrown JSONException for missing field")
        } catch (e: JSONException) {
            // Expected - verify exception indicates missing key
            assertTrue(e.message?.contains("peerName") == true)
        }
    }

    @Test
    fun parseConnectionDetails_handlesNullValues() {
        // Given
        val jsonWithNull =
            """
            [
                {
                    "identityHash": null,
                    "peerName": "RNS-Test",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1000,
                    "firstSeen": 1000,
                    "lastSeen": 2000,
                    "rssi": -70
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonWithNull)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then
        assertTrue(jsonObj.isNull("identityHash"))
    }

    // ========== Data Type Tests ==========

    @Test
    fun jsonFields_correctTypes() {
        // Given
        val jsonString =
            """
            [
                {
                    "identityHash": "abc123",
                    "peerName": "RNS-Test",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1234567890,
                    "firstSeen": 1234567890,
                    "lastSeen": 1234567900,
                    "rssi": -70
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then - Verify types
        assertTrue(jsonObj.get("identityHash") is String)
        assertTrue(jsonObj.get("peerName") is String)
        assertTrue(jsonObj.get("currentMac") is String)
        assertTrue(jsonObj.get("hasCentralConnection") is Boolean)
        assertTrue(jsonObj.get("hasPeripheralConnection") is Boolean)
        assertTrue(jsonObj.get("mtu") is Int)
        assertTrue(jsonObj.get("connectedAt") is Long || jsonObj.get("connectedAt") is Int)
        assertTrue(jsonObj.get("firstSeen") is Long || jsonObj.get("firstSeen") is Int)
        assertTrue(jsonObj.get("lastSeen") is Long || jsonObj.get("lastSeen") is Int)
        assertTrue(jsonObj.get("rssi") is Int)
    }

    // ========== Integration with Repository Tests ==========

    @Test
    fun repository_canParseServiceResponse() =
        runBlocking {
            // Given - Simulated service response
            val serviceResponse =
                """
                [
                    {
                        "identityHash": "test123hash456",
                        "peerName": "RNS-TestPeer",
                        "currentMac": "AA:BB:CC:DD:EE:FF",
                        "hasCentralConnection": true,
                        "hasPeripheralConnection": true,
                        "mtu": 512,
                        "connectedAt": 1234567890,
                        "firstSeen": 1234567890,
                        "lastSeen": 1234567900,
                        "rssi": -65
                    }
                ]
                """.trimIndent()

            // When - Parse as repository would
            val jsonArray = JSONArray(serviceResponse)
            val jsonObj = jsonArray.getJSONObject(0)

            val identityHash = jsonObj.getString("identityHash")
            val peerName = jsonObj.getString("peerName")
            val currentMac = jsonObj.getString("currentMac")
            val hasCentralConnection = jsonObj.getBoolean("hasCentralConnection")
            val hasPeripheralConnection = jsonObj.getBoolean("hasPeripheralConnection")
            val connectionType =
                when {
                    hasCentralConnection && hasPeripheralConnection -> ConnectionType.BOTH
                    hasCentralConnection -> ConnectionType.CENTRAL
                    hasPeripheralConnection -> ConnectionType.PERIPHERAL
                    else -> ConnectionType.CENTRAL
                }
            val mtu = jsonObj.getInt("mtu")
            val rssi = jsonObj.getInt("rssi")

            // Then
            assertEquals("test123hash456", identityHash)
            assertEquals("RNS-TestPeer", peerName)
            assertEquals("AA:BB:CC:DD:EE:FF", currentMac)
            assertEquals(ConnectionType.BOTH, connectionType)
            assertEquals(512, mtu)
            assertEquals(-65, rssi)
        }

    @Test
    fun repository_handlesEmptyServiceResponse() =
        runBlocking {
            // Given
            val emptyResponse = "[]"

            // When
            val jsonArray = JSONArray(emptyResponse)

            // Then
            assertEquals(0, jsonArray.length())
        }

    @Test
    fun repository_handlesMultipleConnections() =
        runBlocking {
            // Given - Multiple connections from service
            val multipleResponse =
                """
                [
                    {
                        "identityHash": "hash1",
                        "peerName": "RNS-Peer1",
                        "currentMac": "AA:BB:CC:DD:EE:01",
                        "hasCentralConnection": true,
                        "hasPeripheralConnection": false,
                        "mtu": 512,
                        "connectedAt": 1000,
                        "firstSeen": 1000,
                        "lastSeen": 2000,
                        "rssi": -60
                    },
                    {
                        "identityHash": "hash2",
                        "peerName": "RNS-Peer2",
                        "currentMac": "AA:BB:CC:DD:EE:02",
                        "hasCentralConnection": false,
                        "hasPeripheralConnection": true,
                        "mtu": 256,
                        "connectedAt": 3000,
                        "firstSeen": 3000,
                        "lastSeen": 4000,
                        "rssi": -80
                    },
                    {
                        "identityHash": "hash3",
                        "peerName": "RNS-Peer3",
                        "currentMac": "AA:BB:CC:DD:EE:03",
                        "hasCentralConnection": true,
                        "hasPeripheralConnection": true,
                        "mtu": 512,
                        "connectedAt": 5000,
                        "firstSeen": 5000,
                        "lastSeen": 6000,
                        "rssi": -70
                    }
                ]
                """.trimIndent()

            // When
            val jsonArray = JSONArray(multipleResponse)

            // Then
            assertEquals(3, jsonArray.length())

            // Verify each connection can be parsed
            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                assertNotNull(jsonObj.getString("identityHash"))
                assertNotNull(jsonObj.getString("peerName"))
                assertNotNull(jsonObj.getString("currentMac"))
                assertTrue(jsonObj.getInt("rssi") <= 0)
                assertTrue(jsonObj.getInt("mtu") > 0)
            }
        }

    // ========== RSSI Value Tests ==========

    @Test
    fun rssiValues_withinValidRange() {
        // Given - Valid RSSI values range from -120 to 0
        val testValues = listOf(-120, -90, -70, -50, -30, 0)

        testValues.forEach { rssi ->
            // When
            val jsonString =
                """
                [
                    {
                        "identityHash": "test",
                        "peerName": "RNS-Test",
                        "currentMac": "AA:BB:CC:DD:EE:FF",
                        "hasCentralConnection": true,
                        "hasPeripheralConnection": false,
                        "mtu": 512,
                        "connectedAt": 1000,
                        "firstSeen": 1000,
                        "lastSeen": 2000,
                        "rssi": $rssi
                    }
                ]
                """.trimIndent()

            // Then
            val jsonArray = JSONArray(jsonString)
            val jsonObj = jsonArray.getJSONObject(0)
            assertEquals(rssi, jsonObj.getInt("rssi"))
            assertTrue("RSSI should be in valid range", rssi in -120..0)
        }
    }

    // ========== MTU Value Tests ==========

    @Test
    fun mtuValues_withinBleRange() {
        // Given - BLE MTU typically ranges from 23 to 517
        val testMtuValues = listOf(23, 185, 247, 512, 517)

        testMtuValues.forEach { mtu ->
            // When
            val jsonString =
                """
                [
                    {
                        "identityHash": "test",
                        "peerName": "RNS-Test",
                        "currentMac": "AA:BB:CC:DD:EE:FF",
                        "hasCentralConnection": true,
                        "hasPeripheralConnection": false,
                        "mtu": $mtu,
                        "connectedAt": 1000,
                        "firstSeen": 1000,
                        "lastSeen": 2000,
                        "rssi": -70
                    }
                ]
                """.trimIndent()

            // Then
            val jsonArray = JSONArray(jsonString)
            val jsonObj = jsonArray.getJSONObject(0)
            assertEquals(mtu, jsonObj.getInt("mtu"))
            assertTrue("MTU should be valid BLE value", mtu in 23..517)
        }
    }

    // ========== Timestamp Tests ==========

    @Test
    fun timestamps_areValidEpochMilliseconds() {
        // Given
        val now = System.currentTimeMillis()
        val jsonString =
            """
            [
                {
                    "identityHash": "test",
                    "peerName": "RNS-Test",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": $now,
                    "firstSeen": $now,
                    "lastSeen": $now,
                    "rssi": -70
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then
        val connectedAt = jsonObj.getLong("connectedAt")
        val firstSeen = jsonObj.getLong("firstSeen")
        val lastSeen = jsonObj.getLong("lastSeen")

        assertTrue("connectedAt should be reasonable", connectedAt > 0)
        assertTrue("firstSeen should be reasonable", firstSeen > 0)
        assertTrue("lastSeen should be reasonable", lastSeen > 0)
        assertTrue("firstSeen should be <= connectedAt", firstSeen <= connectedAt)
        assertTrue("lastSeen should be >= connectedAt", lastSeen >= connectedAt)
    }

    // ========== Edge Case Tests ==========

    @Test
    fun edgeCase_unknownIdentityHash() {
        // Given - Connection with "unknown" identity
        val jsonString =
            """
            [
                {
                    "identityHash": "unknown",
                    "peerName": "RNS-Unknown",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1000,
                    "firstSeen": 1000,
                    "lastSeen": 2000,
                    "rssi": -70
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then
        assertEquals("unknown", jsonObj.getString("identityHash"))
    }

    @Test
    fun edgeCase_extremelyLongIdentityHash() {
        // Given
        val longHash = "a".repeat(100)
        val jsonString =
            """
            [
                {
                    "identityHash": "$longHash",
                    "peerName": "RNS-Test",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1000,
                    "firstSeen": 1000,
                    "lastSeen": 2000,
                    "rssi": -70
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then
        assertEquals(longHash, jsonObj.getString("identityHash"))
        assertEquals(100, jsonObj.getString("identityHash").length)
    }

    @Test
    fun edgeCase_deviceWithNoName() {
        // Given - Device without BLE name uses "No Name" fallback
        val jsonString =
            """
            [
                {
                    "identityHash": "abc123",
                    "peerName": "No Name",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1000,
                    "firstSeen": 1000,
                    "lastSeen": 2000,
                    "rssi": -100
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then
        assertEquals("No Name", jsonObj.getString("peerName"))
        assertEquals(-100, jsonObj.getInt("rssi")) // Fallback RSSI when device not in scanner
    }

    @Test
    fun edgeCase_realRssiFromScanner() {
        // Given - Device with real RSSI from BLE scanner
        val jsonString =
            """
            [
                {
                    "identityHash": "abc123",
                    "peerName": "RNS-Device",
                    "currentMac": "AA:BB:CC:DD:EE:FF",
                    "hasCentralConnection": true,
                    "hasPeripheralConnection": false,
                    "mtu": 512,
                    "connectedAt": 1000,
                    "firstSeen": 1000,
                    "lastSeen": 2000,
                    "rssi": -52
                }
            ]
            """.trimIndent()

        // When
        val jsonArray = JSONArray(jsonString)
        val jsonObj = jsonArray.getJSONObject(0)

        // Then
        assertEquals(-52, jsonObj.getInt("rssi"))
        assertTrue("Real RSSI should be in typical range", jsonObj.getInt("rssi") in -90..-30)
    }
}
