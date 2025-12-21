package com.lxmf.messenger.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LocationTelemetry data class.
 * Tests serialization, default values, and constants.
 */
class LocationTelemetryTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ========== Construction and Default Values ==========

    @Test
    fun `constructor sets required fields correctly`() {
        val telemetry = LocationTelemetry(
            lat = 37.7749,
            lng = -122.4194,
            acc = 10.0f,
            ts = 1234567890L,
        )

        assertEquals(37.7749, telemetry.lat, 0.0001)
        assertEquals(-122.4194, telemetry.lng, 0.0001)
        assertEquals(10.0f, telemetry.acc, 0.01f)
        assertEquals(1234567890L, telemetry.ts)
    }

    @Test
    fun `type defaults to location_share`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = 0L,
        )

        assertEquals(LocationTelemetry.TYPE_LOCATION_SHARE, telemetry.type)
        assertEquals("location_share", telemetry.type)
    }

    @Test
    fun `expires defaults to null`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = 0L,
        )

        assertNull(telemetry.expires)
    }

    @Test
    fun `cease defaults to false`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = 0L,
        )

        assertFalse(telemetry.cease)
    }

    @Test
    fun `approxRadius defaults to 0`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = 0L,
        )

        assertEquals(0, telemetry.approxRadius)
    }

    // ========== Constants ==========

    @Test
    fun `TYPE_LOCATION_SHARE constant is correct`() {
        assertEquals("location_share", LocationTelemetry.TYPE_LOCATION_SHARE)
    }

    @Test
    fun `LXMF_FIELD_ID constant is 7`() {
        assertEquals(7, LocationTelemetry.LXMF_FIELD_ID)
    }

    // ========== Optional Field Handling ==========

    @Test
    fun `expires can be set to timestamp`() {
        val expiryTime = System.currentTimeMillis() + 3600_000L
        val telemetry = LocationTelemetry(
            lat = 37.7749,
            lng = -122.4194,
            acc = 10.0f,
            ts = System.currentTimeMillis(),
            expires = expiryTime,
        )

        assertEquals(expiryTime, telemetry.expires)
    }

    @Test
    fun `cease can be set to true`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = 0L,
            cease = true,
        )

        assertTrue(telemetry.cease)
    }

    @Test
    fun `approxRadius can be set to coarsening value`() {
        val telemetry = LocationTelemetry(
            lat = 37.7749,
            lng = -122.4194,
            acc = 10.0f,
            ts = System.currentTimeMillis(),
            approxRadius = 1000, // 1km coarsening
        )

        assertEquals(1000, telemetry.approxRadius)
    }

    // ========== JSON Serialization ==========

    @Test
    fun `serializes to JSON correctly`() {
        val telemetry = LocationTelemetry(
            lat = 37.7749,
            lng = -122.4194,
            acc = 10.0f,
            ts = 1234567890L,
        )

        val jsonString = json.encodeToString(telemetry)

        // Verify key fields are present in the JSON output
        assertTrue("JSON should contain lat field", jsonString.contains("lat") && jsonString.contains("37.7749"))
        assertTrue("JSON should contain lng field", jsonString.contains("lng") && jsonString.contains("-122.4194"))
        assertTrue("JSON should contain ts field", jsonString.contains("ts") && jsonString.contains("1234567890"))
    }

    @Test
    fun `deserializes from JSON correctly`() {
        val jsonString = """
            {
                "type": "location_share",
                "lat": 40.7128,
                "lng": -74.0060,
                "acc": 15.5,
                "ts": 9876543210
            }
        """.trimIndent()

        val telemetry = json.decodeFromString<LocationTelemetry>(jsonString)

        assertEquals("location_share", telemetry.type)
        assertEquals(40.7128, telemetry.lat, 0.0001)
        assertEquals(-74.0060, telemetry.lng, 0.0001)
        assertEquals(15.5f, telemetry.acc, 0.01f)
        assertEquals(9876543210L, telemetry.ts)
    }

    @Test
    fun `deserializes with optional fields`() {
        val jsonString = """
            {
                "type": "location_share",
                "lat": 51.5074,
                "lng": -0.1278,
                "acc": 5.0,
                "ts": 1111111111,
                "expires": 2222222222,
                "cease": true,
                "approxRadius": 500
            }
        """.trimIndent()

        val telemetry = json.decodeFromString<LocationTelemetry>(jsonString)

        assertEquals(51.5074, telemetry.lat, 0.0001)
        assertEquals(-0.1278, telemetry.lng, 0.0001)
        assertEquals(2222222222L, telemetry.expires)
        assertTrue(telemetry.cease)
        assertEquals(500, telemetry.approxRadius)
    }

    @Test
    fun `serialization roundtrip preserves data`() {
        val original = LocationTelemetry(
            lat = 35.6762,
            lng = 139.6503,
            acc = 8.5f,
            ts = 5555555555L,
            expires = 6666666666L,
            cease = false,
            approxRadius = 250,
        )

        val jsonString = json.encodeToString(original)
        val decoded = json.decodeFromString<LocationTelemetry>(jsonString)

        assertEquals(original.type, decoded.type)
        assertEquals(original.lat, decoded.lat, 0.0001)
        assertEquals(original.lng, decoded.lng, 0.0001)
        assertEquals(original.acc, decoded.acc, 0.01f)
        assertEquals(original.ts, decoded.ts)
        assertEquals(original.expires, decoded.expires)
        assertEquals(original.cease, decoded.cease)
        assertEquals(original.approxRadius, decoded.approxRadius)
    }

    @Test
    fun `deserializes with missing optional fields using defaults`() {
        val jsonString = """
            {
                "type": "location_share",
                "lat": 48.8566,
                "lng": 2.3522,
                "acc": 12.0,
                "ts": 7777777777
            }
        """.trimIndent()

        val telemetry = json.decodeFromString<LocationTelemetry>(jsonString)

        // Optional fields should use defaults
        assertNull(telemetry.expires)
        assertFalse(telemetry.cease)
        assertEquals(0, telemetry.approxRadius)
    }

    // ========== Edge Cases ==========

    @Test
    fun `handles zero coordinates`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = 0L,
        )

        assertEquals(0.0, telemetry.lat, 0.0001)
        assertEquals(0.0, telemetry.lng, 0.0001)
    }

    @Test
    fun `handles negative coordinates`() {
        val telemetry = LocationTelemetry(
            lat = -33.8688,
            lng = -151.2093,
            acc = 5f,
            ts = 1L,
        )

        assertEquals(-33.8688, telemetry.lat, 0.0001)
        assertEquals(-151.2093, telemetry.lng, 0.0001)
    }

    @Test
    fun `handles extreme coordinates`() {
        val telemetry = LocationTelemetry(
            lat = 90.0, // North pole
            lng = 180.0, // International date line
            acc = 1f,
            ts = 1L,
        )

        assertEquals(90.0, telemetry.lat, 0.0001)
        assertEquals(180.0, telemetry.lng, 0.0001)
    }

    @Test
    fun `handles very large timestamp`() {
        val futureTs = Long.MAX_VALUE
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = 0f,
            ts = futureTs,
        )

        assertEquals(futureTs, telemetry.ts)
    }

    @Test
    fun `handles high accuracy value`() {
        val telemetry = LocationTelemetry(
            lat = 0.0,
            lng = 0.0,
            acc = Float.MAX_VALUE,
            ts = 0L,
        )

        assertEquals(Float.MAX_VALUE, telemetry.acc, 0.1f)
    }

    // ========== Data Class Equality ==========

    @Test
    fun `equals returns true for identical data`() {
        val t1 = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)
        val t2 = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)

        assertEquals(t1, t2)
    }

    @Test
    fun `equals returns false for different data`() {
        val t1 = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)
        val t2 = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 5L)

        assertFalse(t1 == t2)
    }

    @Test
    fun `hashCode is consistent for equal objects`() {
        val t1 = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)
        val t2 = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)

        assertEquals(t1.hashCode(), t2.hashCode())
    }

    @Test
    fun `copy creates new instance with modified values`() {
        val original = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)
        val copied = original.copy(lat = 10.0)

        assertEquals(10.0, copied.lat, 0.0001)
        assertEquals(original.lng, copied.lng, 0.0001)
        assertEquals(original.ts, copied.ts)
    }
}
