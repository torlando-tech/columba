package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Delivery methods for LXMF messages.
 */
@Parcelize
enum class DeliveryMethod : Parcelable {
    /** Single-packet delivery, max 295 bytes content, no link required. */
    OPPORTUNISTIC,

    /** Link-based delivery, unlimited size, with retries. */
    DIRECT,

    /** Delivery via propagation node for offline recipients. */
    PROPAGATED,
}
