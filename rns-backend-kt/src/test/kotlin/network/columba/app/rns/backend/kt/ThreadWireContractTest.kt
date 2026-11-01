package network.columba.app.rns.backend.kt

import network.columba.app.rns.api.model.DeliveryStatusEventStream
import network.columba.app.rns.api.util.LxmfFields
import network.columba.app.rns.api.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static wire contract for the four thread message cases (threads-spec §4.2,
 * TOR-24 acceptance): the exact `fields` dict each case puts on the wire.
 *
 * Case 2 must be exactly `{8: bin(16B), 48: bin(32B), 49: <utf8 quote>}` —
 * no other keys. `0x31` carries raw UTF-8 bytes (msgpack bin), matching the
 * pre-existing MeshChatX-compatible reply encoding this build already ships
 * for non-threaded replies (`NativeMessageSender` 0x30/0x31 block); receivers
 * hex-decode either msgpack bin or str.
 *
 * The root message M is NOT re-sent for case 2 — that is structural here:
 * the fields dict is the only thread-specific surface, and nothing else in
 * the send path references M.
 */
class ThreadWireContractTest {
    private val sender =
        NativeMessageSender(
            routerProvider = { null },
            deliveryIdentityProvider = { null },
            deliveryDestinationProvider = { null },
            deliveryStatusEvents = DeliveryStatusEventStream(),
            scopeProvider = { error("not used by buildFields") },
        )

    private val threadId = ByteArray(16) { (it + 1).toByte() }
    private val rootHash = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun `case 1 - new thread from scratch is exactly 8 colon T`() {
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(threadId = threadId),
            )

        assertEquals("exactly one field", setOf(LxmfFields.FIELD_THREAD), fields.keys)
        assertArrayEquals(threadId, fields[LxmfFields.FIELD_THREAD] as ByteArray)
        assertEquals(16, (fields[LxmfFields.FIELD_THREAD] as ByteArray).size)
    }

    @Test
    fun `case 2 - thread from here is exactly 8 48 49 with root not re-sent`() {
        val quote = "the root message excerpt"
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(
                    threadId = threadId,
                    replyToMessageId = rootHash.toHex(),
                    replyQuotedContent = quote,
                ),
            )

        assertEquals(
            "exactly the three case-2 fields",
            setOf(LxmfFields.FIELD_THREAD, LxmfFields.FIELD_REPLY_HASH, LxmfFields.FIELD_REPLY_QUOTE),
            fields.keys,
        )
        assertArrayEquals(threadId, fields[LxmfFields.FIELD_THREAD] as ByteArray)
        assertArrayEquals(rootHash, fields[LxmfFields.FIELD_REPLY_HASH] as ByteArray)
        assertEquals(32, (fields[LxmfFields.FIELD_REPLY_HASH] as ByteArray).size)
        assertArrayEquals(quote.toByteArray(Charsets.UTF_8), fields[LxmfFields.FIELD_REPLY_QUOTE] as ByteArray)
    }

    @Test
    fun `case 3 - subsequent thread message is exactly 8 colon T again (same T reused)`() {
        // Reuse for the thread's lifetime: same bytes as case 1, nothing else.
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(threadId = threadId),
            )
        assertEquals(setOf(0x08), fields.keys)
        assertArrayEquals(threadId, fields[0x08] as ByteArray)
    }

    @Test
    fun `case 4 - reply inside thread is 8 plus 48 49 of the replied message`() {
        val replyTargetHash = ByteArray(32) { (it * 7 + 1).toByte() }
        val quote = "quoted content of N"
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(
                    threadId = threadId,
                    replyToMessageId = replyTargetHash.toHex(),
                    replyQuotedContent = quote,
                ),
            )

        assertEquals(
            setOf(LxmfFields.FIELD_THREAD, LxmfFields.FIELD_REPLY_HASH, LxmfFields.FIELD_REPLY_QUOTE),
            fields.keys,
        )
        assertArrayEquals(threadId, fields[LxmfFields.FIELD_THREAD] as ByteArray)
        assertArrayEquals(replyTargetHash, fields[LxmfFields.FIELD_REPLY_HASH] as ByteArray)
        assertArrayEquals(quote.toByteArray(Charsets.UTF_8), fields[LxmfFields.FIELD_REPLY_QUOTE] as ByteArray)
    }

    @Test
    fun `non-threaded message carries no 0x08 - zero wire change`() {
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(
                    replyToMessageId = rootHash.toHex(),
                    replyQuotedContent = "plain reply",
                ),
            )
        assertTrue("Main replies must not gain a thread field", LxmfFields.FIELD_THREAD !in fields)
        assertEquals(setOf(0x30, 0x31), fields.keys)
    }

    @Test
    fun `empty thread id is dropped not sent as zero-length bin`() {
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(threadId = ByteArray(0)),
            )
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `opaque reception - non-16-byte id still goes on the wire unchanged`() {
        val odd = ByteArray(32) { 0xAB.toByte() } // e.g. a future 32B convention
        val fields =
            sender.buildFields(
                NativeMessageSender.MessageOptions(threadId = odd),
            )
        assertArrayEquals(odd, fields[LxmfFields.FIELD_THREAD] as ByteArray)
    }
}
