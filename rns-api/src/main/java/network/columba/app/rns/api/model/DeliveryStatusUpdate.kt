package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Closed protocol lifecycle emitted by both LXMF backends. */
enum class DeliveryStatus(val wireValue: String) {
    PENDING("pending"),
    RETRYING_PROPAGATED("retrying_propagated"),
    PROPAGATED("propagated"),
    DELIVERED("delivered"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWireValue(value: String): DeliveryStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Delivery status update for a sent LXMF message. */
@Parcelize
data class DeliveryStatusUpdate(
    val messageHash: String,
    val status: DeliveryStatus,
    val timestamp: Long,
    /** Immutable owner captured by the backend when the send attempt is created. */
    val originatingIdentityHash: String? = null,
) : Parcelable
