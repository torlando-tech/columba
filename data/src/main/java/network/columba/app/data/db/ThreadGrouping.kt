package network.columba.app.data.db

import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.ThreadDao
import network.columba.app.data.db.entity.ThreadEntity
import network.columba.app.data.util.ThreadPolicy

/**
 * Receive & grouping algorithm (threads-spec §4.3), shared by both
 * persistence paths — `ConversationRepository.saveMessage` (UI process
 * collector) and `ServicePersistenceManager.persistMessage` (:reticulum
 * service):
 *
 *  - `convKey = (identityHash, peerHash, T)`; `T == null` => Main (no-op here).
 *  - Thread "creation" on receive is implicit: the first message seen with an
 *    unknown T creates the thread row, titled with a truncated excerpt of the
 *    first message.
 *  - When the message also carries a resolved `0x30` target that lives in
 *    Main and this is the first sighting of T, the target becomes the thread
 *    root (the "N replies" hook hangs off it).
 *  - Unknown-root threads simply keep `rootMessageId = null` (P2 renders the
 *    "Thread started off-screen" placeholder). An unresolved `0x30` target
 *    degrades to no root/preview — it never drops the message (persistence of
 *    the message itself happens in the caller, before this runs).
 *
 * Callers must invoke this inside the same transaction as the message insert
 * so first-sighting detection is race-free.
 */
object ThreadGrouping {
    /**
     * Apply the thread side effects of persisting one message.
     *
     * @param threadId raw `FIELD_THREAD` bytes (null = Main => no-op)
     * @param replyToMessageId resolved `0x30` target message id, if any
     * @param content message content used for the implicit title
     * @param activityAt activity watermark (local receive/send time)
     */
    @Suppress("LongParameterList") // DAO pair + full message-activity context; grouping needs all of it atomically
    suspend fun applyMessageActivity(
        threadDao: ThreadDao,
        messageDao: MessageDao,
        identityHash: String,
        conversationHash: String,
        threadId: ByteArray?,
        replyToMessageId: String?,
        content: String,
        activityAt: Long,
    ) {
        if (threadId == null) return

        val existing = threadDao.getThread(identityHash, conversationHash, threadId)
        if (existing == null) {
            // First sighting of T: implicit creation. Attach the root only
            // when the 0x30 target exists, sits in Main (threadId null), and
            // belongs to this same conversation.
            val rootId =
                replyToMessageId?.let { candidate ->
                    val target = messageDao.getMessageById(candidate, identityHash)
                    if (target != null && target.threadId == null && target.conversationHash == conversationHash) {
                        candidate
                    } else {
                        null
                    }
                }
            threadDao.insertThreadIgnore(
                ThreadEntity(
                    threadId = threadId,
                    conversationHash = conversationHash,
                    identityHash = identityHash,
                    rootMessageId = rootId,
                    title = ThreadPolicy.threadTitleExcerpt(content),
                    createdAt = activityAt,
                    lastActivityAt = activityAt,
                    archivedAt = null,
                ),
            )
        } else if (activityAt > existing.lastActivityAt) {
            // Any new message revives a dormant thread; dormancy is derived,
            // nothing else changes (archives are local-only, never wire-set).
            threadDao.touchThread(threadId, identityHash, conversationHash, activityAt)
        }
    }
}
