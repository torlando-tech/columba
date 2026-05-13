package network.columba.app.rns.api.model

import android.os.Parcel
import android.os.Parcelable

/**
 * Events emitted from an RNS Link (observed via [RnsCore.observeLinks]).
 *
 * Manual Parcelable for the same reason as [NetworkStatus] — @Parcelize
 * doesn't synthesize a polymorphic CREATOR on sealed parents. Each subclass
 * writes a tag + its payload; CREATOR dispatches on the tag.
 */
sealed class LinkEvent : Parcelable {
    override fun describeContents(): Int = 0

    data class Established(val link: Link) : LinkEvent() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_ESTABLISHED)
            parcel.writeParcelable(link, flags)
        }
    }

    data class DataReceived(val link: Link, val data: ByteArray) : LinkEvent() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_DATA_RECEIVED)
            parcel.writeParcelable(link, flags)
            parcel.writeByteArray(data)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DataReceived
            if (link != other.link) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = link.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class Closed(val link: Link, val reason: String?) : LinkEvent() {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(TAG_CLOSED)
            parcel.writeParcelable(link, flags)
            parcel.writeString(reason)
        }
    }

    companion object {
        private const val TAG_ESTABLISHED = 0
        private const val TAG_DATA_RECEIVED = 1
        private const val TAG_CLOSED = 2

        @JvmField
        val CREATOR: Parcelable.Creator<LinkEvent> = object : Parcelable.Creator<LinkEvent> {
            @Suppress("DEPRECATION") // readParcelable(ClassLoader) targets API 24+ device fleet.
            override fun createFromParcel(parcel: Parcel): LinkEvent {
                val tag = parcel.readInt()
                val link = parcel.readParcelable<Link>(Link::class.java.classLoader)
                    ?: error("LinkEvent.createFromParcel: link was null")
                return when (tag) {
                    TAG_ESTABLISHED -> Established(link)
                    TAG_DATA_RECEIVED -> DataReceived(link, parcel.createByteArray() ?: ByteArray(0))
                    TAG_CLOSED -> Closed(link, parcel.readString())
                    else -> error("Unknown LinkEvent tag: $tag")
                }
            }

            override fun newArray(size: Int): Array<LinkEvent?> = arrayOfNulls(size)
        }
    }
}
