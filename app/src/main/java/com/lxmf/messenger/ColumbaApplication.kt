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
import com.lxmf.messenger.service.MessageCollector
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main application class for Columba LXMF Messenger.
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection.
 */
@HiltAndroidApp
class ColumbaApplication : Application() {
    @Inject
    lateinit var reticulumProtocol: ReticulumProtocol

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

    // Application-level coroutine scope for app-wide operations
    // Uses Dispatchers.Main for lifecycle operations and UI coordination
    // SupervisorJob ensures failures don't crash the entire app
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Phase 4 Task 4.1: StrictMode for debug builds
        // Detect threading violations during development
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll() // Detect disk reads, disk writes, network, custom slow calls
                    .penaltyLog() // Log violations to logcat
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
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

        // Initialize Python environment
        PythonBridge.initialize(this)

        // If using service-based protocol, bind to service and auto-initialize RNS
        if (reticulumProtocol is ServiceReticulumProtocol) {
            applicationScope.launch {
                try {
                    // Check if we're in the middle of applying config changes
                    val isApplyingConfig =
                        getSharedPreferences("columba_prefs", MODE_PRIVATE)
                            .getBoolean("is_applying_config", false)

                    if (isApplyingConfig) {
                        android.util.Log.d("ColumbaApplication", "Skipping auto-init - config changes are being applied")
                        // Just bind to service, but don't initialize
                        // InterfaceConfigManager will handle initialization with the new config
                        (reticulumProtocol as ServiceReticulumProtocol).bindService()
                        return@launch
                    }

                    // Bind to service first
                    (reticulumProtocol as ServiceReticulumProtocol).bindService()
                    android.util.Log.d("ColumbaApplication", "Successfully bound to ReticulumService")

                    // Check if service is already initialized (handle service process surviving app restart)
                    val currentStatus = (reticulumProtocol as ServiceReticulumProtocol).getStatus().getOrNull()
                    android.util.Log.d("ColumbaApplication", "Service status after binding: $currentStatus")

                    if (currentStatus == "READY") {
                        android.util.Log.d("ColumbaApplication", "Service already initialized and ready")

                        // Verify service identity matches database active identity
                        // This catches mismatches from interrupted identity switches or data imports
                        val serviceIdentity = (reticulumProtocol as ServiceReticulumProtocol)
                            .getLxmfIdentity().getOrNull()
                        val serviceIdentityHash = serviceIdentity?.hash?.toHexString()

                        val activeIdentity = identityRepository.getActiveIdentitySync()
                        val dbIdentityHash = activeIdentity?.identityHash

                        if (serviceIdentityHash != null && dbIdentityHash != null &&
                            serviceIdentityHash != dbIdentityHash
                        ) {
                            android.util.Log.w(
                                "ColumbaApplication",
                                "Identity mismatch detected! Service: ${serviceIdentityHash.take(8)}..., " +
                                    "DB: ${dbIdentityHash.take(8)}... - forcing reinitialization",
                            )
                            // Fall through to initialization code below to fix the mismatch
                        } else {
                            android.util.Log.d(
                                "ColumbaApplication",
                                "Identity verified (${dbIdentityHash?.take(8) ?: "none"}...) - reconnecting",
                            )
                            // Identity matches - just reconnect message collector and auto-announce
                            messageCollector.startCollecting()
                            autoAnnounceManager.start()
                            android.util.Log.d("ColumbaApplication", "MessageCollector and AutoAnnounceManager started")
                            return@launch
                        }
                    } else if (currentStatus != "SHUTDOWN" && currentStatus != null &&
                        !currentStatus.startsWith("ERROR:")
                    ) {
                        // Service is in INITIALIZING or RESTARTING state - wait for it
                        android.util.Log.d("ColumbaApplication", "Service is $currentStatus - waiting for completion")
                        return@launch
                    }

                    // Service is SHUTDOWN or ERROR - need to initialize
                    android.util.Log.d("ColumbaApplication", "Service needs initialization (status: $currentStatus)")

                    // Load interfaces from database
                    android.util.Log.d("ColumbaApplication", "Loading interfaces from database...")
                    val enabledInterfaces = interfaceRepository.enabledInterfaces.first()
                    android.util.Log.d("ColumbaApplication", "Loaded ${enabledInterfaces.size} enabled interface(s)")

                    // Load active identity from database
                    android.util.Log.d("ColumbaApplication", "Loading active identity from database...")
                    val activeIdentity = identityRepository.getActiveIdentitySync()
                    val identityPath = activeIdentity?.filePath
                    val displayName = activeIdentity?.displayName
                    if (identityPath != null) {
                        android.util.Log.d(
                            "ColumbaApplication",
                            "Active identity: ${activeIdentity.displayName} " +
                                "(${activeIdentity.identityHash.take(8)}...)",
                        )
                        android.util.Log.d("ColumbaApplication", "Identity file path: $identityPath")
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
                        )

                    reticulumProtocol.initialize(config)
                        .onSuccess {
                            android.util.Log.i("ColumbaApplication", "Reticulum initialized successfully")

                            // Check and prompt for battery optimization exemption if needed
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                if (com.lxmf.messenger.util.BatteryOptimizationManager.shouldPromptForExemption(this@ColumbaApplication)) {
                                    android.util.Log.d("ColumbaApplication", "Battery optimization detected - will show exemption dialog")

                                    // Show dialog after a delay to ensure MainActivity is ready
                                    applicationScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        kotlinx.coroutines.delay(3000) // Wait 3s for MainActivity
                                        com.lxmf.messenger.util.BatteryOptimizationManager.recordPromptShown(this@ColumbaApplication)

                                        // Launch battery exemption request
                                        try {
                                            val intent =
                                                com.lxmf.messenger.util.BatteryOptimizationManager.createRequestExemptionIntent(
                                                    this@ColumbaApplication,
                                                )
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(intent)
                                            android.util.Log.i("ColumbaApplication", "Launched battery exemption request")
                                        } catch (e: Exception) {
                                            android.util.Log.e("ColumbaApplication", "Failed to launch battery exemption request", e)
                                        }
                                    }
                                }
                            }

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
                                            .getLxmfIdentity().getOrNull()
                                    val destination =
                                        (reticulumProtocol as ServiceReticulumProtocol)
                                            .getLxmfDestination().getOrNull()
                                    val idHash = identity?.hash?.toHexString()
                                    val destHash = destination?.hash?.toHexString()

                                    identityRepository.migrateDefaultIdentityIfNeeded(idHash, destHash)
                                } catch (e: Exception) {
                                    android.util.Log.e("ColumbaApplication", "Error migrating default identity", e)
                                }
                            }

