package network.columba.app.rns.ipc

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.ConversationLinkResult
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import network.columba.app.rns.api.model.LinkStatus
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.NomadnetPageResult
import network.columba.app.rns.api.model.PacketReceipt
import network.columba.app.rns.api.model.PacketType
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.ReceivedPacket
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.api.model.VoiceCallState
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end round-trip tests through the AIDL Stub for the `:rns-ipc`
 * client/server adapter pair. Uses an in-process [RnsBackendServer] stub
 * wrapping a controllable fake [RnsBackend], paired with a
 * [RnsBackendClient] consuming it via `IRnsBackend.Stub` directly. No real
 * binder / serialization happens — but the AIDL-generated callback
 * dispatch, Bundle marshalling, and Parcel CREATOR resolution all do, which
 * is the part of the wire the round-trip exercises.
 *
 * Runs under Robolectric so framework classes (Bundle, Parcel) are
 * available; the test dispatcher pins coroutine scheduling so we can assert
 * on stable state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RnsBackendIpcRoundTripTest {
    private lateinit var fake: FakeRnsBackend

    @Before
    fun setUp() {
        fake = FakeRnsBackend()
    }

    @Test
    fun `suspend Result-Unit hangupCall round-trips through the stub`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        client.telephony.hangupCall()
        advanceUntilIdle()

        assertEquals(1, fake.telephony.hangupCount)
    }

    @Test
    fun `suspend Result-VoiceCallState getCallState marshals the payload`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        val state = VoiceCallState(
            status = "RINGING",
            isActive = false,
            isMuted = false,
            remoteIdentity = "abc123",
            profile = "lxst-0x10",
        )
        fake.telephony.nextCallState = Result.success(state)

        val result = client.telephony.getCallState()
        advanceUntilIdle()

        assertTrue("expected Result.success, was $result", result.isSuccess)
        assertEquals(state, result.getOrThrow())
    }

    @Test
    fun `RnsError onError translates to RnsException on the client side`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        fake.telephony.nextCallState =
            Result.failure(RnsException(RnsError.CallStateInvalid(expected = "ESTABLISHED", actual = "IDLE")))

        val result = client.telephony.getCallState()
        advanceUntilIdle()

        assertTrue("expected Result.failure, was $result", result.isFailure)
        val err = (result.exceptionOrNull() as? RnsException)?.error
        assertTrue("error should be CallStateInvalid, was $err", err is RnsError.CallStateInvalid)
        err as RnsError.CallStateInvalid
        assertEquals("ESTABLISHED", err.expected)
        assertEquals("IDLE", err.actual)
    }

    @Test
    fun `Flow ReceivedMessage observeMessages relays emissions across the seam`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        val message = ReceivedMessage(
            messageHash = "deadbeef",
            content = "hello",
            sourceHash = byteArrayOf(1, 2, 3),
            destinationHash = byteArrayOf(4, 5, 6),
            timestamp = 1_700_000_000L,
        )

        client.lxmf.observeMessages().test {
            advanceUntilIdle()
            fake.lxmf.incomingMessages.emit(message)
            advanceUntilIdle()
            assertEquals(message, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `capabilities snapshot lands in the client StateFlow on connect`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        val caps = client.capabilities.value
        // Default fake exposes BackendId.KOTLIN_NATIVE with hot-reload on.
        assertEquals(BackendCapabilities.BackendId.KOTLIN_NATIVE, caps.backendId)
        assertTrue(caps.interfaces.hotReloadInterfaces)
    }

    @Test
    fun `setConnecting round-trips and the callState observer sees Connecting`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        val hash = "deadbeefdeadbeefdeadbeefdeadbeef"
        client.telephony.callState.test {
            // Initial Idle snapshot.
            assertEquals(network.columba.app.rns.api.model.CallState.Idle, awaitItem())

            client.telephony.setConnecting(hash)
            advanceUntilIdle()

            val next = awaitItem()
            assertTrue(
                "expected Connecting, was $next",
                next is network.columba.app.rns.api.model.CallState.Connecting,
            )
            assertEquals(hash, (next as network.columba.app.rns.api.model.CallState.Connecting).identityHash)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(hash, fake.telephony.remoteIdentity.value)
    }

    @Test
    fun `declineCall round-trips through the stub`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        client.telephony.declineCall()
        advanceUntilIdle()

        assertEquals(1, fake.telephony.declineCount)
    }

    @Test
    fun `setIncomingEnabled round-trips both true and false through the stub`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        client.telephony.setIncomingEnabled(false)
        advanceUntilIdle()
        assertEquals(false, fake.telephony.incomingEnabled)

        client.telephony.setIncomingEnabled(true)
        advanceUntilIdle()
        assertEquals(true, fake.telephony.incomingEnabled)

        assertEquals(2, fake.telephony.setIncomingEnabledCalls)
    }

    @Test
    fun `capabilities observer pushes mutations into the client StateFlow`() = runTest {
        val (client, _) = buildClientAndServer()
        advanceUntilIdle()

        val flipped = fake.capabilitiesState.value.copy(
            interfaces = fake.capabilitiesState.value.interfaces.copy(hotReloadInterfaces = false),
        )
        fake.capabilitiesState.value = flipped
        advanceUntilIdle()

        assertEquals(flipped, client.capabilities.value)
    }

    private suspend fun TestScope.buildClientAndServer(): Pair<RnsBackend, RnsBackendServer> {
        // Single shared scheduler so advanceUntilIdle() drains both halves of
        // the round trip. Separate jobs so we can cancel scopes independently
        // if a test wants to (none currently do, but the symmetry is useful
        // when this evolves into a per-test cleanup).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val serverScope = CoroutineScope(dispatcher + SupervisorJob())
        val clientScope = CoroutineScope(dispatcher + SupervisorJob())
        val server = RnsBackendServer(fake, serverScope)
        val client = RnsBackendClient(clientScope)
        client.connect(server)
        return client to server
    }
}

