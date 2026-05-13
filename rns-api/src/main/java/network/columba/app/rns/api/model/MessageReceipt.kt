package network.columba.app.rns.api.model

/**
 * Receipt for a sent LXMF message.
 */
data class MessageReceipt(
    val messageHash: ByteArray,
    val timestamp: Long,
    // Actual LXMF destination hash used for sending.
    val destinationHash: ByteArray,
)
