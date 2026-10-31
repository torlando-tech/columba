package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One composer draft per (conversation, thread) pair (threads-spec §5, P2).
 *
 * `threadId` is part of the primary key: Main and every thread keep their own
 * draft so switching chips never clobbers another conversation's unsent text.
 * The Main sentinel is the EMPTY byte array — SQLite PK columns must be
 * NOT NULL, and NULLs are distinct under SQLite uniqueness, so a nullable
 * column could not enforce "one Main draft". Wire thread IDs are never empty
 * (16 random bytes; senders drop empty arrays before the wire), so the
 * sentinel cannot collide with a real thread.
 *
 * The PK cutover from `(conversationHash, identityHash)` ships in migration
 * v5→v6, which rewrites pre-thread drafts with `threadId = X''` (Main).
 */
@Entity(
    tableName = "drafts",
    primaryKeys = ["conversationHash", "identityHash", "threadId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["peerHash", "identityHash"],
            childColumns = ["conversationHash", "identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("identityHash"),
    ],
)
data class DraftEntity(
    val conversationHash: String,
    val identityHash: String,
    val content: String,
    val updatedTimestamp: Long,
    // LXMF FIELD_THREAD (0x08) bytes this draft belongs to; [MAIN_THREAD_ID] = Main.
    val threadId: ByteArray = MAIN_THREAD_ID,
) {
    companion object {
        /** Main-conversation sentinel: drafts never carry a real empty thread ID on the wire. */
        @JvmStatic
        val MAIN_THREAD_ID: ByteArray = ByteArray(0)

        /** True when this draft row belongs to the Main conversation. */
        fun isMainThreadId(threadId: ByteArray?): Boolean = threadId == null || threadId.isEmpty()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is DraftEntity &&
                    conversationHash == other.conversationHash &&
                    identityHash == other.identityHash &&
                    content == other.content &&
                    updatedTimestamp == other.updatedTimestamp &&
                    threadId.contentEquals(other.threadId)
            )

    override fun hashCode(): Int {
        var result = conversationHash.hashCode()
        result = 31 * result + identityHash.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + updatedTimestamp.hashCode()
        result = 31 * result + threadId.contentHashCode()
        return result
    }
}
