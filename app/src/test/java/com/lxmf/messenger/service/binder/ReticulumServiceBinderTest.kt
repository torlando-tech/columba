package com.lxmf.messenger.service.binder

import android.content.Context
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.IdentityManager
import com.lxmf.messenger.service.manager.LockManager
import com.lxmf.messenger.service.manager.MaintenanceManager
import com.lxmf.messenger.service.manager.MessagingManager
import com.lxmf.messenger.service.manager.PollingManager
import com.lxmf.messenger.service.manager.PythonWrapperManager
import com.lxmf.messenger.service.manager.RoutingManager
import com.lxmf.messenger.service.manager.ServiceNotificationManager
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
    private lateinit var pollingManager: PollingManager
    private lateinit var broadcaster: CallbackBroadcaster
    private lateinit var lockManager: LockManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var notificationManager: ServiceNotificationManager
    private lateinit var bleCoordinator: BleCoordinator

    private lateinit var networkStatusMock: AtomicReference<String>
    private lateinit var binder: ReticulumServiceBinder
    private var onShutdownCalled = false

    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
        onShutdownCalled = false

        context = mockk(relaxed = true)
        state = mockk(relaxed = true)
        wrapperManager = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        routingManager = mockk(relaxed = true)
        messagingManager = mockk(relaxed = true)
        pollingManager = mockk(relaxed = true)
        broadcaster = mockk(relaxed = true)
        lockManager = mockk(relaxed = true)
        maintenanceManager = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        bleCoordinator = mockk(relaxed = true)

        // Setup networkStatus as a real AtomicReference for verification
        networkStatusMock = mockk(relaxed = true)
        every { state.networkStatus } returns networkStatusMock
        every { state.initializationGeneration } returns mockk(relaxed = true)
        every { state.isCurrentGeneration(any()) } returns true
        coEvery { wrapperManager.shutdown(any()) } just Runs

        binder = ReticulumServiceBinder(
            context = context,
            state = state,
            wrapperManager = wrapperManager,
            identityManager = identityManager,
            routingManager = routingManager,
            messagingManager = messagingManager,
            pollingManager = pollingManager,
            broadcaster = broadcaster,
            lockManager = lockManager,
            maintenanceManager = maintenanceManager,
            notificationManager = notificationManager,
            bleCoordinator = bleCoordinator,
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
        binder.shutdown()

        verify(exactly = 1) { maintenanceManager.stop() }
    }

    @Test
    fun `shutdown stops maintenance before releasing locks`() {
        binder.shutdown()

        verifyOrder {
            maintenanceManager.stop()
            pollingManager.stopAll()
            lockManager.releaseAll()
        }
    }

    @Test
    fun `shutdown calls pollingManager stopAll`() {
        binder.shutdown()

        verify(exactly = 1) { pollingManager.stopAll() }
    }

    @Test
    fun `shutdown calls lockManager releaseAll`() {
        binder.shutdown()

        verify(exactly = 1) { lockManager.releaseAll() }
    }

    @Test
    fun `shutdown updates state to RESTARTING`() {
        binder.shutdown()

        verify { networkStatusMock.set("RESTARTING") }
    }

    @Test
    fun `shutdown broadcasts RESTARTING status`() {
        binder.shutdown()

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
        binder.forceExit()

        verify { maintenanceManager.stop() }
        verify { pollingManager.stopAll() }
        verify { lockManager.releaseAll() }
    }
}
