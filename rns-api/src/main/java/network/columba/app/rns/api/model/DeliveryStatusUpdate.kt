package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Delivery status update for a sent LXMF message.
 *
 * Backends emit the backend-originated subset of [DeliveryState]:
 * [DeliveryState.Delivered], [DeliveryState.Propagated],
 * [DeliveryState.RetryingViaPropagation], and [DeliveryState.Failed].
 * ([DeliveryState.Pending]/[DeliveryState.Sent] are UI-originated.)
 */
@Parcelize
data class DeliveryStatusUpdate(
    val messageHash: String,
    val state: DeliveryState,
    val timestamp: Long,
) : Parcelable
