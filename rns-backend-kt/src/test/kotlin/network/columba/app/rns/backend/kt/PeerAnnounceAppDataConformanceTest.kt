package network.columba.app.rns.backend.kt

import network.columba.app.rns.api.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-impl conformance: the Kotlin backend's LXMF peer-announce app-data
 * ([NativeRnsBackendImpl.buildPeerAnnounceAppData]) must be byte-identical to
 * what the upstream Python LXMF reference emits.
 *
 * Reference: `LXMRouter.get_announce_app_data()` packs a 2-element array
 * `[display_name_bytes_or_None, stamp_cost_or_None]` via `msgpack.packb(...)`
 * (LXMF/LXMRouter.py). Columba registers its delivery identity without a stamp
 * cost, so the reference second element is always nil — which is exactly what
 * [NativeRnsBackendImpl.buildPeerAnnounceAppData] packs.
 *
 * The expected hex strings below are ground truth produced by the Python
 * reference itself:
 *
 *   import msgpack
 *   msgpack.packb([name.encode("utf-8"), None]).hex()
 *
 * (verified against msgpack 1.1.2, whose default `use_bin_type=True` encodes
 * the name as a msgpack bin — matching Kotlin's `packBinaryHeader`). If this
 * test ever fails, the Kotlin announce has diverged from the wire format other
 * LXMF peers expect; re-derive the vectors above before touching the assertion.
 */
class PeerAnnounceAppDataConformanceTest {
    private fun pythonReferenceAppData(displayName: String): String =
        NativeRnsBackendImpl.buildPeerAnnounceAppData(displayName).toHex()

    @Test
    fun `ascii display name matches python msgpack bin encoding`() {
        // msgpack.packb(["Test User".encode("utf-8"), None]).hex()
        assertEquals("92c409546573742055736572c0", pythonReferenceAppData("Test User"))
    }

    @Test
    fun `single char display name matches python`() {
        // msgpack.packb([b"A", None]).hex()
        assertEquals("92c40141c0", pythonReferenceAppData("A"))
    }

    @Test
    fun `empty display name matches python (bin8 length zero)`() {
        // msgpack.packb([b"", None]).hex()
        assertEquals("92c400c0", pythonReferenceAppData(""))
    }

    @Test
    fun `utf8 multibyte display name matches python byte-for-byte`() {
        // msgpack.packb(["Café ☕".encode("utf-8"), None]).hex()
        assertEquals("92c409436166c3a920e29895c0", pythonReferenceAppData("Café ☕"))
    }
}
