package network.columba.app.rns.host.persistence

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.entity.PeerActivityType
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LocationTelemetry
import network.columba.app.rns.api.model.NodeType
import network.columba.app.rns.api.model.ReceivedMessage
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerActivityCollectorTest {
    @Test
    fun `collector records inbound events and rejects unverified states`() = runTest {
        val backend = mockk<RnsBackend>()
        val core = mockk<RnsCore>()
        val lxmf = mockk<RnsLxmf>()
        val telemetryApi = mockk<RnsTelemetry>()
        val transportAdmin = mockk<RnsTransportAdmin>()
        val persistence = mockk<ServicePersistenceManager>()
        val messages = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 4)
        val announces = MutableSharedFlow<AnnounceEvent>(extraBufferCapacity = 4)
        val statuses = MutableSharedFlow<DeliveryStatusUpdate>(extraBufferCapacity = 8)
        val telemetry = MutableSharedFlow<LocationTelemetry>(extraBufferCapacity = 4)
        val links = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 4)
        val reactions = MutableSharedFlow<String>(extraBufferCapacity = 4)

        every { backend.core } returns core
        every { backend.lxmf } returns lxmf
        every { backend.telemetry } returns telemetryApi
        every { backend.transportAdmin } returns transportAdmin
        every { lxmf.observeMessages() } returns messages
        every { lxmf.observeDeliveryStatus() } returns statuses
        every { core.observeAnnounces() } returns announces
        every { core.observeLinks() } returns links
        every { telemetryApi.locationTelemetryFlow } returns telemetry
        every { transportAdmin.reactionReceivedFlow } returns reactions
        coEvery { persistence.recordPeerActivity(any(), any(), any()) } returns true
        coEvery { persistence.persistIncomingMessageActivity(any(), any(), any(), any()) } returns true
        coEvery { persistence.persistReactionActivity(any(), any(), any()) } returns true
        coEvery { persistence.persistDeliveryProof(any(), any()) } returns true
        coEvery { persistence.persistDeliveryStatus(any()) } returns true
        coEvery { persistence.persistTelemetryActivity(any(), any(), any(), any()) } returns true
        coEvery {
            persistence.persistAnnounce(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns true

        val collector = PeerActivityCollector(backend, persistence) { 500L }
        val firstJob = collector.start(this)
        val secondJob = collector.start(this)
        assertSame(firstJob, secondJob)
        runCurrent()

        val source = ByteArray(16) { it.toByte() }
        messages.emit(ReceivedMessage("message", "hello", source, ByteArray(16), Long.MAX_VALUE))
        val announcePublicKey = ByteArray(32) { (it + 1).toByte() }
        val announceIdentityHash = ByteArray(16) { (it + 32).toByte() }
        announces.emit(lxmfDeliveryAnnounce(source, announceIdentityHash, announcePublicKey))
        announces.emit(lxmfDeliveryAnnounce(source, announceIdentityHash, byteArrayOf()))
        val identityHash = "originating-identity"
        statuses.emit(DeliveryStatusUpdate("delivered", DeliveryStatus.DELIVERED, Long.MAX_VALUE, identityHash))
        statuses.emit(DeliveryStatusUpdate("failed", DeliveryStatus.FAILED, Long.MAX_VALUE, identityHash))
        statuses.emit(DeliveryStatusUpdate("propagated", DeliveryStatus.PROPAGATED, Long.MAX_VALUE, identityHash))
        telemetry.emit(LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = Long.MAX_VALUE, sourceHash = "direct", isDirect = true))
        telemetry.emit(LocationTelemetry(lat = 1.0, lng = 2.0, acc = 3f, ts = Long.MAX_VALUE, sourceHash = "relayed", isDirect = false))
        reactions.emit(
            """{"reaction_to":"target","emoji":"👍","sender":"spoofed-peer","source_hash":"reaction-peer","timestamp":123}""",
        )
        reactions.emit("not-json")
        val link = mockk<Link>()
        every { link.destination.hexHash } returns "linked-peer"
        links.emit(LinkEvent.Established(link))
        links.emit(LinkEvent.Closed(link, "closed"))
        runCurrent()

        verifyInboundPersistence(persistence, source, announcePublicKey)

        firstJob.cancel()
        runCurrent()
        val restartedJob = collector.start(this)
        assertNotSame(firstJob, restartedJob)
        restartedJob.cancel()
        advanceUntilIdle()
    }

    private fun lxmfDeliveryAnnounce(
        destinationHash: ByteArray,
        identityHash: ByteArray,
        publicKey: ByteArray,
    ) = AnnounceEvent(
        destinationHash = destinationHash,
        identity = Identity(identityHash, publicKey, null),
        // Canonical LXMF delivery announce: msgpack
        // [binary display_name, nil stamp_cost, supported_functions].
        // Leave displayName null to prove service-side persistence can recover
        // the iOS name directly from app_data below the IPC seam.
        appData =
            byteArrayOf(0x93.toByte(), 0xc4.toByte(), 0x08.toByte()) +
                "iOS Name".encodeToByteArray() +
                byteArrayOf(0xc0.toByte(), 0x91.toByte(), 0x00.toByte()),
        hops = 1,
        timestamp = Long.MAX_VALUE,
        nodeType = NodeType.PEER,
        receivingInterface = "Bluetooth_LE",
        aspect = "lxmf.delivery",
        displayName = null,
        announcePacketHash = byteArrayOf(1, 2, 3, 4),
        isPathResponse = false,
    )

    private fun verifyInboundPersistence(
        persistence: ServicePersistenceManager,
        source: ByteArray,
        announcePublicKey: ByteArray,
    ) {
        val sourceHex = source.joinToString("") { "%02x".format(it) }
        coVerify(exactly = 1) {
            persistence.persistIncomingMessageActivity("message", sourceHex, null, 500L)
        }
        coVerify(exactly = 1) {
            persistence.persistAnnounce(
                destinationHash = sourceHex,
                peerName = "iOS Name",
                publicKey = announcePublicKey,
                appData = any(),
                hops = 1,
                timestamp = 500L,
                nodeType = NodeType.PEER.name,
                receivingInterface = "Bluetooth_LE",
                receivingInterfaceType = any(),
                aspect = "lxmf.delivery",
                stampCost = null,
                stampCostFlexibility = null,
                peeringCost = null,
                propagationTransferLimitKb = null,
                announcePacketHash = "01020304",
                isPathResponse = false,
            )
        }
        coVerify(exactly = 0) {
            persistence.recordPeerActivity(sourceHex, PeerActivityType.ANNOUNCE, any())
        }
        coVerify(exactly = 1) { persistence.persistDeliveryProof("delivered", 500L) }
        coVerify(exactly = 0) { persistence.persistDeliveryProof("failed", any()) }
        coVerify(exactly = 0) { persistence.persistDeliveryProof("propagated", any()) }
        coVerify(exactly = 1) {
            persistence.persistDeliveryStatus(
                DeliveryStatusUpdate("delivered", DeliveryStatus.DELIVERED, Long.MAX_VALUE, "originating-identity"),
            )
        }
        coVerify(exactly = 1) {
            persistence.persistDeliveryStatus(
                DeliveryStatusUpdate("failed", DeliveryStatus.FAILED, Long.MAX_VALUE, "originating-identity"),
            )
        }
        coVerify(exactly = 1) {
            persistence.persistDeliveryStatus(
                DeliveryStatusUpdate("propagated", DeliveryStatus.PROPAGATED, Long.MAX_VALUE, "originating-identity"),
            )
        }
        coVerify(exactly = 1) {
            persistence.persistTelemetryActivity(
                "direct",
                "direct:${Long.MAX_VALUE}:${1.0.toBits()}:${2.0.toBits()}:${3f.toRawBits()}:false",
                500L,
                true,
            )
        }
        coVerify(exactly = 1) {
            persistence.persistTelemetryActivity(
                "relayed",
                "relayed:${Long.MAX_VALUE}:${1.0.toBits()}:${2.0.toBits()}:${3f.toRawBits()}:false",
                500L,
                false,
            )
        }
        coVerify(exactly = 1) {
            persistence.persistReactionActivity("reaction-peer:target:👍:123", "reaction-peer", 500L)
        }
        coVerify(exactly = 1) {
            persistence.recordPeerActivity("linked-peer", PeerActivityType.LINK, 500L)
        }
    }
}
