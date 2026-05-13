package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class LinkEvent : Parcelable {
    @Parcelize
    data class Established(val link: Link) : LinkEvent()

    @Parcelize
    data class DataReceived(val link: Link, val data: ByteArray) : LinkEvent() {
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

    @Parcelize
    data class Closed(val link: Link, val reason: String?) : LinkEvent()
}
