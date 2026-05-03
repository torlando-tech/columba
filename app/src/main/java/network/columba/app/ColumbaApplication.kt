package network.columba.app

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.model.LogLevel
import network.columba.app.reticulum.model.ReticulumConfig
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.service.IdentityResolutionManager
import network.columba.app.service.MessageCollector
import network.columba.app.service.PropagationNodeManager
import network.columba.app.service.LocationSharingManager
import network.columba.app.service.TelemetryCollectorManager
import network.columba.app.startup.ConfigApplyFlagManager
import network.columba.app.startup.ServiceIdentityVerifier
import network.columba.app.startup.StartupConfigLoader
import network.columba.app.util.CrashReportManager
import network.columba.app.util.HexUtils.hexStringToByteArray
import javax.inject.Inject

/**
 * Main application class for Columba LXMF Messenger.
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection.
 */
@HiltAndroidApp
class ColumbaApplication : Application() {
    companion object {
        /** Timeout for IPC calls to prevent ANR during initialization */
        internal const val IPC_TIMEOUT_MS = 5000L
        internal const val PEER_IDENTITY_BULK_RESTORE_DELAY_MS = 5000L
    }

    @Inject
    lateinit var reticulumProtocol: ReticulumProtocol

    // Cross-process SharedPreferences wrapper shared with the :reticulum process.
    // Constructed lazily rather than via Hilt because it only needs a Context and has
    // no other dependencies — this avoids adding a module binding for a single call site.
    private val serviceSettingsAccessor by lazy {
        network.columba.app.service.persistence
            .ServiceSettingsAccessor(this)
    }

    @Inject
    lateinit var startupConfigLoader: StartupConfigLoader

    @Inject
    lateinit var configApplyFlagManager: ConfigApplyFlagManager

    @Inject
    lateinit var serviceIdentityVerifier: ServiceIdentityVerifier

    @Inject
    lateinit var messageCollector: MessageCollector

    @Inject
    lateinit var conversationRepository: ConversationRepository

    @Inject
    lateinit var contactRepository: ContactRepository

    @Inject
    lateinit var interfaceRepository: InterfaceRepository

    @Inject
    lateinit var autoAnnounceManager: network.columba.app.service.AutoAnnounceManager

    @Inject
    lateinit var identityRepository: IdentityRepository

    @Inject
    lateinit var identityKeyProvider: network.columba.app.data.crypto.IdentityKeyProvider

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var identityResolutionManager: IdentityResolutionManager

    @Inject
    lateinit var propagationNodeManager: PropagationNodeManager

    @Inject
    lateinit var crashReportManager: CrashReportManager

    @Inject
    lateinit var telemetryCollectorManager: TelemetryCollectorManager

    @Inject
    lateinit var locationSharingManager: LocationSharingManager

    // Application-level coroutine scope for app-wide operations
    // Uses Dispatchers.Default for background initialization (no main-thread work needed)
    // SupervisorJob ensures failures don't crash the entire app
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Install crash handler FIRST (before anything else that might crash)
        // This ensures we capture crashes from any subsequent initialization
        crashReportManager.installCrashHandler()
        // Initialize Sentry for crash reporting and performance monitoring
        // Phase 1 Plan 01-03: Sentry Performance Monitoring
        initializeSentry()

