package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Direction of a [Destination] — IN for receive, OUT for send.
 *
 * Modeled as an enum (not a sealed class with data objects) because every
 * variant is fieldless and @Parcelize generates the polymorphic CREATOR for
 * enums but not for sealed parents.
 */
@Parcelize
enum class Direction : Parcelable {
    IN,
    OUT,
}
