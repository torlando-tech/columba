package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Receipt for a sent LXMF message.
 */
@Parcelize
data class MessageReceipt(
    val messageHash: ByteArray,
    val timestamp: Long,
    // Actual LXMF destination hash used for sending.
    val destinationHash: ByteArray,
) : Parcelable
