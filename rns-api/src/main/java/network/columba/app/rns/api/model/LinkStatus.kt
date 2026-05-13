package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * RNS link status — PENDING, ACTIVE, STALE, CLOSED.
 *
 * Modeled as an enum (not a sealed class with data objects) for @Parcelize
 * compatibility — see [Direction] for the rationale.
 */
@Parcelize
enum class LinkStatus : Parcelable {
    PENDING,
    ACTIVE,
    STALE,
    CLOSED,
}
