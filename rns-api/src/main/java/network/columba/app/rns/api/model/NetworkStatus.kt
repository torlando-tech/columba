package network.columba.app.rns.api.model

import android.os.Parcel
import android.os.Parcelable

/**
 * Snapshot of the running RNS stack's lifecycle state.
 *
 * Manual Parcelable implementation rather than @Parcelize because @Parcelize
 * doesn't synthesize a polymorphic CREATOR on sealed parents — AIDL needs
 * `NetworkStatus.CREATOR` to round-trip values typed as the sealed parent.
 * Each subclass writes a small int tag + any payload; CREATOR dispatches on
 * the tag.
 */
sealed class NetworkStatus : Parcelable {
    override fun describeContents(): Int = 0

    data object INITIALIZING : NetworkStatus() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_INITIALIZING)
        }
    }

    data object CONNECTING : NetworkStatus() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_CONNECTING)
        }
    }

    data object READY : NetworkStatus() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_READY)
        }
    }

    data class ERROR(val message: String) : NetworkStatus() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_ERROR)
            parcel.writeString(message)
        }
    }

    data object SHUTDOWN : NetworkStatus() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_SHUTDOWN)
        }
    }

    companion object {
        private const val TAG_INITIALIZING = 0
        private const val TAG_CONNECTING = 1
        private const val TAG_READY = 2
        private const val TAG_ERROR = 3
        private const val TAG_SHUTDOWN = 4

        @JvmField
        val CREATOR: Parcelable.Creator<NetworkStatus> = object : Parcelable.Creator<NetworkStatus> {
            override fun createFromParcel(parcel: Parcel): NetworkStatus =
                when (val tag = parcel.readInt()) {
                    TAG_INITIALIZING -> INITIALIZING
                    TAG_CONNECTING -> CONNECTING
                    TAG_READY -> READY
                    TAG_ERROR -> ERROR(parcel.readString().orEmpty())
                    TAG_SHUTDOWN -> SHUTDOWN
                    else -> error("Unknown NetworkStatus tag: $tag")
                }

            override fun newArray(size: Int): Array<NetworkStatus?> = arrayOfNulls(size)
        }
    }
}
