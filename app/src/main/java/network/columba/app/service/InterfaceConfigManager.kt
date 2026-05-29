package network.columba.app.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.LogLevel
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.host.ReticulumService
import network.columba.app.service.manager.InterfaceTransportObserver
import network.columba.app.rns.host.manager.filterByTransport
import network.columba.app.rns.host.persistence.ReticulumConfigSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for applying interface configuration changes to the running Reticulum instance.
 *
 * Handles the complete lifecycle of restarting Reticulum with new configuration:
 * 1. Stop message collector and managers
 * 2. Load enabled interfaces from database
 * 3. Shutdown current Reticulum instance
 * 4. Regenerate config file
 * 5. Initialize Reticulum with new config
 * 6. Restore peer identities
 * 7. Restart message collector and managers (PropagationNodeManager, AutoAnnounceManager, etc.)
 */
@Singleton
@Suppress("LongParameterList") // Dependencies required for full restart lifecycle
class InterfaceConfigManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val rnsCore: RnsCore,
        private val rnsTransportAdmin: RnsTransportAdmin,
        private val interfaceRepository: InterfaceRepository,
        private val identityRepository: IdentityRepository,
        private val identityKeyProvider: network.columba.app.data.crypto.IdentityKeyProvider,
        private val conversationRepository: ConversationRepository,
        private val messageCollector: MessageCollector,
        private val database: ColumbaDatabase,
        private val settingsRepository: SettingsRepository,
        private val autoAnnounceManager: AutoAnnounceManager,
        private val identityResolutionManager: IdentityResolutionManager,
        private val propagationNodeManager: PropagationNodeManager,
        private val transportObserver: InterfaceTransportObserver,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "InterfaceConfigManager"
            private const val APPLY_CHANGES_TIMEOUT_MS = 60_000L
        }

        /**
         * Apply pending interface configuration changes.
         *
         * This RESTARTS THE SERVICE PROCESS to ensure clean port release.
         * The process typically takes 8-12 seconds.
         *
         * @param onServiceReady Called after Reticulum is initialized (Step 9) but before
         *   post-init bookkeeping (identity restore, manager restart). Callers can use this
         *   to clear UI loading state early since the service is usable at this point.
         * @return Result indicating success or failure with error details
         */
        @Suppress("CyclomaticComplexMethod", "LongMethod") // Complex but necessary service restart orchestration
        suspend fun applyInterfaceChanges(onServiceReady: (() -> Unit)? = null): Result<Unit> {
            // Apply flag lifecycle: set in Step 3, cleared HERE in finally. Keeping
            // the flag set across the whole apply (not just until initialize returns)
            // matters for two cross-process races:
            //  • ColumbaApplication.onCreate in the UI process — sees a stale flag
            //    on the next cold start and clears it via isStaleFlag(); a clean
            //    apply just lets this finally cover the exit.
            //  • ReticulumService.onStartCommand in :reticulum — when an Android
            //    auto-restart redelivers an in-flight ACTION_STOP onto the
            //    freshly-spawned process, the gate added in punch-list item 10
            //    consults this same flag to decide whether to ignore the stale
            //    STOP (yes, mid-apply) or honour it (no, real user shutdown).
            //    Clearing the flag in initialize.onFailure used to drop this gate
            //    just in time for the redelivered STOP to kill every restart in
            //    the cascade.
            val clearApplyFlag: () -> Unit = {
                context
                    .getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_applying_config", false)
                    .commit()
            }
            return runCatching {
                withTimeout(APPLY_CHANGES_TIMEOUT_MS) {
                    Log.i(TAG, "==== Applying Interface Configuration Changes (Service Restart) ====")

                    // Step 1: Stop message collector
                    Log.d(TAG, "Step 1: Stopping message collector...")
                    messageCollector.stopCollecting()
                    Log.d(TAG, "✓ Message collector stopped")

                    // Step 1b: Stop managers to prepare for restart
                    Log.d(TAG, "Step 1b: Stopping managers...")
                    autoAnnounceManager.stop()
                    identityResolutionManager.stop()
                    propagationNodeManager.stop()
                    Log.d(TAG, "✓ Managers stopped")

                    // Step 2: Load interfaces BEFORE stopping service
                    Log.d(TAG, "Step 2: Loading interfaces from database...")
                    val rawEnabledInterfaces = interfaceRepository.enabledInterfaces.first()
                    val transport = transportObserver.currentTransport()
                    val enabledInterfaces = filterByTransport(rawEnabledInterfaces, transport)
                    Log.d(
                        TAG,
                        "✓ Loaded ${rawEnabledInterfaces.size} enabled interface(s); " +
                            "${enabledInterfaces.size} active for transport=$transport",
                    )

                    // Step 3: Set flag to prevent ColumbaApplication from auto-initializing on service restart
                    // CRITICAL: Use commit() not apply() to ensure flag is written to disk BEFORE service starts
                    // Service runs in separate process and needs to read this from disk
                    Log.d(TAG, "Step 3: Setting config apply flag (synchronous write)...")
                    context
                        .getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_applying_config", true)
                        .commit() // Synchronous write - blocks until written to disk
                    Log.d(TAG, "✓ Flag written to disk - service process will skip auto-init")

                    // Step 4: Shutdown + unbind + send ACTION_STOP in quick succession
                    // We just need Python to start teardown (2s), then immediately unbind and
                    // send ACTION_STOP to trigger System.exit(0) for clean JVM shutdown hooks.
                    Log.d(TAG, "Step 4: Shutting down ReticulumService process...")
                    val reticulumProcessName = "${context.packageName}:reticulum"
                    try {
                        try {
                            withTimeout(2000L) {
                                rnsCore.shutdown().getOrNull()
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w(TAG, "Shutdown timed out after 2s, proceeding with unbind", e)
                        }
                        // unbindService() was a legacy no-op on the kotlin backend
                    } catch (e: Exception) {
                        Log.w(TAG, "Error during service shutdown", e)
                    }

                    // Send ACTION_STOP to let the service shut down gracefully.
                    // This triggers System.exit(0) inside the service process, which
                    // allows JVM shutdown hooks (including Chaquopy's Python VM teardown)
                    // to run — preventing SIGSEGV in PyGILState_Ensure.
                    var reticulumProcessFound = false
                    try {
                        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val runningProcesses = activityManager.runningAppProcesses.orEmpty()
                        val reticulumProcess = runningProcesses.find { it.processName == reticulumProcessName }

                        if (reticulumProcess != null) {
                            reticulumProcessFound = true
                            Log.d(TAG, "Found reticulum process PID ${reticulumProcess.pid}, sending ACTION_STOP intent...")
                            val stopIntent =
                                Intent(context, ReticulumService::class.java).apply {
                                    action = ReticulumService.ACTION_STOP
                                }
                            ContextCompat.startForegroundService(context, stopIntent)
                        } else {
                            Log.d(TAG, "Service process not found (may have already stopped)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not send ACTION_STOP to service: ${e.message}")
                    }

                    // Step 5: Verify process is dead (poll up to 2 seconds)
                    // This is CRITICAL - if process survives, old identity keeps running
                    if (reticulumProcessFound) {
                        Log.d(TAG, "Step 5: Verifying service process terminated...")
                        val maxVerifyAttempts = 8
                        var processDied = false
                        for (attempt in 1..maxVerifyAttempts) {
                            delay(250)
                            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            val stillRunning =
                                activityManager.runningAppProcesses
                                    .orEmpty()
                                    .any { it.processName == reticulumProcessName }
                            if (!stillRunning) {
                                Log.d(TAG, "✓ Service process confirmed dead after ${attempt * 250}ms")
                                processDied = true
                                break
                            }
                        }
                        if (!processDied) {
                            Log.w(TAG, "Service process didn't exit gracefully after ${maxVerifyAttempts * 250}ms, sending SIGKILL...")
                            try {
                                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                val proc = am.runningAppProcesses.orEmpty().find { it.processName == reticulumProcessName }
                                if (proc != null) {
                                    Process.killProcess(proc.pid)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "SIGKILL fallback failed: ${e.message}")
                            }
                        }
                    }

                    // Step 6: Wait for ports to be fully released by OS
                    // TCP ports in TIME_WAIT on Android release within ~100ms after process death
                    Log.d(TAG, "Step 6: Waiting for ports to release (1000ms)...")
                    delay(1000)
                    Log.d(TAG, "✓ Ports should be released")

                    // Step 7: Start service again (fresh process, no port conflicts)
                    // Clear user shutdown flag so the service starts normally
                    context
                        .getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_user_shutdown", false)
                        .commit()
                    Log.d(TAG, "Step 7: Starting ReticulumService in fresh process...")
                    val startIntent =
                        Intent(context, ReticulumService::class.java).apply {
                            action = ReticulumService.ACTION_START
                        }
                    ContextCompat.startForegroundService(context, startIntent)
                    Log.d(TAG, "✓ Service start initiated")

                    // Step 8: Wait for the new :reticulum process to be live.
                    // The Hilt-bound BoundRnsBackend always wraps its connection in a
                    // StateFlow<RnsBackend?> that emits null on onServiceDisconnected
                    // (post-punch-list-10 fix), so the next initialize() call on the
                    // wrapped RnsCore would suspend until rebind anyway — BUT, between
                    // Step 4's ACTION_STOP and Android's auto-restart from the crash,
                    // the previous binder reference can linger as the StateFlow's most
                    // recent value for up to a few tens of milliseconds. Poll for the
                    // fresh pid before invoking initialize() so the AIDL call lands on
                    // the new process's stub instead of the dead one.
                    Log.d(TAG, "Step 8: Waiting for new :reticulum process to spawn...")
                    val maxBindAttempts = 24 // ~6s at 250ms intervals — covers Android's 1s + 4s service-restart backoff
                    var newProcessUp = false
                    for (attempt in 1..maxBindAttempts) {
                        delay(250)
                        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val freshProcess =
                            activityManager.runningAppProcesses
                                .orEmpty()
                                .find { it.processName == reticulumProcessName }
                        if (freshProcess != null) {
                            Log.d(TAG, "✓ New :reticulum process spawned with PID ${freshProcess.pid} after ${attempt * 250}ms")
                            newProcessUp = true
                            break
                        }
                    }
                    if (!newProcessUp) {
                        Log.w(TAG, "Timed out waiting for new :reticulum process to spawn — proceeding with initialize() anyway")
                    }

                    // Step 9: Initialize Reticulum with new config
                    Log.d(TAG, "Step 9: Initializing Reticulum with new configuration...")

                    // Load active identity and decrypt its key in memory — same pattern
                    // ColumbaApplication uses on cold start. Writing the raw key to
                    // files/reticulum/identity_<hash> here would recreate the plaintext
                    // file on every interface toggle, defeating the on-disk scrub.
                    //
                    // The is_applying_config flag was set synchronously in Step 3 to keep
                    // ColumbaApplication.onCreate from racing us to reinitialize. The
                    // outer try/finally clears it on every exit path (success, throw,
                    // timeout), so individual early-return branches just throw.
                    val activeIdentity = identityRepository.getActiveIdentitySync()
                    if (activeIdentity != null &&
                        runCatching {
                            identityRepository.requiresPassword(activeIdentity.identityHash)
                        }.getOrDefault(true)
                    ) {
                        Log.w(
                            TAG,
                            "Active identity ${activeIdentity.identityHash.take(8)}... is password-protected " +
                                "(or password status unreadable) - skipping apply until unlock",
                        )
                        error("Active identity requires unlock before interface config can apply")
                    }
                    val deliveryKey =
                        if (activeIdentity != null) {
                            identityKeyProvider
                                .getDecryptedKeyData(activeIdentity.identityHash)
                                .onFailure { Log.e(TAG, "Could not decrypt identity key: $it") }
                                .getOrNull()
                        } else {
                            null
                        }
                    if (activeIdentity != null && deliveryKey == null) {
                        Log.e(
                            TAG,
                            "Active identity ${activeIdentity.identityHash.take(8)}... present but key decryption " +
                                "returned null - refusing to restart with an ephemeral identity",
                        )
                        error("Active identity key unavailable; aborting apply")
                    }
                    val displayName = activeIdentity?.displayName
                    Log.d(TAG, "Active identity: ${if (activeIdentity != null) "set (in-memory key)" else "none"}")

                    // Load shared instance preferences
                    val preferOwnInstance = settingsRepository.preferOwnInstanceFlow.first()
                    Log.d(TAG, "Prefer own instance: $preferOwnInstance")

                    // Load RPC key for shared instance authentication
                    val rpcKey = settingsRepository.rpcKeyFlow.first()
                    if (rpcKey != null) {
                        Log.d(TAG, "RPC key configured for shared instance auth")
                    }

                    // Load transport node setting
                    val transportNodeEnabled = settingsRepository.getTransportNodeEnabled()
                    Log.d(TAG, "Transport node enabled: $transportNodeEnabled")

                    // Load battery profile
                    val batteryProfile = settingsRepository.getBatteryProfile()
                    Log.d(TAG, "Battery profile: $batteryProfile")

                    // Load discovery settings
                    val discoverInterfaces = settingsRepository.getDiscoverInterfacesEnabled()
                    val savedAutoconnect = settingsRepository.getAutoconnectDiscoveredCount()
                    // Coerce -1 (never configured sentinel) to 0
                    val autoconnectDiscoveredCount = if (savedAutoconnect >= 0) savedAutoconnect else 0
                    val autoconnectIfacOnly = settingsRepository.getAutoconnectIfacOnly()
                    val shareInstanceHosting = settingsRepository.getShareInstanceHostingEnabled()
                    Log.d(
                        TAG,
                        "Discovery settings: discover=$discoverInterfaces, autoconnect=$autoconnectDiscoveredCount " +
                            "(saved=$savedAutoconnect), ifacOnly=$autoconnectIfacOnly, " +
                            "shareInstanceHosting=$shareInstanceHosting",
                    )

                    val config =
                        ReticulumConfig(
                            storagePath = context.filesDir.absolutePath + "/reticulum",
                            enabledInterfaces = enabledInterfaces,
                            deliveryIdentityKey = deliveryKey,
                            displayName = displayName,
                            logLevel = LogLevel.DEBUG,
                            allowAnonymous = false,
                            batteryProfile = batteryProfile,
                            preferOwnInstance = preferOwnInstance,
                            rpcKey = rpcKey,
                            enableTransport = transportNodeEnabled,
                            shareInstanceHosting = shareInstanceHosting,
                            discoverInterfaces = discoverInterfaces,
                            autoconnectDiscoveredInterfaces = autoconnectDiscoveredCount,
                            autoconnectIfacOnly = autoconnectIfacOnly,
                        )

                    rnsCore
                        .initialize(config)
                        .onSuccess {
                            Log.d(TAG, "✓ Reticulum initialized successfully")

                            // Refresh the persisted snapshot with the config we just
                            // applied. Without this, the snapshot only ever reflects
                            // ColumbaApplication.onCreate's cold-start config — so any
                            // field changed via Apply & Restart (e.g. shareInstanceHosting)
                            // silently reverts the next time the OS reaps and START_STICKY-
                            // restarts the :reticulum process from the stale snapshot.
                            ReticulumConfigSnapshot.write(
                                context = context,
                                config = config,
                                identityHashHex = activeIdentity?.identityHash,
                            )
                        }.onFailure { error ->
                            // The outer finally clears is_applying_config — keep the flag set
                            // here so any stale ACTION_STOP redelivery during the in-flight
                            // service restart (Step 4 → Step 7 window) is still recognised
                            // as an apply-time redelivery and ignored in
                            // ReticulumService.onStartCommand.
                            Log.e(TAG, "Failed to initialize Reticulum", error)
                            throw Exception("Failed to initialize Reticulum: ${error.message}", error)
                        }

                    // Signal caller that service is usable (before post-init bookkeeping)
                    onServiceReady?.invoke()
                    Unit // Hint inference: function return is Result<Unit>.
                } // withTimeout — wraps ONLY the critical-path RNS restart (Steps 1-9).
                // Post-init bookkeeping (Steps 10-12) runs outside the timeout: by
                // this point RNS is up and the user-facing apply has succeeded.
                // Identity restoration on devices with thousands of stored peers
                // can take 45-60s on its own, which used to trip the apply-wide
                // 60s timeout and incorrectly mark the apply as failed even though
                // RNS was healthy. The is_applying_config flag (cleared in the
                // outer .also) stays set across these steps too, so the stale-
                // STOP gate in ReticulumService keeps fielding any redelivered
                // STOP cascades from the Step-4 shutdown.

                // Step 10: Restore peer identities (uses batched loading to prevent OOM)
                Log.d(TAG, "Step 10: Batch restoring peer identities...")
                try {
                    restorePeerIdentitiesInBatches()
                } catch (e: Exception) {
                    Log.w(TAG, "Error batch restoring peer identities", e)
                    // Not fatal - continue
                }

                // Step 10b: Restore announce identities (uses batched loading to prevent OOM)
                Log.d(TAG, "Step 10b: Batch restoring announce identities...")
                try {
                    restoreAnnounceIdentitiesInBatches()
                } catch (e: Exception) {
                    Log.w(TAG, "Error batch restoring announce identities", e)
                    // Not fatal - continue
                }

                // Step 11: Restart message collector
                Log.d(TAG, "Step 11: Starting message collector...")
                messageCollector.startCollecting()
                Log.d(TAG, "✓ Message collector started")

                // Step 12: Restart managers (same as ColumbaApplication.onCreate)
                Log.d(TAG, "Step 12: Restarting managers...")
                autoAnnounceManager.start()
                identityResolutionManager.start(applicationScope)
                propagationNodeManager.start()
                Log.d(TAG, "✓ AutoAnnounceManager, IdentityResolutionManager, PropagationNodeManager started")

                Log.i(TAG, "==== Configuration Changes Applied Successfully ====")
                Unit // Hint inference: function return is Result<Unit>, not Result<Int> from Log.i.
            }.also {
                // Always clear the apply flag — even when initialize() failed or the
                // withTimeout fired. Keeping it set across the whole apply (Step 3 → here)
                // means ReticulumService.onStartCommand's stale-STOP gate stays live
                // until the apply has actually finished one way or the other, preventing
                // the redelivered ACTION_STOP cascade that turned into "Backend not ready".
                Log.d(TAG, "Clearing is_applying_config flag (apply complete: success=${it.isSuccess})")
                clearApplyFlag()
            }
        }

        /**
         * Check if Reticulum is currently running and ready.
         */
        fun isReticulumRunning(): Boolean =
            try {
                val status = rnsCore.networkStatus.value
                status.toString().contains("RUNNING") || status.toString().contains("READY")
            } catch (e: Exception) {
                false
            }

        /**
         * Mark that there are pending interface changes that need to be applied.
         * Used when interfaces are modified outside of InterfaceManagementViewModel
         * (e.g., from RNode wizard).
         */
        fun setPendingChanges(hasPending: Boolean) {
            context
                .getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("has_pending_interface_changes", hasPending)
                .apply()
        }

        /**
         * Check if there are pending interface changes and clear the flag.
         * @return true if there were pending changes, false otherwise
         */
        fun checkAndClearPendingChanges(): Boolean {
            val prefs = context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
            val hasPending = prefs.getBoolean("has_pending_interface_changes", false)
            if (hasPending) {
                prefs.edit().putBoolean("has_pending_interface_changes", false).apply()
            }
            return hasPending
        }

        /**
         * Get the current RSSI of the active RNode BLE connection.
         *
         * @return RSSI in dBm, or -100 if not connected or not available
         */
        fun getRNodeRssi(): Int = rnsTransportAdmin.getRNodeRssi()

        /**
         * Generic batched restoration to prevent OOM when loading large tables across the
         * Chaquopy bridge. Fetches records in pages and yields between batches so the GC
         * can reclaim the previous batch's bridge objects.
         *
         * Batch size is kept at 200 (not 500) to reduce binder pressure: each batch
         * serializes to ~20-24KB of JSON over binder IPC. Smaller batches with brief
         * delays between them prevent saturating the 1MB binder buffer, which otherwise
         * causes cascading DeadObjectException crashes in Room's multi-instance
         * invalidation. See: GitHub #647, COLUMBA-14.
         */
        private suspend fun <T> restoreInBatches(
            label: String,
            batchSize: Int = 200,
            fetchBatch: suspend (limit: Int, offset: Int) -> List<T>,
            processBatch: suspend (List<T>) -> Result<Int>,
        ): Int {
            var offset = 0
            var totalRestored = 0

            Log.d(TAG, "Starting batched $label restoration (batch size: $batchSize)")

            var batch =
                try {
                    fetchBatch(batchSize, offset)
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching initial $label batch", e)
                    emptyList()
                }

            while (batch.isNotEmpty()) {
                Log.d(TAG, "Processing batch ${offset / batchSize + 1}: ${batch.size} $label (offset $offset)")

                val batchCount = batch.size
                try {
                    processBatch(batch)
                        .onSuccess { count ->
                            totalRestored += count
                            Log.d(TAG, "✓ Restored $count $label from batch (total: $totalRestored)")
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to restore $label batch at offset $offset: ${error.message}", error)
                        }

                    offset += batchSize
                    batch =
                        if (batchCount < batchSize) {
                            emptyList()
                        } else {
                            // Brief delay lets the binder buffer drain between batches,
                            // preventing buffer saturation on devices with slow Python execution.
                            kotlinx.coroutines.delay(100)
                            yield() // Let GC reclaim previous batch's bridge objects
                            fetchBatch(batchSize, offset)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing $label batch at offset $offset", e)
                    batch = emptyList()
                }
            }

            Log.d(TAG, "✓ Batch restore complete: $totalRestored $label restored")
            return totalRestored
        }

        private suspend fun restorePeerIdentitiesInBatches() {
            restoreInBatches(
                label = "peer identities",
                fetchBatch = { limit, offset -> conversationRepository.getPeerIdentitiesBatch(limit, offset) },
                processBatch = { batch -> rnsCore.restorePeerIdentities(batch) },
            )
        }

        private suspend fun restoreAnnounceIdentitiesInBatches() {
            restoreInBatches(
                label = "announce identities",
                fetchBatch = { limit, offset -> database.announceDao().getAnnouncesBatch(limit, offset) },
                processBatch = { batch ->
                    val identities = batch.map { it.destinationHash to it.publicKey }
                    rnsCore.restoreAnnounceIdentities(identities)
                },
            )
        }
    }
