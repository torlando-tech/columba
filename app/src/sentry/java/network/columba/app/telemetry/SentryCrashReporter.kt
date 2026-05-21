package network.columba.app.telemetry

import android.content.Context
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import network.columba.app.BuildConfig

/**
 * Sentry-backed [CrashReporter] used by the `sentry` product flavor.
 *
 * Initialization is consent-gated: [SentryAndroid.init] is only ever called once the user
 * has opted in. Until then nothing is sent to the Sentry servers.
 */
internal class SentryCrashReporter : CrashReporter {
    private companion object {
        private const val TAG = "SentryCrashReporter"
    }

    @Volatile
    private var initialized = false

    // Captured init args so consent granted later (via [setEnabled]) can lazily init.
    private var appContext: Context? = null
    private var environment: String = "production"
    private var isDebug: Boolean = false

    override fun initialize(
        context: Context,
        environment: String,
        isDebug: Boolean,
        consentGranted: Boolean,
    ) {
        appContext = context.applicationContext
        this.environment = environment
        this.isDebug = isDebug

        if (consentGranted) {
            initSentry()
        } else {
            Log.d(TAG, "Crash reporting consent not granted; Sentry not initialized")
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (!initialized) initSentry()
        } else {
            // Once initialized, Sentry cannot be torn down mid-process; closing it is the
            // closest equivalent and stops further uploads until the next launch.
            if (initialized) {
                Sentry.close()
                initialized = false
                Log.d(TAG, "Sentry closed; crash reporting disabled")
            }
        }
    }

    // The Sentry static API delegates to a global no-op hub when the SDK is not
    // initialized, so these are safe to call without an instance-level guard. This also
    // lets a separate CrashReporter instance (e.g. in MainActivity) record breadcrumbs
    // against the global hub that ColumbaApplication initialized after consent.
    override fun addBreadcrumb(breadcrumb: CrashBreadcrumb) {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                category = breadcrumb.category
                message = breadcrumb.message
                level = breadcrumb.level.toSentryLevel()
                breadcrumb.data.forEach { (key, value) -> setData(key, value) }
            },
        )
    }

    override fun captureException(throwable: Throwable) {
        Sentry.captureException(throwable)
    }

    private fun initSentry() {
        val context = appContext ?: return
        try {
            SentryAndroid.init(context) { options ->
                // DSN from BuildConfig - set via SENTRY_DSN environment variable at build time.
                options.dsn = BuildConfig.SENTRY_DSN

                // Defense-in-depth: never enable without a DSN even if consent is granted.
                options.isEnabled = BuildConfig.SENTRY_DSN.isNotEmpty()

                // Tag the environment so events are filterable in the Sentry dashboard.
                options.environment = environment

                // Performance Monitoring - Tracing
                if (isDebug) {
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
                options.isEnableAppStartProfiling = !isDebug

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

                Log.d(
                    TAG,
                    "Sentry initialized: enabled=${options.isEnabled}, " +
                        "environment=${options.environment}, " +
                        "tracing=${options.tracesSampleRate}, " +
                        "hasDsn=${BuildConfig.SENTRY_DSN.isNotEmpty()}",
                )
            }
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sentry", e)
        }
    }

    private fun CrashReportLevel.toSentryLevel(): SentryLevel =
        when (this) {
            CrashReportLevel.DEBUG -> SentryLevel.DEBUG
            CrashReportLevel.INFO -> SentryLevel.INFO
            CrashReportLevel.WARNING -> SentryLevel.WARNING
            CrashReportLevel.ERROR -> SentryLevel.ERROR
        }
}
