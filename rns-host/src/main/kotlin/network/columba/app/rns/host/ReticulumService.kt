package network.columba.app.rns.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.host.binder.ReticulumServiceBinder
import network.columba.app.rns.host.di.ServiceModule
import network.columba.app.rns.host.persistence.BackendInitializer
import network.columba.app.rns.host.rnode.KotlinRNodeBridge
import network.columba.app.rns.host.rnode.RNodeOnlineStatusListener
import network.columba.app.rns.host.usb.KotlinUSBBridge
import network.columba.app.rns.host.usb.UsbConnectionListener
import network.columba.app.rns.ipc.RnsBackendServer

/**
 * Background service that hosts the native Reticulum stack.
 * Runs as a foreground service for OOM protection and reliable BLE/socket I/O
 * while the main UI process may come and go.
 *
 * Architecture:
 * - This class is a thin lifecycle shell
 * - All business logic is delegated to specialized managers via ServiceModule
 * - Binder is a plain android.os.Binder liveness handle (no cross-process protocol calls)
 * - State is managed in ServiceState
 */
@AndroidEntryPoint
class ReticulumService : Service() {
    companion object {
        private const val TAG = "ReticulumService"

        // Actions for service control
        const val ACTION_START = "network.columba.app.service.START"
        const val ACTION_STOP = "network.columba.app.service.STOP"
        const val ACTION_RESTART_BLE = "network.columba.app.RESTART_BLE"
        const val ACTION_UPDATE_NOTIFICATION = "network.columba.app.service.UPDATE_NOTIFICATION"
        const val EXTRA_NETWORK_STATUS = "network_status"

        // Grace window for treating ACTION_STOP as a stale redelivery during an
        // Apply & Restart. The original STOP intent travels at most a few seconds
        // (the time between :reticulum dying and Android auto-restarting the FGS),
        // so 5s is plenty of headroom while still failing fast for genuine STOPs.
        private const val STALE_STOP_GRACE_MS = 5_000L
    }

    /**
     * Injected RnsBackend implementation. The flavor-specific HostBackendModule
     * in src/{kotlinBackend,pythonBackend}/ provides the concrete instance —
     * [NativeRnsBackend] on the kotlinBackend flavor, a Chaquopy-backed impl on
     * the pythonBackend flavor (Phase B).
     */
    @Inject lateinit var rnsBackend: RnsBackend

    /**
     * Self-initializer that reads the snapshot UI persisted on its last
     * successful `initialize()` and re-drives the backend through the same
     * path. Lets `:reticulum` come back up under START_STICKY without needing
     * the UI process alive to feed it config.
     */
    @Inject lateinit var backendInitializer: BackendInitializer

    // Coroutine scope for background tasks
    // Uses Dispatchers.Default for CPU-bound work (JSON parsing, orchestration)
    // SupervisorJob ensures child coroutine failures don't cancel the entire service
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Managers container (initialized in onCreate)
    private lateinit var managers: ServiceModule.ServiceManagers

    // Local binder returned from onBind() — liveness handle only, no protocol calls.
    // Retained internally so existing managers (BleCoordinator, NetworkChangeManager
    // callbacks below) can call binder.restartAutoInterface() / announceLxmfDestination() /
    // shutdown() / isInitialized() through the legacy in-process Java surface. A.10
    // routes those calls through `rnsBackend` directly and deletes this property.
    private lateinit var binder: ReticulumServiceBinder

    // Cross-process AIDL stub. Lazily constructed on the first onBind() so we
    // don't pay the construction cost (and the rnsBackend dependency wiring) in
    // the rare case the service runs only via startService (no bindService caller).
    private var aidlServer: RnsBackendServer? = null

    // Process-start timestamp used to detect stale ACTION_STOP redeliveries during
    // an Apply & Restart cycle. See onStartCommand's ACTION_STOP branch for the
    // full rationale. SystemClock.elapsedRealtime is monotonic and includes
    // sleep, so this is robust to wall-clock changes.
    private val processStartElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // A.10: Confirm the local backend resolved in `:reticulum`. Touching
        // the @Inject field triggered eager Hilt construction during
        // super.onCreate() — the log line just makes that visible in logcat
        // so on-device verification can grep for it. The DEBUG-only ctor
        // assertion in ChaquopyRnsBackend / NativeRnsBackend already throws
        // if the construction landed in the wrong process; this log is the
        // success signal.
        Log.i(
            TAG,
            "Pre-warmed RnsBackend in :reticulum pid=${android.os.Process.myPid()} " +
                "-> ${rnsBackend::class.simpleName}",
        )

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

