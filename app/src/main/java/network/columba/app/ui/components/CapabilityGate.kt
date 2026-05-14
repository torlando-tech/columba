package network.columba.app.ui.components

import androidx.compose.runtime.Composable
import network.columba.app.rns.api.BackendCapabilities

/**
 * Renders [content] when the active backend supports a feature, else [fallback].
 *
 * Reads [LocalCapabilities] itself, so callers supply only the predicate and
 * the two branches — keeping per-call-site capability logic to one expression:
 *
 * ```
 * CapabilityGate(
 *     supported = { it.performance.batteryProfileTuning == BackendCapabilities.Support.FULL },
 *     fallback = { UnsupportedFeatureNotice("…") },
 * ) {
 *     BatteryProfilePicker(…)
 * }
 * ```
 *
 * For logic that isn't a clean composable swap (e.g. an Apply button whose
 * *behaviour* changes), read [LocalCapabilities] directly instead.
 */
@Composable
fun CapabilityGate(
    supported: (BackendCapabilities) -> Boolean,
    fallback: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (supported(LocalCapabilities.current)) {
        content()
    } else {
        fallback()
    }
}
