package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceDraft(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE conversationHash = :peerHash AND identityHash = :identityHash LIMIT 1")
    suspend fun getDraft(peerHash: String, identityHash: String): DraftEntity?

    @Query("DELETE FROM drafts WHERE conversationHash = :peerHash AND identityHash = :identityHash")
    suspend fun deleteDraft(peerHash: String, identityHash: String)

    @Query("SELECT * FROM drafts WHERE identityHash = :identityHash")
    fun observeDraftsForIdentity(identityHash: String): Flow<List<DraftEntity>>

    @Query("DELETE FROM drafts WHERE identityHash = :identityHash")
    suspend fun deleteAllDraftsForIdentity(identityHash: String)
}
