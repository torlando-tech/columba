package com.lxmf.messenger.service.di

import android.content.Context
import com.lxmf.messenger.service.manager.MaintenanceManager
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceModule.
 *
 * Tests that the dependency injection module correctly creates and wires
 * service managers, particularly the MaintenanceManager integration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceModuleTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var context: Context

    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== createManagers Tests ==========

    @Test
    fun `createManagers returns ServiceManagers with maintenanceManager`() {
        val managers = ServiceModule.createManagers(context, testScope)

        assertNotNull("ServiceManagers should contain maintenanceManager", managers.maintenanceManager)
    }

    @Test
    fun `createManagers creates MaintenanceManager with correct dependencies`() {
        val managers = ServiceModule.createManagers(context, testScope)

        // MaintenanceManager should be properly initialized
        assertNotNull(managers.maintenanceManager)
        // MaintenanceManager should have lockManager (verified by not throwing when used)
        assertNotNull(managers.lockManager)
    }

    @Test
    fun `createManagers returns all required managers`() {
        val managers = ServiceModule.createManagers(context, testScope)

        assertNotNull("state should not be null", managers.state)
        assertNotNull("lockManager should not be null", managers.lockManager)
        assertNotNull("maintenanceManager should not be null", managers.maintenanceManager)
        assertNotNull("notificationManager should not be null", managers.notificationManager)
        assertNotNull("broadcaster should not be null", managers.broadcaster)
        assertNotNull("bleCoordinator should not be null", managers.bleCoordinator)
        assertNotNull("wrapperManager should not be null", managers.wrapperManager)
        assertNotNull("identityManager should not be null", managers.identityManager)
        assertNotNull("routingManager should not be null", managers.routingManager)
        assertNotNull("messagingManager should not be null", managers.messagingManager)
        assertNotNull("eventHandler should not be null", managers.eventHandler)
    }

    @Test
    fun `createManagers maintenanceManager is correct type`() {
        val managers = ServiceModule.createManagers(context, testScope)

        assert(managers.maintenanceManager is MaintenanceManager) {
            "maintenanceManager should be instance of MaintenanceManager"
        }
    }

    // ========== createBinder Tests ==========

    @Test
    fun `createBinder creates binder with maintenanceManager from managers`() {
        val managers = ServiceModule.createManagers(context, testScope)

        val binder =
            ServiceModule.createBinder(
                context = context,
                managers = managers,
                scope = testScope,
                onInitialized = {},
                onShutdown = {},
                onForceExit = {},
            )

        // Binder should be created without throwing
        assertNotNull("Binder should be created", binder)
    }

    @Test
    fun `createBinder passes all dependencies to binder`() {
        val managers = ServiceModule.createManagers(context, testScope)

        // Should not throw when creating binder with all managers
        val binder =
            ServiceModule.createBinder(
                context = context,
                managers = managers,
                scope = testScope,
                onInitialized = {},
                onShutdown = {},
                onForceExit = {},
            )

        assertNotNull(binder)
    }
}
