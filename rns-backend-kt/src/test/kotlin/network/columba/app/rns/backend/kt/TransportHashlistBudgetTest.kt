package network.columba.app.rns.backend.kt

import network.reticulum.common.Platform
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #1222: `OutOfMemoryError` on a 201 MB-heap phone, at
 * `ConcurrentHashMap$KeySetView.addAll` inside `Transport.addPacketHash`.
 *
 * `Platform.recommendedHashlistSize` is 50k on Android, but the dedup window
 * rotation used the flat desktop `HASHLIST_MAXSIZE` (1M) and rotated by copying
 * a full window into the previous one — so a phone held up to 2M map entries and
 * allocated a third window's worth at the worst possible moment.
 *
 * Pins the budget at the JVM stack the app ships, so a dependency downgrade
 * cannot silently reintroduce the unbounded window.
 */
class TransportHashlistBudgetTest {
    @After
    fun tearDown() {
        Transport.clearPacketHashlist()
    }

    @Test
    fun `packet dedup window stays inside the android hashlist budget`() {
        assertTrue("unit tests must see the Android platform", Platform.isAndroid)
        val budget = Platform.recommendedHashlistSize
        assertEquals("Android hashlist budget", 50_000, budget)

        Transport.clearPacketHashlist()
        repeat(budget * 2) { index -> Transport.addPacketHashForTest(hash(index)) }

        val retained = Transport.packetHashlistSizeForTest()
        assertTrue(
            "retained $retained hashes must not exceed the $budget-entry budget",
            retained <= budget,
        )
    }

    private fun hash(index: Int): ByteArray =
        ByteArray(32).also { bytes ->
            bytes[0] = (index and 0xFF).toByte()
            bytes[1] = ((index ushr 8) and 0xFF).toByte()
            bytes[2] = ((index ushr 16) and 0xFF).toByte()
            bytes[3] = ((index ushr 24) and 0xFF).toByte()
        }
}
