package network.columba.app.telemetry

import android.content.Context

/**
 * Build-time-swappable, opt-in crash reporting seam.
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
     * Record application configuration without initializing the SDK, reading cached telemetry,
     * sampling memory or uploading. Startup must resolve consent before [setEnabled].
     */
    fun configure(
        context: Context,
        environment: String,
    )

    /**
     * Enable or disable reporting at runtime after the consent preference changes.
     * A grant lazily initializes the SDK using [configure]; revocation stops reporting and
     * clears pending telemetry. Callers must coordinate durable consent before activation.
     */
    fun setEnabled(enabled: Boolean)

    fun captureException(throwable: Throwable)
}
