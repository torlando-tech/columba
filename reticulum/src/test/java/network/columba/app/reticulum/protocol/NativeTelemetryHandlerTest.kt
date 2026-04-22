package network.columba.app.reticulum.protocol

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies that NativeTelemetryHandler.handleIncomingTelemetry treats
 * image / file / audio attachments as user-visible chat content, even
 * when the text content is empty and telemetry fields are present.
 *
 * Before the fix this was covering, Sideband's habit of auto-attaching
 * telemetry (FIELD_TELEMETRY_STREAM and/or FIELD_TELEMETRY with a
 * location subrecord) to every outbound message caused image-only
 * messages ("hasTextContent = false, has location telemetry") to be
 * classified as `isLocationOnlyMessage` and dropped silently — users
 * saw nothing in chat. This regression test pins the expected
 * behavior: an image / file / audio attachment alone is enough to
 * surface the message.
 */
class NativeTelemetryHandlerTest {
    private lateinit var handler: NativeTelemetryHandler

    @Before
    fun setup() {
        handler =
            NativeTelemetryHandler(
                scopeProvider = { CoroutineScope(Dispatchers.Unconfined) },
                locationTelemetryFlow = MutableSharedFlow(extraBufferCapacity = 16),
                deliveryIdentityProvider = { null },
                sendMessageFn = { _, _, _, _ -> },
                storedTelemetry = ConcurrentHashMap(),
                telemetryCollectorEnabledProvider = { false },
                telemetryAllowedRequestersProvider = { emptySet() },
            )
    }

    private fun mockMessage(
        content: String = "",
        fields: Map<Int, Any> = emptyMap(),
    ): LXMessage {
        // Strict mock (no relaxed = true, per NoRelaxedMocks detekt rule):
        // handleIncomingTelemetry only reads these three fields off the
        // message, so stub exactly those and nothing more.
        val message = mockk<LXMessage>()
        every { message.content } returns content
        every { message.fields } returns fields.toMutableMap()
        every { message.sourceHash } returns ByteArray(16) { it.toByte() }
        return message
    }

    // ----- Baseline: fields with telemetry and no other content are
    //       still correctly classified as location-only. -----

    @Test
    fun `empty content plus telemetry_stream field is location-only`() {
        val message =
            mockMessage(
                content = "",
                fields = mapOf(LXMFConstants.FIELD_TELEMETRY_STREAM to emptyList<Any>()),
            )
        assertTrue(handler.handleIncomingTelemetry(message, timestamp = 0L))
    }

    @Test
    fun `text content plus telemetry_stream is not location-only`() {
        val message =
            mockMessage(
                content = "check this out",
                fields = mapOf(LXMFConstants.FIELD_TELEMETRY_STREAM to emptyList<Any>()),
            )
        assertFalse(handler.handleIncomingTelemetry(message, timestamp = 0L))
    }

    // ----- Regression: attachments alongside telemetry must count
    //       as chat content and cause isLocationOnly = false. -----

    @Test
    fun `image attachment alone with telemetry_stream is not location-only`() {
        val message =
            mockMessage(
                content = "",
                fields =
                    mapOf(
                        LXMFConstants.FIELD_TELEMETRY_STREAM to emptyList<Any>(),
                        LXMFConstants.FIELD_IMAGE to listOf("webp", ByteArray(100)),
                    ),
            )
        assertFalse(
            "Image-only message (empty text, telemetry present) was classified " +
                "as location-only and would be dropped from the chat UI",
            handler.handleIncomingTelemetry(message, timestamp = 0L),
        )
    }

    @Test
    fun `file attachment alone with telemetry_stream is not location-only`() {
        val message =
            mockMessage(
                content = "",
                fields =
                    mapOf(
                        LXMFConstants.FIELD_TELEMETRY_STREAM to emptyList<Any>(),
                        LXMFConstants.FIELD_FILE_ATTACHMENTS to listOf<List<ByteArray>>(),
                    ),
            )
        assertFalse(handler.handleIncomingTelemetry(message, timestamp = 0L))
    }

    @Test
    fun `audio attachment alone with telemetry_stream is not location-only`() {
        val message =
            mockMessage(
                content = "",
                fields =
                    mapOf(
                        LXMFConstants.FIELD_TELEMETRY_STREAM to emptyList<Any>(),
                        LXMFConstants.FIELD_AUDIO to listOf(0, ByteArray(0)),
                    ),
            )
        assertFalse(handler.handleIncomingTelemetry(message, timestamp = 0L))
    }

    @Test
    fun `image attachment with FIELD_TELEMETRY (location) is not location-only`() {
        // A FIELD_TELEMETRY entry carrying a location record is the other
        // classification path that can flip isLocationOnlyMessage to true;
        // make sure it also respects attachment content.
        //
        // `unpackLocationTelemetryField` accepts either msgpack bytes or a
        // JSON string. We use the JSON-string branch so this test exercises
        // a *real* location-payload path — a short arbitrary ByteArray would
        // fail to parse as msgpack, leaving locationEvent = null and the
        // assertFalse passing even without the fix (i.e. not actually pinning
        // the guarded behavior).
        val locationJson = """{"latitude":37.7749,"longitude":-122.4194,"altitude":15.0}"""
        val message =
            mockMessage(
                content = "",
                fields =
                    mapOf(
                        LXMFConstants.FIELD_TELEMETRY to locationJson,
                        LXMFConstants.FIELD_IMAGE to listOf("webp", ByteArray(100)),
                    ),
            )
        assertFalse(
            "Message with image + telemetry (location payload) was dropped",
            handler.handleIncomingTelemetry(message, timestamp = 0L),
        )
    }

    @Test
    fun `empty content plus FIELD_TELEMETRY location alone is location-only`() {
        // Companion to the test above: prove the FIELD_TELEMETRY path
        // DOES flip isLocationOnly in the absence of attachments. Catches
        // regressions where a future refactor leaves the location branch
        // permanently unreachable.
        val locationJson = """{"latitude":37.7749,"longitude":-122.4194}"""
        val message =
            mockMessage(
                content = "",
                fields = mapOf(LXMFConstants.FIELD_TELEMETRY to locationJson),
            )
        assertTrue(
            "Location-only message (FIELD_TELEMETRY with location, no text, no attachment) " +
                "was not classified as location-only — the classification path is broken",
            handler.handleIncomingTelemetry(message, timestamp = 0L),
        )
    }
}
