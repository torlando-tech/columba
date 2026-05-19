package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Link(
    val id: String,
    val destination: Destination,
    val status: LinkStatus,
    val establishedAt: Long,
    val rtt: Float?,
) : Parcelable
