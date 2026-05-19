package network.columba.app.rns.api.model

import android.os.Parcel
import android.os.Parcelable

/**
 * Voice call lifecycle state crossing the `:rns-api` seam.
 *
 * Mirrors the shape of LXST's `tech.torlando.lxst.core.CallState` (the
 * in-process state machine used by the backend) but lives in `:rns-api`
 * so UI code can observe call state without taking a compile-time
 * dependency on the LXST library that the backend selection happens to
 * use. Both the Kotlin-native and Python-Chaquopy backends translate
 * their internal state into this envelope at the seam boundary.
 *
 * Manual Parcelable implementation rather than @Parcelize because
 * `@Parcelize` does not synthesize a polymorphic CREATOR on sealed
 * parents — AIDL needs `CallState.CREATOR` to round-trip values typed
 * as the sealed parent. Each subclass writes a small int tag + its
 * payload; CREATOR dispatches on the tag. Same pattern as [RnsError].
 */
sealed class CallState : Parcelable {
    override fun describeContents(): Int = 0

    /** No active call. Steady state. */
    data object Idle : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_IDLE)
        }
    }

    /**
     * Outgoing call: protocol layer has been told to dial; link is
     * being established. UI shows a "Connecting…" indicator.
     */
    data class Connecting(val identityHash: String) : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_CONNECTING)
            parcel.writeString(identityHash)
        }
    }

    /**
     * Outgoing call: link is up and the remote is being notified.
     * UI shows a "Ringing…" indicator.
     */
    data class Ringing(val identityHash: String) : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_RINGING)
            parcel.writeString(identityHash)
        }
    }

    /**
     * Incoming call: a remote identity is requesting a voice session.
     * UI shows the lock-screen / answer-or-decline UI.
     */
    data class Incoming(val identityHash: String) : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_INCOMING)
            parcel.writeString(identityHash)
        }
    }

    /** Call established; audio is flowing in both directions. */
    data class Active(val identityHash: String) : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_ACTIVE)
            parcel.writeString(identityHash)
        }
    }

    /** Remote replied that they are already on a call. */
    data object Busy : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_BUSY)
        }
    }

    /** Local or remote actively rejected the call. */
    data object Rejected : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_REJECTED)
        }
    }

    /** Call ended normally. Transient — caller will return to [Idle]. */
    data object Ended : CallState() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_ENDED)
        }
    }

    companion object {
        private const val TAG_IDLE = 0
        private const val TAG_CONNECTING = 1
        private const val TAG_RINGING = 2
        private const val TAG_INCOMING = 3
        private const val TAG_ACTIVE = 4
        private const val TAG_BUSY = 5
        private const val TAG_REJECTED = 6
        private const val TAG_ENDED = 7

        @JvmField
        val CREATOR: Parcelable.Creator<CallState> = object : Parcelable.Creator<CallState> {
            override fun createFromParcel(parcel: Parcel): CallState =
                when (val tag = parcel.readInt()) {
                    TAG_IDLE -> Idle
                    TAG_CONNECTING -> Connecting(parcel.readString().orEmpty())
                    TAG_RINGING -> Ringing(parcel.readString().orEmpty())
                    TAG_INCOMING -> Incoming(parcel.readString().orEmpty())
                    TAG_ACTIVE -> Active(parcel.readString().orEmpty())
                    TAG_BUSY -> Busy
                    TAG_REJECTED -> Rejected
                    TAG_ENDED -> Ended
                    else -> error("Unknown CallState tag: $tag")
                }

            override fun newArray(size: Int): Array<CallState?> = arrayOfNulls(size)
        }
    }
}
