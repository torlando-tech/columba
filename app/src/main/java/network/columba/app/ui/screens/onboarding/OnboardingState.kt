package network.columba.app.ui.screens.onboarding

import androidx.compose.runtime.Immutable

/**
 * State for the paged onboarding flow.
 */
@Immutable
data class OnboardingState(
    val currentPage: Int = 0,
    val displayName: String = "",
    val selectedInterfaces: Set<OnboardingInterfaceType> = setOf(OnboardingInterfaceType.AUTO),
    val notificationsEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val hasCompletedOnboarding: Boolean = false,
    val error: String? = null,
    val blePermissionsGranted: Boolean = false,
    val blePermissionsDenied: Boolean = false,
)

/**
 * Interface types that can be enabled during onboarding.
 * Simplified version of the full InterfaceConfig for user selection.
 */
enum class OnboardingInterfaceType(
    val displayName: String,
    val description: String,
    val secondaryDescription: String? = null,
) {
    AUTO(
        displayName = "Local WiFi",
        description = "Discover peers on your local network",
        secondaryDescription = "No internet required",
    ),
    BLE(
        displayName = "Bluetooth LE",
        description = "Connect directly to nearby devices",
        secondaryDescription = "Requires Bluetooth permissions",
    ),
    TCP(
        displayName = "Internet (TCP)",
        description = "Connect to the global Reticulum network",
        secondaryDescription = "Requires internet connection",
    ),
    RNODE(
        displayName = "LoRa Radio",
        description = "Long-range mesh via RNode hardware",
        secondaryDescription = "Requires external hardware - configure in Settings",
    ),
}

/**
 * Ordered onboarding pages. [CRASH_REPORTING] is only present in the `sentry` flavor
 * (gated by BuildConfig.CRASH_REPORTING_AVAILABLE when the page list is built).
 */
enum class OnboardingPage {
    WELCOME,
    IDENTITY,
    CONNECTIVITY,
    PERMISSIONS,
    CRASH_REPORTING,
    COMPLETE,
}

/**
 * Build the ordered list of onboarding pages for the current build flavor. The crash
 * reporting opt-in page is omitted entirely when crash reporting is not available.
 */
fun onboardingPages(crashReportingAvailable: Boolean): List<OnboardingPage> =
    OnboardingPage.entries.filter {
        it != OnboardingPage.CRASH_REPORTING || crashReportingAvailable
    }
