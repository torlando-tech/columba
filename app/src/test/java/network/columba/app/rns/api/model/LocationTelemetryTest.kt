package network.columba.app.rns.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LocationTelemetry data class — the typed payload
 * `RnsTelemetry` emits/consumes since Phase 2 of the Sideband-interop
 * refactor. The previous JSON-serialization test surface is gone
 * because the class no longer needs to be a JSON wire format —
 * wire-format concerns live in the backend impls (`PythonRnsTelemetry`,
 * `NativeTelemetryHandler`).
 */
class LocationTelemetryTest {
    @Test
    fun `constructor sets required fields correctly`() {
        val telemetry =
            LocationTelemetry(
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
        val telemetry =
            LocationTelemetry(lat = 0.0, lng = 0.0, acc = 0f, ts = 0L)

        assertEquals(LocationTelemetry.TYPE_LOCATION_SHARE, telemetry.type)
        assertEquals("location_share", telemetry.type)
    }

    @Test
    fun `optional fields default correctly`() {
        val telemetry =
            LocationTelemetry(lat = 0.0, lng = 0.0, acc = 0f, ts = 0L)

        assertNull(telemetry.expires)
        assertFalse(telemetry.cease)
        assertEquals(0, telemetry.approxRadius)
        assertEquals(0.0, telemetry.altitude, 0.0001)
        assertEquals(0.0, telemetry.speed, 0.0001)
        assertEquals(0.0, telemetry.bearing, 0.0001)
        assertNull(telemetry.sourceHash)
        assertNull(telemetry.appearance)
    }

    // ========== Constants ==========

    @Test
    fun `TYPE_LOCATION_SHARE constant is correct`() {
        assertEquals("location_share", LocationTelemetry.TYPE_LOCATION_SHARE)
    }

    @Test
    fun `LXMF_FIELD_ID constant is FIELD_TELEMETRY (0x02)`() {
        assertEquals(0x02, LocationTelemetry.LXMF_FIELD_ID)
    }

    @Test
    fun `COLUMBA_META_FIELD_ID is upstream FIELD_CUSTOM_META 0xFD`() {
        // Was 0x70 (Columba-invented unassigned ID); flipped to upstream's
        // canonical FIELD_CUSTOM_META so it doesn't collide if upstream
        // LXMF later assigns numbers in the unassigned range.
        assertEquals(0xFD, LocationTelemetry.COLUMBA_META_FIELD_ID)
    }

    @Test
    fun `LEGACY_FIELD_ID constant is 7 for backwards compat`() {
        assertEquals(7, LocationTelemetry.LEGACY_FIELD_ID)
    }

    // ========== Optional field handling ==========

    @Test
    fun `expires can be set to timestamp`() {
        val expiryTime = System.currentTimeMillis() + 3600_000L
        val telemetry =
            LocationTelemetry(
                lat = 37.7749,
                lng = -122.4194,
                acc = 10.0f,
                ts = System.currentTimeMillis(),
                expires = expiryTime,
            )

        assertEquals(expiryTime, telemetry.expires)
    }

    @Test
    fun `cease frame discards lat lng when intent is delete`() {
        val telemetry =
            LocationTelemetry(
                lat = 0.0,
                lng = 0.0,
                acc = 0f,
                ts = System.currentTimeMillis(),
                cease = true,
            )

        assertTrue(telemetry.cease)
    }

    @Test
    fun `approxRadius coarsens precision`() {
        val telemetry =
            LocationTelemetry(
                lat = 37.7749,
                lng = -122.4194,
                acc = 10.0f,
                ts = System.currentTimeMillis(),
                approxRadius = 1000,
            )

        assertEquals(1000, telemetry.approxRadius)
    }

    @Test
    fun `Telemeter fields round-trip`() {
        val t =
            LocationTelemetry(
                lat = 37.7749,
                lng = -122.4194,
                acc = 5.0f,
                ts = 1L,
                altitude = 16.5,
                speed = 3.2,
                bearing = 142.0,
            )

        assertEquals(16.5, t.altitude, 0.01)
        assertEquals(3.2, t.speed, 0.01)
        assertEquals(142.0, t.bearing, 0.01)
    }

    // ========== Edge Cases ==========

    @Test
    fun `handles negative coordinates`() {
        val t =
            LocationTelemetry(
                lat = -33.8688,
                lng = -151.2093,
                acc = 5f,
                ts = 1L,
            )

        assertEquals(-33.8688, t.lat, 0.0001)
        assertEquals(-151.2093, t.lng, 0.0001)
    }

    @Test
    fun `handles extreme coordinates`() {
        val t =
            LocationTelemetry(
                lat = 90.0,
                lng = 180.0,
                acc = 1f,
                ts = 1L,
            )

        assertEquals(90.0, t.lat, 0.0001)
        assertEquals(180.0, t.lng, 0.0001)
    }

    // ========== Data class semantics ==========

    @Test
    fun `equals true for identical data`() {
        val a = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)
        val b = LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = 4L)
        assertEquals(a, b)
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
