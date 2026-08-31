package network.columba.app.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import network.columba.app.ui.screens.tryReserveGroupBudgetLocked

/**
 * Regression tests for the COLUMBA-4A correction (Greptile P1 on PR #1113,
 * head 4fda09e2): "Send reads still spike heap".
 *
 * The approved send path reads composer picks up to
 * [FileUtils.MAX_TOTAL_ATTACHMENT_SIZE] (32MB) into memory. At 4fda09e2 a
 * successful near-32MB read temporarily held BOTH the growing
 * ByteArrayOutputStream AND a full [ByteArray] copy returned by
 * toByteArray() (~2x per-file bytes), and a multi-file selection retained
 * every individually-valid array until the send-time aggregate check.
 *
 * These tests pin the corrected contract at the deterministic seams:
 *  1. [readBounded] (via the public readers) returns the accumulator itself
 *     — exact byte count, correct content, for every size shape (small,
 *     doubling-growth, exactly-at-cap, seeded-capacity) — while the
 *     unknown-size abort stays bounded to cap + one buffer of slack.
 *  2. The composer group-budget accounting the picker performs
 *     (MessagingScreen.filePickerLauncher, using
 *     [FileUtils.wouldExceedSizeLimit] before/at each add) provably bounds
 *     the retained working set at MAX_TOTAL_ATTACHMENT_SIZE, so the
 *     multi-file retention window the finding named cannot approach an
 *     unbounded multiple of the ceiling.
 */
