package network.columba.app.ui.model

import network.columba.app.data.db.dao.ThreadFirstMessage
import network.columba.app.data.db.dao.ThreadLastMessage
import network.columba.app.data.db.dao.ThreadMessageCount
import network.columba.app.data.db.dao.ThreadUnreadCount
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.ThreadEntity
import network.columba.app.data.util.ThreadPolicy
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.ui.model.ThreadRowSource
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
                threads = listOf(fresh, archivedStale, stale),
                source =
                    ThreadRowSource(
                        unreadByHex = emptyMap(),
                        lastByHex = emptyMap(),
                        firstByHex = emptyMap(),
                    ),
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
                firstByHex = emptyMap(),
            )
        assertEquals(hex, row.threadIdHex)
        assertEquals("last text", row.preview)
        assertEquals(now - 10, row.timestamp)
        assertEquals(2, row.unreadCount)

        val empty = ThreadUiDerivation.toRow(t, emptyMap(), emptyMap(), emptyMap())
        assertEquals("", empty.preview)
        assertEquals(now - 5000, empty.timestamp)
        assertEquals(0, empty.unreadCount)
    }

    @Test
    fun `pill names the open target and carries only its unread`() {
        val state =
            ThreadUiDerivation.buildSheetState(
                threads =
                    listOf(
                        thread(1, lastActivityAt = now - 1000, title = "Trip planning"),
                        thread(2, lastActivityAt = now - 2000, title = "Build notes"),
                    ),
                source =
                    ThreadRowSource(
                        unreadByHex = mapOf("01" to 3, "02" to 1),
                        lastByHex = emptyMap(),
                        firstByHex = emptyMap(),
                    ),
                now = now,
                currentThreadIdHex = "01",
                main = ThreadMainRowUi(unreadCount = 7),
            )

        assertEquals(
            ThreadPillUi(isMain = false, title = "Trip planning", unreadCount = 3),
            ThreadUiDerivation.buildPill(state, "01"),
        )
        assertEquals(ThreadPillUi(isMain = true, unreadCount = 7), ThreadUiDerivation.buildPill(state, null))

        // A brand-new thread with no row yet: the pill keeps the resource default.
        val ghost = ThreadUiDerivation.buildPill(state, "ff")
        assertEquals(false, ghost.isMain)
        assertNull(ghost.title)
        assertEquals(0, ghost.unreadCount)
    }

    @Test
    fun `title precedence is local title then automatic excerpt`() {
        val renamed = thread(1, title = "Trip planning")
        val automatic = thread(2, title = null)
        val firstByHex =
            mapOf(
                "01" to ThreadFirstMessage(byteArrayOf(1), "first of renamed", now - 5000),
                "02" to ThreadFirstMessage(byteArrayOf(2), "  Build   notes\nfrom the mesh  ", now - 5000),
            )

        val renamedRow = ThreadUiDerivation.toRow(renamed, emptyMap(), emptyMap(), firstByHex)
        assertEquals("Trip planning", renamedRow.displayTitle)

        val automaticRow = ThreadUiDerivation.toRow(automatic, emptyMap(), emptyMap(), firstByHex)
        assertNull(automaticRow.title)
        assertEquals("Build notes from the mesh", automaticRow.displayTitle)

        // No messages at all -> no display title, the composable shows the default.
        val emptyRow = ThreadUiDerivation.toRow(automatic, emptyMap(), emptyMap(), emptyMap())
        assertNull(emptyRow.displayTitle)
    }

    @Test
    fun `first message map keeps the earliest row per thread`() {
        val ties =
            listOf(
                ThreadFirstMessage(byteArrayOf(1), "later", now - 100),
                ThreadFirstMessage(byteArrayOf(1), "earlier", now - 900),
                ThreadFirstMessage(byteArrayOf(2), "only", now - 500),
            )
        val byHex = ThreadUiDerivation.firstMessageByThread(ties)
        assertEquals("earlier", byHex["01"]?.content)
        assertEquals("only", byHex["02"]?.content)
    }

    @Test
    fun `footer counts come from the partition and main unread is its own bucket`() {
        val state =
            ThreadUiDerivation.buildSheetState(
                threads =
                    listOf(
                        thread(1, lastActivityAt = now - 1000),
                        thread(2, lastActivityAt = now - window - 1),
                        thread(3, lastActivityAt = now - window - 2),
                        thread(4, archivedAt = now),
                    ),
                source =
                    ThreadRowSource(
                        unreadByHex = mapOf("04" to 2),
                        lastByHex = emptyMap(),
                        firstByHex = emptyMap(),
                    ),
                now = now,
            )

        assertEquals(2, state.dormant.size)
        assertEquals(1, state.archived.size)
        // An archived thread keeps its own badge inside the Archived section.
        assertEquals(2, state.archived.single().unreadCount)
        assertEquals(
            9,
            ThreadUiDerivation.mainUnreadCount(
                listOf(ThreadUnreadCount(byteArrayOf(4), 2), ThreadUnreadCount(null, 9)),
            ),
        )
        assertEquals(0, ThreadUiDerivation.mainUnreadCount(listOf(ThreadUnreadCount(byteArrayOf(4), 2))))
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
    fun `a rooted thread with no messages yet has no replies hook`() {
        // Tapping "Thread from here" then canceling leaves a target with a
        // root but no reply; "0 replies" on Main would advertise a thread the
        // user never sent into.
        val sentInto = thread(1, rootMessageId = "root-1")
        val cancelled = thread(2, rootMessageId = "root-2")

        val hooks =
            ThreadUiDerivation.buildHookMap(
                listOf(sentInto, cancelled),
                countsByHex = mapOf("01" to 2),
                unreadByHex = emptyMap(),
            )

        assertEquals(
            ThreadHookUi(threadIdHex = "01", replyCount = 2, unreadCount = 0),
            hooks["root-1"],
        )
        assertNull("a message-less thread has nothing to count", hooks["root-2"])
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
    fun `header preserves every root signature verification state`() {
        (SignatureStatus.entries + null).forEach { status ->
            val root = message("root-1").copy(signatureStatus = status)
            val header =
                ThreadUiDerivation.deriveHeader(
                    thread(1, rootMessageId = root.id),
                    firstMessage = message("reply", replyToMessageId = root.id),
                    rootMessage = root,
                ) as ThreadHeaderUi.Root

            assertEquals(status, header.signatureStatus)
        }
    }

    @Test
    fun `aggregate maps key by hex and skip null-thread rows`() {
        val unread = ThreadUiDerivation.unreadByThread(listOf(ThreadUnreadCount(byteArrayOf(0x0a), 5), ThreadUnreadCount(null, 9)))
        assertEquals(mapOf("0a" to 5), unread)
        val counts = ThreadUiDerivation.countsByThread(listOf(ThreadMessageCount(byteArrayOf(0x0a), 7), ThreadMessageCount(null, 3)))
        assertEquals(mapOf("0a" to 7), counts)
    }
}
