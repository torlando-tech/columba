package com.lxmf.messenger.service.binder

import android.content.Context
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.EventHandler
import com.lxmf.messenger.service.manager.HealthCheckManager
import com.lxmf.messenger.service.manager.IdentityManager
import com.lxmf.messenger.service.manager.LockManager
import com.lxmf.messenger.service.manager.MaintenanceManager
import com.lxmf.messenger.service.manager.MemoryProfilerManager
import com.lxmf.messenger.service.manager.MessagingManager
import com.lxmf.messenger.service.manager.NetworkChangeManager
import com.lxmf.messenger.service.manager.PythonWrapperManager
import com.lxmf.messenger.service.manager.RoutingManager
import com.lxmf.messenger.service.manager.ServiceNotificationManager
import com.lxmf.messenger.service.persistence.ServicePersistenceManager
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for ReticulumServiceBinder.
 *
 * Tests lifecycle methods and their interaction with MaintenanceManager
 * to ensure wake lock refresh mechanism is properly started and stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReticulumServiceBinderTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var context: Context
    private lateinit var state: ServiceState
    private lateinit var wrapperManager: PythonWrapperManager
    private lateinit var identityManager: IdentityManager
    private lateinit var routingManager: RoutingManager
    private lateinit var messagingManager: MessagingManager
    private lateinit var eventHandler: EventHandler
    private lateinit var broadcaster: CallbackBroadcaster
    private lateinit var lockManager: LockManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var healthCheckManager: HealthCheckManager
    private lateinit var memoryProfilerManager: MemoryProfilerManager
    private lateinit var networkChangeManager: NetworkChangeManager
    private lateinit var notificationManager: ServiceNotificationManager
    private lateinit var bleCoordinator: BleCoordinator
    private lateinit var persistenceManager: ServicePersistenceManager

    private lateinit var networkStatusMock: AtomicReference<String>
    private lateinit var binder: ReticulumServiceBinder
    private var onShutdownCalled = false

    @Suppress("NoRelaxedMocks") // Context is an Android framework class
    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
        onShutdownCalled = false

        context = mockk(relaxed = true)
        state = mockk()
        wrapperManager = mockk()
        identityManager = mockk()
        routingManager = mockk()
        messagingManager = mockk()
        eventHandler = mockk()
        broadcaster = mockk()
        lockManager = mockk()
        maintenanceManager = mockk()
        healthCheckManager = mockk()
        memoryProfilerManager = mockk()
        networkChangeManager = mockk()
        notificationManager = mockk()
        bleCoordinator = mockk()
        persistenceManager = mockk()

        // Setup networkStatus as a real AtomicReference for verification
        networkStatusMock = mockk()
        every { state.networkStatus } returns networkStatusMock
        every { state.initializationGeneration } returns
            java.util.concurrent.atomic
                .AtomicInteger(0)
        every { state.isCurrentGeneration(any()) } returns true
        coEvery { wrapperManager.shutdown(any()) } just Runs

        // Default stubs for commonly called methods during shutdown
        every { networkChangeManager.stop() } just Runs
        every { healthCheckManager.stop() } just Runs
        every { memoryProfilerManager.stopProfiling() } just Runs
        every { maintenanceManager.stop() } just Runs
        every { eventHandler.stopAll() } just Runs
        every { lockManager.releaseAll() } just Runs
        every { networkStatusMock.set(any()) } just Runs
        every { networkStatusMock.get() } returns "READY"
        every { broadcaster.broadcastStatusChange(any()) } just Runs
        every { notificationManager.updateNotification(any()) } just Runs

        // Stubs for callback registration
        every { broadcaster.register(any()) } just Runs
        every { broadcaster.unregister(any()) } just Runs
        every { eventHandler.setConversationActive(any()) } just Runs

        binder =
            ReticulumServiceBinder(
                context = context,
                state = state,
                wrapperManager = wrapperManager,
                identityManager = identityManager,
                routingManager = routingManager,
                messagingManager = messagingManager,
                eventHandler = eventHandler,
                broadcaster = broadcaster,
                lockManager = lockManager,
                maintenanceManager = maintenanceManager,
                healthCheckManager = healthCheckManager,
                memoryProfilerManager = memoryProfilerManager,
                networkChangeManager = networkChangeManager,
                notificationManager = notificationManager,
                bleCoordinator = bleCoordinator,
                persistenceManager = persistenceManager,
                scope = testScope,
                onInitialized = {},
                onShutdown = { onShutdownCalled = true },
                onForceExit = {},
            )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Shutdown Tests ==========

    @Test
    fun `shutdown calls maintenanceManager stop`() {
        val result = runCatching { binder.shutdown() }

        assertTrue("shutdown should complete successfully", result.isSuccess)
        verify(exactly = 1) { maintenanceManager.stop() }
    }

    @Test
    fun `shutdown stops maintenance before releasing locks`() {
        val result = runCatching { binder.shutdown() }

        assertTrue("shutdown should complete successfully", result.isSuccess)
        verifyOrder {
            maintenanceManager.stop()
            eventHandler.stopAll()
            lockManager.releaseAll()
        }
    }

    @Test
    fun `shutdown calls eventHandler stopAll`() {
        val result = runCatching { binder.shutdown() }

        assertTrue("shutdown should complete successfully", result.isSuccess)
        verify(exactly = 1) { eventHandler.stopAll() }
    }

    @Test
    fun `shutdown calls lockManager releaseAll`() {
        val result = runCatching { binder.shutdown() }

        assertTrue("shutdown should complete successfully", result.isSuccess)
        verify(exactly = 1) { lockManager.releaseAll() }
    }

    @Test
    fun `shutdown updates state to RESTARTING`() {
        val result = runCatching { binder.shutdown() }

        assertTrue("shutdown should complete successfully", result.isSuccess)
        verify { networkStatusMock.set("RESTARTING") }
    }

    @Test
    fun `shutdown broadcasts RESTARTING status`() {
        val result = runCatching { binder.shutdown() }

        assertTrue("shutdown should complete successfully", result.isSuccess)
        verify { broadcaster.broadcastStatusChange("RESTARTING") }
    }

    // ========== GetStatus Tests ==========

    @Test
    fun `getStatus returns current network status`() {
        every { networkStatusMock.get() } returns "READY"

        val status = binder.getStatus()

        assert(status == "READY")
    }

    // ========== ForceExit Tests ==========

    @Test
    fun `forceExit calls shutdown first`() {
        val result = runCatching { binder.forceExit() }

        assertTrue("forceExit should complete successfully", result.isSuccess)
        verify { maintenanceManager.stop() }
        verify { eventHandler.stopAll() }
        verify { lockManager.releaseAll() }
    }

    // ========== IsInitialized Tests ==========

    @Test
    fun `isInitialized returns true when state is initialized`() {
        every { state.isInitialized() } returns true

        val result = binder.isInitialized()

        assert(result)
    }

    @Test
    fun `isInitialized returns false when state is not initialized`() {
        every { state.isInitialized() } returns false
        every { state.wrapper } returns null

        val result = binder.isInitialized()

        assert(!result)
    }

    // ========== Identity Delegation Tests ==========

    @Test
    fun `createIdentity delegates to identityManager`() {
        every { identityManager.createIdentity() } returns """{"hash": "abc123"}"""

        val result = binder.createIdentity()

        verify { identityManager.createIdentity() }
        assert(result == """{"hash": "abc123"}""")
    }

    @Test
    fun `loadIdentity delegates to identityManager`() {
        every { identityManager.loadIdentity("/test/path") } returns """{"hash": "abc123"}"""

        val result = binder.loadIdentity("/test/path")

        verify { identityManager.loadIdentity("/test/path") }
        assert(result == """{"hash": "abc123"}""")
    }

    @Test
    fun `saveIdentity delegates to identityManager`() {
        val privateKey = byteArrayOf(1, 2, 3)
        every { identityManager.saveIdentity(privateKey, "/test/path") } returns """{"success": true}"""

        val result = binder.saveIdentity(privateKey, "/test/path")

        verify { identityManager.saveIdentity(privateKey, "/test/path") }
        assert(result == """{"success": true}""")
    }

    @Test
    fun `getLxmfIdentity delegates to identityManager`() {
        every { identityManager.getLxmfIdentity() } returns """{"hash": "lxmf123"}"""

        val result = binder.lxmfIdentity

        verify { identityManager.getLxmfIdentity() }
        assert(result == """{"hash": "lxmf123"}""")
    }

    @Test
    fun `getLxmfDestination delegates to identityManager`() {
        every { identityManager.getLxmfDestination() } returns """{"hash": "dest123"}"""

        val result = binder.lxmfDestination

        verify { identityManager.getLxmfDestination() }
        assert(result == """{"hash": "dest123"}""")
    }

    // ========== Routing Delegation Tests ==========

    @Test
    fun `hasPath delegates to routingManager`() {
        val destHash = byteArrayOf(1, 2, 3)
        every { routingManager.hasPath(destHash) } returns true

        val result = binder.hasPath(destHash)

        verify { routingManager.hasPath(destHash) }
        assert(result)
    }

    @Test
    fun `requestPath delegates to routingManager`() {
        val destHash = byteArrayOf(1, 2, 3)
        every { routingManager.requestPath(destHash) } returns """{"success": true}"""

        val result = binder.requestPath(destHash)

        verify { routingManager.requestPath(destHash) }
        assert(result == """{"success": true}""")
    }

    @Test
    fun `getHopCount delegates to routingManager`() {
        val destHash = byteArrayOf(1, 2, 3)
        every { routingManager.getHopCount(destHash) } returns 3

        val result = binder.getHopCount(destHash)

        verify { routingManager.getHopCount(destHash) }
        assert(result == 3)
    }

    // ========== Messaging Delegation Tests ==========

    @Test
    fun `sendLxmfMessage delegates to messagingManager`() {
        val destHash = byteArrayOf(1, 2, 3)
        val content = "Hello"
        val privateKey = byteArrayOf(4, 5, 6)
        every { messagingManager.sendLxmfMessage(destHash, content, privateKey, null, null, null) } returns """{"success": true}"""

        val result = binder.sendLxmfMessage(destHash, content, privateKey, null, null, null)

        verify { messagingManager.sendLxmfMessage(destHash, content, privateKey, null, null, null) }
        assert(result == """{"success": true}""")
    }

    @Test
    fun `sendLxmfMessage with file attachments delegates to messagingManager`() {
        val destHash = byteArrayOf(1, 2, 3)
        val content = "Here's a file"
        val privateKey = byteArrayOf(4, 5, 6)
        val fileAttachmentsMap: Map<*, *> = mapOf("test.pdf" to byteArrayOf(0x25, 0x50, 0x44, 0x46))
        // The binder converts Map to List<Pair> internally
        every {
            messagingManager.sendLxmfMessage(destHash, content, privateKey, null, null, any())
        } returns """{"success": true, "message_hash": "abc123"}"""

        val result = binder.sendLxmfMessage(destHash, content, privateKey, null, null, fileAttachmentsMap)

        verify { messagingManager.sendLxmfMessage(destHash, content, privateKey, null, null, any()) }
        assert(result.contains("success"))
        assert(result.contains("true"))
    }

    @Test
    fun `sendLxmfMessage with multiple file attachments delegates correctly`() {
        val destHash = byteArrayOf(1, 2, 3)
        val content = "Multiple files"
        val privateKey = byteArrayOf(4, 5, 6)
        val fileAttachmentsMap: Map<*, *> =
            mapOf(
                "doc.pdf" to byteArrayOf(0x25, 0x50, 0x44, 0x46),
                "notes.txt" to "Hello World".toByteArray(),
            )
        every {
            messagingManager.sendLxmfMessage(destHash, content, privateKey, null, null, any())
        } returns """{"success": true}"""

        val result = binder.sendLxmfMessage(destHash, content, privateKey, null, null, fileAttachmentsMap)

        verify { messagingManager.sendLxmfMessage(destHash, content, privateKey, null, null, any()) }
        assert(result == """{"success": true}""")
    }

    // ========== Callback Tests ==========

    @Test
    fun `registerCallback delegates to broadcaster`() {
        val callback = mockk<com.lxmf.messenger.IReticulumServiceCallback>()

        val result = runCatching { binder.registerCallback(callback) }

        assertTrue("registerCallback should complete successfully", result.isSuccess)
        verify { broadcaster.register(callback) }
    }

    @Test
    fun `unregisterCallback delegates to broadcaster`() {
        val callback = mockk<com.lxmf.messenger.IReticulumServiceCallback>()

        val result = runCatching { binder.unregisterCallback(callback) }

        assertTrue("unregisterCallback should complete successfully", result.isSuccess)
        verify { broadcaster.unregister(callback) }
    }

    @Test
    fun `setConversationActive delegates to eventHandler`() {
        val result = runCatching { binder.setConversationActive(true) }

        assertTrue("setConversationActive should complete successfully", result.isSuccess)
        verify { eventHandler.setConversationActive(true) }
    }

    // ========== BLE Connection Tests ==========

    @Test
    fun `getBleConnectionDetails delegates to bleCoordinator`() {
        every { bleCoordinator.getConnectionDetailsJson() } returns """[{"device": "test"}]"""

        val result = binder.bleConnectionDetails

        verify { bleCoordinator.getConnectionDetailsJson() }
        assert(result == """[{"device": "test"}]""")
    }

    // ========== IsSharedInstanceAvailable Tests ==========

    @Test
    fun `isSharedInstanceAvailable returns false when wrapper returns null`() {
        every { wrapperManager.withWrapper<Boolean?>(any()) } returns null

        val result = binder.isSharedInstanceAvailable

        assert(!result)
    }

    @Test
    fun `isSharedInstanceAvailable returns false on exception`() {
        every { wrapperManager.withWrapper<Boolean?>(any()) } throws RuntimeException("Test error")

        val result = binder.isSharedInstanceAvailable

        assert(!result)
    }

    // ========== GetFailedInterfaces Tests ==========

    @Test
    fun `getFailedInterfaces returns empty array when wrapper returns null`() {
        every { wrapperManager.withWrapper<String?>(any()) } returns null

        val result = binder.failedInterfaces

        assert(result == "[]")
    }

    @Test
    fun `getFailedInterfaces returns empty array on exception`() {
        every { wrapperManager.withWrapper<String?>(any()) } throws RuntimeException("Test error")

        val result = binder.failedInterfaces

        assert(result == "[]")
    }

    // ========== Propagation Node Tests ==========

    @Test
    fun `setOutboundPropagationNode returns error when wrapper null`() {
        every { wrapperManager.withWrapper<String?>(any()) } returns null

        val result = binder.setOutboundPropagationNode(byteArrayOf(1, 2, 3))

        assert(result.contains("Wrapper not initialized"))
    }

    @Test
    fun `setOutboundPropagationNode returns error on exception`() {
        every { wrapperManager.withWrapper<String?>(any()) } throws RuntimeException("Test error")

        val result = binder.setOutboundPropagationNode(byteArrayOf(1, 2, 3))

        assert(result.contains("Test error"))
    }

    @Test
    fun `getOutboundPropagationNode returns error when wrapper null`() {
        every { wrapperManager.withWrapper<String?>(any()) } returns null

        val result = binder.outboundPropagationNode

        assert(result.contains("Wrapper not initialized"))
    }

    @Test
    fun `getPropagationState returns error when wrapper null`() {
        every { wrapperManager.withWrapper<String?>(any()) } returns null

        val result = binder.propagationState

        assert(result.contains("Wrapper not initialized"))
    }

    @Test
    fun `requestMessagesFromPropagationNode returns error when wrapper null`() {
        every { wrapperManager.withWrapper<String?>(any()) } returns null

        val result = binder.requestMessagesFromPropagationNode(null, 10)

        assert(result.contains("Wrapper not initialized"))
    }
}
