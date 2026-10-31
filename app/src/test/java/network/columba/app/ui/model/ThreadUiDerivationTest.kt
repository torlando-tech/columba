package network.columba.app.ui.model

import network.columba.app.data.db.dao.ThreadLastMessage
import network.columba.app.data.db.dao.ThreadMessageCount
import network.columba.app.data.db.dao.ThreadUnreadCount
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.ThreadEntity
import network.columba.app.data.util.ThreadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the Threads P2 derivations (threads-spec §6):
 * sheet partition precedence, chip filtering + selection, hook map, and the
 * thread-view header rules.
 */
class ThreadUiDerivationTest {
    private val now = 1_730_000_000_000L
    private val window = ThreadPolicy.DORMANCY_WINDOW_MS

    private fun thread(
        id: Int,
        lastActivityAt: Long = now,
        archivedAt: Long? = null,
        rootMessageId: String? = null,
        title: String? = null,
    ) = ThreadEntity(
        threadId = byteArrayOf(id.toByte()),
        conversationHash = "peer",
        identityHash = "me",
        rootMessageId = rootMessageId,
        title = title,
        createdAt = lastActivityAt,
        lastActivityAt = lastActivityAt,
        archivedAt = archivedAt,
    )

    private fun message(
        id: String,
        content: String = "content",
        isFromMe: Boolean = false,
        replyToMessageId: String? = null,
        receivedAt: Long? = null,
    ) = MessageEntity(
        id = id,
        conversationHash = "peer",
        identityHash = "me",
        content = content,
        timestamp = now - 1000,
        isFromMe = isFromMe,
        replyToMessageId = replyToMessageId,
        receivedAt = receivedAt,
    )

    @Test
    fun `sheet partitions by archived then dormancy keeping dao order`() {
        val fresh = thread(1, lastActivityAt = now - 1000, title = "fresh")
        val stale = thread(2, lastActivityAt = now - window - 1, title = "stale")
        val archivedStale = thread(3, lastActivityAt = now - window - 1, archivedAt = now, title = "arch")
        // DAO contract: lastActivityAt DESC — deliberately interleaved states.
        val state =
            ThreadUiDerivation.buildSheetState(
                listOf(fresh, archivedStale, stale),
                unreadByHex = emptyMap(),
                lastByHex = emptyMap(),
                now = now,
            )
        assertEquals(listOf("fresh"), state.active.map { it.title })
        assertEquals(listOf("stale"), state.dormant.map { it.title })
        // Archived wins over dormancy even though it is also stale.
        assertEquals(listOf("arch"), state.archived.map { it.title })
        assertEquals(3, state.totalThreadCount)
        assertTrue(ThreadSheetUiState().isEmpty)
    }

    @Test
    fun `row preview prefers last message over activity timestamp`() {
        val t = thread(1, lastActivityAt = now - 5000)
        val hex = "01"
        val row =
            ThreadUiDerivation.toRow(
                t,
                unreadByHex = mapOf(hex to 2),
                lastByHex = mapOf(hex to ThreadLastMessage(byteArrayOf(1), "last text", now - 10)),
            )
        assertEquals(hex, row.threadIdHex)
        assertEquals("last text", row.preview)
        assertEquals(now - 10, row.timestamp)
        assertEquals(2, row.unreadCount)

        val empty = ThreadUiDerivation.toRow(t, emptyMap(), emptyMap())
        assertEquals("", empty.preview)
        assertEquals(now - 5000, empty.timestamp)
        assertEquals(0, empty.unreadCount)
    }

    @Test
    fun `chips exclude archived and dormant but always include the selection`() {
        val fresh = thread(1, lastActivityAt = now - 1000, title = "a")
        val stale = thread(2, lastActivityAt = now - window - 1)
        val archived = thread(3, archivedAt = now)
        val selected = thread(4, lastActivityAt = now - 2000)
        val chips =
            ThreadUiDerivation.buildChips(
                listOf(fresh, stale, archived, selected),
                unreadByHex = mapOf("01" to 3),
                selectedThreadIdHex = "04",
                now = now,
            )
        assertEquals(listOf("01", "04"), chips.map { it.threadIdHex })
        assertEquals(3, chips.first().unreadCount)

        // A brand-new thread (no DB row yet) still gets a chip while selected.
        val ghost = ThreadUiDerivation.buildChips(emptyList(), emptyMap(), "ff", now)
        assertEquals(listOf("ff"), ghost.map { it.threadIdHex })
        assertNull(ghost.single().title)
    }

    @Test
    fun `hook map keys by root id and survives archiving`() {
        val withRoot = thread(1, rootMessageId = "root-1")
        val noRoot = thread(2)
        val archivedWithRoot = thread(3, rootMessageId = "root-3", archivedAt = now)
        val hooks =
            ThreadUiDerivation.buildHookMap(
                listOf(withRoot, noRoot, archivedWithRoot),
                countsByHex = mapOf("01" to 4, "03" to 1),
                unreadByHex = mapOf("01" to 2),
            )
        assertEquals(
            ThreadHookUi(threadIdHex = "01", replyCount = 4, unreadCount = 2),
            hooks["root-1"],
        )
        // Archived threads keep their hook: the pill belongs to the root message.
        assertEquals(
            ThreadHookUi(threadIdHex = "03", replyCount = 1, unreadCount = 0),
            hooks["root-3"],
        )
        assertEquals(2, hooks.size)
    }

    @Test
    fun `header resolves root degrades to offscreen and case-1 has none`() {
        val root = message("root-1", content = "root text", isFromMe = true, receivedAt = now - 42)

        // Case 2 with a resolvable root -> pinned root card.
        val resolved =
            ThreadUiDerivation.deriveHeader(
                thread(1, rootMessageId = "root-1"),
                firstMessage = message("m1", replyToMessageId = "root-1"),
                rootMessage = root,
            )
        assertEquals(ThreadHeaderUi.Root(content = "root text", isFromMe = true, timestamp = now - 42), resolved)

        // Case 2 whose root row was deleted locally -> placeholder.
        val deleted =
            ThreadUiDerivation.deriveHeader(
                thread(1, rootMessageId = "root-1"),
                firstMessage = message("m1", replyToMessageId = "root-1"),
                rootMessage = null,
            )
        assertEquals(ThreadHeaderUi.OffScreen, deleted)

        // Case 1 (from scratch): no root, first message quotes nothing -> no header.
        assertNull(
            ThreadUiDerivation.deriveHeader(
                thread(1),
                firstMessage = message("m1"),
                rootMessage = null,
            ),
        )

        // Thread row not written yet (nothing persisted) -> no header.
        assertNull(ThreadUiDerivation.deriveHeader(null, message("m1"), root))
    }

    @Test
    fun `aggregate maps key by hex and skip null-thread rows`() {
        val unread = ThreadUiDerivation.unreadByThread(listOf(ThreadUnreadCount(byteArrayOf(0x0a), 5), ThreadUnreadCount(null, 9)))
        assertEquals(mapOf("0a" to 5), unread)
        val counts = ThreadUiDerivation.countsByThread(listOf(ThreadMessageCount(byteArrayOf(0x0a), 7), ThreadMessageCount(null, 3)))
        assertEquals(mapOf("0a" to 7), counts)
    }
}
