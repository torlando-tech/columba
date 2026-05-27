package network.columba.app.rns.api.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [TelemeterCodec.telemetryRequestTimebaseSeconds] — the LXMF
 * `FIELD_COMMANDS` telemetry-request timebase, which must go on the wire in
 * epoch SECONDS to interop with Sideband/RCH.
 *
 * Regression guard for #927: the timebase was previously shipped in epoch
 * MILLISECONDS, which collectors read as seconds (`int(command_timebase)` /
 * `datetime.fromtimestamp`) — landing ~58000 AD ("year 58337 is out of
 * range") so RCH crashed and Sideband returned no telemetry.
 */
class TelemeterCodecTest {
    // 1_779_580_800_000 ms == 1_779_580_800 s, a ~2026 instant.
    private val millis2026 = 1_779_580_800_000L
    private val seconds2026 = 1_779_580_800L

    @Test
    fun `converts last-request millis to wire seconds`() {
        assertEquals(seconds2026, TelemeterCodec.telemetryRequestTimebaseSeconds(millis2026))
    }

    @Test
    fun `wire value is seconds-magnitude, not the raw millis that crashed collectors`() {
        val wire = TelemeterCodec.telemetryRequestTimebaseSeconds(millis2026)
        assertNotEquals(millis2026, wire)
        // 253_402_300_800 == year-9999 in epoch seconds. The #927 millis value
        // blew past this and crashed Python's datetime.fromtimestamp; a correct
        // seconds value sits far below it.
        assertTrue("timebase $wire still looks like millis", wire < 253_402_300_800L)
    }

    @Test
    fun `null first request maps to 0 - all history, never a null on the wire`() {
        // Sideband does int(timebase); a literal null would crash via int(None).
        assertEquals(0L, TelemeterCodec.telemetryRequestTimebaseSeconds(null))
    }

    @Test
    fun `zero stays zero`() {
        assertEquals(0L, TelemeterCodec.telemetryRequestTimebaseSeconds(0L))
    }
}
