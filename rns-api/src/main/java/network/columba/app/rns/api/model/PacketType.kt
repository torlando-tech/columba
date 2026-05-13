package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Reticulum packet type — DATA, ANNOUNCE, LINKREQUEST, PROOF.
 *
 * Modeled as an enum (not a sealed class with data objects) for @Parcelize
 * compatibility — see [Direction] for the rationale.
 */
@Parcelize
enum class PacketType : Parcelable {
    DATA,
    ANNOUNCE,
    LINKREQUEST,
    PROOF,
}
