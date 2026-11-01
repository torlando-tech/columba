package network.columba.app.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import network.columba.app.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        ORDER BY COALESCE(receivedAt, timestamp) ASC
        """,
    )
    fun getMessagesForConversation(
        peerHash: String,
        identityHash: String,
    ): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        ORDER BY COALESCE(receivedAt, timestamp) DESC LIMIT 1
        """,
    )
    suspend fun getLastMessage(
        peerHash: String,
        identityHash: String,
    ): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationHash = :peerHash AND identityHash = :identityHash")
    suspend fun deleteMessagesForConversation(
        peerHash: String,
        identityHash: String,
    )

    @Query(
        """
        UPDATE messages SET isRead = 1
        WHERE conversationHash = :peerHash AND identityHash = :identityHash AND isFromMe = 0
        """,
    )
    suspend fun markMessagesAsRead(
        peerHash: String,
        identityHash: String,
    )

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND isFromMe = 0 AND isRead = 0
        """,
    )
    suspend fun getUnreadCount(
        peerHash: String,
        identityHash: String,
    ): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId AND identityHash = :identityHash)")
    suspend fun messageExists(
        messageId: String,
        identityHash: String,
    ): Boolean

    @Query("SELECT * FROM messages WHERE id = :messageId AND identityHash = :identityHash LIMIT 1")
    suspend fun getMessageById(
        messageId: String,
        identityHash: String,
    ): MessageEntity?

    @Query(
        "SELECT * FROM messages " +
            "WHERE id = :messageId AND identityHash = :identityHash AND isFromMe = 1 LIMIT 1",
    )
    suspend fun getOutgoingMessageById(
        messageId: String,
        identityHash: String,
    ): MessageEntity?

    /**
     * Observe a message by ID for real-time updates (e.g., status changes).
     * Returns a Flow that emits whenever the message changes in the database.
     */
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    fun observeMessageById(messageId: String): Flow<MessageEntity?>

    /** Invalidates when an outgoing row is inserted, including from another process. */
    @Query("SELECT COUNT(*) FROM messages WHERE isFromMe = 1")
    fun observeOutgoingMessageCount(): Flow<Int>

    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND identityHash = :identityHash")
    suspend fun updateMessageStatus(
        messageId: String,
        identityHash: String,
        status: String,
    )

    /**
     * Applies a protocol delivery event as a closed, monotonic lifecycle.
     * `sent` is accepted only as a legacy/local precursor; it is not a protocol event.
     */
    @Query(
        """
        UPDATE messages
        SET status = CASE
                WHEN :status = 'delivered' AND status IN
                    ('pending', 'sent', 'retrying_propagated', 'propagated', 'failed') THEN 'delivered'
                WHEN :status = 'propagated' AND status IN
                    ('pending', 'sent', 'retrying_propagated', 'failed') THEN 'propagated'
                WHEN :status = 'failed' AND status IN
                    ('pending', 'sent', 'retrying_propagated', 'propagated') THEN 'failed'
                WHEN :status = 'retrying_propagated' AND status IN
                    ('pending', 'sent') THEN 'retrying_propagated'
                ELSE status
            END,
            deliveryMethod = CASE
                WHEN :deliveryMethod IS NOT NULL THEN :deliveryMethod
                WHEN :status IN ('retrying_propagated', 'propagated') THEN 'propagated'
                ELSE deliveryMethod
            END,
            errorMessage = CASE
                WHEN :status IN ('retrying_propagated', 'propagated', 'delivered') THEN NULL
                ELSE errorMessage
            END
        WHERE id = :messageId AND identityHash = :identityHash AND isFromMe = 1
          AND (
            (:status = 'delivered' AND status IN
                ('pending', 'sent', 'retrying_propagated', 'propagated', 'failed', 'delivered')) OR
            (:status = 'propagated' AND status IN
                ('pending', 'sent', 'retrying_propagated', 'failed', 'propagated')) OR
            (:status = 'failed' AND status IN
                ('pending', 'sent', 'retrying_propagated', 'propagated', 'failed')) OR
            (:status = 'retrying_propagated' AND status IN
                ('pending', 'sent', 'retrying_propagated'))
          )
        """,
    )
    suspend fun applyDeliveryStatus(
        messageId: String,
        identityHash: String,
        status: String,
        deliveryMethod: String? = null,
    ): Int

    /**
     * Atomically reduces an identity-owned outgoing row and returns its resulting snapshot.
     * The immutable callback identity is the authority; active-identity state is never read.
     */
    @Transaction
    suspend fun applyDeliveryStatusAndGet(
        messageId: String,
        identityHash: String,
        status: String,
        deliveryMethod: String? = null,
    ): MessageEntity? {
        if (identityHash.isBlank()) return null
        applyDeliveryStatus(messageId, identityHash, status, deliveryMethod)
        return getOutgoingMessageById(messageId, identityHash)
    }

    @Query(
        """
        UPDATE messages
        SET deliveryMethod = :deliveryMethod, errorMessage = :errorMessage
        WHERE id = :messageId AND identityHash = :identityHash
        """,
    )
    suspend fun updateMessageDeliveryDetails(
        messageId: String,
        identityHash: String,
        deliveryMethod: String?,
        errorMessage: String?,
    )

    @Query(
        """
        UPDATE messages SET sentInterface = :sentInterface
        WHERE id = :messageId AND identityHash = :identityHash
        """,
    )
    suspend fun updateSentInterface(
        messageId: String,
        identityHash: String,
        sentInterface: String?,
    )

    @Query(
        """
        UPDATE messages
        SET fieldsJson = :fieldsJson
        WHERE id = :messageId AND identityHash = :identityHash
        """,
    )
    suspend fun updateMessageFieldsJson(
        messageId: String,
        identityHash: String,
        fieldsJson: String?,
    )

    @Query(
        """
        UPDATE messages
        SET reactionsJson = :reactionsJson
        WHERE id = :messageId AND identityHash = :identityHash
        """,
    )
    suspend fun updateMessageReactionsJson(
        messageId: String,
        identityHash: String,
        reactionsJson: String?,
    )

    // Paging3 method for infinite scroll

    /**
     * Get messages for conversation with pagination support.
     * Returns messages in DESC order (newest first) for efficient pagination.
     * UI displays with reverseLayout to show newest at bottom.
     * Sorts by receivedAt (local clock) to handle sender clock skew.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        ORDER BY COALESCE(receivedAt, timestamp) DESC
        """,
    )
    fun getMessagesForConversationPaged(
        peerHash: String,
        identityHash: String,
    ): PagingSource<Int, MessageEntity>

    /**
     * Get messages sorted by sender's timestamp (for "sort by sent time" preference).
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        ORDER BY timestamp DESC
        """,
    )
    fun getMessagesForConversationPagedBySentTime(
        peerHash: String,
        identityHash: String,
    ): PagingSource<Int, MessageEntity>

    /**
     * Get IDs of received (not from me) messages for an identity since a cutoff time.
     * Used to pre-seed duplicate notification prevention cache at startup.
     * Bounded to recent messages to avoid unbounded memory growth.
     */
    @Query("SELECT id FROM messages WHERE identityHash = :identityHash AND isFromMe = 0 AND timestamp >= :since")
    suspend fun getReceivedMessageIds(
        identityHash: String,
        since: Long,
    ): List<String>

    /**
     * Get all messages for an identity (sync, for export).
     */
    @Query("SELECT * FROM messages WHERE identityHash = :identityHash ORDER BY COALESCE(receivedAt, timestamp) ASC")
    suspend fun getAllMessagesForIdentity(identityHash: String): List<MessageEntity>

    /**
     * Bulk insert messages (for import).
     * Uses REPLACE to update existing messages.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    /**
     * Bulk insert messages for migration, ignoring duplicates.
     * Uses IGNORE to preserve existing messages (prevents LXMF replay from overwriting
     * imported message timestamps and status).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessagesIgnoreDuplicates(messages: List<MessageEntity>)

    /**
     * Delete a message by ID.
     * Used when updating message ID (primary key cannot be updated directly).
     */
    @Query("DELETE FROM messages WHERE id = :messageId AND identityHash = :identityHash")
    suspend fun deleteMessageById(
        messageId: String,
        identityHash: String,
    )

    /**
     * Get lightweight reply preview data for a message.
     * Returns minimal data needed to display a reply preview (sender, content preview, attachment info).
     * Used when displaying a message that is replying to another message.
     */
    @Query(
        """
        SELECT id, content, isFromMe, fieldsJson, conversationHash
        FROM messages
        WHERE id = :messageId AND identityHash = :identityHash
        LIMIT 1
        """,
    )
    suspend fun getReplyPreviewData(
        messageId: String,
        identityHash: String,
    ): ReplyPreviewEntity?

    /**
     * Find pending file notification messages in a conversation.
     * These are lightweight system messages sent when a file falls back to propagation,
     * notifying the recipient that a file is coming via relay.
     *
     * Returns messages where fieldsJson contains "pending_file_notification" and
     * does NOT contain "superseded":true (not yet dismissed).
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash
        AND identityHash = :identityHash
        AND fieldsJson LIKE '%pending_file_notification%'
        AND fieldsJson NOT LIKE '%"superseded":true%'
        ORDER BY timestamp DESC
        """,
    )
    suspend fun findPendingFileNotifications(
        peerHash: String,
        identityHash: String,
    ): List<MessageEntity>

    // ==================== Threads (P1, threads-spec §5) ====================

    /**
     * Messages of one thread, oldest first (thread view: pinned root +
     * replies in time order). `threadId` is the raw FIELD_THREAD bytes;
     * exact-byte grouping, any length.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId
        ORDER BY COALESCE(receivedAt, timestamp) ASC
        """,
    )
    fun getMessagesForThread(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    ): Flow<List<MessageEntity>>

    /**
     * Oldest message of one thread (thread-view header preview). LIMIT 1 so
     * deriving the header never reads the whole reply history.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId
        ORDER BY COALESCE(receivedAt, timestamp) ASC
        LIMIT 1
        """,
    )
    suspend fun getFirstMessageForThread(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    ): MessageEntity?

    /** Paged thread messages (newest first), mirroring the Main conversation paging. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId
        ORDER BY COALESCE(receivedAt, timestamp) DESC
        """,
    )
    fun getMessagesForThreadPaged(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    ): PagingSource<Int, MessageEntity>

    /** Paged thread messages by sender timestamp (the "sort by sent time" preference). */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId
        ORDER BY timestamp DESC
        """,
    )
    fun getMessagesForThreadPagedBySentTime(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    ): PagingSource<Int, MessageEntity>

    /**
     * Main-conversation messages only (threadId IS NULL) — the Main chip /
     * message list once threads are enabled.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NULL
        ORDER BY COALESCE(receivedAt, timestamp) ASC
        """,
    )
    fun getMainMessages(
        peerHash: String,
        identityHash: String,
    ): Flow<List<MessageEntity>>

    /** Unread received-message count for one thread (chip unread dot). */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId AND isFromMe = 0 AND isRead = 0
        """,
    )
    suspend fun getUnreadCountForThread(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    ): Int

    /** Unread received-message count for Main (threadId IS NULL). */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NULL AND isFromMe = 0 AND isRead = 0
        """,
    )
    suspend fun getUnreadCountForMain(
        peerHash: String,
        identityHash: String,
    ): Int

    /**
     * Unread received-message counts grouped by thread ID. The row with
     * `threadId == null` is Main's count; every other row maps to a thread
     * (thread sheet / chip bar roll-up).
     */
    @Query(
        """
        SELECT threadId, COUNT(*) AS unreadCount FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND isFromMe = 0 AND isRead = 0
        GROUP BY threadId
        """,
    )
    fun observeUnreadCountsPerThread(
        peerHash: String,
        identityHash: String,
    ): Flow<List<ThreadUnreadCount>>

    /** Mark one thread's received messages read (opening a thread view). */
    @Query(
        """
        UPDATE messages SET isRead = 1
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId AND isFromMe = 0
        """,
    )
    suspend fun markThreadMessagesAsRead(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    )

    /** Paged Main-conversation messages only (threads enabled): newest first, `threadId IS NULL`. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NULL
        ORDER BY COALESCE(receivedAt, timestamp) DESC
        """,
    )
    fun getMainMessagesPaged(
        peerHash: String,
        identityHash: String,
    ): PagingSource<Int, MessageEntity>

    /** Paged Main-conversation messages by sender timestamp (the "sort by sent time" preference). */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NULL
        ORDER BY timestamp DESC
        """,
    )
    fun getMainMessagesPagedBySentTime(
        peerHash: String,
        identityHash: String,
    ): PagingSource<Int, MessageEntity>

    /**
     * Total message counts grouped by thread ID (the "N replies" hook count).
     * The row with `threadId == null` is Main's count.
     */
    @Query(
        """
        SELECT threadId, COUNT(*) AS messageCount FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        GROUP BY threadId
        """,
    )
    fun observeMessageCountsPerThread(
        peerHash: String,
        identityHash: String,
    ): Flow<List<ThreadMessageCount>>

    /**
     * Newest message per thread (title preview row in the thread sheet /
     * expanding chat card). SQLite's bare-column-with-MAX special case makes
     * `content` come from the max-timestamp row of each group. Main
     * (`threadId IS NULL`) is excluded — the conversation row already carries
     * Main's last message.
     */
    @Query(
        """
        SELECT threadId, content, MAX(COALESCE(receivedAt, timestamp)) AS timestamp
        FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NOT NULL
        GROUP BY threadId
        """,
    )
    fun observeLastMessagesPerThread(
        peerHash: String,
        identityHash: String,
    ): Flow<List<ThreadLastMessage>>

    /**
     * Newest Main message of one conversation (the expanding chat card's
     * Main-row preview — the conversation row's `lastMessage` may be a
     * thread message, so Main needs its own lookup). Empty blob threadId:
     * Main rows store NULL.
     */
    @Query(
        """
        SELECT IFNULL(threadId, x'') AS threadId, content, COALESCE(receivedAt, timestamp) AS timestamp
        FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NULL
        ORDER BY COALESCE(receivedAt, timestamp) DESC LIMIT 1
        """,
    )
    fun observeMainLastMessage(
        peerHash: String,
        identityHash: String,
    ): Flow<ThreadLastMessage?>

    /** Mark Main (threadId IS NULL) received messages read — thread-aware read path. */
    @Query(
        """
        UPDATE messages SET isRead = 1
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId IS NULL AND isFromMe = 0
        """,
    )
    suspend fun markMainMessagesAsRead(
        peerHash: String,
        identityHash: String,
    )

    /** Delete a thread's messages (local thread delete; Main rows untouched). */
    @Query(
        """
        DELETE FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        AND threadId = :threadId
        """,
    )
    suspend fun deleteMessagesForThread(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    )
}

/** One row of [MessageDao.observeUnreadCountsPerThread]; null [threadId] = Main. */
data class ThreadUnreadCount(
    val threadId: ByteArray?,
    val unreadCount: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ThreadUnreadCount &&
                unreadCount == other.unreadCount &&
                threadId.contentEquals(other.threadId))

    override fun hashCode(): Int = 31 * (threadId?.contentHashCode() ?: 0) + unreadCount
}

/** One row of [MessageDao.observeMessageCountsPerThread]; null [threadId] = Main. */
data class ThreadMessageCount(
    val threadId: ByteArray?,
    val messageCount: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ThreadMessageCount &&
                messageCount == other.messageCount &&
                threadId.contentEquals(other.threadId))

    override fun hashCode(): Int = 31 * (threadId?.contentHashCode() ?: 0) + messageCount
}

/** One row of [MessageDao.observeLastMessagesPerThread] (newest message per thread). */
data class ThreadLastMessage(
    val threadId: ByteArray,
    val content: String,
    val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ThreadLastMessage &&
                    content == other.content &&
                    timestamp == other.timestamp &&
                    threadId.contentEquals(other.threadId)
            )

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + threadId.contentHashCode()
        return result
    }
}

/**
 * Lightweight entity for reply preview data.
 * Contains only the fields needed to display a reply preview, avoiding
 * loading full message data with large attachment payloads.
 */
data class ReplyPreviewEntity(
    val id: String,
    val content: String,
    val isFromMe: Boolean,
    val fieldsJson: String?,
    val conversationHash: String,
)
