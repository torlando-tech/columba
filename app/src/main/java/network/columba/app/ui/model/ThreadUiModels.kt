package network.columba.app.ui.model

import network.columba.app.data.db.dao.ThreadLastMessage
import network.columba.app.data.db.dao.ThreadMessageCount
import network.columba.app.data.db.dao.ThreadUnreadCount
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.ThreadEntity
import network.columba.app.data.util.ThreadPolicy
import network.columba.app.rns.api.util.toHex

/**
 * UI models + pure derivation for the threads P2 surfaces (threads-spec §6):
 * expanding chat card, chip bar, thread sheet, replies hook, thread view.
 *
 * Everything here is a pure function of DB rows so it can be unit-tested
 * without Compose or Room — the ViewModels only wire flows into these
 * builders. Thread IDs cross flow/Compose boundaries as lowercase hex strings
 * (stable `equals`/`hashCode` for Compose keys; the raw bytes stay in the DB).
 */

/** One thread row in the thread sheet / expanding chat card. */
data class ThreadRowUi(
    val threadIdHex: String,
    /** Local label; null until the first message titles it (UI shows the untitled placeholder). */
    val title: String?,
    /** Last-message preview (empty when the thread has no messages yet). */
    val preview: String,
    val timestamp: Long,
    val unreadCount: Int,
)

/** Thread sheet contents, partitioned per spec §6.3 (Active / Dormant / Archived). */
data class ThreadSheetUiState(
    val active: List<ThreadRowUi> = emptyList(),
    val dormant: List<ThreadRowUi> = emptyList(),
    val archived: List<ThreadRowUi> = emptyList(),
) {
    val totalThreadCount: Int get() = active.size + dormant.size + archived.size
    val isEmpty: Boolean get() = totalThreadCount == 0
}

/** One chip in the messaging chip bar (Main is rendered separately, always first). */
data class ThreadChipUi(
    val threadIdHex: String,
    val title: String?,
    val unreadCount: Int,
)

/** "N replies" pill under a Main root bubble (spec §6.6). */
data class ThreadHookUi(
    val threadIdHex: String,
    val replyCount: Int,
    val unreadCount: Int,
)

/** Pinned header of the thread view (spec §6.5). */
sealed interface ThreadHeaderUi {
    /** Case-2 thread whose Main root is available locally. */
    data class Root(
        val content: String,
        val isFromMe: Boolean,
        val timestamp: Long,
    ) : ThreadHeaderUi

    /** Case-2 thread whose root was never received (or was deleted locally). */
    data object OffScreen : ThreadHeaderUi
}

/** Derived thread-view presentation for the currently selected thread. */
data class ThreadViewUi(
    val header: ThreadHeaderUi?,
    val replyCount: Int,
)

/**
 * Per-conversation threads bundle for the chat list: expanding card
 * (spec §6.1, renders `active + dormant` head-2) and the thread sheet
 * (§6.3, full partition) share one derivation.
 */
data class ChatCardThreadsUi(
    val sheet: ThreadSheetUiState = ThreadSheetUiState(),
    /** Main row preview: last Main message (the conversation row's lastMessage may be a thread message). */
    val mainPreview: String = "",
    val mainTimestamp: Long = 0L,
) {
    /** Non-archived threads, most recent activity first (active all outrank dormant by the time-based cutoff). */
    val nonArchived: List<ThreadRowUi> get() = sheet.active + sheet.dormant
}

/**
 * Pure derivations over thread rows. All ordering follows the DAO contract:
 * `threadsForConversation` already returns `lastActivityAt DESC`.
 */
object ThreadUiDerivation {
    /** Dormancy cutoff for "is this thread dormant right now". */
    fun isDormant(
        lastActivityAt: Long,
        now: Long,
    ): Boolean = ThreadPolicy.isDormant(lastActivityAt, now)

    fun unreadByThread(unread: List<ThreadUnreadCount>): Map<String, Int> =
        unread.mapNotNull { row -> row.threadId?.let { it.toHex() to row.unreadCount } }.toMap()

    fun lastMessageByThread(last: List<ThreadLastMessage>): Map<String, ThreadLastMessage> =
        last.associateBy { it.threadId.toHex() }

    fun countsByThread(counts: List<ThreadMessageCount>): Map<String, Int> =
        counts.mapNotNull { row -> row.threadId?.let { it.toHex() to row.messageCount } }.toMap()

