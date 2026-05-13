package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Reticulum destination type — SINGLE (point-to-point), GROUP (shared key),
 * PLAIN (unencrypted).
 *
 * Modeled as an enum (not a sealed class with data objects) for @Parcelize
 * compatibility — see [Direction] for the rationale.
 */
@Parcelize
enum class DestinationType : Parcelable {
    SINGLE,
    GROUP,
    PLAIN,
}
