package com.lxmf.messenger.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        ORDER BY timestamp ASC
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
        ORDER BY timestamp DESC LIMIT 1
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

    /**
     * Observe a message by ID for real-time updates (e.g., status changes).
     * Returns a Flow that emits whenever the message changes in the database.
     */
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    fun observeMessageById(messageId: String): Flow<MessageEntity?>

    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND identityHash = :identityHash")
    suspend fun updateMessageStatus(
        messageId: String,
        identityHash: String,
        status: String,
    )

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

    // Paging3 method for infinite scroll

    /**
     * Get messages for conversation with pagination support.
     * Returns messages in DESC order (newest first) for efficient pagination.
     * UI displays with reverseLayout to show newest at bottom.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationHash = :peerHash AND identityHash = :identityHash
        ORDER BY timestamp DESC
        """,
    )
    fun getMessagesForConversationPaged(
        peerHash: String,
        identityHash: String,
    ): PagingSource<Int, MessageEntity>

    /**
     * Get all messages for an identity (sync, for export).
     */
    @Query("SELECT * FROM messages WHERE identityHash = :identityHash ORDER BY timestamp ASC")
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
