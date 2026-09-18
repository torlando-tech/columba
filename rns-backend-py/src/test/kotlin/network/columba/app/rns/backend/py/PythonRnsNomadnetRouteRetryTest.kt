package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-discovery retry contract for the python NomadNet backend.
 *
 * On a cold start the destination's identity is already known (loaded with the
 * stored destinations), so `resolveNodeIdentity` returns without sending a
 * path request. The route is therefore absent from `RNS.Transport.path_table`;
 * the first `RNS.Link` cannot go ACTIVE within the 1/3-timeout budget, and the
 * page request fails. The route does arrive (via peer announces over the
 * backbone) seconds later, but the backend has already given up.
 *
 * The kotlin backend handles this in `NativeNomadNetHandler.retryLinkEstablish-
 * ment`: expire the (absent) path, request a fresh one, poll
 * `Transport.has_path`, then rebuild the link. This test pins the same
 * contract on the python backend.
 *
 * The RNS transport and `RNS.Link` are `PyObject`s whose methods are native,
 * so the test overrides the seams (`testCreateNodeLink`, `testLinkStatus`,
 * `testRouteWaiter`) and uses raw `PyObject.getInstance(...)` handles as
 * identity tokens - consistent with `PythonRnsNomadnetLinkLifecycleTest`.
 */
class PythonRnsNomadnetRouteRetryTest {

    /** Nonzero address -> real PyObject instance (no native method called). */
    private fun raw(addr: Long): PyObject =
        checkNotNull(PyObject.getInstance(addr)) { "PyObject.getInstance($addr) returned null" }

    @Test
    fun `when first link budget exhausts and route appears the retry link is returned`() =
        runTest {
            val runtime = mockk<PythonRnsRuntime>()
            val subject = PythonRnsNomadnet(runtime = runtime)
            subject.testLinkBudgetMs = 50
            subject.testPathWaitBudgetMs = 100

            // First createNodeLink -> link1 (never ACTIVE); second -> link2 (ACTIVE).
            val link1 = raw(0x21)
            val link2 = raw(0x22)
            var linkCalls = 0
            subject.testCreateNodeLink = {
                linkCalls += 1
                if (linkCalls == 1) link1 else link2
            }
            subject.testLinkStatus = { link ->
                // Identity match (===): PyObject.equals is native.
                if (link === link1) 0L
                else if (link === link2) 2L
                else 0L
            }
            // The route appears after the first link budget.
            subject.testRouteWaiter = { _, _, _ -> true }
            // Avoid the native callAttr("teardown") on the raw link1 handle.
            subject.testTeardownLink = { }

            val result = subject.establishLink(
                "aabbcc", raw(0x10), 60f, subject.testBeginRequest(),
            )

            assertEquals("the retry must build a second RNS.Link", 2, linkCalls)
            // Identity check (not assertEquals): PyObject.equals/toString are native.
            assertTrue("the returned link must be the retry link", result === link2)
            assertTrue("the retry link must be cached for reuse", subject.nomadnetLinks["aabbcc"] === link2)
        }

    @Test
    fun `when the route never appears establishLink still fails and caches nothing`() =
        runTest {
            val runtime = mockk<PythonRnsRuntime>()
            val subject = PythonRnsNomadnet(runtime = runtime)
            subject.testLinkBudgetMs = 50
            subject.testPathWaitBudgetMs = 50

            val neverActive = raw(0x21)
            var linkCalls = 0
            subject.testCreateNodeLink = {
                linkCalls += 1
                neverActive
            }
            subject.testLinkStatus = { 0L } // never ACTIVE
            subject.testRouteWaiter = { _, _, _ -> false } // route never appears
            subject.testTeardownLink = { }

            val result = runCatching {
                subject.establishLink(
                    "aabbcc", raw(0x10), 60f, subject.testBeginRequest(),
                )
            }
            assertTrue("must fail when the route never appears", result.isFailure)
            assertTrue(
                "failure message must mention the destination",
                result.exceptionOrNull()?.message?.contains("aabbcc") == true,
            )
            // No second link is built when there is no route to wait for.
            assertEquals("only one link is built when the route never appears", 1, linkCalls)
            assertTrue("no link cached after a failure", subject.nomadnetLinks["aabbcc"] == null)
        }
}
