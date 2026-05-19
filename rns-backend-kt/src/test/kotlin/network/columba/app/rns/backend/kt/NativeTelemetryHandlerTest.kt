package network.columba.app.rns.backend.kt

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import network.columba.app.rns.api.model.LocationTelemetry
import network.columba.app.rns.api.util.LxmfFields
import network.reticulum.lxmf.LXMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies NativeTelemetryHandler.handleIncomingTelemetry correctly
 * routes inbound location telemetry to `locationTelemetryFlow`.
 *
 * The chat-emit gate that used to also live here was lifted to
 * [network.columba.app.rns.api.util.isUserVisibleChatMessage] in
 * :rns-api so both backends share one impl — those tests live in
 * `ChatMessageFilterTest`. This file is now scoped to the routing
 * responsibility (does telemetry actually surface on the flow when
 * the LXMessage carries it?).
 */
class NativeTelemetryHandlerTest {
    private lateinit var handler: NativeTelemetryHandler
    private lateinit var locationFlow: MutableSharedFlow<LocationTelemetry>

    @Before
    fun setup() {
        locationFlow = MutableSharedFlow(replay = 4, extraBufferCapacity = 16)
        handler =
            NativeTelemetryHandler(
                scopeProvider = { CoroutineScope(Dispatchers.Unconfined) },
                locationTelemetryFlow = locationFlow,
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
        val message = mockk<LXMessage>()
        every { message.content } returns content
        every { message.fields } returns fields.toMutableMap()
        every { message.sourceHash } returns ByteArray(16) { it.toByte() }
        return message
    }

    private fun pollFlowNonBlocking(timeoutMillis: Long = 250): LocationTelemetry? =
        runBlocking { withTimeoutOrNull(timeoutMillis) { locationFlow.first() } }

    @Test
    fun `FIELD_TELEMETRY with location JSON emits to flow`() {
        // FIELD_TELEMETRY as a JSON string uses `lat`/`lng` keys
        // (matching the working-buffer shape produced by the
        // msgpack-decode path in `unpackLocationFromMsgpack`). The
        // Telemeter-canonical `latitude`/`longitude` form only
        // round-trips through the msgpack decoder.
        val locationJson = """{"lat":37.7749,"lng":-122.4194,"altitude":15.0}"""
        val message =
            mockMessage(
                content = "",
                fields = mapOf(LxmfFields.FIELD_TELEMETRY to locationJson),
            )

        handler.handleIncomingTelemetry(message, timestamp = 0L)

        val emitted = pollFlowNonBlocking()
        assertNotNull(
            "FIELD_TELEMETRY with a location subrecord should fire " +
                "locationTelemetryFlow — the map UI / location share " +
                "machinery depends on it.",
            emitted,
        )
        assertEquals(37.7749, emitted!!.lat, 0.0001)
        assertEquals(-122.4194, emitted.lng, 0.0001)
    }

    @Test
    fun `FIELD_TELEMETRY with text content alongside still routes telemetry`() {
        // Sideband habitually piggybacks telemetry on text messages.
        // The flow should still fire — only the chat-bubble decision
        // is separate (handled by isUserVisibleChatMessage, which
        // returns true here because content is non-blank).
        val locationJson = """{"lat":40.0,"lng":-74.0}"""
        val message =
            mockMessage(
                content = "hey, my location",
                fields = mapOf(LxmfFields.FIELD_TELEMETRY to locationJson),
            )

        handler.handleIncomingTelemetry(message, timestamp = 0L)

        assertNotNull(pollFlowNonBlocking())
    }

    @Test
    fun `message without any telemetry fields does not fire flow`() {
        val message = mockMessage(content = "plain text", fields = emptyMap())

        handler.handleIncomingTelemetry(message, timestamp = 0L)

        assertNull(
            "Plain-text message with no telemetry fields must not produce " +
                "a phantom location event.",
            pollFlowNonBlocking(timeoutMillis = 150),
        )
    }
}
