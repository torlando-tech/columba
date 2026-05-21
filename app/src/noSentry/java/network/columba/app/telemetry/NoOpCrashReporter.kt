package network.columba.app.telemetry

import android.content.Context

/**
 * No-op [CrashReporter] used by the `noSentry` product flavor.
 *
 * This class — and the entire `noSentry` source set — contains zero references to
 * `io.sentry.*`, which is what guarantees the Sentry SDK is fully absent from the
 * `noSentry` APK. Every method is intentionally empty.
 */
internal class NoOpCrashReporter : CrashReporter {
    override fun initialize(
        context: Context,
        environment: String,
        isDebug: Boolean,
        consentGranted: Boolean,
    ) = Unit

    override fun setEnabled(enabled: Boolean) = Unit

    override fun addBreadcrumb(breadcrumb: CrashBreadcrumb) = Unit

    override fun captureException(throwable: Throwable) = Unit
}
