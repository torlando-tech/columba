package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.model.EnrichedConversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE identityHash = :identityHash ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(identityHash: String): Flow<List<ConversationEntity>>

    /**
     * Get enriched conversations with profile icon data from announces and display names from contacts.
     * Combines conversation data with icon appearance from announces table and nicknames from contacts.
     *
     * Display name priority (via COALESCE):
     * 1. contacts.customNickname - User-set nickname (highest priority)
     * 2. announces.peerName - Network broadcast name
     * 3. conversations.peerName - Snapshot from conversation creation
     * 4. conversations.peerHash - Fallback to hash (never null)
     */
    @Query(
        """
        SELECT
            c.peerHash,
            c.peerName,
            COALESCE(ct.customNickname, a.peerName, c.peerName, c.peerHash) as displayName,
            c.peerPublicKey,
            c.lastMessage,
            c.lastMessageTimestamp,
            c.unreadCount,
            a.iconName as iconName,
            a.iconForegroundColor as iconForegroundColor,
            a.iconBackgroundColor as iconBackgroundColor
        FROM conversations c
        LEFT JOIN announces a ON c.peerHash = a.destinationHash
        LEFT JOIN contacts ct ON c.peerHash = ct.destinationHash AND c.identityHash = ct.identityHash
        WHERE c.identityHash = :identityHash
        ORDER BY c.lastMessageTimestamp DESC
        """,
    )
    fun getEnrichedConversations(identityHash: String): Flow<List<EnrichedConversation>>

    /**
     * Search enriched conversations by display name (nickname, announce name, or peer name).
     * Searches across all name sources for better discoverability.
     */
    @Query(
        """
        SELECT
            c.peerHash,
            c.peerName,
            COALESCE(ct.customNickname, a.peerName, c.peerName, c.peerHash) as displayName,
            c.peerPublicKey,
            c.lastMessage,
            c.lastMessageTimestamp,
            c.unreadCount,
            a.iconName as iconName,
            a.iconForegroundColor as iconForegroundColor,
            a.iconBackgroundColor as iconBackgroundColor
        FROM conversations c
        LEFT JOIN announces a ON c.peerHash = a.destinationHash
        LEFT JOIN contacts ct ON c.peerHash = ct.destinationHash AND c.identityHash = ct.identityHash
        WHERE c.identityHash = :identityHash
            AND (ct.customNickname LIKE '%' || :query || '%'
                OR a.peerName LIKE '%' || :query || '%'
                OR c.peerName LIKE '%' || :query || '%'
                OR c.peerHash LIKE '%' || :query || '%')
        ORDER BY c.lastMessageTimestamp DESC
        """,
    )
    fun searchEnrichedConversations(
        identityHash: String,
        query: String,
    ): Flow<List<EnrichedConversation>>

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

    /**
     * Bulk insert conversations (for import).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)
}
