package network.columba.app.rns.host.ipc

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.rns.api.model.CallState
import network.columba.app.rns.api.model.ConversationLinkResult
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import network.columba.app.rns.api.model.LocationTelemetry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.flow.first

/**
 * Unit tests for the [BoundRnsBackend] sub-wrappers' three load-bearing
 * behaviours:
 *
 * 1. Suspend calls await the first non-null binding then forward (the
 *    "delegates each method per sub-interface" half of the Phase 1 gate).
 * 2. StateFlow accessors republish via `flatMapLatest` so a rebind surfaces
 *    a new upstream value rather than a terminal completion.
 * 3. Non-suspend fire-and-forget mutators reach the backend after bind
 *    (covers the `setBatteryProfile` / `setConversationActive` shape).
 *
 * The sub-wrappers are tested against a tiny [FakeRnsBackend] hand-rolled in
 * this file rather than mocking — these classes are pure delegation, and
 * mocking the 200-method aggregate surface would dwarf the test it supports.
 *
 * BoundRnsBackend itself (the orchestrator) takes a `Context` and triggers
 * a real `bindService` call in its constructor, so its end-to-end binding
 * lifecycle belongs to instrumented tests (`:rns-ipc`'s
 * BinderDeathReconnectTest, Phase 5). What this file covers is the layer
 * directly underneath — the part that does the actual republishing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BoundRnsBackendTest {
    @Test
    fun `BoundRnsCore suspend call awaits binding then forwards`() = runTest {
        val flow = MutableStateFlow<RnsBackend?>(null)
        val core = BoundRnsCore(flow.asStateFlow(), backgroundScope)

        // Calling initialize before binding suspends; advancing the dispatcher
        // proves the call is suspended (not throwing).
        val deferred = async { core.initialize(REFERENCE_CONFIG) }
        advanceUntilIdle()
        assertTrue("initialize must suspend until backendFlow emits", !deferred.isCompleted)

        // Bind. The suspended call should resume and return the fake's result.
        val fake = FakeRnsBackend()
        flow.value = fake
        advanceUntilIdle()
        assertTrue("initialize must resume to success once binding lands", deferred.await().isSuccess)
        assertEquals(1, fake.coreFake.initializeCalls)
    }

    @Test
    fun `BoundRnsCore networkStatus republishes across rebinds`() = runTest {
        val flow = MutableStateFlow<RnsBackend?>(null)
        val core = BoundRnsCore(flow.asStateFlow(), backgroundScope)

        val first = FakeRnsBackend()
        val second = FakeRnsBackend()

        core.networkStatus.test {
            assertEquals(NetworkStatus.INITIALIZING, awaitItem()) // seed value

            flow.value = first
            first.coreFake.networkStatusEmitter.value = NetworkStatus.READY
            assertEquals(NetworkStatus.READY, awaitItem())

            // Rebind: a fresh backend with its own StateFlow at a different state.
            second.coreFake.networkStatusEmitter.value = NetworkStatus.CONNECTING
            flow.value = second
            assertEquals(NetworkStatus.CONNECTING, awaitItem())

            // Subsequent upstream emissions from the new backend keep flowing.
            second.coreFake.networkStatusEmitter.value = NetworkStatus.READY
            assertEquals(NetworkStatus.READY, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BoundRnsTransportAdmin fire-and-forget mutator reaches bound backend`() = runTest {
        val flow = MutableStateFlow<RnsBackend?>(null)
        // Use a scope bound to the test's scheduler. backgroundScope is convenient
        // for `stateIn`/`shareIn` initialisers (collected lazily), but its launches
        // are cooperative with the runTest body in ways that complicate ordering;
        // a child scope of the TestScope keeps the fire-and-forget launch in the
        // same dispatch sequence as advanceUntilIdle.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val admin = BoundRnsTransportAdmin(flow.asStateFlow(), scope)

        val fake = FakeRnsBackend()
        flow.value = fake
        admin.setBatteryProfile(BatteryProfile.BALANCED)
        advanceUntilIdle()
        assertEquals(1, fake.transportAdminFake.batteryProfileSets.size)
        assertEquals(BatteryProfile.BALANCED, fake.transportAdminFake.batteryProfileSets.first())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `BoundRnsTransportAdmin getRNodeRssi returns noise-floor before bind`() = runTest {
        val flow = MutableStateFlow<RnsBackend?>(null)
        val admin = BoundRnsTransportAdmin(flow.asStateFlow(), backgroundScope)

        assertEquals(-100, admin.getRNodeRssi())
        assertEquals("[]", admin.getBleConnectionDetails())

        val fake = FakeRnsBackend().apply {
            transportAdminFake.rssi = -77
            transportAdminFake.bleConnections = """[{"address":"AA:BB"}]"""
        }
        flow.value = fake
        assertEquals(-77, admin.getRNodeRssi())
        assertEquals("""[{"address":"AA:BB"}]""", admin.getBleConnectionDetails())
    }

    @Test
    fun `BoundRnsTelephony callState republishes across rebinds`() = runTest {
        val flow = MutableStateFlow<RnsBackend?>(null)
        val tel = BoundRnsTelephony(flow.asStateFlow(), backgroundScope)

        tel.callState.test {
            assertEquals(CallState.Idle, awaitItem())

            val first = FakeRnsBackend()
            flow.value = first
            val newState = CallState.Connecting("ab".repeat(16))
            first.telephonyFake.callStateEmitter.value = newState
            assertEquals(newState, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        val REFERENCE_CONFIG = ReticulumConfig(
            storagePath = "/tmp/test",
            enabledInterfaces = emptyList(),
            enableTransport = false,
            logLevel = network.columba.app.rns.api.model.LogLevel.INFO,
        )
    }

    // ====================================================================
    // Test doubles. Hand-rolled because mocking the full backend surface
    // would dwarf the test it supports and obscure intent.
    // ====================================================================

    private class FakeRnsBackend : RnsBackend {
        val coreFake = FakeRnsCore()
        val telephonyFake = FakeRnsTelephony()
        val transportAdminFake = FakeRnsTransportAdmin()
        private val capabilitiesEmitter = MutableStateFlow(BackendCapabilities.UNKNOWN)

        override val capabilities: StateFlow<BackendCapabilities> = capabilitiesEmitter.asStateFlow()
        override val core: RnsCore = coreFake
        override val lxmf: RnsLxmf = StubRnsLxmf()
        override val telephony: RnsTelephony = telephonyFake
        override val telemetry: RnsTelemetry = StubRnsTelemetry()
        override val nomadnet: RnsNomadnet = StubRnsNomadnet()
        override val transportAdmin: RnsTransportAdmin = transportAdminFake
    }

    /**
     * Records calls to the methods the tests exercise and stubs every other
     * suspend method with a trivial return — implementing the full interface
     * via stubs keeps the class concrete (no `: RnsCore by mock` runtime
     * surprises) without ballooning test setup.
     */
    private class FakeRnsCore : RnsCore {
        var initializeCalls = 0
        val networkStatusEmitter = MutableStateFlow<NetworkStatus>(NetworkStatus.INITIALIZING)

        override suspend fun initialize(config: ReticulumConfig): Result<Unit> {
            initializeCalls++
            return Result.success(Unit)
        }
        override suspend fun shutdown(): Result<Unit> = Result.success(Unit)
        override val networkStatus: StateFlow<NetworkStatus> = networkStatusEmitter.asStateFlow()
        override suspend fun createIdentity(): Result<Identity> = error("not used")
        override suspend fun loadIdentity(path: String): Result<Identity> = error("not used")
        override suspend fun saveIdentity(identity: Identity, path: String) = Result.success(Unit)
        override suspend fun recallIdentity(hash: ByteArray): Identity? = null
        override suspend fun createIdentityWithName(displayName: String): Map<String, Any> = emptyMap()
        override suspend fun importIdentityFile(fileData: ByteArray, displayName: String): Map<String, Any> = emptyMap()
        override suspend fun exportIdentityFile(keyData: ByteArray, filePath: String): ByteArray = ByteArray(0)
        override suspend fun getFullIdentityKey(): ByteArray? = null
        override suspend fun createDestination(
            identity: Identity, direction: Direction, type: DestinationType,
            appName: String, aspects: List<String>,
        ): Result<Destination> = error("not used")
        override suspend fun announceDestination(destination: Destination, appData: ByteArray?) = Result.success(Unit)
        override suspend fun triggerAutoAnnounce(displayName: String) = Result.success(Unit)
        override suspend fun sendPacket(destination: Destination, data: ByteArray, packetType: PacketType): Result<PacketReceipt> = error("not used")
        override fun observePackets() = kotlinx.coroutines.flow.emptyFlow<ReceivedPacket>()
        override suspend fun establishLink(destination: Destination): Result<Link> = error("not used")
        override suspend fun closeLink(link: Link) = Result.success(Unit)
        override suspend fun sendOverLink(link: Link, data: ByteArray) = Result.success(Unit)
        override fun observeLinks() = kotlinx.coroutines.flow.emptyFlow<LinkEvent>()
        override suspend fun hasPath(destinationHash: ByteArray) = false
        override suspend fun requestPath(destinationHash: ByteArray) = Result.success(Unit)
        override suspend fun persistTransportData() = Unit
        override suspend fun getHopCount(destinationHash: ByteArray): Int? = null
        override suspend fun getNextHopInterfaceName(destinationHash: ByteArray): String? = null
        override suspend fun getPathTableHashes(): List<String> = emptyList()
        override suspend fun probeLinkSpeed(destinationHash: ByteArray, timeoutSeconds: Float, deliveryMethod: String): LinkSpeedProbeResult = error("not used")
        override suspend fun isTransportEnabled() = false
        override suspend fun establishConversationLink(destinationHash: ByteArray, timeoutSeconds: Float): Result<ConversationLinkResult> = error("not used")
        override suspend fun closeConversationLink(destinationHash: ByteArray) = Result.success(false)
        override suspend fun getConversationLinkStatus(destinationHash: ByteArray): ConversationLinkResult = error("not used")
        override fun observeAnnounces() = kotlinx.coroutines.flow.emptyFlow<AnnounceEvent>()
        override suspend fun restorePeerIdentities(peerIdentities: List<Pair<String, ByteArray>>) = Result.success(0)
        override suspend fun restoreAnnounceIdentities(announces: List<Pair<String, ByteArray>>) = Result.success(0)
        override suspend fun blockDestination(destinationHashHex: String) = Result.success(Unit)
        override suspend fun unblockDestination(destinationHashHex: String) = Result.success(Unit)
        override suspend fun blackholeIdentity(identityHashHex: String) = Result.success(Unit)
        override suspend fun unblackholeIdentity(identityHashHex: String) = Result.success(Unit)
    }

    private class FakeRnsTelephony : RnsTelephony {
        val callStateEmitter = MutableStateFlow<CallState>(CallState.Idle)
        override suspend fun initiateCall(destinationHash: String, profileCode: Int?) = Result.success(Unit)
        override suspend fun answerCall() = Result.success(Unit)
        override suspend fun hangupCall() {}
        override suspend fun declineCall() {}
        override suspend fun setCallMuted(muted: Boolean) {}
        override suspend fun setCallSpeaker(speakerOn: Boolean) {}
        override suspend fun getCallState(): Result<VoiceCallState> = error("not used")
        override val callState: StateFlow<CallState> = callStateEmitter.asStateFlow()
        override val remoteIdentity: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val isMuted: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val isSpeakerOn: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val isPttMode: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val isPttActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override suspend fun setConnecting(destinationHash: String) {}
        override suspend fun setEnded() {}
        override suspend fun setMutedLocally(muted: Boolean) {}
        override suspend fun setSpeakerLocally(enabled: Boolean) {}
        override suspend fun setPttModeLocally(enabled: Boolean) {}
        override suspend fun setPttActiveLocally(active: Boolean) {}
    }

    private class FakeRnsTransportAdmin : RnsTransportAdmin {
        val batteryProfileSets = mutableListOf<BatteryProfile>()
        var rssi: Int = -100
        var bleConnections: String = "[]"
        private val statusChangedEmitter = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 16)
        private val bleConnectionsEmitter = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)
        private val debugInfoEmitter = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        private val interfaceStatusEmitter = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)
        private val reactionReceivedEmitter = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)

        override fun setBatteryProfile(profile: BatteryProfile) { batteryProfileSets.add(profile) }
        override suspend fun reloadInterfaces(configs: List<InterfaceConfig>) {}
        override suspend fun setDiscoveryEnabled(enabled: Boolean) {}
        override suspend fun setAutoconnectLimit(count: Int) {}
        override suspend fun setAutoconnectIfacOnly(enabled: Boolean) {}
        override suspend fun getDiscoveredInterfaces(): List<DiscoveredInterface> = emptyList()
        override suspend fun isDiscoveryEnabled() = false
        override suspend fun getAutoconnectedEndpoints() = emptySet<String>()
        override suspend fun isSharedInstanceAvailable() = false
        override suspend fun getDebugInfo() = emptyMap<String, Any>()
        override suspend fun getFailedInterfaces(): List<FailedInterface> = emptyList()
        override suspend fun getInterfaceStats(interfaceName: String) = null
        override suspend fun reconnectRNodeInterface() {}
        override fun getRNodeRssi(): Int = rssi
        override fun getBleConnectionDetails(): String = bleConnections
        override val interfaceStatusChanged: SharedFlow<Unit> = statusChangedEmitter.asSharedFlow()
        override val bleConnectionsFlow: SharedFlow<String> = bleConnectionsEmitter.asSharedFlow()
        override val debugInfoFlow: SharedFlow<String> = debugInfoEmitter.asSharedFlow()
        override val interfaceStatusFlow: SharedFlow<String> = interfaceStatusEmitter.asSharedFlow()
        override val reactionReceivedFlow: SharedFlow<String> = reactionReceivedEmitter.asSharedFlow()
    }

    // Trivial stubs for sub-interfaces our tests don't exercise. They exist so
    // FakeRnsBackend can be a concrete RnsBackend without `by lazy { error(...) }`
    // surprises during construction.
    private class StubRnsLxmf : RnsLxmf {
        override suspend fun sendLxmfMessage(destinationHash: ByteArray, content: String, sourceIdentity: Identity, imageData: ByteArray?, imageFormat: String?, fileAttachments: List<Pair<String, ByteArray>>?): Result<MessageReceipt> = error("not used")
        override suspend fun sendLxmfMessageWithMethod(destinationHash: ByteArray, content: String, sourceIdentity: Identity, deliveryMethod: DeliveryMethod, tryPropagationOnFail: Boolean, imageData: ByteArray?, imageFormat: String?, fileAttachments: List<Pair<String, ByteArray>>?, replyToMessageId: String?, replyQuotedContent: String?, iconAppearance: IconAppearance?, extraFields: Map<Int, Any>?): Result<MessageReceipt> = error("not used")
        override suspend fun sendReaction(destinationHash: ByteArray, targetMessageId: String, emoji: String, sourceIdentity: Identity): Result<MessageReceipt> = error("not used")
        override fun observeMessages() = kotlinx.coroutines.flow.emptyFlow<ReceivedMessage>()
        override fun observeDeliveryStatus() = kotlinx.coroutines.flow.emptyFlow<DeliveryStatusUpdate>()
        override suspend fun getLxmfIdentity(): Result<Identity> = error("not used")
        override suspend fun getLxmfDestination(): Result<Destination> = error("not used")
        override suspend fun setOutboundPropagationNode(destHash: ByteArray?) = Result.success(Unit)
        override suspend fun getOutboundPropagationNode(): Result<String?> = Result.success(null)
        override suspend fun requestMessagesFromPropagationNode(identityPrivateKey: ByteArray?, maxMessages: Int): Result<PropagationState> = error("not used")
        override suspend fun getPropagationState(): Result<PropagationState> = error("not used")
        override suspend fun cancelMessageSync() = Result.success(Unit)
        override val propagationStateFlow: SharedFlow<PropagationState> = MutableSharedFlow<PropagationState>(replay = 0).asSharedFlow()
        override fun setConversationActive(active: Boolean) {}
        override fun setIncomingMessageSizeLimit(limitKb: Int) {}
    }

    private class StubRnsTelemetry : RnsTelemetry {
        override suspend fun sendLocationTelemetry(destinationHash: ByteArray, telemetry: LocationTelemetry, sourceIdentity: Identity, iconAppearance: IconAppearance?): Result<MessageReceipt> = error("not used")
        override suspend fun sendTelemetryRequest(destinationHash: ByteArray, sourceIdentity: Identity, timebase: Long?, isCollectorRequest: Boolean): Result<MessageReceipt> = error("not used")
        override suspend fun setTelemetryCollectorMode(enabled: Boolean) = Result.success(Unit)
        override suspend fun storeOwnTelemetry(locationJson: String, iconAppearance: IconAppearance?) = Result.success(Unit)
        override suspend fun setTelemetryAllowedRequesters(allowedHashes: Set<String>) = Result.success(Unit)
        override val locationTelemetryFlow: SharedFlow<LocationTelemetry> = MutableSharedFlow<LocationTelemetry>(replay = 0).asSharedFlow()
    }

    private class StubRnsNomadnet : RnsNomadnet {
        override suspend fun requestNomadnetPage(destinationHash: String, path: String, formDataJson: String?, timeoutSeconds: Float): Result<NomadnetPageResult> = error("not used")
        override suspend fun cancelNomadnetPageRequest() {}
        override suspend fun getNomadnetRequestStatus(): String = "idle"
        override suspend fun getNomadnetDownloadProgress(): Float = 0f
        override suspend fun identifyNomadnetLink(destinationHash: String) = Result.success(false)
        override val nomadnetRequestStatusFlow: StateFlow<String> = MutableStateFlow("idle").asStateFlow()
        override val nomadnetDownloadProgressFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
    }
}
