package network.columba.app.service.di

import android.content.Context
import network.columba.app.service.binder.ReticulumServiceBinder
import network.columba.app.service.manager.BleCoordinator
import network.columba.app.service.manager.CallbackBroadcaster
import network.columba.app.service.manager.LockManager
import network.columba.app.service.manager.MaintenanceManager
import network.columba.app.service.manager.NetworkChangeManager
import network.columba.app.service.manager.ServiceNotificationManager
import network.columba.app.service.persistence.ServicePersistenceManager
import network.columba.app.service.persistence.ServiceSettingsAccessor
import network.columba.app.service.state.ServiceState
import kotlinx.coroutines.CoroutineScope

/**
 * Manual dependency injection module for ReticulumService.
 *
 * Hilt doesn't work across process boundaries, and ReticulumService runs in
 * a separate :reticulum process. This module provides factory methods to
 * create and wire all service managers together.
 */
object ServiceModule {
    /**
     * Container holding all manager instances for the service.
     */
    data class ServiceManagers(
        val state: ServiceState,
        val lockManager: LockManager,
        val maintenanceManager: MaintenanceManager,
        val networkChangeManager: NetworkChangeManager,
        val notificationManager: ServiceNotificationManager,
        val broadcaster: CallbackBroadcaster,
        val bleCoordinator: BleCoordinator,
        val persistenceManager: ServicePersistenceManager,
        val settingsAccessor: ServiceSettingsAccessor,
    )

    /**
     * Create all managers with proper dependency wiring.
     *
     * @param context Service context
     * @param scope Coroutine scope for async operations
     * @param onNetworkChanged Callback when network connectivity changes (for LXMF announce)
     * @return Container with all initialized managers
     */
    fun createManagers(
        context: Context,
        scope: CoroutineScope,
        onNetworkChanged: () -> Unit = {},
    ): ServiceManagers {
        // Phase 1: Foundation (no dependencies)
        val state = ServiceState()
        val lockManager = LockManager(context)
        val maintenanceManager = MaintenanceManager(lockManager, scope)
        val notificationManager = ServiceNotificationManager(context, state)
        val broadcaster = CallbackBroadcaster()
        val bleCoordinator = BleCoordinator(context)
        val settingsAccessor = ServiceSettingsAccessor(context)
        val persistenceManager = ServicePersistenceManager(context, scope, settingsAccessor)

        // Network change monitoring (depends on lockManager)
        val networkChangeManager = NetworkChangeManager(context, lockManager, onNetworkChanged)

        return ServiceManagers(
            state = state,
            lockManager = lockManager,
            maintenanceManager = maintenanceManager,
            networkChangeManager = networkChangeManager,
            notificationManager = notificationManager,
            broadcaster = broadcaster,
            bleCoordinator = bleCoordinator,
            persistenceManager = persistenceManager,
            settingsAccessor = settingsAccessor,
        )
    }

    /**
     * Create the AIDL binder with all dependencies wired.
     */
    fun createBinder(
        managers: ServiceManagers,
        onShutdown: () -> Unit,
        onForceExit: () -> Unit,
    ): ReticulumServiceBinder =
        ReticulumServiceBinder(
            state = managers.state,
            broadcaster = managers.broadcaster,
            onShutdown = onShutdown,
            onForceExit = onForceExit,
        )
}
