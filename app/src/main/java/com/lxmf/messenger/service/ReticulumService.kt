package com.lxmf.messenger.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.lxmf.messenger.service.binder.ReticulumServiceBinder
import com.lxmf.messenger.service.di.ServiceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        const val ACTION_START = "com.lxmf.messenger.service.START"
        const val ACTION_STOP = "com.lxmf.messenger.service.STOP"
        const val ACTION_RESTART_BLE = "com.lxmf.messenger.RESTART_BLE"
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
        managers = ServiceModule.createManagers(
            context = this,
            scope = serviceScope,
            onStaleHeartbeat = {
                Log.e(TAG, "Python heartbeat stale - triggering service restart")
                triggerServiceRestart()
            },
            onNetworkChanged = {
                // Trigger LXMF announce when network changes so peers can discover us
                Log.d(TAG, "Network changed - triggering LXMF announce")
                if (::binder.isInitialized) {
                    try {
                        binder.announceLxmfDestination()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to announce on network change", e)
                    }
                }
            },
        )

        // Create notification channel
        managers.notificationManager.createNotificationChannel()

        // Create binder with callbacks
        binder =
            ServiceModule.createBinder(
                context = this,
                managers = managers,
                scope = serviceScope,
                onInitialized = {
                    Log.d(TAG, "Reticulum initialization complete")
                },
                onShutdown = {
                    Log.d(TAG, "Reticulum shutdown complete")
                },
                onForceExit = {
                    Log.i(TAG, "Exiting process now...")
                    System.exit(0)
                },
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "Service started")

        // Guard against calls before onCreate completes
        if (!::managers.isInitialized || !::binder.isInitialized) {
            Log.e(TAG, "onStartCommand called before onCreate completed - ignoring")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                // Start foreground service with notification
                managers.notificationManager.startForeground(this)
            }
            ACTION_STOP -> {
                // Shutdown and stop service
                Log.d(TAG, "Received ACTION_STOP - forcing process exit")
                binder.shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

                // Force process exit to ensure service truly stops even with active bindings
                // This is safe because we're in a separate :reticulum process
                // Android will automatically restart if needed due to START_STICKY
                System.exit(0)
            }
            ACTION_RESTART_BLE -> {
                // Restart BLE interface after permissions granted
                handleRestartBle(intent)
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

        // Mark service as bound (broadcaster will notify readiness callback)
        managers.broadcaster.setServiceBound(true)

        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Service unbound")
        // Return true to allow rebinding
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Clean up all resources (if initialized)
        if (::managers.isInitialized) {
            managers.networkChangeManager.stop()
            managers.healthCheckManager.stop()
            managers.pollingManager.stopAll()
            managers.lockManager.releaseAll()
            managers.broadcaster.kill()
        }
        serviceScope.cancel()

        // Schedule explicit service restart (Sideband-inspired auto-restart)
        // This ensures service comes back up even if Android delays START_STICKY restart
        scheduleServiceRestart()
    }

    /**
     * Trigger service restart via shutdown and restart.
     * Called when Python heartbeat becomes stale (process may be hung).
     */
    private fun triggerServiceRestart() {
        Log.w(TAG, "Triggering service restart due to stale heartbeat")

        // Clean up current state
        if (::binder.isInitialized) {
            binder.shutdown()
        }

        // Force process restart to get a clean Python environment
        scheduleServiceRestart()
        System.exit(1) // Non-zero exit to indicate abnormal termination
    }

    /**
     * Schedule explicit service restart.
     * Inspired by Sideband's auto-restart pattern in PythonService.java.
     */
    private fun scheduleServiceRestart() {
        try {
            val restartIntent = Intent(applicationContext, ReticulumService::class.java).apply {
                action = ACTION_START
            }
            // Start foreground service - Android will handle queueing if process is dying
            startForegroundService(restartIntent)
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