/* ============================== Fake backend ============================== */

private class FakeRnsBackend : RnsBackend {
    val capabilitiesState: MutableStateFlow<BackendCapabilities> = MutableStateFlow(
        BackendCapabilities(
            backendId = BackendCapabilities.BackendId.KOTLIN_NATIVE,
            versions = BackendCapabilities.Versions("0.0.20", "0.0.13", "0.0.3", null),
            interfaces = BackendCapabilities.InterfaceCaps(hotReloadInterfaces = true),
            telemetry = BackendCapabilities.TelemetryCaps(
                collectorHostMode = BackendCapabilities.Support.FULL,
                storeOwnTelemetry = BackendCapabilities.Support.FULL,
                allowedRequestersFilter = BackendCapabilities.Support.FULL,
            ),
            performance = BackendCapabilities.PerformanceCaps(
                batteryProfileTuning = BackendCapabilities.Support.FULL,
                sharedInstanceAvailabilityChecks = false,
            ),
        ),
    )

    override val capabilities: StateFlow<BackendCapabilities> get() = capabilitiesState.asStateFlow()
    override val core: RnsCore = FakeRnsCore()
    override val lxmf: FakeRnsLxmf = FakeRnsLxmf()
    override val telephony: FakeRnsTelephony = FakeRnsTelephony()
    override val telemetry: RnsTelemetry = FakeRnsTelemetry()
    override val nomadnet: RnsNomadnet = FakeRnsNomadnet()
    override val transportAdmin: RnsTransportAdmin = FakeRnsTransportAdmin()
}

private class FakeRnsTelephony : RnsTelephony {
    var nextInitiateResult: Result<Unit> = Result.success(Unit)
    var nextAnswerResult: Result<Unit> = Result.success(Unit)
    var hangupCount = 0
    var declineCount = 0
    var setIncomingEnabledCalls = 0
    var incomingEnabled: Boolean? = null
    var nextCallState: Result<VoiceCallState> = Result.success(
        VoiceCallState("IDLE", isActive = false, isMuted = false, remoteIdentity = null, profile = null),
    )