    fun toRow(
        thread: ThreadEntity,
        unreadByHex: Map<String, Int>,
        lastByHex: Map<String, ThreadLastMessage>,
    ): ThreadRowUi {
        val hex = thread.threadId.toHex()
        val last = lastByHex[hex]
        return ThreadRowUi(
            threadIdHex = hex,
            title = thread.title,
            preview = last?.content.orEmpty(),
            timestamp = last?.timestamp ?: thread.lastActivityAt,
            unreadCount = unreadByHex[hex] ?: 0,
        )
    }

    /** Sheet partition (spec §6.3): archived wins over dormancy; each section keeps DAO order. */
    fun buildSheetState(
        threads: List<ThreadEntity>,
        unreadByHex: Map<String, Int>,
        lastByHex: Map<String, ThreadLastMessage>,
        now: Long,
    ): ThreadSheetUiState {
        val active = mutableListOf<ThreadRowUi>()
        val dormant = mutableListOf<ThreadRowUi>()
        val archived = mutableListOf<ThreadRowUi>()
        threads.forEach { thread ->
            val row = toRow(thread, unreadByHex, lastByHex)
            when {
                thread.archivedAt != null -> archived += row
                isDormant(thread.lastActivityAt, now) -> dormant += row
                else -> active += row
            }
        }
        return ThreadSheetUiState(active = active, dormant = dormant, archived = archived)
    }

    /**
     * Chip list (spec §6.2): ACTIVE non-archived threads, `lastActivityAt`
     * desc. Main is not included — it is the pinned first chip by
     * construction. A freshly started thread with no row yet (nothing sent)
     * is appended so its chip exists while selected.
     */
    fun buildChips(
        threads: List<ThreadEntity>,
        unreadByHex: Map<String, Int>,
        selectedThreadIdHex: String?,
        now: Long,
    ): List<ThreadChipUi> {
        val chips =
            threads
                .filter { it.archivedAt == null && !isDormant(it.lastActivityAt, now) }
                .map { thread ->
                    val hex = thread.threadId.toHex()
                    ThreadChipUi(
                        threadIdHex = hex,
                        title = thread.title,
                        unreadCount = unreadByHex[hex] ?: 0,
                    )
                }.toMutableList()
        if (selectedThreadIdHex != null && chips.none { it.threadIdHex == selectedThreadIdHex }) {
            chips += ThreadChipUi(threadIdHex = selectedThreadIdHex, title = null, unreadCount = 0)
        }
        return chips
    }

    /**
     * Replies-hook map (spec §6.6): rootMessageId -> hook. Archived threads
     * keep their hook (the pill is a property of the root message; the thread
     * stays reachable from Main).
     */
    fun buildHookMap(
        threads: List<ThreadEntity>,
        countsByHex: Map<String, Int>,
        unreadByHex: Map<String, Int>,
    ): Map<String, ThreadHookUi> =
        threads
            .mapNotNull { thread ->
                val rootId = thread.rootMessageId ?: return@mapNotNull null
                val hex = thread.threadId.toHex()
                rootId to ThreadHookUi(
                    threadIdHex = hex,
                    replyCount = countsByHex[hex] ?: 0,
                    unreadCount = unreadByHex[hex] ?: 0,
                )
            }.toMap()

    /**
     * Thread-view header (spec §6.5):
     *  - resolved root -> pinned root card (degrades to the off-screen
     *    placeholder when the root row was deleted locally),
     *  - unresolved case-2 root (first message carries a `0x30` target that
     *    never resolved) -> "Thread started off-screen" placeholder,
     *  - thread from scratch (case 1) -> no header; messages render in time
     *    order.
     */
    fun deriveHeader(
        thread: ThreadEntity?,
        firstMessage: MessageEntity?,
        rootMessage: MessageEntity?,
    ): ThreadHeaderUi? {
        if (thread == null) return null
        if (thread.rootMessageId != null) {
            return rootMessage?.toRootHeader() ?: ThreadHeaderUi.OffScreen
        }
        return if (firstMessage?.replyToMessageId != null) ThreadHeaderUi.OffScreen else null
    }

    private fun MessageEntity.toRootHeader() = ThreadHeaderUi.Root(content = content, isFromMe = isFromMe, timestamp = receivedAt ?: timestamp)
}
