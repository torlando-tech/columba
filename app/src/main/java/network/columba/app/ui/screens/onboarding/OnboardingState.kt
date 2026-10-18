package network.columba.app.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import network.columba.app.R
import network.columba.app.viewmodel.UiMessage

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
    val error: UiMessage? = null,
    val blePermissionsGranted: Boolean = false,
    val blePermissionsDenied: Boolean = false,
)

/**
 * Interface types that can be enabled during onboarding.
 * Simplified version of the full InterfaceConfig for user selection.
 */
enum class OnboardingInterfaceType(
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val descriptionRes: Int,
    @param:StringRes val secondaryDescriptionRes: Int? = null,
) {
    AUTO(
        displayNameRes = R.string.onboarding_iface_wifi_name,
        descriptionRes = R.string.onboarding_iface_wifi_desc,
        secondaryDescriptionRes = R.string.onboarding_iface_wifi_secondary,
    ),
    BLE(
        displayNameRes = R.string.onboarding_iface_ble_name,
        descriptionRes = R.string.onboarding_iface_ble_desc,
        secondaryDescriptionRes = R.string.onboarding_iface_ble_secondary,
    ),
    TCP(
        displayNameRes = R.string.onboarding_iface_tcp_name,
        descriptionRes = R.string.onboarding_iface_tcp_desc,
        secondaryDescriptionRes = R.string.onboarding_iface_tcp_secondary,
    ),
    RNODE(
        displayNameRes = R.string.onboarding_iface_lora_name,
        descriptionRes = R.string.onboarding_iface_lora_desc,
        secondaryDescriptionRes = R.string.onboarding_iface_lora_secondary,
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
