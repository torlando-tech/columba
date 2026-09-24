package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At-establishment auto-identify contract for the python NomadNet backend —
 * the core of the "identify saved nodes at link establishment" fix (the
 * Python equivalent of upstream `Browser.link_established`).
 *
 * Two entry points into the same `identifyIfFlagged` core, both probed here
 * without a native RNS runtime:
 *
 * - `setIdentifyOnConnectNodes` on an already-ACTIVE link (covers the
 *   first-launch window and the Node-Details toggle: the link predates the
 *   flag). Only *newly-added* nodes are (re)identified.
 * - `identifyIfFlagged` directly (the establishLink path): a link goes ACTIVE,
 *   and the backend identifies it if flagged. This is where the link-id-keyed
 *   dedup lives — a reused link doesn't re-send, a rebuilt link (new link_id)
 *   re-identifies.
 *
 * Also probed: an unflagged node is not identified, a non-active link is not
 * identified at flag time, an explicit `identifyNomadnetLink` after the
 * auto-identify reports already-identified (shared dedup set), and a failed
 * identify (no local identity) does not poison the dedup set.
 *
 * The harness mirrors [PythonRnsNomadnetLinkLifecycleTest]: `PyObject` cannot
 * be mocked on the JVM classpath, so `PyObject.getInstance(n)` provides raw
 * handle identity tokens. `testLinkIdHex` maps each handle to a stable
 * identityHashCode-derived key (same handle -> same key, distinct handles ->
 * distinct keys), standing in for a real `link.link_id`. `testIdentifyOnLink`
 * stands in for `link.callAttr("identify", identity)`.
 */
class PythonRnsNomadnetIdentifyOnConnectTest {
    private val runtime = mockk<PythonRnsRuntime>()
    private val subject = PythonRnsNomadnet(runtime = runtime)

    // RNS Link status constants (matches PythonRnsNomadnet's private LINK_*;
    // RNS Link.STATUS_ACTIVE == 2).
    private val linkActive = 2L

    private fun rawLinkHandle(addr: Long): PyObject =
        // Nonzero address -> a real PyObject instance; the address gives each
        // handle a distinct identity for the dedup-key seam.
        PyObject.getInstance(addr)

    /** Wire the seams a fresh test needs (seams are instance state). */
    private fun wireSeams() {
        // Stable link-identity key per handle (a stand-in for link.link_id hex).
        // System.identityHashCode is always safe (unlike PyObject.hashCode(),
        // which is native) and is stable per handle for its lifetime and unique
        // per handle, so distinct links map to distinct keys.
        subject.testLinkIdHex = { link -> "linkid:" + System.identityHashCode(link) }
        // identifyNomadnetLink calls requireRunning(); stub it as a no-op.
        every { runtime.requireRunning() } just io.mockk.Runs
        // A real local identity is required for the proof send.
        every { runtime.localIdentity } returns PyObject.getInstance(99L)
    }

    // ── set path: flag arrives while the link is already ACTIVE ──────────────

    @Test
    fun `flagging a node with an active link identifies it exactly once`() =
        runTest {
            wireSeams()
            val link = rawLinkHandle(1L)
            subject.nomadnetLinks["nodeA"] = link
            subject.testLinkStatus = { linkActive }

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            subject.setIdentifyOnConnectNodes(setOf("nodeA"))

            // The proof was sent once for the flagged, active link.
            assertEquals("auto-identify must send the proof once", 1, identifyCount)
            assertTrue("the link's identity must be recorded in the dedup set",
                subject.identifiedLinks.isNotEmpty())
        }

    @Test
    fun `re-flagging the same active link does not re-send the proof`() =
        runTest {
            wireSeams()
            val link = rawLinkHandle(1L)
            subject.nomadnetLinks["nodeA"] = link
            subject.testLinkStatus = { linkActive }

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            // First flag: sends the proof.
            subject.setIdentifyOnConnectNodes(setOf("nodeA"))
            assertEquals("first flag sends the proof", 1, identifyCount)

            // The user toggles it off and back on (or a re-sync of the same
            // set arrives): the link is the SAME active link, so no re-send.
            subject.setIdentifyOnConnectNodes(emptySet())
            subject.setIdentifyOnConnectNodes(setOf("nodeA"))
            assertEquals("re-flagging the same active link must not re-send", 1, identifyCount)
        }

