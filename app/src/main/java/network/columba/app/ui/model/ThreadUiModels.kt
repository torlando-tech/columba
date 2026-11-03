package network.columba.app.ui.model

import network.columba.app.data.db.dao.ThreadFirstMessage
import network.columba.app.data.db.dao.ThreadLastMessage
import network.columba.app.data.db.dao.ThreadMessageCount
import network.columba.app.data.db.dao.ThreadUnreadCount
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.ThreadEntity
import network.columba.app.data.util.ThreadPolicy
import network.columba.app.rns.api.util.toHex

/**
 * Threads P2 presentation models (threads-spec §6). One projection feeds the
 * header pill, the thread sheet (partial and full-screen presentations) and
 * the expanding chat cards; composables never re-derive thread state.
 */

/** One thread row in the thread sheet / expanding chat card. */
data class ThreadRowUi(
    val threadIdHex: String,
    /** Local title; null means "automatic" (threads-spec D8). */
    val title: String?,
    /** Automatic fallback: excerpt of the thread's first message, null when it has none. */
    val autoTitle: String?,
    /** Last-message preview (empty when the thread has no messages yet). */
    val preview: String,
    val timestamp: Long,
    val unreadCount: Int,
) {
    /**
     * Text to display: the user's title, else the automatic excerpt. Null
     * when neither exists — the composable then falls back to the resource
     * default (`New thread`), so the projection stays resource-free.
     */
    val displayTitle: String? get() = title ?: autoTitle
}

/**
 * Per-thread lookups for one conversation: unread counts, last message and
 * first message keyed by thread id hex. Grouped so row derivations do not
 * thread three parallel maps through every call.
 */
data class ThreadRowSource(
    val unreadByHex: Map<String, Int> = emptyMap(),
    val lastByHex: Map<String, ThreadLastMessage> = emptyMap(),
    val firstByHex: Map<String, ThreadFirstMessage> = emptyMap(),
)

/** The conversation's Main target as a sheet row (pinned, not a `threads` row). */
data class ThreadMainRowUi(
    val preview: String = "",
    val timestamp: Long = 0L,
    val unreadCount: Int = 0,
)

/** Thread sheet contents, partitioned per spec §6.3 (Active / Dormant / Archived). */
data class ThreadSheetUiState(
    val active: List<ThreadRowUi> = emptyList(),
    val dormant: List<ThreadRowUi> = emptyList(),
    val archived: List<ThreadRowUi> = emptyList(),
    val main: ThreadMainRowUi = ThreadMainRowUi(),
    /** Open target (null = Main) — the sheet marks that row. */
    val currentThreadIdHex: String? = null,
    /**
     * False when the host has no open target (the chat list's "View all"
     * sheet): nothing is marked current and undo never re-opens a thread.
     */
    val openTargetKnown: Boolean = true,
) {
    val totalThreadCount: Int get() = active.size + dormant.size + archived.size
    val isEmpty: Boolean get() = totalThreadCount == 0

    /** All thread rows, in sheet order (Main excluded). */
    val allRows: List<ThreadRowUi> get() = active + dormant + archived
}

/** Header pill (spec §5.1): the open target's title + unread, tap opens the sheet. */
data class ThreadPillUi(
    val isMain: Boolean = true,
    /** Null for Main or a freshly started thread; the pill shows the resource default. */
    val title: String? = null,
    val unreadCount: Int = 0,
)

/**
 * One-shot composer prefill for "Thread from here", bound to the thread it was
 * created for: applying it to whatever target happens to be selected would put
 * the quote in another thread's draft.
 */
