package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class DestinationType : Parcelable {
    @Parcelize
    data object SINGLE : DestinationType()

    @Parcelize
    data object GROUP : DestinationType()

    @Parcelize
    data object PLAIN : DestinationType()
}
