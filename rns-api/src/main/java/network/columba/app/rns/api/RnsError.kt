package network.columba.app.rns.api

import android.os.Parcel
import android.os.Parcelable

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
 *
 * Manual Parcelable implementation rather than @Parcelize because @Parcelize
 * doesn't synthesize a polymorphic CREATOR on sealed parents — AIDL needs
 * `RnsError.CREATOR` to round-trip values typed as the sealed parent. Each
 * subclass writes a small int tag + its payload; CREATOR dispatches on the
 * tag.
 */
sealed class RnsError : Parcelable {
    override fun describeContents(): Int = 0

    /**
     * Unrecognized failure. [stackTraceText] is the formatted stack trace
     * for the originating exception (`Throwable.stackTraceToString()`),
     * preserved here because AIDL can't marshal the live [Throwable].
     * Never empty for backend-side failures; may be `null` for client-side
     * synthesized errors (e.g., timeout from the IPC layer itself).
     */
    data class Generic(
        val message: String,
        val stackTraceText: String?,
    ) : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_GENERIC)
            parcel.writeString(message)
            parcel.writeString(stackTraceText)
        }
    }

    /**
     * Backend hasn't completed initialize() yet. Surfaced by any operation
     * that requires the RNS stack to be running. UI should either wait for
     * the network-status observer to flip to READY or surface a "still
     * starting up" notice.
     */
    data object BackendNotReady : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_BACKEND_NOT_READY)
        }
    }

    /**
     * Identity wasn't found in the backend's identity store. [hashHex] is
     * the truncated identity hash that was looked up.
     */
    data class IdentityNotFound(val hashHex: String) : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_IDENTITY_NOT_FOUND)
            parcel.writeString(hashHex)
        }
    }

    /**
     * Operation took longer than the caller's timeout budget. [operation]
     * is a human-readable name of the call; [timeoutMs] is the timeout in
     * milliseconds. Backends emit this for protocol-level timeouts; the
     * IPC layer also synthesizes it when an AIDL callback fails to fire
     * within the suspending caller's deadline.
     */
    data class TimeoutExceeded(
        val operation: String,
        val timeoutMs: Long,
    ) : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_TIMEOUT_EXCEEDED)
            parcel.writeString(operation)
            parcel.writeLong(timeoutMs)
        }
    }

    /**
     * Caller invoked a method whose corresponding capability is
     * [BackendCapabilities.Support.UNSUPPORTED]. Should only occur if a
     * UI gate was missed — every UI surface that calls a capability-gated
     * method is supposed to check the capability first via
     * [BackendCapabilities] or `CapabilityGate`. Emit this with [feature]
     * naming the specific capability path (e.g.,
     * `"performance.batteryProfileTuning"`).
     */
    data class FeatureUnsupported(val feature: String) : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_FEATURE_UNSUPPORTED)
            parcel.writeString(feature)
        }
    }

    /**
     * Telephony state machine refused the requested transition.
     * [expected] is what the operation needed (e.g., `"ESTABLISHED"`);
     * [actual] is the current state name. UI typically just shows a
     * toast and re-renders the call card from the latest [VoiceCallState].
     */
    data class CallStateInvalid(
        val expected: String,
        val actual: String,
    ) : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_CALL_STATE_INVALID)
            parcel.writeString(expected)
            parcel.writeString(actual)
        }
    }

    /**
     * NomadNet page request couldn't reach the destination or the
     * destination returned a 404-equivalent. [destHash] is the target
     * destination's hex hash; [path] is the requested page path
     * (`"/page/index.mu"` etc.).
     */
    data class NomadnetPageNotFound(
        val destHash: String,
        val path: String,
    ) : RnsError() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_NOMADNET_PAGE_NOT_FOUND)
            parcel.writeString(destHash)
            parcel.writeString(path)
        }
    }

    companion object {
        private const val TAG_GENERIC = 0
        private const val TAG_BACKEND_NOT_READY = 1
        private const val TAG_IDENTITY_NOT_FOUND = 2
        private const val TAG_TIMEOUT_EXCEEDED = 3
        private const val TAG_FEATURE_UNSUPPORTED = 4
        private const val TAG_CALL_STATE_INVALID = 5
        private const val TAG_NOMADNET_PAGE_NOT_FOUND = 6

        @JvmField
        val CREATOR: Parcelable.Creator<RnsError> = object : Parcelable.Creator<RnsError> {
            override fun createFromParcel(parcel: Parcel): RnsError =
                when (val tag = parcel.readInt()) {
                    TAG_GENERIC -> Generic(parcel.readString().orEmpty(), parcel.readString())
                    TAG_BACKEND_NOT_READY -> BackendNotReady
                    TAG_IDENTITY_NOT_FOUND -> IdentityNotFound(parcel.readString().orEmpty())
                    TAG_TIMEOUT_EXCEEDED -> TimeoutExceeded(parcel.readString().orEmpty(), parcel.readLong())
                    TAG_FEATURE_UNSUPPORTED -> FeatureUnsupported(parcel.readString().orEmpty())
                    TAG_CALL_STATE_INVALID -> CallStateInvalid(parcel.readString().orEmpty(), parcel.readString().orEmpty())
                    TAG_NOMADNET_PAGE_NOT_FOUND -> NomadnetPageNotFound(parcel.readString().orEmpty(), parcel.readString().orEmpty())
                    else -> error("Unknown RnsError tag: $tag")
                }

            override fun newArray(size: Int): Array<RnsError?> = arrayOfNulls(size)
        }
    }
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
                is RnsError.Generic ->
                    // Generic is the "unexpected failure" bucket — append the backend
                    // stack text (preserved across the AIDL boundary because the live
                    // Throwable can't cross) so it lands in logs/Sentry. For the python
                    // backend this carries Chaquopy's python traceback frames.
                    if (error.stackTraceText.isNullOrBlank()) {
                        error.message
                    } else {
                        "${error.message}\n${error.stackTraceText}"
                    }
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
