package network.columba.app.service.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import network.columba.app.service.binder.ReticulumServiceBinder
import network.columba.app.service.manager.BleCoordinator
import network.columba.app.service.manager.LockManager
import network.columba.app.service.manager.NetworkChangeManager
import network.columba.app.service.manager.ServiceNotificationManager
import network.columba.app.service.persistence.ServicePersistenceManager
import network.columba.app.service.persistence.ServiceSettingsAccessor
import network.columba.app.service.state.ServiceState

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
        val networkChangeManager: NetworkChangeManager,
        val notificationManager: ServiceNotificationManager,
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
        val state = ServiceState()
        val lockManager = LockManager(context)
        val notificationManager = ServiceNotificationManager(context, state)
        val bleCoordinator = BleCoordinator(context)
        val settingsAccessor = ServiceSettingsAccessor(context)
        val persistenceManager = ServicePersistenceManager(context, scope, settingsAccessor)
        val networkChangeManager = NetworkChangeManager(context, lockManager, onNetworkChanged)

        return ServiceManagers(
            state = state,
            lockManager = lockManager,
            networkChangeManager = networkChangeManager,
            notificationManager = notificationManager,
            bleCoordinator = bleCoordinator,
            persistenceManager = persistenceManager,
            settingsAccessor = settingsAccessor,
        )
    }

    /**
     * Create the local binder returned from ReticulumService.onBind().
     */
    fun createBinder(
        managers: ServiceManagers,
        onShutdown: () -> Unit,
    ): ReticulumServiceBinder =
        ReticulumServiceBinder(
            state = managers.state,
            onShutdown = onShutdown,
        )
}
