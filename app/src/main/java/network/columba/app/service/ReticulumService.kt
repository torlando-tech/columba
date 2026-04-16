package network.columba.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import network.columba.app.service.binder.ReticulumServiceBinder
import network.columba.app.service.di.ServiceModule

/**
 * Background service that hosts the Python Reticulum instance.
 * Runs as a foreground service to ensure reliability and proper threading for socket I/O.
 *
 * This solves the Chaquopy threading limitation where background threads for socket I/O
 * don't work reliably in the main app process.
 *
 * Architecture:
 * - This class is a thin lifecycle shell (~150 lines vs original 1,762)
 * - All business logic is delegated to specialized managers via ServiceModule
 * - AIDL implementation is in ReticulumServiceBinder
 * - State is managed in ServiceState
 */
class ReticulumService : Service() {
    companion object {
        private const val TAG = "ReticulumService"

        // Actions for service control
        const val ACTION_START = "network.columba.app.service.START"
        const val ACTION_STOP = "network.columba.app.service.STOP"
        const val ACTION_RESTART_BLE = "network.columba.app.RESTART_BLE"
        const val ACTION_UPDATE_NOTIFICATION = "network.columba.app.service.UPDATE_NOTIFICATION"
        const val EXTRA_NETWORK_STATUS = "network_status"
    }

    // Coroutine scope for background tasks
    // Uses Dispatchers.Default for CPU-bound work (JSON parsing, orchestration)
    // SupervisorJob ensures child coroutine failures don't cancel the entire service
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Managers container (initialized in onCreate)
    private lateinit var managers: ServiceModule.ServiceManagers

    // AIDL binder (initialized in onCreate)
    private lateinit var binder: ReticulumServiceBinder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Initialize all managers via dependency injection
        // Provide callbacks for health monitoring and network changes
        managers =
            ServiceModule.createManagers(
                context = this,
                scope = serviceScope,
                onNetworkChanged = {
                    // Trigger AutoInterface hot-add + LXMF announce when network changes.
                    // CRITICAL: Run in coroutine scope to avoid blocking the ConnectivityManager
                    // callback thread. Blocking that thread can cause Android's watchdog to kill
                    // the service, leading to "Service not bound" errors.
                    Log.d(TAG, "Network changed - restarting AutoInterface and triggering LXMF announce")
                    // Guard: binder property must be initialized AND Reticulum must be ready
                    // This prevents announces during service initialization, which can cause
                    // DataStore race conditions and service crashes
                    if (::binder.isInitialized && binder.isInitialized()) {
                        serviceScope.launch {
                            try {
                                withTimeout(10_000L) {
                                    // Hot-add any new network interfaces to AutoInterface FIRST,
                                    // so the subsequent announce goes out on the new interface.
                                    // This fixes the bug where starting without WiFi and later
                                    // connecting never discovers AutoInterface peers.
                                    binder.restartAutoInterface()
                                    binder.announceLxmfDestination()
                                }
                                // Signal main app's AutoAnnounceManager to reset its timer
                                // This uses DataStore for cross-process communication
                                val now = System.currentTimeMillis()
                                managers.settingsAccessor.saveNetworkChangeAnnounceTime(now)
                                managers.settingsAccessor.saveLastAutoAnnounceTime(now)
                            } catch (_: TimeoutCancellationException) {
                                Log.w(TAG, "LXMF announce timed out on network change")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to announce on network change", e)
                            }
                        }
                    } else {
                        Log.d(TAG, "Skipping announce - Reticulum not yet initialized")
                    }
                },
            )

        // Create notification channel
        managers.notificationManager.createNotificationChannel()

        // Restore the last persisted network status written by the app process. If Android
        // restarted the :reticulum process on its own (START_STICKY), the native stack in
        // the app process is still READY, and without this restore the first notification
        // would flash "Disconnected" with no one around to push a fresh update.
        managers.settingsAccessor.getLastNetworkStatus()?.let { status ->
            managers.state.networkStatus.set(status)
        }

        // CRITICAL: Start foreground immediately in onCreate to prevent being killed
        // before onStartCommand or onBind are called. This is the earliest safe point.
        managers.notificationManager.startForeground(this)
        Log.d(TAG, "Foreground service started in onCreate")

        // CRITICAL: Acquire wake lock early to prevent CPU sleep during initialization
        // Native stack manages multicast lock per-AutoInterface for battery savings.
        managers.lockManager.skipMulticastLock = true
        managers.lockManager.acquireAll()
        Log.d(TAG, "Wake locks acquired in onCreate")

        // Clean up stale announces (>30 days old) on each service lifecycle
        managers.persistenceManager.cleanupStaleAnnounces()

