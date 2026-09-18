package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.util.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-discovery retry contract for the python NomadNet backend.
 *
 * On a cold start the destination's identity is already known (loaded with the
 * stored destinations), so `resolveNodeIdentity` returns without sending a
 * path request. The route is therefore absent from `RNS.Transport.path_table`;
 * the first `RNS.Link` cannot go ACTIVE within the link budget, and the page
 * request would fail. The route does arrive (via peer announces over the
 * backbone) seconds later, but the backend must not give up.
 *
 * The kotlin backend handles this in `NativeNomadNetHandler.retryLinkEstablish-
 * ment`: expire the (absent) path, request a fresh one, poll
 * `Transport.has_path`, then rebuild the link. This test pins the same
 * contract on the python backend.
 *
 * The RNS transport and `RNS.Link` are `PyObject`s whose methods are native,
 * so the test overrides the seams (`testCreateNodeLink`, `testLinkStatus`,
 * `testTransportInvoke`, `testHasPath`, `testTeardownLink`, budget overrides)
 * and uses raw `PyObject.getInstance(...)` handles as identity tokens -
 * consistent with `PythonRnsNomadnetLinkLifecycleTest`. The route-wait is
 * driven at the transport boundary (real `expire_path` / `request_path` /
 * `has_path` ordering) rather than stubbed as a whole, so the test would fail
 * if the path operations were removed or called with the wrong destination.
 */
class PythonRnsNomadnetRouteRetryTest {

    private val destinationHex = "aabbcc"

    /** Nonzero address -> real PyObject instance (no native method called). */
    private fun raw(addr: Long): PyObject =
        checkNotNull(PyObject.getInstance(addr)) { "PyObject.getInstance($addr) returned null" }

    /** Transport-boundary driver: records op order and flips `has_path`
     *  after the path request, verifying the real discovery sequence. */
    private class TransportDriver(private val destination: ByteArray) {
        val ops = mutableListOf<String>()
        var hasPathCalls = 0
        var pathAppeared = false
        var forceRouteAbsent = false

        fun invoke(op: String, dest: ByteArray) {
            check(dest.contentEquals(destination)) { "path op called with wrong destination: $op" }
            ops += op
            // The route appears once a fresh path has been requested (unless
            // the test forces it absent).
            if (op == "request_path" && !forceRouteAbsent) pathAppeared = true
        }

        fun hasPath(dest: ByteArray): Boolean {
            check(dest.contentEquals(destination)) { "has_path called with wrong destination" }
            hasPathCalls += 1
            return pathAppeared
        }
    }

    @Test
    fun `when first link budget exhausts the backend requests a path and the retry link is cached`() =
        runTest {
            val runtime = mockk<PythonRnsRuntime>()
            val subject = PythonRnsNomadnet(runtime = runtime)
            val driver = TransportDriver(destinationHex.hexToBytes())

            subject.testLinkBudgetMs = 50
            subject.testPathWaitBudgetMs = 500
            val first = raw(0x21)
            val retry = raw(0x22)
            var linkCall = 0
            subject.testCreateNodeLink = {
                linkCall += 1
                if (linkCall == 1) first else retry
            }
            // First link never goes ACTIVE; the retry link is ACTIVE.
            subject.testLinkStatus = { link -> if (link === retry) 2L else 0L }
            subject.testTransportInvoke = { op, dest -> driver.invoke(op, dest) }
            subject.testHasPath = { dest -> driver.hasPath(dest) }
            subject.testTeardownLink = { }

            val result = subject.establishLink(
                destinationHex, raw(0x10), 60f, subject.testBeginRequest(),
            )

            // The route-wait must have driven the real discovery sequence
            // against the correct destination.
            assertEquals(
                "route discovery must expire the stale path before requesting",
                listOf("expire_path", "request_path"),
                driver.ops,
            )
            assertTrue("has_path must have been polled", driver.hasPathCalls > 0)
            // The retry must build a second RNS.Link and cache the active one.
            assertEquals("the retry must build a second RNS.Link", 2, linkCall)
            assertTrue("the returned link must be the retry link", result === retry)
            assertTrue(
                "the retry link must be cached for reuse",
                subject.nomadnetLinks[destinationHex] === retry,
            )
        }

    @Test
    fun `when the route never appears establishLink fails and caches nothing`() =
        runTest {
            val runtime = mockk<PythonRnsRuntime>()
            val subject = PythonRnsNomadnet(runtime = runtime)
            val driver = TransportDriver(destinationHex.hexToBytes())
            driver.forceRouteAbsent = true

            subject.testLinkBudgetMs = 50
            subject.testPathWaitBudgetMs = 100
            subject.testCreateNodeLink = { raw(0x21) }
            subject.testLinkStatus = { 0L } // never ACTIVE
            subject.testTransportInvoke = { op, dest -> driver.invoke(op, dest) }
            subject.testHasPath = { dest -> driver.hasPath(dest) }
            subject.testTeardownLink = { }

            val result = runCatching {
                subject.establishLink(
                    destinationHex, raw(0x10), 60f, subject.testBeginRequest(),
                )
            }
            assertTrue("must fail when the route never appears", result.isFailure)
            assertTrue(
                "failure message must mention the destination",
                result.exceptionOrNull()?.message?.contains(destinationHex) == true,
            )
            // Discovery was still requested (the fix's behaviour), but the
            // link was torn down and nothing cached.
            assertEquals(
                "route discovery must still be requested",
                listOf("expire_path", "request_path"),
                driver.ops,
            )
            assertTrue(
                "no link cached after a failure",
                subject.nomadnetLinks[destinationHex] == null,
            )
        }
}
