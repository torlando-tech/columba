package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class LinkStatus : Parcelable {
    @Parcelize
    data object PENDING : LinkStatus()

    @Parcelize
    data object ACTIVE : LinkStatus()

    @Parcelize
    data object STALE : LinkStatus()

    @Parcelize
    data object CLOSED : LinkStatus()
}
