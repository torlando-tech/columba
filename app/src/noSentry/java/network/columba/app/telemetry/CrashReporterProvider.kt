package network.columba.app.telemetry

/**
 * Flavor-bound factory. The `noSentry` flavor returns a no-op reporter, ensuring no
 * crash/telemetry data ever leaves the device and the Sentry SDK is not linked in.
 *
 * The matching object in `src/sentry` returns the Sentry-backed reporter. Both source
 * sets declare the same fully-qualified name so `src/main` resolves the correct
 * implementation at compile time per flavor.
 */
object CrashReporterProvider {
    private val instance: CrashReporter by lazy { NoOpCrashReporter() }

    fun create(): CrashReporter = instance
}
