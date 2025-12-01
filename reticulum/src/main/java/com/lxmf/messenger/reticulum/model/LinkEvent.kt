package com.lxmf.messenger.reticulum.model

sealed class LinkEvent {
    data class Established(val link: Link) : LinkEvent()

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

    data class Closed(val link: Link, val reason: String?) : LinkEvent()
}
