package com.lxmf.messenger.reticulum.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reticulum data models.
 * Verifies correct behavior of data classes, sealed classes, and their methods.
 */
class DataModelsTest {
    @Test
    fun `Identity equality works correctly`() {
        val hash = ByteArray(16) { it.toByte() }
        val publicKey = ByteArray(32) { it.toByte() }
        val privateKey = ByteArray(32) { (it * 2).toByte() }

        val identity1 = Identity(hash, publicKey, privateKey)
        val identity2 = Identity(hash.copyOf(), publicKey.copyOf(), privateKey.copyOf())
        val identity3 = Identity(ByteArray(16) { 0 }, publicKey, privateKey)

        assertEquals(identity1, identity2)
        assertNotEquals(identity1, identity3)
    }

    @Test
    fun `Identity hashCode works correctly`() {
        val hash = ByteArray(16) { it.toByte() }
        val publicKey = ByteArray(32) { it.toByte() }
        val privateKey = ByteArray(32) { (it * 2).toByte() }

        val identity1 = Identity(hash, publicKey, privateKey)
        val identity2 = Identity(hash.copyOf(), publicKey.copyOf(), privateKey.copyOf())

        assertEquals(identity1.hashCode(), identity2.hashCode())
    }

    @Test
    fun `Destination equality works correctly`() {
        val identity = Identity(ByteArray(16), ByteArray(32), ByteArray(32))
        val hash = ByteArray(16) { it.toByte() }

        val dest1 =
            Destination(
                hash = hash,
                hexHash = "abcd1234",
                identity = identity,
                direction = Direction.OUT,
                type = DestinationType.SINGLE,
                appName = "test.app",
                aspects = listOf("aspect1"),
            )

        val dest2 =
            Destination(
                hash = hash.copyOf(),
                hexHash = "abcd1234",
                identity = identity,
                direction = Direction.OUT,
                type = DestinationType.SINGLE,
                appName = "test.app",
                aspects = listOf("aspect1"),
            )

        assertEquals(dest1, dest2)
    }

    @Test
    fun `Direction sealed class instances are singletons`() {
        val in1 = Direction.IN
        val in2 = Direction.IN
        val out1 = Direction.OUT
        val out2 = Direction.OUT

        assertSame(in1, in2)
        assertSame(out1, out2)
        assertNotSame(in1, out1)
    }

    @Test
    fun `DestinationType sealed class instances are singletons`() {
        val single1 = DestinationType.SINGLE
        val single2 = DestinationType.SINGLE
        val group1 = DestinationType.GROUP
        val plain1 = DestinationType.PLAIN

        assertSame(single1, single2)
        assertNotSame(single1, group1)
        assertNotSame(single1, plain1)
    }

    @Test
    fun `LinkStatus sealed class instances work correctly`() {
        val pending = LinkStatus.PENDING
        val active = LinkStatus.ACTIVE
        val stale = LinkStatus.STALE
        val closed = LinkStatus.CLOSED

        assertTrue(pending is LinkStatus)
        assertTrue(active is LinkStatus)
        assertTrue(stale is LinkStatus)
        assertTrue(closed is LinkStatus)
    }

    @Test
    fun `Link data class works correctly`() {
        val identity = Identity(ByteArray(16), ByteArray(32), null)
        val destination =
            Destination(
                ByteArray(16),
                "abcd",
                identity,
                Direction.OUT,
                DestinationType.SINGLE,
                "app",
                emptyList(),
            )

        val link =
            Link(
                id = "link123",
                destination = destination,
                status = LinkStatus.ACTIVE,
                establishedAt = System.currentTimeMillis(),
                rtt = 150.5f,
            )

        assertEquals("link123", link.id)
        assertEquals(LinkStatus.ACTIVE, link.status)
        assertNotNull(link.rtt)
    }

    @Test
    fun `NetworkStatus sealed class works correctly`() {
        val initializing = NetworkStatus.INITIALIZING
        val ready = NetworkStatus.READY
        val error = NetworkStatus.ERROR("Test error")
        val shutdown = NetworkStatus.SHUTDOWN

        assertTrue(initializing is NetworkStatus)
        assertTrue(ready is NetworkStatus)
        assertTrue(error is NetworkStatus)
        assertTrue(shutdown is NetworkStatus)

        assertEquals("Test error", (error as NetworkStatus.ERROR).message)
    }

    @Test
    fun `PacketReceipt equality works correctly`() {
        val hash = ByteArray(32) { it.toByte() }
        val timestamp = System.currentTimeMillis()

        val receipt1 = PacketReceipt(hash, true, timestamp)
        val receipt2 = PacketReceipt(hash.copyOf(), true, timestamp)
        val receipt3 = PacketReceipt(hash, false, timestamp)

        assertEquals(receipt1, receipt2)
        assertNotEquals(receipt1, receipt3)
    }

