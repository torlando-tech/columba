package com.lxmf.messenger.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.LogLevel
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        private val reticulumProtocol: ReticulumProtocol,
        private val interfaceRepository: InterfaceRepository,
        private val identityRepository: IdentityRepository,
        private val conversationRepository: ConversationRepository,
        private val messageCollector: MessageCollector,
        private val database: ColumbaDatabase,
        private val settingsRepository: SettingsRepository,
        private val autoAnnounceManager: AutoAnnounceManager,
        private val identityResolutionManager: IdentityResolutionManager,
        private val propagationNodeManager: PropagationNodeManager,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "InterfaceConfigManager"
        }

        /**
         * Apply pending interface configuration changes.
         *
         * This RESTARTS THE SERVICE PROCESS to ensure clean port release.
         * The process typically takes 8-12 seconds.
         *
         * @return Result indicating success or failure with error details
         */
        @Suppress("CyclomaticComplexMethod", "LongMethod") // Complex but necessary service restart orchestration
        suspend fun applyInterfaceChanges(): Result<Unit> {
            return runCatching {
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
                val enabledInterfaces = interfaceRepository.enabledInterfaces.first()
                Log.d(TAG, "✓ Loaded ${enabledInterfaces.size} enabled interface(s)")

                // Step 3: Set flag to prevent ColumbaApplication from auto-initializing on service restart
                // CRITICAL: Use commit() not apply() to ensure flag is written to disk BEFORE service starts
                // Service runs in separate process and needs to read this from disk
                Log.d(TAG, "Step 3: Setting config apply flag (synchronous write)...")
                context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_applying_config", true)
                    .commit() // Synchronous write - blocks until written to disk
                Log.d(TAG, "✓ Flag written to disk - service process will skip auto-init")

                // Step 4: Force service process to exit
                // We use forceExit() via IPC instead of stopService() because:
                // - stopService() doesn't work on bound services (Android ignores it)
                // - forceExit() triggers System.exit(0) inside the service process
                // - This guarantees the process dies and ports are released
                Log.d(TAG, "Step 4: Forcing ReticulumService process to exit...")
                if (reticulumProtocol is ServiceReticulumProtocol) {
                    try {
                        // Call forceExit() via IPC - this will kill the service process
                        val serviceConnection = (reticulumProtocol as ServiceReticulumProtocol)
                        // We need to access the service directly
                        // For now, let's trigger via unbind which will at least disconnect us
                        reticulumProtocol.shutdown().getOrNull() // Try to shutdown (has internal polling)
                        reticulumProtocol.unbindService() // Unbind our connection
                    } catch (e: Exception) {
                        Log.w(TAG, "Error during service shutdown: ${e.message}")
                    }
                }

                // Now use ActivityManager to force kill the process
                var reticulumProcessPid: Int? = null
                val reticulumProcessName = "${context.packageName}:reticulum"
                try {
                    val activityManager = context.getSystemService(android.app.Activity.ACTIVITY_SERVICE) as ActivityManager
                    val runningProcesses = activityManager.runningAppProcesses.orEmpty()
                    val reticulumProcess = runningProcesses.find { it.processName == reticulumProcessName }

                    if (reticulumProcess != null) {
                        reticulumProcessPid = reticulumProcess.pid
                        Log.d(TAG, "Found reticulum process PID $reticulumProcessPid, killing it...")
                        Process.killProcess(reticulumProcess.pid)
                    } else {
                        Log.d(TAG, "Service process not found (may have already stopped)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not kill process via ActivityManager: ${e.message}")
                }

                // Step 5: Verify process is dead (poll up to 5 seconds)
                // This is CRITICAL - if process survives, old identity keeps running
                if (reticulumProcessPid != null) {
                    Log.d(TAG, "Step 5: Verifying service process terminated...")
                    var verifyAttempts = 0
                    val maxVerifyAttempts = 10
                    while (verifyAttempts < maxVerifyAttempts) {
                        delay(500)
                        verifyAttempts++

                        val activityManager = context.getSystemService(android.app.Activity.ACTIVITY_SERVICE) as ActivityManager
                        val runningProcesses = activityManager.runningAppProcesses.orEmpty()
                        val stillRunning = runningProcesses.any { it.processName == reticulumProcessName }

                        if (!stillRunning) {
                            Log.d(TAG, "✓ Service process confirmed dead after ${verifyAttempts * 500}ms")
                            break
                        }

                        if (verifyAttempts >= maxVerifyAttempts) {
                            Log.e(TAG, "ERROR: Service process refused to die after ${maxVerifyAttempts * 500}ms")
                            error("Service process did not terminate")
                        }
                    }
                }

                // Step 6: Wait for ports to be fully released by OS
                // Even after process dies, ports remain in TIME_WAIT state briefly
                Log.d(TAG, "Step 6: Waiting for ports to release (3000ms)...")
                delay(3000)
                Log.d(TAG, "✓ Ports should be released")

                // Step 7: Start service again (fresh process, no port conflicts)
                Log.d(TAG, "Step 7: Starting ReticulumService in fresh process...")
                val startIntent =
                    Intent(context, ReticulumService::class.java).apply {
                        action = ReticulumService.ACTION_START
                    }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
                Log.d(TAG, "✓ Service start initiated")

                // Step 8: Bind to the new service
                Log.d(TAG, "Step 8: Binding to new service instance...")
                if (reticulumProtocol is ServiceReticulumProtocol) {
                    reticulumProtocol.bindService()
                    // Phase 2, Task 2.3: bindService() now waits for explicit readiness signal
                    // No more arbitrary delay needed - service notifies when ready for IPC calls
                }
                Log.d(TAG, "✓ Bound to new service and ready for IPC")

                // Step 9: Initialize Reticulum with new config
                Log.d(TAG, "Step 9: Initializing Reticulum with new configuration...")

                // Load active identity and ensure its file exists (recover from keyData if needed)
                val activeIdentity = identityRepository.getActiveIdentitySync()
                val identityPath =
                    if (activeIdentity != null) {
                        identityRepository.ensureIdentityFileExists(activeIdentity)
                            .onFailure { error ->
                                Log.e(TAG, "Failed to ensure identity file exists: ${error.message}")
                            }
                            .getOrNull()
                    } else {
                        null
                    }
                val displayName = activeIdentity?.displayName
                Log.d(TAG, "Active identity: ${activeIdentity?.displayName ?: "none"}, verified path: $identityPath")

                // Load shared instance preferences
                val preferOwnInstance = settingsRepository.preferOwnInstanceFlow.first()
                Log.d(TAG, "Prefer own instance: $preferOwnInstance")

                // Load RPC key for shared instance authentication
                val rpcKey = settingsRepository.rpcKeyFlow.first()
                if (rpcKey != null) {
                    Log.d(TAG, "RPC key configured for shared instance auth")
                }

                val config =
                    ReticulumConfig(
                        storagePath = context.filesDir.absolutePath + "/reticulum",
                        enabledInterfaces = enabledInterfaces,
                        identityFilePath = identityPath,
                        displayName = displayName,
                        logLevel = LogLevel.DEBUG,
                        allowAnonymous = false,
                        preferOwnInstance = preferOwnInstance,
                        rpcKey = rpcKey,
                    )

                reticulumProtocol.initialize(config)
                    .onSuccess {
                        Log.d(TAG, "✓ Reticulum initialized successfully")

                        // Clear the flag now that initialization is complete
                        context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_applying_config", false)
                            .commit() // Synchronous to ensure flag is cleared for next restart
                        Log.d(TAG, "✓ Config apply flag cleared")
                    }
                    .onFailure { error ->
                        // Clear flag even on failure
                        context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_applying_config", false)
                            .commit() // Synchronous to ensure flag is cleared

                        Log.e(TAG, "Failed to initialize Reticulum", error)
                        throw Exception("Failed to initialize Reticulum: ${error.message}", error)
                    }

                // Step 10: Restore peer identities
                Log.d(TAG, "Step 10: Restoring peer identities...")
                try {
                    val peerIdentities = conversationRepository.getAllPeerIdentities()
                    Log.d(TAG, "Retrieved ${peerIdentities.size} peer identities from database")

                    if (peerIdentities.isNotEmpty() && reticulumProtocol is ServiceReticulumProtocol) {
                        reticulumProtocol.restorePeerIdentities(peerIdentities)
                            .onSuccess { count ->
                                Log.d(TAG, "✓ Restored $count peer identities")
                            }
                            .onFailure { error ->
                                Log.w(TAG, "Failed to restore peer identities: ${error.message}", error)
                                // Not fatal - continue
                            }
                    } else {
                        Log.d(TAG, "No peer identities to restore")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring peer identities", e)
                    // Not fatal - continue
                }

                // Step 10b: Restore announce peer identities
                Log.d(TAG, "Step 10b: Restoring announce peer identities...")
                try {
                    val announces = database.announceDao().getAllAnnouncesSync()
                    Log.d(TAG, "Retrieved ${announces.size} announce peer identities from database")

                    if (announces.isNotEmpty() && reticulumProtocol is ServiceReticulumProtocol) {
                        // Map announces to peer identity format (destinationHash, publicKey)
                        val announcePeerIdentities =
                            announces.map { announce ->
                                announce.destinationHash to announce.publicKey
                            }
                        reticulumProtocol.restorePeerIdentities(announcePeerIdentities)
                            .onSuccess { count ->
                                Log.d(TAG, "✓ Restored $count announce peer identities")
                            }
                            .onFailure { error ->
                                Log.w(TAG, "Failed to restore announce peer identities: ${error.message}", error)
                                // Not fatal - continue
                            }
                    } else {
                        Log.d(TAG, "No announce peer identities to restore")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring announce peer identities", e)
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
            }
        }

        /**
         * Check if Reticulum is currently running and ready.
         */
        fun isReticulumRunning(): Boolean {
            return try {
                val status = reticulumProtocol.networkStatus.value
                status.toString().contains("RUNNING") || status.toString().contains("READY")
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Mark that there are pending interface changes that need to be applied.
         * Used when interfaces are modified outside of InterfaceManagementViewModel
         * (e.g., from RNode wizard).
         */
        fun setPendingChanges(hasPending: Boolean) {
            context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
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
        fun getRNodeRssi(): Int {
            return if (reticulumProtocol is ServiceReticulumProtocol) {
                reticulumProtocol.getRNodeRssi()
            } else {
                -100
            }
        }
    }
