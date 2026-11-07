package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A thread inside one contact's conversation (threads-spec §5).
 *
 * `threadId` is the raw LXMF `FIELD_THREAD` (0x08) wire bytes — normally 16
 * CSPRNG bytes, stored opaquely (any length is a valid grouping key). The
 * thread belongs to the *Main* conversation row identified by
 * (`conversationHash`, `identityHash`) — Columba's canonical conversation
 * key (the spec's abstract "conversationId"), matching `MessageEntity`'s FK
 * shape.
 *
 * Derived state needs no column beyond [archivedAt]:
 *  - ACTIVE:   `lastActivityAt` within the dormancy window (14 days,
 *              [network.columba.app.data.util.ThreadPolicy.DORMANCY_WINDOW_MS])
 *  - DORMANT:  older than the window; purely local, revives on any new message
 *  - ARCHIVED: [archivedAt] != null; local action only — never set or cleared
 *              by wire events, and archiving emits no wire traffic.
 *
 * [rootMessageId] is set when the thread was created "from here" (wire case 2)
 * and the root was resolvable at first sighting; threads whose root was never
 * received keep it null (P2 renders the "Thread started off-screen" placeholder).
 * It is a soft reference, not an FK: `messages`' PK is composite
 * (`id`, `identityHash`) and an `ON DELETE SET NULL` FK would require the
 * shared `identityHash` child column to be nullable. Root deletion is already
 * covered by the conversation cascade, and P2 degrades gracefully on a
 * missing root, so no hard constraint is needed.
 */
@Entity(
    tableName = "threads",
    primaryKeys = ["conversationHash", "identityHash", "threadId"], // Spec §4.3 convKey: same T from different peers is distinct threads
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["peerHash", "identityHash"],
            childColumns = ["conversationHash", "identityHash"],
            onDelete = ForeignKey.CASCADE, // Deleting the conversation deletes its threads (local only)
        ),
    ],
    indices = [
        // The composite PK above *is* the spec §5 unique index
        // (conversationId, threadId), identity-scoped to match the composite
        // conversation key; SQLite's implicit PK index enforces it.
        Index("identityHash", "conversationHash", "lastActivityAt"), // Chip-bar / sheet ordering + dormancy sweep
        Index("rootMessageId"), // Replies-hook lookups from Main bubbles
    ],
)
data class ThreadEntity(
    val threadId: ByteArray, // 16B wire id (opaque; any length accepted)
    val conversationHash: String, // Peer's destination hash (Main conversation)
    val identityHash: String, // Which local identity owns this thread
    val rootMessageId: String? = null, // MessageEntity.id when created "from here"
    val title: String? = null, // Local label (default: truncated first-message excerpt); user-editable in P2
    val createdAt: Long,
    val lastActivityAt: Long,
    val archivedAt: Long? = null, // Manual archive only; never deleted by wire events
)
