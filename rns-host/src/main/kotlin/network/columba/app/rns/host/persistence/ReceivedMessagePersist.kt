package network.columba.app.rns.host.persistence

/**
 * Result of [ServicePersistenceManager.persistReceivedMessage].
 *
 * The service process decides whether it should notify on [Inserted] only; a
 * [Duplicate] means the row was already there (a replay, or the app process won
 * the race to store it) and must never notify again.
 *
 * Inserted-vs-Duplicate is decided from the row's existence, then the
 * notification itself is gated by a compare-and-set claim on
 * `messages.notificationHandled` — so even a cross-process race that makes two
 * callers both believe they inserted still produces exactly one notification.
 */
sealed interface ReceivedMessagePersist {
    /**
     * Stored by this call.
     *
     * @param identityHash identity the message was addressed to
     * @param messageId LXMF message hash (the `messages.id` key)
     * @param peerHash sender's destination hash (hex)
     * @param peerName resolved display name (contact nickname, announced name, or short hash)
     * @param preview stored message content (already attachment-extracted)
     * @param threadIdHex thread id when the message carried `FIELD_THREAD`, else null
     * @param isSavedPeer whether the sender is a contact or a favourited announce
     */
    data class Inserted(
        val identityHash: String,
        val messageId: String,
        val peerHash: String,
        val peerName: String,
        val preview: String,
        val threadIdHex: String?,
        val isSavedPeer: Boolean,
    ) : ReceivedMessagePersist

    /** Already stored for this identity: no notification, ever. */
    data object Duplicate : ReceivedMessagePersist

    /** Blocked sender, unknown sender while blocking is on, or no matching local identity. */
    data object Dropped : ReceivedMessagePersist
}
