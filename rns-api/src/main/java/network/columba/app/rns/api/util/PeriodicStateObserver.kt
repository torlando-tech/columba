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
 * LXST-kt's `Telephone` tracks the active codec profile and call mode as
 * plain fields with no reactive hook, and either can change because the local
 * user switched it or because the remote peer sent a `PREFERRED_PROFILE` /
 * `PREFERRED_MODE` signal mid-call. This observer mirrors the value at a fixed
 * cadence while [isCallInProgress] reports an active call, then parks (emitting
 * the [idleValue]) when the call ends. A 500 ms cadence is well below
 * perceptible latency for a UI affordance and negligible CPU when idle, so it
 * keeps the observable honest across both backends without requiring a library
 * bump to add a push-style hook.
 *
 * @param T The value type. When a call ends the flow parks at [idleValue].
 * @param pollIntervalMs Cadence of the read while a call is in progress.
 * @param isCallInProgress Returns true while a call is in progress (any
 *   non-terminal phase). The poller emits [idleValue] and skips reads when
 *   false.
 * @param reader Returns the current value, or `null`/[idleValue] when the
 *   backing manager / call has nothing to report. Called on the observer's
 *   [CoroutineScope] dispatcher.
 * @param idleValue The value emitted when no call is in progress (typically
 *   `null` for an abbreviation-bearing observable).
 */
class PeriodicStateObserver<T>(
    scope: CoroutineScope,
    private val pollIntervalMs: Long,
    private val isCallInProgress: () -> Boolean,
    private val reader: () -> T?,
    private val idleValue: T? = null,
) {
    private val _state = MutableStateFlow<T?>(idleValue)

    /** The observed value; [idleValue] (usually `null`) when no call is in progress. */
    val state: StateFlow<T?> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                if (isCallInProgress()) {
                    _state.value = reader()
                } else {
                    _state.value = idleValue
                }
                delay(pollIntervalMs)
            }
        }
    }
}
