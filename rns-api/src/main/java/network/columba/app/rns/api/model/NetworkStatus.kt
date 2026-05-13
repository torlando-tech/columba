package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class NetworkStatus : Parcelable {
    @Parcelize
    data object INITIALIZING : NetworkStatus()

    @Parcelize
    data object CONNECTING : NetworkStatus()

    @Parcelize
    data object READY : NetworkStatus()

    @Parcelize
    data class ERROR(val message: String) : NetworkStatus()

    @Parcelize
    data object SHUTDOWN : NetworkStatus()
}
