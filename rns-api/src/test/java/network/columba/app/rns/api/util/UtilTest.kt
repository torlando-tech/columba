package network.columba.app.rns.api.util

import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.msgpack.core.MessagePack
import org.robolectric.RobolectricTestRunner

/**
 * Covers the `:rns-api/util/` surface that both backends share:
 * - [HexExt] toHex / hexToBytes round-trip
 * - [Aspects] constants + ALL membership
 * - [NodeType.fromAspect] including the UNKNOWN fallback
 * - [AppDataParser] display-name / stamp-meta / icon-appearance parsing
 *
 * Robolectric is used because [AppDataParser] logs via `android.util.Log`
 * in its catch branches — without it, hitting a malformed-bytes path
 * blows up with `NoClassDefFoundError` on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class UtilTest {
    // ============================ HexExt ============================

    @Test
    fun `toHex emits lowercase fixed-width per byte`() {
        assertEquals("00ff", byteArrayOf(0x00, 0xff.toByte()).toHex())
        assertEquals("01ab", byteArrayOf(0x01, 0xab.toByte()).toHex())
        assertEquals("", byteArrayOf().toHex())
    }

    @Test
    fun `hexToBytes is the inverse of toHex`() {
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertEquals("deadbeef", bytes.toHex())
        assertTrue(bytes.contentEquals("deadbeef".hexToBytes()))
        assertTrue(bytes.contentEquals(bytes.toHex().hexToBytes()))
    }

    @Test
    fun `hexToBytes accepts mixed case`() {
        assertTrue(
            byteArrayOf(0xde.toByte(), 0xad.toByte()).contentEquals("DeAd".hexToBytes()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects odd-length input`() {
        "abc".hexToBytes()
    }

    @Test(expected = NumberFormatException::class)
    fun `hexToBytes rejects non-hex characters`() {
        "zz".hexToBytes()
    }

    // ============================ Aspects ============================

    @Test
    fun `Aspects ALL covers every NodeType-resolvable aspect`() {
        // ALL must contain every aspect that NodeType.fromAspect resolves
        // to a non-UNKNOWN value — that's the load-bearing invariant. If
        // someone adds a new aspect to one place without the other, this
        // catches it.
        assertEquals(4, Aspects.ALL.size)
        for (aspect in Aspects.ALL) {
            assertNotEquals(
                "aspect $aspect resolved to UNKNOWN but is in Aspects.ALL",
                NodeType.UNKNOWN,
                NodeType.fromAspect(aspect),
            )
        }
    }

    // ===================== NodeType.fromAspect =====================

    @Test
    fun `fromAspect maps each known aspect to its NodeType`() {
        assertEquals(NodeType.PROPAGATION_NODE, NodeType.fromAspect(Aspects.LXMF_PROPAGATION))
        assertEquals(NodeType.NODE, NodeType.fromAspect(Aspects.NOMADNET_NODE))
        assertEquals(NodeType.PHONE, NodeType.fromAspect(Aspects.LXST_TELEPHONY))
        assertEquals(NodeType.PEER, NodeType.fromAspect(Aspects.LXMF_DELIVERY))
    }

    @Test
    fun `fromAspect returns UNKNOWN for null or unknown aspects`() {
        assertEquals(NodeType.UNKNOWN, NodeType.fromAspect(null))
        assertEquals(NodeType.UNKNOWN, NodeType.fromAspect(""))
        assertEquals(NodeType.UNKNOWN, NodeType.fromAspect("meshchat.room"))
        assertEquals(NodeType.UNKNOWN, NodeType.fromAspect("lxmf.delivery.typo"))
    }

    // ===================== AppDataParser.parseDisplayName =====================

    @Test
    fun `parseDisplayName for nomadnet node keeps colon characters intact`() {
        // Regression: a previous `split(":")[0]` mangled this to just ".".
        val bytes = ".:FreeBSD 1st nomad node".toByteArray(Charsets.UTF_8)
        assertEquals(
            ".:FreeBSD 1st nomad node",
            AppDataParser.parseDisplayName(bytes, Aspects.NOMADNET_NODE),
        )
    }

    @Test
    fun `parseDisplayName returns null for empty appData`() {
        assertNull(AppDataParser.parseDisplayName(byteArrayOf(), Aspects.LXMF_DELIVERY))
        assertNull(AppDataParser.parseDisplayName(byteArrayOf(), Aspects.NOMADNET_NODE))
        assertNull(AppDataParser.parseDisplayName(byteArrayOf(), Aspects.LXMF_PROPAGATION))
    }

    @Test
    fun `parseDisplayName for lxmf delivery parses msgpack peer announce shape`() {
        // Peer announce is a msgpack array of [name_bytes, stamp_cost]
        // (LXMF.display_name_from_app_data convention).
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(2)
        val name = "Alice".toByteArray(Charsets.UTF_8)
        packer.packBinaryHeader(name.size)
        packer.writePayload(name)
        packer.packInt(42)
        val appData = packer.toByteArray()

        assertEquals("Alice", AppDataParser.parseDisplayName(appData, Aspects.LXMF_DELIVERY))
        assertEquals(42, AppDataParser.parsePeerStampCost(appData))
    }

    @Test
    fun `parseDisplayName returns null when msgpack header is truncated`() {
        // Header 0x91 = "array of length 1", but no element follows — the
        // unpacker throws inside the catch-block path. Critical that this
        // is logged + nulled, never bubbled up; Robolectric is here to make
        // `android.util.Log.w` resolve.
        val truncated = byteArrayOf(0x91.toByte())
        assertNull(AppDataParser.parseDisplayName(truncated, Aspects.LXMF_DELIVERY))
    }

    // ===================== AppDataParser.parseStampMeta =====================

    @Test
    fun `parseStampMeta returns all-null for null appData`() {
        val (a, b, c) = AppDataParser.parseStampMeta(null, Aspects.LXMF_DELIVERY)
        assertNull(a); assertNull(b); assertNull(c)
    }

    @Test
    fun `parseStampMeta for propagation aspect extracts full triple`() {
        // Propagation node announce: msgpack array length >= 6 where index 5
        // is the cost array [stampCost, flexibility, peering].
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        // skip 0..4
        repeat(5) { packer.packNil() }
        // index 5: cost array
        packer.packArrayHeader(3)
        packer.packInt(16) // stamp cost
        packer.packInt(3) // flexibility
        packer.packInt(18) // peering
        // index 6: arbitrary metadata map (skip)
        packer.packMapHeader(0)
        val appData = packer.toByteArray()

        val (stamp, flex, peering) = AppDataParser.parseStampMeta(appData, Aspects.LXMF_PROPAGATION)
        assertEquals(16, stamp)
        assertEquals(3, flex)
        assertEquals(18, peering)
    }

    // ===================== AppDataParser.parseIconAppearance =====================

    @Test
    fun `parseIconAppearance accepts the msgpack ByteArray shape`() {
        // Kotlin native path: msgpack-decoded values arrive as
        // [String, ByteArray, ByteArray] where the latter two are raw 3-byte
        // RGB that should be hex-encoded for IconAppearance.
        val field = listOf<Any?>(
            "torch",
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            byteArrayOf(0x1e, 0x88.toByte(), 0xe5.toByte()),
        )
        assertEquals(
            IconAppearance(iconName = "torch", foregroundColor = "ffffff", backgroundColor = "1e88e5"),
            AppDataParser.parseIconAppearance(field),
        )
    }

    @Test
    fun `parseIconAppearance accepts the JSON String shape`() {
        // Python flavor path: event_bridge.py hex-encodes the ByteArrays
        // before crossing JNI, so they arrive as already-hex strings.
        val field = listOf<Any?>("torch", "ffffff", "1e88e5")
        assertEquals(
            IconAppearance(iconName = "torch", foregroundColor = "ffffff", backgroundColor = "1e88e5"),
            AppDataParser.parseIconAppearance(field),
        )
    }

    @Test
    fun `parseIconAppearance accepts ByteArray name + String colors mix`() {
        val field = listOf<Any?>(
            "torch".toByteArray(Charsets.UTF_8),
            "ffffff",
            byteArrayOf(0x1e, 0x88.toByte(), 0xe5.toByte()),
        )
        assertNotNull(AppDataParser.parseIconAppearance(field))
    }

    @Test
    fun `parseIconAppearance returns null when malformed`() {
        assertNull(AppDataParser.parseIconAppearance(null))
        assertNull(AppDataParser.parseIconAppearance(emptyList<Any?>()))
        assertNull(AppDataParser.parseIconAppearance(listOf("only-name")))
        assertNull(AppDataParser.parseIconAppearance(listOf("name", "ffffff"))) // short
        assertNull(AppDataParser.parseIconAppearance(listOf("", "ffffff", "1e88e5"))) // empty name
        assertNull(AppDataParser.parseIconAppearance(listOf("name", "", "1e88e5"))) // empty fg
        assertNull(AppDataParser.parseIconAppearance(listOf("name", "ffffff", ""))) // empty bg
        assertNull(AppDataParser.parseIconAppearance(listOf(123, "ffffff", "1e88e5"))) // wrong type
    }
}
