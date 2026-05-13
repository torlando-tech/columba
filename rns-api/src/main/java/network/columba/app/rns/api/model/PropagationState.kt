package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * State of propagation node message sync/transfer.
 */
@Parcelize
data class PropagationState(
    /** Numeric state code (0=idle, 1=path_requested, 2=link_establishing, etc.). */
    val state: Int,
    /** Human-readable state name. */
    val stateName: String,
    /** Transfer progress (0.0 to 1.0). */
    val progress: Float,
    /** Number of messages received in the last completed transfer. */
    val messagesReceived: Int,
) : Parcelable {
    companion object {
        // These constants mirror LXMF.LXMRouter.PR_* from Python LXMF library.
        // Keep in sync with LXMF/LXMRouter.py propagation transfer states.
        const val STATE_IDLE = 0 // PR_IDLE
        const val STATE_PATH_REQUESTED = 1 // PR_PATH_REQUESTED
        const val STATE_LINK_ESTABLISHING = 2 // PR_LINK_ESTABLISHING
        const val STATE_LINK_ESTABLISHED = 3 // PR_LINK_ESTABLISHED
        const val STATE_REQUEST_SENT = 4 // PR_REQUEST_SENT
        const val STATE_RECEIVING = 5 // PR_RECEIVING
        const val STATE_RESPONSE_RECEIVED = 6 // PR_RESPONSE_RECEIVED
        const val STATE_COMPLETE = 7 // PR_COMPLETE

        val IDLE = PropagationState(STATE_IDLE, "idle", 0f, 0)
    }
}
