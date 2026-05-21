package network.columba.app.telemetry

import android.content.Context

/**
 * Severity for breadcrumbs / captured events, decoupled from any reporting SDK so that
 * shared `src/main` code never imports `io.sentry.*` directly.
 */
enum class CrashReportLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * Lightweight breadcrumb model so callers never touch `io.sentry.Breadcrumb`.
 */
data class CrashBreadcrumb(
    val category: String,
    val message: String,
    val level: CrashReportLevel = CrashReportLevel.INFO,
    val data: Map<String, Any> = emptyMap(),
)

/**
 * Build-time-swappable crash/performance reporting seam.
 *
 * The `sentry` product flavor provides a Sentry-backed implementation (src/sentry); the
 * `noSentry` flavor provides a no-op (src/noSentry) so that the Sentry SDK is entirely
 * absent from that APK. Shared `src/main` code must depend ONLY on this interface — no
 * `io.sentry.*` imports are allowed in `src/main`.
 *
 * Reporting is consent-gated: nothing is initialized or uploaded until the user opts in.
 */
interface CrashReporter {
    /**
     * Initialize the reporter. Must be safe to call from `Application.onCreate()`.
     *
     * @param consentGranted user opt-in, read synchronously at startup. When `false` the
     *   reporter must NOT initialize the SDK or perform any network upload.
     */
    fun initialize(
        context: Context,
        environment: String,
        isDebug: Boolean,
        consentGranted: Boolean,
    )

    /**
     * Enable or disable reporting at runtime after the consent preference changes.
     * Implementations should lazily initialize the SDK on the first `setEnabled(true)` if
     * [initialize] was called with `consentGranted == false`.
     */
    fun setEnabled(enabled: Boolean)

    fun addBreadcrumb(breadcrumb: CrashBreadcrumb)

    fun captureException(throwable: Throwable)
}