data class ThreadComposerPrefill(
    val threadIdHex: String,
    val text: String,
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
        val signatureVerified: Boolean? = null,
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

    /** Main's unread count (the null-threadId row of the grouped query). */
    fun mainUnreadCount(unread: List<ThreadUnreadCount>): Int =
        unread.firstOrNull { it.threadId == null }?.unreadCount ?: 0

    fun lastMessageByThread(last: List<ThreadLastMessage>): Map<String, ThreadLastMessage> =
        last.associateBy { it.threadId.toHex() }

    /**
     * Oldest message per thread keyed by thread id. Ties on the min timestamp
     * can yield several rows for one thread; the earliest content wins, so
     * the derivation is deterministic.
     */
    fun firstMessageByThread(first: List<ThreadFirstMessage>): Map<String, ThreadFirstMessage> {
        val byHex = LinkedHashMap<String, ThreadFirstMessage>()
        first.sortedBy { it.timestamp }.forEach { row ->
            val hex = row.threadId.toHex()
            if (hex !in byHex) byHex[hex] = row
        }
        return byHex
    }

    fun countsByThread(counts: List<ThreadMessageCount>): Map<String, Int> =
        counts.mapNotNull { row -> row.threadId?.let { it.toHex() to row.messageCount } }.toMap()

    fun toRow(
        thread: ThreadEntity,
        unreadByHex: Map<String, Int>,
        lastByHex: Map<String, ThreadLastMessage>,
        firstByHex: Map<String, ThreadFirstMessage>,
    ): ThreadRowUi {
        val hex = thread.threadId.toHex()
        val last = lastByHex[hex]
        val first = firstByHex[hex]
        return ThreadRowUi(
            threadIdHex = hex,
            title = thread.title,
            autoTitle = first?.content?.let { ThreadPolicy.threadTitleExcerpt(it) }?.ifBlank { null },
            preview = last?.content.orEmpty(),
            timestamp = last?.timestamp ?: thread.lastActivityAt,
            unreadCount = unreadByHex[hex] ?: 0,
        )
    }

    /** Sheet partition (spec §6.3): archived wins over dormancy; each section keeps DAO order. */
    fun buildSheetState(
        threads: List<ThreadEntity>,
        source: ThreadRowSource,
        now: Long,
        currentThreadIdHex: String? = null,
        main: ThreadMainRowUi = ThreadMainRowUi(),
        openTargetKnown: Boolean = true,
    ): ThreadSheetUiState {
        val active = mutableListOf<ThreadRowUi>()
        val dormant = mutableListOf<ThreadRowUi>()
        val archived = mutableListOf<ThreadRowUi>()
        threads.forEach { thread ->
            val row = toRow(thread, source.unreadByHex, source.lastByHex, source.firstByHex)
            when {
                thread.archivedAt != null -> archived += row
                isDormant(thread.lastActivityAt, now) -> dormant += row
                else -> active += row
            }
        }
        return ThreadSheetUiState(
            active = active,
            dormant = dormant,
            archived = archived,
            main = main,
            currentThreadIdHex = currentThreadIdHex,
            openTargetKnown = openTargetKnown,
        )
    }

    /**
     * Header pill contents: the open target's title + unread. A freshly
     * started thread that has no row yet keeps a null title so the pill shows
     * the resource default until its first message titles it.
     */
    fun buildPill(
        sheet: ThreadSheetUiState,
        selectedThreadIdHex: String?,
    ): ThreadPillUi {
        if (selectedThreadIdHex == null) {
            return ThreadPillUi(isMain = true, unreadCount = sheet.main.unreadCount)
        }
        val row = sheet.allRows.firstOrNull { it.threadIdHex == selectedThreadIdHex }
        return ThreadPillUi(
            isMain = false,
            title = row?.displayTitle,
            unreadCount = row?.unreadCount ?: 0,
        )
    }

    /**
     * Replies-hook map (spec §6.6): rootMessageId -> hook. Archived threads
     * keep their hook (the pill is a property of the root message; the thread
     * stays reachable from Main). A thread with no messages yet has no
     * replies, so it gets no hook: "0 replies" on Main would advertise a
     * thread the user started (and then canceled) without ever sending.
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
                val messages = countsByHex[hex] ?: 0
                if (messages == 0) return@mapNotNull null
                rootId to ThreadHookUi(
                    threadIdHex = hex,
                    replyCount = messages,
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

    private fun MessageEntity.toRootHeader() =
        ThreadHeaderUi.Root(
            content = content,
            isFromMe = isFromMe,
            timestamp = receivedAt ?: timestamp,
            signatureVerified = signatureVerified,
        )
}
