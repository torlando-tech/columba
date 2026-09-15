package network.columba.app.rns.backend.py

import com.chaquo.python.PyObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Link-lifecycle contract for the python NomadNet backend.
 *
 * A page-image cancel (or a ViewModel teardown on tab-switch) must only stop
 * the in-flight request — it must NOT tear down the cached ACTIVE link. The
 * upstream python browser (reticulum nomadnet Browser.py `__load`) reuses a
 * per-destination link across requests, and the kotlin backend's
 * `cancelNomadnetPageRequest` mirrors that (it only sets a cancel flag). The
 * python backend must behave the same, so re-entering a site after a tab switch
 * reuses the warm link instead of re-establishing one from scratch.
 */
class PythonRnsNomadnetLinkLifecycleTest {
    private fun activeLink(): PyObject {
        val link = mockk<PyObject>()
        every { link["status"] } returns null
        every { link.callAttr("teardown") } returns mockk()
        return link
    }

    @Test
    fun `cancel preserves the cached active link for reuse`() =
        runTest {
            val subject = PythonRnsNomadnet(runtime = mockk())
            val link = activeLink()
            subject.nomadnetLinks["abc"] = link

            subject.cancelNomadnetPageRequest()

            // The cached link must survive the cancel so the next page/image
            // request to the same node reuses it instead of re-establishing.
            assertSame(
                "cancel must not evict the cached link",
                link,
                subject.nomadnetLinks["abc"],
            )
            verify(exactly = 0) { link.callAttr("teardown") }
        }

    @Test
    fun `a cancelled in-flight request still unwinds while the link is kept`() =
        runTest {
            val subject = PythonRnsNomadnet(runtime = mockk())
            val link = activeLink()
            subject.nomadnetLinks["abc"] = link

            subject.cancelNomadnetPageRequest()

            // The cancel still signals in-flight polling loops (status resets),
            // proving the in-flight request is interrupted even though the link
            // object is preserved for the next request.
            assertEquals("idle", subject.getNomadnetRequestStatus())
            assertNotNull(subject.nomadnetLinks["abc"])
        }
}