        // Create binder returned from onBind(). No cross-process protocol calls flow
        // through here anymore — it's just a liveness handle for bindService consumers.
        binder =
            ServiceModule.createBinder(
                managers = managers,
                onShutdown = {
                    Log.d(TAG, "Reticulum shutdown complete")
                },
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")

        // If the user explicitly shut down the service, don't allow START_STICKY or
        // scheduleServiceRestart() to bring it back. Check flag and stop immediately.
        val isUserShutdown =
            getSharedPreferences("columba_prefs", MODE_PRIVATE)
                .getBoolean("is_user_shutdown", false)
        val isUserShutdownRestart = isUserShutdown && intent?.action != ACTION_STOP && intent?.action != ACTION_START

        // CRITICAL: Always return START_STICKY to ensure Android restarts the service if killed.
        // Previously returned START_NOT_STICKY when managers weren't initialized, which meant
        // the service wouldn't restart after being killed during the initialization race window.
        val notInitialized = !::managers.isInitialized || !::binder.isInitialized

        if (isUserShutdownRestart || notInitialized) {
            if (isUserShutdownRestart) {
                Log.i(TAG, "User shutdown flag set - stopping service instead of restarting")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            Log.w(TAG, "onStartCommand called before onCreate completed - will retry after init")
            return START_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                // Clear user shutdown flag — this is an intentional start (app launch or restart)
                getSharedPreferences("columba_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_user_shutdown", false)
                    .apply()

                // Reinforce foreground service with notification (may already be started in onCreate)
                managers.notificationManager.startForeground(this)
            }
            ACTION_STOP -> {
                // Shutdown and stop service
                Log.d(TAG, "Received ACTION_STOP - forcing process exit")

                // Remove notification FIRST — binder.shutdown()'s async Python cleanup
                // can crash the process, so ensure the notification is gone before that.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

                if (::managers.isInitialized) managers.state.isPythonShutdownStarted.set(true)

                // Stop BLE immediately on Main thread before process exit.
                // The normal Python shutdown path (ReticulumWrapper.shutdown() → BLEInterface.detach()
                // → AndroidBLEDriver.stop() → KotlinBLEBridge.stop()) won't complete because
                // System.exit(0) kills the process before async cleanup finishes.
                if (::managers.isInitialized) {
                    try {
                        managers.bleCoordinator.stopImmediate()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error during BLE immediate shutdown", e)
                    }
                }

                try {
                    binder.shutdown()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during shutdown cleanup (process exiting anyway)", e)
                }

                // Force process exit to ensure service truly stops even with active bindings
                // This is safe because we're in a separate :reticulum process
                System.exit(0)
            }
            ACTION_RESTART_BLE -> {
                // Restart BLE interface after permissions granted
                handleRestartBle(intent)
            }
            ACTION_UPDATE_NOTIFICATION -> {
                val status = intent.getStringExtra(EXTRA_NETWORK_STATUS)
                if (status != null) {
                    managers.state.networkStatus.set(status)
                    managers.notificationManager.updateNotification(status)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")

        // Guard against calls before onCreate completes
        if (!::managers.isInitialized || !::binder.isInitialized) {
            Log.e(TAG, "onBind called before onCreate completed")
            throw IllegalStateException("Service not initialized")
        }

        // Start as foreground when first client binds
        managers.notificationManager.startForeground(this)

        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "Service rebound")
        if (::managers.isInitialized) {
            managers.notificationManager.startForeground(this)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Service unbound")

        // CRITICAL: Reinforce foreground status when all clients disconnect.
        // This ensures the service stays protected even when no clients are bound.
        // The service should continue running in foreground to receive messages.
        if (::managers.isInitialized) {
            managers.notificationManager.startForeground(this)
            Log.d(TAG, "Reinforced foreground status after unbind")
        }

        // Return true to allow rebinding without destroying the service
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Set kill switch to prevent SIGSEGV from late Python calls
        if (::managers.isInitialized) {
            managers.state.isPythonShutdownStarted.set(true)
        }

        // Clean up all resources (if initialized)
        if (::managers.isInitialized) {
            managers.notificationManager.resetSyncNotification()
            managers.networkChangeManager.stop()
            managers.lockManager.releaseAll()
            // Safety net: stop BLE if not already stopped by ACTION_STOP
            managers.bleCoordinator.stopImmediate()
        }
        serviceScope.cancel()

        // Only schedule restart if the user didn't explicitly shut down the service
        val isUserShutdown =
            getSharedPreferences("columba_prefs", MODE_PRIVATE)
                .getBoolean("is_user_shutdown", false)
        if (isUserShutdown) {
            Log.d(TAG, "User shutdown flag set - skipping service restart")
        } else {
            // Schedule explicit service restart (Sideband-inspired auto-restart)
            // This ensures service comes back up even if Android delays START_STICKY restart
            scheduleServiceRestart()
        }
    }

    /**
     * Schedule explicit service restart.
     * Inspired by Sideband's auto-restart pattern in PythonService.java.
     */
    private fun scheduleServiceRestart() {
        try {
            val restartIntent =
                Intent(applicationContext, ReticulumService::class.java).apply {
                    action = ACTION_START
                }
            // Start foreground service - Android will handle queueing if process is dying
            ContextCompat.startForegroundService(applicationContext, restartIntent)
            Log.d(TAG, "Service restart scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
            // START_STICKY should still trigger restart eventually
        }
    }

    /**
     * Handle BLE restart intent with optional test mode configuration.
     */
    private fun handleRestartBle(intent: Intent) {
        Log.d(TAG, "Received ACTION_RESTART_BLE - restarting BLE interface")
        Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString()}")

        serviceScope.launch {
            try {
                val bridge = managers.bleCoordinator.getBridge()

                // Test mode: Initialize bridge with UUIDs from intent extras
                // This solves the cross-process singleton issue in instrumented tests
                val testServiceUuid = intent.getStringExtra("test_service_uuid")
                Log.d(TAG, "testServiceUuid = $testServiceUuid")

                if (testServiceUuid != null) {
                    val testRxCharUuid = intent.getStringExtra("test_rx_char_uuid")!!
                    val testTxCharUuid = intent.getStringExtra("test_tx_char_uuid")!!
                    val testIdentityCharUuid = intent.getStringExtra("test_identity_char_uuid")!!

                    Log.d(TAG, "Test mode - initializing BLE with UUIDs from intent extras")
                    bridge.start(testServiceUuid, testRxCharUuid, testTxCharUuid, testIdentityCharUuid)
                }

                bridge.restart()
                Log.d(TAG, "BLE restart complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting BLE interface", e)
            }
        }
    }
}