        // Wire RNode disconnect notifications. ServiceNotificationManager.
        // updateRNodeStatus already owns the heads-up alert (CHANNEL_ID_RNODE,
        // IMPORTANCE_HIGH) and the foreground-notification text suffix; we
        // just need to fire it on the right signals. v0.10.x wired this in
        // ReticulumServiceBinder.kt:235-243; dual-build never carried it
        // over, so RNode disconnect was silent across both backends.
        //
        // BLE / Classic path: ColumbaRNodeInterface._set_online (Python)
        // calls kotlin_bridge.notifyOnlineStatusChanged on every connect /
        // disconnect, which fans out to our listener here.
        //
        // USB path: KotlinUSBBridge owns a BroadcastReceiver for
        // ACTION_USB_DEVICE_DETACHED (already exists) and fires
        // UsbConnectionListener callbacks. We track hasActiveUsbRNode so we
        // only post the notification for disconnects that follow a real
        // RNode-mode USB attach — onUsbConnected only fires for SUPPORTED_VIDS
        // (FTDI / CP210x / etc.) so a non-RNode charger pull doesn't trigger
        // a spurious notification. Interface name is the placeholder
        // "RNode (USB)" rather than the configured InterfaceConfig name —
        // querying the interfaces DB from here would require a cross-module
        // dep we don't currently have, and the user only ever has one USB
        // RNode plugged in at a time on a phone in practice.
        KotlinRNodeBridge.getInstance(this).addOnlineStatusListener(
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean, interfaceName: String) {
                    Log.d(TAG, "RNode online status changed: [$interfaceName] online=$isOnline")
                    managers.notificationManager.updateRNodeStatus(isOnline, interfaceName)
                }
            },
        )
        val usbBridge = KotlinUSBBridge.getInstance(this)
        // Seed hasActiveUsbRNode from devices already attached when the
        // service starts. Without this, an RNode plugged in BEFORE the
        // service starts (typical: user plugs cable, then launches app)
        // gets no notification on unplug because ACTION_USB_DEVICE_ATTACHED
        // fired before our BroadcastReceiver was registered, leaving the
        // flag false and the disconnect path guarded out. Verified on Fold
        // tonight: "USB device disconnected unexpectedly: 1002" with
        // connectedDeviceId=null and no preceding onUsbConnected.
        var hasActiveUsbRNode = usbBridge.getConnectedUsbDevices().isNotEmpty()
        usbBridge.addConnectionListener(
            object : UsbConnectionListener {
                override fun onUsbConnected(deviceId: Int) {
                    // Supported-VID USB device attached. Mark that we have a
                    // USB RNode candidate and dismiss any stale disconnect
                    // notification from a previous cable pull.
                    hasActiveUsbRNode = true
                    managers.notificationManager.updateRNodeStatus(true, "RNode (USB)")
                }

                override fun onUsbDisconnected(deviceId: Int) {
                    // Only post the notification if we previously saw a
                    // supported-VID attach in this process lifetime (either
                    // via onUsbConnected mid-flight, or via the
                    // getConnectedUsbDevices() seed at startup); avoids
                    // spurious notifications for non-RNode USB devices.
                    if (hasActiveUsbRNode) {
                        hasActiveUsbRNode = false
                        Log.d(TAG, "USB RNode detached: deviceId=$deviceId")
                        managers.notificationManager.updateRNodeStatus(false, "RNode (USB)")
                    }
                }

                // Permission grants and denials are surfaced by KotlinUSBBridge
                // for the USB-driver layer but the service has no notification
                // surface tied to them — RNode detach is the load-bearing event.
                override fun onUsbPermissionGranted(deviceId: Int) = Unit

                override fun onUsbPermissionDenied(deviceId: Int) = Unit
            },
        )

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

        // A.10 follow-up: self-init from snapshot if UI isn't around to drive
        // initialize() (OOM restart, force-stop recovery). No-op on first run
        // (no snapshot yet). Idempotent if UI races us — `runtime.start()`
        // already early-returns when running.
        //
        // Apply-in-progress gate: when InterfaceConfigManager is mid-restart
        // (is_applying_config=true), the on-disk snapshot is STALE relative to
        // what the UI is about to push via initialize(). Running the snapshot
        // self-init here would (a) bring the stack up on the old interface
        // list, and (b) race with the UI's incoming initialize() such that
        // PythonRnsRuntime.start sees running=true on the second call and
        // silently drops the FRESH config. Skip self-init and let the UI drive.
        val isApplyingConfig =
            getSharedPreferences("columba_prefs", MODE_PRIVATE)
                .getBoolean("is_applying_config", false)
        if (isApplyingConfig) {
            Log.i(TAG, "Skipping snapshot self-init — apply-in-progress; UI will drive initialize()")
        } else {
            serviceScope.launch {
                backendInitializer.initializeFromSnapshot(rnsBackend)
            }
        }
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
            return if (isUserShutdownRestart) {
                Log.i(TAG, "User shutdown flag set - stopping service instead of restarting")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            } else {
                Log.w(TAG, "onStartCommand called before onCreate completed - will retry after init")
                START_STICKY
            }
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
                // Apply-in-progress gate: when the UI is mid-apply, the ACTION_STOP we
                // ourselves sent in InterfaceConfigManager.applyInterfaceChanges Step 4
                // targets the OLD :reticulum pid. Android, however, has no way to drop
                // an in-flight startForegroundService intent if the receiver dies first
                // — the intent gets requeued onto whichever :reticulum process spawns
                // next (via START_STICKY auto-restart of the crashed service). That
                // stale STOP then System.exits the freshly-spawned process before it
                // can bind for the UI's pending initialize(), surfacing as the
                // "Failed to initialize Reticulum: Backend not ready" toast in the
                // Apply & Restart dialog (punch-list item 10).
                //
                // The fix: when is_applying_config is set on a NEWLY-spawned service
                // (process is younger than the stale STOP could possibly target), treat
                // ACTION_STOP as a redelivery and consume the startId without exiting.
                // The UI driving the apply will send its own ACTION_START + initialize()
                // shortly. The genuine non-apply STOP path (user-initiated shutdown,
                // user-toggled service kill) is unaffected because is_applying_config
                // is false in those scenarios.
                val isApplyingConfig =
                    getSharedPreferences("columba_prefs", MODE_PRIVATE)
                        .getBoolean("is_applying_config", false)
                val processAgeMs = android.os.SystemClock.elapsedRealtime() - processStartElapsedRealtimeMs
                if (isApplyingConfig && processAgeMs < STALE_STOP_GRACE_MS) {
                    Log.w(
                        TAG,
                        "Ignoring ACTION_STOP: apply-in-progress and process is only " +
                            "${processAgeMs}ms old (likely a redelivery of the STOP that " +
                            "killed the prior :reticulum pid during Apply & Restart)",
                    )
                    // Consume the startId so Android doesn't see an outstanding start
                    // request when we eventually do exit. We deliberately do NOT call
                    // System.exit — the snapshot self-init in onCreate is already in
                    // flight and the UI will follow up with its own initialize.
                    return START_STICKY
                }

                // Shutdown and stop service
                Log.d(TAG, "Received ACTION_STOP - forcing process exit")

                // Remove notification before anything else so System.exit(0) can't leave a
                // lingering entry in the shade.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

                // Stop BLE immediately on the Main thread before process exit, since
                // System.exit(0) will kill the process before any async cleanup runs.
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

        // A.8: Return the cross-process AIDL stub instead of the legacy liveness
        // binder. The UI process wraps this binder in `RnsBackendClient.connect()`
        // and reaches the backend through the typed RnsBackend / sub-interface
        // surface. The legacy `binder` field above is still constructed (in
        // onCreate) because in-process service managers (NetworkChangeManager,
        // BleCoordinator) call its `restartAutoInterface` / `announceLxmfDestination`
        // helpers via direct Java calls — A.10 rewires those to use `rnsBackend`.
        return aidlServer ?: RnsBackendServer(impl = rnsBackend, scope = serviceScope).also {
            aidlServer = it
        }
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