                            // Restore peer identities from database to enable message sending
                            applicationScope.launch {
                                try {
                                    val peerIdentities = conversationRepository.getAllPeerIdentities()
                                    if (peerIdentities.isNotEmpty()) {
                                        val result =
                                            (reticulumProtocol as ServiceReticulumProtocol)
                                                .restorePeerIdentities(peerIdentities)
                                        result.onSuccess { count ->
                                            android.util.Log.d("ColumbaApplication", "Restored $count peer identities")
                                        }.onFailure { error ->
                                            android.util.Log.e("ColumbaApplication", "Failed to restore peer identities", error)
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ColumbaApplication", "Error restoring peer identities", e)
                                }
                            }

                            // Start the message collector service after Reticulum is ready
                            messageCollector.startCollecting()
                            autoAnnounceManager.start()
                            android.util.Log.d("ColumbaApplication", "MessageCollector and AutoAnnounceManager started")
                        }
                        .onFailure { error ->
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

        // Stop auto-announce manager and message collection
        autoAnnounceManager.stop()
        messageCollector.stopCollecting()

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
    private fun getCurrentProcessName(): String? {
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
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
    }

    /**
     * Detect if we're running in a test environment.
     * Used to skip auto-initialization during instrumented tests.
     */
    private fun isRunningInTest(): Boolean {
        return try {
            Class.forName("androidx.test.espresso.Espresso")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * Convert ByteArray to hex string.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
