package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class Direction : Parcelable {
    @Parcelize
    data object IN : Direction()

    @Parcelize
    data object OUT : Direction()
}
