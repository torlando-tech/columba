package network.columba.app.rns.api.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A [StateFlow] of a value that is only meaningful while a call is in
 * progress, backed by a bounded poller.
 *
 * LXST-kt's `Telephone` tracks the active codec profile as a plain field
 * with no reactive hook (pinned v0.0.8), and the profile can change either
 * because the local user switched it or because the remote peer sent a
 * `PREFERRED_PROFILE` signal mid-call. This observer mirrors that field at a
 * fixed cadence while [isCallInProgress] reports an active call, then parks
 * (emitting `null`) when the call ends. A 500 ms cadence is well below
 * perceptible latency for a UI affordance and negligible CPU when idle, so
 * it keeps the observable honest across both backends without requiring a
 * library bump to add a push-style hook.
 *
 * @param pollIntervalMs Cadence of the read while a call is in progress.
 * @param isCallInProgress Returns true while a call is in progress (any
 *   non-terminal phase). The poller emits `null` and skips reads when false.
 * @param readProfile Returns the current active-profile abbreviation, or
 *   `null` when the backing manager / call has no active profile. Called on
 *   the observer's [CoroutineScope] dispatcher.
 */
class PeriodicStateObserver(
    scope: CoroutineScope,
    private val pollIntervalMs: Long,
    private val isCallInProgress: () -> Boolean,
    private val readProfile: () -> String?,
) {
    private val _activeProfile = MutableStateFlow<String?>(null)

    /** Active-profile abbreviation, `null` when no call is in progress. */
    val activeProfile: StateFlow<String?> = _activeProfile.asStateFlow()

    init {
        scope.launch {
            while (true) {
                if (isCallInProgress()) {
                    _activeProfile.value = readProfile()
                } else {
                    _activeProfile.value = null
                }
                delay(pollIntervalMs)
            }
        }
    }
}
