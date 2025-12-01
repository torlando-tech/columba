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

    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND identityHash = :identityHash")
    suspend fun updateMessageStatus(
        messageId: String,
        identityHash: String,
        status: String,
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
}
