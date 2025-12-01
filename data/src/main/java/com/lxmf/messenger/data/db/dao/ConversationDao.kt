package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE identityHash = :identityHash ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(identityHash: String): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        WHERE identityHash = :identityHash AND peerName LIKE '%' || :query || '%'
        ORDER BY lastMessageTimestamp DESC
        """,
    )
    fun searchConversations(
        identityHash: String,
        query: String,
    ): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerHash = :peerHash AND identityHash = :identityHash")
    suspend fun getConversation(
        peerHash: String,
        identityHash: String,
    ): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE peerHash = :peerHash AND identityHash = :identityHash")
    suspend fun deleteConversationByKey(
        peerHash: String,
        identityHash: String,
    )

    @Query(
        """
        UPDATE conversations SET unreadCount = 0, lastSeenTimestamp = :timestamp
        WHERE peerHash = :peerHash AND identityHash = :identityHash
        """,
    )
    suspend fun markAsRead(
        peerHash: String,
        identityHash: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE conversations SET unreadCount = unreadCount + 1
        WHERE peerHash = :peerHash AND identityHash = :identityHash
        """,
    )
    suspend fun incrementUnreadCount(
        peerHash: String,
        identityHash: String,
    )

    @Query(
        """
        UPDATE conversations SET peerName = :peerName
        WHERE peerHash = :peerHash AND identityHash = :identityHash
        """,
    )
    suspend fun updatePeerName(
        peerHash: String,
        identityHash: String,
        peerName: String,
    )

    @Query("SELECT * FROM conversations WHERE identityHash = :identityHash")
    suspend fun getAllConversationsList(identityHash: String): List<ConversationEntity>
}
