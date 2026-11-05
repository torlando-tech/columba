package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import network.columba.app.data.db.entity.ThreadEntity

/**
 * Thread rows are created implicitly on receive (first message seen with an
 * unknown `FIELD_THREAD` value — threads-spec §4.3) and by the send path when
 * the user starts a thread (P2). Wire events never remove threads.
 *
 * P2 adds the thread-sheet management surface: rename, archive/un-archive,
 * and local delete. All three are local-only — they never emit wire traffic
 * and never touch received message history beyond the explicit delete of the
 * thread's own messages (the conversation's Main rows are untouched).
 */
@Dao
interface ThreadDao {
    /** Insert a newly-sighted thread; ignored when the (conversation, threadId) row already exists. Returns rowId or -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertThreadIgnore(thread: ThreadEntity): Long

    /** All threads owned by one identity (backup export). */
    @Query("SELECT * FROM threads WHERE identityHash = :identityHash")
    suspend fun getAllThreadsList(identityHash: String): List<ThreadEntity>

    @Query(
        "SELECT * FROM threads WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId LIMIT 1",
    )
    suspend fun getThread(
        identityHash: String,
        conversationHash: String,
        threadId: ByteArray,
    ): ThreadEntity?

    /** Threads of one conversation ordered for the chip bar / thread sheet (most recent activity first). */
    @Query(
        """
        SELECT * FROM threads
        WHERE identityHash = :identityHash AND conversationHash = :conversationHash
        AND (:includeArchived OR archivedAt IS NULL)
        ORDER BY lastActivityAt DESC
        """,
    )
    fun threadsForConversation(
        identityHash: String,
        conversationHash: String,
        includeArchived: Boolean = false,
    ): Flow<List<ThreadEntity>>

    /** Bump activity watermark on any new message in the thread (revives dormant threads). */
    @Query(
        "UPDATE threads SET lastActivityAt = :activityAt WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId",
    )
    suspend fun touchThread(
        threadId: ByteArray,
        identityHash: String,
        conversationHash: String,
        activityAt: Long,
    )

    /** Attach the Main-message root when a case-2 first sighting resolves it (spec §4.3 thread hook). */
    @Query(
        "UPDATE threads SET rootMessageId = :rootMessageId WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId",
    )
    suspend fun setRootMessage(
        threadId: ByteArray,
        identityHash: String,
        conversationHash: String,
        rootMessageId: String,
    )

    /**
     * Dormancy sweep (spec §5): non-archived threads with no activity since
     * [cutoffAt] (= now - dormancy window). Dormant is derived state — the
     * sweep only reports; nothing is written or deleted.
     */
    @Query(
        """
        SELECT * FROM threads
        WHERE identityHash = :identityHash
        AND archivedAt IS NULL
        AND lastActivityAt < :cutoffAt
        ORDER BY lastActivityAt ASC
        """,
    )
    suspend fun dormantThreads(
        identityHash: String,
        cutoffAt: Long,
    ): List<ThreadEntity>

    /**
     * Rename a thread (local label only — titles never touch the wire, spec §2
     * non-goals). `title = NULL` resets the thread to its automatic excerpt
     * (threads-spec D8), so the parameter is nullable.
     */
    @Query(
        "UPDATE threads SET title = :title WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId",
    )
    suspend fun renameThread(
        threadId: ByteArray,
        identityHash: String,
        conversationHash: String,
        title: String?,
    )

    /** Archive ([archivedAt] = now) or un-archive ([archivedAt] = null). Local presentation only (spec §5). */
    @Query(
        "UPDATE threads SET archivedAt = :archivedAt WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId",
    )
    suspend fun setArchived(
        threadId: ByteArray,
        identityHash: String,
        conversationHash: String,
        archivedAt: Long?,
    )

    /**
     * Archive / un-archive several threads in one statement (selection-mode
     * batch). One UPDATE is atomic, so a batch can never leave a partially
     * archived set behind (threads-spec §5.3). Empty ids is a no-op.
     */
    @Query(
        """
        UPDATE threads SET archivedAt = :archivedAt
        WHERE identityHash = :identityHash AND conversationHash = :conversationHash
        AND threadId IN (:threadIds)
        """,
    )
    suspend fun setArchivedBatch(
        threadIds: List<ByteArray>,
        identityHash: String,
        conversationHash: String,
        archivedAt: Long?,
    )

    /**
     * Delete one thread row. The caller deletes the thread's messages and
     * draft in the same transaction (repository `deleteThread`); Main rows
     * and the wire history are never touched.
     */
    @Query(
        "DELETE FROM threads WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId",
    )
    suspend fun deleteThread(
        threadId: ByteArray,
        identityHash: String,
        conversationHash: String,
    )

    /**
     * Delete the thread row ONLY when it is message-less and carries no name —
     * one atomic statement, so a first send completing concurrently can never
     * leave its reply orphaned outside Main with the thread row gone.
     *
     * `title IS NULL` is the whole guard: any title is treated as the user's
     * label and is never deleted on the draft-clear path. Drafts never create
     * rows, so the only message-less rows are renamed ones — the user's name
     * is what this keeps; deleting on a title match would remove a thread the
     * user renamed to the same text as a draft (threads-spec §6.2 "title
     * optional at creation").
     */
    @Query(
        """
        DELETE FROM threads
        WHERE identityHash = :identityHash AND conversationHash = :conversationHash AND threadId = :threadId
        AND title IS NULL
        AND (
            SELECT COUNT(*) FROM messages
            WHERE messages.threadId = :threadId
              AND messages.conversationHash = :conversationHash
              AND messages.identityHash = :identityHash
        ) = 0
        """,
    )
    suspend fun deleteThreadIfEmpty(
        threadId: ByteArray,
        identityHash: String,
        conversationHash: String,
    ): Int
}
