package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import network.columba.app.data.db.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-(conversation, thread) composer drafts (threads-spec §5, P2).
 * `threadId` is matched by exact bytes; the empty array is the Main sentinel
 * ([DraftEntity.MAIN_THREAD_ID]) — see [DraftEntity] for why Main is `X''`
 * and not NULL.
 */
@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceDraft(draft: DraftEntity)

    @Query(
        "SELECT * FROM drafts WHERE conversationHash = :peerHash AND identityHash = :identityHash AND threadId = :threadId LIMIT 1",
    )
    suspend fun getDraft(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    ): DraftEntity?

    @Query(
        "DELETE FROM drafts WHERE conversationHash = :peerHash AND identityHash = :identityHash AND threadId = :threadId",
    )
    suspend fun deleteDraft(
        peerHash: String,
        identityHash: String,
        threadId: ByteArray,
    )

    @Query("SELECT * FROM drafts WHERE identityHash = :identityHash")
    fun observeDraftsForIdentity(identityHash: String): Flow<List<DraftEntity>>

    @Query("DELETE FROM drafts WHERE identityHash = :identityHash")
    suspend fun deleteAllDraftsForIdentity(identityHash: String)
}
