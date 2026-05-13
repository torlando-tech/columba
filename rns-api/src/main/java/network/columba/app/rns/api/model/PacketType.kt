package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class PacketType : Parcelable {
    @Parcelize
    data object DATA : PacketType()

    @Parcelize
    data object ANNOUNCE : PacketType()

    @Parcelize
    data object LINKREQUEST : PacketType()

    @Parcelize
    data object PROOF : PacketType()
}