    private val _callState =
        kotlinx.coroutines.flow.MutableStateFlow<network.columba.app.rns.api.model.CallState>(
            network.columba.app.rns.api.model.CallState.Idle,
        )
    private val _remoteIdentity = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val _isMuted = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _isSpeakerOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _isPttMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _isPttActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    override val callState: kotlinx.coroutines.flow.StateFlow<network.columba.app.rns.api.model.CallState>
        get() = _callState
    override val remoteIdentity: kotlinx.coroutines.flow.StateFlow<String?> get() = _remoteIdentity
    override val isMuted: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _isMuted
    override val isSpeakerOn: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _isSpeakerOn
    override val isPttMode: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _isPttMode
    override val isPttActive: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _isPttActive

    fun emitCallState(state: network.columba.app.rns.api.model.CallState) {
        _callState.value = state
    }

    override suspend fun initiateCall(destinationHash: String, profileCode: Int?) = nextInitiateResult
    override suspend fun answerCall() = nextAnswerResult
    override suspend fun hangupCall() { hangupCount++ }
    override suspend fun declineCall() { declineCount++ }
    override suspend fun setCallMuted(muted: Boolean) { _isMuted.value = muted }
    override suspend fun setCallSpeaker(speakerOn: Boolean) { _isSpeakerOn.value = speakerOn }
    override suspend fun getCallState(): Result<VoiceCallState> = nextCallState

    override suspend fun setConnecting(destinationHash: String) {
        _remoteIdentity.value = destinationHash
        _callState.value = network.columba.app.rns.api.model.CallState.Connecting(destinationHash)
    }
    override suspend fun setEnded() {
        _callState.value = network.columba.app.rns.api.model.CallState.Ended
    }
    override suspend fun setMutedLocally(muted: Boolean) { _isMuted.value = muted }
    override suspend fun setSpeakerLocally(enabled: Boolean) { _isSpeakerOn.value = enabled }
    override suspend fun setPttModeLocally(enabled: Boolean) { _isPttMode.value = enabled }
    override suspend fun setPttActiveLocally(active: Boolean) { _isPttActive.value = active }
    override suspend fun setIncomingEnabled(enabled: Boolean) {
        setIncomingEnabledCalls++
        incomingEnabled = enabled
    }
}

private class FakeRnsLxmf : RnsLxmf {
    val incomingMessages: MutableSharedFlow<ReceivedMessage> = MutableSharedFlow(extraBufferCapacity = 8)
    private val deliveryStatus: MutableSharedFlow<DeliveryStatusUpdate> = MutableSharedFlow(extraBufferCapacity = 8)
    private val propagation: MutableSharedFlow<PropagationState> = MutableSharedFlow(extraBufferCapacity = 8)

    override suspend fun sendLxmfMessage(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
    ): Result<MessageReceipt> = Result.failure(NotImplementedError())

    override suspend fun sendLxmfMessageWithMethod(
        destinationHash: ByteArray,
        content: String,
        sourceIdentity: Identity,
        deliveryMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: List<Pair<String, ByteArray>>?,
        replyToMessageId: String?,
        replyQuotedContent: String?,
        iconAppearance: IconAppearance?,
        extraFields: Map<Int, Any>?,
    ): Result<MessageReceipt> = Result.failure(NotImplementedError())

    override suspend fun sendReaction(
        destinationHash: ByteArray,
        targetMessageId: String,
        emoji: String,
        sourceIdentity: Identity,
    ): Result<MessageReceipt> = Result.failure(NotImplementedError())

    override fun observeMessages(): Flow<ReceivedMessage> = incomingMessages
    override fun observeDeliveryStatus(): Flow<DeliveryStatusUpdate> = deliveryStatus

    override suspend fun getLxmfIdentity(): Result<Identity> = Result.failure(NotImplementedError())
    override suspend fun getLxmfDestination(): Result<Destination> = Result.failure(NotImplementedError())

