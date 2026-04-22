package network.columba.app.reticulum.call.telephone

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.local.LocalServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.link.Link
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Instrumented tests for NativeNetworkTransport.
 *
 * Tests the native Kotlin transport layer for LXST voice calls on a real
 * Android device. Verifies:
 * - Link establishment to a local destination
 * - Signal sending/receiving (single-byte packets)
 * - Audio data sending/receiving (multi-byte packets)
 * - Bidirectional data flow
 * - Link teardown
 * - Callback dispatching (signal vs audio)
 *
 * These tests create a local loopback: NativeNetworkTransport → Link → local
 * Destination. This verifies the transport works without requiring a remote peer.
 */
@RunWith(AndroidJUnit4::class)
class NativeNetworkTransportInstrumentedTest {
    private lateinit var transport: NativeNetworkTransport
    private lateinit var responderIdentity: Identity
    private lateinit var responderDest: Destination
    private val tcpPort = 37500 + (System.currentTimeMillis() % 1000).toInt()

    // Local TCP interfaces for loopback
    private var server: LocalServerInterface? = null
    private var client: LocalClientInterface? = null

    // Track what the responder link receives
    private val responderReceived = CopyOnWriteArrayList<ByteArray>()
    private var responderLink: Link? = null

    @Before
    fun setup() {
        // Start Transport (no Reticulum.start needed for basic link tests)
        Transport.stop()
        Thread.sleep(100)
        Transport.start(enableTransport = false)

        // Create local TCP loopback interfaces (same pattern as TwoNodeTestHarness)
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.onPacketReceived = { data, fromInterface ->
            Transport.clearPacketHashlist()
            Transport.inbound(data, fromInterface.toRef())
        }
        server!!.start()
        Transport.registerInterface(server!!.toRef())

        client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
        client!!.onPacketReceived = { data, fromInterface ->
            Transport.clearPacketHashlist()
            Transport.inbound(data, fromInterface.toRef())
        }
        client!!.start()
        Transport.registerInterface(client!!.toRef())

        Thread.sleep(200) // Let TCP connect

        // Create a local LXST destination (simulates remote peer)
        responderIdentity = Identity.create()
        responderDest =
            Destination.create(
                identity = responderIdentity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "lxst",
                "telephony",
            )
        responderDest.acceptLinkRequests = true

        // Set up link established callback on responder
        responderDest.setLinkEstablishedCallback { linkAny ->
            val link = linkAny as Link
            responderLink = link
            link.setPacketCallback { data, _ ->
                responderReceived.add(data)
            }
        }

        Transport.registerDestination(responderDest)

        // Register the responder identity so NativeNetworkTransport can recall it
        Identity.remember(
            packetHash = responderDest.hash,
            destHash = responderDest.hash,
            publicKey = responderIdentity.getPublicKey(),
        )

        transport = NativeNetworkTransport()
    }

    @After
    fun teardown() {
        transport.teardownLink()
        responderLink?.teardown()
        server?.detach()
        client?.detach()
        Transport.stop()
        Thread.sleep(100)
    }

    @Test
    fun isLinkActive_initiallyFalse() {
        assertFalse("Link should not be active initially", transport.isLinkActive)
    }

    @Test
    fun establishLink_toLocalDestination_succeeds() =
        runBlocking {
            val result = transport.establishLink(responderDest.hash)
            assertTrue("Link should establish to local destination", result)
            assertTrue("Link should be active after establishment", transport.isLinkActive)
        }

