package network.columba.app.rns.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Typed error envelope crossing the AIDL boundary between the UI process
 * and the :reticulum backend process.
 *
 * AIDL can't carry [Throwable] cleanly — the cause chain, suppressed
 * exceptions, and (for the python backend) the python stack trace all get
 * lost across binder. Instead, backends emit one of these typed parcels;
 * `:rns-ipc` translates them to/from [Result.failure(RnsException)] on the
 * UI side so callers keep the familiar `result.fold` shape.
 *
 * Most error cases have a typed variant so the UI can render a meaningful
 * message and choose recovery actions; truly novel failures fall through
 * to [Generic] with the original message and stack trace text preserved
 * for debugging.
 */
sealed class RnsError : Parcelable {
    /**
     * Unrecognized failure. [stackTraceText] is the formatted stack trace
     * for the originating exception (`Throwable.stackTraceToString()`),
     * preserved here because AIDL can't marshal the live [Throwable].
     * Never empty for backend-side failures; may be `null` for client-side
     * synthesized errors (e.g., timeout from the IPC layer itself).
     */
    @Parcelize
    data class Generic(
        val message: String,
        val stackTraceText: String?,
    ) : RnsError()

    /**
     * Backend hasn't completed initialize() yet. Surfaced by any operation
     * that requires the RNS stack to be running. UI should either wait for
     * the network-status observer to flip to READY or surface a "still
     * starting up" notice.
     */
    @Parcelize
    data object BackendNotReady : RnsError()

    /**
     * Identity wasn't found in the backend's identity store. [hashHex] is
     * the truncated identity hash that was looked up.
     */
    @Parcelize
    data class IdentityNotFound(val hashHex: String) : RnsError()

    /**
     * Operation took longer than the caller's timeout budget. [operation]
     * is a human-readable name of the call; [timeoutMs] is the timeout in
     * milliseconds. Backends emit this for protocol-level timeouts; the
     * IPC layer also synthesizes it when an AIDL callback fails to fire
     * within the suspending caller's deadline.
     */
    @Parcelize
    data class TimeoutExceeded(
        val operation: String,
        val timeoutMs: Long,
    ) : RnsError()

    /**
     * Caller invoked a method whose corresponding capability is
     * [BackendCapabilities.Support.UNSUPPORTED]. Should only occur if a
     * UI gate was missed — every UI surface that calls a capability-gated
     * method is supposed to check the capability first via
     * [BackendCapabilities] or `CapabilityGate`. Emit this with [feature]
     * naming the specific capability path (e.g.,
     * `"performance.batteryProfileTuning"`).
     */
    @Parcelize
    data class FeatureUnsupported(val feature: String) : RnsError()

    /**
     * Telephony state machine refused the requested transition.
     * [expected] is what the operation needed (e.g., `"ESTABLISHED"`);
     * [actual] is the current state name. UI typically just shows a
     * toast and re-renders the call card from the latest [VoiceCallState].
     */
    @Parcelize
    data class CallStateInvalid(
        val expected: String,
        val actual: String,
    ) : RnsError()

    /**
     * NomadNet page request couldn't reach the destination or the
     * destination returned a 404-equivalent. [destHash] is the target
     * destination's hex hash; [path] is the requested page path
     * (`"/page/index.mu"` etc.).
     */
    @Parcelize
    data class NomadnetPageNotFound(
        val destHash: String,
        val path: String,
    ) : RnsError()
}

/**
 * `Throwable` wrapper for [RnsError] so backends can throw and callers
 * can catch with the standard JVM exception machinery while preserving
 * the typed envelope. `:rns-ipc` translates between this exception and
 * the AIDL parcel as data crosses the binder boundary.
 */
class RnsException(val error: RnsError) : RuntimeException(describe(error)) {
    private companion object {
        fun describe(error: RnsError): String =
            when (error) {
                is RnsError.Generic -> error.message
                is RnsError.BackendNotReady -> "Backend not ready"
                is RnsError.IdentityNotFound -> "Identity not found: ${error.hashHex}"
                is RnsError.TimeoutExceeded -> "Timeout (${error.timeoutMs}ms) on ${error.operation}"
                is RnsError.FeatureUnsupported -> "Feature unsupported: ${error.feature}"
                is RnsError.CallStateInvalid ->
                    "Invalid call state: expected ${error.expected}, was ${error.actual}"
                is RnsError.NomadnetPageNotFound ->
                    "NomadNet page not found: ${error.destHash}${error.path}"
            }
    }
}