    override suspend fun setOutboundPropagationNode(destHash: ByteArray?): Result<Unit> = Result.success(Unit)
    override suspend fun getOutboundPropagationNode(): Result<String?> = Result.success(null)
    override suspend fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): Result<PropagationState> = Result.failure(NotImplementedError())

    override suspend fun getPropagationState(): Result<PropagationState> = Result.failure(NotImplementedError())
    override suspend fun cancelMessageSync(): Result<Unit> = Result.success(Unit)
    override val propagationStateFlow: SharedFlow<PropagationState> = propagation.asSharedFlow()

    override fun setConversationActive(active: Boolean) {}
    override fun setIncomingMessageSizeLimit(limitKb: Int) {}
}

/** Minimal stubs for the unused-in-tests sub-interfaces. */
private class FakeRnsCore : RnsCore {
    private val status = MutableStateFlow<NetworkStatus>(NetworkStatus.READY)
    override val networkStatus: StateFlow<NetworkStatus> get() = status.asStateFlow()
    override suspend fun initialize(config: ReticulumConfig) = Result.success(Unit)
    override suspend fun shutdown() = Result.success(Unit)
    override suspend fun createIdentity(): Result<Identity> = Result.failure(NotImplementedError())
    override suspend fun loadIdentity(path: String): Result<Identity> = Result.failure(NotImplementedError())
    override suspend fun saveIdentity(identity: Identity, path: String) = Result.success(Unit)
    override suspend fun recallIdentity(hash: ByteArray): Identity? = null
    override suspend fun createIdentityWithName(displayName: String): Map<String, Any> = emptyMap()
    override suspend fun importIdentityFile(fileData: ByteArray, displayName: String): Map<String, Any> = emptyMap()
    override suspend fun exportIdentityFile(keyData: ByteArray, filePath: String): ByteArray = ByteArray(0)
    override suspend fun getFullIdentityKey(): ByteArray? = null
    override suspend fun createDestination(
        identity: Identity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        aspects: List<String>,
    ): Result<Destination> = Result.failure(NotImplementedError())

    override suspend fun announceDestination(destination: Destination, appData: ByteArray?) = Result.success(Unit)
    override suspend fun triggerAutoAnnounce(displayName: String) = Result.success(Unit)
    override suspend fun sendPacket(
        destination: Destination,
        data: ByteArray,
        packetType: PacketType,
    ): Result<PacketReceipt> = Result.failure(NotImplementedError())

    override fun observePackets(): Flow<ReceivedPacket> = MutableSharedFlow()
    override suspend fun establishLink(destination: Destination): Result<Link> = Result.failure(NotImplementedError())
    override suspend fun closeLink(link: Link) = Result.success(Unit)
    override suspend fun sendOverLink(link: Link, data: ByteArray) = Result.success(Unit)
    override fun observeLinks(): Flow<LinkEvent> = MutableSharedFlow()
    override suspend fun hasPath(destinationHash: ByteArray): Boolean = false
    override suspend fun requestPath(destinationHash: ByteArray) = Result.success(Unit)
    override suspend fun persistTransportData() {}
    override suspend fun getHopCount(destinationHash: ByteArray): Int? = null
    override suspend fun getNextHopInterfaceName(destinationHash: ByteArray): String? = null
    override suspend fun getPathTableHashes(): List<String> = emptyList()
    override suspend fun probeLinkSpeed(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
        deliveryMethod: String,
    ): LinkSpeedProbeResult = throw NotImplementedError()
    override suspend fun isTransportEnabled(): Boolean = false
    override suspend fun establishConversationLink(
        destinationHash: ByteArray,
        timeoutSeconds: Float,
    ): Result<ConversationLinkResult> = Result.failure(NotImplementedError())
    override suspend fun closeConversationLink(destinationHash: ByteArray): Result<Boolean> = Result.success(false)
    override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult =
        throw NotImplementedError()
    override fun observeAnnounces(): Flow<AnnounceEvent> = MutableSharedFlow()
    override suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>): Result<Int> =
        Result.success(0)
    override suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>): Result<Int> =
        Result.success(0)
    override suspend fun blockDestination(destinationHashHex: String) = Result.success(Unit)
    override suspend fun unblockDestination(destinationHashHex: String) = Result.success(Unit)
    override suspend fun blackholeIdentity(identityHashHex: String) = Result.success(Unit)
    override suspend fun unblackholeIdentity(identityHashHex: String) = Result.success(Unit)
}

