package network.columba.app.nomadnet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [buildNomadNetPersistPath]: reconstructing the full persist path
 * (with trailing backtick field block) from a bare page path and its link-field
 * tokens, so that restoring the persisted path reproduces the original request.
 */
class BuildNomadNetPersistPathTest {
    @Test
    fun `no field tokens yields the bare path unchanged`() {
        assertEquals("/page/index.mu", buildNomadNetPersistPath("/page/index.mu", emptyList()))
    }

    @Test
    fun `field tokens are appended as a pipe-joined backtick block`() {
        val tokens = listOf("cat=general", "thread=a-gentle-look-at-prns")
        assertEquals(
            "/page/forum/thread.mu`cat=general|thread=a-gentle-look-at-prns",
            buildNomadNetPersistPath("/page/forum/thread.mu", tokens),
        )
    }

    @Test
    fun `the reconstructed path round-trips through splitNomadNetPathFields`() {
        val tokens = listOf("cat=general", "thread=a-gentle-look-at-prns")
        val persistPath = buildNomadNetPersistPath("/page/forum/thread.mu", tokens)
        val (path, fields) = splitNomadNetPathFields(persistPath)
        assertEquals("/page/forum/thread.mu", path)
        assertEquals(tokens, fields)
    }
}
