package network.columba.app.rns.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the [toBundle] / [toAnyMap] round trip preserves the
 * `getDebugInfo()` "interfaces" shape — the regression A.10 exposed.
 * Before the fix, the `List<Map<String, Any>>` value fell through to
 * `value.toString()` so DebugViewModel's `as? List<Map<String, Any>>` cast
 * returned null and the Network Status screen rendered "Network
 * Interfaces (0)".
 */
@RunWith(RobolectricTestRunner::class)
class MapBundlingTest {
    @Test
    fun `round-trips primitive map unchanged`() {
        val source = mapOf(
            "backend" to "python-chaquopy",
            "initialized" to true,
            "path_table_size" to 7,
            "heartbeat_age_seconds" to 0L,
            "transport_enabled" to false,
        )
        val decoded = source.toBundle().toAnyMap()
        assertEquals(source, decoded)
    }

    @Test
    fun `round-trips list of interface maps as List of Map of String to Any`() {
        val source = mapOf<String, Any>(
            "backend" to "python-chaquopy",
            "interfaces" to listOf(
                mapOf<String, Any>(
                    "name" to "Auto Discovery",
                    "type" to "AutoInterface",
                    "online" to true,
                    "can_send" to true,
                    "rx_bytes" to 1234L,
                    "tx_bytes" to 5678L,
                    "parent_name" to "",
                ),
                mapOf<String, Any>(
                    "name" to "Bluetooth LE",
                    "type" to "BLEInterface",
                    "online" to false,
                    "can_send" to false,
                    "rx_bytes" to 0L,
                    "tx_bytes" to 0L,
                    "parent_name" to "",
                ),
            ),
        )

        val decoded = source.toBundle().toAnyMap()

        // DebugViewModel does this exact cast — it must succeed post-round-trip.
        @Suppress("UNCHECKED_CAST")
        val interfaces = decoded["interfaces"] as? List<Map<String, Any>>
        assertNotNull(
            "Expected `interfaces` to round-trip as List<Map<String, Any>>; got ${decoded["interfaces"]?.javaClass}",
            interfaces,
        )
        assertEquals(2, interfaces!!.size)
        assertEquals("Auto Discovery", interfaces[0]["name"])
        assertEquals(true, interfaces[0]["online"])
        assertEquals("Bluetooth LE", interfaces[1]["name"])
        assertEquals(false, interfaces[1]["online"])
    }

    @Test
    fun `empty list of maps decodes as absent key, matching empty-default semantics`() {
        // toBundle drops empty lists rather than writing an empty ArrayList,
        // because the reader's `as? List<...> ?: emptyList()` produces the
        // same observable result either way and we save a Parcel write.
        val source = mapOf<String, Any>(
            "backend" to "x",
            "interfaces" to emptyList<Map<String, Any>>(),
        )
        val decoded = source.toBundle().toAnyMap()
        assertTrue("expected `interfaces` to be absent for empty list", "interfaces" !in decoded)
        assertEquals("x", decoded["backend"])
    }

    @Test
    fun `nested single map decodes as Map of String to Any`() {
        val source = mapOf<String, Any>(
            "nested" to mapOf<String, Any>(
                "k1" to "v1",
                "k2" to 42,
            ),
        )
        val decoded = source.toBundle().toAnyMap()
        @Suppress("UNCHECKED_CAST")
        val nested = decoded["nested"] as? Map<String, Any>
        assertNotNull(nested)
        assertEquals("v1", nested!!["k1"])
        assertEquals(42, nested["k2"])
    }
}
