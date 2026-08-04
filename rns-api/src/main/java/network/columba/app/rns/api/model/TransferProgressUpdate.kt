package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Ephemeral progress for one LXMF Resource transfer. Never persisted as message state. */
@Parcelize
data class TransferProgressUpdate(
    /** Resource hash when available; otherwise the outgoing message hash. */
    val transferId: String,
    /** LXMF message hash for outgoing transfers. Incoming Resources have no message hash until decoded. */
    val messageHash: String? = null,
    val direction: Direction,
    /** Inclusive 0.0 to 1.0 progress reported by LXMF/RNS. */
    val progress: Float,
    val phase: TransferPhase,
    val totalBytes: Long? = null,
    val deliveryMethod: DeliveryMethod? = null,
) : Parcelable {
    val isTerminal: Boolean
        get() = phase == TransferPhase.COMPLETE || phase == TransferPhase.FAILED
}

@Parcelize
enum class TransferPhase : Parcelable {
    PREPARING,
    TRANSFERRING,
    COMPLETE,
    FAILED,
}
