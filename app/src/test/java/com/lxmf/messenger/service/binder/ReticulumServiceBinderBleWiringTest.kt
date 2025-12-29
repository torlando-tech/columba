package com.lxmf.messenger.service.binder

import android.content.Context
import com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.HealthCheckManager
import com.lxmf.messenger.service.manager.IdentityManager
import com.lxmf.messenger.service.manager.LockManager
import com.lxmf.messenger.service.manager.MaintenanceManager
import com.lxmf.messenger.service.manager.MessagingManager
import com.lxmf.messenger.service.manager.NetworkChangeManager
import com.lxmf.messenger.service.manager.PollingManager
import com.lxmf.messenger.service.manager.PythonWrapperManager
import com.lxmf.messenger.service.manager.RoutingManager
import com.lxmf.messenger.service.manager.ServiceNotificationManager
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests that BleCoordinator is properly wired up during service initialization.
 *
 * This test verifies the fix for the bug where BLE connection updates weren't
 * being broadcast because setCallbackBroadcaster() was never called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReticulumServiceBinderBleWiringTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context
    private lateinit var mockState: ServiceState
    private lateinit var mockWrapperManager: PythonWrapperManager
    private lateinit var mockIdentityManager: IdentityManager
    private lateinit var mockRoutingManager: RoutingManager
    private lateinit var mockMessagingManager: MessagingManager
    private lateinit var mockPollingManager: PollingManager
    private lateinit var mockBroadcaster: CallbackBroadcaster
    private lateinit var mockLockManager: LockManager
    private lateinit var mockMaintenanceManager: MaintenanceManager
    private lateinit var mockHealthCheckManager: HealthCheckManager
    private lateinit var mockNetworkChangeManager: NetworkChangeManager
    private lateinit var mockNotificationManager: ServiceNotificationManager
    private lateinit var mockBleCoordinator: BleCoordinator
    private lateinit var mockBridge: KotlinBLEBridge

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockState = mockk(relaxed = true)
        mockWrapperManager = mockk(relaxed = true)
        mockIdentityManager = mockk(relaxed = true)
        mockRoutingManager = mockk(relaxed = true)
        mockMessagingManager = mockk(relaxed = true)
        mockPollingManager = mockk(relaxed = true)
        mockBroadcaster = mockk(relaxed = true)
        mockLockManager = mockk(relaxed = true)
        mockMaintenanceManager = mockk(relaxed = true)
        mockHealthCheckManager = mockk(relaxed = true)
        mockNetworkChangeManager = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        mockBleCoordinator = mockk(relaxed = true)
        mockBridge = mockk(relaxed = true)

        // Mock the KotlinBLEBridge singleton
        mockkObject(KotlinBLEBridge.Companion)
        every { KotlinBLEBridge.getInstance(any()) } returns mockBridge

        // Setup default mocks
        every { mockState.networkStatus } returns AtomicReference("SHUTDOWN")
        every { mockBleCoordinator.setCallbackBroadcaster(any()) } just Runs
        every { mockBleCoordinator.getBridge() } returns mockBridge
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(KotlinBLEBridge.Companion)
        clearAllMocks()
    }

    @Test
    fun `setupBridges calls bleCoordinator setCallbackBroadcaster`() =
        runTest {
            // Given - create binder with mocked dependencies
            val binder =
                ReticulumServiceBinder(
                    context = mockContext,
                    state = mockState,
                    wrapperManager = mockWrapperManager,
                    identityManager = mockIdentityManager,
                    routingManager = mockRoutingManager,
                    messagingManager = mockMessagingManager,
                    pollingManager = mockPollingManager,
                    broadcaster = mockBroadcaster,
                    lockManager = mockLockManager,
                    maintenanceManager = mockMaintenanceManager,
                    healthCheckManager = mockHealthCheckManager,
                    networkChangeManager = mockNetworkChangeManager,
                    notificationManager = mockNotificationManager,
                    bleCoordinator = mockBleCoordinator,
                    scope = CoroutineScope(testDispatcher),
                    onInitialized = {},
                    onShutdown = {},
                    onForceExit = {},
                )

            // When - call setupBridges via reflection
            val setupBridgesMethod =
                ReticulumServiceBinder::class.java.getDeclaredMethod("setupBridges")
            setupBridgesMethod.isAccessible = true
            setupBridgesMethod.invoke(binder)

            // Then - verify setCallbackBroadcaster was called with the broadcaster
            verify { mockBleCoordinator.setCallbackBroadcaster(mockBroadcaster) }
        }
}