    @Test
    fun sendSignal_singleByteDelivered() =
        runBlocking {
            transport.establishLink(responderDest.hash)
            assertTrue("Link should be active", transport.isLinkActive)

            // Wait for responder link to be set up
            val deadline = System.currentTimeMillis() + 5000
            while (responderLink == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertNotNull("Responder should have accepted link", responderLink)

            // Send LXST signalling — wrapped in msgpack {0: [signal]} for Python interop
            transport.sendSignal(0x02) // STATUS_CALLING
            transport.sendSignal(0x06) // STATUS_ESTABLISHED

            Thread.sleep(500)

            assertTrue("Responder should receive signals", responderReceived.size >= 2)
            assertEquals(
                "First signal should be CALLING",
                0x02,
                unpackSignalling(responderReceived[0]),
            )
            assertEquals(
                "Second signal should be ESTABLISHED",
                0x06,
                unpackSignalling(responderReceived[1]),
            )
        }

    @Test
    fun sendPacket_multiBytAudioFrame_delivered() =
        runBlocking {
            transport.establishLink(responderDest.hash)
            assertTrue("Link should be active", transport.isLinkActive)

            val deadline = System.currentTimeMillis() + 5000
            while (responderLink == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertNotNull("Responder should have accepted link", responderLink)

            // Send audio frame (Opus: codec byte + data) — wrapped in msgpack {1: binary}
            val audioFrame = ByteArray(40) { it.toByte() }
            audioFrame[0] = 0x01 // Opus codec identifier
            transport.sendPacket(audioFrame)

            Thread.sleep(500)

            assertTrue("Responder should receive audio frame", responderReceived.isNotEmpty())
            val unpacked = unpackFrames(responderReceived[0])
            assertEquals("Frame size should match", 40, unpacked.size)
            assertTrue("Frame content should match", audioFrame.contentEquals(unpacked))
        }

    private fun unpackSignalling(bytes: ByteArray): Int {
        val unpacker =
            org.msgpack.core.MessagePack
                .newDefaultUnpacker(bytes)
        val mapSize = unpacker.unpackMapHeader()
        require(mapSize == 1) { "Expected single-entry map, got $mapSize" }
        val fieldId = unpacker.unpackInt()
        require(fieldId == 0x00) { "Expected FIELD_SIGNALLING(0x00), got $fieldId" }
        val arraySize = unpacker.unpackArrayHeader()
        require(arraySize == 1) { "Expected 1-element signal array, got $arraySize" }
        return unpacker.unpackInt().also { unpacker.close() }
    }

    private fun unpackFrames(bytes: ByteArray): ByteArray {
        val unpacker =
            org.msgpack.core.MessagePack
                .newDefaultUnpacker(bytes)
        val mapSize = unpacker.unpackMapHeader()
        require(mapSize == 1) { "Expected single-entry map, got $mapSize" }
        val fieldId = unpacker.unpackInt()
        require(fieldId == 0x01) { "Expected FIELD_FRAMES(0x01), got $fieldId" }
        val payloadLen = unpacker.unpackBinaryHeader()
        return unpacker.readPayload(payloadLen).also { unpacker.close() }
    }

    @Test
    fun callbackDispatching_distinguishesSignalsFromAudio() =
        runBlocking {
            val receivedSignals = CopyOnWriteArrayList<Int>()
            val receivedAudio = CopyOnWriteArrayList<ByteArray>()

            transport.setSignalCallback { signal -> receivedSignals.add(signal) }
            transport.setPacketCallback { data -> receivedAudio.add(data) }

            transport.establishLink(responderDest.hash)

            val deadline = System.currentTimeMillis() + 5000
            while (responderLink == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertNotNull("Responder should have accepted link", responderLink)

            // Responder sends signal and audio back to initiator
            responderLink!!.send(byteArrayOf(0x04)) // STATUS_RINGING (1 byte = signal)
            responderLink!!.send(byteArrayOf(0x01, 0x02, 0x03, 0x04)) // Audio frame (>1 byte)

            Thread.sleep(1000)

            assertEquals("Should receive 1 signal", 1, receivedSignals.size)
            assertEquals("Signal should be RINGING", 0x04, receivedSignals[0])

            assertEquals("Should receive 1 audio frame", 1, receivedAudio.size)
            assertEquals("Audio frame should be 4 bytes", 4, receivedAudio[0].size)
        }

    @Test
    fun teardownLink_makesLinkInactive() =
        runBlocking {
            transport.establishLink(responderDest.hash)
            assertTrue("Link should be active", transport.isLinkActive)

            transport.teardownLink()

            // Link becomes inactive after teardown
            Thread.sleep(200)
            assertFalse("Link should be inactive after teardown", transport.isLinkActive)
        }

    @Test
    fun sendPacket_whenLinkInactive_doesNotCrash() {
        // Should be a no-op, not crash
        assertFalse(transport.isLinkActive)
        transport.sendPacket(ByteArray(40))
        transport.sendSignal(0x02)
        // No exception = pass
    }

    @Test
    fun teardownLink_whenNoLink_doesNotCrash() {
        // Should be safe to call without active link
        transport.teardownLink()
        transport.teardownLink() // Double teardown
        // No exception = pass
    }
}