// Suppress NoRelaxedMocks for Android framework classes (Context, ContentResolver, Cursor, Uri)
// which have many methods that are not relevant to these tests
@Suppress("NoRelaxedMocks")
class FileUtilsSendHeapSpikeTest {
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockContentResolver = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun stubPick(
        uri: Uri,
        filename: String,
        statSize: Long,
        streamProvider: () -> InputStream,
    ) {
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockContentResolver.query(uri, null, null, null, null) } returns mockCursor
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns filename
        every { mockContentResolver.getType(uri) } returns "application/octet-stream"
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)
        every { mockPfd.statSize } returns statSize
        every { mockContentResolver.openFileDescriptor(uri, "r") } returns mockPfd
        every { mockContentResolver.openInputStream(uri) } answers { streamProvider() }
    }

    // ---------- 1. readBounded heap shape: single buffer, exact result ----------

    @Test
    fun `send read returns exact bytes with no second-copy corruption across size shapes`() {
        // The corrected readBounded hands back its accumulator (no
        // toByteArray() second full copy). Pin correctness across the shapes
        // that exercise seeding and doubling growth: tiny, mid-size,
        // doubling boundaries past the 64KB initial buffer, and exactly
        // MAX_SINGLE_FILE_SIZE on the generic reader.
        val shapes =
            intArrayOf(
                1,
                64 * 1024, // exactly the initial buffer
                64 * 1024 + 1, // first growth step
                100 * 1024, // mid doubling
                FileUtils.MAX_SINGLE_FILE_SIZE, // exactly the generic cap
            )
        for (size in shapes) {
            val payload = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }
            val testUri = mockk<Uri>(relaxed = true)
            stubPick(testUri, "shape-$size.bin", -1L) {
                ChunkedStream(payload, 7 * 1024) // awkward chunk sizes
            }

            val result = FileUtils.readFileFromUriWithResult(mockContext, testUri)

            assertTrue(
                "Expected Success for ${size}B unknown-size stream, got: " +
                    result::class.java.simpleName,
                result is FileUtils.FileReadResult.Success,
            )
            val attachment = (result as FileUtils.FileReadResult.Success).attachment
            assertEquals(size, attachment.sizeBytes)
            assertEquals(size, attachment.data.size)
            assertTrue(
                "Bytes must survive the grow-into-single-buffer read exactly (size=$size)",
                payload.contentEquals(attachment.data),
            )
        }
    }

    @Test
    fun `seeded-capacity send read of a known-size file returns exact bytes`() {
        // Known provider size seeds the accumulator exactly (single
        // allocation, no doubling, no copy). 3MB > 512KB so it must go
        // through the send-path reader.
        val size = 3 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 17 + 3) % 253).toByte() }
        val testUri = mockk<Uri>(relaxed = true)
        stubPick(testUri, "seeded-3mb.bin", size.toLong()) {
            ChunkedStream(payload, 911 * 1024)
        }

        val result = FileUtils.readFileForSendWithResult(mockContext, testUri)

        assertTrue(
            "Expected Success for seeded 3MB send pick, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.Success,
        )
        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals(size, attachment.sizeBytes)
        assertTrue(payload.contentEquals(attachment.data))
    }

    @Test
    fun `unknown-size send read just over the 32MB cap aborts near the cap`() {
        // Heap-shape correction must NOT weaken the OOM guard: statSize -1
        // over a ~33MB lazy stream must be refused with the read stopped at
        // cap + one buffer of slack, never returning a huge array.
        val streamSize = FileUtils.MAX_TOTAL_ATTACHMENT_SIZE + 1024 * 1024
        val testUri = mockk<Uri>(relaxed = true)
        val counting = CountingLazyStream(streamSize)
        stubPick(testUri, "just-over-32mb.bin", -1L) { counting }

        val result = FileUtils.readFileForSendWithResult(mockContext, testUri)

        assertTrue(
            "Expected FileTooLarge for unknown-size ${streamSize}B send pick, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.FileTooLarge,
        )
        assertTrue(
            "Bounded send read must abort near the cap, but consumed ${counting.totalRead}B " +
                "of a ${streamSize}B stream (cap ${FileUtils.MAX_TOTAL_ATTACHMENT_SIZE})",
            counting.totalRead <= FileUtils.MAX_TOTAL_ATTACHMENT_SIZE + MAX_SLACK_BYTES,
        )
    }

    @Test
    fun `understated-size send read grows the accumulator never past the cap`() {
        // Provider understates statSize below the 64KB initial buffer while
        // the stream is ~2MB: the accumulator must seed small, grow by
        // doubling, and still return exact bytes on the send path.
        val size = 2 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 13 + 11) % 251).toByte() }
        val testUri = mockk<Uri>(relaxed = true)
        stubPick(testUri, "understated-2mb.bin", 100L) {
            ChunkedStream(payload, 200 * 1024)
        }

        val result = FileUtils.readFileForSendWithResult(mockContext, testUri)

        assertTrue(
            "Expected Success for understated 2MB send pick, got: " +
                result::class.java.simpleName,
            result is FileUtils.FileReadResult.Success,
        )
        val attachment = (result as FileUtils.FileReadResult.Success).attachment
        assertEquals(size, attachment.sizeBytes)
        assertTrue(payload.contentEquals(attachment.data))
    }

    // ---------- 2. Group-budget accounting bounds the retained working set ----------

    /**
     * Mirrors the exact accounting MessagingScreen.filePickerLauncher
     * performs per pick (pre-check on known size, then exact post-read
     * accounting with [FileUtils.wouldExceedSizeLimit] before adding).
     * [size] is the provider statSize, or -1 for unknown — in which case
     * the bounded reader may hand back up to the full per-file cap and the
     * simulation conservatively retains that worst case. Asserts at every
     * step that the retained working set never exceeds the group ceiling.
     */
    private fun simulateGroupBudget(sizes: List<Long>): Int {
        var retained = 0
        for (size in sizes) {
            // Skip (no allocation, no retention) when the per-file cap
            // refuses the pick outright, or when a KNOWN size already busts
            // the remaining group budget before any read happens.
            val refusedBeforeRead =
                size > FileUtils.MAX_TOTAL_ATTACHMENT_SIZE ||
                    (size >= 1 && FileUtils.wouldExceedSizeLimit(retained, size.toInt()))
            if (!refusedBeforeRead) {
                // Exact post-read accounting: known size retains exactly; an
                // unknown-size bounded read retains at most the per-file cap.
                val retainedBytes =
                    if (size >= 1) size.toInt() else FileUtils.MAX_TOTAL_ATTACHMENT_SIZE
                if (!FileUtils.wouldExceedSizeLimit(retained, retainedBytes)) {
                    retained += retainedBytes
                }
                assertTrue(
                    "Retained attachment working set must never exceed the group cap " +
                        "(was $retained after a ${size}B pick)",
                    retained <= FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                )
            }
        }
        return retained
    }

    @Test
    fun `group budget accounting retains every file that fits and refuses the first that does not`() {
        // 20 files x 1MB all fit the 32MB group budget.
        val retained = simulateGroupBudget(List(20) { 1024L * 1024 })
        assertEquals(20 * 1024 * 1024, retained)

        // 30 files x 1.5MB = 45MB: accounting keeps the retained total at or
        // below 32MB no matter how many individually-valid picks arrive.
        val many = simulateGroupBudget(List(30) { 1536L * 1024 })
        assertTrue(
            "Retained total must stay <= group cap, was $many",
            many <= FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
        )
    }

    @Test
    fun `group budget accounting with multiple near-cap files bounds the retained set`() {
        // The exact Greptile scenario: several individually-valid ~30MB
        // picks. The first attaches; every later near-cap pick is refused by
        // the group accounting BEFORE its read is retained, so the composer
        // working set is bounded at ~1x cap instead of N x cap.
        val near = 30L * 1024 * 1024
        val retained = simulateGroupBudget(List(5) { near })
        assertEquals(near.toInt(), retained)
    }

    @Test
    fun `group budget accounting with unknown sizes bounds the retained set`() {
        // statSize -1 picks bypass the pre-check; the per-file bounded read
        // plus exact post-read accounting still bound the retained total:
        // even four consecutive unknown-size picks retain at most 1x cap.
        val retained = simulateGroupBudget(List(4) { -1L })
        assertEquals(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE, retained)
        // And mixed known/unknown where known files consume the budget:
        // 30MB fits; the second 30MB is pre-check-skipped; the 1MB fits
        // (31MB total); the unknown-size pick (worst case 32MB) does not.
        val mixed =
            simulateGroupBudget(
                listOf(
                    30L * 1024 * 1024,
                    30L * 1024 * 1024,
                    1L * 1024 * 1024,
                    -1L,
                ),
            )
        assertEquals(31 * 1024 * 1024, mixed)
    }

    // ---------- 3. F1: near-cap trim copy must not double the heap ----------
    // Greptile P1 "Trim copy doubles heap" at 51603ecb: `data.copyOf(total)`
    // allocated a near-file-sized result while the full accumulator stayed
    // live (~63MB peak on the 32MB send path). The corrected readBounded
    // rewinds a verified-restartable provider and fills ONE exact array
    // AFTER dropping the accumulator. The heapObserver seam reports every
    // accumulator allocation/release so the live-bytes shape is asserted
    // deterministically (no GC-timing assumptions).

    @Test
    fun `near-cap unknown-size read releases the accumulator before the final allocation`() {
        // Unknown-size (~31.5MB, just below the 32MB cap) stream: growth
        // caps the accumulator at 32MB, EOF lands at 31.5MB. The old code
        // trim-copied HERE, holding 32MB + 31.5MB at once. The fix rewinds:
        // the final event pair MUST be release(accumulator) followed by
        // alloc(exact), with only the 64KB scan buffer still live in
        // between — never two near-full arrays.
        val size = FileUtils.MAX_TOTAL_ATTACHMENT_SIZE - 512 * 1024
        val payload = ByteArray(size) { ((it * 7 + 13) % 251).toByte() }
        val events = mutableListOf<Pair<Boolean, Int>>()
        var live = 0
        var liveBeforeFinalAlloc = -1

        val result =
            readBounded(
                ByteArrayInputStream(payload),
                FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                reopen = { ByteArrayInputStream(payload) },
                heapObserver =
                    ReadBoundedHeapObserver { released, sizeBytes ->
                        if (released) {
                            live -= sizeBytes
                        } else {
                            // Remember live bytes at EVERY allocation — the
                            // last one is the final result array.
                            liveBeforeFinalAlloc = live
                            live += sizeBytes
                        }
                        events += released to sizeBytes
                    },
            )

        assertNotNull(result)
        assertEquals(size, result!!.size)
        assertTrue(payload.contentEquals(result))
        val lastAlloc = events.indexOfLast { !it.first }
        // The last allocation is the exact-sized result...
        assertEquals(size, events[lastAlloc].second)
        // ...immediately preceded by the accumulator release (32MB cap),
        // i.e. the two near-full arrays were NEVER live at the same time.
        assertEquals(true to FileUtils.MAX_TOTAL_ATTACHMENT_SIZE, events[lastAlloc - 1])
        assertEquals(
            "Live accumulator bytes when the final array was allocated must be just the " +
                "64KB scan buffer — the accumulator must have been released first " +
                "(events: $events)",
            FILE_READ_BUF,
            liveBeforeFinalAlloc,
        )
    }

    // Greptile P1 "Accumulator remains live during rewind" at a69311dd:
    // the release ABOVE is a synthetic event — the accumulator array stayed
    // REACHABLE through the `pass` local across the fillExactFromReopen
    // allocation, so the ~2x near-cap heap peak the PR exists to eliminate
    // survived. The corrected rewind DRAINS the accumulator holder before
    // allocating. Pin that with real GC evidence, not observer events: hold
    // only a WeakReference to the accumulator (captured at the seam while
    // live), and at the point immediately before the exact allocation force
    // a GC and require the accumulator to be unreachable — while a control
    // garbage array in the same frame proves the GC actually ran.
    @Test
    fun `near-cap rewind accumulator is garbage at the point of the exact allocation`() {
        val size = FileUtils.MAX_TOTAL_ATTACHMENT_SIZE - 512 * 1024
        val payload = ByteArray(size) { ((it * 7 + 13) % 251).toByte() }

        var accumulatorRef: WeakReference<ByteArray>? = null
        var accumulatorCollectedAtAllocPoint: Boolean? = null
        var controlCollectedAtAllocPoint: Boolean? = null

        val probe =
            object : RewindReachabilityProbe {
                override fun onAccumulatorReady(accumulator: ByteArray) {
                    // Weak handle ONLY — the test must never keep the
                    // accumulator strongly reachable itself.
                    accumulatorRef = WeakReference(accumulator)
                }

                override fun onBeforeExactAllocation() {
                    var control: ByteArray? = ByteArray(8 * 1024)
                    val controlRef = WeakReference(control)
                    control = null
                    // Force real collections, then observe reachability at
                    // the exact point the result array is about to be
                    // allocated (the allocation happens inside this very
                    // call stack, right after this method returns).
                    repeat(4) {
                        System.gc()
                        Thread.sleep(20)
                    }
                    controlCollectedAtAllocPoint = controlRef.get() == null
                    accumulatorCollectedAtAllocPoint = accumulatorRef?.get() == null
                }
            }

        val result =
            readBounded(
                ByteArrayInputStream(payload),
                FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                reopen = { ByteArrayInputStream(payload) },
                rewindProbe = probe,
            )

        assertNotNull(result)
        assertEquals(size, result!!.size)
        assertTrue(payload.contentEquals(result))
        // The seams must have fired on the rewind path at all.
        assertNotNull(accumulatorRef)
        assertEquals(true, accumulatorCollectedAtAllocPoint)
        // GC-collaboration guard: if the control array survived, no
        // collection happened and the reachability assertion above is
        // meaningless — fail loudly instead of passing vacuously.
        assertEquals(
            "Explicit GC did not collect control garbage — the reachability " +
                "evidence below would be vacuous on this JVM configuration",
            true,
            controlCollectedAtAllocPoint,
        )
    }

    @Test
    fun `exact-seed known-size send read remains copy-free`() {
        // Heap-shape contract that must NOT regress: a plausible known
        // statSize seeds the accumulator exactly and the accumulator itself
        // is returned — one accumulator allocation, zero releases, no trim.
        val size = 3 * 1024 * 1024
        val payload = ByteArray(size) { ((it * 11 + 5) % 251).toByte() }
        val events = mutableListOf<Pair<Boolean, Int>>()

        val result =
            readBounded(
                ByteArrayInputStream(payload),
                FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                initialCapacity = size,
                reopen = { ByteArrayInputStream(payload) },
                heapObserver =
                    ReadBoundedHeapObserver { released, sizeBytes ->
                        events += released to sizeBytes
                    },
            )

        assertNotNull(result)
        assertTrue(payload.contentEquals(result))
        val accumulatorEvents = events.filter { it.second != FILE_READ_BUF }
        assertEquals(
            "Exact-seed read must allocate the accumulator once and never grow/trim it " +
                "(events: $events)",
            listOf(false to size),
            accumulatorEvents,
        )
    }

    @Test
    fun `near-cap read on a non-restartable provider falls back to a correct trim`() {
        // The rewind is only taken when a fresh open provably restarts the
        // same content. A provider whose re-opened stream differs (e.g. a
        // regenerating/streamed source) must still return the exact
        // first-pass bytes via the bounded trim fallback — correctness over
        // the fast path.
        val size = TRIM_THRESHOLD + 512 * 1024
        val payload = ByteArray(size) { ((it * 3 + 7) % 251).toByte() }
        val mutated = payload.clone().also { it[0] = (it[0].toInt() + 1).toByte() }

        val result =
            readBounded(
                ByteArrayInputStream(payload),
                FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                reopen = { ByteArrayInputStream(mutated) },
            )

        assertNotNull(result)
        assertEquals(size, result!!.size)
        assertTrue(payload.contentEquals(result))
    }

    @Test
    fun `near-cap read with no reopen capability still returns exact bytes`() {
        // reopen = null (callers without a re-openable source): the trim
        // fallback must stay correct — never weaker.
        val size = TRIM_THRESHOLD + 1024
        val payload = ByteArray(size) { ((it * 5 + 11) % 249).toByte() }

        val result =
            readBounded(
                ByteArrayInputStream(payload),
                FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
            )

        assertNotNull(result)
        assertTrue(payload.contentEquals(result))
    }

    @Test
    fun `rewind path aborts an over-cap stream without allocating past the cap`() {
        // The OOM guard must survive the rewind rewrite on EVERY branch:
        // a 33MB unknown-size stream on the 32MB send bound must be refused
        // (null) with consumption bounded at cap + one buffer.
        val streamSize = FileUtils.MAX_TOTAL_ATTACHMENT_SIZE + 1024 * 1024
        val counting = CountingLazyStream(streamSize)

        val result =
            readBounded(
                counting,
                FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
                reopen = { CountingLazyStream(streamSize) },
            )

        assertNull(result)
        assertTrue(
            "Rewind-aware bounded read must abort near the cap, but consumed " +
                "${counting.totalRead}B of a ${streamSize}B stream",
            counting.totalRead <= FileUtils.MAX_TOTAL_ATTACHMENT_SIZE + MAX_SLACK_BYTES,
        )
    }

    // ---------- 4. F2: overlapping picker callbacks share ONE budget ----------
    // Greptile P1 "Picker budgets race" at 51603ecb: each picker callback
    // snapshotted groupBudgetBase at entry and kept a callback-local
    // groupBudgetUsed, so two overlapping batches could jointly retain
    // more than MAX_TOTAL_ATTACHMENT_SIZE in the composer. The fix routes
    // every check-and-add through tryReserveGroupBudgetLocked, which
    // re-reads the LIVE retained total inside a shared mutex.

    @Test
    fun `overlapping picker batches sharing one budget never exceed the group cap`() =
        runTest {
            // Stand-in for viewModel.selectedFileAttachments: the live
            // retained byte totals. 24 concurrent "picks" interleave
            // through the shared mutex exactly like two picker batches do.
            val mutex = Mutex()
            val composer = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val pick = 2 * 1024 * 1024

            val accepted =
                coroutineScope {
                    (1..24).map {
                        async {
                            tryReserveGroupBudgetLocked(
                                mutex = mutex,
                                currentTotalBytes = { composer.sum() },
                                newAttachmentBytes = pick,
                            ) {
                                composer.add(pick)
                            }
                        }
                    }.awaitAll()
                }

            val retained = composer.sum()
            assertTrue(
                "Retained composer working set must never exceed the group cap, was $retained",
                retained <= FileUtils.MAX_TOTAL_ATTACHMENT_SIZE,
            )
            // 16 x 2MB exactly fills the 32MB cap; the other 8 must be
            // refused — the stale-snapshot design could accept all 24 from
            // each batch's own point of view.
            assertEquals(16, accepted.count { it })
            assertEquals(8, accepted.count { !it })
            assertEquals(accepted.count { it } * pick, retained)
        }

    @Test
    fun `budget reservation sees adds committed by an earlier overlapping batch`() =
        runTest {
            // The race Greptile named: batch B's read finished while batch
            // A already added 30MB. B's check MUST see A's committed 30MB
            // (live total), not a stale entry-time snapshot of 0, and
            // refuse the second near-cap 30MB pick.
            val mutex = Mutex()
            val composer = mutableListOf<Int>()

            val first =
                tryReserveGroupBudgetLocked(mutex, { composer.sum() }, 30 * 1024 * 1024) {
                    composer.add(30 * 1024 * 1024)
                }
            val second =
                tryReserveGroupBudgetLocked(mutex, { composer.sum() }, 30 * 1024 * 1024) {
                    composer.add(30 * 1024 * 1024)
                }

            assertTrue(first)
            assertTrue("Second near-cap pick must be refused against the LIVE composer total", !second)
            assertEquals(30 * 1024 * 1024, composer.sum())

            // Room for exactly 2MB more (32MB cap): accepted, UX for
            // small follow-up picks preserved.
            val third =
                tryReserveGroupBudgetLocked(mutex, { composer.sum() }, 2 * 1024 * 1024) {
                    composer.add(2 * 1024 * 1024)
                }
            assertTrue(third)
            assertEquals(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE, composer.sum())
        }

    @Test
    fun `refused reservation never commits to the composer`() =
        runTest {
            // FileTooLarge-adjacent semantics: a rejected reservation must
            // not touch the composer at all (no half-added attachment).
            val mutex = Mutex()
            val composer = mutableListOf<Int>()

            val accepted =
                tryReserveGroupBudgetLocked(
                    mutex,
                    { FileUtils.MAX_TOTAL_ATTACHMENT_SIZE },
                    1,
                ) {
                    composer.add(1)
                }

            assertTrue(!accepted)
            assertTrue(composer.isEmpty())
        }

    /** Allowed slack beyond the cap for one read buffer before aborting. */
    private companion object {
        const val MAX_SLACK_BYTES = 64 * 1024

        /** Mirrors FileUtils' private TRIM_FREE_RETAIN_THRESHOLD (4MB). */
        const val TRIM_THRESHOLD = 4 * 1024 * 1024

        /** Mirrors FileUtils' private FILE_READ_BUFFER_SIZE (64KB). */
        const val FILE_READ_BUF = 64 * 1024
    }

    /** Serves [data] in fixed-size chunks (exercises growth boundaries). */
    private class ChunkedStream(
        private val data: ByteArray,
        private val chunk: Int,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            data.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }

    /**
     * InputStream that serves [size] bytes with zero upfront allocation and
     * counts every byte actually consumed, so tests can assert the read was
     * aborted near the cap instead of pulling the whole stream into memory.
     */
    private class CountingLazyStream(size: Int) : InputStream() {
        @Volatile
        var totalRead: Int = 0
            private set

        private var remaining = size

        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            totalRead++
            return 0
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len, remaining)
            b.fill(0, off, off + toRead)
            remaining -= toRead
            totalRead += toRead
            return toRead
        }
    }
}
