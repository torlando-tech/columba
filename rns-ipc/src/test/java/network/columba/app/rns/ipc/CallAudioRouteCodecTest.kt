package network.columba.app.rns.ipc

import network.columba.app.rns.api.model.CallAudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the call-audio route wire format used by
 * `IRnsTelephony.getCallAudioRoutes`.
 */
class CallAudioRouteCodecTest {
    @Test
    fun `routes survive an encode-decode round trip`() {
        val routes =
            listOf(
                CallAudioRoute(deviceId = 1, type = 1, name = "Earpiece", isActive = false),
                CallAudioRoute(deviceId = 2, type = 2, name = "Speaker", isActive = true),
                CallAudioRoute(deviceId = 42, type = 8, name = "Galaxy Buds|Pro", isActive = false),
            )

        val decoded = CallAudioRouteCodec.decode(CallAudioRouteCodec.encode(routes))

        assertEquals(routes, decoded)
    }

    @Test
    fun `names keep field-separator characters`() {
        val route = CallAudioRoute(deviceId = 7, type = 8, name = "Head\u001Fset", isActive = true)

        val decoded = CallAudioRouteCodec.decode(CallAudioRouteCodec.encode(listOf(route)))

        assertEquals(route, decoded.single())
    }

    @Test
    fun `malformed entries are dropped`() {
        val entries = listOf("", "notanumber\u001F8\u001F1\u001FName", "3\u001F8\u001F1", "3\u001F8")

        assertTrue(CallAudioRouteCodec.decode(entries).isEmpty())
    }
}
