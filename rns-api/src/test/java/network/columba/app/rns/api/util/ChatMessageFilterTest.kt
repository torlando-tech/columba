package network.columba.app.rns.api.util

import android.app.Application
import network.columba.app.rns.api.model.ReceivedMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [isUserVisibleChatMessage] — the shared predicate that
 * decides whether an inbound LXMessage should render as a chat bubble.
 *
 * The predicate is the single source of truth for both backends
 * (`NativeRnsBackendImpl` and `PythonEventBridge`); a regression
 * here regresses both at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatMessageFilterTest {
    private fun received(
        content: String = "",
        fieldsJson: String? = null,
    ) = ReceivedMessage(
        messageHash = "test_hash",
        content = content,
        sourceHash = ByteArray(16) { 0xAA.toByte() },
        destinationHash = ByteArray(16) { 0xBB.toByte() },
        timestamp = 0L,
        fieldsJson = fieldsJson,
    )

    @Test
    fun `text content surfaces a chat bubble`() {
        assertTrue(received(content = "hello").isUserVisibleChatMessage())
    }

    @Test
    fun `whitespace-only content does not surface a bubble`() {
        // Distinguishes "user sent a space" from "side-channel with no
        // text" — the latter is what telemetry-only sends look like.
        assertFalse(received(content = "   ").isUserVisibleChatMessage())
    }

    @Test
    fun `image-only message surfaces a chat bubble`() {
        assertTrue(
            received(
                content = "",
                fieldsJson = """{"6": ["png", "base64data"]}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `file-attachment-only message surfaces a chat bubble`() {
        assertTrue(
            received(
                content = "",
                fieldsJson = """{"5": [["file.pdf", "data"]]}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `audio-only message surfaces a chat bubble`() {
        assertTrue(
            received(
                content = "",
                fieldsJson = """{"7": [0, "audiodata"]}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `telemetry-only frame does not surface a bubble`() {
        // The previously-broken case: empty content + FIELD_TELEMETRY
        // landed as an empty bubble on the Python backend.
        assertFalse(
            received(
                content = "",
                fieldsJson = """{"2": "deadbeef"}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `telemetry-stream-only frame does not surface a bubble`() {
        assertFalse(
            received(
                content = "",
                fieldsJson = """{"3": [["timestamp", "data"]]}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `reaction-only frame does not surface a bubble`() {
        // Reactions ride on fields[16] with empty content.
        // `routeReactionSideChannel` fires the dedicated flow; this
        // predicate keeps them out of the chat bubble stream.
        assertFalse(
            received(
                content = "",
                fieldsJson = """{"16": {"reaction_to": "abc", "emoji": "👍", "sender": "deadbeef"}}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `icon-appearance-only frame does not surface a bubble`() {
        // Sideband attaches icon appearance (field 4) to many frames;
        // a message that's ONLY an icon update shouldn't render.
        assertFalse(
            received(
                content = "",
                fieldsJson = """{"4": ["robot", "ffffff", "000000"]}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `text plus telemetry surfaces (mixed payload preferred)`() {
        // A user message that ALSO carries telemetry should render.
        // Sideband habitually piggybacks telemetry on outbound text;
        // we never want to drop those.
        assertTrue(
            received(
                content = "hey, my location",
                fieldsJson = """{"2": "deadbeef"}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `image plus telemetry surfaces`() {
        assertTrue(
            received(
                content = "",
                fieldsJson = """{"2": "deadbeef", "6": ["png", "imgdata"]}""",
            ).isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `empty content with null fieldsJson does not surface`() {
        assertFalse(received(content = "", fieldsJson = null).isUserVisibleChatMessage())
    }

    @Test
    fun `empty content with empty fieldsJson string does not surface`() {
        assertFalse(received(content = "", fieldsJson = "").isUserVisibleChatMessage())
    }

    @Test
    fun `malformed fieldsJson with empty content does not surface`() {
        // A parse failure with no text content falls through to
        // "drop the message" — safer than rendering an empty bubble.
        assertFalse(
            received(content = "", fieldsJson = "not json {{").isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `malformed fieldsJson with text content still surfaces`() {
        // Text content is checked first, before any JSON parsing —
        // a broken fields blob doesn't cost the user their message.
        assertTrue(
            received(content = "hello", fieldsJson = "not json {{").isUserVisibleChatMessage(),
        )
    }

    @Test
    fun `reply with text surfaces (text wins, reply fields are inline metadata)`() {
        // Replies are normal text messages that ALSO carry reply
        // metadata in fields[0x30] / fields[0x31]. They should
        // render normally — the text drives the predicate.
        assertTrue(
            received(
                content = "I agree",
                fieldsJson = """{"48": "abcd1234", "49": "48656c6c6f"}""",
            ).isUserVisibleChatMessage(),
        )
    }
}
