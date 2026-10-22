package network.columba.app.telemetry

import android.content.Context
import android.util.Log
import io.sentry.AsyncHttpTransportFactory
import io.sentry.ITransportFactory
import io.sentry.NoOpLogger
import io.sentry.SendCachedEnvelopeFireAndForgetIntegration
import io.sentry.SendFireAndForgetEnvelopeSender
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.UncaughtExceptionHandlerIntegration
import io.sentry.cache.EnvelopeCache
import io.sentry.util.DebugMetaPropertiesApplier
import network.columba.app.BuildConfig
import java.io.File
import java.util.Properties

/**
 * Opt-in managed crashes only. Deliberately uses the core SDK, not SentryAndroid:
 * no Android device/installation collectors, native minidumps, historical ANRs,
 * logcat/Timber, screenshots, view hierarchy, replay or performance integrations.
 * The only automatic integration is the Java uncaught-exception handler.
 */
internal class SentryCrashReporter(
    private val dsn: String = BuildConfig.SENTRY_DSN,
    private val transportFactory: ITransportFactory = AsyncHttpTransportFactory(),
) : CrashReporter {
    private var appContext: Context? = null
    private var consentStore: CrashReportingConsentStore? = null
    private var needsCachePurge = false
    private var environment = "production"

    @Volatile
    private var consent: SentryConsent? = null

    @Synchronized
    override fun configure(
        context: Context,
        environment: String,
    ) {
        appContext = context.applicationContext
        consentStore = CrashReportingConsentStore(context.applicationContext)
        this.environment = if (environment == "production") "production" else "development"
    }

    @Synchronized
    override fun setEnabled(enabled: Boolean) {
        if (enabled) {
            initSentry()
        } else {
            // Revoke first: cache writers and queued HTTP gates see a permanently dead epoch.
            val previous = consent
            previous?.revoke()
            consent = null
            MemoryDiagnostics.stop()
            needsCachePurge = true
            try {
                // Also journal direct reporter revocations before SDK shutdown can block.
                consentStore?.requireCachePurge()
            } finally {
                if (previous != null) Sentry.close()
                appContext?.let { context ->
                    val privateCleared = purgeSdkDirectory(File(context.cacheDir, PRIVATE_CACHE))
                    val legacyCleared = purgeSdkDirectory(File(context.cacheDir, LEGACY_CACHE))
                    if (privateCleared && legacyCleared) {
                        consentStore?.markCachePurged()
                        needsCachePurge = false
                    }
                }
            }
        }
    }

    override fun captureException(throwable: Throwable) {
        consent?.ifGranted { Sentry.captureException(throwable) }
    }

    private fun initSentry() {
        if (consent != null || dsn.isBlank()) return
        val context = appContext ?: return
        // A grant never clears this journal. Finish interrupted revocations before
        // constructing the SDK, whose cached-envelope integration can replay immediately.
        val pendingPurge = needsCachePurge || consentStore?.cachePurgeRequired == true
        if (pendingPurge && !purgeSdkDirectory(File(context.cacheDir, PRIVATE_CACHE))) return
        val buildUuid = loadProguardUuid(context)
        // Never replay envelopes from the old unrestricted Android SDK configuration.
        if (!purgeSdkDirectory(File(context.cacheDir, LEGACY_CACHE))) {
            Log.w(TAG, "Crash reporting disabled: unable to clear legacy SDK cache")
            return
        }
        if (pendingPurge) {
            consentStore?.markCachePurged()
            needsCachePurge = false
        }
        val epoch = SentryConsent()
        val policy =
            SentryPrivacyPolicy(
                release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                environment = environment,
                proguardUuid = buildUuid,
            )
        try {
            val options =
                SentryOptions().apply {
                    dsn = this@SentryCrashReporter.dsn
                    proguardUuid = buildUuid
                    release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                    environment = this@SentryCrashReporter.environment
                    isEnableExternalConfiguration = false
                    isDebug = false
                    setLogger(NoOpLogger.getInstance())
                    isSendDefaultPii = false
                    isAttachServerName = false
                    isAttachThreads = false
                    isAttachStacktrace = false
                    isSendModules = false
                    isEnableAutoSessionTracking = false
                    isSendClientReports = false
                    isEnableScopePersistence = false
                    isEnableShutdownHook = false
                    isEnableUncaughtExceptionHandler = true
                    isEnableSpotlight = false
                    isPrintUncaughtStackTrace = false
                    isEnableBackpressureHandling = false
                    isEnableScreenTracking = false
                    isEnableUserInteractionTracing = false
                    isEnableUserInteractionBreadcrumbs = false
                    isEnableTimeToFullDisplayTracing = false
                    isEnableAppStartProfiling = false
                    isStartProfilerOnAppStart = false
                    isCaptureOpenTelemetryEvents = false
                    tracesSampleRate = 0.0
                    profilesSampleRate = 0.0
                    profileSessionSampleRate = 0.0
                    setTracePropagationTargets(emptyList())
                    isPropagateTraceparent = false
                    logs.isEnabled = false
                    metrics.isEnabled = false
                    sessionReplay.sessionSampleRate = 0.0
                    sessionReplay.onErrorSampleRate = 0.0
                    maxBreadcrumbs = 0
                    maxAttachmentSize = 0
                    maxQueueSize = 10
                    maxCacheItems = 10
                    beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { _, _ -> null }
                    beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { _, _ -> null }
                    beforeSendReplay = SentryOptions.BeforeSendReplayCallback { _, _ -> null }
                    beforeSend =
                        SentryOptions.BeforeSendCallback { event, hint ->
                            if (!epoch.granted) return@BeforeSendCallback null
                            hint.clearAttachments()
                            event.contexts[SentryPrivacyPolicy.MEMORY_CONTEXT] = MemoryDiagnostics.snapshot()
                            policy.event(event)
                        }
                    cacheDirPath = File(context.cacheDir, PRIVATE_CACHE).absolutePath
                    setEnvelopeDiskCache(SentryPrivacyCache(epoch, policy, serializer, EnvelopeCache.create(this)))
                    setTransportGate(io.sentry.transport.ITransportGate { epoch.granted })
                    setTransportFactory(SentryPrivacyTransportFactory(epoch, policy, this@SentryCrashReporter.transportFactory))
                    integrations.clear()
                    addIntegration(UncaughtExceptionHandlerIntegration())
                    addIntegration(
                        SendCachedEnvelopeFireAndForgetIntegration(
                            SendFireAndForgetEnvelopeSender { cacheDirPath },
                        ),
                    )
                }
            Sentry.init(options)
            if (!Sentry.isEnabled()) {
                epoch.revoke()
                return
            }
            consent = epoch
            MemoryDiagnostics.start(context) {
                // A callback already in flight at stop must not revive collection.
                epoch.ifGranted {
                    if (consent === epoch) Sentry.captureMessage(SentryPrivacyPolicy.HIGH_MEMORY_MESSAGE, SentryLevel.WARNING)
                }
            }
        } catch (_: Exception) {
            epoch.revoke()
            consent = null
            MemoryDiagnostics.stop()
            Sentry.close()
            Log.w(TAG, "Crash reporting initialization failed")
        }
    }

    companion object {
        private const val TAG = "SentryCrashReporter"
        internal const val PRIVATE_CACHE = "sentry-private-v1"
        private const val LEGACY_CACHE = "sentry"

        private val PROGUARD_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        internal fun loadProguardUuid(context: Context): String? =
            try {
                context.assets.open(DebugMetaPropertiesApplier.DEBUG_META_PROPERTIES_FILENAME).use { input ->
                    val properties = Properties().apply { load(input) }
                    DebugMetaPropertiesApplier.getProguardUuid(properties)?.takeIf { PROGUARD_UUID.matches(it) }
                }
            } catch (_: Exception) {
                null // Debug/non-minified builds need no mapping identifier.
            }

        /** Only caller-selected SDK directories; never traverse symlinks into app data. */
        internal fun purgeSdkDirectory(directory: File): Boolean =
            try {
                val absolute = directory.absoluteFile
                // Android may alias a parent (/data/user/0 versus /data/data). Only a
                // link at this entry is unsafe to recurse into; parent aliases are valid.
                val expectedEntry = File(requireNotNull(absolute.parentFile).canonicalFile, absolute.name)
                if (absolute.canonicalFile != expectedEntry) {
                    directory.delete() || !directory.exists()
                } else {
                    val children = directory.listFiles()
                    var cleared = children != null || !directory.isDirectory
                    children?.forEach { if (!purgeSdkDirectory(it)) cleared = false }
                    cleared && (directory.delete() || !directory.exists())
                }
            } catch (_: Exception) {
                false
            }
    }
}
