package com.lxmf.messenger.reticulum.protocol

import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.LinkStatus
import com.lxmf.messenger.reticulum.model.LogLevel
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MockReticulumProtocol.
 * These tests verify the mock implementation behaves correctly for development/testing.
 */
class MockReticulumProtocolTest {
    private lateinit var protocol: MockReticulumProtocol

    @Before
    fun setup() {
        protocol = MockReticulumProtocol()
    }

    @Test
    fun `networkStatus returns SHUTDOWN initially`() {
        val status = protocol.networkStatus.value
        assertEquals(NetworkStatus.SHUTDOWN, status)
    }

    @Test
    fun `initialize changes status to READY`() =
        runTest {
            val config =
                ReticulumConfig(
                    storagePath = "/tmp/test",
                    enabledInterfaces = listOf(InterfaceConfig.AutoInterface()),
                    logLevel = LogLevel.INFO,
                )

            val result = protocol.initialize(config)
            assertTrue(result.isSuccess)
            assertEquals(NetworkStatus.READY, protocol.networkStatus.value)
        }

    @Test
    fun `shutdown changes status to SHUTDOWN`() =
        runTest {
            val config =
                ReticulumConfig(
                    storagePath = "/tmp/test",
                    enabledInterfaces = listOf(InterfaceConfig.AutoInterface()),
                )
            protocol.initialize(config)

            val result = protocol.shutdown()
            assertTrue(result.isSuccess)
            assertEquals(NetworkStatus.SHUTDOWN, protocol.networkStatus.value)
        }

    @Test
    fun `createIdentity returns valid identity`() =
        runTest {
            val result = protocol.createIdentity()

            assertTrue(result.isSuccess)
            val identity = result.getOrThrow()
            assertEquals(16, identity.hash.size) // Reticulum uses 16-byte truncated hash
            assertEquals(32, identity.publicKey.size)
            assertEquals(32, identity.privateKey?.size)
        }

    @Test
    fun `createIdentity generates unique identities`() =
        runTest {
            val identity1 = protocol.createIdentity().getOrThrow()
            val identity2 = protocol.createIdentity().getOrThrow()

            assertFalse(identity1.hash.contentEquals(identity2.hash))
            assertFalse(identity1.publicKey.contentEquals(identity2.publicKey))
        }

    @Test
    fun `createDestination returns valid destination`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()

            val result =
                protocol.createDestination(
                    identity = identity,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "test.app",
                    aspects = listOf("aspect1", "aspect2"),
                )

            assertTrue(result.isSuccess)
            val destination = result.getOrThrow()
            assertEquals(16, destination.hash.size)
            assertFalse(destination.hexHash.isEmpty())
            assertEquals(identity, destination.identity)
            assertEquals(Direction.OUT, destination.direction)
            assertEquals(DestinationType.SINGLE, destination.type)
            assertEquals("test.app", destination.appName)
            assertEquals(listOf("aspect1", "aspect2"), destination.aspects)
        }

    @Test
    fun `announceDestination succeeds`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            val destination =
                protocol.createDestination(
                    identity = identity,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "test.app",
                    aspects = emptyList(),
                ).getOrThrow()

            val result = protocol.announceDestination(destination)
            assertTrue(result.isSuccess)
        }

    @Test
    fun `sendPacket returns receipt with delivered status`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            val destination =
                protocol.createDestination(
                    identity = identity,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "test.app",
                    aspects = emptyList(),
                ).getOrThrow()

            val testData = "Hello, Reticulum!".toByteArray()
            val result = protocol.sendPacket(destination, testData)

            assertTrue(result.isSuccess)
            val receipt = result.getOrThrow()
            assertEquals(32, receipt.hash.size)
            assertTrue(receipt.delivered)
            assertTrue(receipt.timestamp > 0)
        }

    @Test
    fun `establishLink returns active link`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            val destination =
                protocol.createDestination(
                    identity = identity,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "test.app",
                    aspects = emptyList(),
                ).getOrThrow()

            val result = protocol.establishLink(destination)

            assertTrue(result.isSuccess)
            val link = result.getOrThrow()
            assertFalse(link.id.isEmpty())
            assertEquals(destination, link.destination)
            assertEquals(LinkStatus.ACTIVE, link.status)
            assertTrue(link.establishedAt > 0)
            assertNotNull(link.rtt)
        }

    @Test
    fun `closeLink succeeds`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            val destination =
                protocol.createDestination(
                    identity = identity,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "test.app",
                    aspects = emptyList(),
                ).getOrThrow()
            val link = protocol.establishLink(destination).getOrThrow()

            val result = protocol.closeLink(link)
            assertTrue(result.isSuccess)
        }

    @Test
    fun `hasPath returns true`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            assertTrue(protocol.hasPath(identity.hash))
        }

    @Test
    fun `requestPath succeeds`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            val result = protocol.requestPath(identity.hash)
            assertTrue(result.isSuccess)
        }

    @Test
    fun `getHopCount returns valid hop count`() {
        val hash = ByteArray(16)
        val hopCount = protocol.getHopCount(hash)
        assertNotNull(hopCount)
        assertTrue(hopCount!! >= 0)
    }

    @Test
    fun `saveIdentity succeeds`() =
        runTest {
            val identity = protocol.createIdentity().getOrThrow()
            val result = protocol.saveIdentity(identity, "/tmp/test_identity")
            assertTrue(result.isSuccess)
        }

    @Test
    fun `recallIdentity returns null in mock`() =
        runTest {
            val hash = ByteArray(16)
            val identity = protocol.recallIdentity(hash)
            assertNull(identity)
        }

    @Test
    fun `different interface configs can be created`() {
        val tcpConfig = InterfaceConfig.TCPClient(targetHost = "192.168.1.1", targetPort = 4242)
        val udpConfig = InterfaceConfig.UDP(listenPort = 4242)
        val rnodeConfig = InterfaceConfig.RNode(port = "/dev/ttyUSB0")
        val autoConfig = InterfaceConfig.AutoInterface()

        assertTrue(tcpConfig is InterfaceConfig.TCPClient)
        assertTrue(udpConfig is InterfaceConfig.UDP)
        assertTrue(rnodeConfig is InterfaceConfig.RNode)
        assertTrue(autoConfig is InterfaceConfig.AutoInterface)
    }
}
