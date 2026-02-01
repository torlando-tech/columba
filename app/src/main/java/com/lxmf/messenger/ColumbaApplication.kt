package com.lxmf.messenger

import android.app.Application
import android.os.StrictMode
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.LogLevel
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.IdentityResolutionManager
import com.lxmf.messenger.service.MessageCollector
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.TelemetryCollectorManager
import com.lxmf.messenger.startup.ConfigApplyFlagManager
import com.lxmf.messenger.startup.ServiceIdentityVerifier
import com.lxmf.messenger.startup.StartupConfigLoader
import com.lxmf.messenger.util.CrashReportManager
import com.lxmf.messenger.util.HexUtils.hexStringToByteArray
import com.lxmf.messenger.util.HexUtils.toHexString
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    }

    @Inject
    lateinit var reticulumProtocol: ReticulumProtocol

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
    lateinit var interfaceRepository: InterfaceRepository

    @Inject
    lateinit var autoAnnounceManager: com.lxmf.messenger.service.AutoAnnounceManager

    @Inject
    lateinit var identityRepository: IdentityRepository

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

    // Application-level coroutine scope for app-wide operations
    // Uses Dispatchers.Main for lifecycle operations and UI coordination
    // SupervisorJob ensures failures don't crash the entire app
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        // Not the :reticulum service process or test instrumentation process
        val processName = getCurrentProcessName()
        if (processName?.contains(":reticulum") == true || isRunningInTest()) {
            val context = if (isRunningInTest()) "test" else "service"
            android.util.Log.d("ColumbaApplication", "$context process detected ($processName) - skipping auto-initialization")
            // Still initialize Python (service needs Python for RNS, tests need it for mocking)
            PythonBridge.initialize(this)
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
            com.lxmf.messenger.util.FileUtils
                .cleanupAllTempFiles(this@ColumbaApplication)
        }

        // Register existing companion device associations (Android 12+)
        // This ensures RNodeCompanionService is bound when associated devices connect,
        // even for associations created before this code was added
        // Run on IO dispatcher to avoid blocking main thread with system service calls
        applicationScope.launch(Dispatchers.IO) {
            registerExistingCompanionDevices()
        }

        // Initialize Python environment
        PythonBridge.initialize(this)

        // If using service-based protocol, bind to service and auto-initialize RNS
        if (reticulumProtocol is ServiceReticulumProtocol) {
            val serviceProtocol = reticulumProtocol as ServiceReticulumProtocol

            // Set up the alternative relay handler for propagation failover
            serviceProtocol.alternativeRelayHandler = { excludeHashes ->
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
            // and we successfully rebind but Python/Reticulum needs to be restarted
            serviceProtocol.onServiceNeedsInitialization = {
                android.util.Log.i("ColumbaApplication", "Service needs reinitialization after rebind - starting initialization")
                initializeReticulumService(serviceProtocol)
            }

            applicationScope.launch {
                try {
                    // Check if we're in the middle of applying config changes
                    val isApplyingConfig = configApplyFlagManager.isApplyingConfig()

                    if (isApplyingConfig) {
                        android.util.Log.d("ColumbaApp", "Config apply flag set - checking service...")
                        // Bind to service to check status
                        (reticulumProtocol as ServiceReticulumProtocol).bindService()

                        // Check if service is actually being configured, or if the flag is stale
                        // from a crashed/failed previous config apply
                        // Use timeout to prevent ANR if service is slow
                        val status =
                            withTimeoutOrNull(IPC_TIMEOUT_MS) {
                                (reticulumProtocol as ServiceReticulumProtocol).getStatus().getOrNull()
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
                        (reticulumProtocol as ServiceReticulumProtocol).bindService()
                    }
                    android.util.Log.d("ColumbaApplication", "Successfully bound to ReticulumService")

                    // Start PropagationNodeManager early so relay is synced to Python ASAP
                    // This allows PROPAGATED sends to work before full initialization completes
                    propagationNodeManager.start()
                    android.util.Log.d("ColumbaApplication", "PropagationNodeManager started early (relay sync)")

                    // Check if service is already initialized (handle service process surviving app restart)
                    // Use timeout to prevent ANR if service is slow
                    val currentStatus =
                        withTimeoutOrNull(IPC_TIMEOUT_MS) {
                            (reticulumProtocol as ServiceReticulumProtocol).getStatus().getOrNull()
                        }
                    android.util.Log.d("ColumbaApplication", "Service status after binding: $currentStatus")

                    if (currentStatus == "READY") {
                        android.util.Log.d("ColumbaApplication", "Service already initialized and ready")

                        // Verify service identity matches database active identity
                        // This catches mismatches from interrupted identity switches or data imports
                        val serviceIdentity =
                            withTimeoutOrNull(IPC_TIMEOUT_MS) {
                                (reticulumProtocol as ServiceReticulumProtocol)
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
                    val preferOwnInstance = startupConfig.preferOwn
                    val rpcKey = startupConfig.rpcKey
                    val transportNodeEnabled = startupConfig.transport
                    val discoverInterfaces = startupConfig.discoverInterfaces
                    val autoconnectDiscoveredCount = startupConfig.autoconnectDiscoveredCount
                    android.util.Log.d("ColumbaApplication", "Loaded ${enabledInterfaces.size} enabled interface(s)")
                    android.util.Log.d("ColumbaApplication", "Prefer own instance: $preferOwnInstance")
                    android.util.Log.d("ColumbaApplication", "Transport node enabled: $transportNodeEnabled")
                    android.util.Log.d("ColumbaApplication", "Discover interfaces: $discoverInterfaces, autoconnect: $autoconnectDiscoveredCount")

                    // Ensure identity file exists (recover from keyData if missing)
                    var identityPath: String? = null
                    val displayName = activeIdentity?.displayName
                    if (activeIdentity != null) {
                        android.util.Log.d(
                            "ColumbaApplication",
                            "Active identity: ${activeIdentity.displayName} " +
                                "(${activeIdentity.identityHash.take(8)}...)",
                        )
                        val fileResult = identityRepository.ensureIdentityFileExists(activeIdentity)
                        if (fileResult.isSuccess) {
                            identityPath = fileResult.getOrNull()
                            android.util.Log.d("ColumbaApplication", "Identity file verified/recovered: $identityPath")
                        } else {
                            android.util.Log.e(
                                "ColumbaApplication",
                                "Could not ensure identity file exists: ${fileResult.exceptionOrNull()}",
                            )
                            // identityPath remains null - Python will create new default
                        }
                        android.util.Log.d("ColumbaApplication", "Display name: $displayName")
                    } else {
                        android.util.Log.d("ColumbaApplication", "No active identity found, Python will create default")
                    }

                    // Auto-initialize Reticulum with config from database
                    android.util.Log.d("ColumbaApplication", "Auto-initializing Reticulum...")
                    val config =
                        ReticulumConfig(
                            storagePath = filesDir.absolutePath + "/reticulum",
                            enabledInterfaces = enabledInterfaces,
                            identityFilePath = identityPath,
                            displayName = displayName,
                            logLevel = LogLevel.DEBUG,
                            allowAnonymous = false,
                            preferOwnInstance = preferOwnInstance,
                            rpcKey = rpcKey,
                            enableTransport = transportNodeEnabled,
                            discoverInterfaces = discoverInterfaces,
                            autoconnectDiscoveredInterfaces = autoconnectDiscoveredCount,
                        )

                    reticulumProtocol
                        .initialize(config)
                        .onSuccess {
                            android.util.Log.i("ColumbaApplication", "Reticulum initialized successfully")

                            // Battery optimization exemption is now handled in OnboardingPagerScreen
                            // for new users, and via Settings for existing users

                            // Migrate default identity to database if needed
                            // Wait a bit for the identity file to be created by Python
                            applicationScope.launch(Dispatchers.IO) {
                                try {
                                    delay(1000) // Wait 1 second for identity file creation

                                    // Check if this is an upgrade (existing identity) vs fresh install
                                    // If we already have identities, mark onboarding as completed
                                    // so returning users don't see the welcome screen
                                    val existingIdentity = identityRepository.getActiveIdentitySync()
                                    if (existingIdentity != null) {
                                        android.util.Log.d("ColumbaApplication", "Existing identity found - marking onboarding complete for upgrade")
                                        settingsRepository.markOnboardingCompleted()
                                    }

                                    // Get identity hashes from service
                                    val identity =
                                        (reticulumProtocol as ServiceReticulumProtocol)
                                            .getLxmfIdentity()
                                            .getOrNull()
                                    val destination =
                                        (reticulumProtocol as ServiceReticulumProtocol)
                                            .getLxmfDestination()
                                            .getOrNull()
                                    val idHash = identity?.hash?.toHexString()
                                    val destHash = destination?.hash?.toHexString()

                                    identityRepository.migrateDefaultIdentityIfNeeded(idHash, destHash)
                                } catch (e: Exception) {
                                    android.util.Log.e("ColumbaApplication", "Error migrating default identity", e)
                                }
                            }

                            // Restore peer identities from database to enable message sending
                            restorePeerIdentities(reticulumProtocol as ServiceReticulumProtocol)

                            // Start the message collector service after Reticulum is ready
                            messageCollector.startCollecting()
                            autoAnnounceManager.start()
                            identityResolutionManager.start(applicationScope)
                            propagationNodeManager.start()
                            telemetryCollectorManager.start()
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
    }

    override fun onTerminate() {
        super.onTerminate()

        // Stop auto-announce manager, message collection, and identity resolution
        autoAnnounceManager.stop()
        messageCollector.stopCollecting()
        identityResolutionManager.stop()

        // Shutdown and unbind from service when app terminates
        if (reticulumProtocol is ServiceReticulumProtocol) {
            applicationScope.launch {
                try {
                    reticulumProtocol.shutdown()
                } catch (e: Exception) {
                    android.util.Log.e("ColumbaApplication", "Error shutting down Reticulum", e)
                }
                (reticulumProtocol as ServiceReticulumProtocol).unbindService()
            }
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
     * @param serviceProtocol The ServiceReticulumProtocol instance to initialize
     */
    private suspend fun initializeReticulumService(serviceProtocol: ServiceReticulumProtocol) {
        try {
            android.util.Log.d("ColumbaApplication", "initializeReticulumService: Loading configuration from database...")

            // Load all configuration from database in parallel for faster startup
            val startupConfig = startupConfigLoader.loadConfig()
            val enabledInterfaces = startupConfig.interfaces
            val activeIdentity = startupConfig.identity
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

            // Ensure identity file exists (recover from keyData if missing)
            var identityPath: String? = null
            val displayName = activeIdentity?.displayName
            if (activeIdentity != null) {
                android.util.Log.d(
                    "ColumbaApplication",
                    "initializeReticulumService: Active identity: ${activeIdentity.displayName} " +
                        "(${activeIdentity.identityHash.take(8)}...)",
                )
                val fileResult = identityRepository.ensureIdentityFileExists(activeIdentity)
                if (fileResult.isSuccess) {
                    identityPath = fileResult.getOrNull()
                    android.util.Log.d("ColumbaApplication", "initializeReticulumService: Identity file verified/recovered: $identityPath")
                } else {
                    android.util.Log.e(
                        "ColumbaApplication",
                        "initializeReticulumService: Could not ensure identity file exists: ${fileResult.exceptionOrNull()}",
                    )
                }
            } else {
                android.util.Log.d("ColumbaApplication", "initializeReticulumService: No active identity found, Python will create default")
            }

            // Initialize Reticulum with config from database
            android.util.Log.d("ColumbaApplication", "initializeReticulumService: Initializing Reticulum...")
            val config =
                ReticulumConfig(
                    storagePath = filesDir.absolutePath + "/reticulum",
                    enabledInterfaces = enabledInterfaces,
                    identityFilePath = identityPath,
                    displayName = displayName,
                    logLevel = LogLevel.DEBUG,
                    allowAnonymous = false,
                    preferOwnInstance = preferOwnInstance,
                    rpcKey = rpcKey,
                    enableTransport = transportNodeEnabled,
                    discoverInterfaces = discoverInterfaces,
                    autoconnectDiscoveredInterfaces = autoconnectDiscoveredCount,
                )

            serviceProtocol
                .initialize(config)
                .onSuccess {
                    android.util.Log.i("ColumbaApplication", "initializeReticulumService: Reticulum initialized successfully")

                    // Restore peer identities from database to enable message sending
                    restorePeerIdentities(serviceProtocol)

                    // Start the message collector and other services after Reticulum is ready
                    messageCollector.startCollecting()
                    autoAnnounceManager.start()
                    identityResolutionManager.start(applicationScope)
                    propagationNodeManager.start()
                    telemetryCollectorManager.start()
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

    /** Helper to restore peer identities in background after Reticulum initialization. */
    private fun restorePeerIdentities(serviceProtocol: ServiceReticulumProtocol) {
        applicationScope.launch(Dispatchers.IO) {
            try {
                restorePeerIdentitiesInBatches(serviceProtocol)
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Error restoring peer identities", e)
            }
        }
    }

    /**
     * Restore peer identities in batches to prevent OOM on devices with large amounts of identity data.
     * Uses pagination to load peer identities in manageable chunks.
     */
    private suspend fun restorePeerIdentitiesInBatches(serviceProtocol: ServiceReticulumProtocol) {
        val batchSize = 500 // Process 500 peer identities at a time to limit memory usage
        var offset = 0
        var totalRestored = 0

        android.util.Log.d("ColumbaApplication", "Starting batched peer identity restoration (batch size: $batchSize)")

        var batch =
            try {
                conversationRepository.getPeerIdentitiesBatch(batchSize, offset)
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Error fetching initial peer identity batch", e)
                emptyList()
            }

        while (batch.isNotEmpty()) {
            android.util.Log.d("ColumbaApplication", "Processing batch ${offset / batchSize + 1}: ${batch.size} peer identities (offset $offset)")

            val batchCount = batch.size
            try {
                serviceProtocol
                    .restorePeerIdentities(batch)
                    .onSuccess { count ->
                        totalRestored += count
                        android.util.Log.d("ColumbaApplication", "✓ Restored $count peer identities from batch (total: $totalRestored)")
                    }.onFailure { error ->
                        android.util.Log.w("ColumbaApplication", "Failed to restore peer identity batch at offset $offset: ${error.message}", error)
                    }

                offset += batchSize
                batch =
                    if (batchCount < batchSize) {
                        emptyList()
                    } else {
                        kotlinx.coroutines.yield() // Let GC reclaim previous batch's bridge objects
                        conversationRepository.getPeerIdentitiesBatch(batchSize, offset)
                    }
            } catch (e: Exception) {
                android.util.Log.e("ColumbaApplication", "Error processing peer identity batch at offset $offset", e)
                batch = emptyList()
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
