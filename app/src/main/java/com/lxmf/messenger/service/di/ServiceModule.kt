package com.lxmf.messenger.service.di

import android.content.Context
import com.lxmf.messenger.service.binder.ReticulumServiceBinder
import com.lxmf.messenger.service.manager.BleCoordinator
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.manager.LockManager
import com.lxmf.messenger.service.manager.MaintenanceManager
import com.lxmf.messenger.service.manager.NetworkChangeManager
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