    @Test
    fun `ReceivedPacket equality works correctly`() {
        val data = ByteArray(128) { it.toByte() }
        val identity = Identity(ByteArray(16), ByteArray(32), null)
        val destination =
            Destination(
                ByteArray(16),
                "abcd",
                identity,
                Direction.IN,
                DestinationType.SINGLE,
                "app",
                emptyList(),
            )

        val packet1 =
            ReceivedPacket(
                data = data,
                destination = destination,
                link = null,
                timestamp = 12345L,
                rssi = -80,
                snr = 10.5f,
            )

        val packet2 =
            ReceivedPacket(
                data = data.copyOf(),
                destination = destination,
                link = null,
                timestamp = 12345L,
                rssi = -80,
                snr = 10.5f,
            )

        assertEquals(packet1, packet2)
    }

    @Test
    fun `LinkEvent sealed class works correctly`() {
        val identity = Identity(ByteArray(16), ByteArray(32), null)
        val destination =
            Destination(
                ByteArray(16),
                "abcd",
                identity,
                Direction.OUT,
                DestinationType.SINGLE,
                "app",
                emptyList(),
            )
        val link = Link("id", destination, LinkStatus.ACTIVE, 12345L, null)

        val established = LinkEvent.Established(link)
        val dataReceived = LinkEvent.DataReceived(link, ByteArray(64))
        val closed = LinkEvent.Closed(link, "Connection timeout")

        assertTrue(established is LinkEvent)
        assertTrue(dataReceived is LinkEvent)
        assertTrue(closed is LinkEvent)

        assertEquals(link, established.link)
        assertEquals("Connection timeout", (closed as LinkEvent.Closed).reason)
    }

    @Test
    fun `LinkEvent DataReceived equality works correctly`() {
        val identity = Identity(ByteArray(16), ByteArray(32), null)
        val destination =
            Destination(
                ByteArray(16),
                "abcd",
                identity,
                Direction.OUT,
                DestinationType.SINGLE,
                "app",
                emptyList(),
            )
        val link = Link("id", destination, LinkStatus.ACTIVE, 12345L, null)
        val data = ByteArray(64) { it.toByte() }

        val event1 = LinkEvent.DataReceived(link, data)
        val event2 = LinkEvent.DataReceived(link, data.copyOf())

        assertEquals(event1, event2)
    }

    @Test
    fun `AnnounceEvent equality works correctly`() {
        val destHash = ByteArray(16) { it.toByte() }
        val identity = Identity(ByteArray(16), ByteArray(32), null)
        val appData = ByteArray(128) { (it * 2).toByte() }

        val event1 =
            AnnounceEvent(
                destinationHash = destHash,
                identity = identity,
                appData = appData,
                hops = 3,
                timestamp = 12345L,
            )

        val event2 =
            AnnounceEvent(
                destinationHash = destHash.copyOf(),
                identity = identity,
                appData = appData.copyOf(),
                hops = 3,
                timestamp = 12345L,
            )

        assertEquals(event1, event2)
    }

    @Test
    fun `ReticulumConfig with different interfaces`() {
        val config =
            ReticulumConfig(
                storagePath = "/tmp/reticulum",
                enabledInterfaces =
                    listOf(
                        InterfaceConfig.TCPClient(targetHost = "192.168.1.1", targetPort = 4242),
                        InterfaceConfig.UDP(listenPort = 4243),
                        InterfaceConfig.RNode(targetDeviceName = "RNode 1234"),
                        InterfaceConfig.AutoInterface(),
                    ),
                logLevel = LogLevel.DEBUG,
                allowAnonymous = true,
            )

        assertEquals("/tmp/reticulum", config.storagePath)
        assertEquals(4, config.enabledInterfaces.size)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        assertTrue(config.allowAnonymous)

        val tcpInterface = config.enabledInterfaces[0] as InterfaceConfig.TCPClient
        assertEquals(4242, tcpInterface.targetPort)
    }

    @Test
    fun `LogLevel enum values`() {
        val levels =
            listOf(
                LogLevel.CRITICAL,
                LogLevel.ERROR,
                LogLevel.WARNING,
                LogLevel.INFO,
                LogLevel.DEBUG,
                LogLevel.VERBOSE,
            )

        assertEquals(6, levels.size)
        assertTrue(LogLevel.INFO in levels)
    }

    @Test
    fun `ReticulumError types work correctly`() {
        val networkError = ReticulumError.NetworkError("Connection failed")
        val timeoutError = ReticulumError.TimeoutError("sendPacket")
        val invalidDest = ReticulumError.InvalidDestination("abcd1234")
        val encryptionError = ReticulumError.EncryptionError("Key exchange failed")
        val serializationError = ReticulumError.SerializationError("Invalid JSON")

        assertTrue(networkError is ReticulumError)
        assertTrue(timeoutError is ReticulumError)
        assertTrue(invalidDest is ReticulumError)
        assertTrue(encryptionError is ReticulumError)
        assertTrue(serializationError is ReticulumError)

        assertEquals("Connection failed", networkError.message)
        assertEquals("Operation timed out: sendPacket", timeoutError.message)
        assertEquals("Invalid destination: abcd1234", invalidDest.message)
    }

    @Test
    fun `PacketType sealed class instances`() {
        val data = PacketType.DATA
        val announce = PacketType.ANNOUNCE
        val linkRequest = PacketType.LINKREQUEST
        val proof = PacketType.PROOF

        assertTrue(data is PacketType)
        assertTrue(announce is PacketType)
        assertTrue(linkRequest is PacketType)
        assertTrue(proof is PacketType)
    }
}
