package network.columba.app.detekt.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [NoRnsFacadeInPythonBackend].
 *
 * The rule scans the `:rns-backend-py/src/main/python/` filesystem tree from a
 * Kotlin-anchored `visitKtFile` — which `rule.lint(code)` can't exercise (no
 * real tree on disk). So these tests hit the extracted, pure
 * [NoRnsFacadeInPythonBackend.findRnsFacadeFiles] function directly with a
 * temp directory, which is the production code path the rule delegates to.
 */
class NoRnsFacadeInPythonBackendTest {
    @Test
    fun `flags rns_ prefixed files and ignores the legitimate slim-Python tree`(
        @TempDir tmp: File,
    ) {
        // The legitimate slim-Python tree — none of these should be flagged.
        File(tmp, "event_bridge.py").writeText("# the one authored file with logic")
        File(tmp, "usb_bridge.py").writeText("# forced interface adapter")
        File(tmp, "rnode_interface.py").writeText("# forced interface adapter")
        File(tmp, "ble_modules").mkdirs()
        File(tmp, "ble_modules/android_ble_interface.py").writeText("# forced adapter")
        // Forbidden facade files.
        File(tmp, "rns_api.py").writeText("# regression: a facade")
        File(tmp, "rns_wrapper.py").writeText("# regression: a facade")

        val flagged = NoRnsFacadeInPythonBackend.findRnsFacadeFiles(tmp)

        assertEquals(listOf("rns_api.py", "rns_wrapper.py"), flagged)
    }

    @Test
    fun `flags facade files nested anywhere under the python tree`(
        @TempDir tmp: File,
    ) {
        File(tmp, "patches/RNS").mkdirs()
        File(tmp, "patches/RNS/rns_internal.py").writeText("# regression nested deep")

        val flagged = NoRnsFacadeInPythonBackend.findRnsFacadeFiles(tmp)

        assertTrue(flagged.contains("rns_internal.py"))
    }

    @Test
    fun `clean slim-Python tree produces no findings`(
        @TempDir tmp: File,
    ) {
        File(tmp, "event_bridge.py").writeText("# clean")
        File(tmp, "jnius").mkdirs()
        File(tmp, "jnius/__init__.py").writeText("# chaquopy env stub")

        assertEquals(emptyList<String>(), NoRnsFacadeInPythonBackend.findRnsFacadeFiles(tmp))
    }

    @Test
    fun `missing python directory is not an error`() {
        val absent = File("/nonexistent/rns-backend-py/src/main/python")

        assertEquals(emptyList<String>(), NoRnsFacadeInPythonBackend.findRnsFacadeFiles(absent))
    }
}