private class FakeRnsTelemetry : RnsTelemetry {
    private val emissions = MutableSharedFlow<network.columba.app.rns.api.model.LocationTelemetry>(extraBufferCapacity = 4)
    override suspend fun sendLocationTelemetry(
        destinationHash: ByteArray,
        telemetry: network.columba.app.rns.api.model.LocationTelemetry,
        sourceIdentity: Identity,
        iconAppearance: IconAppearance?,
    ): Result<MessageReceipt> = Result.failure(NotImplementedError())
    override suspend fun sendTelemetryRequest(
        destinationHash: ByteArray,
        sourceIdentity: Identity,
        timebase: Long?,
        isCollectorRequest: Boolean,
    ): Result<MessageReceipt> = Result.failure(NotImplementedError())
    override suspend fun setTelemetryCollectorMode(enabled: Boolean) = Result.success(Unit)
    override suspend fun storeOwnTelemetry(locationJson: String, iconAppearance: IconAppearance?) =
        Result.success(Unit)
    override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>) = Result.success(Unit)
    override val locationTelemetryFlow: SharedFlow<network.columba.app.rns.api.model.LocationTelemetry> =
        emissions.asSharedFlow()
}

private class FakeRnsNomadnet : RnsNomadnet {
    private val status = MutableStateFlow("idle")
    private val progress = MutableStateFlow(0f)
    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<NomadnetPageResult> = Result.failure(NotImplementedError())
    override suspend fun cancelNomadnetPageRequest() {}
    override suspend fun getNomadnetRequestStatus(): String = status.value
    override suspend fun getNomadnetDownloadProgress(): Float = progress.value
    override suspend fun identifyNomadnetLink(destinationHash: String) = Result.success(true)
    override val nomadnetRequestStatusFlow: StateFlow<String> get() = status.asStateFlow()
    override val nomadnetDownloadProgressFlow: StateFlow<Float> get() = progress.asStateFlow()
}

private class FakeRnsTransportAdmin : RnsTransportAdmin {
    override fun setBatteryProfile(profile: network.columba.app.rns.api.model.BatteryProfile) {}
    override suspend fun reloadInterfaces(configs: List<InterfaceConfig>) {}
    override suspend fun setDiscoveryEnabled(enabled: Boolean) {}
    override suspend fun setAutoconnectLimit(count: Int) {}
    override suspend fun setAutoconnectIfacOnly(enabled: Boolean) {}
    override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> = emptyList()
    override suspend fun isDiscoveryEnabled(): Boolean = false
    override suspend fun getAutoconnectedEndpoints(): Set<String> = emptySet()
    override suspend fun isSharedInstanceAvailable(): Boolean = false
    override suspend fun getDebugInfo(): Map<String, Any> = emptyMap()
    override suspend fun getFailedInterfaces(): List<FailedInterface> = emptyList()
    override suspend fun getInterfaceStats(interfaceName: String): Map<String, Any>? = null
    override suspend fun reconnectRNodeInterface() {}
    override fun getRNodeRssi(): Int = -100
    override fun getBleConnectionDetails(): String = "[]"
    override val interfaceStatusChanged: SharedFlow<Unit> = MutableSharedFlow()
    override val bleConnectionsFlow: SharedFlow<String> = MutableSharedFlow()
    override val debugInfoFlow: SharedFlow<String> = MutableSharedFlow()
    override val interfaceStatusFlow: SharedFlow<String> = MutableSharedFlow()
    override val reactionReceivedFlow: SharedFlow<String> = MutableSharedFlow()
}
