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
    /** Sender's LXMF delivery destination for incoming direct Resources, when the link identifies it. */
    val sourceDestinationHash: String? = null,
    val direction: Direction,
    /** Inclusive 0.0 to 1.0 progress reported by LXMF/RNS. */
    val progress: Float,
    val phase: TransferPhase,
    val totalBytes: Long? = null,
    val deliveryMethod: DeliveryMethod? = null,
    /** Current backend delivery attempt while preparing/retrying, when available. */
    val currentAttempt: Int? = null,
    /** Backend-configured delivery attempt ceiling, when available. */
    val maxAttempts: Int? = null,
) : Parcelable {
    val isTerminal: Boolean
        get() = phase == TransferPhase.COMPLETE || phase == TransferPhase.FAILED

    fun isIncomingForConversation(destinationHash: String): Boolean =
        direction == Direction.IN &&
            sourceDestinationHash?.equals(destinationHash, ignoreCase = true) == true
}

@Parcelize
enum class TransferPhase : Parcelable {
    PREPARING,
    TRANSFERRING,
    COMPLETE,
    FAILED,
}
