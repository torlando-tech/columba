package network.columba.app.telemetry

/**
 * Flavor-bound factory. The `sentry` flavor returns the Sentry-backed reporter.
 *
 * The matching object in `src/noSentry` returns a no-op so the Sentry SDK is absent from
 * that APK. Both source sets declare the same fully-qualified name so `src/main` resolves
 * the correct implementation at compile time per flavor.
 */
object CrashReporterProvider {
    // Configuration at startup and consent changes from settings/onboarding share one reporter.
    private val instance: CrashReporter by lazy { SentryCrashReporter() }

    fun create(): CrashReporter = instance
}
