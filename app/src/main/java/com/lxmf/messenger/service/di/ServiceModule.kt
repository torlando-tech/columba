package com.lxmf.messenger.service.di

import android.content.Context
import com.lxmf.messenger.service.binder.ReticulumServiceBinder
import com.lxmf.messenger.service.manager.AttachmentStorageManager
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.EventHandler
import com.lxmf.messenger.service.manager.HealthCheckManager
import com.lxmf.messenger.service.manager.IdentityManager
import com.lxmf.messenger.service.manager.LockManager
import com.lxmf.messenger.service.manager.MaintenanceManager
import com.lxmf.messenger.service.manager.MessagingManager
import com.lxmf.messenger.service.manager.NetworkChangeManager
import com.lxmf.messenger.service.manager.PythonWrapperManager
import com.lxmf.messenger.service.manager.RoutingManager
import com.lxmf.messenger.service.manager.ServiceNotificationManager
import com.lxmf.messenger.service.persistence.ServicePersistenceManager
import com.lxmf.messenger.service.persistence.ServiceSettingsAccessor
import com.lxmf.messenger.service.state.ServiceState
import kotlinx.coroutines.CoroutineScope

/**
 * Manual dependency injection module for ReticulumService.
 *
 * Hilt doesn't work across process boundaries, and ReticulumService runs in
 * a separate :reticulum process. This module provides factory methods to
 * create and wire all service managers together.
 *
 * Usage:
 * ```kotlin
 * class ReticulumService : Service() {
 *     private lateinit var managers: ServiceManagers
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
 *         managers = ServiceModule.createManagers(this, scope, ...)
 *     }
 * }
 * ```
 */
object ServiceModule {
    /**
     * Container holding all manager instances for the service.
     */
    data class ServiceManagers(
        val state: ServiceState,
        val lockManager: LockManager,
        val maintenanceManager: MaintenanceManager,
        val healthCheckManager: HealthCheckManager,
        val networkChangeManager: NetworkChangeManager,
        val notificationManager: ServiceNotificationManager,
        val broadcaster: CallbackBroadcaster,
        val bleCoordinator: BleCoordinator,
        val wrapperManager: PythonWrapperManager,
        val identityManager: IdentityManager,
        val routingManager: RoutingManager,
        val messagingManager: MessagingManager,
        val eventHandler: EventHandler,
        val persistenceManager: ServicePersistenceManager,
        val settingsAccessor: ServiceSettingsAccessor,
    )

    /**
     * Create all managers with proper dependency wiring.
     *
     * @param context Service context
     * @param scope Coroutine scope for async operations
     * @param onStaleHeartbeat Callback when Python heartbeat is stale (service should restart)
     * @param onNetworkChanged Callback when network connectivity changes (for LXMF announce)
     * @return Container with all initialized managers
     */
    fun createManagers(
        context: Context,
        scope: CoroutineScope,
        onStaleHeartbeat: () -> Unit = {},
        onNetworkChanged: () -> Unit = {},
    ): ServiceManagers {
        // Phase 1: Foundation (no dependencies)
        val state = ServiceState()
        val lockManager = LockManager(context)
        val maintenanceManager = MaintenanceManager(lockManager, scope)
        val notificationManager = ServiceNotificationManager(context, state)
        val broadcaster = CallbackBroadcaster()
        val bleCoordinator = BleCoordinator(context)
        val persistenceManager = ServicePersistenceManager(context, scope)
        val settingsAccessor = ServiceSettingsAccessor(context)

        // Phase 2: Python wrapper (depends on state, context, scope)
        val wrapperManager = PythonWrapperManager(state, context, scope)

        // Phase 3: Health monitoring (depends on wrapperManager, scope)
        // Started after initialization completes (see ReticulumServiceBinder)
        val healthCheckManager = HealthCheckManager(wrapperManager, scope, onStaleHeartbeat)

        // Phase 4: Network change monitoring (depends on lockManager)
        // Reacquires locks and triggers announce on network changes
        val networkChangeManager = NetworkChangeManager(context, lockManager, onNetworkChanged)

        // Phase 5: Business logic (depends on wrapperManager)
        val identityManager = IdentityManager(wrapperManager)
        val routingManager = RoutingManager(wrapperManager)
        val messagingManager = MessagingManager(wrapperManager)

        // Phase 6: Attachment storage (depends on context)
        val attachmentStorage = AttachmentStorageManager(context)

        // Phase 7: Event handling and persistence (depends on state, wrapperManager, broadcaster, scope, attachmentStorage, persistenceManager)
        val eventHandler = EventHandler(state, wrapperManager, broadcaster, scope, attachmentStorage, persistenceManager)

        return ServiceManagers(
            state = state,
            lockManager = lockManager,
            maintenanceManager = maintenanceManager,
            healthCheckManager = healthCheckManager,
            networkChangeManager = networkChangeManager,
            notificationManager = notificationManager,
            broadcaster = broadcaster,
            bleCoordinator = bleCoordinator,
            wrapperManager = wrapperManager,
            identityManager = identityManager,
            routingManager = routingManager,
            messagingManager = messagingManager,
            eventHandler = eventHandler,
            persistenceManager = persistenceManager,
            settingsAccessor = settingsAccessor,
        )
    }

    /**
     * Create the AIDL binder with all dependencies wired.
     *
     * @param managers Container with all manager instances
     * @param scope Coroutine scope for async operations
     * @param onInitialized Callback when initialization completes
     * @param onShutdown Callback when shutdown completes
     * @param onForceExit Callback for force exit (kills process)
     * @return Configured binder implementing IReticulumService
     */
    fun createBinder(
        context: Context,
        managers: ServiceManagers,
        scope: CoroutineScope,
        onInitialized: () -> Unit,
        onShutdown: () -> Unit,
        onForceExit: () -> Unit,
    ): ReticulumServiceBinder {
        return ReticulumServiceBinder(
            context = context,
            state = managers.state,
            wrapperManager = managers.wrapperManager,
            identityManager = managers.identityManager,
            routingManager = managers.routingManager,
            messagingManager = managers.messagingManager,
            eventHandler = managers.eventHandler,
            broadcaster = managers.broadcaster,
            lockManager = managers.lockManager,
            maintenanceManager = managers.maintenanceManager,
            healthCheckManager = managers.healthCheckManager,
            networkChangeManager = managers.networkChangeManager,
            notificationManager = managers.notificationManager,
            bleCoordinator = managers.bleCoordinator,
            persistenceManager = managers.persistenceManager,
            scope = scope,
            onInitialized = onInitialized,
            onShutdown = onShutdown,
            onForceExit = onForceExit,
        )
    }
}