        // Phase 4 Task 4.1: StrictMode for debug builds
        // Detect threading violations during development
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll() // Detect disk reads, disk writes, network, custom slow calls
                    .penaltyLog() // Log violations to logcat
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectAll() // Detect leaks, file URI exposure, etc.
                    .penaltyLog()
                    .build(),
            )
            android.util.Log.d("ColumbaApplication", "StrictMode enabled for debug build")
        }

        // Skip RNS auto-init in service process or test environment
        // Only the main app process should initialize
        val processName = getCurrentProcessName()
        if (processName?.contains(":reticulum") == true || isRunningInTest()) {
            val context = if (isRunningInTest()) "test" else "service"
            android.util.Log.d("ColumbaApplication", "$context process detected ($processName) - skipping auto-initialization")
            return
        }

        android.util.Log.d("ColumbaApplication", "Main app process detected ($processName) - proceeding with auto-initialization")

        // Preload theme preference into DataStore's in-memory cache
        // This eliminates theme flash on app startup by ensuring the theme is cached
        // before MainActivity renders. Combined with SplashScreen API for zero-flash UX.
        applicationScope.launch {
            try {
                settingsRepository.themePreferenceFlow.first()
                android.util.Log.d("ColumbaApplication", "Theme preference preloaded and cached")
            } catch (e: Exception) {
                android.util.Log.w(
                    "ColumbaApplication",
                    "Failed to preload theme preference (will use default): ${e.message}",
                )
            }
        }

        // Clean up old temp files from previous sessions (attachments, share_images)
        // Run on IO dispatcher to avoid blocking main thread with file operations
        applicationScope.launch(Dispatchers.IO) {
            network.columba.app.util.FileUtils
                .cleanupAllTempFiles(this@ColumbaApplication)
        }

        // Migrate unencrypted identity keys to encrypted storage (one-time, idempotent),
        // then scrub any stale plaintext identity_<hash> files. The migration reads those
        // same files for upgraders whose keys were only on disk, so the scrub MUST run
        // after the migration completes — otherwise a lost race deletes the only copy of
        // the private key and the identity is permanently gone.
        //
        // The scrub itself removes forensic liability: the native stack now gets the key
        // in memory via ReticulumConfig.deliveryIdentityKey; the disk copies are dead
        // weight. Best-effort zero-overwrite + delete — true secure erase isn't achievable
        // on wear-levelled / journalled storage, but we remove the dangling-reference risk.
        applicationScope.launch(Dispatchers.IO) {
            // Gate the scrub on full migration success. If migration throws, returns
            // Result.failure, or reports any per-identity failureCount > 0, the on-disk
            // identity_<hash> files are still the only copy of the key for at least one
            // identity — deleting them here would permanently destroy it on upgraders
            // hitting a transient Keystore or DB error on first boot.
            val migrationSucceeded =
                try {
                    val result = identityRepository.runEncryptionMigration()
                    result.isSuccess && (result.getOrNull()?.failureCount ?: 1) == 0
                } catch (e: Exception) {
                    android.util.Log.e("ColumbaApplication", "Identity key encryption migration failed", e)
                    false
                }
            if (!migrationSucceeded) {
                android.util.Log.w(
                    "ColumbaApplication",
                    "Skipping stale identity file scrub - encryption migration did not fully succeed",
                )
                return@launch
            }
            // Skip scrub if the active identity requires a password — in that case
            // ColumbaApplication can't decrypt the key at startup (no UI prompt yet),
            // so the on-disk identity file is still the only usable fallback for
            // handing the identity to the native stack. Deleting it silently rotates
            // the user onto a fresh ephemeral identity.
            if (anyActiveIdentityRequiresPassword()) {
                android.util.Log.w(
                    "ColumbaApplication",
                    "Skipping stale identity file scrub - active identity is password-protected",
                )
                return@launch
            }
            try {
                val reticulumDir = java.io.File(filesDir, "reticulum")
                // Both layouts are now write-free in normal operation — the
                // createIdentityWithName / importIdentityFile refactor returns
                // the key via ReticulumConfig.deliveryIdentityKey in memory.
                // These scrub passes exist purely to clean up stale files left
                // by earlier builds on upgrade:
                //   - reticulum/identity_<hash>   (pre-#785 flat layout)
                //   - reticulum/identities/<hash> (post-#785, pre-this-refactor)
                // The reticulum-kt FileMigrator handles the LXMF per-destination
                // ratchet directory (reticulum/lxmf/ratchets/) upstream.
                val staleIdentityFiles =
                    (reticulumDir.listFiles { f -> f.isFile && f.name.startsWith("identity_") } ?: emptyArray()) +
                        (java.io.File(reticulumDir, "identities").listFiles { f -> f.isFile } ?: emptyArray())
                staleIdentityFiles.forEach { f ->
                    runCatching { f.writeBytes(ByteArray(f.length().toInt())) }
                    if (f.delete()) {
                        android.util.Log.i(
                            "ColumbaApplication",
                            "Removed stale identity file: ${f.parentFile?.name}/${f.name}",
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ColumbaApplication", "Stale identity file cleanup failed: ${e.message}")
            }
        }

        // Register existing companion device associations (Android 12+)
        // This ensures RNodeCompanionService is bound when associated devices connect,
        // even for associations created before this code was added
        // Run on IO dispatcher to avoid blocking main thread with system service calls
        applicationScope.launch(Dispatchers.IO) {
            registerExistingCompanionDevices()
        }

        // Set up the alternative relay handler for propagation failover
        reticulumProtocol.alternativeRelayHandler = { excludeHashes ->
            val relay = propagationNodeManager.getAlternativeRelay(excludeHashes)
            if (relay != null) {
                android.util.Log.d("ColumbaApplication", "Providing alternative relay: ${relay.destinationHash.take(16)}")
                relay.destinationHash.hexStringToByteArray()
            } else {
                android.util.Log.d("ColumbaApplication", "No alternative relay available")
                null
            }
        }

        // Set up the reinitialization callback for when Android kills the service
        // and we successfully rebind but Reticulum needs to be restarted
        reticulumProtocol.onServiceNeedsInitialization = {
            android.util.Log.i("ColumbaApplication", "Service needs reinitialization after rebind - starting initialization")
            initializeReticulumService(reticulumProtocol)
        }

        // Mirror the protocol's networkStatus into the :reticulum service process so the
        // foreground notification stays in sync. On the native stack the protocol lives in
        // the app process — the service is just the notification/wake-lock host. When its
        // state changes (INITIALIZING → READY, or later ERROR) we push it; we also persist
        // the value via cross-process prefs so that if Android kills and restarts :reticulum
        // on its own, onCreate() can restore the correct initial text instead of defaulting
        // to SHUTDOWN / "Disconnected".
        applicationScope.launch {
            reticulumProtocol.networkStatus.collect { status ->
                val serviceStatus = networkStatusToServiceString(status)
                // applicationScope runs on Dispatchers.Default; commit() is a synchronous
                // disk write, so move the persistence hop to IO to keep StrictMode quiet
                // in debug builds. updateServiceNotification itself just dispatches an
                // Intent and can stay on the collector's context.
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    serviceSettingsAccessor.saveLastNetworkStatus(serviceStatus)
                }
                updateServiceNotification(serviceStatus)
            }
        }

        applicationScope.launch {
            try {
                // Check if we're in the middle of applying config changes
                val isApplyingConfig = configApplyFlagManager.isApplyingConfig()

                if (isApplyingConfig) {
                    android.util.Log.d("ColumbaApp", "Config apply flag set - checking service...")
                    // Bind to service to check status
                    reticulumProtocol.bindService()

                    // Check if service is actually being configured, or if the flag is stale
                    // from a crashed/failed previous config apply
                    // Use timeout to prevent ANR if service is slow
                    val status =
                        withTimeoutOrNull(IPC_TIMEOUT_MS) {
                            reticulumProtocol.getStatus().getOrNull()
                        }
                    android.util.Log.d("ColumbaApplication", "Service status with config flag set: $status")

                    if (configApplyFlagManager.isStaleFlag(status)) {
                        // Service is not running/ready - the flag is stale from a failed config apply
                        // Clear it and proceed with normal initialization
                        android.util.Log.w(
                            "ColumbaApp",
                            "Stale config flag (status: $status) - clearing",
                        )
                        configApplyFlagManager.clearFlag()
                        // Fall through to normal initialization below
                    } else {
                        // Service is INITIALIZING or READY - InterfaceConfigManager is handling it
                        android.util.Log.d("ColumbaApplication", "Config apply in progress - skipping auto-init")
                        return@launch
                    }
                }

                // Bind to service first (skip if already bound above for config flag check)
                if (!isApplyingConfig) {
                    reticulumProtocol.bindService()
                }
                android.util.Log.d("ColumbaApplication", "Successfully bound to ReticulumService")

                // Start PropagationNodeManager early so relay is synced to the native
                // stack ASAP. This allows PROPAGATED sends to work before full
                // initialization completes.
                propagationNodeManager.start()
                android.util.Log.d("ColumbaApplication", "PropagationNodeManager started early (relay sync)")

                // Start telemetry settings observation early so map UI state
                // (collector address + send/request toggles) is available even if
                // startup exits early while service is INITIALIZING/RESTARTING.
                telemetryCollectorManager.start()
                locationSharingManager.restoreIfActive()
                android.util.Log.d("ColumbaApplication", "TelemetryCollectorManager started early after bind")

                // Check if service is already initialized (handle service process surviving app restart)
                // Use timeout to prevent ANR if service is slow
                val currentStatus =
                    withTimeoutOrNull(IPC_TIMEOUT_MS) {
                        reticulumProtocol.getStatus().getOrNull()
                    }
                android.util.Log.d("ColumbaApplication", "Service status after binding: $currentStatus")

                if (currentStatus == "READY") {
                    android.util.Log.d("ColumbaApplication", "Service already initialized and ready")

                    // Verify service identity matches database active identity
                    // This catches mismatches from interrupted identity switches or data imports
                    val serviceIdentity =
                        withTimeoutOrNull(IPC_TIMEOUT_MS) {
                            reticulumProtocol
                                .getLxmfIdentity()
                                .getOrNull()
                        }
                    val verificationResult = serviceIdentityVerifier.verify(serviceIdentity)

                    if (!verificationResult.isMatch) {
                        android.util.Log.w(
                            "ColumbaApplication",
                            "Identity mismatch detected! Service: ${verificationResult.serviceIdentityHash?.take(8)}..., " +
                                "DB: ${verificationResult.dbIdentityHash?.take(8)}... - forcing reinitialization",
                        )
                        // Fall through to initialization code below to fix the mismatch
                    } else {
                        android.util.Log.d(
                            "ColumbaApplication",
                            "Identity verified (${verificationResult.dbIdentityHash?.take(8) ?: "none"}...) - reconnecting",
                        )
                        // Identity matches - reconnect collectors and managers
                        messageCollector.startCollecting()
                        autoAnnounceManager.start()
                        identityResolutionManager.start(applicationScope)
                        propagationNodeManager.start()
                        telemetryCollectorManager.start()
                        locationSharingManager.restoreIfActive()
                        android.util.Log.d(
                            "ColumbaApplication",
                            "MessageCollector, AutoAnnounceManager, IdentityResolutionManager, PropagationNodeManager, TelemetryCollectorManager started",
                        )
                        return@launch
                    }
                } else if (currentStatus != "SHUTDOWN" &&
                    currentStatus != null &&
                    !currentStatus.startsWith("ERROR:")
                ) {
                    // Service is in INITIALIZING or RESTARTING state - wait for it
                    android.util.Log.d("ColumbaApplication", "Service is $currentStatus - waiting for completion")
                    return@launch
                }

                // Service is SHUTDOWN or ERROR - need to initialize
                android.util.Log.d("ColumbaApplication", "Service needs initialization (status: $currentStatus)")

                // Load all configuration from database in parallel for faster startup
                android.util.Log.d("ColumbaApplication", "Loading configuration from database (parallel)...")
                val startupConfig = startupConfigLoader.loadConfig()
                val enabledInterfaces = startupConfig.interfaces
                val activeIdentity = startupConfig.identity

                // Password-protected identity: we can't decrypt the key without a
                // password prompt. Bailing out here keeps the existing identity file
                // usable as a future unlock-flow input rather than silently booting
                // a fresh ephemeral identity that severs contact with the user's
                // peers. TODO: wire an unlock screen that resumes this init path.
                // Fail safe on a DB/Keystore error: a thrown requiresPassword would
                // otherwise escape to the outer catch and get logged as "Failed to bind
                // to ReticulumService" — the real failure is a DB query, and we want to
                // skip init in that case rather than power on with the wrong identity.
                if (activeIdentity != null &&
                    runCatching {
                        identityRepository.requiresPassword(activeIdentity.identityHash)
                    }.getOrDefault(true)
                ) {
                    android.util.Log.w(
                        "ColumbaApplication",
                        "Active identity ${activeIdentity.identityHash.take(8)}... is password-protected " +
                            "(or password status unreadable) - skipping auto-init until unlock",
                    )
                    return@launch
                }

                val preferOwnInstance = startupConfig.preferOwn
                val rpcKey = startupConfig.rpcKey
                val transportNodeEnabled = startupConfig.transport
                val discoverInterfaces = startupConfig.discoverInterfaces
                val autoconnectDiscoveredCount = startupConfig.autoconnectDiscoveredCount
                android.util.Log.d("ColumbaApplication", "Loaded ${enabledInterfaces.size} enabled interface(s)")
                android.util.Log.d("ColumbaApplication", "Prefer own instance: $preferOwnInstance")
                android.util.Log.d("ColumbaApplication", "Transport node enabled: $transportNodeEnabled")
                android.util.Log.d("ColumbaApplication", "Discover interfaces: $discoverInterfaces, autoconnect: $autoconnectDiscoveredCount")

                val displayName = activeIdentity?.displayName
                val deliveryKey = decryptDeliveryKey(activeIdentity)

                // Bail before Reticulum init if we have an active identity in Room but
                // couldn't produce its key bytes. Proceeding would start the native stack
                // with NativeIdentity.create() (a fresh ephemeral identity) while Room
                // still has the original active — the mismatch is invisible post-init
                // because the service check only asserts `existingActive != null`. Most
                // common cause: first-launch race where decryptDeliveryKey runs before
                // runEncryptionMigration has populated the Keystore-wrapped blob.
                if (activeIdentity != null && deliveryKey == null) {
                    android.util.Log.e(
                        "ColumbaApplication",
                        "Active identity ${activeIdentity.identityHash.take(8)}... present but key decryption " +
                            "returned null - skipping init to avoid silently substituting a fresh identity",
                    )
                    // Raise the unlock flag so the UI routes to IdentityUnlockScreen
                    // instead of showing a broken chats tab. Most common cause is an
                    // Auto Backup restore on a new device — the encrypted key blob
                    // came back but the Keystore AES key that produced it didn't.
                    settingsRepository.setNeedsIdentityUnlock(true)
                    return@launch
                }
                // Clear any stale flag from a previous failed boot.
                settingsRepository.setNeedsIdentityUnlock(false)

                // Auto-initialize Reticulum with config from database
                android.util.Log.d("ColumbaApplication", "Auto-initializing Reticulum...")
                val config =
                    ReticulumConfig(
                        storagePath = filesDir.absolutePath + "/reticulum",
                        enabledInterfaces = enabledInterfaces,
                        deliveryIdentityKey = deliveryKey,
                        displayName = displayName,
                        logLevel = LogLevel.DEBUG,
                        allowAnonymous = false,
                        preferOwnInstance = preferOwnInstance,
                        rpcKey = rpcKey,
                        enableTransport = transportNodeEnabled,
                        discoverInterfaces = discoverInterfaces,
                        autoconnectDiscoveredInterfaces = autoconnectDiscoveredCount,
                        autoconnectIfacOnly = startupConfig.autoconnectIfacOnly,
                    )

                reticulumProtocol
                    .initialize(config)
                    .onSuccess {
                        android.util.Log.i("ColumbaApplication", "Reticulum initialized successfully")

                        // networkStatus.collect (set up earlier) already pushes
                        // ACTION_UPDATE_NOTIFICATION when status transitions to READY, so no
                        // explicit call is needed here.

                        // Battery optimization exemption is now handled in OnboardingPagerScreen
                        // for new users, and via Settings for existing users

                        // Ensure identity is registered in Room database.
                        // On the native path, the identity exists only in reticulum-kt's memory —
                        // Columba's Room DB needs it for conversations, messages, and contacts.
                        applicationScope.launch(Dispatchers.IO) {
                            try {
                                val existingActive = identityRepository.getActiveIdentitySync()
                                if (existingActive != null) {
                                    android.util.Log.d("ColumbaApplication", "Active identity already in Room: ${existingActive.identityHash.take(8)}")
                                } else {
                                    // No active identity in Room — create one from the native stack
                                    val identity = reticulumProtocol.getLxmfIdentity().getOrNull()
                                    val destination = reticulumProtocol.getLxmfDestination().getOrNull()
                                    val idHash = identity?.hash?.joinToString("") { "%02x".format(it) }
                                    val destHash = destination?.hash?.joinToString("") { "%02x".format(it) }

                                    if (idHash != null && destHash != null) {
                                        // Get the full 64-byte keypair directly from the protocol
                                        // (bypasses the Columba model which only carries 32-byte sigPrv)
                                        val keyData = reticulumProtocol.getFullIdentityKey()

                                        val result =
                                            identityRepository.createIdentity(
                                                identityHash = idHash,
                                                displayName = displayName ?: "Anonymous Peer",
                                                destinationHash = destHash,
                                                filePath = "", // No raw file on native path
                                                keyData = keyData,
                                            )
                                        if (result.isSuccess) {
                                            identityRepository.switchActiveIdentity(idHash)
                                            android.util.Log.i(
                                                "ColumbaApplication",
                                                "Created active identity in Room: ${idHash.take(8)} (key encrypted: ${keyData != null})",
                                            )
                                        } else {
                                            android.util.Log.e("ColumbaApplication", "Failed to create identity in Room: ${result.exceptionOrNull()}")
                                        }
                                    } else {
                                        // Fallback: try legacy file-based migration
                                        identityRepository.migrateDefaultIdentityIfNeeded(idHash, destHash)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ColumbaApplication", "Error ensuring identity in Room", e)
                            }
                        }

                        restorePeerIdentities(reticulumProtocol)

                        // Start the message collector service after Reticulum is ready
                        messageCollector.startCollecting()
                        autoAnnounceManager.start()
                        identityResolutionManager.start(applicationScope)
                        propagationNodeManager.start()
                        telemetryCollectorManager.start()
                        locationSharingManager.restoreIfActive()
                        android.util.Log.d(
                            "ColumbaApplication",
                            "MessageCollector, AutoAnnounceManager, IdentityResolutionManager, PropagationNodeManager, TelemetryCollectorManager started",
                        )
                    }.onFailure { error ->
                        android.util.Log.e("ColumbaApplication", "Failed to initialize Reticulum: ${error.message}", error)
                    }
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Failed to bind to ReticulumService", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        // Stop auto-announce manager, message collection, and identity resolution
        autoAnnounceManager.stop()
        messageCollector.stopCollecting()
        identityResolutionManager.stop()

        // Shutdown and unbind from service when app terminates
        applicationScope.launch {
            try {
                reticulumProtocol.shutdown()
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Error shutting down Reticulum", e)
            }
            reticulumProtocol.unbindService()
        }
    }

    /**
     * Map the protocol's sealed NetworkStatus to the string vocabulary that
     * ServiceNotificationManager.getStatusTexts already branches on.
     */
    private fun networkStatusToServiceString(status: network.columba.app.reticulum.model.NetworkStatus): String =
        when (status) {
            is network.columba.app.reticulum.model.NetworkStatus.READY -> "READY"
            is network.columba.app.reticulum.model.NetworkStatus.INITIALIZING -> "INITIALIZING"
            is network.columba.app.reticulum.model.NetworkStatus.CONNECTING -> "CONNECTING"
            is network.columba.app.reticulum.model.NetworkStatus.SHUTDOWN -> "SHUTDOWN"
            is network.columba.app.reticulum.model.NetworkStatus.ERROR -> "ERROR:${status.message}"
        }

    /**
     * Send a network status update to the service process to refresh
     * the foreground notification text.
     */
    private fun updateServiceNotification(status: String) {
        try {
            val intent =
                android.content.Intent(this, network.columba.app.service.ReticulumService::class.java).apply {
                    action = network.columba.app.service.ReticulumService.ACTION_UPDATE_NOTIFICATION
                    putExtra(network.columba.app.service.ReticulumService.EXTRA_NETWORK_STATUS, status)
                }
            // startForegroundService also spins up the :reticulum process if it isn't running.
            // ReticulumService.onStartCommand guards on ::managers.isInitialized and returns
            // START_STICKY if onCreate hasn't finished, so an ACTION_UPDATE_NOTIFICATION
            // delivered during that brief window is dropped. Because this function now fires
            // for every networkStatus transition (including early SHUTDOWN / INITIALIZING /
            // CONNECTING emissions during startup), the drop window can be hit — but
            // ReticulumService.onCreate reads saveLastNetworkStatus back from cross-process
            // prefs before it calls startForeground, so the initial notification still
            // reflects the most recent status even when an early intent was dropped.
            androidx.core.content.ContextCompat
                .startForegroundService(this, intent)
        } catch (e: Exception) {
            android.util.Log.w("ColumbaApplication", "Failed to update service notification: ${e.message}")
        }
    }

    /**
     * Get the current process name.
     * Used to detect if we're running in the main app or service process.
     */
    private fun getCurrentProcessName(): String? =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            try {
                val mypid = android.os.Process.myPid()
                val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                manager.runningAppProcesses?.find { it.pid == mypid }?.processName
            } catch (e: Exception) {
                android.util.Log.w("ColumbaApplication", "Could not determine process name", e)
                null
            }
        }

    /**
     * Detect if we're running in a test environment.
     * Used to skip auto-initialization during instrumented tests.
     */
    private fun isRunningInTest(): Boolean =
        try {
            Class.forName("androidx.test.espresso.Espresso")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

    /**
     * Register existing companion device associations for device presence monitoring.
     * This ensures that RNodeCompanionService is bound when associated devices connect,
     * even for associations that were created before startObservingDevicePresence() was added.
     *
     * Only runs on Android 13+ (API 33+) where CompanionDeviceManager.myAssociations is available.
     */
    internal fun registerExistingCompanionDevices() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }

        try {
            val companionDeviceManager =
                getSystemService(android.companion.CompanionDeviceManager::class.java)
                    ?: return

            val associations = companionDeviceManager.myAssociations
            if (associations.isEmpty()) {
                android.util.Log.d("ColumbaApplication", "No companion device associations found")
                return
            }

            android.util.Log.d("ColumbaApplication", "████ COMPANION DEVICE REGISTRATION ████ Found ${associations.size} association(s)")

            for (association in associations) {
                try {
                    val macAddress = association.deviceMacAddress?.toString()
                    if (macAddress != null) {
                        android.util.Log.d(
                            "ColumbaApplication",
                            "████ REGISTERING OBSERVER ████ MAC=$macAddress name=${association.displayName}",
                        )
                        companionDeviceManager.startObservingDevicePresence(macAddress)
                        android.util.Log.d(
                            "ColumbaApplication",
                            "████ OBSERVER REGISTERED ████ MAC=$macAddress",
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "ColumbaApplication",
                        "Failed to register device presence for association ${association.id}: ${e.message}",
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ColumbaApplication", "Failed to register companion devices: ${e.message}")
        }
    }

    /**
     * Initialize the Reticulum service with configuration from the database.
     * This is called both during normal app startup and when the service needs
     * reinitialization after being killed by Android and successfully rebound.
     *
     * @param protocol The ReticulumProtocol instance to initialize
     */
    private suspend fun initializeReticulumService(protocol: ReticulumProtocol) {
        try {
            android.util.Log.d("ColumbaApplication", "initializeReticulumService: Loading configuration from database...")

            // Load all configuration from database in parallel for faster startup
            val startupConfig = startupConfigLoader.loadConfig()
            val enabledInterfaces = startupConfig.interfaces
            val activeIdentity = startupConfig.identity

            if (activeIdentity != null &&
                runCatching {
                    identityRepository.requiresPassword(activeIdentity.identityHash)
                }.getOrDefault(true)
            ) {
                android.util.Log.w(
                    "ColumbaApplication",
                    "initializeReticulumService: Active identity ${activeIdentity.identityHash.take(8)}... " +
                        "is password-protected (or password status unreadable) - skipping init until unlock",
                )
                return
            }

            val preferOwnInstance = startupConfig.preferOwn
            val rpcKey = startupConfig.rpcKey
            val transportNodeEnabled = startupConfig.transport
            val discoverInterfaces = startupConfig.discoverInterfaces
            val autoconnectDiscoveredCount = startupConfig.autoconnectDiscoveredCount
            android.util.Log.d("ColumbaApplication", "initializeReticulumService: Loaded ${enabledInterfaces.size} enabled interface(s)")
            android.util.Log.d(
                "ColumbaApplication",
                "initializeReticulumService: Discover interfaces: $discoverInterfaces, autoconnect: $autoconnectDiscoveredCount",
            )

            val displayName = activeIdentity?.displayName
            val deliveryKey = decryptDeliveryKey(activeIdentity)

            // Same guard as the cold-start path: don't init with a null key when an
            // active identity exists, or the native stack silently substitutes a fresh
            // ephemeral identity.
            if (activeIdentity != null && deliveryKey == null) {
                android.util.Log.e(
                    "ColumbaApplication",
                    "initializeReticulumService: Active identity ${activeIdentity.identityHash.take(8)}... " +
                        "present but key decryption returned null - skipping init",
                )
                settingsRepository.setNeedsIdentityUnlock(true)
                return
            }
            settingsRepository.setNeedsIdentityUnlock(false)

            // Initialize Reticulum with config from database
            android.util.Log.d("ColumbaApplication", "initializeReticulumService: Initializing Reticulum...")
            val config =
                ReticulumConfig(
                    storagePath = filesDir.absolutePath + "/reticulum",
                    enabledInterfaces = enabledInterfaces,
                    deliveryIdentityKey = deliveryKey,
                    displayName = displayName,
                    logLevel = LogLevel.DEBUG,
                    allowAnonymous = false,
                    preferOwnInstance = preferOwnInstance,
                    rpcKey = rpcKey,
                    enableTransport = transportNodeEnabled,
                    discoverInterfaces = discoverInterfaces,
                    autoconnectDiscoveredInterfaces = autoconnectDiscoveredCount,
                    autoconnectIfacOnly = startupConfig.autoconnectIfacOnly,
                )

            protocol
                .initialize(config)
                .onSuccess {
                    android.util.Log.i("ColumbaApplication", "initializeReticulumService: Reticulum initialized successfully")

                    // networkStatus.collect (set up in onCreate) handles the READY
                    // notification push.

                    restorePeerIdentities(protocol)

                    // Start the message collector and other services after Reticulum is ready
                    messageCollector.startCollecting()
                    autoAnnounceManager.start()
                    identityResolutionManager.start(applicationScope)
                    propagationNodeManager.start()
                    telemetryCollectorManager.start()
                    locationSharingManager.restoreIfActive()
                    android.util.Log.d(
                        "ColumbaApplication",
                        "initializeReticulumService: MessageCollector, AutoAnnounceManager, IdentityResolutionManager, PropagationNodeManager, TelemetryCollectorManager started",
                    )
                }.onFailure { error ->
                    android.util.Log.e("ColumbaApplication", "initializeReticulumService: Failed to initialize Reticulum: ${error.message}", error)
                }
        } catch (e: Exception) {
            android.util.Log.e("ColumbaApplication", "initializeReticulumService: Error during initialization", e)
        }
    }

    /**
     * True when the currently active identity is password-protected. Used to gate
     * both the stale-file scrub and Reticulum auto-init: without a password prompt
     * we can't decrypt the key, and deleting the on-disk file would silently rotate
     * the user onto a fresh ephemeral identity.
     */
    private suspend fun anyActiveIdentityRequiresPassword(): Boolean {
        // Fail safe: if we can't read the active identity or its password status, assume
        // yes. The caller uses this to decide whether to delete on-disk key files; a
        // transient DB/Keystore error shouldn't let the scrub proceed and potentially
        // destroy the only copy of a password-protected identity.
        val active =
            runCatching { identityRepository.getActiveIdentitySync() }
                .getOrElse { return true }
                ?: return false
        return runCatching {
            identityRepository.requiresPassword(active.identityHash)
        }.getOrDefault(true)
    }

    /**
     * Decrypt the active identity's private key into memory so it can be handed
     * to the native stack without ever writing plaintext to disk. Returns null
     * when no active identity exists (native stack will create a fresh one) or
     * when decryption fails (caller falls back to the same path).
     */
    private suspend fun decryptDeliveryKey(activeIdentity: network.columba.app.data.db.entity.LocalIdentityEntity?): ByteArray? {
        if (activeIdentity == null) {
            android.util.Log.d(
                "ColumbaApplication",
                "decryptDeliveryKey: No active identity found, native stack will create default",
            )
            return null
        }
        android.util.Log.d(
            "ColumbaApplication",
            "decryptDeliveryKey: Active identity: ${activeIdentity.displayName} " +
                "(${activeIdentity.identityHash.take(8)}...)",
        )
        val keyResult = identityKeyProvider.getDecryptedKeyData(activeIdentity.identityHash)
        return keyResult.fold(
            onSuccess = { key ->
                android.util.Log.d(
                    "ColumbaApplication",
                    "decryptDeliveryKey: Decrypted delivery identity key into memory (${key.size} bytes)",
                )
                key
            },
            onFailure = { error ->
                android.util.Log.e(
                    "ColumbaApplication",
                    "decryptDeliveryKey: Could not decrypt identity key: $error",
                )
                null
            },
        )
    }

    /** Helper to restore peer identities in background after Reticulum initialization. */
    private fun restorePeerIdentities(protocol: ReticulumProtocol) {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val restoredContactIdentityHashes = restoreContactIdentities(protocol)
                delay(PEER_IDENTITY_BULK_RESTORE_DELAY_MS)
                restorePeerIdentitiesInBatches(protocol, restoredContactIdentityHashes)
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Error restoring peer identities", e)
            }
        }
    }

    private suspend fun restoreContactIdentities(protocol: ReticulumProtocol): Set<String> {
        val contactIdentities = contactRepository.getRestorableContactIdentitiesForActiveIdentity()
        if (contactIdentities.isEmpty()) {
            android.util.Log.d("ColumbaApplication", "No restorable contact identities found")
            return emptySet()
        }

        val batchSize = 500
        val restoredIdentityHashes = mutableSetOf<String>()

        contactIdentities.chunked(batchSize).forEachIndexed { index, chunk ->
            try {
                val result = protocol.restorePeerIdentities(chunk)
                result
                    .onSuccess { count ->
                        android.util.Log.d(
                            "ColumbaApplication",
                            "✓ Restored $count prioritized contact identities from chunk ${index + 1}",
                        )
                        restoredIdentityHashes.addAll(chunk.map { it.first })
                    }.onFailure { error ->
                        android.util.Log.w(
                            "ColumbaApplication",
                            "Failed to restore prioritized contact identities chunk ${index + 1}: ${error.message}",
                            error,
                        )
                    }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ColumbaApplication",
                    "Error restoring prioritized contact identities chunk ${index + 1}",
                    e,
                )
            }
        }

        return restoredIdentityHashes
    }

    /**
     * Restore peer identities in batches to prevent OOM on devices with large amounts of identity data.
     * Uses pagination to load peer identities in manageable chunks.
     */
    private suspend fun restorePeerIdentitiesInBatches(
        protocol: ReticulumProtocol,
        alreadyRestoredIdentityHashes: Set<String>,
    ) {
        val batchSize = 500 // Process 500 peer identities at a time to limit memory usage
        var offset = 0
        var totalRestored = 0
        var hasMoreBatches = true

        android.util.Log.d("ColumbaApplication", "Starting batched peer identity restoration (batch size: $batchSize)")

        while (hasMoreBatches) {
            val rawBatch =
                try {
                    conversationRepository.getPeerIdentitiesBatch(batchSize, offset)
                } catch (e: Exception) {
                    android.util.Log.e("ColumbaApplication", "Error fetching peer identity batch at offset $offset", e)
                    emptyList()
                }

            if (rawBatch.isEmpty()) {
                hasMoreBatches = false
                continue
            }

            val batch = rawBatch.filterNot { alreadyRestoredIdentityHashes.contains(it.first) }
            val skippedCount = rawBatch.size - batch.size
            android.util.Log.d(
                "ColumbaApplication",
                "Processing batch ${offset / batchSize + 1}: ${batch.size}/${rawBatch.size} peer identities (offset $offset)",
            )
            if (skippedCount > 0) {
                android.util.Log.d(
                    "ColumbaApplication",
                    "Skipped $skippedCount already-restored peer identities in batch ${offset / batchSize + 1}",
                )
            }

            val batchCount = rawBatch.size
            try {
                if (batch.isNotEmpty()) {
                    protocol
                        .restorePeerIdentities(batch)
                        .onSuccess { count ->
                            totalRestored += count
                            android.util.Log.d("ColumbaApplication", "✓ Restored $count peer identities from batch (total: $totalRestored)")
                        }.onFailure { error ->
                            android.util.Log.w("ColumbaApplication", "Failed to restore peer identity batch at offset $offset: ${error.message}", error)
                        }
                }

                offset += batchSize
                hasMoreBatches = batchCount >= batchSize
                if (hasMoreBatches) {
                    kotlinx.coroutines.yield()
                }
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Error processing peer identity batch at offset $offset", e)
                hasMoreBatches = false
            }
        }

        android.util.Log.d("ColumbaApplication", "✓ Batch restore complete: $totalRestored peer identities restored")
    }

    /**
     * Initialize Sentry SDK for crash reporting and performance monitoring.
     * Phase 1 Plan 01-03: Production observability for performance issues.
     */
    private fun initializeSentry() {
        try {
            io.sentry.android.core.SentryAndroid.init(this) { options ->
                // DSN from BuildConfig - set via SENTRY_DSN environment variable at build time
                // Empty DSN = Sentry disabled (used by noSentry build variant in Phase 2)
                options.dsn = BuildConfig.SENTRY_DSN

                // Sentry is enabled when DSN is provided (not empty).
                // Both debug and release builds report, distinguished by environment.
                options.isEnabled = BuildConfig.SENTRY_DSN.isNotEmpty()

                // Tag the environment so events are filterable in the Sentry dashboard.
                // "debug" for local/dev builds, "production" for release builds.
                options.environment = if (BuildConfig.DEBUG) "debug" else "production"

                // Performance Monitoring - Tracing
                // Debug builds use lower sampling to reduce noise during development.
                if (BuildConfig.DEBUG) {
                    options.tracesSampleRate = 0.1 // 10% sampling for debug
                    options.profilesSampleRate = 0.0 // No profiling in debug
                } else {
                    options.tracesSampleRate = 0.5 // 50% sampling appropriate for <500 users
                    options.profilesSampleRate = 0.1 // Profile 10% of sampled transactions
                }

                // Activity & Fragment tracing (enabled by default)
                options.isEnableActivityLifecycleTracingAutoFinish = true

                // User Interaction tracing (clicks, scrolls, swipes)
                options.isEnableUserInteractionTracing = true
                options.isEnableUserInteractionBreadcrumbs = true

                // App Start performance tracking (cold/warm starts)
                options.isEnableAppStartProfiling = !BuildConfig.DEBUG

                // ANR Detection (Application Not Responding)
                options.isAnrEnabled = true
                options.anrTimeoutIntervalMillis = 5000 // 5 second ANR threshold
                options.isAttachAnrThreadDump = true

                // Frame Tracking (slow/frozen frames)
                options.isEnableFramesTracking = true

                // Breadcrumbs for debugging
                options.isEnableActivityLifecycleBreadcrumbs = true
                options.isEnableAppComponentBreadcrumbs = true
                options.isEnableSystemEventBreadcrumbs = true

                android.util.Log.d(
                    "ColumbaApplication",
                    "Sentry initialized: enabled=${options.isEnabled}, " +
                        "environment=${options.environment}, " +
                        "tracing=${options.tracesSampleRate}, " +
                        "hasDsn=${BuildConfig.SENTRY_DSN.isNotEmpty()}",
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ColumbaApplication", "Failed to initialize Sentry", e)
        }
    }
}