    @Test
    fun `an unflagged node's active link is not identified`() =
        runTest {
            wireSeams()
            val link = rawLinkHandle(1L)
            subject.nomadnetLinks["nodeA"] = link
            subject.testLinkStatus = { linkActive }

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            // Flag a DIFFERENT node; nodeA's link stays unflagged.
            subject.setIdentifyOnConnectNodes(setOf("nodeB"))

            assertEquals("an unflagged node must not be auto-identified", 0, identifyCount)
        }

    @Test
    fun `a non-active link is not identified at flag time`() =
        runTest {
            wireSeams()
            val link = rawLinkHandle(1L)
            subject.nomadnetLinks["nodeA"] = link
            // The link is not yet ACTIVE (still establishing); the set path only
            // identifies ACTIVE links. The establishLink path (identifyIfFlagged)
            // fires later when it becomes active.
            subject.testLinkStatus = { 1L } // LINK_PENDING, not ACTIVE

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            subject.setIdentifyOnConnectNodes(setOf("nodeA"))

            assertEquals("a non-active link must not be identified", 0, identifyCount)
        }

    // ── establishLink path: a link goes ACTIVE (or is rebuilt) ────────────────

    @Test
    fun `a rebuilt link for the same destination re-identifies`() =
        runTest {
            wireSeams()
            subject.testLinkStatus = { linkActive }
            // Flag the node up front (the establishLink path checks the set).
            subject.setIdentifyOnConnectNodes(setOf("nodeA"))

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            // First link established + active: identifyIfFlagged sends the proof.
            val link1 = rawLinkHandle(1L)
            subject.identifyIfFlagged("nodeA", link1)
            assertEquals("first link sends the proof", 1, identifyCount)

            // The link drops and a NEW link (new link_id) is established for the
            // same destination. A fresh handshake means a fresh identification:
            // identifyIfFlagged is called again with the new link.
            val link2 = rawLinkHandle(2L)
            subject.identifyIfFlagged("nodeA", link2)
            assertEquals("a rebuilt link must re-identify", 2, identifyCount)
        }

    @Test
    fun `reusing the same active link does not re-identify`() =
        runTest {
            wireSeams()
            subject.testLinkStatus = { linkActive }
            subject.setIdentifyOnConnectNodes(setOf("nodeA"))

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            // establishLink reuses the warm link and re-calls identifyIfFlagged
            // with the SAME link (link_id unchanged): the dedup set must
            // suppress the re-send.
            val link = rawLinkHandle(1L)
            subject.identifyIfFlagged("nodeA", link)
            assertEquals("first call sends the proof", 1, identifyCount)
            subject.identifyIfFlagged("nodeA", link)
            assertEquals("reusing the same link must not re-send", 1, identifyCount)
        }

    // ── explicit identify (the dialog button) shares the dedup set ────────────

    @Test
    fun `explicit identify after auto-identify reports already-identified`() =
        runTest {
            wireSeams()
            val link = rawLinkHandle(1L)
            subject.nomadnetLinks["nodeA"] = link
            subject.testLinkStatus = { linkActive }

            var identifyCount = 0
            subject.testIdentifyOnLink = { _, _ -> identifyCount++ }

            // Auto-identify at establishment sends the proof.
            subject.setIdentifyOnConnectNodes(setOf("nodeA"))
            assertEquals("auto-identify sends the proof", 1, identifyCount)

            // An explicit identify on the same active link must not re-send:
            // it shares the identifiedLinks dedup set and reports
            // already-identified (true).
            val alreadyIdentified = subject.identifyNomadnetLink("nodeA")
            assertTrue("explicit identify after auto-identify should succeed", alreadyIdentified.isSuccess)
            assertEquals("explicit identify should report already-identified", true, alreadyIdentified.getOrNull())
            assertEquals("explicit identify must not re-send the proof", 1, identifyCount)
        }

    @Test
    fun `a failed explicit identify does not poison the dedup set`() =
        runTest {
            wireSeams()
            val link = rawLinkHandle(1L)
            subject.nomadnetLinks["nodeA"] = link
            subject.testLinkStatus = { linkActive }

            // No local identity available: the proof send fails (no identity).
            // The dedup key must be removed so a later retry can send it.
            every { runtime.localIdentity } returns null

            val first = subject.identifyNomadnetLink("nodeA")
            assertTrue("no local identity -> identify fails", first.isFailure)
            assertTrue("a failed identify must not record the link as identified",
                subject.identifiedLinks.isEmpty())
        }
}
