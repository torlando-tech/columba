package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link-lifecycle contract for the python NomadNet backend.
 *
 * A page/image cancel (or a ViewModel teardown on tab-switch) must only stop
 * the in-flight request - it must NOT tear down the cached ACTIVE link. The
 * upstream python browser (reticulum nomadnet Browser.py `__load`) reuses a
 * per-destination link across requests, and the kotlin backend's
 * `cancelNomadnetPageRequest` mirrors that (it only sets a cancel flag). The
 * python backend must behave the same, so re-entering a site after a tab switch
 * reuses the warm link instead of re-establishing one from scratch (which is
 * what produced the "failed to establish link to nomadnet node" error on the
 * report).
 *
 * The cached link is a `PyObject` handle. `com.chaquo.python.PyObject` cannot
 * be mocked on the JVM unit-test classpath (its methods - `toString`,
 * `callAttr`, `get` - are native), but [PyObject.getInstance] returns a raw
 * handle for a nonzero address without starting the interpreter or calling any
 * native method, so it is used here purely as an identity token to prove the
 * cached-link map survives a cancel.
 */
class PythonRnsNomadnetLinkLifecycleTest {
    private fun rawLinkHandle(): PyObject =
        // Nonzero address -> a real PyObject instance (addr=1); zero -> null.
        PyObject.getInstance(1L)

    @Test
    fun `cancel preserves the cached active link for reuse`() =
        runTest {
            val subject = PythonRnsNomadnet(runtime = mockk())
            val link = rawLinkHandle()
            subject.nomadnetLinks["abc"] = link

            subject.cancelNomadnetPageRequest()

            // The cached link must survive the cancel so the next page/image
            // request to the same node reuses it instead of re-establishing.
            // (Boolean identity check, not assertSame, because a failing
            // assertSame would call PyObject.toString(), which is native and
            // unavailable on the JVM test classpath.)
            assertTrue(
                "cancel must not evict the cached link",
                subject.nomadnetLinks["abc"] === link,
            )
        }

    @Test
    fun `a cancelled in-flight request still unwinds while the link is kept`() =
        runTest {
            val subject = PythonRnsNomadnet(runtime = mockk())
            subject.nomadnetLinks["abc"] = rawLinkHandle()

            subject.cancelNomadnetPageRequest()

            // The cancel still signals in-flight polling loops (status resets to
            // idle), proving the in-flight request is interrupted even though the
            // link object is preserved for the next request.
            assertEquals("idle", subject.getNomadnetRequestStatus())
            assertNotNull(subject.nomadnetLinks["abc"])
        }

    @Test
    fun `a concurrent sibling request does not cancel an earlier in-flight request`() {
        // Regression for Greptile's "concurrent requests cancel each other":
        // the browser runs partial-page and image requests CONCURRENTLY
        // (PartialManager + PageImageLoader share this backend), so a sibling
        // request starting must never cancel a legitimate in-flight request.
        // A naive "newer generation supersedes older" rule (the prior fix)
        // cancels the older one as soon as the sibling begins - that is wrong.
        val subject = PythonRnsNomadnet(runtime = mockk())

        // An earlier request is in flight.
        val older = subject.testBeginRequest()
        // A concurrent sibling request begins while it is still in flight.
        val sibling = subject.testBeginRequest()
        assertTrue("sanity: sibling claims a newer generation", sibling > older)

        // The earlier request must still be LIVE - the sibling did not cancel it.
        assertFalse(
            "a concurrent sibling request must not cancel an in-flight request",
            subject.testIsCancelled(older),
        )
    }

    @Test
    fun `an explicit cancel cancels in-flight requests but not one begun after`() =
        runTest {
            val subject = PythonRnsNomadnet(runtime = mockk())

            // An in-flight request, then an explicit cancel targets it.
            val inFlight = subject.testBeginRequest()
            subject.cancelNomadnetPageRequest()
            assertTrue("a pre-cancel in-flight request is cancelled", subject.testIsCancelled(inFlight))

            // A later, unrelated request begins AFTER the cancel.
            val later = subject.testBeginRequest()
            assertFalse(
                "a request begun after the cancel must not inherit the stale cancel",
                subject.testIsCancelled(later),
            )
            // And the pre-cancel request is NOT revived by the later request.
            assertTrue(
                "a cancelled request is not revived by a later replacement",
                subject.testIsCancelled(inFlight),
            )
        }
}
