package network.columba.app.rns.backend.kt

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.LXMRouter
import network.reticulum.packet.Packet
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.PathEntry
import network.reticulum.transport.PathState
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GitHub #752: "Sync Timeout: No path to relay" on a freshly started app.
 *
 * The kotlin backend syncs through LXMF-kt. Up to LXMF-kt v0.0.14, a sync whose
 * relay path was not known yet went straight to `establishPropagationLink`: the
 * link request had no route, the sync stalled until the caller's timeout, and
 * the relay sync reported NO_PATH until the user tried again. LXMF-kt v0.0.15
 * mirrors python (`LXMRouter.py:539-545`): request the relay path, expose
 * `PATH_REQUESTED`, and let the path-wait job re-enter the sync when the announce
 * lands.
 *
 * This test pins that behavior at the JVM stack the app actually ships, so a
 * dependency downgrade cannot silently reintroduce the bug.
 */
class PropagationPathRequestTest {
    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAA.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    private lateinit var iface: CapturingInterface
    private lateinit var router: LXMRouter
    private lateinit var relayHash: ByteArray

    private val pathRequestDestHash: ByteArray =
        Destination.create(
            identity = null,
            direction = DestinationDirection.IN,
            type = DestinationType.PLAIN,
            appName = "rnstransport",
            aspects = arrayOf("path", "request"),
        ).hash

    @Before
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)
        iface = CapturingInterface(name = "capture-${System.nanoTime()}")
        Transport.registerInterface(iface)

        router = LXMRouter(identity = Identity.create())
        val relayIdentity = Identity.create()
        relayHash =
            Destination.create(
                identity = relayIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                aspects = arrayOf("propagation"),
            ).hash
        router.addPropagationNode(
            LXMRouter.PropagationNode(destHash = relayHash, identity = relayIdentity, isActive = true),
        )
        router.setActivePropagationNode(relayHash.joinToString("") { "%02x".format(it) })
    }

    @After
    fun teardown() {
        router.close()
        try {
            Transport.deregisterInterface(iface)
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    @Test
    fun `sync with no relay path requests the path instead of stalling the link`() {
        assertFalse("the relay must have no path for this test", Transport.hasPath(relayHash))

        router.requestMessagesFromPropagationNode()

        assertEquals(
            "sync must wait for the relay path (LXMF-kt v0.0.15 parity with python)",
            LXMRouter.PropagationTransferState.PATH_REQUESTED,
            router.propagationTransferState,
        )
        assertEquals("exactly one path request for the relay", 1, pathRequestCountFor(relayHash))
        assertEquals("no hopeful link request without a route", 0, linkRequestCount())
    }


    @Test
    fun `the relay announce resumes the sync with a link request`() {
        assertFalse("the relay must have no path for this test", Transport.hasPath(relayHash))
        router.requestMessagesFromPropagationNode()
        assertEquals(
            "sync must wait for the relay path",
            LXMRouter.PropagationTransferState.PATH_REQUESTED,
            router.propagationTransferState,
        )

        // The relay's announce lands: Transport now has a route through our interface.
        installRelayPath()

        // The path-wait job polls, re-enters the sync and sends the link request.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && linkRequestCount() == 0) {
            Thread.sleep(50)
        }

        assertTrue("the resumed sync must send a link request", linkRequestCount() > 0)
        assertTrue(
            "the sync must leave PATH_REQUESTED once the relay path exists, was ${router.propagationTransferState}",
            router.propagationTransferState != LXMRouter.PropagationTransferState.PATH_REQUESTED,
        )
    }

    /** Install an ACTIVE route to the relay through the capturing interface. */
    private fun installRelayPath() {
        val now = System.currentTimeMillis()
        Transport.pathTable[relayHash.toKey()] =
            PathEntry(
                timestamp = now,
                nextHop = relayHash,
                hops = 1,
                expires = now + 60_000,
                randomBlobs = mutableListOf(),
                receivingInterfaceHash = iface.hash,
                announcePacketHash = ByteArray(16) { 0x11 },
                state = PathState.ACTIVE,
                failureCount = 0,
            )
    }

    /** Distinct `Transport.requestPath` calls (by request tag) aimed at [destHash]. */
    private fun pathRequestCountFor(destHash: ByteArray): Int =
        iface.sent
            .mapNotNull { wire ->
                val pkt = Packet.unpack(wire) ?: return@mapNotNull null
                val isPathReqForDest =
                    pkt.destinationHash.contentEquals(pathRequestDestHash) &&
                        pkt.data.size >= 2 * RnsConstants.TRUNCATED_HASH_BYTES &&
                        pkt.data.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES).contentEquals(destHash)
                if (!isPathReqForDest) {
                    null
                } else {
                    pkt.data.copyOfRange(pkt.data.size - RnsConstants.TRUNCATED_HASH_BYTES, pkt.data.size)
                        .joinToString("") { "%02x".format(it) }
                }
            }.toSet()
            .size

    private fun linkRequestCount(): Int =
        iface.sent.count { wire -> Packet.unpack(wire)?.packetType == PacketType.LINKREQUEST }
}
